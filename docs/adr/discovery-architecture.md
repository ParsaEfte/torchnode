# Discovery as identity and endpoint observations

Date: 2026-09-28. Scope: architecture only; discv4 remains the only provider.

## Context and repository audit

Previously `ScanDaemon` directly owned the discv4 listener, bootstrap bonding,
FIND_NODE traversal, `DiscoveredNode` maps and conversion into `NodeRecord`.
Both the transient map and SQLite `nodes.key` identified entries by IP/UDP.
The unused `DiscoveryEngine` also converted discv4 objects directly into rows.
`InspectionResult` hardcoded discv4 as its source. Inspection and the Go helper
already consumed `NodeRecord`, rather than packet classes. UDP and TCP were
separate, and authenticated Hello/Status JSON already lived in
`p2p_observations`, independently of the discovered ports.

The dashboard reads node projections through `NodeStore`; its RPC and Beacon
counters count their persisted availability flags. Deep Inspection sets those
flags and saves them in `InspectionService.finish`. The background API inspector
uses the same flags. CSV exports projections; Clear All Data stops the scanner
and uses the inspection generation guard before clearing collected tables.

## Decision and dependency direction

```
LocalNodeIdentity + discv4 packet/bonding/crawl implementation
    -> Discv4DiscoveryProvider implements DiscoveryProvider
    -> immutable DiscoveryObservation
    -> NodeIdentity + NodeEndpoint values / NodeRecord endpoint projection
    -> scanner persistence and independent P2P, RPC, Beacon inspection
    -> Observatory and Deep Inspection
```

`DiscoveryProvider` exposes protocol, start, observation delivery and close. Discovery emits queued
observations during crawling, so counters do not wait for an entire crawl. A closed
provider drains pending receipt evidence without network activity, allowing a
scanner stop to persist already received observations. The provider owns the
socket listener and joins it at shutdown. Bootstrap addresses, IPv4 packet
handling, UDP bonding, depth, delays and FIND_NODE behavior are preserved.
`DiscoveryEngine` is now a small protocol-neutral persistence bridge.
`ScanDaemon` understands neither packet layouts nor bonding state.

`model.NodeIdentity` contains a normalized 64-byte secp256k1 public-key identity,
independent of IP and ports. `discovery.LocalNodeIdentity` is the renamed existing
local signing credential, never the identity of a remote discovered peer.
Legacy rows whose identity cannot be recovered retain an explicitly unavailable
identity and their existing endpoint key; no identity is synthesized.

`NodeEndpoint` contains address, transport, port, address family and purpose.
Discovery UDP and P2P TCP are distinct purposes/transports; zero TCP remains an
unusable advertisement, never a fallback to UDP. Address families include IPv6
as a value representation only. No IPv6 discovery or probing is enabled.

`DiscoveryObservation` contains identity, source, immutable endpoint claims,
receipt `Instant` and provenance. For discv4 provenance records the NEIGHBORS
sender, not a claim of authenticated endpoint ownership. Repeated receipts have
distinct timestamps. A conflicting TCP port or changed address is another
observation, even when the identity is unchanged. Exact re-delivery is idempotent.
Discovery establishes an observation, not independent verification or reachability.

`NodeRecord` remains the compatible list/inspection projection for one identity
and discovery endpoint. Its row key includes identity plus IP/UDP; its canonical
identity is `identity()`, not that row key. One identity may have several rows
and arbitrary retained observations. The UI continues listing endpoint
projections, so its Discovered count is not a new unique-identity analytic.
A selected observation supplies the P2P TCP endpoint, independently from UDP.
Inspection imports only neutral model types. Source labels come from the record.

Authenticated Hello's `listenPort`, including zero, remains in P2P evidence and
cannot replace the discovered TCP claim or the endpoint supplied to the helper.
TCP reachability, RPC/Beacon candidate URLs, successful API results and Hello
remain separate observations with their existing semantics. No NAT conclusion
is derived from disagreement. No P2P protocol logic or API port heuristic changes.
Inspection writes service fields only, preventing a delayed inspection from
restoring an older discovery endpoint.

## Persistence and migration

The existing `nodes` and `p2p_observations` tables remain. An additive
`discovery_observations` table stores normalized identity, source, provenance,
exact ISO timestamp and the endpoint array as JSON. It preserves conflicts and
receipt times without a speculative multi-table normalized model. An identity
index supports observation lookup. `nodes.discovery_source` records the selected
projection source. The existing Last Seen column retains epoch-second precision;
receipt evidence retains nanoseconds. Last Seen now reflects received evidence,
rather than refreshing merely because an old neighbor is visited again.

Migration version 1 is recorded in `schema_migrations`. Within one transaction,
it backfills existing endpoint claims at their saved Last Seen timestamp with
provenance `legacy-node-record`, changes recoverable row keys to include identity,
and remaps associated P2P keys. Raw legacy identity fields, inspection fields,
Hello/Status JSON and P2P timestamps are preserved. Unavailable legacy identity
rows retain their endpoint keys. It does not delete the database or historical
rows. Reopening does not repeat backfill. Failure rolls back the migration.
Existing IP/UDP links remain usable as a compatibility lookup for the most
recent matching projection; new links use identity-qualified keys.

Saving discovery evidence and its projection is transactional. Rediscovery does
not erase service fields. Evidence is append-only apart from Clear All Data,
which transactionally clears discovery observations, P2P observations and nodes,
keeping schema/migration metadata. Deleting an individual projection retains its
discovery history. SQLite failures propagate rather than silently losing evidence.

## Sanity checks and regression protection

Loopback fixtures exercise successful Deep Inspection RPC and Beacon probes,
SQLite persistence, dashboard counters and CSV despite an unavailable P2P
endpoint. They establish that known-good API observations flow through this path;
a zero count in a remote scan alone does not reproduce a persistence defect.
No RPC/Beacon heuristic was broadened. Other tests cover identity normalization,
multiple endpoints, conflicts, exact timestamps/provenance, distinct UDP/TCP,
actual NEIGHBORS translation through the provider, helper TCP selection, stale
inspection writes, Hello zero port, absent client identity, migration backfill,
migration rollback/reopen and collected-data clearing. Existing Java/Go protocol
regressions retain no-compatible-ETH Status as NOT_TESTED after Auth/Hello PASS.

## Deliberately deferred

No discv5 provider (including a stub), ENR fetching/parsing/verification, IPv6
network activity, NAT analysis, GeoIP/ASN, historical analytics, UI redesign,
ETH/68, BSC/Bor protocol, transaction tracking or additional RLPx features are
implemented. ENR/sequence remain unavailable. Future providers can emit neutral
observations without changing packet-independent inspection or rewriting storage.
Selecting among conflicting sources is a future policy; retained evidence must
not be replaced by a claim of canonical network truth.
