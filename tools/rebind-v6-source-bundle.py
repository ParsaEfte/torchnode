#!/usr/bin/env python3
"""Repack existing verified source materials with the host-only recovery diagnostics patch.
No extraction, database access, Docker calls or authorization creation.
"""
import argparse,copy,gzip,hashlib,io,json,tarfile
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('source',type=Path);p.add_argument('output',type=Path)
p.add_argument('--expected-source-sha256',required=True)
a=p.parse_args();root=Path(__file__).resolve().parents[1]
def digest(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
if digest(a.source)!=a.expected_source_sha256:raise ValueError('Original source archive hash mismatch')
if a.output.exists():raise ValueError('Output already exists; no overwrite')
prefix='torchnode-corresponding-sources/'
updates={prefix+'application/'+name:(root/name).read_bytes() for name in
 ('scripts/topology-recovery-rehearsal.py','tools/check-v6-recovery-rehearsal.py','tools/rebind-v6-source-bundle.py','tools/fixtures/v6-recorded-identity.json')}
with tarfile.open(a.source,'r:gz') as source:
 members={}
 for member in source:
  path=Path(member.name)
  if path.is_absolute() or '..' in path.parts or not member.name.startswith(prefix) or member.name in members:
   raise ValueError('Unsafe/duplicate source archive member')
  if not (member.isfile() or member.isdir()):raise ValueError('Unexpected source archive link/special file')
  members[member.name]=member
 binding=json.loads(source.extractfile(prefix+'ARTIFACT-BINDING.json').read())
 binding.update(recoveryExecutorSha256=hashlib.sha256(updates[prefix+'application/scripts/topology-recovery-rehearsal.py']).hexdigest(),
                recoveryDiagnosticsScope='Host executor diagnostics/tests only; application image/JAR unchanged; new package approval required')
 updates[prefix+'ARTIFACT-BINDING.json']=(json.dumps(binding,indent=2)+'\n').encode()
 hashes={}
 for name,member in members.items():
  if member.isfile() and name!=prefix+'SOURCE-SHA256SUMS':
   if name in updates:hashes[name]=hashlib.sha256(updates[name]).hexdigest()
   else:
    with source.extractfile(member) as stream:hashes[name]=hashlib.file_digest(stream,'sha256').hexdigest()
 for name,data in updates.items():hashes[name]=hashlib.sha256(data).hexdigest()
 original={}
 for line in source.extractfile(prefix+'SOURCE-SHA256SUMS').read().decode().splitlines():
  value,name=line.split('  ',1);original[prefix+name]=value
 for name,value in original.items():
  if name not in updates and hashes.get(name)!=value:raise ValueError('Original source inventory mismatch: '+name)
 if set(original)!={name for name,m in members.items() if m.isfile() and name!=prefix+'SOURCE-SHA256SUMS'}:
  raise ValueError('Incomplete original source inventory')
 updates[prefix+'SOURCE-SHA256SUMS']=''.join(value+'  '+name[len(prefix):]+'\n' for name,value in sorted(hashes.items())).encode()
 with a.output.open('xb') as raw,gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0,compresslevel=6) as compressed,tarfile.open(fileobj=compressed,mode='w',format=tarfile.PAX_FORMAT) as target:
  for name in sorted(set(members)|set(updates)):
   member=copy.copy(members[name]) if name in members else tarfile.TarInfo(name)
   member.uid=member.gid=0;member.uname=member.gname='';member.mtime=1794528000;member.pax_headers={}
   if name in updates:
    data=updates[name];member.size=len(data);member.type=tarfile.REGTYPE;member.mode=0o644
    target.addfile(member,io.BytesIO(data))
   elif member.isfile():
    with source.extractfile(members[name]) as stream:target.addfile(member,stream)
   else:target.addfile(member)
print(json.dumps({'archiveSha256':digest(a.output),'bytes':a.output.stat().st_size,'sourceFiles':len(hashes)+1,'updatedMembers':sorted(updates)}))
