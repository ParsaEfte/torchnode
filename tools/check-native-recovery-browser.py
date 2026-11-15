#!/usr/bin/env python3
"""Create only synthetic storage and run native Chrome against the recovery adapter."""
from pathlib import Path
import tempfile,subprocess,os,time,sys,hashlib,json
root=Path(sys.argv[1]).resolve();repo=Path(__file__).resolve().parents[1];jar=root/'target/torchnode-1.0.0-jar-with-dependencies.jar'
fixture=Path(tempfile.mkdtemp(prefix='torchnode-native-browser-')).resolve()
environment={k:v for k,v in os.environ.items() if not k.startswith(('TORCHNODE_','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'))}
environment.update(TORCHNODE_COLLECTION_DISABLED='true',TORCHNODE_SECURE_COOKIES='false')
(fixture/'V6-FIXTURE.json').write_text('{"synthetic":true}');(fixture/'V6-REHEARSAL').touch()
source=fixture/'CreateSynthetic.java';source.write_text('import io.github.gavinruff007.torchnode.storage.SqliteNodeStore; class CreateSynthetic {public static void main(String[] a)throws Exception{try(var s=new SqliteNodeStore(a[0])){}}}')
subprocess.run(['java','-cp',str(jar),str(source),str(fixture/'working-v6.sqlite')],check=True,capture_output=True,env=environment)
subprocess.run(['javac','--release','21','-cp',str(jar),'-d',str(fixture/'probe'),str(repo/'tools/V6RecoveryProbe.java')],check=True,env=environment)
with (fixture/'server.log').open('w') as log:
 p=subprocess.Popen(['java','-cp',str(jar)+':'+str(fixture/'probe'),'io.github.gavinruff007.torchnode.dashboard.V6RecoveryProbe',str(fixture),'--browser-fixture'],env=environment,stdout=log,stderr=log)
 try:
  end=time.monotonic()+20
  while not (fixture/'browser-session.json').exists():
   if time.monotonic()>end or p.poll() is not None:raise RuntimeError('fixture not ready: '+str(fixture))
   time.sleep(.1)
  subprocess.run(['node',str(repo/'tools/check-v6-browser.mjs'),str(fixture),*sys.argv[2:]],cwd=repo,timeout=90,check=True,env=environment)
  report=repo/'docs/validation/v6-synthetic-browser.json';value=json.loads(report.read_text());value['applicationSha256']=hashlib.sha256(jar.read_bytes()).hexdigest();report.write_text(json.dumps(value,indent=2)+'\n')
 finally:
  (fixture/'BROWSER-DONE').touch()
  try:p.wait(timeout=15)
  except subprocess.TimeoutExpired:p.terminate();p.wait(timeout=10)
print('Synthetic browser workspace:',fixture)
