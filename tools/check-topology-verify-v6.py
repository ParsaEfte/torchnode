"""Synthetic schema fixtures only; no retained or live database is selected."""
import importlib.util
from pathlib import Path
import sqlite3
import time
import unittest
s=importlib.util.spec_from_file_location('v6',Path(__file__).resolve().parents[1]/'scripts/topology-verify-v6.py')
v=importlib.util.module_from_spec(s);s.loader.exec_module(v)
class Verification(unittest.TestCase):
    def fixture(self):
        db=sqlite3.connect(':memory:');self.addCleanup(db.close)
        for sql in v.reference_schema()[0]:db.execute(sql)
        db.execute('CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY)')
        db.executemany('INSERT INTO schema_migrations VALUES(?)',[(i,) for i in range(1,7)])
        for name in v.offline.REQUIRED-{'schema_migrations'}:db.execute('CREATE TABLE '+name+'(value TEXT)')
        return db
    def test_exact_v6_empty_schema(self):
        r=v.inspect(self.fixture(),time.monotonic()+10)
        self.assertTrue(all(x['status']=='PASS' for k,x in r.items() if k!='limitations'))
        self.assertEqual(r['v6Definitions']['details']['checkedObjects'],42)
    def test_v5_not_accepted_as_v6(self):
        db=self.fixture();db.execute('DELETE FROM schema_migrations WHERE version=6')
        self.assertEqual(v.inspect(db,time.monotonic()+10)['schemaHistory']['status'],'FAIL')
    def test_named_but_wrong_index_not_accepted(self):
        db=self.fixture();db.execute('DROP INDEX idx_discovery_runs_observer');db.execute('CREATE INDEX idx_discovery_runs_observer ON discovery_runs(id)')
        self.assertEqual(v.inspect(db,time.monotonic()+10)['v6Definitions']['status'],'FAIL')
    def test_missing_constraint_trigger_not_accepted(self):
        db=self.fixture();db.execute('DROP TRIGGER validate_attributable_relationship')
        self.assertEqual(v.inspect(db,time.monotonic()+10)['v6Definitions']['status'],'FAIL')
    def test_foreign_key_orphan_record_detected(self):
        db=self.fixture();db.execute("INSERT INTO discovery_runs VALUES('run','missing','time',NULL,'EPHEMERAL_FIRST_SEEN')")
        self.assertEqual(v.inspect(db,time.monotonic()+10)['foreignKeys']['status'],'FAIL')
    def test_payload_hash_corruption_detected(self):
        db=self.fixture();db.execute("INSERT INTO discovery_exchange_evidence VALUES(?,?,NULL,'discv5','TRACE',2,NULL,'{}',?,NULL,'DIAGNOSTIC','UNKNOWN','time')",('a'*64,'receipt','0'*64))
        self.assertEqual(v.inspect(db,time.monotonic()+10)['evidencePayloadHashes']['status'],'FAIL')
    def test_canonical_not_raw_json_hash(self):
        import hashlib
        db=self.fixture()
        raw='{ "z": 2, "a": {"b": 1, "a": true} }'
        digest=hashlib.sha256(b'{"a":{"a":true,"b":1},"z":2}').hexdigest()
        db.execute("INSERT INTO discovery_exchange_evidence VALUES(?,?,NULL,'discv5','TRACE',2,NULL,?,?,NULL,'DIAGNOSTIC','UNKNOWN','time')",('a'*64,'receipt',raw,digest))
        self.assertEqual(v.inspect(db,time.monotonic()+10)['evidencePayloadHashes']['status'],'PASS')
    def test_deadline(self):
        with self.assertRaises(ValueError):v.inspect(self.fixture(),time.monotonic()-1)
if __name__=='__main__':unittest.main()
