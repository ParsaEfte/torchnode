#!/usr/bin/env python3
"""Run inventory and Go symbol gates against pinned official advisory inputs.

Only source/build inputs are read. No application database or Docker connection.
The advisory snapshot must be refreshed/reviewed for each release candidate.
"""
import argparse,datetime,hashlib,json,os,subprocess,sys,tarfile,tempfile
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('root',type=Path)
parser.add_argument('--go-db',type=Path,help='Existing full official OSV directory; content hash must match policy')
parser.add_argument('--govulncheck',default='govulncheck')
args=parser.parse_args();repo=Path(__file__).resolve().parents[1];root=args.root.resolve()
policy=json.loads((repo/'tools/security-policy.json').read_text());pins=policy['goDatabase']
reviewed=datetime.datetime.fromisoformat(policy['reviewedAt'])
age=datetime.datetime.now(datetime.timezone.utc)-reviewed
if age<datetime.timedelta(0) or age>datetime.timedelta(days=policy['maximumAdvisorySnapshotAgeDays']):raise ValueError('Security snapshot review is stale or future-dated; refresh and review policy inputs')
supplement=repo/policy['githubSupplement']['path']
if hashlib.sha256(supplement.read_bytes()).hexdigest()!=policy['githubSupplement']['sha256']:raise ValueError('Supplement binding changed')
with tempfile.TemporaryDirectory(prefix='torchnode-security-') as temporary:
 temporary=Path(temporary)
 if args.go_db:database=args.go_db
 else:
  archive=temporary/'database.tar.gz';database=temporary/'osv';database.mkdir()
  subprocess.run(['curl','--fail-with-body','--max-time','120','-sS','-L','https://codeload.github.com/golang/vulndb/tar.gz/'+pins['revision'],'-o',str(archive)],check=True)
  if hashlib.sha256(archive.read_bytes()).hexdigest()!=pins['archiveSha256']:raise ValueError('Advisory archive binding changed')
  total=0
  with tarfile.open(archive) as source:
   for member in source:
    parts=Path(member.name).parts
    if len(parts)==4 and parts[1:3]==('data','osv') and parts[3].startswith('GO-') and parts[3].endswith('.json'):
     total+=member.size
     if not member.isfile() or member.size>2_000_000 or total>100_000_000:raise ValueError('Unsafe advisory archive')
     with source.extractfile(member) as inp,(database/parts[3]).open('xb') as out:out.write(inp.read())
 digest=hashlib.sha256()
 for path in sorted(database.glob('GO-*.json')):digest.update(path.name.encode()+b'\0'+hashlib.sha256(path.read_bytes()).digest())
 if digest.hexdigest()!=pins['contentSha256']:raise ValueError('Advisory database content binding changed')
 target=root/'target';target.mkdir(exist_ok=True)
 result=subprocess.run([sys.executable,str(repo/'tools/check-release-dependencies.py'),str(root),str(target/'dependency-scan.json'),'--go-db',str(database),'--go-db-revision',pins['revision'],'--github-supplement',str(supplement)])
 with (target/'govulncheck-source.jsonstream').open('w') as output:
  symbols=subprocess.run([args.govulncheck,'-json','-db',database.resolve().as_uri(),'./...'],cwd=root/'p2p-helper',stdout=output)
 # JSON mode may return zero even for vulnerabilities. Inspect symbol findings.
 text=(target/'govulncheck-source.jsonstream').read_text();decoder=json.JSONDecoder();reachable=set();config=None
 while text.strip():
  message,end=decoder.raw_decode(text.lstrip());text=text.lstrip()[end:]
  config=message.get('config',config)
  finding=message.get('finding')
  if finding and finding.get('trace') and finding['trace'][0].get('function'):reachable.add(finding['osv'])
 summary={'inventoryExit':result.returncode,'symbolScanExit':symbols.returncode,'reachableAdvisories':sorted(reachable),'scannerConfig':config,'databaseRevision':pins['revision']}
 (target/'security-gate.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps(summary))
 if result.returncode or symbols.returncode or reachable or not config:raise SystemExit(1)
