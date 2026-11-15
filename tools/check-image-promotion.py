#!/usr/bin/env python3
"""Reject tampered promotion artifacts and contradictory reproducibility reports."""
import hashlib,json,subprocess,sys,tempfile
from pathlib import Path

with tempfile.TemporaryDirectory(prefix='torchnode-promotion-test-') as temporary:
 root=Path(temporary);archive=root/'image.tar';archive.write_bytes(b'synthetic-image-archive')
 report=root/'report.json';image='sha256:'+'1'*64
 row={'manifestDigest':image,'configDigest':'sha256:'+'2'*64,'archiveSha256':hashlib.sha256(archive.read_bytes()).hexdigest()}
 def run(value,ok):
  report.write_text(json.dumps(value))
  result=subprocess.run([sys.executable,str(Path(__file__).with_name('verify-image-archive.py')),str(archive),str(report)],capture_output=True,text=True)
  assert (result.returncode==0)==ok,result.stdout+result.stderr
  if ok:assert result.stdout.strip()==image
 def fresh():return {'reproducible':True,'localImageId':image,'builds':[dict(row),dict(row)]}
 run(fresh(),True)
 for key in row:
  value=fresh();value['builds'][1][key]='tampered';run(value,False)
 value=fresh();value['localImageId']='sha256:'+'3'*64;run(value,False)
 value=fresh();value['reproducible']=False;run(value,False)
 archive.write_bytes(b'tampered');run(fresh(),False)
print('PASS: valid promotion and six tamper/inconsistent-report rejections')
