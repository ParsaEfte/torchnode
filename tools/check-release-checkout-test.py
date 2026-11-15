import contextlib,io,json,runpy,subprocess,unittest
from pathlib import Path
from unittest.mock import patch
script=Path(__file__).with_name('check-release-checkout.py')
class CheckoutGuard(unittest.TestCase):
 def run_guard(self,status,tracked):
  def output(args,**kwargs):
   if args[1:3]==['status','--porcelain']:return status
   if args[1]=='ls-files':return ('\0'.join(tracked)+'\0').encode()
   if args[1]=='rev-parse':return 'a'*40+'\n'
   raise AssertionError(args)
  stream=io.StringIO();code=0
  with patch.object(subprocess,'check_output',side_effect=output),contextlib.redirect_stdout(stream):
   try:runpy.run_path(str(script),run_name='__main__')
   except SystemExit as error:code=error.code
  return code,json.loads(stream.getvalue())
 def test_clean_source_allowed(self):self.assertEqual(self.run_guard('', ['pom.xml','src/main/java/Main.java'])[0],0)
 def test_dirty_checkout_rejected(self):self.assertEqual(self.run_guard(' M pom.xml\n',['pom.xml'])[0],1)
 def test_runtime_database_rejected(self):self.assertEqual(self.run_guard('', ['nested/private.sqlite'])[0],1)
 def test_private_approval_record_rejected(self):self.assertEqual(self.run_guard('', ['docs/renewal-approval.json'])[0],1)
 def test_private_manifest_rejected(self):self.assertEqual(self.run_guard('', ['docs/v6-recovery-attempt2-artifacts.json'])[0],1)
 def test_private_validation_output_rejected(self):self.assertEqual(self.run_guard('', ['docs/validation/offline-v6-record.json'])[0],1)
if __name__=='__main__':unittest.main()
