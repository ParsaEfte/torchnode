#!/usr/bin/env python3
"""Synthetic production-entrypoint smoke. No host DB mount, network or port publication."""
import base64,hashlib,json,os,pty,re,secrets,select,sqlite3,struct,subprocess,sys,tempfile,time,uuid,urllib.parse
from pathlib import Path
image=sys.argv[1]
if not re.fullmatch(r'sha256:[0-9a-f]{64}',image):raise SystemExit('Immutable local image ID required')
root=Path(tempfile.mkdtemp(prefix='torchnode-container-synthetic-')).resolve()
label='torchnode.synthetic='+uuid.uuid4().hex
volume='torchnode-synthetic-'+uuid.uuid4().hex
ids=[]
def run(*args,timeout=30,**kwargs):return subprocess.run(list(args),check=True,capture_output=True,timeout=timeout,**kwargs)
def docker(*args,**kwargs):return run('docker',*args,**kwargs).stdout.decode()
def logs(cid):
 result=run('docker','logs',cid)
 return result.stdout.decode()+result.stderr.decode()
probe=root/'http-probe';probe.mkdir()
run('javac','--release','21','-d',str(probe),str(Path(__file__).with_name('ContainerHttpProbe.java')),str(Path(__file__).with_name('RuntimeImageProbe.java')))
cookies={}
def curl(cid,path,*args):
 method='GET';form=[];cookie='';save=None;status_only=False;discard=False;headers=False
 for option,value in zip(args[::2],args[1::2]):
  if option=='-b':cookie=cookies.get((cid,value),'')
  elif option=='-c':save=value
  elif option=='-X':method=value
  elif option=='--data-urlencode':form.append(tuple(value.split('=',1)));method='POST'
  elif option=='-w':assert value=='%{http_code}';status_only=True
  elif option=='-o':discard=True
  elif option=='-D':assert value=='-';headers=True
  else:raise ValueError('Unsupported test-client option: '+option)
 fields=[path,method,urllib.parse.urlencode(form),cookie]
 data=b''.join(struct.pack('>I',len(s.encode()))+s.encode() for s in fields)
 lines=run('docker','exec','-i',cid,'java','-cp','/http-probe','ContainerHttpProbe',input=data).stdout.decode().splitlines()
 status=int(lines[0]);head=base64.b64decode(lines[1]).decode();body=base64.b64decode(lines[2] if len(lines)>2 else '').decode()
 if save:
  found=re.findall(r'^Set-Cookie:\s*([^;\r\n]+)',head,re.I|re.M)
  if found:cookies[cid,save]='; '.join(found)
 return (head if headers else '')+('' if discard else body)+(str(status) if status_only else '')
def authenticate(cid,username,password):
 page=curl(cid,'/login','-c','/tmp/cookies')
 token=re.search(r'name="csrf" value="([^"]+)"',page).group(1)
 status=curl(cid,'/login','-b','/tmp/cookies','-c','/tmp/cookies','-o','/tmp/login-response','-w','%{http_code}','--data-urlencode','csrf='+token,'--data-urlencode','username='+username,'--data-urlencode','password='+password)
 assert status=='302',status
forbidden=['-p','--publish','--network=host','--privileged']
report={'status':'FAIL','syntheticOnly':True,'image':image,'workspace':str(root),'network':'none','publishedPorts':{},'collection':'DISABLED','rounds':[]}
viewer_password=secrets.token_urlsafe(32)
volume_owned=False
try:
 assert subprocess.run(['docker','volume','inspect',volume],capture_output=True,timeout=10).returncode!=0,'volume name collision'
 docker('volume','create','--label',label,volume)
 assert json.loads(docker('volume','inspect',volume))[0]['Labels'].get('torchnode.synthetic')==label.split('=')[1]
 volume_owned=True
 for round_number in range(2):
  command=['run','-d','--platform=linux/amd64','--pull=never','--init','--network=none','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true','--memory=1g','--cpus=2','--pids-limit=128','--label',label,'--tmpfs','/tmp:rw,noexec,nosuid,nodev,size=128m','--mount',f'type=volume,source={volume},target=/data','--mount',f'type=bind,source={probe},target=/http-probe,readonly','-e','TORCHNODE_COLLECTION_DISABLED=true','-e','TORCHNODE_SECURE_COOKIES=false',image]
  cid=docker(*command).strip();ids.append(cid)
  state=json.loads(docker('inspect',cid))[0]
  assert state['HostConfig']['NetworkMode']=='none' and not state['HostConfig']['PortBindings']
  assert state['HostConfig']['ReadonlyRootfs'] and state['HostConfig']['Memory']==1073741824
  assert state['HostConfig']['Init'] is True
  assert state['HostConfig']['RestartPolicy']['Name']=='no'
  assert state['Config']['Entrypoint']==['java','-jar','/app/torchnode.jar']
  assert all(m['Type']=='volume' and m['Name']==volume or m['Type']=='tmpfs' and m['Destination']=='/tmp' or m['Type']=='bind' and m['Destination']=='/http-probe' and m['Source'] in (str(probe),'/host_mnt'+str(probe)) and not m['RW'] for m in state['Mounts'])
  deadline=time.monotonic()+60
  while True:
   result=subprocess.run(['docker','exec',cid,'java','-cp','/app/torchnode.jar','io.github.gavinruff007.torchnode.HealthCheck'],capture_output=True,timeout=8)
   if result.returncode==0:break
   assert time.monotonic()<deadline,'readiness timeout'
   assert json.loads(docker('inspect',cid))[0]['State']['Running'],'Main exited'
   time.sleep(.25)
  assert state['Config']['User']=='10001:10001'
  report['runtime']=json.loads(docker('exec',cid,'java','-cp','/http-probe:/app/torchnode.jar','RuntimeImageProbe'))
  assert curl(cid,'/api/v1','-o','/dev/null','-w','%{http_code}')=='401'
  if round_number==0:
   password=secrets.token_urlsafe(32)
   master,slave=pty.openpty()
   p=subprocess.Popen(['docker','exec','-it',cid,'java','-jar','/app/torchnode.jar','--provision-admin'],stdin=slave,stdout=slave,stderr=slave);os.close(slave)
   try:
    for marker,answer in [(b'Administrator username/email: ','synthetic-admin'),(b'Password (12',password),(b'Confirm password: ',password)]:
     output=b'';end=time.monotonic()+30
     while marker not in output:
      assert time.monotonic()<end,'provision timeout'
      if select.select([master],[],[],.2)[0]:output+=os.read(master,4096)
     os.write(master,(answer+'\n').encode())
    assert p.wait(timeout=30)==0
   finally:
    os.close(master)
    if p.poll() is None:p.kill();p.wait()
  authenticate(cid,'synthetic-admin',password)
  page=curl(cid,'/topology','-b','/tmp/cookies');assert 'tn-navigation' in page
  token=re.search(r'name="csrf" value="([^"]+)"',page).group(1)
  for route in ['/api/v1','/api/v1/topology/graph','/api/v1/topology/summary','/api/v1/topology/candidates']:
   value=json.loads(curl(cid,route,'-b','/tmp/cookies'));assert value
  assert curl(cid,'/api/v1/topology/graph','-b','/tmp/cookies','-X','POST','-o','/dev/null','-w','%{http_code}')=='405'
  blocked=urllib.parse.unquote_plus(curl(cid,'/scanner/start','-b','/tmp/cookies','-D','-','--data-urlencode','csrf='+token))
  assert 'Collection disabled by operator configuration' in blocked,blocked[:300]
  assert 'torchnode-discovery-helper' not in docker('top',cid)
  if round_number==0:
   assert curl(cid,'/admin/users','-b','/tmp/cookies','-o','/dev/null','-w','%{http_code}','--data-urlencode','csrf='+token,'--data-urlencode','action=create','--data-urlencode','username=synthetic-viewer','--data-urlencode','password='+viewer_password,'--data-urlencode','role=VIEWER')=='302'
  authenticate(cid,'synthetic-viewer',viewer_password)
  assert curl(cid,'/api/v1/topology/graph','-b','/tmp/cookies','-o','/dev/null','-w','%{http_code}')=='200'
  for route in ['/admin/users','/api/v1/exports/analytics.csv']:
   assert curl(cid,route,'-b','/tmp/cookies','-o','/dev/null','-w','%{http_code}')=='403'
  for route in ['/scanner/start','/inspection/start','/data/clear']:
   assert curl(cid,route,'-b','/tmp/cookies','-X','POST','-o','/dev/null','-w','%{http_code}')=='403'
  docker('stop','--time','15',cid,timeout=25)
  state=json.loads(docker('inspect',cid))[0];assert state['State']['ExitCode'] in (0,143),'unclean shutdown'
  db=root/f'round-{round_number}.sqlite';docker('cp',cid+':/data/torchnode.db',str(db))
  with sqlite3.connect(db) as c:
   assert c.execute('select version from schema_migrations order by version').fetchall()==[(n,) for n in range(1,7)]
   assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
   assert not c.execute('pragma foreign_key_check').fetchall()
   assert c.execute('select count(*) from discovery_exchange_evidence').fetchone()[0]==0
  server_log=logs(cid)
  assert password not in server_log and viewer_password not in server_log and '$argon2id$' not in server_log,'sensitive server logging'
  assert 'Failed to load native library' not in server_log,'SQLite native loading failed'
  (root/f'round-{round_number}.log').write_text(server_log)
  report['rounds'].append({'startup':True,'login':True,'viewerRestrictions':True,'topologyReadOnly':True,'collectionStartBlocked':True,'schema':6,'integrity':'ok','exitCode':state['State']['ExitCode'],'databaseSha256':hashlib.sha256(db.read_bytes()).hexdigest()})
  docker('rm',cid);ids.remove(cid)
 report['status']='PASS'
except BaseException as error:
 report['error']=type(error).__name__+': '+str(error)
 raise
finally:
 for cid in ids:
  state=json.loads(docker('inspect',cid))[0]
  if label.split('=')[1]==state['Config']['Labels'].get('torchnode.synthetic'):
   (root/(cid+'.log')).write_text(logs(cid))
   (root/(cid+'.state.json')).write_text(json.dumps({'state':state['State'],'hostConfig':state['HostConfig']},indent=2)+'\n')
   docker('rm','-f',cid)
 if volume_owned:
  assert json.loads(docker('volume','inspect',volume))[0]['Labels'].get('torchnode.synthetic')==label.split('=')[1]
  docker('volume','rm',volume)
 (root/'report.json').write_text(json.dumps(report,indent=2)+'\n')
 print('Private synthetic container evidence:',root)
print(json.dumps(report,indent=2))
