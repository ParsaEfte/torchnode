# Operational deployment hardening

## Audit and decision

TorchNode is a Java 21 embedded-Tomcat application packaged as a Maven
assembly JAR. The P2P inspector and discv5 provider are two binaries from one
Go 1.25 module, pinned to go-ethereum v1.17.6. P2P helper discovery supports
`TORCHNODE_P2P_HELPER`; discv5 uses `TORCHNODE_DISCV5_HELPER` and sends its
ephemeral private key over stdin. The application originally bound HTTP to
127.0.0.1, used `TORCHNODE_PORT` and `TORCHNODE_DB`, initialized SQLite on
store open, and used a shutdown hook to stop Tomcat, scanning, and inspection.
JSPs use temporary files. Country/ASN MMDB paths are optional. `/api/v1` is
a lightweight read-only discovery route; it serves as health without adding
product surface. There was no Dockerfile, Compose, or CI workflow.

Use one container with the JRE, JAR, and both helpers. A Go build stage and
Maven build stage produce the artifacts; the final JRE image excludes both
compilers and source. The application starts as UID/GID 10001, with only
`/data` persistent. The image sets helper paths explicitly and binds HTTP to
the container interface; native Java keeps loopback binding. Docker's host
port is published on loopback by default. Only outbound Ethereum networking
is needed. No inbound P2P port is published.

## Storage, readiness, lifecycle

`/data/torchnode.db` resides in a named Compose volume. The datastore is
single-instance/single-writer; concurrent containers must not share it.
Startup opens SQLite before HTTP readiness, using existing schema-v5 startup
behavior. There is no migration 6. The health check performs a bounded GET
of `/api/v1`, without evidence reads or writes. Java is PID 1 and its existing
shutdown hook handles SIGTERM. The existing child-process deadlines, limits,
and cleanup remain in place. `/tmp` stays writable for embedded Tomcat JSP
work. Missing MMDBs produce `DATASET_UNAVAILABLE`, not startup failure.

The image has no auth or rate limiter. Docker/Compose is for an
operator-controlled deployment. Do not expose the UI/API directly to the
hostile Internet. The Compose port is host loopback; no permissive CORS is
added. GeoLite2 Country/ASN files are external, optional, read-only mounts.
Private key material is neither baked into an image nor logged.

## CI and distribution

PRs run Java tests/package, Go tests/vet/race, a Docker build, and container
smoke/persistence checks. They have no publishing credentials. Main pushes
pass the same gates before publishing moving `edge` and a commit-SHA tag to
Docker Hub `parsa202089/torchnode`. Version-tag pushes pass the gates and an
ancestor-of-main check, then publish semantic version, minor, major, and
`latest` tags. A release-version tag already present in the registry blocks
overwrite. `latest` is reserved for stable releases; no `v0.0.1` Git tag or
Docker tag is created by this milestone. Workflow permissions are limited to
`contents: read`; Docker Hub login uses GitHub secrets. Buildx provenance and
SBOM are requested for published images.

The Go build tries the official module proxy first, then `goproxy.io` when a
download fails. `go.sum` verifies the pinned modules; the fallback is needed
because this Docker Desktop environment receives 403 from the official
proxy's archive storage URL. The fallback is confined to the build stage.
The runtime OCI version label follows the Maven `1.0-SNAPSHOT` JAR; stable
image tags remain the release identifier until application versioning is
aligned in the formal release milestone.

Publish `linux/amd64` only until an arm64 build, startup, health, helper, and
HTTP path have been independently runtime-validated. The local arm64 Docker
host can test an amd64 image through emulation, but that is not a substitute
for native arm64 publication evidence. Base image/action tags are explicit;
reviewed update commits must re-run the integration gates. The Maven version
is still `1.0-SNAPSHOT`; Docker tags identify validated distribution builds.

## Scope and limits

This packaging layer does not change identity, observations, protocol stages,
ENR trust, enrichment, ChangeEvents, analytics, API v1, exports, or Clear.
Schema v5 remains the persistence contract. There is no new observation
domain, API-specific durable state, authentication system, reverse proxy,
hosted service, Kubernetes, or final release. Operational validation results
are recorded separately in the validation report.
