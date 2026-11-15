#!/usr/bin/env python3
"""Start a bounded, synthetic-only human review fixture. Never records acceptance."""
import argparse,hashlib,json,os,subprocess,tempfile,time
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--jar',type=Path,required=True);p.add_argument('--expected-sha256',required=True);p.add_argument('--jdk',type=Path,required=True);p.add_argument('--seconds',type=int,default=600);a=p.parse_args()
if not 2<=a.seconds<=600:raise ValueError('Review duration must be 2–600 seconds')
with a.jar.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
if digest!=a.expected_sha256:raise ValueError('JAR identity mismatch')
os.umask(0o077);root=Path(tempfile.mkdtemp(prefix='torchnode-human-accessibility-')).resolve();(root/'SYNTHETIC-ONLY').touch();classes=root/'classes';classes.mkdir()
env={k:v for k,v in os.environ.items() if not k.startswith(('TORCHNODE_','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'))};env['TORCHNODE_COLLECTION_DISABLED']='true'
subprocess.run([str(a.jdk/'bin/javac'),'--release','21','-cp',str(a.jar.resolve()),'-d',str(classes),str(Path(__file__).with_name('HumanAccessibilityFixture.java'))],check=True,capture_output=True,timeout=30)
with (root/'server.log').open('x') as log:
 child=subprocess.Popen([str(a.jdk/'bin/java'),'-Xmx512m','-cp',str(classes)+os.pathsep+str(a.jar.resolve()),'io.github.gavinruff007.torchnode.dashboard.HumanAccessibilityFixture',str(root),str(a.seconds)],env=env,stdout=log,stderr=log)
 try:
  deadline=time.monotonic()+25
  while not (root/'session.json').exists():
   if child.poll() is not None or time.monotonic()>deadline:raise RuntimeError('Fixture startup failed; inspect '+str(root/'server.log'))
   time.sleep(.1)
  session=json.loads((root/'session.json').read_text());print('Synthetic review URL:',session['url'],flush=True);print('Private synthetic credentials:',root/'session.json',flush=True);print('Stop with Ctrl-C; automatic shutdown in',a.seconds,'seconds. Acceptance remains PENDING.',flush=True);child.wait(timeout=a.seconds+20)
 finally:
  if child.poll() is None:
   child.terminate()
   try:child.wait(timeout=15)
   except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)
