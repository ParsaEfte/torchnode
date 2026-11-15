#!/usr/bin/env python3
"""Read-only clean-checkout guard for CI; never commits, pushes or publishes."""
import json,re,subprocess,sys
from pathlib import PurePosixPath
status=subprocess.check_output(['git','status','--porcelain','--untracked-files=all'],text=True)
tracked=subprocess.check_output(['git','ls-files','-z']).decode().split('\0')
forbidden=[]
for name in filter(None,tracked):
 p=PurePosixPath(name);base=p.name.lower()
 # Runtime databases, usable approval claims and private rehearsal diagnostics are not release inputs.
 if (name.startswith('docs/validation/') or
     (name.startswith('docs/v6-') and base.endswith('.json')) or
     base.endswith(('.sqlite','.sqlite3','.db','-wal','-shm','.used')) or
     base in ('authorization.json','browser-session.json') or
     re.search(r'(authorization.*execution|amendment.*approval|renewal.*approval)',base) or
     any(part in ('torchnode-backups','torchnode-release-candidates') for part in p.parts)):
  forbidden.append(name)
result={'clean':not status,'forbiddenTrackedPaths':forbidden,'commit':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),'scope':'Path/cleanliness guard only; not a comprehensive secret scanner or legal approval'}
print(json.dumps(result,indent=2))
if status or forbidden:raise SystemExit(1)
