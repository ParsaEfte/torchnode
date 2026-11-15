import unittest
from dependency_ranges import affected,version,github_affected,maven_query

class SecurityMatching(unittest.TestCase):
 def test_maven_module_suffix_and_classifier(self):
  self.assertEqual(maven_query(' a:b:jar:1.2.3:compile -- module b (auto)')['version'],'1.2.3')
  self.assertEqual(maven_query(' a:b:jar:linux:1.2.3:runtime')['version'],'1.2.3')
  self.assertIsNone(maven_query(' a:b:jar:1.2.3:test'))
 def test_fixed_boundary(self):
  ranges=[{'type':'SEMVER','events':[{'introduced':'0'},{'fixed':'1.5.3'}]}]
  self.assertTrue(affected('v1.4.2',ranges));self.assertFalse(affected('v1.5.3',ranges))
 def test_multiple_intervals(self):
  ranges=[{'type':'SEMVER','events':[{'introduced':'0'},{'fixed':'1.82.2'},{'introduced':'1.83.0'},{'fixed':'1.83.2'}]}]
  self.assertFalse(affected('v1.82.2',ranges));self.assertTrue(affected('v1.83.1',ranges));self.assertFalse(affected('v1.83.2',ranges))
 def test_prerelease(self):
  self.assertLess(version('v0.58.1-0.20261009181718-2f1f834330b2'),version('v0.59.0'))
  self.assertLess(version('v1.0.0-rc.2'),version('v1.0.0-rc.10'));self.assertLess(version('v1.0.0-rc.10'),version('v1.0.0'))
  self.assertEqual(version('v1.0.0+incompatible'),version('v1.0.0'))
 def test_inclusive_end(self):
  ranges=[{'type':'SEMVER','events':[{'introduced':'0'},{'last_affected':'1.0.0'}]}]
  self.assertTrue(affected('1.0.0',ranges));self.assertFalse(affected('1.0.1',ranges))
 def test_github(self):
  self.assertTrue(github_affected('v1.46.0','>= 1.33.0, < 1.47.0'));self.assertFalse(github_affected('v1.47.0','>= 1.33.0, < 1.47.0'))
 def test_invalid_data_fails_closed(self):
  with self.assertRaises(ValueError):version('not-a-version')
  with self.assertRaises(ValueError):affected('1.0.0',[{'type':'GIT','events':[]}])
  with self.assertRaises(ValueError):github_affected('1.0.0','arbitrary syntax')

if __name__=='__main__':unittest.main()
