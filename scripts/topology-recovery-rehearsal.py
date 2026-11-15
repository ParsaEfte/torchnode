#!/usr/bin/env python3
"""Explicitly gated disposable v6 rehearsal; no production defaults or migrations."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

RETAINED = Path('/UNCONFIGURED/retained-checkpoint.sqlite')
HASH = '0000000000000000000000000000000000000000000000000000000000000000'
AUTH_SCHEMA = {
    'auth_schema': 'CREATE TABLE auth_schema(version INTEGER PRIMARY KEY)',
    'auth_users': "CREATE TABLE auth_users(username TEXT PRIMARY KEY, password_hash TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('ADMIN','VIEWER')), enabled INTEGER NOT NULL DEFAULT 1, version INTEGER NOT NULL DEFAULT 1)",
    'auth_audit': 'CREATE TABLE auth_audit(id INTEGER PRIMARY KEY, occurred_at TEXT NOT NULL, actor TEXT NOT NULL, event TEXT NOT NULL, remote_address TEXT NOT NULL)'
}
def require(ok, message):
    if not ok: raise ValueError(message)
# Only numeric filesystem identity fields may enter mismatch diagnostics.
IDENTITY_FIELDS = (('device','st_dev'),('inode','st_ino'),('owner','st_uid'),
                   ('mode','st_mode'),('sizeBytes','st_size'),('mtimeNs','st_mtime_ns'))
def retained_identity(source_stat):
    return {name:(getattr(source_stat,attribute)&0o777 if name=='mode' else getattr(source_stat,attribute))
            for name,attribute in IDENTITY_FIELDS}
def validate_retained_identity(expected,source_stat):
    actual=retained_identity(source_stat)
    mismatches={}
    for name,_ in IDENTITY_FIELDS:
        wanted=expected.get(name)
        observed=actual[name]
        # Exact integers retain nanosecond precision; malformed values are redacted.
        if type(wanted) is not int or type(observed) is not int or wanted!=observed:
            safe_expected=(wanted if type(wanted) is int else
                           '<missing>' if name not in expected else '<invalid non-integer>')
            safe_actual=observed if type(observed) is int else '<invalid non-integer>'
            mismatches[name]={'expected':safe_expected,'actual':safe_actual}
    require(not mismatches,'retained metadata binding mismatch: '+json.dumps(mismatches,separators=(',',':')))

def preserved_inventory(pre,post):
    # Only the separately authorized, exact additive security schema may appear.
    historical=lambda rows:[r for r in rows if r[2] not in AUTH_SCHEMA]
    require(historical(pre['objects'])==historical(post['objects']) and pre['versions']==post['versions'],'historical schema changed')
    normalized=lambda sql:''.join(sql.split()).replace('IFNOTEXISTS','')
    security={r[1]:r for r in post['objects'] if r[2] in AUTH_SCHEMA}
    require(set(security)==set(AUTH_SCHEMA),'unexpected security objects')
    for name,sql in AUTH_SCHEMA.items():
        row=security[name];require(row[0]=='table' and row[2]==name and normalized(row[3])==normalized(sql),'unexpected security schema definition')
    version_digest=hashlib.sha256(json.dumps([('int',1)],ensure_ascii=True,separators=(',',':')).encode()).hexdigest()
    require(post['counts']['auth_schema']==1 and post['digests']['auth_schema']==version_digest,'security schema version changed')
    for table in pre['counts']:
        if table not in ('auth_users','auth_audit'):
            require(pre['counts'][table]==post['counts'][table] and pre['digests'][table]==post['digests'][table],'historical/evidence content changed')
def canonical(p):
    p=Path(p);require(p.is_absolute() and '..' not in p.parts,'absolute canonical path required')
    require(all(not x.is_symlink() for x in (p,*p.parents)) and p.resolve()==p,'symlink/alias refused')
    return p
def sha(p):
    h=hashlib.sha256();end=time.monotonic()+60
    with p.open('rb') as f:
        for block in iter(lambda:f.read(1024*1024),b''):
            require(time.monotonic()<end,'hash deadline');h.update(block)
    return h.hexdigest()
def source_gate(mode,source):
    raw=Path(source)
    if mode=='fixture':
        require(raw.name=='fixture-v6.sqlite' and raw.parent.is_relative_to(Path(tempfile.gettempdir()).resolve()),'private temporary synthetic fixture required')
        root=canonical(raw.parent);require(root.stat().st_uid==os.geteuid() and root.stat().st_mode&0o077==0,'private fixture root required')
        require(json.loads((root/'V6-FIXTURE.json').read_text())=={'synthetic':True},'synthetic declaration required')
    else: require(raw==RETAINED,'exact retained snapshot required')
    p=canonical(raw);s=p.lstat();require(p.is_file() and s.st_nlink==1 and s.st_uid==os.geteuid(),'owned single-link source required')
    require(not any(Path(str(p)+x).exists() for x in ('-wal','-shm','-journal')),'standalone source required')
    return p

def container_command(name,image,work,jar,classes):
    require(re.fullmatch(r'sha256:[0-9a-f]{64}',image) is not None,'immutable local image ID required')
    return ['docker','run','--pull=never','--name',name,'--label','torchnode.rehearsal='+name,
            '--cidfile',str(work/(name+'.cid')),'--network=none','--no-healthcheck','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges:true',
            '--memory=1g','--cpus=2','--pids-limit=128','--user',str(os.geteuid())+':'+str(os.getegid()),
            '-e','TORCHNODE_COLLECTION_DISABLED=true',
            '--mount','type=bind,src='+str(work)+',dst=/work',
            '--mount','type=bind,src='+str(jar)+',dst=/candidate.jar,readonly',
            '--mount','type=bind,src='+str(classes)+',dst=/probe,readonly',
            '--entrypoint','java',image,'-Xmx512m','-Djava.io.tmpdir=/work/tmp','-cp','/probe:/candidate.jar',
            'io.github.gavinruff007.torchnode.dashboard.V6RecoveryProbe','/work']

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('action',choices=['plan','execute']);p.add_argument('--mode',choices=['fixture','retained'],required=True)
    p.add_argument('--source',required=True);p.add_argument('--workspace',required=True)
    p.add_argument('--artifact-manifest',required=True);p.add_argument('--image',required=True)
    p.add_argument('--authorization-file')
    a=p.parse_args();os.umask(0o077)
    # No source reads/copies in plan mode.
    manifest_path=canonical(a.artifact_manifest);pins=json.loads(manifest_path.read_text())
    require(pins['containerImageId']==a.image,'image not pinned in artifact manifest')
    work=canonical(a.workspace);require(work.is_relative_to(Path(tempfile.gettempdir()).resolve()) and work.name.startswith('v6-rehearsal-'),'fresh system-temp workspace required')
    require(not work.exists(),'workspace collision')
    jar=canonical(pins['application']['path']);classes=canonical(pins['probeClasses'])
    require(sha(jar)==pins['application']['sha256'],'candidate JAR hash changed')
    require(sha(Path(__file__).with_name('topology-migration.py'))==pins['offlineVerifierSha256'],'inventory verifier hash changed')
    require({str(p.relative_to(classes)) for p in classes.rglob('*') if p.is_file()}==set(pins['probeHashes']),'unexpected probe files')
    for name,digest in pins['probeHashes'].items():require(sha(classes/name)==digest,'probe hash changed')
    require(sha(Path(__file__).resolve())==pins['rehearsalScriptSha256'],'script binding changed')
    name=work.name
    command=container_command(name,a.image,work,jar,classes)
    if a.action=='plan':print(json.dumps({'command':command,'copiesCreated':False}));return
    if a.mode=='retained':
        require(a.authorization_file,'separate rehearsal authorization required')
        auth=canonical(a.authorization_file);s=auth.lstat();require(s.st_uid==os.geteuid() and s.st_nlink==1 and s.st_mode&0o077==0,'private authorization required')
        value=json.loads(auth.read_text());require(value.get('decision')=='APPROVED_BY_OPERATOR','draft/request is not execution authorization')
        require(value['scope']=='one-disposable-v6-rehearsal' and value['source']==a.source and value['sourceSha256']==HASH and value['workspace']==str(work) and value['artifactManifestSha256']==sha(manifest_path),'authorization binding mismatch')
        expiry=dt.datetime.fromisoformat(value['expiresAt']);require(expiry.tzinfo and dt.datetime.now(dt.timezone.utc)<expiry,'authorization expired')
        # Consume the attempt before any retained-source inspection or byte read.
        with os.fdopen(os.open(str(auth)+'.used',os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600),'w') as f:json.dump({'claimedAt':dt.datetime.now(dt.timezone.utc).isoformat()},f)
    source=source_gate(a.mode,a.source);source_stat=source.stat();before=sha(source)
    source_identity=(source_stat.st_dev,source_stat.st_ino,source_stat.st_uid,source_stat.st_mode,source_stat.st_size,source_stat.st_mtime_ns)
    if a.mode=='retained':
        require(before==HASH,'retained hash mismatch')
        validate_retained_identity(pins['snapshotIdentity'],source_stat)
    for artifact in (source,jar,classes,work):require(',' not in str(artifact),'mount delimiter refused')
    subprocess.run(['docker','image','inspect',a.image],check=True,capture_output=True,timeout=10)
    work.mkdir(mode=0o700);(work/'tmp').mkdir(mode=0o700);(work/'V6-REHEARSAL').write_text('disposable only')
    report={'sourceSha256':before,'rounds':[],'accepted':False}
    from importlib.util import spec_from_file_location,module_from_spec
    spec=spec_from_file_location('offline_rehearsal',Path(__file__).with_name('topology-migration.py'));offline=module_from_spec(spec);spec.loader.exec_module(offline)
    end=time.monotonic()+600
    try:
        for phase in ('startup','recovery-from-unchanged-checkpoint'):
            require(time.monotonic()<end,'rehearsal deadline')
            db=work/'working-v6.sqlite'
            if db.exists():
                # Only the exact owned rehearsal file is replaced; no recursive cleanup.
                require(db.is_file() and not db.is_symlink() and db.stat().st_nlink==1,'unsafe working file')
                require(not any(Path(str(db)+s).exists() for s in ('-wal','-shm','-journal')),'working sidecars retained; stop')
                db.rename(work/'first-pass.sqlite')
            with source.open('rb') as inp,db.open('xb') as out:shutil.copyfileobj(inp,out,1024*1024)
            require(sha(db)==before,'copy hash mismatch')
            offline.end_time=min(end,time.monotonic()+120);pre=offline.inventory(db,6)
            owned=False
            try:
                # Name was checked absent. Only this unique labeled container is eligible for cleanup.
                old=subprocess.run(['docker','container','inspect',name],capture_output=True,timeout=10)
                require(old.returncode!=0,'container name collision')
                owned=True
                with (work/(phase+'.log')).open('x') as log:
                    subprocess.run(command,stdout=log,stderr=log,check=True,timeout=min(120,end-time.monotonic()))
            finally:
                if owned:
                    cidfile=work/(name+'.cid')
                    if cidfile.exists():
                        cid=cidfile.read_text().strip();require(re.fullmatch('[0-9a-f]{64}',cid) is not None,'invalid owned container ID')
                        check=subprocess.run(['docker','inspect','--format','{{ index .Config.Labels "torchnode.rehearsal" }}',cid],capture_output=True,text=True,timeout=10)
                        if check.returncode==0 and check.stdout.strip()==name:subprocess.run(['docker','rm','-f',cid],check=True,capture_output=True,timeout=15)
                        cidfile.rename(work/(phase+'.cid'))
            offline.end_time=min(end,time.monotonic()+120);post=offline.inventory(db,6)
            preserved_inventory(pre,post)
            report['rounds'].append({'phase':phase,'http':'PASS','historicalSchemaUnchanged':True,'nonAuthInventoryPreserved':True,'authSchemaAdded':[name for name in AUTH_SCHEMA if name not in pre['counts']]})
    finally:
        after=source.stat()
        report['sourceUnchanged']=sha(source)==before and (after.st_dev,after.st_ino,after.st_uid,after.st_mode,after.st_size,after.st_mtime_ns)==source_identity
        (work/'rehearsal-report.json').write_text(json.dumps(report,indent=2))
    require(report['sourceUnchanged'],'original source changed');print(json.dumps(report))
if __name__=='__main__':main()
