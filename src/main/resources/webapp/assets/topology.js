/* Phase A: arbitrary positions of recorded identities; never synthesize topology edges. */
(() => {
    const root=document.getElementById('topology-app');if(!root)return;
    const el=id=>document.getElementById('topology-'+id);
    const make=(tag,text)=>{const node=document.createElement(tag);if(text!==undefined)node.textContent=String(text);return node;};
    const svgNode=(tag,attrs={})=>{const node=document.createElementNS('http://www.w3.org/2000/svg',tag);for(const [key,value] of Object.entries(attrs))node.setAttribute(key,String(value));return node;};
    const compactIdentity=value=>value.slice(0,12)+'…'+value.slice(-8);
    function identityDetail(label,value) {
        const detail=make('details');detail.className='identity-detail';
        const summary=make('summary',label+': '+compactIdentity(value)+' · full identity');
        const full=make('code',value);full.className='identity';detail.append(summary,full);return detail;
    }
    const identity=value=>typeof value==='string' && /^[0-9a-f]{128}$/.test(value);
    const num=value=>Number(value).toLocaleString();
    let params,graph,selected=null,cursor='',generation=0,scopeController,selectionController;
    let transform={x:0,y:0,scale:1},drag=null;
    const setStatus=(text,state='ready')=>{el('status').textContent=text;el('status').dataset.state=state;};
    function error(parent,error,retry) {
        parent.replaceChildren(make('p',error.message));const button=make('button','Retry');button.type='button';button.onclick=retry;parent.append(button);
        if(error.auth){const link=make('a','Sign in again');link.href='/login';parent.append(link);}
    }
    async function request(path,signal) {
        const controller=new AbortController();const abort=()=>controller.abort();signal?.addEventListener('abort',abort,{once:true});
        let timedOut=false;const timeout=setTimeout(()=>{timedOut=true;controller.abort();},15000);
        try {
            const response=await fetch('/api/v1/topology'+path,{signal:controller.signal,credentials:'same-origin',redirect:'error',cache:'no-store',headers:{Accept:'application/json'}});
            if(response.status===401 || response.redirected)throw Object.assign(new Error('Authentication required. Sign in again.'),{auth:true});
            if(!response.ok) {
                let message;try{const body=await response.json();if(typeof body?.error?.message==='string')message=body.error.message.slice(0,1024);}catch {}
                throw new Error(message || (response.status===413?'This scope or graph page exceeds the explicit limits. Narrow the window or identity prefix.':`Topology request failed (HTTP ${response.status}).`));
            }
            if(!(response.headers.get('Content-Type')||'').includes('application/json'))throw new Error('Expected JSON evidence; check your session and retry.');
            const body=await response.json();if(!body || body.apiVersion!=='v1' || !body.data || typeof body.data!=='object')throw new Error('Malformed topology response.');return body.data;
        } catch(cause) {if(timedOut)throw new Error('Topology request timed out. Narrow the scope or retry.');throw cause;}
        finally{clearTimeout(timeout);signal?.removeEventListener('abort',abort);}
    }
    const utcInput=date=>date.toISOString().slice(0,19);
    function quickRange() {
        const end=new Date(),days=Number(el('range').value),start=new Date(end.getTime()-days*86400000);
        el('start').value=utcInput(start);el('end').value=utcInput(end);el('baseline-start').value=utcInput(new Date(start.getTime()-(end-start)));el('baseline-end').value=utcInput(start);
    }
    function windowValue(id){const value=el(id).value;const parsed=new Date(value+'Z');if(!value || !Number.isFinite(parsed.getTime()))throw new Error('Enter a valid UTC '+id+' timestamp.');return parsed;}
    function filters() {
        const start=windowValue('start'),end=windowValue('end');if(end<=start || end-start>31*86400000)throw new Error('Select a positive UTC window of at most 31 days.');
        const result=new URLSearchParams({start:start.toISOString(),end:end.toISOString(),protocol:el('protocol').value,relation:el('relation').value,q:el('search').value.trim().toLowerCase(),limit:el('limit').value});if(el('availability')?.value)result.set('availability',el('availability').value);if(el('observer')?.value.trim())result.set('observer',el('observer').value.trim());return result;
    }
    function validate(value) {
        if(!value || !Array.isArray(value.nodes) || !Array.isArray(value.edges) || value.nodes.length>200 || !value.summary || !value.coverage || !value.scope)throw new Error('Malformed or oversized graph response.');
        if(value.edges.length>400 || value.edges.some(edge=>!identity(edge.sourceIdentity)||!identity(edge.targetIdentity)||edge.relationType!=='REPORTED_NEIGHBOR'||!edge.evidenceId))throw new Error('Malformed relationship evidence.');
        for(const key of ['degreeDistribution','connectedComponents','repeatedRelationshipObservations','relationshipFreshness'])if(value.summary[key]!==null)throw new Error('Unavailable relationship metrics must remain null, not measured zero.');
        if(value.coverage.queryComplete!==true || value.coverage.aggregateTruncated!==false)throw new Error('Incomplete or capped aggregates cannot be presented as complete statistics.');
        if(!Number.isSafeInteger(value.summary.observedIdentityCount)||value.summary.observedIdentityCount<value.nodes.length || !Number.isSafeInteger(value.coverage.sourceOccurrences)||value.coverage.sourceOccurrences<0)throw new Error('Malformed topology counts.');
        if(new Set(value.nodes.map(node=>node.identity)).size!==value.nodes.length)throw new Error('Duplicate graph identities are not a valid page.');
        if(value.nodes.some(node=>!identity(node.identity)))throw new Error('Graph identity is malformed; endpoints cannot substitute for public keys.');
        return value;
    }
    function layout(nodes){return nodes.map((node,index)=>{const angle=index*2.399963229728653,radius=260*Math.sqrt((index+.5)/Math.max(1,nodes.length));return {...node,x:480+Math.cos(angle)*radius*1.5,y:310+Math.sin(angle)*radius};});}
    function applyTransform(){el('viewport').setAttribute('transform',`translate(${transform.x} ${transform.y}) scale(${transform.scale})`);}
    function zoom(factor){transform.scale=Math.min(6,Math.max(.4,transform.scale*factor));applyTransform();}
    function draw(data) {
        graph=validate(data);const viewport=el('viewport');viewport.replaceChildren();
        const positions=new Map(layout(data.nodes).map(node=>[node.identity,node]));
        const defs=svgNode('defs'),marker=svgNode('marker',{id:'reported-arrow',viewBox:'0 0 10 10',refX:18,refY:5,markerWidth:7,markerHeight:7,orient:'auto-start-reverse'});marker.append(svgNode('path',{d:'M 0 0 L 10 5 L 0 10 z',fill:'#57ddba'}));defs.append(marker);viewport.append(defs);
        const relationships=new Map();for(const edge of data.edges){const key=edge.sourceIdentity+':'+edge.targetIdentity;if(!relationships.has(key))relationships.set(key,[]);relationships.get(key).push(...(edge.observations||[edge]));}
        for(const observations of relationships.values()) {
            const edge=observations[0],source=positions.get(edge.sourceIdentity),target=positions.get(edge.targetIdentity);if(!source||!target)continue;
            const line=svgNode('line',{x1:source.x,y1:source.y,x2:target.x,y2:target.y,stroke:'#57ddba','stroke-width':3,'marker-end':'url(#reported-arrow)',tabindex:0,role:'button','aria-label':`Reported neighbor, ${observations.length} displayed observations`});
            const inspect=async()=>{selectionController?.abort();selectionController=new AbortController();selected=null;el('selection').replaceChildren(make('p',`${compactIdentity(edge.sourceIdentity)} → ${compactIdentity(edge.targetIdentity)}`),identityDetail('Responder',edge.sourceIdentity),identityDetail('Reported node',edge.targetIdentity),make('p',`${observations.length} displayed immutable observations. First ${observations.map(o=>o.observedAt).sort()[0]}; last ${observations.map(o=>o.observedAt).sort().at(-1)} UTC. Discovery report, not a peer session.`));el('evidence').replaceChildren();
                for(const observation of observations){const detail=make('details');detail.append(make('summary',`${observation.observedAt} · ${observation.protocol} · observer ${observation.observer}`));el('evidence').append(detail);detail.ontoggle=async()=>{if(!detail.open||detail.dataset.loaded)return;detail.dataset.loaded='true';try{const provenance=await request('/edges/'+observation.id+'?'+params,selectionController.signal);detail.append(make('pre',JSON.stringify(provenance,null,2)));}catch(cause){detail.append(make('p',cause.message));}};}
            };line.onclick=inspect;line.onkeydown=event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();inspect();}};viewport.append(line);
        }
        for(const node of layout(data.nodes)) {
            const group=svgNode('g'),circle=svgNode('circle',{cx:node.x,cy:node.y,r:selected===node.identity?9:6,fill:node.validationStatus==='VALIDATED_ENR_IDENTITY'?'#57ddba':'#87aaff',tabindex:0,role:'button','data-identity':node.identity,'aria-label':'Select recorded identity '+node.identity});
            const title=svgNode('title');title.textContent=`${node.identity} · ${node.observationCount} source occurrences · ${node.validationStatus}. Position is arbitrary.`;circle.append(title);
            circle.onclick=()=>select(node);circle.onkeydown=event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();select(node);}};group.append(circle);
            if(data.nodes.length<=80){const label=svgNode('text',{x:node.x+10,y:node.y+4});label.textContent=node.identity.slice(0,8)+'…';group.append(label);}viewport.append(group);
        }
        applyTransform();el('empty').hidden=!!data.nodes.length;el('next').hidden=!data.nextCursor;cursor=data.nextCursor||'';
        const kpis=el('kpis');kpis.replaceChildren();
        for(const [label,value,unit] of [['Observed identities',data.summary.observedIdentityCount,'distinct matching canonical identities in window'],['Displayed identities',data.nodes.length,'bounded page; not all Ethereum nodes'],['Attributable edges',data.summary.attributableEdgeCount,'distinct directed reported-neighbor relationships'],['Relationship statistics','Unavailable','global degree and connectivity remain unknown']]) {
            const card=make('div');card.className='kpi';card.append(make('span',label),make('strong',value==='Unavailable'?value:num(value)),make('small',unit));kpis.append(card);
        }
        el('caption').textContent=`${data.evidenceCoverage?`Collector ${data.evidenceCoverage.collectorStatus}. ${data.evidenceCoverage.relationshipMeasurementAvailable ? (data.evidenceCoverage.relationshipObservationCount+' attributable observations; '+data.evidenceCoverage.distinctDirectedRelationshipCount+' directed relationships.') : 'No attributable relationship evidence available in this window.'} ${data.evidenceCoverage.nonPromotableCandidateCount} diagnostic candidates.`:''} UTC [${data.scope.startInclusive}, ${data.scope.endExclusive}). ${num(data.coverage.sourceOccurrences)} matching source occurrences; ${num(data.coverage.excludedInvalidEnrs)} invalid/mismatched ENRs excluded from vertices. ${data.displayedSubset?'Displaying a subset; use pages or identity search.':'All matching identities fit this page.'} Generated ${data.generatedAt} UTC. Safety budget ${num(data.coverage.safetyLimitOccurrences)} source occurrences; budget reached=${data.coverage.safetyLimitReached}; scoped query complete=${data.coverage.queryComplete}, aggregate truncated=${data.coverage.aggregateTruncated}. ${data.relationshipEdgesTruncated?'Relationship rendering is a bounded subset.':''} Network coverage remains unknown. Relationship filters apply only to attributable edges; identities remain visible. Layout is arbitrary; no physical or connectivity meaning.`;
        const table=el('table');table.replaceChildren();
        for(const node of data.nodes){const row=make('tr'),id=make('td'),button=make('button',node.identity);button.type='button';button.onclick=()=>select(node);const printed=make('span',node.identity);printed.className='print-identity';printed.setAttribute('aria-hidden','true');id.append(button,printed);row.append(id,make('td',node.firstObservedAt),make('td',node.lastObservedAt),make('td',num(node.observationCount)),make('td',node.validationStatus+' / '+(node.evidenceKinds||[]).join(', ')));table.append(row);}
        if(!data.nodes.length){const row=make('tr'),cell=make('td','No timestamped identity evidence matches this scope. No edges or measurements are invented.');cell.colSpan=5;row.append(cell);table.append(row);}
    }
    async function load(next=false) {
        scopeController?.abort();selectionController?.abort();scopeController=new AbortController();const version=++generation;
        el('load').disabled=true;el('compare').disabled=true;el('print').disabled=true;const oldExport=el('export');if(oldExport)oldExport.hidden=true;el('retry').hidden=true;el('login').hidden=true;el('next').disabled=true;setStatus('Loading bounded historical identity evidence…','loading');
        selected=null;el('selection').textContent='Select an identity to inspect its records.';el('evidence').replaceChildren();el('neighborhood').hidden=true;el('neighborhood-status').textContent='';el('comparison').replaceChildren();
        try {
            if(!next)params=filters();const query=new URLSearchParams(params);if(next&&cursor)query.set('after',cursor);
            const data=validate(await request('/graph?'+query,scopeController.signal));if(version!==generation)return;
            draw(data);if(data.highWatermark!==undefined)params.set('highWatermark',data.highWatermark);const exported=el('export');if(exported){exported.href='/api/v1/topology/exports/graph.json?'+query;exported.hidden=false;}el('compare').disabled=false;el('print').disabled=false;setStatus(`Loaded ${data.nodes.length} recorded identities. ${data.edges.length} displayed directed report observations; network coverage unknown.`);
        } catch(cause) {
            if(cause.name==='AbortError'||version!==generation)return;
            el('viewport').replaceChildren();el('table').replaceChildren();el('kpis').replaceChildren();el('next').hidden=true;
            setStatus(cause.message,'error');el('retry').hidden=false;el('login').hidden=!cause.auth;
        } finally {if(version===generation){el('load').disabled=false;el('next').disabled=false;}}
    }
    async function select(node) {
        selected=node.identity;
        for(const circle of el('viewport').querySelectorAll('circle'))circle.setAttribute('r',circle.getAttribute('data-identity')===selected?9:6);
        selectionController?.abort();selectionController=new AbortController();const signal=selectionController.signal;
        const selection=el('selection');selection.replaceChildren();selection.append(identityDetail('Node',node.identity),make('p',`${node.validationStatus}. ${num(node.observationCount)} source occurrences. First ${node.firstObservedAt}; last ${node.lastObservedAt} UTC.`));
        const explorer=make('a','Open Node Explorer / stored observations');explorer.href='/explorer?q='+encodeURIComponent(node.identity);selection.append(explorer);el('neighborhood').hidden=false;el('neighborhood-status').textContent='Load bounded attributable report observations.';
        el('evidence').textContent='Loading source evidence…';
        try {
            const page=await request('/nodes/'+node.identity+'/evidence?'+params,signal);if(signal.aborted||selected!==node.identity)return;
            if(!Array.isArray(page.observations)||page.observations.length>20)throw new Error('Malformed evidence response.');
            const container=el('evidence');container.replaceChildren(make('p',page.limitation));
            if(!page.observations.length)container.append(make('p','No evidence occurrences in this selected window.'));
            for(const item of page.observations){const detail=make('details'),summary=make('summary',`${item.kind} · ${item.observedAt} UTC · ${item.validationStatus}`);detail.append(summary,make('p',item.provenance));
                if(typeof item.href==='string'&&(item.href.startsWith('/api/v1/identities/')||item.href.startsWith('/api/v1/topology/edges/'))){const link=make('a','Open original source evidence');link.href=item.href+(item.kind==='relationship'?'?'+params:'');detail.append(link);}
                if(item.provenanceTruncated)detail.append(make('p','Provenance excerpt is truncated; the original source link retains the complete value.'));
                detail.append(make('pre',item.payloadOmitted?'Payload omitted by the explicit size limit; follow the original source link.':JSON.stringify(item.payload,null,2)));container.append(detail);
            }
            if(page.truncated)container.append(make('p','Only the latest 20 occurrences are shown. Narrow the window or use the bounded identity evidence API.'));
        } catch(cause){if(cause.name!=='AbortError'&&selected===node.identity)error(el('evidence'),cause,()=>select(node));}
    }
    async function neighborhood() {
        if(!selected)return;const id=selected,signal=selectionController.signal;el('neighborhood').disabled=true;el('neighborhood-status').textContent='Loading observed neighborhood…';
        try {
            const data=validate(await request('/nodes/'+id+'/neighborhood?'+params,signal));if(selected!==id||signal.aborted)return;
            draw(data);el('neighborhood-status').textContent=`${data.nodes.length} selected identity; ${data.edges.length} attributable edges. Bounded attributable report neighborhood; missing evidence does not establish isolation. This does not establish isolation.`;
        } catch(cause){if(cause.name!=='AbortError'&&selected===id)error(el('neighborhood-status'),cause,neighborhood);}
        finally{el('neighborhood').disabled=false;}
    }
    async function compare() {
        if(!params)return;el('compare').disabled=true;el('comparison').textContent='Measuring two historical windows…';const version=generation;
        try {
            const query=new URLSearchParams(params);query.set('baselineStart',windowValue('baseline-start').toISOString());query.set('baselineEnd',windowValue('baseline-end').toISOString());
            const data=await request('/compare?'+query,scopeController.signal);if(version!==generation)return;
            if(!data.identities || !data.relationships)throw new Error('Malformed comparison response.');
            const container=el('comparison');container.replaceChildren(make('p',`Baseline UTC [${data.baseline.startInclusive}, ${data.baseline.endExclusive}); current UTC [${data.current.startInclusive}, ${data.current.endExclusive}).`));
            container.append(make('p',data.relationships.reason));for(const key of ['newlyObserved','reobserved','notReobserved'])container.append(make('p',`${key}: ${data.relationships.available ? (data.relationships[key]||[]).length : 'Unavailable'}`));for(const [key,label] of [['newlyObserved','Relationships observed only in current window'],['reobserved','Relationships observed in both windows'],['notReobserved','Relationships observed only in baseline window']]){if(!data.relationships.available)continue;const list=data.relationships[key]||[],detail=make('details');detail.append(make('summary',label),make('pre',list.slice(0,200).join('\n')||'None observed.'));if(list.length>200)detail.append(make('p','Only the first 200 relationship keys are displayed.'));container.append(detail);}
            const counts=make('div');counts.className='comparison-grid';
            for(const [key,label] of [['newlyObserved','Newly observed identities'],['reobserved','Re-observed identities'],['notReobserved','Identities not re-observed']])counts.append(make('p',`${label}: ${num(data.identities[key])}`));container.append(counts,make('p',data.identities.meaning));
            for(const [key,label] of [['newlyObservedIdentities','New identity samples'],['reobservedIdentities','Re-observed identity samples'],['notReobservedIdentities','Not re-observed identity samples']]){const detail=make('details');detail.append(make('summary',label),make('pre',(data.identities[key]||[]).join('\n')||'None in this measured comparison.'));container.append(detail);}
            if(data.identities.listsTruncated)container.append(make('p','Identity samples are bounded by the graph page-size limit; counts cover the full eligible query scope.'));
        } catch(cause){if(cause.name!=='AbortError'&&version===generation)error(el('comparison'),cause,compare);}
        finally{if(version===generation)el('compare').disabled=false;}
    }
    el('filters').addEventListener('submit',event=>{event.preventDefault();load();});el('range').onchange=()=>{quickRange();load();};el('retry').onclick=()=>load();el('next').onclick=()=>load(true);el('neighborhood').onclick=neighborhood;el('compare').onclick=compare;
    el('zoom-in').onclick=()=>zoom(1.25);el('zoom-out').onclick=()=>zoom(.8);el('reset').onclick=()=>{transform={x:0,y:0,scale:1};applyTransform();};
    el('print').onclick=()=>window.print();
    const canvas=el('graph');canvas.addEventListener('wheel',event=>{event.preventDefault();zoom(event.deltaY<0?1.1:1/1.1);},{passive:false});
    canvas.addEventListener('pointerdown',event=>{if(['circle','line'].includes(event.target.tagName.toLowerCase()))return;drag={x:event.clientX,y:event.clientY,tx:transform.x,ty:transform.y};canvas.setPointerCapture(event.pointerId);});
    canvas.addEventListener('pointermove',event=>{if(!drag)return;const box=canvas.getBoundingClientRect();transform.x=drag.tx+(event.clientX-drag.x)*960/box.width;transform.y=drag.ty+(event.clientY-drag.y)*620/box.height;applyTransform();});
    canvas.addEventListener('pointerup',()=>drag=null);canvas.addEventListener('pointercancel',()=>drag=null);
    canvas.addEventListener('keydown',event=>{if(event.key==='+'||event.key==='=')zoom(1.25);else if(event.key==='-')zoom(.8);else if(event.key.startsWith('Arrow')){event.preventDefault();transform.x+=event.key==='ArrowLeft'?-25:event.key==='ArrowRight'?25:0;transform.y+=event.key==='ArrowUp'?-25:event.key==='ArrowDown'?25:0;applyTransform();}});
    window.addEventListener('pagehide',()=>{generation++;scopeController?.abort();selectionController?.abort();});
    window.TorchTopology={layout,validate};quickRange();load();
})();
