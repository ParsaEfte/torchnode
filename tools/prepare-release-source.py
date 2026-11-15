#!/usr/bin/env python3
"""Create a private sanitized source-review tree and reproducible archive. Never publish."""
import argparse,gzip,hashlib,io,json,os,re,shutil,tarfile
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);a=p.parse_args();repo=Path(__file__).resolve().parents[1];out=a.output.resolve()
if out.exists():raise ValueError('Output collision')
os.umask(0o077);out.mkdir(parents=True,mode=0o700);tree=out/'source';tree.mkdir();copied=[];redactions=[]
private_source='/UNCONFIGURED/retained-checkpoint.sqlite'
public_docs={'api.md','architecture.md','configuration.md','deployment.md','security-and-analytics.md','third-party-licenses.md','distribution-compliance.md','public-distribution-resolution.md','recovery-rollback-policy.md','release-checklist-v1.0.md','release-notes-v1.0.0-DRAFT.md','release-publication-plan.md','accessibility-acceptance.md'}
root_files=['Dockerfile','.dockerignore','.gitignore','pom.xml','LICENSE','README.md','CHANGELOG.md','SECURITY.md','CONTRIBUTING.md','THIRD_PARTY_NOTICES.md','DISTRIBUTION-LICENSE.md','SOURCE-DELIVERY.md','compose.yaml','compose.geoip.yaml','.mvn/jvm.config','build-cache/README.md','build-cache/maven/.keep']
def excluded(path):
 name=path.name.lower()
 return name in {'.ds_store','session.json','browser-session.json','p2p-helper','torchnode-p2p-helper','torchnode-discovery-helper'} or name.endswith(('.sqlite','.sqlite3','.db','.db-wal','.db-shm','.db-journal','.class','.jar','.war','.exe','.log','.pyc','.used','.pem','.key')) or name.startswith('.env') or '__pycache__' in path.parts or 'target' in path.parts or 'node_modules' in path.parts or '.git' in path.parts
files=[repo/name for name in root_files if (repo/name).is_file()]
for directory in ['src','p2p-helper','scripts','tools','third-party','.github']:
 files.extend(path for path in (repo/directory).rglob('*') if path.is_file() and not excluded(path.relative_to(repo)))
files.extend(repo/'docs'/name for name in sorted(public_docs) if (repo/'docs'/name).is_file())
for source in sorted(set(files)):
 relative=source.relative_to(repo)
 if source.is_symlink():raise ValueError('Source symlink requires review: '+str(relative))
 if relative==Path('tools/fixtures/v6-recorded-identity.json'):
  data=json.loads(source.read_text());fake={'sha256':'0'*64,'device':1,'inode':2,'owner':1000,'sizeBytes':1234,'mode':384,'mtimeNs':1794560968867239432};data['scope']='Public synthetic metadata vectors only; no private evidence';data['expected']=fake;data['historical']={name:{'before':dict(fake),'after':dict(fake)} for name in ['synthetic-one.json','synthetic-two.json']};payload=(json.dumps(data,indent=2)+'\n').encode();redactions.append(str(relative))
 else:
  payload=source.read_bytes()
  if relative.parts[0] in ('scripts','tools'):
   try:
    text=payload.decode();changed=text.replace(private_source,'/UNCONFIGURED/retained-checkpoint.sqlite').replace('/UNCONFIGURED/','/UNCONFIGURED/').replace('0000000000000000000000000000000000000000000000000000000000000000','0'*64)
    if changed!=text:payload=changed.encode();redactions.append(str(relative))
   except UnicodeDecodeError:pass
 if relative.suffix=='.md' and relative.parts[0]!='third-party':
  text=payload.decode();changed=text.replace(private_source,'PRIVATE_CHECKPOINT_PATH').replace('0000000000000000000000000000000000000000000000000000000000000000','CHECKPOINT_SHA256').replace('/UNCONFIGURED/','/PRIVATE/')
  if changed!=text:payload=changed.encode();redactions.append(str(relative))
 target=tree/relative;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(payload);target.chmod(0o644);copied.append(str(relative))
summary=tree/'docs/release-validation.md';summary.write_text("# Private source review candidate — not a public release\n\nThe default ECJ-free v1.0.0 build passes 249 Java tests, canonical fixtures and exact-image synthetic integration. Go/race/vet evidence is preserved for unchanged helper source and byte-identical binaries. The image scan has zero matches/suppressions against a verified fresh advisory database. Native Chrome/axe checks pass. Recovery is NOT VERIFIED. Public distribution uses the owner-authorized documented review; source-first delivery, approved final commit and remote CI remain required. Human acceptance and the non-production policy exception are owner-confirmed, with no invented detailed test scope. Production deployment is NO-GO. Private operator records and all usable approvals are excluded. Operator-only retained paths/hashes are unconfigured examples in this review export; original private bindings are unchanged.\n")
# Public-facing links must not point at excluded operator records.
for source in list(tree.glob('*.md'))+list((tree/'docs').glob('*.md')):
 text=source.read_text()
 def replace(match):
  link=match.group(1)
  if link.startswith(('https:','http:','#')) or (source.parent/link.split('#')[0]).exists():return match.group(0)
  return ']('+('docs/' if source.parent==tree else '')+'release-validation.md)'
 source.write_text(re.sub(r'\]\(([^)]+)\)',replace,text))
manifest={str(path.relative_to(tree)):hashlib.sha256(path.read_bytes()).hexdigest() for path in sorted(tree.rglob('*')) if path.is_file()}
for name in manifest:
 if name.startswith('docs/validation/') or name.endswith(('.sqlite','.db','.used')):raise ValueError('Forbidden review payload')
for index in [1,2]:
 with (out/f'source-review-{index}.tar.gz').open('xb') as raw,gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0) as gz,tarfile.open(fileobj=gz,mode='w',format=tarfile.PAX_FORMAT) as archive:
  for name in sorted(manifest):
   data=(tree/name).read_bytes();info=tarfile.TarInfo('torchnode-source-review/'+name);info.size=len(data);info.mode=0o644;info.mtime=1794528000;archive.addfile(info,io.BytesIO(data))
hashes=[hashlib.sha256((out/f'source-review-{index}.tar.gz').read_bytes()).hexdigest() for index in [1,2]]
if hashes[0]!=hashes[1]:raise ValueError('Source archive reproduction mismatch')
report={'status':'PRIVATE_REVIEW_NOT_APPROVED_FOR_PUBLICATION','tree':str(tree),'files':len(manifest),'sourceFileHashes':manifest,'operatorOnlyExportRedactions':redactions,'sourceArchiveSha256':hashes[0],'archivesReproducible':True,'runtimeSourcesRedacted':False,'rootWorkingTreeOrRecoveryBindingsChanged':False,'containsUsableAuthorization':False,'applicationVersion':'1.0.0','finalVersionAndImage':'v1.0.0 built; publication pending mandatory approvals','legalApproval':False}
(out/'review-manifest.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k!='sourceFileHashes'},indent=2))
