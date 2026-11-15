#!/usr/bin/env python3
"""Verify a downloaded CI archive against the exact reproducible build report."""
import hashlib,json,re,sys
from pathlib import Path

archive,report=map(Path,sys.argv[1:3]);value=json.loads(report.read_text())
if value['reproducible'] is not True or len(value['builds'])!=2:raise ValueError('Two matching builds required')
for key in ('manifestDigest','configDigest','archiveSha256'):
 if any(row[key]!=value['builds'][0][key] for row in value['builds']):raise ValueError(key+' mismatch')
with archive.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
if digest!=value['builds'][0]['archiveSha256']:raise ValueError('Archive checksum mismatch')
image=value['localImageId']
if not re.fullmatch('sha256:[0-9a-f]{64}',image):raise ValueError('Invalid image identity')
if image!=value['builds'][0]['manifestDigest']:raise ValueError('Image identity differs from verified manifest')
print(image)
