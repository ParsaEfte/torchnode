import importlib.util,json,tempfile,unittest,sys,hashlib,copy,os,stat
from types import SimpleNamespace
from pathlib import Path
from unittest.mock import patch
s=importlib.util.spec_from_file_location('rehearsal',Path(__file__).resolve().parents[1]/'scripts/topology-recovery-rehearsal.py');r=importlib.util.module_from_spec(s);s.loader.exec_module(r)
class Gates(unittest.TestCase):
 def test_only_exact_security_initialization_may_change_schema(self):
  pre={'objects':[('table','nodes','nodes','CREATE TABLE nodes(id TEXT)')],'versions':[1,2,3,4,5,6],'counts':{'nodes':1},'digests':{'nodes':'historical'}}
  post=copy.deepcopy(pre);post['objects']+= [('table',name,name,sql) for name,sql in r.AUTH_SCHEMA.items()]
  post['counts']['auth_schema']=1;post['digests']['auth_schema']=hashlib.sha256(b'[["int",1]]').hexdigest()
  r.preserved_inventory(pre,post)
  changed=copy.deepcopy(post);changed['digests']['nodes']='mutated'
  with self.assertRaisesRegex(ValueError,'historical/evidence'):r.preserved_inventory(pre,changed)
  changed=copy.deepcopy(post);changed['objects'].append(('trigger','extra','auth_users','CREATE TRIGGER extra ...'))
  with self.assertRaisesRegex(ValueError,'security objects'):r.preserved_inventory(pre,changed)
 def test_draft_authorization_rejected_before_retained_access(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp).resolve();classes=root/'classes';classes.mkdir();jar=root/'candidate.jar';jar.touch()
   pins={'containerImageId':'sha256:'+'a'*64,'application':{'path':str(jar),'sha256':'h'},'probeClasses':str(classes),'probeHashes':{},'offlineVerifierSha256':'h','rehearsalScriptSha256':'h'}
   manifest=root/'manifest.json';manifest.write_text(json.dumps(pins));auth=root/'request.json';auth.write_text('{"decision":"NOT_AUTHORIZED"}');auth.chmod(0o600)
   argv=['rehearsal','execute','--mode','retained','--source',str(r.RETAINED),'--workspace',str(root/'v6-rehearsal-fixture'),'--artifact-manifest',str(manifest),'--image',pins['containerImageId'],'--authorization-file',str(auth)]
   with patch.object(sys,'argv',argv),patch.object(r,'sha',return_value='h'),patch.object(r,'source_gate',side_effect=AssertionError('retained source must not be accessed')),patch.object(r.subprocess,'run',side_effect=AssertionError('Docker must not execute')):
    with self.assertRaisesRegex(ValueError,'not execution authorization'):r.main()
 def test_fixture_rejects_retained_before_source_access(self):
  with patch.object(Path,'lstat',side_effect=AssertionError('must not inspect retained source')):
   with self.assertRaises(ValueError):r.source_gate('fixture',r.RETAINED)
 def test_retained_rejects_other_path(self):
  with self.assertRaises(ValueError):r.source_gate('retained','/unopened/torchnode.db')
 def test_fixture_private_declaration(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp).resolve();(root/'V6-FIXTURE.json').write_text(json.dumps({'synthetic':True}));p=root/'fixture-v6.sqlite';p.write_bytes(b'synthetic')
   self.assertEqual(r.source_gate('fixture',p),p)
 def test_fixture_symlink_refused(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp).resolve();(root/'V6-FIXTURE.json').write_text('{"synthetic":true}');(root/'other').write_bytes(b'fixture');p=root/'fixture-v6.sqlite';p.symlink_to(root/'other')
   with self.assertRaises(ValueError):r.source_gate('fixture',p)
 def test_container_isolation(self):
  cmd=r.container_command('v6-rehearsal-fixture','sha256:'+'a'*64,Path('/tmp/private-work'),Path('/tmp/candidate.jar'),Path('/tmp/classes'))
  for value in ['--network=none','--read-only','--pull=never','--cap-drop=ALL','--cidfile','TORCHNODE_COLLECTION_DISABLED=true']:self.assertIn(value,cmd)
  self.assertNotIn('-p',cmd);self.assertNotIn('--privileged',cmd);self.assertFalse(any(str(r.RETAINED) in v for v in cmd))
  with self.assertRaises(ValueError):r.container_command('fixture','latest',Path('/tmp/w'),Path('/tmp/j'),Path('/tmp/c'))
class IdentityDiagnostics(unittest.TestCase):
 def recorded(self):
  root=Path(__file__).resolve().parents[1]
  return json.loads((root/'tools/fixtures/v6-recorded-identity.json').read_text())['expected']
 def fake_stat(self,identity):
  values={attribute:identity[name] for name,attribute in r.IDENTITY_FIELDS}
  values['st_mode']|=stat.S_IFREG
  return SimpleNamespace(**values)
 def mismatch(self,expected,observed):
  with self.assertRaises(ValueError) as caught:r.validate_retained_identity(expected,observed)
  return json.loads(str(caught.exception).split(': ',1)[1])
 def test_recorded_identity_matches_with_file_type_bits(self):
  expected=self.recorded();r.validate_retained_identity(expected,self.fake_stat(expected))
 def test_each_field_names_exact_expected_and_actual(self):
  expected=self.recorded()
  for name,attribute in r.IDENTITY_FIELDS:
   with self.subTest(field=name):
    observed=self.fake_stat(expected);setattr(observed,attribute,getattr(observed,attribute)+1)
    self.assertEqual(self.mismatch(expected,observed),{name:{'expected':expected[name],'actual':expected[name]+1}})
 def test_reports_multiple_fields_only(self):
  expected=self.recorded();observed=self.fake_stat(expected);observed.st_ino+=1;observed.st_mtime_ns+=1
  self.assertEqual(set(self.mismatch(expected,observed)),{'inode','mtimeNs'})
 def test_integer_nanoseconds_survive_json_without_rounding(self):
  expected=json.loads(json.dumps(self.recorded()));self.assertGreater(expected['mtimeNs'],2**53)
  observed=self.fake_stat(expected);observed.st_mtime_ns+=1
  self.assertEqual(self.mismatch(expected,observed)['mtimeNs']['actual'],expected['mtimeNs']+1)
 def test_malformed_values_are_rejected_without_sensitive_output(self):
  for value in ['SECRET_DB_CONTENT',float(self.recorded()['mtimeNs']),True,None,{'secret':'SECRET_DB_CONTENT'}]:
   with self.subTest(value_type=type(value).__name__):
    expected=self.recorded();observed=self.fake_stat(expected);expected['mtimeNs']=value;expected['untrustedPath']='SECRET_DB_CONTENT'
    details=self.mismatch(expected,observed)
    self.assertEqual(details['mtimeNs']['expected'],'<invalid non-integer>')
    self.assertNotIn('SECRET_DB_CONTENT',json.dumps(details));self.assertNotIn('untrustedPath',details)
 def test_missing_field_identified(self):
  expected=self.recorded();observed=self.fake_stat(expected);del expected['inode']
  self.assertEqual(self.mismatch(expected,observed)['inode']['expected'],'<missing>')
 def test_matching_bytes_do_not_bypass_changed_metadata(self):
  with tempfile.TemporaryDirectory() as tmp:
   p=Path(tmp)/'synthetic-only';p.write_bytes(b'synthetic fixture, not retained data')
   before=p.stat();expected=r.retained_identity(before);content=hashlib.sha256(p.read_bytes()).hexdigest()
   os.utime(p,ns=(before.st_atime_ns,before.st_mtime_ns+2_000_000_000))
   self.assertEqual(hashlib.sha256(p.read_bytes()).hexdigest(),content)
   details=self.mismatch(expected,p.stat());self.assertEqual(set(details),{'mtimeNs'})
 def test_recorded_manifest_matches_both_historical_records(self):
  root=Path(__file__).resolve().parents[1];expected=self.recorded()
  fixture=json.loads((root/'tools/fixtures/v6-recorded-identity.json').read_text())
  for name,record in fixture['historical'].items():
   for phase in ['before','after']:
    with self.subTest(record=name,phase=phase):
     observed=record[phase]
     self.assertEqual(observed,expected)
     r.validate_retained_identity(expected,self.fake_stat(observed))

if __name__=='__main__':unittest.main()
