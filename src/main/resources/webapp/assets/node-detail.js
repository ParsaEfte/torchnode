/* Node evidence loading is independent of discovery scanning and never starts probes. */
(function (global) {
    const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
    const list = value => Array.isArray(value) ? value : [];
    class NodeRequestError extends Error {
        constructor(message, kind = 'REQUEST_FAILED') { super(message); this.kind = kind; }
    }
    function normalize(data) {
        if (!object(data) || !object(data.node) || typeof data.complete !== 'boolean' || !Array.isArray(data.diagnostics))
            throw new NodeRequestError('Malformed node evidence response. Retry loading stored evidence.');
        const terminal = data.complete;
        const result = {...data};
        for (const key of ['diagnostics','timeline','endpointAttempts','endpointObservations','networkEnrichment','discv5'])
            result[key] = list(data[key]).filter(object);
        result.diagnostics = result.diagnostics.map(stage => ({...stage,
            name: typeof stage.name === 'string' ? stage.name : 'Unknown stage',
            state: typeof stage.state !== 'string' ? 'UNKNOWN' : terminal && stage.state === 'CHECKING' ? 'NOT_TESTED' : stage.state}));
        for (const key of ['rpc','beacon','p2p','client','nodeStack','endpointAnalysis']) result[key] = object(data[key]) ? data[key] : null;
        if (result.nodeStack && (!object(result.nodeStack.execution) || !object(result.nodeStack.consensus))) result.nodeStack = null;
        if (result.endpointAnalysis) result.endpointAnalysis = {...result.endpointAnalysis,
            comparisons:list(result.endpointAnalysis.comparisons).filter(object), evidence:list(result.endpointAnalysis.evidence).filter(object)};
        result.networkVerification = object(data.networkVerification) ? {...data.networkVerification,rows:list(data.networkVerification.rows).filter(object)} : {rows:[],warning:false,network:null};
        result.rpcProbeEndpoints = list(data.rpcProbeEndpoints); result.beaconProbeEndpoints = list(data.beaconProbeEndpoints);
        return result;
    }
    class NodeDetailController {
        constructor(options) {
            this.options = options; this.canInspect = false; this.alive = true; this.requests = new Set();
            this.timer = null; this.version = 0; this.now = options.now || (() => Date.now());
            this.schedule = options.schedule || ((fn,ms) => setTimeout(fn,ms));
            this.unschedule = options.unschedule || (id => clearTimeout(id));
            this.requestTimeout = options.requestTimeout ?? 15000;
            this.pollLimit = options.pollLimit ?? 160; this.pollWindow = options.pollWindow ?? 150000;
        }
        ready(busy,known=true) { this.options.onReady?.({canInspect:this.canInspect,busy,known}); }
        state(kind,message) { if(this.alive)this.options.onState?.(kind,message); }
        async request(url, options = {}) {
            const controller = new AbortController(); this.requests.add(controller);
            let timedOut = false;
            const timeout = this.schedule(() => { timedOut = true; controller.abort(); },this.requestTimeout);
            try {
                const response = await (this.options.fetch || global.fetch)(url,{...options,credentials:'same-origin',redirect:'error',cache:'no-store',signal:controller.signal,
                    headers:{Accept:'application/json',...(options.headers || {})}});
                if(response.status === 401 || response.redirected || /\/login(?:[?#]|$)/.test(response.url || ''))
                    throw new NodeRequestError('Authentication required. Sign in again, then retry.','AUTHENTICATION_REQUIRED');
                if(!response.ok)throw new NodeRequestError(`Request failed (HTTP ${response.status}). ${response.status===403?'Permission denied. ':''}Retry loading evidence.`);
                if(!(response.headers.get('Content-Type') || '').toLowerCase().includes('application/json'))
                    throw new NodeRequestError('Expected JSON but received another content type. Your session may have expired; sign in or retry.');
                let data; try { data = await response.json(); } catch { throw new NodeRequestError('Malformed JSON response. Retry loading evidence.'); }
                if(data === null)throw new NodeRequestError('Empty JSON response. Retry loading evidence.');
                return data;
            } catch(error) {
                if(timedOut)throw new NodeRequestError('Request timed out. Retry to check the actual backend state.','REQUEST_FAILED');
                if(error.name === 'AbortError' && !this.alive)throw error;
                throw error instanceof NodeRequestError ? error : new NodeRequestError('Request failed or was redirected. Check your session and retry.');
            } finally { this.unschedule(timeout);this.requests.delete(controller); }
        }
        async load() {
            const version = ++this.version;
            if(this.timer !== null)this.unschedule(this.timer);
            this.state('LOADING','Loading stored node evidence…');this.ready(false,false);
            try {
                const response=await this.request('/inspection/current.json?key='+encodeURIComponent(this.options.key));
                if(!this.alive || version !== this.version)return;
                if(!object(response) || typeof response.canInspect !== 'boolean' || typeof response.activeInspectionId !== 'string')
                    throw new NodeRequestError('Malformed node state response. Retry loading evidence.');
                this.canInspect=response.canInspect;
                const data=normalize(response.data);this.options.render(data);
                const id=response.activeInspectionId || (this.firstLoad ? '' : this.options.initialJobId || '');this.firstLoad=true;
                if(id) { this.beginPolling(id,version);return; }
                this.ready(false);this.state(data.hasStoredInspection ? 'LOADED' : 'EMPTY',data.hasStoredInspection ? 'Loaded stored evidence · No active deep inspection' : 'No stored inspection evidence · No active deep inspection');
            } catch(error) { if(this.alive && version===this.version){this.ready(false,false);this.state(error.kind || 'REQUEST_FAILED',error.message);} }
        }
        beginPolling(id,version) {
            this.ready(true);this.state('RUNNING','Inspection running · discovery scanning is a separate operation');
            const started=this.now();let attempts=0;
            const poll=async()=>{
                if(!this.alive || version!==this.version)return;
                if(++attempts>this.pollLimit || this.now()-started>=this.pollWindow) {
                    this.ready(true,false);this.state('POLL_TIMEOUT','Polling timed out. The backend job may still be running. Retry to check its state.');return;
                }
                try {
                    const data=normalize(await this.request('/inspection/status?id='+encodeURIComponent(id)));
                    if(!this.alive || version!==this.version)return;
                    this.options.render(data);
                    if(data.complete) {
                        const state=data.jobState || 'COMPLETED';this.ready(false);
                        this.state(state,state==='FAILED'?'Inspection failed: '+(data.jobError || 'See recorded diagnostics'):state==='TIMED_OUT'?'Inspection timed out':state==='CANCELLED'?'Inspection cancelled':'Inspection completed · Loaded recorded evidence');
                        this.options.onCompleted?.();return;
                    }
                    this.timer=this.schedule(poll,750);
                } catch(error) {
                    if(!this.alive || version!==this.version)return;
                    this.ready(true,false);this.state(error.kind || 'REQUEST_FAILED',error.message);
                }
            };poll();
        }
        async inspect(body) {
            if(!this.canInspect)return;
            const version=++this.version;this.ready(true);this.state('RUNNING','Starting deep inspection…');
            try {
                const data=await this.request('/inspection/start',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body});
                if(!this.alive || version!==this.version)return;
                if(!object(data) || typeof data.id!=='string' || !data.id)throw new NodeRequestError('Inspection response did not contain a job ID. Retry to check its state.');
                this.beginPolling(data.id,version);
            } catch(error) {if(this.alive && version===this.version){this.ready(true,false);this.state(error.kind || 'REQUEST_FAILED',error.message);} }
        }
        close() {
            this.alive=false;this.version++;if(this.timer!==null)this.unschedule(this.timer);
            for(const request of this.requests)request.abort();this.requests.clear();
        }
    }
    global.NodeDetailController=NodeDetailController;
})(typeof window === 'undefined' ? globalThis : window);
