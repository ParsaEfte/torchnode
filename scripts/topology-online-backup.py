#!/usr/bin/env python3
"""B6-B online snapshot; no implicit source and no application/migration startup."""
import argparse
import _sqlite3
import contextlib
import datetime as dt
import hashlib
import http.client
import importlib.util
import json
import multiprocessing
import os
from pathlib import Path
import re
import shutil
import signal
import sqlite3
import stat
import sys
import tempfile
import time
import uuid
from urllib.parse import urlsplit

VERSION = '3'
FIXTURE_NAME = 'fixture-v5.sqlite'


def require(ok, message):
    if not ok:
        raise ValueError(message)


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def canonical(value):
    p = Path(value)
    require(p.is_absolute() and '..' not in p.parts, 'canonical absolute path required')
    for part in (p, *p.parents):
        require(not part.is_symlink(), 'symlink path refused')
    require(str(p) == str(p.resolve()), 'canonical path spelling required')
    return p


def identity(p):
    s = p.lstat()
    require(stat.S_ISREG(s.st_mode) and s.st_nlink == 1, 'single-link regular file required')
    require(s.st_uid == os.geteuid(), 'source must be owned by invoking user')
    return {'device': s.st_dev, 'inode': s.st_ino, 'owner': s.st_uid}


def private_file(p):
    identity(p)
    require(p.stat().st_mode & 0o077 == 0, 'private file required')
    require(p.stat().st_size <= 16384, 'metadata file too large')
    return json.loads(p.read_text())


def digest(p, end=float('inf')):
    h = hashlib.sha256()
    with p.open('rb') as f:
        for block in iter(lambda: f.read(1024 * 1024), b''):
            require(time.monotonic() < end, 'verification hash deadline exceeded')
            h.update(block)
    require(time.monotonic() < end, 'verification hash deadline exceeded')
    return h.hexdigest()


def write_json(p, value):
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'w') as f:
        json.dump(value, f, indent=2)
        f.write('\n')
        f.flush()
        os.fsync(f.fileno())


def runtime():
    require(sqlite3.sqlite_version_info >= (3, 51, 3), 'SQLite >= 3.51.3 required; no unreviewed backport exceptions')
    return {'pythonExecutable': sys.executable, 'pythonVersion': sys.version.split()[0],
            'sqliteVersion': sqlite3.sqlite_version,
            'pythonSha256': digest(Path(sys.executable).resolve()),
            'sqliteExtensionSha256': digest(Path(_sqlite3.__file__).resolve())}


def tool_hashes():
    return {'wrapper': digest(Path(__file__).resolve()),
            'offlineVerifier': digest(Path(__file__).with_name('topology-migration.py'))}


def authorize(args):
    # Fixture rejection precedes all metadata access on the supplied source.
    if args.mode == 'fixture':
        require(args.fixture_root and not args.real_source and not args.approval_file,
                'fixture mode requires fixture root and forbids real authority')
        raw = Path(args.source)
        require(raw.name == FIXTURE_NAME and not any(x.startswith('torchnode.db') for x in raw.parts),
                'fixture source name required; production names forbidden')
        root = canonical(args.fixture_root)
        require(root.is_relative_to(Path(tempfile.gettempdir()).resolve()) and root != Path(tempfile.gettempdir()).resolve(),
                'fixture root must be inside system temporary directory')
        require(root.stat().st_uid == os.geteuid() and root.stat().st_mode & 0o077 == 0,
                'private owned fixture root required')
        require(raw == root / FIXTURE_NAME, 'source outside fixture root')
        require(Path(args.workspace).is_relative_to(root), 'fixture output outside fixture root')
        marker = private_file(root / 'B6B-FIXTURE.json')
        require(marker == {'kind': 'synthetic-fixture', 'source': FIXTURE_NAME}, 'fixture declaration required')
        return None
    require(args.real_source and args.approval_file and not args.fixture_root,
            'real source requires distinct real-source gate and approval file')
    require(args.health_url is not None, 'real source requires HTTP health monitoring')
    require(sys.stdin.isatty(), 'real source requires interactive approval; CI/environment cannot enable it')
    approval_path = canonical(args.approval_file)
    approval = private_file(approval_path)
    expected = {'source': args.source, 'workspace': args.workspace,
                'approvalReference': args.approval_reference, 'deadlineSeconds': args.deadline_seconds,
                'supervisorSeconds': args.supervisor_seconds, 'minimumFreeBytes': args.minimum_free_bytes,
                'verifySeconds': args.verify_seconds, 'runtime': runtime(), 'toolHashes': tool_hashes(),
                'monitoring': monitoring_options(args)}
    require(all(approval.get(k) == v for k, v in expected.items()), 'approval does not match exact operation')
    require(re.fullmatch(r'[0-9a-f]{32}', approval.get('nonce', '')) is not None, 'unique approval nonce required')
    expires = dt.datetime.fromisoformat(approval['expiresAt'])
    require(expires.tzinfo is not None and dt.datetime.now(dt.timezone.utc) < expires,
            'approval expired or timezone missing')
    require(input('Enter approval nonce for ONE live-source backup: ') == approval['nonce'],
            'interactive approval mismatch')
    # A prompt may remain open past expiry or while reviewed files/runtime change.
    require(dt.datetime.now(dt.timezone.utc) < expires, 'approval expired during confirmation')
    require(private_file(approval_path) == approval, 'approval changed during confirmation')
    require(runtime() == expected['runtime'] and tool_hashes() == expected['toolHashes'],
            'runtime or tools changed during confirmation')
    # No reusable token, no retry using this approval file, including after failed attempts.
    write_json(approval_path.with_name(approval_path.name + '.used'),
               {'nonce': approval['nonce'], 'claimedAt': utc()})
    return approval


def source_size(src):
    total = src.stat().st_size
    for suffix in ('-wal', '-shm', '-journal'):
        p = canonical(str(src) + suffix)
        if p.exists():
            identity(p)
            if suffix == '-wal':
                total += p.stat().st_size
    return total


def check_space(src, workspace, reserve, initial=False, highwater=0):
    estimate = max(source_size(src), highwater)
    required = max(1024**3, 8 * estimate, reserve) if initial else reserve + estimate
    for parent in {src.parent, workspace.parent}:
        require(shutil.disk_usage(parent).free >= required, 'disk space reserve breached')
    return estimate


def prepare(args, approval):
    src, workspace = canonical(args.source), canonical(args.workspace)
    require(not workspace.exists(), 'destination workspace collision')
    require(src != workspace and not src.is_relative_to(workspace), 'source/destination overlap')
    require(not workspace.is_relative_to(src), 'source/destination overlap')
    require(re.fullmatch(r'b6b-real-v5-\d{8}T\d{6}Z-[0-9a-f]{32}', workspace.name) is not None,
            'unique UTC/UUID workspace name required')
    parent = workspace.parent
    require(parent.is_dir() and parent.stat().st_uid == os.geteuid(), 'owned destination parent required')
    require(parent.stat().st_mode & 0o022 == 0, 'destination parent writable by others')
    sid = identity(src)
    if approval:
        require(approval.get('sourceIdentity') == sid, 'approved source identity changed')
    require(not Path(str(src) + '-wal').exists() or Path(str(src) + '-shm').exists(),
            'WAL without existing SHM refused; no sidecar creation fallback')
    check_space(src, workspace, args.minimum_free_bytes, initial=True)
    workspace.mkdir(mode=0o700)  # exclusive, never reuse; source and sidecars are never removed
    return src, workspace, sid


def offline_inventory(dest, end):
    spec = importlib.util.spec_from_file_location('b6b_offline', Path(__file__).with_name('topology-migration.py'))
    verifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verifier)
    verifier.end_time = end
    require(time.monotonic() < end, 'verification deadline exceeded')
    require(not any(Path(str(dest) + s).exists() for s in ('-wal', '-shm', '-journal')),
            'destination is not standalone')
    result = verifier.inventory(dest, 5)
    require(time.monotonic() < end, 'verification deadline exceeded')
    return result


def snapshot(src, workspace, sid, deadline, reserve):
    """Called only in supervised worker; all source SQL is connection-local/read-only."""
    end = time.monotonic() + deadline
    highwater = check_space(src, workspace, reserve, initial=True)
    require(identity(canonical(src)) == sid, 'source identity changed before open')
    dest = workspace / 'backup-v5.sqlite'
    os.close(os.open(dest, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600))
    def progress(status, remaining, total):
        nonlocal highwater
        require(time.monotonic() < end, 'backup deadline exceeded')
        highwater = check_space(src, workspace, reserve, highwater=highwater)
        require(identity(canonical(src)) == sid, 'source identity changed during backup')
    with contextlib.closing(sqlite3.connect(src.as_uri() + '?mode=ro', uri=True, timeout=.25)) as source:
        source.execute('PRAGMA query_only=ON')
        with contextlib.closing(sqlite3.connect(dest, timeout=.25)) as target:
            progress(0, 0, 0)
            source.backup(target, pages=32, progress=progress, sleep=.05)
    # Source is closed before ANY verification or destination journal changes.
    with contextlib.closing(sqlite3.connect(dest, timeout=.25)) as target:
        require(target.execute('PRAGMA journal_mode=DELETE').fetchone() == ('delete',),
                'destination journal normalization failed')
    require(identity(canonical(src)) == sid, 'source identity changed after backup')
    return dest


def worker(config):
    os.umask(0o077)
    src, workspace = Path(config['source']), Path(config['workspace'])
    try:
        versions = runtime()
        require(versions == config['runtime'] and tool_hashes() == config['toolHashes'],
                'worker runtime or tool binding changed')
        start = utc()
        dest = snapshot(src, workspace, config['sourceIdentity'], config['deadline'], config['reserve'])
        verify_end = time.monotonic() + config['verifySeconds']
        inventory = offline_inventory(dest, verify_end)
        sha = digest(dest, verify_end)
        os.chmod(dest, 0o400)
        write_json(workspace / 'inventory.json', inventory)
        write_json(workspace / 'backup-provenance.json', {
            'status': 'verified', 'toolVersion': VERSION, 'toolHashes': tool_hashes(), **versions,
            'source': str(src), 'sourceIdentity': config['sourceIdentity'], 'startedAt': start,
            'finishedAt': utc(), 'sha256': sha, 'sizeBytes': dest.stat().st_size,
            'schemaVersion': 5, 'integrity': 'ok', 'foreignKeys': 'ok',
            'approvalReference': config['approvalReference'], 'mode': config['mode'],
            'deadlineSeconds': config['deadline'], 'supervisorSeconds': config['supervisorSeconds'],
            'minimumFreeBytes': config['reserve'], 'monitoring': config.get('monitoring'),
            'realBackupVerification': 'PENDING'})
        require(time.monotonic() < verify_end, 'verification publication deadline exceeded')
    except BaseException as error:
        # No SQL text, rows, environment values or raw exception strings in logs.
        write_json(workspace / 'failure.json', {'status': 'unverified', 'errorType': type(error).__name__,
                   'sqliteCode': getattr(error, 'sqlite_errorname', None),
                   'reason': str(error) if isinstance(error, ValueError) else None, 'at': utc()})
        raise SystemExit(1)


def monitoring_options(args):
    url = args.health_url
    if url is not None:
        parsed = urlsplit(url)
        require(parsed.scheme == 'http' and parsed.hostname == '127.0.0.1'
                and parsed.port is not None and parsed.path == '/healthz'
                and not parsed.query and not parsed.fragment and not parsed.username
                and not parsed.password, 'health URL must be literal loopback HTTP /healthz')
        require(args.mode != 'fixture' or parsed.port != 8080,
                'fixture monitoring must not contact production port 8080')
    require(0 < args.monitor_interval_seconds <= 5 and 0 < args.health_timeout_seconds <= 5,
            'monitor interval and health timeout must be positive and at most five seconds')
    require(args.max_wal_bytes > 0, 'positive WAL ceiling required')
    return {'healthUrl': url, 'intervalSeconds': args.monitor_interval_seconds,
            'healthTimeoutSeconds': args.health_timeout_seconds, 'healthFailures': 2,
            'maxWalBytes': args.max_wal_bytes}


def stop_child(process):
    # Only an owned Process object created by this supervisor is accepted. Never signal
    # a caller-supplied PID, port owner, process name or process group.
    require(isinstance(process, multiprocessing.process.BaseProcess)
            and process._parent_pid == os.getpid(), 'owned child process handle required')
    # CPython poll caches exit status when reaping. If the child exits after this
    # check it stays unreaped (its PID cannot be reused) until this parent waits.
    # No external waitpid/SIGCHLD reaper or concurrent lifecycle owner is supported.
    if process.is_alive():
        process.terminate()
        process.join(1)
    if process.is_alive():
        process.kill()
        process.join(1)
    require(not process.is_alive(), 'child termination pending; do not reuse workspace')
    exitcode = process.exitcode
    process.close()
    return exitcode


def health_probe(url):
    parsed = urlsplit(url)
    try:
        connection = http.client.HTTPConnection('127.0.0.1', parsed.port, timeout=2)
        try:
            connection.request('GET', '/healthz', headers={'Connection': 'close'})
            response = connection.getresponse()
            ok = response.status == 200 and response.read(3) == b'ok'
        finally:
            connection.close()
    except Exception:
        ok = False
    raise SystemExit(0 if ok else 1)


def healthy(url, timeout):
    # Probe in a separate owned child: even slow-drip headers/body cannot hold up the
    # supervisor beyond its join budget. No redirects, proxies, DNS or credentials.
    probe = multiprocessing.get_context('spawn').Process(target=health_probe, args=(url,))
    try:
        probe.start()
        probe.join(timeout)
        return not probe.is_alive() and probe.exitcode == 0
    finally:
        stop_child(probe)


def monitor_sample(config, state):
    src = canonical(config['source'])
    require(identity(src) == config['sourceIdentity'], 'monitor source identity changed')
    wal = canonical(str(src) + '-wal')
    size = 0
    if wal.exists():
        identity(wal)
        size = wal.stat().st_size
    state['walPeakBytes'] = max(state['walPeakBytes'], size)
    require(size <= config['monitoring']['maxWalBytes'], 'monitor WAL ceiling exceeded')
    state['sizeHighWater'] = check_space(src, Path(config['workspace']), config['reserve'],
                                       highwater=state['sizeHighWater'])
    state['samples'] += 1


def supervise(config, seconds, target=worker):
    process = multiprocessing.get_context('spawn').Process(target=target, args=(config,))
    started = time.monotonic()
    end = started + seconds
    monitor = config.get('monitoring')
    state = {'status': 'unverified', 'samples': 0, 'httpChecks': 0, 'httpFailures': 0,
             'consecutiveHttpFailures': 0, 'walPeakBytes': 0, 'sizeHighWater': 0,
             'workerPid': None, 'workerId': uuid.uuid4().hex, 'startedAt': utc()}
    def sample_http(required=None):
        if monitor['healthUrl'] is None:
            return
        remaining = end - time.monotonic()
        require(remaining > 0, 'supervisor timeout; unverified artifacts retained')
        ok = healthy(monitor['healthUrl'], min(monitor['healthTimeoutSeconds'], remaining))
        state['httpChecks'] += 1
        state['httpFailures'] += int(not ok)
        state['consecutiveHttpFailures'] = 0 if ok else state['consecutiveHttpFailures'] + 1
        require(ok or required is None, f'monitor HTTP {required} failed')
        require(state['consecutiveHttpFailures'] < monitor['healthFailures'],
                'monitor consecutive HTTP health failures')
    try:
        if monitor:
            monitor_sample(config, state)
            sample_http(required='baseline')  # do not open source if baseline is unhealthy
        require(time.monotonic() < end, 'supervisor timeout; unverified artifacts retained')
        process.start()
        state['workerPid'] = process.pid
        while True:
            require(time.monotonic() < end, 'supervisor timeout; unverified artifacts retained')
            if monitor:
                monitor_sample(config, state)
                sample_http()
            remaining = end - time.monotonic()
            require(remaining > 0, 'supervisor timeout; unverified artifacts retained')
            process.join(min(monitor['intervalSeconds'] if monitor else .05, remaining))
            if not process.is_alive():
                break
        require(time.monotonic() < end, 'supervisor timeout; unverified artifacts retained')
        require(process.exitcode == 0, 'backup worker failed; unverified artifacts retained')
        if monitor:
            monitor_sample(config, state)
            sample_http(required='final')  # never accept a failed final health sample
        require(time.monotonic() < end, 'supervisor timeout; unverified artifacts retained')
        state['status'] = 'passed'
    except BaseException as error:
        state['reason'] = str(error) if isinstance(error, ValueError) else type(error).__name__
        raise
    finally:
        state['workerExitcode'] = stop_child(process)
        state['workerReaped'] = True
        state['elapsedSeconds'] = time.monotonic() - started
        state['finishedAt'] = utc()
        if monitor:
            write_json(Path(config['workspace']) / 'monitor.json', state)
    return end


def interrupted(signum, frame):
    raise KeyboardInterrupt()


def run(args):
    os.umask(0o077)
    require(0 < args.deadline_seconds <= 3600 and 0 < args.verify_seconds <= 3600,
            'deadlines must be positive and at most one hour')
    require(args.deadline_seconds < args.supervisor_seconds <= 7200,
            'supervisor must exceed backup deadline and be at most two hours')
    require(args.minimum_free_bytes > 0 and bool(args.approval_reference.strip()), 'reserve and approval reference required')
    runtime()
    monitoring = monitoring_options(args)
    approval = authorize(args)
    src, workspace, sid = prepare(args, approval)
    config = {'source': str(src), 'workspace': str(workspace), 'sourceIdentity': sid,
              'deadline': args.deadline_seconds, 'verifySeconds': args.verify_seconds,
              'supervisorSeconds': args.supervisor_seconds, 'reserve': args.minimum_free_bytes,
              'approvalReference': args.approval_reference, 'mode': args.mode, 'monitoring': monitoring,
              'runtime': runtime(), 'toolHashes': tool_hashes()}
    if approval:
        require(config['runtime'] == approval['runtime'] and config['toolHashes'] == approval['toolHashes'],
                'claimed runtime or tools changed')
    write_json(workspace / 'attempt.json', {**config, 'createdAt': utc(), 'status': 'unverified'})
    previous = signal.signal(signal.SIGTERM, interrupted)
    try:
        end = supervise(config, args.supervisor_seconds)
    finally:
        signal.signal(signal.SIGTERM, previous)
    provenance = private_file(workspace / 'backup-provenance.json')
    require(runtime() == config['runtime'] and tool_hashes() == config['toolHashes'],
            'runtime or tools changed before acceptance')
    require(time.monotonic() < end, 'supervisor deadline before acceptance')
    # Acceptance marker is published LAST, only by supervisor after successful child exit.
    write_json(workspace / 'B6B-OFFLINE.json', {
        'kind': 'fixture' if args.mode == 'fixture' else 'approved-offline', 'offline': True,
        'source': 'backup-v5.sqlite', 'sha256': provenance['sha256'],
        'approvalReference': args.approval_reference})
    print(json.dumps({'status': 'verified', 'workspace': str(workspace),
                      'sha256': provenance['sha256'], 'realBackupVerification': 'PENDING'}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['fixture', 'real'])
    parser.add_argument('--source', required=True)
    parser.add_argument('--workspace', required=True)
    parser.add_argument('--approval-reference', required=True)
    parser.add_argument('--minimum-free-bytes', type=int, required=True)
    parser.add_argument('--deadline-seconds', type=float, default=60)
    parser.add_argument('--verify-seconds', type=float, default=240)
    parser.add_argument('--supervisor-seconds', type=float, default=360)
    parser.add_argument('--health-url')
    parser.add_argument('--max-wal-bytes', type=int, default=134217728)
    parser.add_argument('--monitor-interval-seconds', type=float, default=1)
    parser.add_argument('--health-timeout-seconds', type=float, default=2)
    parser.add_argument('--fixture-root')
    parser.add_argument('--real-source', action='store_true')
    parser.add_argument('--approval-file')
    try:
        run(parser.parse_args())
    except (Exception, KeyboardInterrupt) as error:
        print(json.dumps({'status': 'unverified', 'errorType': type(error).__name__,
                          'reason': str(error) if isinstance(error, ValueError) else None}), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
