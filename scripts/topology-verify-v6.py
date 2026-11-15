#!/usr/bin/env python3
"""Offline v6 diagnostic only: never publishes acceptance or migrates storage."""
import contextlib
import importlib.util
import json
import re
import sqlite3
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('offline_v6_base', ROOT / 'scripts/topology-migration.py')
offline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(offline)


def reference_schema():
    """Read CREATE definitions from reviewed Java source; never run its migration."""
    source = (ROOT / 'src/main/java/io/github/gavinruff007/torchnode/storage/DiscoveryEvidenceSchema.java').read_text()
    identity = lambda name: "(" + name + " IS NULL OR (length(" + name + ")=128 AND " + name + " NOT GLOB '*[^0-9a-f]*'))"
    statements = []
    for raw in re.findall(r's.execute\("((?:[^"\\]|\\.)*)"\)', source):
        sql = json.loads('"' + raw + '"')
        if sql.startswith('CREATE '):
            statements.append(sql)
    for sql in re.findall(r's.execute\("""(.*?)"""', source, re.S):
        sql = sql.strip()
        if 'CREATE TABLE discovery_record_evidence' in sql:
            sql = sql.replace('source_identity TEXT, target_identity TEXT,', 'source_identity TEXT CHECK(' + identity('source_identity') + '), target_identity TEXT CHECK(' + identity('target_identity') + '),')
        elif 'CREATE TABLE discovery_relationship_observations' in sql:
            sql = sql.replace('CHECK(source_identity<>target_identity)', "CHECK(source_identity<>target_identity), CHECK(length(source_identity)=128 AND source_identity NOT GLOB '*[^0-9a-f]*'), CHECK(length(target_identity)=128 AND target_identity NOT GLOB '*[^0-9a-f]*')")
        elif 'CREATE TABLE discovery_candidates' in sql:
            sql = sql.replace('source_identity TEXT, target_identity TEXT', 'source_identity TEXT CHECK(' + identity('source_identity') + '), target_identity TEXT CHECK(' + identity('target_identity') + ')')
        statements.append(sql)
    statements.insert(1, 'CREATE TABLE discovery_runs(id TEXT PRIMARY KEY, observer_id TEXT NOT NULL REFERENCES observer_installations(id), started_at TEXT NOT NULL, local_identity TEXT CHECK(' + identity('local_identity') + '), key_lifecycle TEXT NOT NULL, UNIQUE(id,observer_id))')
    for column in ('observed_at', 'observer_id,observed_at', 'protocol,observed_at', 'source_identity,observed_at', 'target_identity,observed_at'):
        statements.append('CREATE INDEX idx_relationship_' + column.replace(',', '_') + ' ON discovery_relationship_observations(' + column + ',id,seq)')
    for table in sorted(offline.NEW):
        for operation in ('update', 'delete'):
            statements.append("CREATE TRIGGER immutable_" + table + '_' + operation + ' BEFORE ' + operation.upper() + " ON " + table + " BEGIN SELECT RAISE(ABORT,'Discovery evidence is append-only'); END")
    statements.sort(key=lambda sql: 0 if sql.startswith('CREATE TABLE') else 1)
    with contextlib.closing(sqlite3.connect(':memory:')) as db:
        for sql in statements:
            db.execute(sql)
        objects = {(t, n): sql for t, n, sql in db.execute("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'")}
        assert sum(t == 'table' for t, n in objects) == 9
        assert sum(t == 'index' for t, n in objects) == 13
        assert sum(t == 'trigger' for t, n in objects) == 19
        assert sum(t == 'view' for t, n in objects) == 1
        return statements, objects


def sql_tokens(sql):
    return [t if t.startswith("'") else t.lower() for t in re.findall(r"'(?:''|[^'])*'|[A-Za-z_][A-Za-z_0-9]*|[0-9]+|[^\s]", sql)]


def inspect(db, end, results=None):
    results = {} if results is None else results
    def record(name, ok, details):
        results[name] = {'status': 'PASS' if ok else 'FAIL', 'details': details}
    integrity = db.execute('PRAGMA integrity_check').fetchall()
    record('integrity', integrity == [('ok',)], integrity[:20])
    fk = db.execute('PRAGMA foreign_key_check').fetchmany(21)
    record('foreignKeys', not fk, {'violationsFirst20': fk[:20], 'truncated': len(fk)>20})
    versions = [r[0] for r in db.execute('SELECT version FROM schema_migrations ORDER BY version')]
    record('schemaHistory', versions == list(range(1,7)), versions)
    _, expected = reference_schema()
    actual = {(t,n): sql for t,n,sql in db.execute("SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL")}
    mismatches = [n for (t,n), sql in expected.items() if (t,n) not in actual or sql_tokens(actual[t,n]) != sql_tokens(sql)]
    record('v6Definitions', not mismatches, {'checkedObjects':len(expected), 'mismatches':mismatches, 'scope':'Exact tokenized CREATE definitions include columns, PK/FK/UNIQUE/CHECK constraints, indexes, validation and immutability triggers, view. No mutation tests on retained copy.'})
    tables = sorted(n for t,n in actual if t=='table' and not n.startswith('sqlite_'))
    offline.require(offline.REQUIRED | offline.NEW <= set(tables), 'required historical/v6 tables missing')
    offline.require(len(tables)<=100, 'inventory table limit exceeded')
    counts = {}
    for table in tables:
        offline.require(time.monotonic()<end, 'v6 inventory deadline exceeded')
        counts[table] = db.execute('SELECT COUNT(*) FROM ' + offline.quote(table)).fetchone()[0]
    record('inventory', True, counts)
    bad = db.execute("""SELECT COUNT(*) FROM discovery_relationship_observations o
        LEFT JOIN discovery_runs d ON d.id=o.run_id AND d.observer_id=o.observer_id
        LEFT JOIN discovery_exchange_evidence e ON e.id=o.evidence_id
        LEFT JOIN discovery_record_evidence v ON v.evidence_id=o.evidence_id AND v.packet_index=o.packet_index AND v.record_index=o.record_index
        LEFT JOIN discovery_exchange_claims c ON c.exchange_key=o.correlation_ref AND c.evidence_id=o.evidence_id
        WHERE d.id IS NULL OR e.id IS NULL OR v.evidence_id IS NULL OR c.evidence_id IS NULL
        OR e.run_id<>o.run_id OR e.protocol<>o.protocol OR e.contract_version<>2
        OR e.classification<>'ATTRIBUTABLE_EXCHANGE' OR e.authentication<>o.authentication
        OR v.source_identity IS NOT o.source_identity OR v.target_identity IS NOT o.target_identity
        OR v.authentication<>o.authentication OR v.enr_signature<>'VALID' OR v.identity_comparison<>'MATCH'
        OR v.correlation<>'AUTHENTICATED_REQUEST_MATCH'""").fetchone()[0]
    rejected = db.execute('SELECT COUNT(*) FROM discovery_relationship_observations o WHERE EXISTS(SELECT 1 FROM discovery_evidence_rejections r WHERE r.evidence_id=o.evidence_id)').fetchone()[0]
    record('relationshipConsistency', bad==0, {'inconsistentRecords':bad, 'observations':counts['discovery_relationship_observations'], 'rejectedHistoricalObservations':rejected, 'meaning':'Rejected historical rows are excluded by eligibility view, not deleted; zero rows is vacuous, not collection proof.'})
    raw_bad = 0
    unsupported = 0
    import hashlib
    for raw, digest in db.execute('SELECT raw_json,raw_hash FROM discovery_exchange_evidence'):
        offline.require(time.monotonic()<end, 'evidence hash deadline exceeded')
        value = json.loads(raw)
        def canonical(value):
            if isinstance(value, float):
                raise ValueError('Jackson floating-point canonicalization not independently matched')
            if isinstance(value, dict):
                return {k: canonical(value[k]) for k in sorted(value, key=lambda k: k.encode('utf-16-be'))}
            if isinstance(value, list):
                return [canonical(x) for x in value]
            return value
        try:
            encoded = json.dumps(canonical(value), ensure_ascii=False, separators=(',', ':')).encode()
        except ValueError:
            unsupported += 1
            continue
        raw_bad += int(hashlib.sha256(encoded).hexdigest()!=digest)
    record('evidencePayloadHashes', raw_bad==0, {'mismatchedHashes':raw_bad, 'unsupportedFloatingPointPayloads':unsupported})
    if unsupported and not raw_bad:
        results['evidencePayloadHashes']['status'] = 'INDETERMINATE'
    results['limitations'] = ['No live comparison: historical/source preservation and ingestion completeness cannot be proved.', 'Schema/SQL relationships do not independently reauthenticate packets, ENR signatures or peer connectivity.', 'No acceptance marker, final provenance, migration or v5 rollback classification.']
    return results
