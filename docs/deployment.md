# Container deployment

TorchNode is an operator-controlled, single-instance observatory. Its web UI and
read-only API have no authentication or application rate limiter. The default
Compose file publishes port 8080 on host loopback only. Do not publish it to
the Internet without external TLS, authentication, access control, and rate
limiting. Outbound discovery and inspection use ordinary container networking;
no inbound Ethereum port is published.

## Start from source

With Docker and Compose installed:

```sh
docker compose up -d
docker compose ps
```

Open <http://127.0.0.1:8080/> or <http://127.0.0.1:8080/api/v1>.
Compose builds the Java application and both Go helpers inside the image.
No host Java, Maven, or Go installation is required. The named
`torchnode-data` volume stores `/data/torchnode.db`. Use only one TorchNode
container per database volume. Docker Desktop's named-volume location is
managed by Docker. `docker compose down` keeps the volume; **do not use**
`down -v` unless you intend to delete collected evidence.

```sh
docker compose logs -f torchnode
docker compose restart torchnode
docker compose down
```

The image runs as UID/GID 10001 and its HTTP service binds `0.0.0.0:8080`
inside the container. Compose publishes only `127.0.0.1:8080`. For a direct
Docker run, publish `127.0.0.1:8080:8080` and mount a writable volume at
`/data`. Native Java execution keeps the loopback bind by default; the
`TORCHNODE_BIND` environment variable can override it. `TORCHNODE_PORT`
selects the HTTP port and `TORCHNODE_DB` selects the database path. If you
change the port inside the image, update its health check/publishing as well.

## Prebuilt image and tags

The official Docker Hub repository is `parsa202089/torchnode` (the GitHub
repository is `ParsaEfte/torchnode`). The immutable v0.0.1 release image is
`0.0.1`; `latest` tracks the latest stable release. `edge` is the moving
validated main-branch image, while `sha-<12-character-sha>` identifies a
validated development commit. Stable `vX.Y.Z` Git tags publish `X.Y.Z`,
`X.Y`, `X`, and `latest` after validation; release-version tags are not
overwritten.
Only `linux/amd64` is published until `linux/arm64` receives independent
runtime validation. The Maven JAR currently retains its internal
`1.0-SNAPSHOT` version; image tags identify the distribution.

```sh
docker pull --platform linux/amd64 parsa202089/torchnode:0.0.1
docker volume create torchnode-data
docker run -d --name torchnode --platform linux/amd64 --restart unless-stopped \
  -p 127.0.0.1:8080:8080 -v torchnode-data:/data \
  parsa202089/torchnode:0.0.1
```

The explicit platform works on amd64 and permits Docker Desktop on an arm64
host to run this single-platform image through emulation. A plain `docker
pull` on an arm64 host fails with `no matching manifest for linux/arm64/v8`.

Compose also accepts the published image: set
`TORCHNODE_IMAGE=parsa202089/torchnode:0.0.1` and
`TORCHNODE_PULL_POLICY=always` for `docker compose up -d`; on arm64 also set
`DOCKER_DEFAULT_PLATFORM=linux/amd64`. The default
Compose path builds locally. Do not run the direct Docker and Compose
examples against the same data volume concurrently.

The image carries the Java runtime and executable P2P/discv5 helpers. It does
not contain private scanner keys, MMDB datasets, Maven, or Go. The discv5
helper receives ephemeral key material over stdin. Java is PID 1 and handles
`docker stop` through its shutdown hook. Runtime logs go to `docker logs`.
The health check probes the lightweight, read-only `/api/v1` discovery route.

## Optional GeoLite2 datasets

Obtain Country and ASN MMDB files under their provider terms. They are not
downloaded, bundled, or needed to start TorchNode. With files in the checkout
root, run:

```sh
docker compose -f compose.yaml -f compose.geoip.yaml up -d
```

The override mounts the two files read-only at `/geoip` and sets
`TORCHNODE_GEOIP_COUNTRY_DB` and `TORCHNODE_GEOIP_ASN_DB`. Without datasets,
lookups remain `DATASET_UNAVAILABLE`. City MMDB is not used. On bind-mounted
host directories, grant UID 10001 write access to `/data`; MMDB files need
only read access.

## Backup and restore

Keep the database on a local Docker volume, not an ephemeral container layer
or shared network filesystem. For a consistent online backup, use SQLite's
backup API or `sqlite3 .backup` from a trusted environment with access to the
volume. Do not copy a live database file while it may be written. To restore,
stop TorchNode, put a verified backup at `/data/torchnode.db` with UID 10001
ownership, restart, then check health and SQLite integrity/schema version.
Test restore using a separate volume before depending on a backup.

## CI and limits

Pull requests run Java, Go, Docker build, and container smoke/persistence
checks without Docker Hub credentials. Main runs the same checks before
publishing `edge` and the commit-SHA tag. Stable Git tags run the checks before
publishing release tags. The workflow uses Docker Hub repository secrets,
never build arguments. Base images and GitHub Actions have explicit version
tags; update them through reviewed changes and revalidate both helpers.
The deployment is a single writer. No application-level rate limiter, inbound
TLS, or authentication is provided. Docker host IPv6 routing varies, so a
container's IPv6 reachability must be measured in its actual environment.
