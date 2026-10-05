# TorchNode v0.0.1

TorchNode Observatory's first public release provides Ethereum network
observability below the RPC layer. It records evidence from one operator's
observer and keeps cryptographic node identity separate from IP endpoints.
Latest projections support browsing; historical observations remain the
durable record. Changes are derived from comparable evidence.

## Highlights

- **Discovery:** discv4 and discv5, ENR acquisition and validation, IPv4/IPv6
  endpoint claims, and provenance for advertised and observed addresses.
- **Protocol inspection:** bounded TCP, RLPx Auth, devp2p Hello, and ETH Status
  stages. RPC and Beacon probes are independent evidence paths; a failed P2P
  stage does not erase their results.
- **History and changes:** retained discovery, ENR, enrichment, and inspection
  occurrences, with conservative derived ChangeEvents.
- **Analysis and presentation:** endpoint analysis, bounded Network Analytics,
  Dashboard, Deep Inspection, History, Changes, and printable network reports.
- **Public API:** read-only `/api/v1` with bounded identity history and CSV
  exports. The retired network-wide `/export.csv` returns HTTP 410.
- **Deployment:** a single linux/amd64 image contains Java and both Go helpers,
  runs as non-root, provides an HTTP health check, and stores SQLite data in a
  persistent volume. GeoLite2 Country and ASN datasets are optional mounts.

## Install

```sh
docker pull --platform linux/amd64 parsa202089/torchnode:0.0.1
docker volume create torchnode-data
docker run -d --name torchnode --platform linux/amd64 --restart unless-stopped \
  -p 127.0.0.1:8080:8080 -v torchnode-data:/data \
  parsa202089/torchnode:0.0.1
```

Open <http://127.0.0.1:8080/>. See the [deployment guide](deployment.md) for
Compose, persistence, optional datasets, backup, and shutdown. See the
[API guide](api.md) for request and export semantics. The source is licensed
under [MIT](../LICENSE).

## Known limitations

- Docker Hub publication supports linux/amd64 only. Docker Desktop on arm64
  can run it through emulation; native arm64 support is not claimed.
- Country and ASN MMDB files are not bundled. Country is approximate network
  location; ASN organization is not proof of a hosting provider.
- The UI and API have no application authentication or rate limiter. The
  documented port binding is host-loopback only. Internet exposure requires
  external security controls.
- One SQLite volume is for one TorchNode instance. Reports describe this
  observer's evidence, not the whole Ethereum population. Advertised IPv6
  does not establish reachable IPv6; untested stages and missing evidence
  are not failures or inferred disappearance.
- The internal Maven artifact version remains `1.0-SNAPSHOT`; the immutable
  Git `v0.0.1` tag and Docker `0.0.1` tag identify this release distribution.
