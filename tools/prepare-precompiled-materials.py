#!/usr/bin/env python3
"""Prepare matching experimental source/notices and replaceable Java layout. No publication."""
import argparse,copy,gzip,hashlib,io,json,tarfile,zipfile
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--baseline',type=Path,required=True);p.add_argument('--baseline-sha256',required=True);p.add_argument('--review',type=Path,required=True);p.add_argument('--jar',type=Path,required=True);p.add_argument('--jar-sha256',required=True);p.add_argument('--ant',type=Path,required=True);p.add_argument('--ant-launcher',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
def sha(path):
 with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
if sha(a.baseline)!=a.baseline_sha256 or sha(a.jar)!=a.jar_sha256:raise ValueError('Input hash mismatch')
if a.output.exists():raise ValueError('Output collision')
a.output.mkdir(parents=True,mode=0o700);prefix='torchnode-precompiled-materials/';updates={};root=a.review/'source';review=json.loads((a.review/'review-manifest.json').read_text())
for name,digest in review['sourceFileHashes'].items():
 path=root/name
 if sha(path)!=digest:raise ValueError('Review source changed')
 updates[prefix+'application/'+name]=path.read_bytes()
with zipfile.ZipFile(a.jar) as jar:
 if any(n.startswith('org/eclipse/jdt/') for n in jar.namelist()):raise ValueError('ECJ still present')
 buffer=io.BytesIO()
 with zipfile.ZipFile(buffer,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as thin:
  for name in sorted(jar.namelist()):
   if name.endswith('/') or not name.startswith(('io/github/gavinruff007/torchnode/','org/apache/jsp/','webapp/','legal/','torchnode-precompiled-jsp.properties')):continue
   info=zipfile.ZipInfo(name,(2026,11,13,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;thin.writestr(info,jar.read(name))
 updates[prefix+'replaceable-java/torchnode.jar']=buffer.getvalue()
updates[prefix+'artifacts/torchnode-precompiled.jar']=a.jar.read_bytes()
for path in [a.ant,a.ant_launcher]:
 updates[prefix+'build-tools/'+path.name]=path.read_bytes()
 with zipfile.ZipFile(path) as jar:
  for name in jar.namelist():
   if name.startswith('META-INF/') and Path(name).name.upper().startswith(('LICENSE','NOTICE')):updates[prefix+'build-tools/notices/'+path.stem+'/'+Path(name).name]=jar.read(name)
updates[prefix+'README.txt']=b'v1.0.0 MATCHING MATERIALS; OWNER-DIRECTED COMPLIANCE MATERIALS; ACTUAL PUBLIC DELIVERY REQUIRED.\nApplication source and artifacts are the precompiled variant. ECJ is a build-only compiler; runtime lib excludes ECJ. Ant build-tool JARs retain their original notices. Helper/vendor, runtime and upstream corresponding source archives remain unchanged. Public distribution still requires qualified review. Use replaceable-java/torchnode.jar with lib/* for LGPL library replacement. No retained database, usable approval or retained-checkpoint diagnostics are supplied.\n'
updates[prefix+'ARTIFACT-BINDING.json']=(json.dumps({'precompiledJarSha256':a.jar_sha256,'sourceReviewArchiveSha256':review['sourceArchiveSha256'],'baseSourceArchiveSha256':a.baseline_sha256,'runtimeEcj':False,'ecjBuildOnly':True,'legalApproval':False,'imageDigest':'Bind separately after image validation; original recovery image unchanged'},indent=2)+'\n').encode()
with tarfile.open(a.baseline) as old:
 members={};oldprefix='torchnode-corresponding-sources/'
 for member in old:
  name=member.name.removeprefix(oldprefix)
  if not member.name.startswith(oldprefix) or not member.isfile():continue
  if name.startswith('application/') or name.endswith('.log') or name in ['README.txt','ARTIFACT-BINDING.json','SOURCE-SHA256SUMS','replaceable-java/torchnode.jar','helper/p2p-helper']:continue
  new=prefix+name
  if name.startswith('replaceable-java/lib/') and 'ecj' in Path(name).name.lower():new=prefix+'build-tools/'+Path(name).name
  if name=='upstream-projects/source-jar-project-coverage.json':
   updates[new]=old.extractfile(member).read().replace((str(Path.home())+'/').encode(),b'/LOCAL-BUILD-CACHE/')
   continue
  members[new]=member
 inventory={name:hashlib.sha256(data).hexdigest() for name,data in updates.items()}
 for name,member in members.items():
  with old.extractfile(member) as stream:inventory[name]=hashlib.file_digest(stream,'sha256').hexdigest()
 updates[prefix+'SOURCE-SHA256SUMS']=''.join(d+'  '+name[len(prefix):]+'\n' for name,d in sorted(inventory.items())).encode()
 records=[]
 for index in [1,2]:
  target=a.output/f'corresponding-sources-{index}.tar.gz'
  with target.open('xb') as raw,gzip.GzipFile(filename='',fileobj=raw,mode='wb',mtime=0) as gz,tarfile.open(fileobj=gz,mode='w',format=tarfile.PAX_FORMAT) as archive:
   for name in sorted(set(members)|set(updates)):
    info=tarfile.TarInfo(name);info.mode=0o644;info.mtime=1794528000
    if name in updates:data=updates[name];info.size=len(data);archive.addfile(info,io.BytesIO(data))
    else:
     member=members[name];info.size=member.size
     with old.extractfile(member) as stream:archive.addfile(info,stream)
  records.append({'path':str(target),'sha256':sha(target),'bytes':target.stat().st_size})
if records[0]['sha256']!=records[1]['sha256']:raise ValueError('Corresponding source reproduction mismatch')
report={'status':'MATCHING_MATERIALS_PREPARED_DISTRIBUTION_REVIEW_PENDING','archives':records,'files':len(inventory)+1,'ecjRuntimeLibraryOmitted':not any('/replaceable-java/lib/' in n and 'ecj' in Path(n).name.lower() for n in inventory),'applicationSource':review['sourceArchiveSha256'],'legalApproval':False,'recoveryPackageRebound':False}
(a.output/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
