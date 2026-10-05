# TorchNode Observatory

**Ethereum network observability below the RPC layer.** TorchNode discovers
cryptographic node identities, inspects public protocol and service endpoints,
and stores timestamped evidence in a local SQLite database. Its Dashboard,
History, Changes, Network Analytics, Reports, and read-only API show what this
observer measured. They do not estimate the entire Ethereum network.

[![CI and Docker distribution](https://github.com/ParsaEfte/torchnode/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/ParsaEfte/torchnode/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## Quick Start: v0.0.1 Docker image

Docker Engine or Docker Desktop is required. The published image supports
**linux/amd64**; Docker Desktop can run it through emulation on an arm64 host.

```sh
docker pull --platform linux/amd64 parsa202089/torchnode:0.0.1
docker volume create torchnode-data
docker run -d --name torchnode --platform linux/amd64 --restart unless-stopped \
  -p 127.0.0.1:8080:8080 -v torchnode-data:/data \
  parsa202089/torchnode:0.0.1
```

Open the [Dashboard](http://127.0.0.1:8080/) or [API v1](http://127.0.0.1:8080/api/v1).
Stop with `docker stop torchnode`; restart with `docker start torchnode`.
The named volume retains `/data/torchnode.db` across container recreation.
Use one TorchNode container per database volume.

The UI and API have **no authentication or application rate limiter**. The
command publishes port 8080 on host loopback only. Public Internet exposure
requires external TLS, authentication, access control, and rate limiting.
No inbound Ethereum port is published.

From a source checkout, `docker compose up -d` builds the image without host
Java, Maven, or Go; `docker compose down` retains its data volume. For Compose
with the prebuilt release image, optional GeoLite2 mounts, logs, backups, and
restore, see the [deployment guide](docs/deployment.md). Optional Country and
ASN datasets are not bundled; without them, lookups report
`DATASET_UNAVAILABLE`. `edge` remains a moving development tag; `latest`
tracks the latest stable release. Prefer `0.0.1` for a repeatable deployment.

## Evidence and architecture

TorchNode treats IP addresses as endpoints, not identities. Each discovery,
inspection, enrichment, and history record keeps its own source and time.
Latest state is a projection; historical observations are durable evidence.
Changes are derived comparisons between compatible observations. A failed
P2P stage does not invalidate independent RPC or Beacon results.

```text
discv4 + discv5 discovery -> cryptographic node identity -> ENR / endpoints
                                                        |
                      +---------------------------------+-----------------+
                      |                                 |                 |
              TCP -> RLPx -> Hello -> ETH Status        RPC             Beacon
                      +---------------------------------+-----------------+
                                                        |
                                   optional address-scoped GeoIP / ASN
                                                        |
                             historical observations -> changes / analytics
                                                        |
                                     Dashboard / Reports / Public API v1
```

The [public API guide](docs/api.md) covers bounded identity history and
exports; `/api/v1` is the discovery route. The former network-wide
`/export.csv` returns HTTP 410. See the [v0.0.1 release notes](docs/release-notes-v0.0.1.md),
[changelog](CHANGELOG.md), and [MIT license](LICENSE).

Discovery uses a provider boundary with independent discv4 and discv5 providers.
Cryptographic node identities are separate from endpoint observations, whose
source, provenance and timestamps are retained in SQLite. See the
[discovery architecture decision](docs/adr/discovery-architecture.md).

Completed deep inspections and background API scans are now retained as
measurement occurrences, with bounded identity history in Deep Inspection.
Repeated identical evidence shares stored payloads while each attempt keeps
its own time and outcome. Existing node rows remain the latest projection;
history records factual observations. A separate, bounded Deep Inspection
section shows conservative derived comparisons between compatible evidence;
the observations remain authoritative. See the
[historical observations ADR](docs/adr/historical-observations.md) and
[change detection ADR](docs/adr/change-detection.md).

TorchNode acquires and validates real ENRs over discv4 and discv5, preserving raw records,
sequence, unknown fields and advertised endpoints independently of discovery
and P2P evidence. Trusted IPv4 and IPv6 endpoints support active discovery and
inspection. See [ENR support](docs/adr/enr-support.md) and
[Active IPv6 support](docs/adr/active-ipv6-support.md).

discv5 uses the pinned geth discovery engine through a separate helper. Build it
before scanning with both providers:

```shell
cd p2p-helper
GOCACHE=/private/tmp/torchnode-go-build go build -o ../target/torchnode-discovery-helper ./cmd/discovery
cd ..
```

Set `TORCHNODE_DISCV5_HELPER` to override its path and
`TORCHNODE_DISCV5_BOOTSTRAPS` to an ENR configuration file. Missing or failed
helpers leave discv4 available. Observatory counts cryptographic identities;
endpoint observations remain preserved. See [discv5 architecture](docs/adr/discv5-support.md).
Both endpoint families retain their provenance under one cryptographic identity.
IPv6 discovery, TCP/RLPx, and HTTP transport have deterministic loopback coverage;
public IPv6 success depends on observer connectivity. See the
[IPv6 validation report](docs/validation/active-ipv6-support.md).

## Build and run

```shell
mvn package
java -jar target/torchnode-1.0-SNAPSHOT-jar-with-dependencies.jar
```

Open `http://localhost:8080`. Scanning, stopping, inspection, filtering,
statistics, bounded API v1 exports, and reports are available in the dashboard.

The main Dashboard v2 shows bounded observation coverage, a local country-level endpoint-context map, client evidence, discovery/address-family views, protocol attempts, independent RPC/Beacon probes, and derived changes. Its 24-hour, 7-day and 30-day historical windows are separate from the latest-projection node table. No peer addresses are sent to a map service. See [Dashboard v2](docs/adr/dashboard-v2.md).

Deep Inspection includes bounded, expandable discovery, ENR, enrichment, run, and derived-change history. The [Network Measurement Report](http://localhost:8080/reports/network) prints a selected Network Analytics window with units, denominators, unknowns, and methodology; browser Print can save it as a PDF. Reports are live views of stored evidence, not immutable snapshots. See [History UX and reports](docs/adr/history-ux-reports.md).

`/analytics` provides a bounded UTC measurement window over recorded observations, with explicit counting units, denominators, and unknown evidence. `/analytics.json` exposes the same internal report; `/analytics/snapshot.json` is a separate latest-projection view. These observer-local counts are not Ethereum population estimates. See [Network Analytics](docs/adr/network-analytics.md).

Set `TORCHNODE_PORT` or `TORCHNODE_DB` to override the default port and database path.
Native execution binds 127.0.0.1 by default; `TORCHNODE_BIND` overrides the
address. The Docker image sets it to 0.0.0.0 internally, with Compose publishing
only the host loopback address.

Node Inspect measures discovery, P2P TCP, RLPx Auth, devp2p Hello, ETH Status,
JSON-RPC and Beacon API independently. The optional RLPx inspector is an isolated
Go helper using pinned go-ethereum; the Java dashboard still builds and runs
without it. See [the architecture decision](docs/adr/rlpx-p2p-inspection.md).

## Enable authenticated P2P inspection

From the repository root, build the Java jar and the companion helper with Go
1.25 or newer (or Go's automatic toolchain download):

```shell
mvn package
(cd p2p-helper && GOTOOLCHAIN=auto go build -o ../target/torchnode-p2p-helper .)
java -jar target/torchnode-1.0-SNAPSHOT-jar-with-dependencies.jar
```

The application discovers an executable `torchnode-p2p-helper` beside its jar
(or in `target/` / `p2p-helper/` when running from the repository root).
`mvn package` alone does **not** build the Go helper. For an installed layout
or an explicit override, set `TORCHNODE_P2P_HELPER` before starting Java:

```shell
export TORCHNODE_P2P_HELPER="$PWD/target/torchnode-p2p-helper"
```

An explicit path may be absolute or relative to the process working directory;
if it is wrong or not executable, TorchNode does not silently fall back to
another binary. Without a runnable helper, TCP is still tested while Auth,
Hello and ETH Status remain `NOT_TESTED`. The helper connects only to the
discovered node's advertised **TCP** port and authenticates its discovered
secp256k1 node ID. UDP and TCP ports are never assumed equal.

ETH Status requires a real local chain context. Set
`TORCHNODE_P2P_STATUS_RPC_URL` to an operator-trusted execution node's HTTP(S)
JSON-RPC endpoint (prefer loopback), for example **only if you run and trust a
synced local execution client**:

```shell
export TORCHNODE_P2P_STATUS_RPC_URL=http://127.0.0.1:8545
```

Do not point this variable at the node being inspected: its RPC is not
independent local Status context. TorchNode never fills the variable from a
discovered peer's RPC endpoint. If you have no trusted local execution RPC,
leave the variable unset; real Auth and Hello can still run, with ETH Status
`NOT_TESTED`. The helper makes only read-only chain,
network and block calls. It verifies canonical genesis and a current head;
currently Mainnet, Sepolia and Hoodi are supported. Without valid context,
Auth and Hello can still pass, but ETH Status remains `NOT_TESTED` with a reason.
The helper supports devp2p 4/5 Hello and negotiates the highest common ETH
version among 69–72. It does not advertise SNAP or serve chain data; it only
records a peer's advertised `snap/1` capability. ETH/69–72 Status provides
network ID, execution genesis hash, fork ID, and the available full-block range
(`earliest`, `latest`, `latestHash`), but no
total difficulty. Authenticated Hello and Status observations are retained in
the `p2p_observations` latest SQLite projection and in completed inspection
history.
The pinned go-ethereum release exposes ETH/69–72, not ETH/68. An ETH/68-only
peer can complete Auth and Hello, but ETH Status remains `NOT_TESTED` with
`NO_COMPATIBLE_ETH_CAPABILITY`; TorchNode does not fabricate an ETH/68 Status.
The local Hello is devp2p/5 with client ID `TorchNode Observatory/1.0`, the
pinned geth ETH capabilities, listen port 0 (no inbound listener), and the
ephemeral authenticated secp256k1 public key. Its RLP fields and encrypted
message code 0 are exercised against a local geth `p2p.Server` in Go tests.
For failed Hello exchanges, diagnostics distinguish decoded Disconnect reason
codes, clean EOF, TCP reset, timeout, malformed frame and invalid MAC when
the transport actually exposes those conditions. A decoded frame count of zero
does not establish whether the peer sent an incomplete encrypted frame.

Default helper deadlines are TCP 2 s, Auth 3 s, Hello 2 s, Status 3 s and
12 s total. They can be bounded via `TORCHNODE_P2P_TCP_TIMEOUT_MS`,
`TORCHNODE_P2P_AUTH_TIMEOUT_MS`, `TORCHNODE_P2P_HELLO_TIMEOUT_MS`,
`TORCHNODE_P2P_STATUS_TIMEOUT_MS`, and `TORCHNODE_P2P_TOTAL_TIMEOUT_MS`.
Java permits at most four concurrent helper processes and kills a child after
15 s or when its inspection task is interrupted.

`PASS` means that specific protocol exchange completed; TCP reachability does
not imply Auth/Hello/Status success. A single network value is `OBSERVED`;
independent agreement is `MATCH`, disagreement is `MISMATCH`, and only
consistent independent evidence makes a network `VERIFIED`. A failed P2P
interface does not invalidate independently reachable RPC or Beacon services.
Local deterministic RLPx peers and a pinned geth `p2p.Server` exercise Auth,
Hello, Status, malformed input, timeouts and cancellation with `go test ./...`;
no public peer is required.
Geth, Reth, Nethermind, Besu and Erigon binaries are not bundled, so live
multi-client interoperability must be run separately before claiming coverage.
The Status `latestHash` is the hash of the latest **available full block**, not
necessarily the canonical chain head. A first non-fixture bidirectional ETH/72
Status exchange was captured on Sepolia on 2026-09-25 using a verified local
light-client context and a separate unmodified geth 1.17.6 peer. The peer was
still at genesis, and Reth, Nethermind and Besu Status interoperability remains
unevaluated. See the
[inspection ADR](docs/adr/rlpx-p2p-inspection.md) for the evidence and limits.

## Optional offline network enrichment

Deep Inspection and CSV expose address-scoped country and ASN evidence for IPv4
and IPv6. They never assign one location/provider to a cryptographic identity.
Hosting classification is `NOT_AVAILABLE`: ASN organization is not a hosting,
cloud or residential classification. Endpoint/NAT conclusions are unchanged.

Obtain **GeoLite2 Country** and **GeoLite2 ASN** MMDB files separately under
[MaxMind's download/setup terms](https://dev.maxmind.com/geoip/geolite2-free-geolocation-data/)
and [GeoLite EULA](https://www.maxmind.com/en/geolite/eula). Configure the local
files before starting TorchNode:

```shell
export TORCHNODE_GEOIP_COUNTRY_DB=/path/to/GeoLite2-Country.mmdb
export TORCHNODE_GEOIP_ASN_DB=/path/to/GeoLite2-ASN.mmdb
```

GeoIP2 Country files are also accepted. TorchNode does not download datasets or
accept/download license keys. Keep databases and credentials outside the repo;
follow the dataset terms, including updates and attribution. This product
includes GeoLite2 data created by MaxMind, available from https://www.maxmind.com
when those optional files are configured. The Java MMDB reader is Apache 2.0;
the datasets have separate terms.

Without either file, that lookup reports `DATASET_UNAVAILABLE`; Ethereum
observation/inspection continues. Private, local, documentation and other
excluded special-purpose addresses report `NOT_APPLICABLE`. Missing records and
runtime failures remain distinct. Country is approximate IP-network location,
not a peer's physical location; no city/region precision is claimed.

Lookups are local, asynchronous and bounded. Rendering/export only read stored
evidence; no observed IP is sent to a geolocation service. Results retain
separate country/ASN source, SHA-256/build version, prefix and lookup timestamp.
Replace datasets and restart the scanner/application to change the active
version. Same-version stored results (including misses/failures) are reused;
there are no automatic retries or background dataset updates. Prior-version
records remain evidence, and UI/export show the latest known lookup, explicitly
separate from the endpoint observation time. See the
[ADR](docs/adr/geoip-asn-enrichment.md) and
[validation](docs/validation/geoip-asn-enrichment.md).
