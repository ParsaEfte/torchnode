#!/usr/bin/env bash
set -euo pipefail

image=${1:-torchnode:local}
name="torchnode-smoke-$$"
volume="torchnode-smoke-$$"
port=${TORCHNODE_SMOKE_PORT:-18081}
db_copy=$(mktemp)
cleanup() {
  docker rm -f "$name" >/dev/null 2>&1 || true
  docker volume rm "$volume" >/dev/null 2>&1 || true
  rm -f "$db_copy"
}
trap cleanup EXIT
docker volume create "$volume" >/dev/null
start() {
  docker run -d --name "$name" -p "127.0.0.1:${port}:8080" -v "$volume:/data" "$image" >/dev/null
  for attempt in {1..60}; do
    status=$(docker inspect --format '{{.State.Health.Status}}' "$name")
    if [[ $status == healthy ]]; then return; fi
    if [[ $(docker inspect --format '{{.State.Running}}' "$name") != true ]]; then
      docker logs "$name"
      return 1
    fi
    sleep 2
  done
  docker logs "$name"
  echo 'Container did not become healthy.' >&2
  return 1
}
start
test "$(docker exec "$name" id -u)" = 10001
docker exec "$name" test -x /app/torchnode-p2p-helper
docker exec "$name" test -x /app/torchnode-discovery-helper
printf '{}\n' | docker exec -i "$name" /app/torchnode-p2p-helper | \
  python3 -c 'import json,sys; r=json.load(sys.stdin); assert all(k in r for k in ("tcp","auth","hello","status"))'
curl -fsS "http://127.0.0.1:${port}/" >/dev/null
curl -fsS "http://127.0.0.1:${port}/api/v1" | python3 -c 'import json,sys; assert json.load(sys.stdin)["apiVersion"] == "v1"'
test "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${port}/export.csv")" = 410
python3 - "$name" "$port" <<'PY'
import http.cookiejar
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

name, port = sys.argv[1:]
base = f"http://127.0.0.1:{port}"
browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
page = browser.open(base + "/", timeout=10).read().decode()
token = re.search(r'name="csrf" value="([^"]+)"', page).group(1)

def action(path):
    data = urllib.parse.urlencode({"csrf": token}).encode()
    return browser.open(urllib.request.Request(base + path, data=data), timeout=15).read().decode()

assert "Scanner started" in action("/scanner/start")
for _ in range(20):
    if "/app/torchnode-discovery-helper" in subprocess.check_output(["docker", "top", name], text=True):
        break
    time.sleep(0.25)
else:
    raise AssertionError("Java did not start the packaged discovery helper")
assert "Scanner stopped" in action("/scanner/stop")
assert "/app/torchnode-discovery-helper" not in subprocess.check_output(["docker", "top", name], text=True)
PY
docker exec "$name" test -w /data
docker exec "$name" test -s /data/torchnode.db
before=$(docker exec "$name" sha256sum /data/torchnode.db | cut -d' ' -f1)
docker stop -t 30 "$name" >/dev/null
docker cp "$name:/data/torchnode.db" "$db_copy"
python3 - "$db_copy" <<'PY'
import sqlite3, sys
with sqlite3.connect(sys.argv[1]) as db:
    assert db.execute('select max(version) from schema_migrations').fetchone()[0] == 5
    assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
    assert not db.execute('pragma foreign_key_check').fetchall()
PY
docker rm "$name" >/dev/null
start
docker exec "$name" test -s /data/torchnode.db
curl -fsS "http://127.0.0.1:${port}/api/v1" >/dev/null
docker stop -t 30 "$name" >/dev/null
docker cp "$name:/data/torchnode.db" "$db_copy"
python3 - "$db_copy" <<'PY'
import sqlite3, sys
with sqlite3.connect(sys.argv[1]) as db:
    assert db.execute('select max(version) from schema_migrations').fetchone()[0] == 5
    assert db.execute('pragma integrity_check').fetchone()[0] == 'ok'
    assert not db.execute('pragma foreign_key_check').fetchall()
PY
echo "Container smoke passed; persistent DB initial hash: $before"
