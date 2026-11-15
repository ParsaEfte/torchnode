import fs from 'node:fs';import os from 'node:os';import path from 'node:path';import {spawn} from 'node:child_process';
const fixture=fs.realpathSync(process.argv[2]);
if(!fixture.startsWith(fs.realpathSync(os.tmpdir())+'/')||JSON.parse(fs.readFileSync(fixture+'/V6-FIXTURE.json')).synthetic!==true)throw Error('Private synthetic fixture required');
const config=JSON.parse(fs.readFileSync(fixture+'/browser-session.json'));
if(!Number.isInteger(config.port)||config.port===8080||config.port<1024)throw Error('Non-production fixture port required');
const base='http://127.0.0.1:'+config.port;
const axeSource=process.argv[3]?fs.readFileSync(process.argv[3],'utf8'):null;
const headed=process.argv.includes('--headed');
const profile=fs.mkdtempSync(path.join(os.tmpdir(),'torchnode-browser-'));
const chrome=spawn('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',[...(headed?[]:['--headless=new']),'--no-first-run','--disable-background-networking','--disable-sync','--disable-default-apps','--host-resolver-rules=MAP * 0.0.0.0, EXCLUDE 127.0.0.1','--remote-debugging-port=0','--user-data-dir='+profile,'about:blank'],{stdio:'ignore'});
let socket;let seq=0;const pending=new Map();const sleep=ms=>new Promise(r=>setTimeout(r,ms));
try{
 for(let i=0;i<100&&!fs.existsSync(profile+'/DevToolsActivePort');i++)await sleep(100);
 const port=fs.readFileSync(profile+'/DevToolsActivePort','utf8').split('\n')[0];
 const target=await (await fetch('http://127.0.0.1:'+port+'/json/new?about:blank',{method:'PUT'})).json();
 socket=new WebSocket(target.webSocketDebuggerUrl);await new Promise((r,j)=>{socket.onopen=r;socket.onerror=j});
 socket.onmessage=e=>{const x=JSON.parse(e.data);if(pending.has(x.id)){pending.get(x.id)(x);pending.delete(x.id)}};
 const send=(method,params={})=>new Promise((resolve,reject)=>{const id=++seq;const timer=setTimeout(()=>reject(Error(method+' timeout')),10000);pending.set(id,x=>{clearTimeout(timer);if(x.error)reject(Error(JSON.stringify(x.error)));else resolve(x.result)});socket.send(JSON.stringify({id,method,params}))});
 const evaluate=async expression=>{const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(result.exceptionDetails)throw Error(result.exceptionDetails.text+': '+result.exceptionDetails.exception?.description);return result.result.value};
 const accessibility=[];
 const audit=async page=>{if(!axeSource)return;await evaluate(axeSource);const result=await evaluate(`axe.run(document,{runOnly:{type:'tag',values:['wcag2a','wcag2aa','wcag21a','wcag21aa','wcag22aa']}}).then(r=>({version:r.testEngine.version,violations:r.violations,passes:r.passes.length,incomplete:r.incomplete.map(x=>({id:x.id,nodes:x.nodes.map(n=>n.target)}))}))`);accessibility.push({page,...result})};
 await send('Network.enable');
 await send('Page.navigate',{url:base+'/legal'});await sleep(500);
 await audit('legal-desktop');
 await send('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await sleep(300);await audit('legal-mobile');
 if(await evaluate('document.documentElement.scrollWidth>innerWidth'))throw Error('Legal notice mobile overflow');
 await send('Emulation.setDeviceMetricsOverride',{width:1280,height:900,deviceScaleFactor:1,mobile:false});

 await send('Page.navigate',{url:base+'/login'});await sleep(700);
 await audit('login');
 await evaluate(`document.querySelector('[name=username]').value=${JSON.stringify(config.username)};document.querySelector('[name=password]').value=${JSON.stringify(config.password)};document.querySelector('#submit').focus()`);
 const button=await evaluate('(()=>{const r=document.querySelector("#submit").getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()');
 await send('Input.dispatchMouseEvent',{type:'mousePressed',...button,button:'left',clickCount:1});
 await send('Input.dispatchMouseEvent',{type:'mouseReleased',...button,button:'left',clickCount:1});await sleep(1800);
 if(await evaluate('!!document.querySelector("input[type=password]")'))throw Error('Native browser login failed: '+await evaluate('document.body.innerText'));
 await send('Page.navigate',{url:base+'/topology'});await sleep(1800);
 const results=[];
 for(const [name,width,height,mobile]of [['desktop',1280,900,false],['mobile',390,844,true]]){
  await send('Emulation.setDeviceMetricsOverride',{width,height,deviceScaleFactor:1,mobile});await sleep(500);
  const metrics=await evaluate('({title:document.title,width:innerWidth,scrollWidth:document.documentElement.scrollWidth,hasMain:!!document.querySelector("main"),passwordFields:document.querySelectorAll("input[type=password]").length})');
  if(!metrics.hasMain||metrics.scrollWidth>metrics.width)throw Error('Invalid layout');
  if(metrics.passwordFields)throw Error('Authenticated fixture session did not reach topology');
  const ax=await send('Accessibility.getFullAXTree');const controls=ax.nodes.filter(n=>!n.ignored&&['button','link'].includes(n.role?.value));
  const unnamed=controls.filter(n=>!n.name?.value).length;if(unnamed)throw Error('Unnamed interactive control');
  await send('Input.dispatchKeyEvent',{type:'keyDown',key:'Tab',code:'Tab',windowsVirtualKeyCode:9});await send('Input.dispatchKeyEvent',{type:'keyUp',key:'Tab',code:'Tab',windowsVirtualKeyCode:9});
  const focused=await evaluate('({tag:document.activeElement.tagName,label:document.activeElement.getAttribute("aria-label")||document.activeElement.textContent.trim().slice(0,80),visible:document.activeElement.matches(":focus-visible")&&getComputedStyle(document.activeElement).outlineStyle!=="none"&&parseFloat(getComputedStyle(document.activeElement).outlineWidth)>0})');
  if(!focused.visible)throw Error('Keyboard focus indicator missing');
  const contrast=await evaluate(`(()=>{
   const rgb=value=>{const n=value.match(/[0-9.]+/g).map(Number);return [n[0],n[1],n[2],n[3]??1]};
   const over=(front,back)=>[0,1,2].map(i=>front[i]*front[3]+back[i]*(1-front[3]));
   const luminance=color=>color.map(x=>{x/=255;return x<=.04045?x/12.92:((x+.055)/1.055)**2.4}).reduce((s,x,i)=>s+x*[.2126,.7152,.0722][i],0);
   return [...document.querySelectorAll('#tn-menu,#topology-zoom-out,.tn-navigation .icon,[aria-hidden=true].icon')].filter(e=>e.getClientRects().length).map(e=>{
    const chain=[];for(let p=e;p;p=p.parentElement)chain.unshift(p);
    let background=[255,255,255];for(const p of chain)background=over(rgb(getComputedStyle(p).backgroundColor),background);
    const style=getComputedStyle(e);const front=rgb(style.color);front[3]*=Number(style.opacity);
    const a=luminance(over(front,background)),b=luminance(background);const ratio=(Math.max(a,b)+.05)/(Math.min(a,b)+.05);
    return {element:e.id||e.parentElement.getAttribute('href'),color:style.color,background,ratio,decorative:e.getAttribute('aria-hidden')==='true',pass:ratio>=4.5};
   });
  })()`);
  if(contrast.some(item=>!item.pass))throw Error('Symbolic control/icon contrast below 4.5:1');
  await audit('topology-'+name);
  const png=await send('Page.captureScreenshot',{format:'png'});fs.writeFileSync('docs/validation/v6-synthetic-'+name+'.png',Buffer.from(png.data,'base64'));
  results.push({name,metrics,controls:controls.length,unnamedControls:unnamed,focused,contrast,noHorizontalOverflow:metrics.scrollWidth<=metrics.width});
 }
 const browser=await send('Browser.getVersion');
 fs.writeFileSync('docs/validation/v6-synthetic-browser.json',JSON.stringify({syntheticOnly:true,headed,browser,results,accessibility,limitations:['Native Chrome form login submitted by native mouse click; no session imported.','Automated AX/name/keyboard/overflow and optional axe checks, not screen-reader or WCAG certification.','Incomplete axe checks require human review; symbolic foreground/background contrast is measured separately.']},null,2));
 if(accessibility.some(r=>r.violations.length))throw Error('axe accessibility violations; inspect docs/validation/v6-synthetic-browser.json');
 console.log(JSON.stringify(results));await send('Browser.close').catch(()=>{});
}finally{socket?.close();if(chrome.exitCode===null)chrome.kill('SIGTERM');fs.writeFileSync(fixture+'/BROWSER-DONE','done')}
