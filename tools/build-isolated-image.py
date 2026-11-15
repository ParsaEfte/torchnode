#!/usr/bin/env python3
"""Build/load a local candidate; optionally recompile and compare. Never push.

Use an explicit isolated source copy (ISOLATED-SOURCE marker) or clean CI checkout.
Runtime tests and retained-data authorization are separate gates.
"""
import argparse,datetime,hashlib,json,os,re,subprocess,tempfile,xml.etree.ElementTree as ET
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('context',type=Path);parser.add_argument('output',type=Path)
parser.add_argument('--go-deps-image',help='Explicit checksum-verified offline Go SDK cache image');parser.add_argument('--tag',required=True);parser.add_argument('--rebuild',action='store_true');args=parser.parse_args()
context=args.context.resolve();output=args.output.resolve()
if not (context/'ISOLATED-SOURCE').is_file() and os.environ.get('CI')!='true':raise ValueError('Isolated source marker or CI checkout required')
if not output.is_relative_to(Path(tempfile.gettempdir()).resolve()) and not output.is_relative_to(Path('/private/tmp')):raise ValueError('Private temporary build output required')
if not re.fullmatch('torchnode:[a-z0-9][a-z0-9_.-]*',args.tag):raise ValueError('Local torchnode build tag required')
output.mkdir(parents=True,exist_ok=True,mode=0o700)
pom=ET.parse(context/'pom.xml').getroot();ns={'m':'http://maven.apache.org/POM/4.0.0'}
stamp=pom.find('m:properties/m:project.build.outputTimestamp',ns).text
epoch=str(int(datetime.datetime.fromisoformat(stamp.replace('Z','+00:00')).timestamp()))
records=[]
for index in range(1,3 if args.rebuild else 2):
 archive=output/f'image-{index}.tar';metadata=output/f'build-{index}.metadata.json';log=output/f'build-{index}.log'
 if archive.exists() or metadata.exists():raise ValueError('Build output collision; choose a new owned directory')
 command=['docker','buildx','build','--platform','linux/amd64','--provenance=false','--build-arg','SOURCE_DATE_EPOCH='+epoch,
          '--output',f'type=docker,dest={archive},rewrite-timestamp=true','--metadata-file',str(metadata),'-t',args.tag]
 # Both passes reconstruct compiler/runtime stages; COPY cache keys ignore mtime.
 command+=['--no-cache-filter','runtime-base,runtime']
 if args.go_deps_image:command+=['--build-arg','GO_DEPS_IMAGE='+args.go_deps_image]
 command.append(str(context))
 with log.open('x') as stream:
  result=subprocess.run(command,stdout=stream,stderr=stream,timeout=1800)
 if result.returncode:raise RuntimeError('Image build failed; inspect '+str(log))
 info=json.loads(metadata.read_text());records.append({'manifestDigest':info['containerimage.digest'],'configDigest':info['containerimage.config.digest'],'archiveSha256':hashlib.file_digest(archive.open('rb'),'sha256').hexdigest(),'metadata':str(metadata),'archive':str(archive),'log':str(log)})
 if index==1:subprocess.run(['docker','load','--input',str(archive)],check=True,timeout=180)
image=json.loads(subprocess.check_output(['docker','image','inspect',args.tag],text=True))[0]
if image['Os']!='linux' or image['Architecture']!='amd64' or image['Config']['User']!='10001:10001':raise ValueError('Unexpected image runtime identity')
version=pom.find('m:version',ns).text
if image['Config']['Labels'].get('org.opencontainers.image.version')!=version:raise ValueError('Image/application version mismatch')
same=all(all(r[key]==records[0][key] for key in ('manifestDigest','configDigest','archiveSha256')) for r in records)
report={'published':False,'platform':'linux/amd64','localImageId':image['Id'],'descriptor':image.get('Descriptor'),'sourceDateEpoch':int(epoch),'builds':records,
        'applicationVersion':version,'sourceCommit':os.environ.get('GITHUB_SHA'),'workflowRun':os.environ.get('GITHUB_RUN_ID'),
        'reproducible':same if args.rebuild else None,'scope':'Both passes reassemble runtime stages and compare manifest/config/archive bytes; compiler and dependency caches may be reused. Default Maven JAR rebuild is verified separately. Runtime tests are separate.'}
(output/'image-build-report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if not same:raise SystemExit('Image reproduction mismatch; do not authorize rehearsal')
