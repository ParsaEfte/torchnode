#!/usr/bin/env python3
"""B6-B OFFLINE tooling. No production defaults, discovery, or automatic path search."""
import argparse
import contextlib
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import stat
import subprocess
import sys
import time
import uuid

REQUIRED = {'nodes', 'p2p_observations', 'schema_migrations', 'discovery_observations',
            'enr_observations', 'inspection_runs', 'inspection_evidence',
            'discovery_endpoint_index', 'network_enrichment', 'network_enrichment_lookups',
            'inspection_enrichment_context', 'change_events'}
NEW = {'observer_installations', 'discovery_runs', 'discovery_exchange_evidence',
       'discovery_exchange_claims', 'discovery_record_evidence',
       'discovery_relationship_observations', 'discovery_candidates',
       'discovery_evidence_rejections', 'discovery_capture_health'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def safe_path(path):
    p = Path(path)
    require(p.is_absolute(), 'absolute paths required')
    require('..' not in p.parts, 'parent traversal forbidden')
    require(not any(part in {'torchnode.db', 'torchnode.db-wal', 'torchnode.db-shm'}
                    for part in p.parts), 'production-style database paths forbidden')
    for item in (p, *p.parents):
        require(not item.is_symlink(), 'symlink paths forbidden; use canonical paths')
    return p


def workspace(path, require_offline=True):
    root = safe_path(path)
    require(root.is_dir(), 'offline workspace must exist')
    require(root.stat().st_mode & 0o077 == 0, 'offline workspace must be private (mode 0700)')
    marker = safe_path(root / 'B6B-OFFLINE.json')
    require(marker.is_file() and marker.stat().st_nlink == 1, 'offline manifest required')
    require(marker.stat().st_size <= 8192, 'manifest too large')
    manifest = json.loads(marker.read_text())
    require(manifest.get('kind') in {'fixture', 'approved-offline'}, 'offline provenance required')
    require(manifest.get('source') == 'backup-v5.sqlite', 'source must be backup-v5.sqlite inside workspace')
    if require_offline:
        require(manifest.get('offline') is True, 'explicit offline declaration required')
    if manifest['kind'] == 'approved-offline':
        require(bool(manifest.get('approvalReference')), 'offline approval reference required')
    return root, manifest


def sha(path):
    h = hashlib.sha256()
    end = time.monotonic() + 120
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            require(time.monotonic() < end, 'file hash deadline exceeded')
            h.update(block)
    return h.hexdigest()


def source(root, manifest):
    p = safe_path(root / manifest['source'])
    require(p.is_file() and stat.S_ISREG(p.stat().st_mode) and p.stat().st_nlink == 1,
            'single-link regular offline source required')
    require(not any(Path(str(p) + suffix).exists() for suffix in ('-wal', '-shm', '-journal')),
            'offline source has SQLite sidecars; do not ignore or delete them')
    with p.open('rb') as stream:
        require(stream.read(16) == b'SQLite format 3\x00', 'invalid SQLite header')
    require(sha(p) == manifest.get('sha256'), 'source SHA-256 does not match manifest')
    return p


@contextlib.contextmanager
def readonly(path):
    # immutable is valid only after the explicit offline/sidecar checks above.
    db = sqlite3.connect(path.as_uri() + '?mode=ro&immutable=1', uri=True, timeout=.5)
    end = min(time.monotonic() + 60, globals().get('end_time', float('inf')))
    db.set_progress_handler(lambda: int(time.monotonic() >= end), 1000)
    try:
        db.execute('PRAGMA query_only=ON')
        yield db
    finally:
        db.close()


def quote(name):
    return '"' + name.replace('"', '""') + '"'


def inventory(path, version):
    with readonly(path) as db:
        require(db.execute('PRAGMA integrity_check').fetchall() == [('ok',)], 'integrity_check failed')
        require(not db.execute('PRAGMA foreign_key_check').fetchmany(1), 'foreign_key_check failed')
        objects = db.execute("SELECT type,name,tbl_name,sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name").fetchall()
        tables = {r[1] for r in objects if r[0] == 'table'}
        require(REQUIRED <= tables, 'required historical tables missing')
        prerequisites = {
            'nodes': {'key', 'node_id', 'ip', 'discovery_source'},
            'discovery_observations': {'id', 'node_id', 'source', 'provenance', 'observed_at', 'endpoints_json'},
            'enr_observations': {'id', 'node_id', 'observed_at', 'evidence_json', 'signature_validation', 'identity_comparison'},
            'inspection_runs': {'id', 'node_id', 'started_at', 'evidence_hash'},
            'inspection_evidence': {'hash', 'evidence_json'},
            'discovery_endpoint_index': {'observation_id', 'node_id', 'address', 'observed_at'},
            'change_events': {'id', 'node_id', 'current_observation_id', 'derivation_version'}
        }
        for table, columns in prerequisites.items():
            require(columns <= {r[1] for r in db.execute('PRAGMA table_info(' + quote(table) + ')')},
                    'migration prerequisite columns missing')
        versions = [r[0] for r in db.execute('SELECT version FROM schema_migrations ORDER BY version')]
        require(versions == list(range(1, version + 1)), 'unexpected or incomplete migration version history')
        require(version != 5 or not (NEW & tables), 'v5 has partial/conflicting v6 objects')
        if version == 6:
            require(NEW <= tables, 'v6 tables missing')
            names = {r[1] for r in objects}
            expected = {'discovery_attributable_relationships', 'validate_attributable_relationship'}
            expected |= {'idx_relationship_' + x for x in ['observed_at', 'observer_id_observed_at',
                         'protocol_observed_at', 'source_identity_observed_at', 'target_identity_observed_at']}
            expected |= {'idx_discovery_runs_observer', 'idx_discovery_evidence_time', 'idx_discovery_evidence_run',
                         'idx_discovery_candidates_time', 'idx_discovery_candidates_source', 'idx_discovery_health_run',
                         'idx_discovery_record_source', 'idx_discovery_record_target'}
            expected |= {'immutable_' + table + '_' + op for table in NEW for op in ['update', 'delete']}
            require(expected <= names, 'v6 indexes/triggers/view missing')
        counts, digests = {}, {}
        # Bounded-memory multiset digest: duplicates counted, raw rows never included in reports.
        for table in sorted(tables - {'schema_migrations'}):
            total = count = 0
            for row in db.execute('SELECT * FROM ' + quote(table)):
                encoded = json.dumps([(type(x).__name__, x.hex() if isinstance(x, bytes) else x)
                                      for x in row], ensure_ascii=True, separators=(',', ':')).encode()
                require(len(encoded) <= 16 * 1024 * 1024, 'row exceeds verification budget')
                total = (total + int.from_bytes(hashlib.sha256(encoded).digest(), 'big')) % (1 << 256)
                count += 1
                require(time.monotonic() < end_time, 'inventory deadline exceeded')
            counts[table], digests[table] = count, f'{total:064x}'
        return {'schemaVersion': version, 'versions': versions, 'objects': objects,
                'counts': counts, 'digests': digests, 'integrity': 'ok', 'foreignKeys': 'ok'}


def check_space(root, size):
    required = max(64 * 1024 * 1024, size * 5)
    free = shutil.disk_usage(root).free
    require(free >= required, 'insufficient disk space: require five source sizes or 64 MiB')
    return {'freeBytes': free, 'requiredBytes': required}


def exclusive(path):
    return os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)


def write_json(path, value):
    with os.fdopen(exclusive(path), 'w') as out:
        json.dump(value, out, indent=2)
        out.write('\n')


def snapshot_fixture(root, manifest, destination):
    require(manifest['kind'] == 'fixture', 'online snapshot command is fixture-only; production requires separate approval')
    require(destination and Path(destination).name == destination and destination.endswith('.sqlite')
            and destination != manifest['source'], 'distinct destination basename required')
    src = safe_path(root / manifest['source'])
    require(src.is_file() and src.stat().st_nlink == 1, 'regular fixture source required')
    dest = safe_path(root / destination)
    require(not dest.exists(), 'snapshot destination already exists')
    check_space(root, src.stat().st_size)
    os.close(exclusive(dest))
    end = time.monotonic() + 15
    def progress(status, remaining, total):
        require(time.monotonic() < end, 'snapshot deadline exceeded; incomplete destination retained')
    try:
        with contextlib.closing(sqlite3.connect(src.as_uri() + '?mode=ro', uri=True, timeout=.25)) as source_db:
            with contextlib.closing(sqlite3.connect(dest)) as target_db:
                source_db.backup(target_db, pages=32, progress=progress, sleep=.05)
                target_db.execute('PRAGMA journal_mode=DELETE')
        result = inventory(dest, 5)
        write_json(dest.with_suffix('.json'), {'status': 'verified', 'sha256': sha(dest),
                   'schemaVersion': 5, 'sqliteVersion': sqlite3.sqlite_version,
                   'createdAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'counts': result['counts']})
        return {'status': 'verified', 'destination': destination}
    except Exception:
        # Preserve failure artifacts for review; never delete the source or replace a previous backup.
        raise


def run_probe(args, mode, path, log):
    require(bool(args.java) and bool(args.classpath), 'dry-run requires explicit --java and --classpath')
    java = safe_path(args.java)
    require(java.is_file() and os.access(java, os.X_OK), 'explicit executable Java 21 path required')
    require(bool(args.classpath), 'explicit compiled project classpath required')
    env = os.environ.copy()
    env['TORCHNODE_SECURE_COOKIES'] = 'false'  # only the disposable loopback smoke application
    with log.open('xb') as stream:
        process = subprocess.run([str(java), '-cp', args.classpath,
             'io.github.gavinruff007.torchnode.storage.B6MigrationProbe', mode, str(path)],
             stdout=stream, stderr=stream, env=env, timeout=45)
    require(process.returncode == 0, 'Java ' + mode + ' failed; inspect private working-directory log')


def dry_run(args, root, manifest, src, before):
    check_space(root, src.stat().st_size)
    run = root / ('run-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:12])
    run.mkdir(mode=0o700)  # exclusive; no user-selected existing output directory
    (run / 'B6B-WORK').write_text('disposable offline migration copy\n')
    report = {'status': 'failed', 'realBackupVerification': 'PENDING', 'sourceSha256': manifest['sha256'],
              'pythonVersion': sys.version.split()[0], 'verifierSqliteVersion': sqlite3.sqlite_version,
              'verifierSha256': sha(Path(__file__).resolve())}
    try:
        working = run / 'working.sqlite'
        with src.open('rb') as original, os.fdopen(exclusive(working), 'wb') as copy:
            shutil.copyfileobj(original, copy, 1024 * 1024)
        require(sha(working) == manifest['sha256'], 'working-copy hash mismatch')
        write_json(run / 'before.json', before)
        run_probe(args, 'migrate', working, run / 'migration.log')
        after = inventory(working, 6)
        old_objects = {tuple(r) for r in before['objects']}
        require(old_objects <= {tuple(r) for r in after['objects']}, 'historical schema changed')
        for table in before['counts']:
            require((before['counts'][table], before['digests'][table]) ==
                    (after['counts'][table], after['digests'][table]), 'historical data changed')
        require(all(after['counts'][table] == 0 for table in NEW), 'migration fabricated relationship evidence')
        run_probe(args, 'migrate', working, run / 'idempotency.log')
        require(inventory(working, 6) == after, 'migration not logically idempotent')
        write_json(run / 'after.json', after)
        # Authentication smoke adds only a temporary viewer to a THIRD copy, never the migrated artifact.
        smoke = run / 'smoke.sqlite'
        with working.open('rb') as original, os.fdopen(exclusive(smoke), 'wb') as copy:
            shutil.copyfileobj(original, copy, 1024 * 1024)
        run_probe(args, 'smoke', smoke, run / 'startup.log')
        require(inventory(working, 6) == after, 'startup smoke changed migration artifact')
        report.update(status='passed', historicalPreserved=True, migrationIdempotent=True,
                      startupAndAuthenticatedTopology=True, rollbackPrepared=True,
                      rollbackSourceSchema=5, migratedSchema=6, deploymentReady=False,
                      rollbackScope='verified database checkpoint only; previous binary/configuration approval still required')
    finally:
        report['sourceUnchanged'] = sha(src) == manifest['sha256']
        if not report['sourceUnchanged']:
            report['status'] = 'failed'
        write_json(run / 'report.json', report)
        with os.fdopen(exclusive(run / 'report.txt'), 'w') as out:
            out.write('Offline v5 → v6 dry run: ' + report['status'] + '\n')
            out.write('Original source unchanged: ' + str(report['sourceUnchanged']) + '\n')
            out.write('Real schema-v5 backup verification: PENDING; fixture results are not production approval.\n')
        print(json.dumps({'reportDirectory': str(run), **report}))
    require(report['status'] == 'passed', 'dry run failed')


def main():
    global end_time
    end_time = time.monotonic() + 240
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['preflight', 'dry-run', 'snapshot-fixture'])
    parser.add_argument('--workspace', required=True)
    parser.add_argument('--java')
    parser.add_argument('--classpath')
    parser.add_argument('--destination')
    args = parser.parse_args()
    os.umask(0o077)
    root, manifest = workspace(args.workspace, require_offline=args.command != 'snapshot-fixture')
    if args.command == 'snapshot-fixture':
        destination = args.destination or ('snapshot-' + datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:12] + '.sqlite')
        print(json.dumps(snapshot_fixture(root, manifest, destination)))
        return
    if args.command == 'dry-run':
        require(bool(args.java) and bool(args.classpath), 'dry-run requires explicit --java and --classpath')
    src = source(root, manifest)
    before = inventory(src, 5)
    space = check_space(root, src.stat().st_size)
    if args.command == 'preflight':
        print(json.dumps({'status': 'passed', 'schemaVersion': 5, 'sha256': manifest['sha256'],
                          'tables': before['counts'], 'space': space, 'realBackupVerification': 'PENDING'}))
    else:
        dry_run(args, root, manifest, src, before)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, sqlite3.Error, subprocess.SubprocessError) as error:
        # Never print source rows, credentials, raw SQL exceptions or inherited environment values.
        print(json.dumps({'status': 'failed', 'error': str(error) if isinstance(error, ValueError)
                          else type(error).__name__,
                          'sqliteCode': getattr(error, 'sqlite_errorname', None),
                          'errno': getattr(error, 'errno', None)}), file=sys.stderr)
        sys.exit(1)
