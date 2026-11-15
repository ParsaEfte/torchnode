"""Exercise console-only provisioning against a disposable database; never print credentials."""
import os
import pathlib
import pty
import secrets
import select
import sqlite3
import subprocess
import tempfile
import time

jar = pathlib.Path("target/torchnode-1.0.0-jar-with-dependencies.jar").resolve()
java = str(pathlib.Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
with tempfile.TemporaryDirectory(prefix="torchnode-provision-") as directory:
    database = pathlib.Path(directory) / "test.db"
    password = secrets.token_urlsafe(32)
    def provision(reset=False):
        master, slave = pty.openpty()
        environment = dict(os.environ, TORCHNODE_DB=str(database))
        arguments=["--reset-admin-password","console-admin"] if reset else ["--provision-admin"]
        process = subprocess.Popen([java, "-jar", str(jar), *arguments], stdin=slave, stdout=slave, stderr=slave, env=environment)
        os.close(slave)
        try:
            prompts=[(b"Password (12", password), (b"Confirm password: ", password)]
            if not reset: prompts.insert(0,(b"Administrator username/email: ", "console-admin"))
            for marker, answer in prompts:
                output = b""
                deadline = time.monotonic() + 30
                while marker not in output:
                    if time.monotonic() > deadline:
                        raise AssertionError("Timed out waiting for provisioning prompt")
                    if select.select([master], [], [], 1)[0]:
                        output += os.read(master, 4096)
                os.write(master, (answer + "\n").encode())
            return process.wait(timeout=30)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait()
            os.close(master)
    assert provision() == 0
    with sqlite3.connect(database) as connection:
        before = connection.execute("SELECT password_hash FROM auth_users WHERE username='console-admin'").fetchone()[0]
        assert before.startswith("$argon2id$") and password not in before
        versions = connection.execute("SELECT version FROM schema_migrations ORDER BY version").fetchall()
        assert versions == [(n,) for n in range(1, 7)], "Current binary initializes schema v6 before provisioning"
    assert provision() != 0, "Reprovisioning must fail closed"
    with sqlite3.connect(database) as connection:
        assert connection.execute("SELECT password_hash FROM auth_users WHERE username='console-admin'").fetchone()[0] == before
        assert connection.execute("SELECT version FROM schema_migrations ORDER BY version").fetchall() == versions
    password=secrets.token_urlsafe(32)
    assert provision(reset=True)==0
    with sqlite3.connect(database) as connection:
        assert connection.execute("SELECT password_hash FROM auth_users WHERE username='console-admin'").fetchone()[0] != before
        assert connection.execute("SELECT version FROM auth_users WHERE username='console-admin'").fetchone()[0] == 2
        assert connection.execute("SELECT version FROM schema_migrations ORDER BY version").fetchall() == versions
print("Console provisioning and recovery: Argon2id, initialized-v6 consistency and repeat-provision rejection PASS")
