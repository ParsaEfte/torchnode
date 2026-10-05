# Operational deployment hardening validation

Results below were reproduced on 2026-10-04 and 2026-10-05. An unlisted gate
is not claimed as passed.

## Baseline and source checks

- Required baseline: `main` at `1e27097f5c1f81ce70c8d74b08fa93d2b263d6cf`.
  On 2026-10-05, HEAD matched upstream, but the working tree already held
  deployment-scope changes. They were audited read-only and preserved.
- The pre-change datastore reports schema migration version 5; no migration 6
  file was present.
- `mvn test`: 153 tests, zero failures/errors/skips, success.
- `mvn package`: 153 tests, zero failures/errors/skips, assembly JAR built,
  success on isolated rerun. One concurrent earlier package run hit a UDP
  timing assertion (`MALFORMED_RESPONSE` versus `BOND_TIMEOUT`) while Go race
  tests ran; the isolated rerun passed.
- `go test ./...`: 43 tests passed in two packages.
- `go vet ./...`: passed.
- `go test -race -count=1 ./...`: 43 tests passed in two packages.
- `docker compose config`: passed and showed host binding
  `127.0.0.1:8080:8080`, a named `/data` volume, no extra services, and
  `no-new-privileges` with all capabilities dropped.
- Release-tag dry-run: `refs/tags/v0.0.1` generated `0.0.1`, `0.0`, `0`, and
  `latest`; main-ref dry-run generated `edge` and `sha-1234567890ab`.
- `git diff --check`: passed at the time of this report.
- A read-only SQLite backup of the live representative database was written
  outside the repository at a temporary path. Its SHA-256 was
  `32cc0e49fdd13c40a88ef48c06e0560d797082acd1e7a83aa0e717b1c0c249c6`.
  The copy has schema v5, `integrity_check=ok`, zero foreign-key violations,
  14,003 latest node projection rows, 62,564 discovery observations, 7,495
  inspection runs, and 2,938 ChangeEvents. The live database was not mutated.

## Local image and deployment

- On 2026-10-05, `mvn test` and `mvn package` each passed 153 tests; `go test
  ./...` and `go test -race -count=1 ./...` each passed 43 tests; `go vet ./...`
  passed. go-ethereum remains pinned to v1.17.6.
- The first source `docker compose build` failed at `go mod download`: the
  official Go proxy redirected module archives to a storage URL returning
  HTTP 403 from this environment. The pinned archive was retrieved through
  `goproxy.io` in a container; the Dockerfile now uses it as a fallback, with
  `go.sum` verification. A clean source `docker compose build` then passed.
- Exact quick-start `docker compose up -d` started the arm64 image. The
  container became healthy, ran as UID/GID 10001, and served `/`, `/api/v1`,
  `/analytics`, `/reports`, and `/api/v1/help` with HTTP 200. `/export.csv`
  returned 410. API discovery reported `apiVersion=v1`.
- The local image was 549,695,277 bytes unpacked by Docker image inspect.
  `/app` held only the JAR, two executable helpers, and LICENSE. No Maven or
  Go executable was found in the runtime image. The corrected OCI app version
  label was `1.0-SNAPSHOT`. Idle Compose usage was 120.3 MiB, 0.23% CPU,
  and 41 PIDs on this Docker Desktop host.
- Actual Compose configuration bound only `127.0.0.1:8080`, used a named
  writable `/data` volume, dropped all Linux capabilities, enabled
  `no-new-privileges`, and did not use privileged or host networking.
- `scripts/container-smoke.sh torchnode:local` passed twice. It executed the
  P2P helper protocol, observed Java spawning and stopping the packaged
  discv5 helper, checked API v1, non-root execution, schema v5, integrity and
  foreign keys, and recreated a container with the same disposable volume.
  The first initialized DB SHA-256 was
  `5e36532b2cce3c0077fafae16bbed72eb9aa276fdf725e19d580eec0c08dec6a`.
- `docker compose down` then `docker compose up -d` recreated the Compose
  container without deleting its named volume. The new container was healthy
  and served `/api/v1`.
- Docker Scout was present, but its quick scan required Docker Hub sign-in;
  no vulnerability result is claimed.

## Existing database and lifecycle

- A read-only SQLite backup of the live representative DB was placed outside
  the repository. Its SHA-256 was
  `32cc0e49fdd13c40a88ef48c06e0560d797082acd1e7a83aa0e717b1c0c249c6`.
  It had schema v5, `integrity_check=ok`, zero foreign-key violations,
  14,003 nodes, 62,564 discovery observations, 7,495 inspection runs, and
  2,938 ChangeEvents. The live DB was not used for destructive testing.
- A separate disposable volume containing that copy started healthy. UI,
  API, analytics, and reports returned 200. Clear returned success; afterward
  API identities were empty, while analytics and reports still returned 200.
  A new scan started the packaged discv5 helper from Java, and a new identity
  appeared through API v1. `docker stop -t 30` exited within about four
  seconds without SIGKILL. Its closed DB still had schema v5,
  `integrity_check=ok`, zero foreign-key violations, and 70 newly collected
  nodes. Restarting the same volume became healthy and served API v1.

## Browser and external distribution

- Chrome/Computer Use was retried against the running local container. Its
  initial browser state was visible, but navigation failed with
  `Computer Use server error -10005: cgWindowNotFound`; the in-app browser
  reported `Browser is not available: iab`. No visual observation is claimed.
- Chrome/Computer Use was retried against the independently pulled Docker Hub
  image. The first attempt reported that the Chrome application state changed;
  the isolated retry reported `Browser is not available: chrome`. Automated
  browser validation was blocked by environment/tooling. The operator then
  inspected the running pulled image at desktop (~1440px), laptop
  (~1024–1280px), and mobile (~375–430px) widths, including the requested
  pages and interactions, and reported `PASS — no blocking visual issues`.
- Workflow run `37299049396` completed successfully: verify passed its Java,
  Go, tag-policy, amd64 Docker build, and container smoke/persistence gates;
  Docker Hub login and build/push passed. It published
  `parsa202089/torchnode:edge` and `sha-9e46a74185fd`. The observed registry
  index digest for `edge` was
  `sha256:ce8be1db8fc722b996a0ffc1016d7cbe9744efff8b961fb9d5ea4230d0b9aec7`;
  the linux/amd64 runtime manifest was
  `sha256:66c1389987f3cbbad817528b603d8fbe3beb15cffe4f17885f6936d0bfbf4a1f`.
  The index also contained an unknown/unknown attestation manifest.
- A plain `docker pull parsa202089/torchnode:edge` on this arm64 host failed
  with `no matching manifest for linux/arm64/v8`, exposing a quick-start
  documentation error. `docker pull --platform linux/amd64
  parsa202089/torchnode:edge` succeeded independently. Docker inspected the
  pulled image as linux/amd64, 529,814,942 bytes unpacked, UID/GID 10001,
  with the registry index digest above. The README and deployment guide now
  specify the platform for the prebuilt-image path.
- A fresh persistent named volume and the pulled image became healthy on
  host loopback. Dashboard, API v1, analytics, reports, and help returned
  HTTP 200; API discovery reported `apiVersion=v1`; legacy `/export.csv`
  returned 410. The P2P helper exchanged its expected JSON protocol shape.
  Java started the packaged discv5 helper during a scan and stopped it when
  scanning stopped. The scan created 95 nodes.
- `docker stop -t 30` exited in about 1.2 seconds, without SIGKILL. The
  closed database copy before recreation had SHA-256
  `2d590a5fbb36349deab522b88bcd1926e5833ac01e119db677036c1e96037123`,
  schema v5, `integrity_check=ok`, zero foreign-key violations, 95 nodes,
  190 discovery observations, and 16 inspection runs. Removing and recreating
  the container with the same volume became healthy and preserved readable
  identity data. Dashboard, analytics, reports, and API still returned 200.
- Pulled-image runtime inspection found only the JAR, two executable helpers,
  and LICENSE in `/app`; neither Maven nor Go was in the runtime. Image
  history had no credential-pattern matches. After publication, Docker Hub
  returned 200 for `edge` and the commit-SHA tag, and 404 for `0.0.1` and
  `latest`. Idle usage after the scan was about 367.9 MiB, 0.88% CPU, and
  45 PIDs on this arm64 Docker Desktop host using amd64 emulation.
- The documented prebuilt Compose path was also exercised with
  `DOCKER_DEFAULT_PLATFORM=linux/amd64`, `TORCHNODE_IMAGE` set to the edge
  image, and `TORCHNODE_PULL_POLICY=always`. Compose pulled it, started a
  healthy container on `127.0.0.1:8080`, and served Dashboard and API v1.
  Compose was then stopped without deleting its data volume.

## CI lifecycle test correction

- The first pushed workflow run (`37296113137`) failed its Java gate in
  `NetworkEnrichmentLifecycleTest.queuedCancellationLateStopAndCallbackSuppression`:
  the assertion at line 33 read `queued.isCancelled()` immediately after
  `active.get()` observed cancellation. `close()` cancels the active and queued
  futures sequentially, so the active wakeup does not synchronize with queued
  cancellation. This was a test scheduling race; production lifecycle code
  was unchanged.
- The follow-up test waits for cancellation of the queued future itself with
  a bounded `get`, then retains the queued-state and all callback/cache/worker
  assertions. No sleep, timeout increase, or assertion removal was used.
- Before the fix, 30 isolated local method runs passed despite the CI failure.
  After the fix, 30 isolated method runs and 15 whole-class runs passed; the
  three related enrichment/lifecycle classes passed 13 tests. `mvn test` and
  `mvn package` each passed all 153 tests. Go test (43), vet, and race (43)
  passed again. Follow-up commit: `addc0846117ec68256c2438a4aacef3c7e9e7830`.
- Workflow run `37297735685` passed Java, Go, tag-policy, amd64 Docker build,
  and container smoke/persistence in its verify job. Its publish job failed at
  `docker/login-action@v4` before build/push with the public run annotation:
  `Error response from daemon: Get "https://registry-1.docker.io/v2/": unknown: malformed HTTP Authorization header`.
  No secret values were inspected. The repository secrets were updated by the
  operator; the subsequent run `37299049396` passed and published as recorded
  above. No failing gate was bypassed.
