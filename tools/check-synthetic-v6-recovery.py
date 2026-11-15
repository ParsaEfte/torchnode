#!/usr/bin/env python3
"""Exercise the complete gated harness using newly generated synthetic data only."""
import hashlib,json,os,re,subprocess,sys,tempfile,uuid
from pathlib import Path

image=sys.argv[1];jar=Path(sys.argv[2]).resolve();repo=Path(__file__).resolve().parents[1]
if not re.fullmatch('sha256:[0-9a-f]{64}',image):raise ValueError('Immutable image ID required')
os.umask(0o077);root=Path(tempfile.mkdtemp(prefix='torchnode-populated-v6-')).resolve()
(root/'V6-FIXTURE.json').write_text('{"synthetic":true}')
env={k:v for k,v in os.environ.items() if not k.startswith(('TORCHNODE_','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS'))}
env.update(TORCHNODE_COLLECTION_DISABLED='true',TORCHNODE_SECURE_COOKIES='false')
subprocess.run(['java','-cp',str(jar),str(repo/'tools/CreateSyntheticV6Recovery.java'),str(root),str(repo/'src/test/resources/discv5-trace-v2-valid.json')],check=True,env=env,timeout=45)
classes=root/'probe'
subprocess.run(['javac','--release','21','-cp',str(jar),'-d',str(classes),str(repo/'tools/V6RecoveryProbe.java')],check=True,timeout=30)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
pins={'containerImageId':image,'application':{'path':str(jar),'sha256':sha(jar)},'probeClasses':str(classes),
      'probeHashes':{str(p.relative_to(classes)):sha(p) for p in classes.rglob('*.class')},
      'offlineVerifierSha256':sha(repo/'scripts/topology-migration.py'),'rehearsalScriptSha256':sha(repo/'scripts/topology-recovery-rehearsal.py')}
manifest=root/'synthetic-artifacts.json';manifest.write_text(json.dumps(pins,indent=2)+'\n')
work=Path(tempfile.gettempdir()).resolve()/('v6-rehearsal-fixture-'+uuid.uuid4().hex)
command=['python3',str(repo/'scripts/topology-recovery-rehearsal.py'),'execute','--mode','fixture','--source',str(root/'fixture-v6.sqlite'),'--workspace',str(work),'--artifact-manifest',str(manifest),'--image',image]
subprocess.run(command,check=True,env=env,timeout=660)
print('Synthetic source:',root,'\nPrivate harness evidence:',work)
