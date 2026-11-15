import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source=fs.readFileSync('src/main/resources/webapp/assets/topology.js','utf8');
class Element {
    constructor(tag='div'){this.tag=tag;this.tagName=tag;this.children=[];this.dataset={};this.attributes={};this.listeners={};this.value='';this._text='';this.hidden=false;this.disabled=false;}
    append(...children){this.children.push(...children);}
    replaceChildren(...children){this._text='';this.children=[...children];}
    set textContent(value){this._text=String(value??'');this.children=[];}
    get textContent(){return this._text+this.children.map(child=>child.textContent).join(' ');}
    setAttribute(name,value){this.attributes[name]=String(value);}
    getAttribute(name){return this.attributes[name];}
    addEventListener(event,fn){this.listeners[event]=fn;}
    querySelectorAll(tag){return this.children.flatMap(child=>[child,...child.querySelectorAll(tag)]).filter(child=>child.tag===tag);}
    setPointerCapture(){}
    getBoundingClientRect(){return {width:960,height:620};}
}
const id=n=>n.toString(16).padStart(128,'0');
const vertex=n=>({identity:id(n),firstObservedAt:'2026-01-01T00:00:00Z',lastObservedAt:'2026-01-01T01:00:00Z',observationCount:3,evidenceKinds:['discovery'],discoverySources:['discv4'],validationStatus:'RECORDED_IDENTITY_CLAIM'});
const scope={startInclusive:'2026-01-01T00:00:00Z',endExclusive:'2026-01-02T00:00:00Z'};
const graph=(nodes=[vertex(1),vertex(2)])=>({scope,generatedAt:'2026-01-02T00:00:00Z',nodes,edges:[],nextCursor:'',displayedSubset:false,summary:{observedIdentityCount:nodes.length,attributableEdgeCount:0,relationshipAnalyticsAvailable:false,degreeDistribution:null,connectedComponents:null,repeatedRelationshipObservations:null,relationshipFreshness:null},coverage:{sourceOccurrences:6,excludedInvalidEnrs:1,safetyLimitOccurrences:50000,safetyLimitReached:false,queryComplete:true,aggregateTruncated:false,networkCoverageKnown:false}});
const flush=async()=>{for(let i=0;i<6;i++)await new Promise(resolve=>setImmediate(resolve));};
function setup(config={}) {
    const roots=new Map(),events={},timers=new Map(),requests=[];let printed=0,serial=0;
    const element=id=>{if(!roots.has(id))roots.set(id,Object.assign(new Element(),{id}));return roots.get(id);};
    element('topology-range').value='1';element('topology-limit').value='100';element('topology-protocol').value='all';element('topology-relation').value='all';
    const context=vm.createContext({document:{getElementById:element,createElement:tag=>new Element(tag),createElementNS:(_,tag)=>new Element(tag)},
        Date,URLSearchParams,AbortController,console,setTimeout:(fn,ms)=>{timers.set(++serial,{fn,ms});return serial;},clearTimeout:key=>timers.delete(key),
        addEventListener:(event,handler)=>events[event]=handler,print:()=>printed++,
        fetch:async(url,options)=>{
            requests.push({url,options});
            if(config.hang)return new Promise((resolve,reject)=>options.signal.addEventListener('abort',()=>reject(Object.assign(new Error('cancelled'),{name:'AbortError'}))));
            let data=config.graph||graph();
            if(url.includes('/evidence?'))data={observations:[{kind:'discovery',observedAt:'2026-01-01T00:00:00Z',validationStatus:'RECORDED_CLAIM_OR_MEASUREMENT',provenance:'<img src=x onerror=alert(1)>',payload:{endpoints:[{address:'192.0.2.1'}]},payloadOmitted:false,href:'/api/v1/identities/'+id(1)+'/evidence/discovery/1'}],truncated:true,limitation:'Latest 20 bounded source occurrences'};
            if(url.includes('/neighborhood?'))data=graph([vertex(1)]);
            if(url.includes('/edges/'))data={evidence:{id:'b'.repeat(64),classification:'ATTRIBUTABLE_EXCHANGE',raw_json:'immutable-fixture'},validations:[{correlation:'AUTHENTICATED_REQUEST_MATCH'}]};
            if(url.includes('/compare?'))data={current:scope,baseline:scope,identities:{newlyObserved:1,reobserved:2,notReobserved:1,newlyObservedIdentities:[id(3)],reobservedIdentities:[id(1)],notReobservedIdentities:[id(4)],meaning:'Not re-observed does not mean disconnected.'},relationships:config.relationships||{available:false,reason:'Complete attribution unavailable',newlyObserved:null,reobserved:null,notReobserved:null}};
            const response={ok:!config.status,status:config.status||200,redirected:!!config.redirected,headers:{get:()=>config.contentType||'application/json;charset=UTF-8'},json:async()=>config.status?{error:{message:config.message||'Explicit query failure; no aggregate returned'}}:config.malformed?null:{apiVersion:'v1',data}};
            return response;
        }});
    context.window=context;vm.runInContext(source,context);
    return {context,element,requests,timers,events,config,printed:()=>printed};
}
let app=setup();await flush();assert.match(app.element('topology-status').textContent,/Loaded 2/);assert.equal(app.element('topology-viewport').children.length,3);assert.equal(app.element('topology-viewport').querySelectorAll('line').length,0);assert.equal(app.element('topology-table').children.length,2);assert.match(app.element('topology-kpis').textContent,/Unavailable/);assert.match(app.element('topology-caption').textContent,/Network coverage remains unknown/);assert.match(app.element('topology-caption').textContent,/aggregate truncated=false/);assert.ok(app.requests.every(request=>request.options.credentials==='same-origin'&&!request.options.method));
app.element('topology-viewport').querySelectorAll('circle')[0].onclick();await flush();assert.match(app.element('topology-evidence').textContent,/<img src=x onerror=alert\(1\)>/);assert.equal(app.element('topology-evidence').querySelectorAll('img').length,0);assert.match(app.element('topology-evidence').textContent,/Only the latest 20/);
await app.element('topology-neighborhood').onclick();assert.match(app.element('topology-neighborhood-status').textContent,/does not establish isolation/);
await app.element('topology-compare').onclick();assert.match(app.element('topology-comparison').textContent,/newlyObserved: Unavailable/);assert.match(app.element('topology-comparison').textContent,/Identities not re-observed: 1/);
app.element('topology-print').onclick();assert.equal(app.printed(),1);assert.match(app.element('topology-export').href,/exports\/graph.json\?start=/);
app.element('topology-zoom-in').onclick();assert.match(app.element('topology-viewport').getAttribute('transform'),/1.25/);app.element('topology-reset').onclick();assert.match(app.element('topology-viewport').getAttribute('transform'),/scale\(1\)/);
app=setup({graph:graph([])});await flush();assert.equal(app.element('topology-empty').hidden,false);assert.match(app.element('topology-table').textContent,/No timestamped identity evidence/);assert.equal(app.element('topology-viewport').children.length,1);assert.match(app.element('topology-kpis').textContent,/Unavailable/);
app=setup({graph:graph(Array.from({length:200},(_,i)=>vertex(i+1)))});const started=performance.now();await flush();assert.equal(app.element('topology-viewport').querySelectorAll('circle').length,200);assert.equal(app.element('topology-viewport').querySelectorAll('text').length,0);console.log(`Topology bounded 200-identity DOM render: ${(performance.now()-started).toFixed(1)} ms (DOM harness, not browser frame time)`);
for(const config of [{status:401},{status:413,message:'Safety cap exceeded; no aggregate returned'},{status:503,message:'SQLite execution deadline interrupted query; no aggregate returned'},{contentType:'text/html'},{redirected:true},{malformed:true},{graph:graph(Array.from({length:201},(_,i)=>vertex(i+1)))},{graph:{...graph(),edges:[{source:id(1),target:id(2)}]}},{graph:{...graph(),coverage:{...graph().coverage,queryComplete:false,aggregateTruncated:true}}},{graph:{...graph(),summary:{...graph().summary,connectedComponents:0}}},{graph:graph([vertex(1),vertex(1)])}]) {
    app=setup(config);await flush();assert.equal(app.element('topology-status').dataset.state,'error');assert.equal(app.element('topology-retry').hidden,false);assert.equal(app.element('topology-viewport').children.length,0);assert.equal(app.element('topology-print').disabled,true);assert.equal(app.element('topology-export').hidden,true);
}
app=setup({status:401});await flush();assert.equal(app.element('topology-login').hidden,false);delete app.config.status;await app.element('topology-retry').onclick();await flush();assert.match(app.element('topology-status').textContent,/Loaded 2/);
app=setup({hang:true});await flush();const timer=[...app.timers.values()][0];timer.fn();await flush();assert.match(app.element('topology-status').textContent,/timed out/);assert.equal(app.element('topology-retry').hidden,false);
app=setup({hang:true});await flush();app.events.pagehide();await flush();assert.equal(app.timers.size,0);
if(process.argv[2]) {
    const recorded=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));app=setup({graph:recorded});await flush();assert.notEqual(app.element('topology-status').dataset.state,'error',app.element('topology-status').textContent);assert.ok(app.element('topology-viewport').querySelectorAll('circle').length<=200);assert.ok(app.element('topology-viewport').querySelectorAll('line').length<=400);assert.match(app.element('topology-kpis').textContent,/Unavailable/);
    console.log(`Recorded HTTP graph: ${recorded.nodes.length} identities rendered; no connectivity or unavailable metrics invented`);
}
console.log('Topology DOM: no synthetic edges, unavailable metrics, 200-node cap, evidence escaping, neighborhood limits, comparison, export/print, auth/cap/deadline errors, Retry and cleanup PASS');

const directed={...graph(),edges:[{id:'a'.repeat(64),evidenceId:'b'.repeat(64),sourceIdentity:id(1),targetIdentity:id(2),relationType:'REPORTED_NEIGHBOR',observedAt:'2026-01-01T00:00:00Z',observer:'fixture',protocol:'discv5'}]};
app=setup({graph:directed});await flush();assert.equal(app.element('topology-viewport').querySelectorAll('line').length,1);const line=app.element('topology-viewport').querySelectorAll('line')[0];await line.onclick();assert.match(app.element('topology-selection').textContent,/not a peer session/);assert.match(app.element('topology-evidence').textContent,/observer fixture/);line.onkeydown({key:'Enter',preventDefault(){}});await flush();console.log('B5 directed edge selection, keyboard selection and provenance references PASS');

// B6-A interaction checks use the real renderer with controlled DOM events, not a browser claim.
app=setup({graph:directed,relationships:{available:true,reason:'Coverage unknown',newlyObserved:['current-only'],reobserved:['both'],notReobserved:['baseline-only']}});assert.equal(app.element('topology-status').dataset.state,'loading');await flush();
const canvas=app.element('topology-graph');canvas.listeners.pointerdown({target:{tagName:'svg'},clientX:10,clientY:20,pointerId:1});canvas.listeners.pointermove({clientX:110,clientY:70});canvas.listeners.pointerup();assert.match(app.element('topology-viewport').getAttribute('transform'),/translate\(100 50\)/);
app.element('topology-reset').onclick();assert.equal(app.element('topology-viewport').getAttribute('transform'),'translate(0 0) scale(1)');
canvas.listeners.keydown({key:'ArrowRight',preventDefault(){}});assert.match(app.element('topology-viewport').getAttribute('transform'),/translate\(25 0\)/);
for(const node of app.context.TorchTopology.layout(graph(Array.from({length:200},(_,i)=>vertex(i+1))).nodes)){assert.ok(node.x>0&&node.x<960&&node.y>0&&node.y<620);}
await app.element('topology-viewport').querySelectorAll('line')[0].onclick();const occurrence=app.element('topology-evidence').querySelectorAll('details')[0];occurrence.open=true;await occurrence.ontoggle();assert.match(occurrence.textContent,/AUTHENTICATED_REQUEST_MATCH/);assert.match(occurrence.textContent,/immutable-fixture/);assert.ok(app.requests.some(r=>r.url.includes('/edges/'+'a'.repeat(64))));
await app.element('topology-compare').onclick();assert.match(app.element('topology-comparison').textContent,/Relationships observed only in baseline window/);assert.match(app.element('topology-comparison').textContent,/baseline-only/);
app.element('topology-protocol').value='discv5';app.element('topology-observer').value='00000000-0000-0000-0000-000000000001';app.element('topology-search').value=id(1);app.element('topology-filters').listeners.submit({preventDefault(){}});await flush();assert.equal(app.element('topology-evidence').children.length,0);assert.ok(app.requests.at(-1).url.includes('protocol=discv5'));assert.ok(app.requests.at(-1).url.includes('observer=00000000-0000-0000-0000-000000000001'));
console.log('B6 DOM: loading, pointer pan, keyboard pan, reset/fit layout bounds, lazy immutable provenance, filters, selection reset and relationship comparison PASS');

app=setup({graph:{...graph(),edges:Array.from({length:401},()=>directed.edges[0])}});await flush();assert.equal(app.element('topology-status').dataset.state,'error');assert.equal(app.element('topology-viewport').children.length,0);console.log('B6 oversized 401-edge response rejected before SVG rendering PASS');

app=setup({graph:directed});await flush();await app.element('topology-viewport').querySelectorAll('line')[0].onclick();const identityDetails=app.element('topology-selection').querySelectorAll('details');assert.equal(identityDetails.length,2);assert.match(identityDetails[0].children[0].textContent,/Responder: .*….*full identity/);assert.equal(identityDetails[0].querySelectorAll('code')[0].textContent,id(1));assert.equal(identityDetails[1].querySelectorAll('code')[0].textContent,id(2));assert.equal(identityDetails[0].querySelectorAll('code')[0].textContent.length,128);console.log('B6 compact identity disclosures preserve full selectable 128-hex source/target identities PASS');
