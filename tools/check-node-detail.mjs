import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const lifecycle=fs.readFileSync('src/main/resources/webapp/assets/node-detail.js','utf8');
const html=fs.readFileSync('src/main/resources/webapp/WEB-INF/views/inspection.jsp','utf8').replace(/<%=[\s\S]*?%>/g,'');
const inline=[...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(match=>match[1]).filter(Boolean).join('\n');
class Element {
    constructor(tag='div'){this.tag=tag;this.children=[];this.dataset={key:'fixture-key'};this.hidden=false;this.disabled=false;this.className='';this._text='';this.listeners={};this.style={};this.classList={add:name=>this.toggle(name,true),remove:name=>this.toggle(name,false),toggle:(name,force)=>this.toggle(name,force),contains:name=>this.className.split(' ').includes(name)};}
    toggle(name,force){const set=new Set(this.className.split(' ').filter(Boolean));const add=force??!set.has(name);if(add)set.add(name);else set.delete(name);this.className=[...set].join(' ');return add;}
    get textContent(){return this._text+this.children.map(child=>child.textContent).join(' ');}
    set textContent(value){this._text=String(value??'');this.children=[];}
    append(...children){this.children.push(...children);}
    replaceChildren(...children){this._text='';this.children=[...children];}
    addEventListener(event,fn){this.listeners[event]=fn;}
    setAttribute(key,value){this[key]=value;}
    querySelector(selector){return walk(this).find(node=>node.classList.contains(selector.slice(1)))||null;}
}
function walk(element){return element.children.flatMap(child=>[child,...walk(child)]);}
const fixture=(changes={})=>({complete:true,hasStoredInspection:true,node:{key:'fixture-key',ip:'192.0.2.1',nodeId:'ab'.repeat(64),discovery:'discv4',tcpPort:30303,udpPort:30303},diagnostics:[],timeline:[],...changes});
const flush=async()=>{for(let i=0;i<5;i++)await new Promise(resolve=>setImmediate(resolve));};
function setup(config={}) {
    const roots=new Map(),events={},timers=new Map(),calls=[];let current=0,nextId=0;
    const element=id=>{if(!roots.has(id)){const node=new Element();node.id=id;if(['summary-network','summary-p2p','summary-rpc','summary-beacon','summary-inspected'].includes(id))node.className='pending-value';roots.set(id,node);}return roots.get(id);};
    const response=body=>({status:200,ok:true,redirected:false,url:'http://localhost/inspection/current.json',headers:{get:()=>config.contentType||'application/json;charset=UTF-8'},json:async()=>body});
    const context=vm.createContext({document:{getElementById:element,createElement:tag=>new Element(tag),querySelectorAll:selector=>[...roots.values()].flatMap(node=>[node,...walk(node)]).filter(node=>node.classList.contains(selector.slice(1)))},
        addEventListener:(event,fn)=>events[event]=fn,setTimeout:(fn,ms)=>{timers.set(++nextId,{fn,at:current+ms});return nextId;},clearTimeout:id=>timers.delete(id),
        Date:class extends Date{static now(){return current;}},AbortController,URLSearchParams,FormData,console,
        fetch:async(url,options)=>{
            calls.push({url,options});
            if(config.hang && url.includes('current.json'))return new Promise((resolve,reject)=>options.signal.addEventListener('abort',()=>reject(Object.assign(new Error('aborted'),{name:'AbortError'}))));
            if(url.includes('current.json')){
                if(config.status)return {...response(null),status:config.status,ok:false};
                if(config.redirected)return {...response(null),redirected:true};
                if(config.badJson)return {...response(null),json:async()=>{throw new Error('bad JSON');}};
                return response(config.body===undefined?{data:fixture(config.data),activeInspectionId:config.job||'',canInspect:config.canInspect??true,scannerRunning:true}:config.body);
            }
            if(url.includes('/status'))return response(config.poll?.(calls.filter(call=>call.url.includes('/status')).length)??fixture({complete:false}));
            if(url.includes('/start'))return response({id:'new-job'});
            return response([]);
        }});
    context.window=context;vm.runInContext(lifecycle,context);vm.runInContext(inline,context);
    return {roots,element,calls,timers,events,config,context,
        async nextTimer(){const entry=[...timers.entries()].sort((a,b)=>a[1].at-b[1].at)[0];if(entry){timers.delete(entry[0]);current=entry[1].at;entry[1].fn();await flush();}},
        state:()=>element('node-load-state').dataset.state,
        skeletons:()=>[...roots.values()].flatMap(node=>[node,...walk(node)]).filter(node=>node.classList.contains('skeleton-tile')||node.classList.contains('pending-value')).length};
}
let app=setup();await flush();assert.equal(app.state(),'LOADED');assert.equal(app.skeletons(),0);assert.equal(app.element('run-inspection').disabled,false);assert.ok(app.element('identity').textContent.includes('192.0.2.1'));assert.ok(app.calls.every(call=>(call.options.method||'GET')==='GET'));assert.ok(app.calls.every(call=>call.options.credentials==='same-origin'));
app=setup({data:{hasStoredInspection:false}});await flush();assert.equal(app.state(),'EMPTY');assert.equal(app.skeletons(),0);assert.equal(app.element('run-inspection').disabled,false);
app=setup({canInspect:false});await flush();assert.equal(app.element('run-inspection').disabled,true);
for(const config of [{status:401},{status:403},{status:500},{contentType:'text/html'},{body:null},{body:{}},{body:{data:null,canInspect:true,activeInspectionId:''}},{badJson:true},{redirected:true}]) {
    app=setup(config);await flush();assert.ok(['REQUEST_FAILED','AUTHENTICATION_REQUIRED'].includes(app.state()),JSON.stringify(config));assert.equal(app.skeletons(),0);assert.equal(app.element('retry-node').hidden,false);assert.equal(app.element('run-inspection').disabled,true);
}
app=setup({status:401});await flush();assert.equal(app.state(),'AUTHENTICATION_REQUIRED');delete app.config.status;await app.element('retry-node').onclick();await flush();assert.equal(app.state(),'LOADED');assert.equal(app.skeletons(),0);
for(const jobState of ['COMPLETED','FAILED','TIMED_OUT','CANCELLED']) {
    app=setup({job:'active-job',poll:n=>fixture({complete:n>1,jobState,jobError:'fixture error',diagnostics:[{name:'P2P TCP',state:'CHECKING'}]})});await flush();assert.equal(app.state(),'RUNNING');assert.equal(app.element('run-inspection').disabled,true);await app.nextTimer();assert.equal(app.state(),jobState);assert.equal(app.skeletons(),0);assert.equal(app.element('run-inspection').disabled,false);assert.equal(app.timers.size,0,'terminal polling stops');
}
app=setup({job:'active-job'});vm.runInContext('controller.pollLimit=2',app.context);await flush();await app.nextTimer();await app.nextTimer();assert.equal(app.state(),'POLL_TIMEOUT');assert.equal(app.skeletons(),0);assert.equal(app.timers.size,0);assert.equal(app.element('retry-node').hidden,false);
app=setup({hang:true});await flush();await app.nextTimer();assert.equal(app.state(),'REQUEST_FAILED');assert.match(app.element('node-load-state').textContent,/timed out/);assert.equal(app.skeletons(),0);
app=setup({hang:true});await flush();app.events.pagehide();await flush();assert.equal(app.timers.size,0);const count=app.calls.length;await app.nextTimer();assert.equal(app.calls.length,count,'no requests after pagehide');
if(process.argv[2]) {
    const recorded=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
    for(const body of recorded){app=setup({body});await flush();assert.ok(['LOADED','EMPTY'].includes(app.state()),app.element('node-load-state').textContent);assert.equal(app.skeletons(),0);}
    console.log(`Recorded database responses: ${recorded.length} distinct node pages rendered without skeletons`);
}
console.log('Node detail DOM: stored/empty loads, ADMIN/VIEWER, auth/errors/HTML/malformed JSON, Retry, terminal jobs, bounded polling/request timeout and pagehide cleanup PASS');
