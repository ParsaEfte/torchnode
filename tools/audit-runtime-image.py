#!/usr/bin/env python3
"""Audit distributed layers and extract only named candidate files; never start an app."""
import argparse,hashlib,json,tarfile
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__);p.add_argument('archive',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
a.output.mkdir(parents=True,exist_ok=True)
selected={'app/torchnode.jar','app/torchnode-p2p-helper','app/torchnode-discovery-helper','app/lib/libsqlitejdbc.so','lib/apk/db/installed','usr/lib/os-release','etc/passwd','etc/group'}
files={};names=set();modes={}
with tarfile.open(a.archive) as outer:
 manifest=json.load(outer.extractfile('manifest.json'))[0]
 for layer in manifest['Layers']:
  with tarfile.open(fileobj=outer.extractfile(layer)) as inner:
   for member in inner:
    name=member.name.removeprefix('./').lstrip('/')
    if '/.wh.' in '/'+name:raise ValueError('Unexpected whiteout: final runtime must contain only assembled files')
    names.add(name);modes[name]=member.mode
    if name in selected and member.isfile():files[name]=inner.extractfile(member).read()
 assert selected<=files.keys(),'Missing required runtime records/payloads'
 forbidden=[n for n in names if n in ('bin/busybox','bin/sh','bin/bash','usr/bin/curl','usr/bin/wget','sbin/apk','usr/lib/libz.so.1','usr/bin/openssl','usr/bin/sqlite3') or n.startswith(('lib/x86_64-linux-gnu/','usr/lib/x86_64-linux-gnu/'))]
 assert not forbidden,forbidden
 assert modes['app/lib']&0o555==0o555 and modes['app/lib/libsqlitejdbc.so']&0o444==0o444
 packages=[];paths=set()
 for block in files['lib/apk/db/installed'].decode().strip().split('\n\n'):
  values={};directory=''
  for line in block.splitlines():
   key,_,value=line.partition(':')
   if key in ('P','V','L','o','c'):values[key]=value
   if key=='F':directory=value
   if key=='R':paths.add(directory+'/'+value)
  packages.append(values)
 assert {r['P'] for r in packages}=={'musl','tzdata','ca-certificates-bundle','alpine-release'}
 assert paths<=names,'APK records contain missing files'
 for name,data in files.items():
  target=a.output/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
 for name in ('app/torchnode-p2p-helper','app/torchnode-discovery-helper'):(a.output/name).chmod(0o755)
 report={'status':'PASS','archiveSha256':hashlib.file_digest(a.archive.open('rb'),'sha256').hexdigest(),'packages':packages,'completePackageFileRecords':len(paths),'allDistributedLayerEntries':len(names),'forbiddenRuntimeFiles':forbidden,'payloadHashes':{n:hashlib.sha256(b).hexdigest() for n,b in files.items()},'extractedRoot':str(a.output)}
 (a.output/'runtime-audit.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
