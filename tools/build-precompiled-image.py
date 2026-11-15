#!/usr/bin/env python3
"""Build a local precompiled image from pinned public base stages. Never push/run."""
import argparse,hashlib,json,os,re,shutil,subprocess,tempfile,uuid,zipfile
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,required=True);p.add_argument('--jar',type=Path,required=True);p.add_argument('--expected-sha256',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--rebuild',action='store_true');p.add_argument('--prepare-only',action='store_true');p.add_argument('--artifacts',type=Path,help='Explicit offline assembly using verified unchanged helpers/JNI');a=p.parse_args();source=a.source.resolve();out=a.output.resolve()
if out.exists() or not out.is_relative_to(Path(tempfile.gettempdir()).resolve()):raise ValueError('Fresh private system-temp output required')
with a.jar.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
if digest!=a.expected_sha256:raise ValueError('Precompiled JAR hash mismatch')
with zipfile.ZipFile(a.jar) as z:
 names=z.namelist()
 if any(n.startswith('org/eclipse/jdt/') for n in names) or 'torchnode-precompiled-jsp.properties' not in names:raise ValueError('Validated ECJ-free precompiled JAR required')
os.umask(0o077);out.mkdir(mode=0o700);context=out/'context';context.mkdir();
for name in ['pom.xml','LICENSE','THIRD_PARTY_NOTICES.md','.dockerignore']:shutil.copy2(source/name,context/name)
for name in ['src','p2p-helper','third-party']:shutil.copytree(source/name,context/name,ignore=shutil.ignore_patterns('target','__pycache__','.git','*.db','*.sqlite','*.sqlite3','*.key','*.pem','p2p-helper','torchnode-p2p-helper','torchnode-discovery-helper'))
shutil.copy2(a.jar,context/'precompiled.jar');dockerfile=(source/'Dockerfile').read_text();marker='# Pinned, supported upstream distribution; assemble a headless runtime offline.'
if marker not in dockerfile:raise ValueError('Unsupported runtime recipe')
check="""FROM java-build AS checked-precompiled
ARG PRECOMPILED_JAR_SHA256
COPY precompiled.jar /out/torchnode-precompiled.jar
RUN test -n "$PRECOMPILED_JAR_SHA256" && echo "$PRECOMPILED_JAR_SHA256  /out/torchnode-precompiled.jar" | sha256sum -c -

"""
artifact_inputs={}
if a.artifacts:
 pins=json.loads(a.artifacts.read_text())
 for helper in pins['helpers']:
  path=Path(helper['path']);data=path.read_bytes()
  if hashlib.sha256(data).hexdigest()!=helper['sha256']:raise ValueError('Helper hash mismatch')
  shutil.copy2(path,context/path.name);artifact_inputs[path.name]=helper['sha256']
 with zipfile.ZipFile(pins['application']['path']) as original:
  native=original.read('org/sqlite/native/Linux-Musl/x86_64/libsqlitejdbc.so')
 if hashlib.sha256(native).hexdigest()!=pins['nativeSqliteSha256']:raise ValueError('JNI hash mismatch')
 (context/'libsqlitejdbc.so').write_bytes(native);artifact_inputs['libsqlitejdbc.so']=pins['nativeSqliteSha256']
 dockerfile='ARG SOURCE_DATE_EPOCH=1794528000\n'+dockerfile[dockerfile.index(marker):]
 dockerfile=dockerfile.replace('COPY --from=go-build --chown=torchnode:torchnode --chmod=755 /out/torchnode-p2p-helper','COPY --chown=torchnode:torchnode --chmod=755 torchnode-p2p-helper').replace('COPY --from=go-build --chown=torchnode:torchnode --chmod=755 /out/torchnode-discovery-helper','COPY --chown=torchnode:torchnode --chmod=755 torchnode-discovery-helper').replace('COPY --from=java-build --chown=torchnode:torchnode /out/sqlite/org/sqlite/native/Linux-Musl/x86_64/libsqlitejdbc.so','COPY --chown=torchnode:torchnode libsqlitejdbc.so')
else:dockerfile=dockerfile.replace(marker,check+marker,1)
old='COPY --from=java-build --chown=torchnode:torchnode /build/target/torchnode-1.0.0-jar-with-dependencies.jar /app/torchnode.jar'
if old not in dockerfile:raise ValueError('Application copy recipe changed')
dockerfile=dockerfile.replace(old,'COPY --chown=torchnode:torchnode precompiled.jar /app/torchnode.jar' if a.artifacts else 'COPY --from=checked-precompiled --chown=torchnode:torchnode /out/torchnode-precompiled.jar /app/torchnode.jar',1)
(context/'Dockerfile').write_text(dockerfile);ignore=context/'.dockerignore';ignore.write_text(ignore.read_text()+'\n!precompiled.jar\n!torchnode-p2p-helper\n!torchnode-discovery-helper\n!libsqlitejdbc.so\n');report={'published':False,'recoveryArtifactReplaced':False,'precompiledJarSha256':digest,'source':str(source),'builds':[],'assemblyMode':'verified-prebuilt-helpers-and-JNI' if a.artifacts else 'compiler-stages','artifactInputs':artifact_inputs}
if a.prepare_only:
 report['status']='CONTEXT_PREPARED_NOT_BUILT';(out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report));raise SystemExit(0)
tag='torchnode:precompiled-'+uuid.uuid4().hex[:12]
for index in range(1,3 if a.rebuild else 2):
 archive=out/f'image-{index}.tar';meta=out/f'image-{index}.metadata.json'
 command=['docker','buildx','build','--platform','linux/amd64','--provenance=false','--build-arg','SOURCE_DATE_EPOCH=1794528000','--build-arg','PRECOMPILED_JAR_SHA256='+digest,'--output','type=docker,dest='+str(archive)+',rewrite-timestamp=true','--metadata-file',str(meta),'-t',tag,str(context)]
 with (out/f'image-{index}.log').open('x') as log:subprocess.run(command,stdout=log,stderr=log,check=True,timeout=1200)
 data=json.loads(meta.read_text());report['builds'].append({'manifest':data['containerimage.digest'],'config':data['containerimage.config.digest'],'archiveSha256':hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()})
if a.rebuild and report['builds'][0]!=report['builds'][1]:raise ValueError('Image reproduction mismatch')
subprocess.run(['docker','load','--input',str(out/'image-1.tar')],check=True,timeout=120);image=json.loads(subprocess.check_output(['docker','image','inspect',tag],timeout=10))[0];report.update(status='BUILT_NOT_RUNTIME_ACCEPTED',imageId=image['Id'],reproducible=a.rebuild,tag=tag);(out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
