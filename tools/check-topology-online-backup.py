"""Synthetic temporary fixtures only. Never discover or accept a production source."""
import argparse
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import uuid

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/topology-online-backup.py'
spec = importlib.util.spec_from_file_location('online_backup', SCRIPT)
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
# Spawn requires the imported worker module to be importable by name.
sys.modules[spec.name] = b
sys.path.insert(0, str(SCRIPT.parent))

COLUMNS = {
 'nodes': 'key,node_id,ip,discovery_source',
 'discovery_observations': 'id,node_id,source,provenance,observed_at,endpoints_json',
 'enr_observations': 'id,node_id,observed_at,evidence_json,signature_validation,identity_comparison',
 'inspection_runs': 'id,node_id,started_at,evidence_hash',
 'inspection_evidence': 'hash,evidence_json',
 'discovery_endpoint_index': 'observation_id,node_id,address,observed_at',
 'change_events': 'id,node_id,current_observation_id,derivation_version',
 'p2p_observations': 'value', 'network_enrichment': 'value',
 'network_enrichment_lookups': 'value', 'inspection_enrichment_context': 'value'}


def stalled_worker(config):
    time.sleep(20)


def retained_worker(config):
    import signal
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    (Path(config['workspace']) / 'partial-fixture.sqlite').write_bytes(b'partial synthetic artifact')
    time.sleep(20)


def wal_growth_worker(config):
    (Path(config['workspace']) / 'partial-fixture.sqlite').write_bytes(b'partial synthetic artifact')
    # Synthetic metadata-only WAL-size injection, never a live SQLite sidecar.
    Path(config['source'] + '-wal').write_bytes(b'x' * 100)
    time.sleep(20)


@contextlib.contextmanager
def fixture_http(statuses=(200,), delay=0):
    from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
    class Handler(BaseHTTPRequestHandler):
        count = 0
        def do_GET(self):
            index = min(type(self).count, len(statuses)-1)
            type(self).count += 1
            time.sleep(delay)
            try:
                self.send_response(statuses[index]); self.end_headers(); self.wfile.write(b'ok')
            except (BrokenPipeError, ConnectionResetError):
                pass
        def log_message(self, *args):
            pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    if server.server_port == 8080:
        server.server_close()
        raise AssertionError('fixture must not use production port')
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    try:
        yield 'http://127.0.0.1:' + str(server.server_port) + '/healthz'
    finally:
        server.shutdown(); server.server_close(); thread.join(2)


class OnlineBackup(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='b6b-fixture-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        os.chmod(self.root, 0o700)
        self.src = self.root / b.FIXTURE_NAME
        b.write_json(self.root / 'B6B-FIXTURE.json', {'kind': 'synthetic-fixture', 'source': b.FIXTURE_NAME})
        with contextlib.closing(sqlite3.connect(self.src)) as db, db:
            db.execute('CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY)')
            db.executemany('INSERT INTO schema_migrations VALUES(?)', [(v,) for v in range(1, 6)])
            for table, cols in COLUMNS.items():
                db.execute('CREATE TABLE ' + table + '(' + ','.join(c + ' TEXT' for c in cols.split(',')) + ')')
            db.execute("INSERT INTO p2p_observations VALUES('historical')")
        self.workspace = self.root / ('b6b-real-v5-20261112T000000Z-' + uuid.uuid4().hex)
        self.args = argparse.Namespace(mode='fixture', source=str(self.src), workspace=str(self.workspace),
            approval_reference='synthetic-fixture-only', minimum_free_bytes=1024**2,
            deadline_seconds=5., verify_seconds=10., supervisor_seconds=20.,
            fixture_root=str(self.root), real_source=False, approval_file=None,
            health_url=None, max_wal_bytes=134217728, monitor_interval_seconds=.05, health_timeout_seconds=.5)
        # All in-process SQLite opens must be within THIS test's private root.
        real_connect = sqlite3.connect
        def guarded(database, *args, **kwargs):
            from urllib.parse import urlsplit, unquote
            raw = os.fspath(database)
            p = Path(unquote(urlsplit(raw).path)) if raw.startswith('file:') else Path(raw)
            if not p.resolve().is_relative_to(self.root):
                raise AssertionError('test attempted SQLite access outside its temporary root')
            return real_connect(database, *args, **kwargs)
        guard = patch.object(sqlite3, 'connect', side_effect=guarded)
        guard.start()
        self.addCleanup(guard.stop)

    def prepare(self):
        b.authorize(self.args)
        return b.prepare(self.args, None)

    def config(self):
        src, root, sid = self.prepare()
        return dict(source=str(src), workspace=str(root), sourceIdentity=sid, deadline=5., reserve=1024**2,
                    verifySeconds=10., supervisorSeconds=20., approvalReference='fixture', mode='fixture',
                    runtime=b.runtime(), toolHashes=b.tool_hashes())

    def test_valid_integrity_provenance_and_preservation(self):
        before = self.src.read_bytes()
        with contextlib.redirect_stdout(io.StringIO()):
            b.run(self.args)
        manifest = json.loads((self.workspace / 'B6B-OFFLINE.json').read_text())
        dest = self.workspace / 'backup-v5.sqlite'
        self.assertEqual(manifest['kind'], 'fixture')
        self.assertEqual(manifest['sha256'], hashlib.sha256(dest.read_bytes()).hexdigest())
        self.assertEqual(before, self.src.read_bytes())
        report = json.loads((self.workspace / 'backup-provenance.json').read_text())
        self.assertEqual(report['sqliteVersion'], sqlite3.sqlite_version)
        self.assertEqual(report['toolHashes'], b.tool_hashes())
        self.assertEqual(report['sourceIdentity'], b.identity(self.src))
        self.assertEqual(dest.stat().st_mode & 0o777, 0o400)
        self.assertEqual(self.workspace.stat().st_mode & 0o777, 0o700)
        self.assertEqual((self.workspace / 'B6B-OFFLINE.json').stat().st_mode & 0o777, 0o600)
        self.assertFalse(Path(str(dest) + '-wal').exists())
        b.offline_inventory(dest, time.monotonic() + 10)

    def test_wal_only_committed_row_and_source_sidecars_preserved(self):
        with contextlib.closing(sqlite3.connect(self.src)) as writer:
            writer.execute('PRAGMA journal_mode=WAL')
            writer.execute('PRAGMA wal_autocheckpoint=0')
            writer.execute("INSERT INTO p2p_observations VALUES('wal-only')")
            writer.commit()
            wal = Path(str(self.src) + '-wal'); shm = Path(str(self.src) + '-shm')
            wal_before = wal.read_bytes(); inode = shm.stat().st_ino
            cfg = self.config()
            b.worker(cfg)
            with contextlib.closing(sqlite3.connect(self.workspace / 'backup-v5.sqlite')) as copy, copy:
                self.assertEqual(copy.execute('SELECT value FROM p2p_observations ORDER BY value').fetchall(), [('historical',), ('wal-only',)])
            self.assertEqual(wal.read_bytes(), wal_before)
            self.assertEqual(shm.stat().st_ino, inode)
            self.assertEqual(writer.execute('SELECT COUNT(*) FROM p2p_observations').fetchone()[0], 2)

    def test_concurrent_fixture_writer(self):
        with contextlib.closing(sqlite3.connect(self.src)) as db, db:
            db.execute('PRAGMA journal_mode=WAL')
            db.executemany('INSERT INTO p2p_observations VALUES(?)', [('x'*2048,)] * 1500)
        ready, stop = threading.Event(), threading.Event()
        errors = []; committed = []
        def writer():
            try:
                with contextlib.closing(sqlite3.connect(self.src)) as db:
                    db.execute('PRAGMA wal_autocheckpoint=0')
                    ready.set()
                    while not stop.is_set():
                        db.execute("INSERT INTO p2p_observations VALUES('concurrent')"); db.commit()
                        committed.append(1); time.sleep(.02)
            except Exception as error:
                errors.append(type(error).__name__); ready.set()
        thread = threading.Thread(target=writer); thread.start()
        try:
            self.assertTrue(ready.wait(2))
            cfg = self.config(); b.worker(cfg)
            self.assertTrue(committed)
            self.assertFalse(errors)
        finally:
            stop.set(); thread.join(3)
            self.assertFalse(thread.is_alive())

    def test_locked_source_is_bounded_and_retains_failure(self):
        cfg = self.config(); cfg['deadline'] = .15
        with contextlib.closing(sqlite3.connect(self.src)) as locker:
            locker.execute('BEGIN EXCLUSIVE')
            started = time.monotonic()
            with self.assertRaises(SystemExit): b.worker(cfg)
            self.assertLess(time.monotonic() - started, 2)
            self.assertTrue((self.workspace / 'failure.json').exists())
            self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())

    def test_expired_cooperative_deadline(self):
        cfg = self.config(); cfg['deadline'] = -1
        with self.assertRaises(SystemExit): b.worker(cfg)
        self.assertTrue((self.workspace / 'backup-v5.sqlite').exists())
        self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())

    def test_supervisor_kills_stuck_worker(self):
        started = time.monotonic()
        with self.assertRaisesRegex(ValueError, 'supervisor timeout'):
            b.supervise({}, .15, target=stalled_worker)
        self.assertLess(time.monotonic() - started, 4)

    def test_interruption_never_publishes_acceptance(self):
        cfg = self.config(); before = self.src.read_bytes()
        with patch.object(b, 'offline_inventory', side_effect=KeyboardInterrupt):
            with self.assertRaises(SystemExit): b.worker(cfg)
        self.assertTrue((self.workspace / 'failure.json').exists())
        self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())
        self.assertEqual(before, self.src.read_bytes())

    def test_collision_aliasing_and_symlinks(self):
        self.workspace.mkdir()
        with self.assertRaisesRegex(ValueError, 'collision'): b.prepare(self.args, None)
        self.workspace.rmdir()
        self.args.workspace = str(self.src)
        with self.assertRaises(ValueError): b.prepare(self.args, None)
        self.args.workspace = str(self.workspace)
        alias = self.root / 'alias.sqlite'; os.link(self.src, alias)
        with self.assertRaisesRegex(ValueError, 'single-link'): b.prepare(self.args, None)
        alias.unlink()
        alias.symlink_to(self.src)
        self.args.source = str(alias)
        with self.assertRaisesRegex(ValueError, 'symlink'): b.prepare(self.args, None)
        self.args.source = str(self.src)
        link = self.root / 'link'; link.symlink_to(self.root, target_is_directory=True)
        self.args.workspace = str(link / self.workspace.name)
        with self.assertRaisesRegex(ValueError, 'symlink'): b.prepare(self.args, None)

    def test_corrupt_wrong_version_missing_schema_and_fk_rejected(self):
        cfg = self.config()
        self.src.write_bytes(b'not sqlite')
        with self.assertRaises(SystemExit): b.worker(cfg)
        self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())

    def test_wrong_version_rejected(self):
        with contextlib.closing(sqlite3.connect(self.src)) as db, db: db.execute('INSERT INTO schema_migrations VALUES(6)')
        cfg = self.config()
        with self.assertRaises(SystemExit): b.worker(cfg)

    def test_missing_schema_rejected(self):
        with contextlib.closing(sqlite3.connect(self.src)) as db, db: db.execute('DROP TABLE nodes')
        cfg = self.config()
        with self.assertRaises(SystemExit): b.worker(cfg)

    def test_fk_violation_rejected(self):
        with contextlib.closing(sqlite3.connect(self.src)) as db, db:
            db.execute('CREATE TABLE parent(id INTEGER PRIMARY KEY)')
            db.execute('CREATE TABLE child(id INTEGER REFERENCES parent(id))')
            db.execute('INSERT INTO child VALUES(99)')
        cfg = self.config()
        with self.assertRaises(SystemExit): b.worker(cfg)

    def test_low_space_and_wal_growth_fail_closed(self):
        with patch.object(b.shutil, 'disk_usage', return_value=argparse.Namespace(free=1)):
            with self.assertRaisesRegex(ValueError, 'disk space'): self.prepare()
        cfg = self.config()
        with patch.object(b.shutil, 'disk_usage', return_value=argparse.Namespace(free=1)):
            with self.assertRaises(SystemExit): b.worker(cfg)
        with patch.object(b, 'source_size', return_value=10**15):
            with self.assertRaisesRegex(ValueError, 'disk space'):
                b.check_space(self.src, self.workspace, 1024**2)

    def test_enospc_retains_unverified_artifacts(self):
        cfg = self.config()
        with patch.object(b, 'offline_inventory', side_effect=OSError(28, 'fixture ENOSPC')):
            with self.assertRaises(SystemExit): b.worker(cfg)
        self.assertTrue((self.workspace / 'backup-v5.sqlite').exists())
        self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())

    def test_source_replacement_rejected(self):
        cfg = self.config()
        other = self.root / 'replacement'; other.write_bytes(b'fixture')
        other.replace(self.src)
        with self.assertRaises(SystemExit): b.worker(cfg)

    def test_fixture_gate_rejects_production_before_metadata_or_sqlite(self):
        self.args.source = '/unopened/torchnode.db'
        with patch.object(Path, 'lstat', side_effect=AssertionError('must not stat')), patch.object(sqlite3, 'connect', side_effect=AssertionError('must not open')):
            with self.assertRaisesRegex(ValueError, 'production names'): b.authorize(self.args)

    def test_real_gate_cannot_be_enabled_by_fixture_or_environment(self):
        self.args.mode = 'real'; self.args.fixture_root = None
        with patch.object(Path, 'lstat', side_effect=AssertionError('must not stat')):
            with self.assertRaisesRegex(ValueError, 'real-source gate'): b.authorize(self.args)
        self.args.health_url = 'http://127.0.0.1:1/healthz'
        self.args.real_source = True; self.args.approval_file = str(self.root / 'absent-approval.json')
        with patch.object(sys.stdin, 'isatty', return_value=False):
            with self.assertRaisesRegex(ValueError, 'interactive'): b.authorize(self.args)

    def test_source_connection_is_readonly_and_query_only(self):
        src, root, sid = self.prepare()
        connect = b.sqlite3.connect; observed = []; statements = []
        def traced(path, *args, **kwargs):
            db = connect(path, *args, **kwargs)
            if str(path).startswith(self.src.as_uri()):
                observed.append((path, kwargs))
                db.set_trace_callback(statements.append)
            return db
        with patch.object(b.sqlite3, 'connect', side_effect=traced):
            b.snapshot(src, root, sid, 5, 1024**2)
        self.assertEqual(observed[0][0], self.src.as_uri() + '?mode=ro')
        self.assertTrue(observed[0][1]['uri'])
        self.assertEqual(statements, ['PRAGMA query_only=ON'])

    def test_positive_deadline_expiration_through_public_entrypoint(self):
        self.args.deadline_seconds = .000001
        with self.assertRaisesRegex(ValueError, 'worker failed'):
            b.run(self.args)
        self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())
        self.assertTrue((self.workspace / 'attempt.json').exists())

    def test_runtime_policy(self):
        with patch.object(b.sqlite3, 'sqlite_version_info', (3, 51, 2)):
            with self.assertRaisesRegex(ValueError, '3.51.3'): b.runtime()

    def test_real_approval_binding_expiry_and_single_use_without_backup(self):
        # Exercise only authorization with synthetic paths; never invoke real-mode run.
        self.args.mode = 'real'; self.args.fixture_root = None; self.args.real_source = True
        self.args.health_url = 'http://127.0.0.1:1/healthz'
        approval = self.root / 'approval.json'; self.args.approval_file = str(approval)
        value = {'source': self.args.source, 'workspace': self.args.workspace,
                 'approvalReference': self.args.approval_reference, 'deadlineSeconds': self.args.deadline_seconds,
                 'supervisorSeconds': self.args.supervisor_seconds, 'verifySeconds': self.args.verify_seconds,
                 'minimumFreeBytes': self.args.minimum_free_bytes, 'toolHashes': b.tool_hashes(),
                 'runtime': b.runtime(), 'monitoring': b.monitoring_options(self.args), 'nonce': uuid.uuid4().hex, 'expiresAt': '2099-01-01T00:00:00+00:00',
                 'sourceIdentity': b.identity(self.src)}
        b.write_json(approval, value)
        with patch.object(sys.stdin, 'isatty', return_value=True), patch('builtins.input', return_value=value['nonce']):
            original = self.args.minimum_free_bytes; self.args.minimum_free_bytes += 1
            with self.assertRaisesRegex(ValueError, 'exact operation'): b.authorize(self.args)
            self.args.minimum_free_bytes = original
            self.args.max_wal_bytes += 1
            with self.assertRaisesRegex(ValueError, 'exact operation'): b.authorize(self.args)
            self.args.max_wal_bytes -= 1
            for field in ('runtime', 'toolHashes'):
                changed = dict(value, **{field: dict(value[field], changed='fixture')})
                approval.write_text(json.dumps(changed))
                with self.assertRaisesRegex(ValueError, 'exact operation'): b.authorize(self.args)
            approval.write_text(json.dumps(value))
            def expire_during_prompt(_):
                value['expiresAt'] = '2000-01-01T00:00:00+00:00'
                approval.write_text(json.dumps(value))
                return value['nonce']
            with patch('builtins.input', side_effect=expire_during_prompt):
                with self.assertRaisesRegex(ValueError, 'changed during confirmation'): b.authorize(self.args)
            value['expiresAt'] = '2099-01-01T00:00:00+00:00'
            approval.write_text(json.dumps(value))
            clock_type = b.dt.datetime
            with patch.object(b.dt, 'datetime', wraps=clock_type) as clock:
                clock.now.side_effect = [clock_type.now(b.dt.timezone.utc),
                                        clock_type(2100, 1, 1, tzinfo=b.dt.timezone.utc)]
                with self.assertRaisesRegex(ValueError, 'expired during confirmation'): b.authorize(self.args)
            hashes = b.tool_hashes()
            with patch.object(b, 'tool_hashes', side_effect=[hashes, dict(hashes, wrapper='changed')]):
                with self.assertRaisesRegex(ValueError, 'tools changed during confirmation'): b.authorize(self.args)
            expired = dict(value, expiresAt='2000-01-01T00:00:00+00:00')
            approval.write_text(json.dumps(expired))
            with self.assertRaisesRegex(ValueError, 'expired'): b.authorize(self.args)
            approval.write_text(json.dumps(value))
            self.assertEqual(b.authorize(self.args), value)
            with self.assertRaises(FileExistsError): b.authorize(self.args)
        self.assertFalse(self.workspace.exists())

    def test_sigterm_reaps_worker_and_leaves_no_acceptance(self):
        import signal
        with contextlib.closing(sqlite3.connect(self.src)) as locker:
            locker.execute('BEGIN EXCLUSIVE')
            cmd = [sys.executable, str(SCRIPT), 'fixture', '--source', str(self.src),
                   '--workspace', str(self.workspace), '--fixture-root', str(self.root),
                   '--approval-reference', 'signal-fixture', '--minimum-free-bytes', str(1024**2)]
            process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                end = time.monotonic() + 5
                while not (self.workspace / 'backup-v5.sqlite').exists() and time.monotonic() < end:
                    time.sleep(.01)
                self.assertTrue((self.workspace / 'backup-v5.sqlite').exists())
                process.send_signal(signal.SIGTERM)
                out, err = process.communicate(timeout=5)
                self.assertNotEqual(process.returncode, 0)
                self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())
                self.assertTrue((self.workspace / 'backup-v5.sqlite').exists())
                report = json.loads((self.workspace / 'monitor.json').read_text())
                self.assertEqual(report['reason'], 'KeyboardInterrupt')
                self.assertTrue(report['workerReaped'])
            finally:
                if process.poll() is None: process.kill()
                process.communicate()

    def monitored_config(self, url):
        cfg = self.config()
        self.args.health_url = url
        cfg['monitoring'] = b.monitoring_options(self.args)
        return cfg

    def test_http_abort_kills_only_owned_worker_and_preserves_artifact(self):
        sentinel = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        try:
            with fixture_http((200, 503, 503)) as url:
                cfg = self.monitored_config(url)
                with self.assertRaisesRegex(ValueError, 'consecutive HTTP'):
                    b.supervise(cfg, 5, target=retained_worker)
            self.assertIsNone(sentinel.poll())
            report = json.loads((self.workspace / 'monitor.json').read_text())
            self.assertTrue(report['workerReaped'])
            self.assertNotEqual(report['workerPid'], sentinel.pid)
            self.assertNotEqual(report['workerExitcode'], 0)
            self.assertEqual(report['httpFailures'], 2)
            self.assertEqual((self.workspace / 'partial-fixture.sqlite').read_bytes(), b'partial synthetic artifact')
            self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())
        finally:
            sentinel.terminate(); sentinel.wait(timeout=3)

    def test_wal_ceiling_aborts_owned_worker(self):
        cfg = self.monitored_config(None); cfg['monitoring']['maxWalBytes'] = 50
        with self.assertRaisesRegex(ValueError, 'WAL ceiling'):
            b.supervise(cfg, 5, target=wal_growth_worker)
        report = json.loads((self.workspace / 'monitor.json').read_text())
        self.assertEqual(report['walPeakBytes'], 100)
        self.assertTrue(report['workerReaped'])
        self.assertEqual(Path(str(self.src)+'-wal').stat().st_size, 100)
        self.assertTrue((self.workspace / 'partial-fixture.sqlite').exists())
        self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())

    def test_monitor_disk_breach_aborts_inflight_and_retains_artifact(self):
        cfg = self.monitored_config(None)
        check = b.check_space
        def falling_reserve(*args, **kwargs):
            if (self.workspace / 'partial-fixture.sqlite').exists():
                with patch.object(b.shutil, 'disk_usage', return_value=argparse.Namespace(free=1)):
                    return check(*args, **kwargs)
            return check(*args, **kwargs)
        with patch.object(b, 'check_space', side_effect=falling_reserve):
            with self.assertRaisesRegex(ValueError, 'disk space reserve'):
                b.supervise(cfg, 5, target=retained_worker)
        report = json.loads((self.workspace / 'monitor.json').read_text())
        self.assertTrue(report['workerReaped'])
        self.assertIn('disk space reserve', report['reason'])
        self.assertTrue((self.workspace / 'partial-fixture.sqlite').exists())
        self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())

    def test_stale_reaped_handle_cannot_signal_reused_pid(self):
        child = b.multiprocessing.get_context('spawn').Process(target=time.sleep, args=(0,))
        child.start(); child.join(3)
        self.assertEqual(child.exitcode, 0)
        sentinel = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        try:
            # Model PID reuse after reaping. Cached exit status must prevent ANY signal.
            child._popen.pid = sentinel.pid
            with patch.object(b.os, 'kill', side_effect=AssertionError('stale handle signalled')):
                self.assertEqual(b.stop_child(child), 0)
                with self.assertRaisesRegex(ValueError, 'owned child'):
                    b.stop_child(sentinel.pid)
            self.assertIsNone(sentinel.poll())
        finally:
            sentinel.terminate(); sentinel.wait(timeout=3)

    def test_verification_budget_and_worker_binding_fail_closed(self):
        cfg = self.config()
        with patch.object(b, 'offline_inventory', side_effect=lambda *args: time.sleep(.05)):
            cfg['verifySeconds'] = .001
            with self.assertRaises(SystemExit): b.worker(cfg)
        report = json.loads((self.workspace / 'failure.json').read_text())
        self.assertIn('deadline', report['reason'])
        self.assertTrue((self.workspace / 'backup-v5.sqlite').exists())
        self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())
        with self.assertRaisesRegex(ValueError, 'verification deadline'):
            b.offline_inventory(self.src, time.monotonic()-1)

    def test_changed_worker_runtime_rejected_before_source_open(self):
        cfg = self.config(); cfg['runtime'] = dict(cfg['runtime'], pythonVersion='changed')
        with patch.object(b, 'snapshot', side_effect=AssertionError('must not open source')):
            with self.assertRaises(SystemExit): b.worker(cfg)
        self.assertIn('binding changed', json.loads((self.workspace / 'failure.json').read_text())['reason'])

    def test_changed_worker_tool_hash_rejected_before_source_open(self):
        cfg = self.config(); cfg['toolHashes'] = dict(cfg['toolHashes'], wrapper='changed')
        with patch.object(b, 'snapshot', side_effect=AssertionError('must not open source')):
            with self.assertRaises(SystemExit): b.worker(cfg)
        self.assertIn('binding changed', json.loads((self.workspace / 'failure.json').read_text())['reason'])

    def test_failed_final_health_sample_never_accepts_completed_worker(self):
        cfg = self.monitored_config('http://127.0.0.1:1/healthz')
        # Substitute an already completed owned child to locate the final check
        # precisely, independent of scheduling. No actual HTTP request is made.
        ctx = b.multiprocessing.get_context('spawn')
        process = ctx.Process(target=time.sleep, args=(0,))
        process.start(); process.join(3)
        with patch.object(ctx.__class__, 'Process', return_value=process), \
             patch.object(process, 'start'), patch.object(b, 'healthy', side_effect=[True, True, False]):
            with self.assertRaisesRegex(ValueError, 'final failed'): b.supervise(cfg, 5)
        self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())

    def test_operator_sigint_retains_artifacts_and_audits_abort(self):
        import signal
        with contextlib.closing(sqlite3.connect(self.src)) as locker, fixture_http() as url:
            locker.execute('BEGIN EXCLUSIVE')
            cmd = [sys.executable, str(SCRIPT), 'fixture', '--source', str(self.src),
                   '--workspace', str(self.workspace), '--fixture-root', str(self.root),
                   '--approval-reference', 'cancel-fixture', '--minimum-free-bytes', str(1024**2),
                   '--health-url', url]
            process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                end = time.monotonic() + 5
                while not (self.workspace / 'backup-v5.sqlite').exists() and time.monotonic() < end:
                    time.sleep(.01)
                self.assertTrue((self.workspace / 'backup-v5.sqlite').exists())
                process.send_signal(signal.SIGINT)
                process.communicate(timeout=6)
                self.assertNotEqual(process.returncode, 0)
                report = json.loads((self.workspace / 'monitor.json').read_text())
                self.assertTrue(report['workerReaped'])
                self.assertEqual(report['reason'], 'KeyboardInterrupt')
                self.assertTrue((self.workspace / 'backup-v5.sqlite').exists())
                self.assertTrue((self.workspace / 'attempt.json').exists())
                self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())
            finally:
                if process.poll() is None: process.kill()
                process.communicate()

    def test_bad_baseline_never_starts_backup_child(self):
        with fixture_http((503,)) as url:
            cfg = self.monitored_config(url)
            with self.assertRaisesRegex(ValueError, 'baseline failed'):
                b.supervise(cfg, 5, target=retained_worker)
        report = json.loads((self.workspace / 'monitor.json').read_text())
        self.assertIsNone(report['workerPid'])
        self.assertFalse((self.workspace / 'partial-fixture.sqlite').exists())

    def test_slow_http_probe_cannot_hide_supervisor_deadline(self):
        with fixture_http(delay=1) as url:
            cfg = self.monitored_config(url)
            start = time.monotonic()
            with self.assertRaises(ValueError): b.supervise(cfg, .15, target=stalled_worker)
            self.assertLess(time.monotonic()-start, 2)
        self.assertTrue(json.loads((self.workspace / 'monitor.json').read_text())['workerReaped'])

    def test_monitor_failure_leaves_claim_consumed_and_no_acceptance(self):
        # A synthetic one-use authority object exercises the real authorization routine
        # only. Execution remains fixture mode, with temporary paths and fixture HTTP.
        from copy import copy
        with fixture_http((200, 503, 503)) as url:
            self.args.health_url = url
            real = copy(self.args); real.mode = 'real'; real.real_source = True; real.fixture_root = None
            real.approval_file = str(self.root / 'synthetic-approval.json')
            value = {'source': real.source, 'workspace': real.workspace, 'approvalReference': real.approval_reference,
                     'deadlineSeconds': real.deadline_seconds, 'verifySeconds': real.verify_seconds,
                     'supervisorSeconds': real.supervisor_seconds, 'minimumFreeBytes': real.minimum_free_bytes,
                     'sourceIdentity': b.identity(self.src), 'runtime': b.runtime(), 'toolHashes': b.tool_hashes(),
                     'monitoring': b.monitoring_options(real), 'nonce': uuid.uuid4().hex,
                     'expiresAt': '2099-01-01T00:00:00+00:00'}
            b.write_json(Path(real.approval_file), value)
            authorize = b.authorize
            with patch.object(sys.stdin, 'isatty', return_value=True), patch('builtins.input', return_value=value['nonce']):
                supervisor = b.supervise
                with patch.object(b, 'authorize', side_effect=lambda args: authorize(real)), patch.object(
                        b, 'supervise', side_effect=lambda cfg, seconds: supervisor(cfg, seconds, target=retained_worker)):
                    with self.assertRaisesRegex(ValueError, 'consecutive HTTP'): b.run(self.args)
                self.assertTrue(Path(real.approval_file + '.used').exists())
                with self.assertRaises(FileExistsError): authorize(real)
            self.assertTrue((self.workspace / 'attempt.json').exists())
            self.assertTrue((self.workspace / 'partial-fixture.sqlite').exists())
            self.assertTrue((self.workspace / 'monitor.json').exists())
            self.assertFalse((self.workspace / 'B6B-OFFLINE.json').exists())

    def test_monitored_success_has_acceptance_and_monitor_evidence(self):
        with fixture_http() as url:
            self.args.health_url = url
            with contextlib.redirect_stdout(io.StringIO()): b.run(self.args)
        report = json.loads((self.workspace / 'monitor.json').read_text())
        self.assertEqual(report['status'], 'passed')
        self.assertTrue(report['workerReaped'])
        self.assertGreaterEqual(report['httpChecks'], 3)
        self.assertTrue((self.workspace / 'B6B-OFFLINE.json').exists())

    def test_monitor_approval_binding_and_fixture_port_isolation(self):
        self.args.health_url = 'http://127.0.0.1:8080/healthz'
        with patch.object(b, 'healthy', side_effect=AssertionError('must not contact production')):
            with self.assertRaisesRegex(ValueError, 'production port'): b.run(self.args)
        for url in ('http://localhost:1234/healthz', 'https://127.0.0.1:1234/healthz',
                    'http://127.0.0.1:1234/login', 'http://user:secret@127.0.0.1:1234/healthz'):
            self.args.health_url = url
            with self.assertRaisesRegex(ValueError, 'literal loopback'): b.monitoring_options(self.args)

    def test_real_requires_health_monitor_before_source_access(self):
        self.args.mode='real'; self.args.real_source=True; self.args.fixture_root=None
        self.args.approval_file=str(self.root/'absent.json')
        with patch.object(Path, 'lstat', side_effect=AssertionError('must not stat')):
            with self.assertRaisesRegex(ValueError, 'requires HTTP'): b.authorize(self.args)

    def test_cli_fixture_only(self):
        cmd = [sys.executable, str(SCRIPT), 'fixture', '--source', str(self.src), '--workspace', str(self.workspace),
               '--fixture-root', str(self.root), '--approval-reference', 'cli-fixture', '--minimum-free-bytes', str(1024**2)]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=25)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.workspace / 'B6B-OFFLINE.json').exists())


if __name__ == '__main__':
    unittest.main()
