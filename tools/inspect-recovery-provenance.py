#!/usr/bin/env python3
"""One explicitly authorized, metadata-only provenance inspection. Never read DB bytes."""
import argparse,datetime as dt,hashlib,json,os,platform,plistlib,stat,subprocess,sys,tempfile,time
from pathlib import Path
SCOPE='one-metadata-only-retained-provenance-inspection'
REPO=Path(__file__).resolve().parents[1]
SOURCE='/UNCONFIGURED/retained-checkpoint.sqlite'
FIELDS=('st_dev','st_ino','st_uid','st_gid','st_mode','st_nlink','st_size','st_mtime_ns','st_ctime_ns','st_birthtime_ns','st_flags')
DISK_KEYS=('DeviceIdentifier','ParentWholeDisk','MountPoint','VolumeUUID','DiskUUID','FileSystemPersonality','FilesystemName','Mounted','ReadOnlyVolume','ReadOnlyMedia')
def require(ok,message):
 if not ok:raise ValueError(message)
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def authorize(request,request_path,auth):
 require(auth is not None,'separate metadata-only authorization required')
 s=auth.lstat();require(stat.S_ISREG(s.st_mode) and not auth.is_symlink() and s.st_uid==os.geteuid() and s.st_nlink==1 and s.st_mode&0o077==0,'private owned single-link authorization required')
 value=json.loads(auth.read_text())
 require(value.get('decision')=='APPROVED_BY_OPERATOR' and value.get('scope')==SCOPE,'not metadata-only execution authorization')
 require(value.get('requestSha256')==sha(request_path) and value.get('source')==request['source'] and value.get('outputDirectory')==request['outputDirectory'],'authorization binding mismatch')
 expiry=dt.datetime.fromisoformat(value['expiresAt']);require(expiry.tzinfo and dt.datetime.now(dt.timezone.utc)<expiry,'authorization expired')
 with os.fdopen(os.open(str(auth)+'.used',os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600),'w') as f:json.dump({'claimedAt':dt.datetime.now(dt.timezone.utc).isoformat(),'scope':SCOPE},f)
def plan(request):
 return {'scope':SCOPE,'source':request['source'],'fields':list(FIELDS),'pathResolution':'lstat exact file and ancestors, refuse symlinks; canonical resolution; no directory listing','mountCommands':[['/bin/df','-P',request['source']],['/usr/sbin/diskutil','info','-plist','<mount point returned by df>']],'diskKeys':list(DISK_KEYS),'historicalEvidence':request['historicalEvidence'],'hostMetadata':['kernel release','Python version/executable','kern.boottime'],'originalBytesRead':False,'copiesCreated':False,'changesIdentityPolicy':False}
def inspect(request):
 source=Path(request['source']);require(source.is_absolute() and '..' not in source.parts,'exact absolute path required')
 output=Path(request['outputDirectory']);require(output.is_relative_to(Path(tempfile.gettempdir()).resolve()) and output.name.startswith('torchnode-provenance-') and output.resolve()==output,'private system-temp output required');require(not output.exists(),'output collision');output.mkdir(mode=0o700)
 report={'scope':SCOPE,'status':'FAIL','originalBytesRead':False,'copiesCreated':False,'identityBindingModified':False,'policyChanged':False,'recoveryExecuted':False,'inspectedAt':dt.datetime.now(dt.timezone.utc).isoformat()}
 try:
  deadline=time.monotonic()+60
  def budget():require(time.monotonic()<deadline,'provenance inspection deadline')
  budget()
  report['host']={'kernelRelease':platform.release(),'python':sys.version,'executable':sys.executable,'bootTime':subprocess.run(['/usr/sbin/sysctl','-n','kern.boottime'],check=True,capture_output=True,text=True,timeout=5).stdout.strip()}
  metadata=[]
  for path in reversed((source,*source.parents)):
   budget();s=path.lstat();row={'path':str(path),'symlink':stat.S_ISLNK(s.st_mode),'device':s.st_dev,'inode':s.st_ino,'owner':s.st_uid,'permissions':s.st_mode&0o777,'links':s.st_nlink};metadata.append(row)
   require(not row['symlink'],'symlink encountered; no target traversal authorized')
  report['pathMetadata']=metadata;require(source.resolve()==source,'canonical path mismatch');s=source.lstat();require(stat.S_ISREG(s.st_mode) and s.st_uid==os.geteuid() and s.st_nlink==1,'owned regular single-link source required')
  report['fileMetadata']={field:getattr(s,field,None) for field in FIELDS};v=os.statvfs(source);report['statvfs']={k:getattr(v,k,None) for k in ('f_fsid','f_flag','f_namemax')}
  report['df']=subprocess.run(['/bin/df','-P',str(source)],check=True,capture_output=True,text=True,timeout=10).stdout
  budget()
  lines=report['df'].strip().splitlines();require(len(lines)==2,'ambiguous filesystem mapping');columns=lines[1].split(None,5);require(len(columns)==6,'invalid filesystem mapping');mount=Path(columns[5]);require(mount.is_absolute() and '..' not in mount.parts,'invalid mount point')
  disk=subprocess.run(['/usr/sbin/diskutil','info','-plist',str(mount)],check=True,capture_output=True,timeout=15);data=plistlib.loads(disk.stdout);report['disk']={k:data[k] for k in DISK_KEYS if k in data}
  budget()
  report['historicalEvidenceHashes']={path:sha(Path(path)) for path in request['historicalEvidence']}
  report['status']='METADATA_COLLECTED_NOT_RECOVERY_ACCEPTANCE'
 except Exception as e:report['error']=str(e);raise
 finally:(output/'provenance-report.json').write_text(json.dumps(report,indent=2)+'\n')
 print(json.dumps({'status':report['status'],'report':str(output/'provenance-report.json'),'originalBytesRead':False,'copiesCreated':False}))
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['plan','inspect']);p.add_argument('--request',type=Path,required=True);p.add_argument('--authorization-file',type=Path);a=p.parse_args();os.umask(0o077)
 request=json.loads(a.request.read_text());require(request['scope']==SCOPE and request['source']==SOURCE and sha(Path(__file__))==request['inspectorSha256'],'inspector/request binding mismatch')
 allowed={str(REPO/p) for p in ['docs/v6-recovery-artifacts.json','docs/v6-recovery-attempt2-artifacts.json','docs/validation/offline-v6-verification-20261113T092655Z.json','docs/validation/offline-v6-semantics-20261113T094137Z.json','docs/validation/v6-device-identity-analysis.json','docs/validation/v6-metadata-preflight-investigation.json']}
 require(set(request['historicalEvidence'])==allowed,'historical evidence allowlist mismatch')
 if a.action=='plan':print(json.dumps(plan(request),indent=2));return
 require(a.authorization_file is not None and str(a.authorization_file)==request["authorizationFileAfterApprovalOnly"],"exact separate authorization path required")
 authorize(request,a.request,a.authorization_file)
 require(all(sha(Path(path))==digest for path,digest in request['historicalEvidence'].items()),'historical evidence hash changed')
 inspect(request)
if __name__=='__main__':main()
