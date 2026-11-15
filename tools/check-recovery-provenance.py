import datetime as dt,importlib.util,json,os,stat,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock,patch
spec=importlib.util.spec_from_file_location('provenance',Path(__file__).with_name('inspect-recovery-provenance.py'));r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
class ProvenanceGates(unittest.TestCase):
 def request(self):return {'scope':r.SCOPE,'source':r.SOURCE,'outputDirectory':'/private/tmp/torchnode-provenance-synthetic','historicalEvidence':{}}
 def auth(self,**updates):
  value={'decision':'APPROVED_BY_OPERATOR','scope':r.SCOPE,'source':r.SOURCE,'outputDirectory':self.request()['outputDirectory'],'requestSha256':'hash','expiresAt':(dt.datetime.now(dt.timezone.utc)+dt.timedelta(minutes=1)).isoformat()};value.update(updates)
  auth=MagicMock();auth.lstat.return_value=SimpleNamespace(st_mode=stat.S_IFREG|0o600,st_uid=os.geteuid(),st_nlink=1);auth.is_symlink.return_value=False;auth.read_text.return_value=json.dumps(value);return auth
 def test_plan_never_inspects_retained_metadata(self):
  with patch.object(Path,'lstat',side_effect=AssertionError('no metadata')),patch.object(r.subprocess,'run',side_effect=AssertionError('no commands')):
   plan=r.plan(self.request());self.assertFalse(plan['originalBytesRead']);self.assertFalse(plan['copiesCreated'])
 def test_missing_authorization_rejected(self):
  with self.assertRaisesRegex(ValueError,'separate'):r.authorize(self.request(),Path('request'),None)
 def test_recovery_scope_cannot_authorize_metadata_inspection(self):
  with self.assertRaisesRegex(ValueError,'not metadata-only'):r.authorize(self.request(),Path('request'),self.auth(scope='one-disposable-v6-rehearsal'))
 def test_draft_rejected(self):
  with self.assertRaisesRegex(ValueError,'not metadata-only'):r.authorize(self.request(),Path('request'),self.auth(decision='NOT_AUTHORIZED'))
 def test_expired_authorization_rejected_before_claim(self):
  with patch.object(r,'sha',return_value='hash'),patch.object(os,'open',side_effect=AssertionError('must not claim')):
   with self.assertRaisesRegex(ValueError,'expired'):r.authorize(self.request(),Path('request'),self.auth(expiresAt='2020-01-01T00:00:00+00:00'))
 def test_changed_request_rejected(self):
  with patch.object(r,'sha',return_value='changed'):
   with self.assertRaisesRegex(ValueError,'binding mismatch'):r.authorize(self.request(),Path('request'),self.auth())
 def test_consumed_claim_not_reused(self):
  with patch.object(r,'sha',return_value='hash'),patch.object(os,'open',side_effect=FileExistsError('already used')):
   with self.assertRaises(FileExistsError):r.authorize(self.request(),Path('request'),self.auth())
 def test_world_readable_authorization_rejected(self):
  auth=self.auth();auth.lstat.return_value.st_mode=stat.S_IFREG|0o644
  with self.assertRaisesRegex(ValueError,'private'):r.authorize(self.request(),Path('request'),auth)
if __name__=='__main__':unittest.main()
