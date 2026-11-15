#!/usr/bin/env python3
"""Reconcile scanner license gaps with preserved component/source evidence, without approving distribution."""
import argparse,hashlib,json
from pathlib import Path
from urllib.parse import unquote

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('inventory',type=Path);p.add_argument('runtime_audit',type=Path);p.add_argument('image_sbom',type=Path);p.add_argument('maven_sbom',type=Path);p.add_argument('output',type=Path)
p.add_argument('--maven-directory',type=Path,required=True)
a=p.parse_args();inventory=json.loads(a.inventory.read_text());runtime=json.loads(a.runtime_audit.read_text())
maven={':'.join(x['coordinate'].split(':')[:3]):x for x in inventory['maven']}
go={x['module']:x for x in inventory['go']};apk={x['P']:x for x in runtime['packages']}
by_sha256={x['artifactSha256']:x for x in inventory['maven']};by_sha1={}
for jar in a.maven_directory.glob('*.jar'):
 data=jar.read_bytes();record=by_sha256.get(hashlib.sha256(data).hexdigest())
 if record is None:raise ValueError('Uninventoried dependency JAR: '+jar.name)
 by_sha1[hashlib.sha1(data).hexdigest()]=record
if len(by_sha1)!=len(inventory['maven']):raise ValueError('Incomplete exact dependency JAR set')
rows=[];unknown=[]
for source in (a.image_sbom,a.maven_sbom):
 for component in json.loads(source.read_text())['components']:
  if component.get('type')=='file':continue
  url=unquote(component.get('purl','').split('?')[0]);license=None;evidence=None
  if url.startswith('pkg:golang/'):
   module,version=url.removeprefix('pkg:golang/').rsplit('@',1)
   if module=='stdlib':license='BSD-3-Clause';evidence='Exact Go1.26.11 LICENSE/PATENTS and source archive'
   elif module in go:
    row=go[module];assert version==row['version'],(module,version,row['version'])
    license=row['license'];evidence='Compiled-package inventory and preserved upstream notices'
    if module=='github.com/ethereum/go-ethereum':license='GPL-3.0-or-later AND LGPL-3.0-or-later (actual compiled-file declarations)'
  elif url.startswith('pkg:maven/'):
   coordinate=url.removeprefix('pkg:maven/').replace('/',':',1).replace('@',':')
   if coordinate in maven:license=maven[coordinate]['licenses'];evidence='Resolved POM/source JAR/NOTICE and binary hashes'
   elif coordinate.startswith('io.github.gavinruff007:torchnode:'):license='MIT (own source); combined components retain their terms';evidence='Project LICENSE and full dependency inventory'
   elif coordinate.startswith('jrt-fs:jrt-fs:'):license='GPL-2.0-only WITH Classpath-exception-2.0';evidence='Exact Temurin JRE legal directory/source archive'
   if license is None:
    hashes=[h['content'] for r in component.get('externalReferences',[]) for h in r.get('hashes',[]) if h['alg']=='SHA-1']
    found=[by_sha1[h] for h in hashes if h in by_sha1]
    if len(found)==1:license=found[0]['licenses'];evidence='Exact JAR SHA-256 inventory and scanner SHA-1 identity; scanner heuristic GAV is not trusted'
  elif url.startswith('pkg:apk/alpine/'):
   name=url.removeprefix('pkg:apk/alpine/').rsplit('@',1)[0]
   if name in apk:license=apk[name]['L'];evidence='Original APK record and exact version/commit source recipe'
  elif url.startswith('pkg:generic/oracle/openjdk@'):
   license='GPL-2.0-only WITH Classpath-exception-2.0; per-file JRE notices/exceptions apply';evidence='Exact official Temurin source/binary metadata and complete legal directory'
  elif component.get('type')=='operating-system' and component.get('name')=='alpine':
   license='Aggregate: see four component license records';evidence='OS identity, not an independent package or blanket MIT assertion'
  row={'catalog':source.name,'name':component['name'],'version':component.get('version'),'purl':component.get('purl'),'scannerLicenseMissing':not component.get('licenses'),'licenseEvidence':license,'evidence':evidence}
  rows.append(row)
  if license is None:unknown.append(row)
report={'status':'INVENTORY_PASS_REVIEW_PENDING' if not unknown else 'UNRESOLVED','mavenRuntimeComponents':len(inventory['maven']),'goLicenseEntries':len(inventory['go']),'runtimeApkComponents':len(apk),'assessedCatalogEntries':len(rows),'rawMissingLicenseEntries':sum(x['scannerLicenseMissing'] for x in rows),'unassessed':unknown,'catalogHashes':{str(s):hashlib.sha256(s.read_bytes()).hexdigest() for s in (a.image_sbom,a.maven_sbom)},'distributionApproved':False,'candidateElection':{'JNR-POSIX':'LGPL-2.1 alternative; root LICENSE and original alternatives preserved'},'operatorReview':['GPL combined-helper scope versus separate Java process; EPL/GPL compatibility if treated as a single work','Final distribution/source access arrangements and matching-source review before GitHub/container publication; no publication performed'],'components':rows}
a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k not in ('components','catalogHashes')}))
if unknown:raise SystemExit(2)
