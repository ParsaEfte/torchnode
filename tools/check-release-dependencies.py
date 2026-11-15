#!/usr/bin/env python3
"""OSV batch inventory scan, one request per batch; never retries."""
import argparse,hashlib,json,re,subprocess,sys,tempfile
from pathlib import Path
from dependency_ranges import affected,github_affected,maven_query
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('root',type=Path);parser.add_argument('out',type=Path)
parser.add_argument('--go-db',type=Path);parser.add_argument('--go-db-revision')
parser.add_argument('--github-supplement',action='append',type=Path,default=[])
args=parser.parse_args();root=args.root;out=args.out;queries=[]
if args.go_db and not re.fullmatch('[0-9a-f]{40}',args.go_db_revision or ''):parser.error('Pinned official Go database commit required')
for line in (root/'target/runtime-dependencies.txt').read_text().splitlines():
 query=maven_query(line)
 if query:queries.append(query)
if not queries:raise ValueError('Resolved Maven runtime inventory is empty')
modules=subprocess.check_output(['go','list','-m','-f','{{if .Version}}{{.Path}} {{.Version}}{{end}}','all'],cwd=root/'p2p-helper',text=True)
go_queries=[]
for line in modules.splitlines():
 name,version=line.split();go_queries.append({'package':{'ecosystem':'Go','name':name},'version':version})
rows=[];errors=[];database=None
if args.go_db:
 files=sorted(args.go_db.glob('GO-*.json'))
 if len(files)<1000:raise ValueError('Full official Go OSV snapshot required, not selected advisories')
 by_module={};digest=hashlib.sha256()
 for path in files:
  raw=path.read_bytes();digest.update(path.name.encode()+b'\0'+hashlib.sha256(raw).digest());entry=json.loads(raw)
  if entry.get('withdrawn'):continue
  for item in entry.get('affected',[]):
   if item['package']['ecosystem'].lower()=='go':by_module.setdefault(item['package']['name'],[]).append((entry,item))
 supplements=[json.loads(p.read_text()) for p in args.github_supplement]
 database={'revision':args.go_db_revision,'entries':len(files),'contentSha256':digest.hexdigest(),'supplements':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in args.github_supplement}}
 for query in go_queries:
  found={}
  try:
   for entry,item in by_module.get(query['package']['name'],[]):
    if affected(query['version'],item.get('ranges',[])) or query['version'].lstrip('v') in item.get('versions',[]):found[entry['id']]={'id':entry['id'],'ranges':item.get('ranges',[]),'url':'https://pkg.go.dev/vuln/'+entry['id']}
   for entry in supplements:
    if entry.get('withdrawn_at'):continue
    for item in entry['vulnerabilities']:
     if item['package']['ecosystem'].lower()=='go' and item['package']['name']==query['package']['name'] and github_affected(query['version'],item['vulnerable_version_range']):found[entry['ghsa_id']]={'id':entry['ghsa_id'],'range':item['vulnerable_version_range'],'url':entry['html_url']}
   rows.append(dict(query,source='official-go-osv-snapshot+github-supplements',advisories=list(found.values())))
  except (KeyError,ValueError) as error:rows.append(dict(query,unresolved=str(error),advisories=[]))
else:queries+=go_queries
with tempfile.TemporaryDirectory(prefix='torchnode-osv-') as tmp:
 for index in range(0,len(queries),75):
  batch=queries[index:index+75];q=Path(tmp)/'query.json';r=Path(tmp)/'result.json';q.write_text(json.dumps({'queries':batch}))
  p=subprocess.run(['curl','--fail-with-body','--max-time','60','-sS','-H','Content-Type: application/json','--data-binary','@'+str(q),'https://api.osv.dev/v1/querybatch','-o',str(r)],capture_output=True,text=True)
  try:
   if p.returncode:raise ValueError(p.stderr.strip())
   results=json.loads(r.read_text())['results']
   if len(results)!=len(batch):raise ValueError('Incomplete OSV response')
   for query,result in zip(batch,results):rows.append(dict(query,source='osv-querybatch',advisories=result.get('vulns',[])))
  except (KeyError,ValueError) as error:
   errors.append({'offset':index,'count':len(batch),'error':str(error)})
   rows.extend(dict(query,unresolved=str(error),advisories=[]) for query in batch)
unresolved=[r for r in rows if 'unresolved' in r];findings=[r for r in rows if r['advisories']]
report={'status':'PARTIAL' if unresolved else 'COMPLETE','scope':'Maven runtime + Go module graph; inventory matching, not reachability or OS scan','packageCount':len(rows),'scannedCount':len(rows)-len(unresolved),'unresolvedCount':len(unresolved),'findingPackageCount':len(findings),'goDatabase':database,'errors':errors,'packages':rows}
out.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k not in ('packages','goDatabase')}));print('findings',[(r['package']['name'],[a['id'] for a in r['advisories']]) for r in findings])
sys.exit(2 if unresolved else 1 if findings else 0)
