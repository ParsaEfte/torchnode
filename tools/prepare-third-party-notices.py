#!/usr/bin/env python3
"""Collect candidate license evidence and source artifacts, without publication.

Inputs: resolved Maven BOM, Linux Go package metadata and go-licenses CSV.
Unknown/contradictory declarations are reported, never silently approved.
"""
import argparse,csv,hashlib,json,re,shutil,tarfile,zipfile
from urllib.parse import parse_qs,urlsplit
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('root',type=Path);parser.add_argument('--output-root',type=Path)
parser.add_argument('--bloomfilter-source',type=Path,help='Matching v2.0.3 upstream archive, including repository-root MIT-LICENSE.txt')
args=parser.parse_args()
source_root=args.root.resolve();root=(args.output_root or source_root).resolve();target=source_root/'target';dest=root/'third-party';dest.mkdir(exist_ok=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
report={'scope':'Maven runtime + Linux amd64 CGO-disabled helper packages','maven':[],'go':[],'unresolved':[],'distributionReview':'PENDING: copyleft/source obligations; no release publication authorized'}
licenses=re.compile(r'(^|[/_.-])(licen[sc]e|copying|notice|copyright|authors|about\.html)([._/-]|$)',re.I)
repository=Path.home()/'.m2/repository';bom=json.loads((target/'bom.json').read_text())
source_dest=target/'corresponding-sources/maven';source_dest.mkdir(parents=True,exist_ok=True)
for component in bom['components']:
 group,name,version=component['group'],component['name'],component['version'];relative=Path(group.replace('.','/'))/name/version;base=repository/relative
 classifier=parse_qs(urlsplit(component.get('purl','')).query).get('classifier',[''])[0]
 jar=base/(name+'-'+version+('-'+classifier if classifier else '')+'.jar');source=base/(name+'-'+version+'-sources.jar');pom=base/(name+'-'+version+'.pom')
 entry={'coordinate':group+':'+name+':'+version+(':'+classifier if classifier else ''),'licenses':component.get('licenses',[]),'artifactSha256':sha(jar),'texts':[],'sourceAvailable':source.is_file(),'sourceScope':'Published source JAR; native-binary rebuild completeness requires separate review'}
 if not entry['licenses']:report['unresolved'].append(entry['coordinate']+': missing license declaration')
 if not source.is_file():report['unresolved'].append(entry['coordinate']+': source JAR unavailable')
 headers=set()
 for archive in (jar,source):
  if not archive.is_file():continue
  with zipfile.ZipFile(archive) as z:
   for name_in_zip in z.namelist():
    if archive==source and name_in_zip.endswith(('.java','.kt','.c','.h')):
     content=z.read(name_in_zip).decode('utf-8',errors='replace')[:8192]
     prefix=content.split('package ',1)[0]
     if 'copyright' in prefix.lower() and prefix.lstrip().startswith(('/*','//')):headers.add(prefix.strip())
    if not name_in_zip.endswith(('/', '.class')) and licenses.search(name_in_zip):
     path=dest/'maven'/relative/archive.stem/Path(name_in_zip)
     if '..' in path.parts or Path(name_in_zip).is_absolute():raise ValueError('Unsafe archive entry')
     path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(z.read(name_in_zip));entry['texts'].append(str(path.relative_to(root)))
 if headers:
  path=dest/'maven'/relative/'SOURCE-NOTICES.txt';path.parent.mkdir(parents=True,exist_ok=True);path.write_text('\n\n---\n\n'.join(sorted(headers))+'\n');entry['texts'].append(str(path.relative_to(root)))
 for file in (jar,source,pom):
  if file.is_file():
   path=source_dest/relative/file.name;path.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(file,path)
 entry['sourceSha256']=sha(source) if source.is_file() else None
 report['maven'].append(entry)

stream=(target/'go-packages-linux-amd64.jsonstream').read_text();decoder=json.JSONDecoder();packages=[]
while stream.strip():item,end=decoder.raw_decode(stream.lstrip());stream=stream.lstrip()[end:];packages.append(item)
for name,url,license_id in csv.reader((target/'go-license-linux-amd64.csv').open()):
 matches=[p for p in packages if p['ImportPath']==name or p['ImportPath'].startswith(name+'/')]
 if not matches:report['unresolved'].append(name+': no compiled package metadata');continue
 module=matches[0]['Module'];directory=Path(module['Dir']);module_path=module['Path'];texts=[]
 if name=='github.com/holiman/bloomfilter/v2':
  license_id='MIT'
  if not args.bloomfilter_source:raise ValueError('Matching upstream bloomfilter archive required for repository-root license')
  with tarfile.open(args.bloomfilter_source) as upstream:
   members={m.name:m for m in upstream.getmembers()};prefix=next(n.rsplit('/MIT-LICENSE.txt',1)[0] for n in members if n.endswith('/MIT-LICENSE.txt'))
   for file in list(directory.glob('*.go'))+[directory/'go.mod']:
    expected=upstream.extractfile(members[prefix+'/v2/'+file.name]).read()
    if expected!=file.read_bytes():raise ValueError('Bloomfilter tag/module mismatch: '+file.name)
   p=dest/'go'/module_path/'MIT-LICENSE.txt';p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(upstream.extractfile(members[prefix+'/MIT-LICENSE.txt']).read());texts.append(str(p.relative_to(root)))
   report['bloomfilterLicenseProvenance']={'archiveSha256':sha(args.bloomfilter_source),'url':'https://github.com/holiman/bloomfilter/tree/v2.0.3','verification':'All module Go files and go.mod exactly match tagged v2 subtree; original repository-root license and v2 copyright declarations retained'}
  declaration=directory/'bloomfilter.go';p=dest/'go'/module_path/'LICENSE.declaration.txt';p.parent.mkdir(parents=True,exist_ok=True);p.write_text('\n'.join(declaration.read_text().splitlines()[:12])+'\n');texts.append(str(p.relative_to(root)))
 elif name=='github.com/jackpal/go-nat-pmp':license_id='Apache-2.0 (upstream LICENSE declaration and README)'
 elif name.startswith('github.com/gavinruff007/torchnode/p2p-helper'):license_id='MIT (project source; combined binary carries dependency obligations)'
 if license_id=='Unknown':report['unresolved'].append(name+': unknown license')
 for file in directory.rglob('*'):
  if file.is_file() and licenses.search(file.name) and file.stat().st_size<1_000_000:
   path=dest/'go'/module_path/file.relative_to(directory);path.parent.mkdir(parents=True,exist_ok=True)
   if path.exists():path.chmod(0o644)
   shutil.copyfile(file,path);texts.append(str(path.relative_to(root)))
 report['go'].append({'package':name,'module':module_path,'version':module.get('Version','candidate'),'license':license_id,'classifierUrl':url,'texts':sorted(set(texts))})

# Preserve the actual GPL-only source declaration; never relabel it LGPL.
geth=next(p['Module'] for p in packages if p.get('Module',{}).get('Path')=='github.com/ethereum/go-ethereum')
gpl=Path(geth['Dir'])/'trie/bintrie/bitarray.go';p=dest/'go/github.com/ethereum/go-ethereum/GPL-source-declaration.txt'
p.parent.mkdir(parents=True,exist_ok=True);p.write_text('\n'.join(gpl.read_text().splitlines()[:16])+'\n')
report['copyleftEvidence']={'compiledFile':'github.com/ethereum/go-ethereum/trie/bintrie/bitarray.go','sha256':sha(gpl),'declaration':str(p.relative_to(root)),'disposition':'GPL-3.0-or-later source/distribution obligations require review; not waived'}
manifest={str(p.relative_to(root)):sha(p) for p in dest.rglob('*') if p.is_file()}
report['licenseTextHashes']=manifest
(target/'third-party-license-inventory.json').write_text(json.dumps(report,indent=2)+'\n')
lines=['# Third-party notices — TorchNode Observatory candidate','',
 'TorchNode-owned source is MIT licensed. This does not license all dependencies or combined helper binaries as MIT.',
 'The RLPx helper includes compiled geth GPL-3.0-or-later code and LGPL-3.0-or-later library code. Java dependencies include LGPL and EPL components. Preserve their notices, corresponding source and replacement/rebuild rights. Distribution review is PENDING; this file grants no publication approval.',
 'The local corresponding-source bundle contains the helper source/vendor tree and resolved Maven runtime artifacts/source JARs. Rebuild instructions are in docs/third-party-licenses.md.', '',
 '## Maven runtime components','', '| Component | Declared license metadata |','| --- | --- |']
for row in report['maven']:
 names=[x.get('license',{}).get('id',x.get('license',{}).get('name',x.get('expression','UNKNOWN'))) for x in row['licenses']]
 lines.append('| '+row['coordinate']+' | '+', '.join(names)+' |')
lines+=['','## Go helper components (Linux amd64, CGO disabled)','','| Package | Version | License evidence |','| --- | --- | --- |']
for row in report['go']:lines.append('| '+row['package']+' | '+row['version']+' | '+row['license']+' |')
lines+=['','License/NOTICE texts are supplied in third-party/. Automated classifiers can miss per-file licensing. The GPL source declaration is preserved explicitly.','',
 'The bloomfilter v2 module source matches the tagged upstream v2 subtree; its repository-root MIT-LICENSE.txt and per-file copyright declarations are supplied. NAT-PMP has an Apache-2.0 declaration in LICENSE and README; its classifier unknown was reviewed against those files.','']
(root/'THIRD_PARTY_NOTICES.md').write_text('\n'.join(lines))
print(json.dumps({'maven':len(report['maven']),'go':len(report['go']),'textFiles':len(manifest),'unresolved':report['unresolved'],'distributionReview':report['distributionReview']}))
