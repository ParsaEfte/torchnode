"""Small isolated safety checks supplementing Java's actual-schema integration tests."""
import importlib.util
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('offline', Path(__file__).resolve().parents[1] / 'scripts/topology-migration.py')
offline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(offline)


class Safety(unittest.TestCase):
    def test_sql_progress_guard_respects_shorter_verification_budget(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory).resolve() / 'fixture.sqlite'
            db = offline.sqlite3.connect(source); db.close()
            with patch.object(offline, 'end_time', time.monotonic()-1, create=True):
                with offline.readonly(source) as db:
                    with self.assertRaises(offline.sqlite3.OperationalError):
                        db.execute('WITH RECURSIVE n(x) AS (VALUES(0) UNION ALL SELECT x+1 FROM n WHERE x<100000) SELECT sum(x) FROM n').fetchall()

    def test_production_names_rejected_before_any_filesystem_or_sqlite_access(self):
        with patch.object(Path, 'is_symlink', side_effect=AssertionError('must not stat')), patch.object(offline.sqlite3, 'connect', side_effect=AssertionError('must not connect')):
            for path in ['/unopened/torchnode.db', '/unopened/torchnode.db-wal', '/unopened/torchnode.db-shm']:
                with self.assertRaises(ValueError):
                    offline.safe_path(path)

    def test_relative_and_parent_traversal_rejected_before_stat(self):
        with patch.object(Path, 'is_symlink', side_effect=AssertionError('must not stat')):
            for path in ['backup-v5.sqlite', '/unopened/../backup-v5.sqlite']:
                with self.assertRaises(ValueError):
                    offline.safe_path(path)

    def test_low_disk_space_is_not_ignored(self):
        with patch.object(offline.shutil, 'disk_usage', return_value=type('Usage', (), {'free': 1})()):
            with self.assertRaisesRegex(ValueError, 'disk space'):
                offline.check_space(Path('/unused'), 100)

    def test_unmarked_workspace_never_opens_sqlite(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(offline.sqlite3, 'connect', side_effect=AssertionError('must not connect')):
            with self.assertRaises(ValueError):
                offline.workspace(Path(directory).resolve())

    def test_online_snapshot_refuses_non_fixture_authority(self):
        with patch.object(offline.sqlite3, 'connect', side_effect=AssertionError('must not connect')):
            with self.assertRaises(ValueError):
                offline.snapshot_fixture(Path('/unused'), {'kind': 'approved-offline'}, 'copy.sqlite')

    def test_backup_collision_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'existing.sqlite'
            path.write_bytes(b'preserve checkpoint')
            with self.assertRaises(FileExistsError):
                offline.exclusive(path)
            self.assertEqual(path.read_bytes(), b'preserve checkpoint')


if __name__ == '__main__':
    unittest.main()
