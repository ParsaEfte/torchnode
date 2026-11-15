#!/usr/bin/env python3
"""Synthetic filesystem identity experiments only; no retained paths or Docker."""
import hashlib,importlib.util,json,os,platform,subprocess,sys,tempfile
from pathlib import Path
from types import SimpleNamespace
repo=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('recovery_identity',repo/'scripts/topology-recovery-rehearsal.py')
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
with tempfile.TemporaryDirectory(prefix='torchnode-device-synthetic-') as directory:
 root=Path(directory).resolve();p=root/'synthetic.txt';p.write_bytes(b'non-retained synthetic fixture')
 before=p.stat();digest=hashlib.sha256(p.read_bytes()).hexdigest();expected=r.retained_identity(before)
 with p.open('rb') as stream:descriptor=os.fstat(stream.fileno())
 native_args=['/usr/bin/stat','-f','%d:%i'] if platform.system()=='Darwin' else ['/usr/bin/stat','-c','%d:%i']
 native=subprocess.check_output([*native_args,str(p)],text=True).strip()
 child=json.loads(subprocess.check_output([sys.executable,'-c','import os,json,sys;s=os.stat(sys.argv[1]);print(json.dumps([s.st_dev,s.st_ino]))',str(p)],text=True))
 checks={'statEqualsFstat':(before.st_dev,before.st_ino)==(descriptor.st_dev,descriptor.st_ino),
         'pythonEqualsNativeStat':native==str(before.st_dev)+':'+str(before.st_ino),
         'sameHostChildContextAgrees':child==[before.st_dev,before.st_ino]}
 moved=root/'renamed.txt';p.rename(moved);checks['sameFilesystemRenamePreservesIdentityAndBytes']=r.retained_identity(moved.stat())==expected and hashlib.sha256(moved.read_bytes()).hexdigest()==digest
 alias=root/'alias.txt';alias.symlink_to(moved)
 try:r.canonical(alias);checks['aliasRejected']=False
 except ValueError:checks['aliasRejected']=True
 simulated=SimpleNamespace(**{attribute:getattr(before,attribute) for _,attribute in r.IDENTITY_FIELDS});simulated.st_dev+=2
 try:r.validate_retained_identity(expected,simulated);checks['deviceOnlyDifferenceRejected']=False
 except ValueError as error:
  mismatches=json.loads(str(error).split(': ',1)[1]);checks['deviceOnlyDifferenceRejected']=set(mismatches)=={'device'} and mismatches['device']=={'expected':before.st_dev,'actual':before.st_dev+2}
 checks['integerJsonRoundTrip']=json.loads(json.dumps(expected))==expected
 result={'syntheticOnly':True,'checks':checks,'status':'PASS' if all(checks.values()) else 'FAIL','policyChanged':False,'mountsOrRebootsPerformed':False,'retainedSourceAccess':False,'limitations':'No remount/reboot/APFS namespace change was reproduced; simulated device mismatch does not establish the retained-source cause.'}
 print(json.dumps(result,indent=2))
 if not all(checks.values()):raise SystemExit(1)
