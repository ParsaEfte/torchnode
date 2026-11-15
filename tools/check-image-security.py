#!/usr/bin/env python3
"""Scan one local candidate archive with pinned Grype; no waivers or retries."""
import argparse,collections,datetime,hashlib,json,os,platform,re,subprocess,tarfile,tempfile
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('archive',type=Path);parser.add_argument('output',type=Path)
parser.add_argument('--grype',type=Path,help='Existing checksum-verified scanner')
parser.add_argument('--database-cache',type=Path)
parser.add_argument('--offline-database',action='store_true',help='Explicitly scan with existing validated database (maximum age seven days), without an update request')
args=parser.parse_args()
repo=Path(__file__).resolve().parents[1];policy=json.loads((repo/'tools/security-policy.json').read_text())
archive=args.archive.resolve();output=args.output.resolve();output.mkdir(parents=True,exist_ok=True,mode=0o700)
env={k:v for k,v in os.environ.items() if not k.startswith(('GRYPE_','SYFT_'))}
env.update(GRYPE_DB_CACHE_DIR=str(args.database_cache or output/'grype-db'),GRYPE_CHECK_FOR_APP_UPDATE='false',GRYPE_DB_VALIDATE_AGE='true',GRYPE_DB_MAX_ALLOWED_BUILT_AGE='168h')
with tempfile.TemporaryDirectory(prefix='torchnode-image-scanner-') as temporary:
 temporary=Path(temporary);scanner=args.grype
 if scanner is None:
  system={'Darwin':'darwin','Linux':'linux'}[platform.system()];arch={'arm64':'arm64','aarch64':'arm64','x86_64':'amd64'}[platform.machine()]
  name='grype_'+policy['grypeVersion'].lstrip('v')+'_'+system+'_'+arch+'.tar.gz'
  expected=policy['grypeArchives'][name];package=temporary/'scanner.tar.gz'
  subprocess.run(['curl','--fail-with-body','--max-time','180','-sS','-L','https://github.com/anchore/grype/releases/download/'+policy['grypeVersion']+'/'+name,'-o',str(package)],check=True)
  if hashlib.sha256(package.read_bytes()).hexdigest()!=expected:raise ValueError('Scanner archive checksum mismatch')
  with tarfile.open(package) as source:
   member=source.getmember('grype')
   if not member.isfile():raise ValueError('Invalid scanner binary')
   scanner=temporary/'grype';scanner.write_bytes(source.extractfile(member).read());scanner.chmod(0o755)
 version=subprocess.check_output([str(scanner),'version'],text=True)
 if not re.search(r'Version:\s+'+re.escape(policy['grypeVersion'].lstrip('v'))+r'\s',version):raise ValueError('Unreviewed scanner version')
 config=temporary/'config.yaml';config.write_text('ignore: []\ncheck-for-app-update: false\n')
 with (output/'scanner.log').open('w') as log:
  if not args.offline_database:subprocess.run([str(scanner),'db','update','--config',str(config)],env=env,stdout=log,stderr=log,check=True,timeout=240)
  env['GRYPE_DB_AUTO_UPDATE']='false'
  process=subprocess.run([str(scanner),'docker-archive:'+str(archive),'--config',str(config),'-o','json='+str(output/'vulnerabilities.json'),'-o','cyclonedx-json='+str(output/'sbom.json'),'--fail-on','negligible'],env=env,stdout=log,stderr=log,timeout=300)
 if process.returncode not in (0,2):raise RuntimeError('Scanner failed; inspect '+str(output/'scanner.log'))
 report=json.loads((output/'vulnerabilities.json').read_text());matches=report['matches']
 database=report['descriptor'].get('db',{});status=database.get('status',{})
 if status.get('valid') is not True:raise ValueError('Validated vulnerability database required')
 built=datetime.datetime.fromisoformat(status['built'].replace('Z','+00:00'))
 age=datetime.datetime.now(datetime.timezone.utc)-built
 if not datetime.timedelta(hours=-1)<=age<=datetime.timedelta(days=7):raise ValueError('Vulnerability database outside freshness window')
 summary={'archiveSha256':hashlib.file_digest(archive.open('rb'),'sha256').hexdigest(),'scannerVersion':policy['grypeVersion'],
          'status':'FAIL' if matches or report.get('ignoredMatches') else 'PASS','matchCount':len(matches),
          'severityCounts':dict(collections.Counter(m['vulnerability']['severity'] for m in matches)),
          'suppressedCount':len(report.get('ignoredMatches',[])),'database':report['descriptor'].get('db'),'offlineDatabase':args.offline_database,
          'disposition':'All matches require remediation or explicit reviewed disposition; none automatically accepted'}
 (output/'image-security-summary.json').write_text(json.dumps(summary,indent=2)+'\n');print(json.dumps({k:v for k,v in summary.items() if k!='database'}))
 if summary['status']!='PASS':raise SystemExit(1)
