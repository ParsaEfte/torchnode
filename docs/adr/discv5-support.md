# Discovery v5 provider

Status: accepted; validation results are recorded below.

## Audit and count semantics (recorded before changing Observatory deduplication)

The baseline Discovered count is `store.findAll().size()`: it counts rows in `nodes`, keyed by canonical public-key identity plus IP/discovery UDP endpoint. Thus one identity at two endpoints counts twice. Generic discovery observations and ENRs already coexist separately; neither increases count by itself without a node projection. Two identities at one IP have separate identity-qualified keys.

The new Observatory view/count groups existing projection rows by canonical `NodeIdentity`, with identity-unavailable legacy rows falling back to their existing keys. Same identity from discv4 and discv5 counts once, even with multiple ENRs/observations; distinct identities at the same IP count separately. Existing endpoint rows and all source-specific observations remain stored. The view prefers a discv4 projection when available, preserving its P2P selection. API and active counters count an identity once when any of its projections supplies that evidence. CSV keeps endpoint rows for compatibility and appends source/provenance fields.

## Trust boundary

Pinned go-ethereum v1.17.6 is the protocol engine: wire framing, AES header masking/GCM, ECDH/HKDF, WHOAREYOU/identity proof, sessions, request correlation, PING/PONG, FINDNODE/NODES and routing table. It is not TorchNode's trust authority. The helper emits exact canonical received ENR RLP, public identity, receipt timestamp and acquisition/session metadata. Java runs every emitted record through the existing EnrDecoder/EnrEvidence path; structural/signature/identity failures cannot create endpoint observations. No second Java ENR parser or node database is introduced.

One Java LocalNodeIdentity supplies the same secp256k1 private scalar to discv4 and the helper over stdin for a scanner run. It is never placed in argv, events, storage or logs. Session secrets remain ephemeral in the helper. The local ENR contains identity only: unknown externally reachable addresses/ports are omitted and endpoint prediction is disabled.

No migration is needed: discovery_observations and enr_observations retain source, timestamps, provenance, endpoints, raw records and validation. Any inability to represent necessary evidence will be explained before schema changes.


## Protocol references and delegated responsibilities

Implementation was audited against the authoritative [discv5 v5.1 wire specification](https://github.com/ethereum/devp2p/blob/master/discv5/discv5-wire.md), [theory](https://github.com/ethereum/devp2p/blob/master/discv5/discv5-theory.md) and [canonical test vectors](https://github.com/ethereum/devp2p/blob/master/discv5/discv5-wire-test-vectors.md), as retrieved on 2026-09-28. The reviewed implementation is [geth v1.17.6 discover](https://github.com/ethereum/go-ethereum/tree/v1.17.6/p2p/discover) and its v5wire codec. Existing EIP-778/ENR ADR rules remain unchanged. No new crypto dependency was needed.

The helper delegates packet headers, AES-128-CTR header masking, AES-128-GCM with authenticated header data, ephemeral secp256k1 ECDH, SHA-256 HKDF, identity-proof signing/verification and nonce/session state to geth. WHOAREYOU mirrors the initiating nonce and supplies challenge data; the identity proof and derived keys bind the challenge, ephemeral key and destination identity. A correlated encrypted response confirms the remote session. Node IDs used for routing are 32-byte public-key hashes; TorchNode's canonical NodeIdentity remains the 64-byte public key.

The standard UDPv5 transport delegates request correlation, PING/PONG, FINDNODE/NODES, multi-response aggregation and table maintenance to geth. An isolated helper emits raw ENRs plus timestamp, public identity and acquisition metadata. Java revalidates structure, signature, identity binding and endpoint eligibility using EnrDecoder. A matching candidate identity does not mean independent network verification; all these observations remain OBSERVED, never VERIFIED. Authentication of the responding peer is distinguished from an ENR advertisement for a third party.

### Explicit IPv4 selection for dual-stack records

Geth's Node view can prefer a global IPv6 address over a private IPv4 address. TorchNode must still use the signed IPv4 tuple and preserve the IPv6 fields. A narrow IPv4 request adapter is therefore used for these records: it reads ip/udp explicitly, opens only udp4, and delegates packet encoding/decoding, WHOAREYOU, proof and session crypto to the **same pinned v5wire.Codec**. Its single outstanding request checks source endpoint, peer ID, nonce and request ID, permits only one WHOAREYOU transition, caps NODES totals at five and records at sixteen, rejects invalid distances/ENRs, and supports reciprocal PING and distance-zero FINDNODE. It does not fabricate an altered ENR or strip its IPv6 fields.

A deterministic fixture retains 127.0.0.1 plus a global IPv6 advertisement and completes authenticated PING and FINDNODE/NODES over IPv4 loopback. The global IPv6 value survives the returned raw ENR. Egress guards reject every IPv6 destination; IPv6-only ENRs remain passive evidence when received. Native routing cannot actively use IPv6-preferred nodes; the bounded foreground frontier uses their explicit IPv4 advertisements. No IPv6 listener, discovery or P2P/API probing is enabled.

## Provider lifecycle and local record

CompositeDiscoveryProvider owns two independent workers and bounded queues. Each provider starts/crawls independently; missing helper, crash, timeout or startup failure is reported without stopping its sibling. The Java helper reader has an 8192-character event bound and a 64-entry diagnostic ring. A silent helper is terminated after two minutes; stop sends termination and forces it after a three-second grace period. Reader/workers are joined. The provider retains accepted evidence until consumer delivery succeeds.

Scanner orchestration drains observations every second. API inspection has one separate batch worker so existing API deadlines cannot stall provider drains. Inspection reads existing provider-acquired ENR for a discv5 projection; it does not send discv4 ENR packets to a discv5 endpoint. P2P/RPC/Beacon implementations and their candidate ports remain unchanged. Start creates one shared local key; restart creates a new run identity. The same helper identity survives all requests and session epochs within the run. Geth assigns local sequence values; no endpoint changes are generated because unknown public endpoints are omitted and endpoint prediction is disabled.

## Routing, bootstrap and bounds

Bootstrap ENRs are configured in `src/main/resources/discv5-bootnodes.txt`, sourced from pinned geth V5Bootnodes (two Microsoft-operated records and one EF-operated record). They are not discv4 bootnodes. A file selected by TORCHNODE_DISCV5_BOOTSTRAPS overrides the defaults. Update by reviewing a new upstream release, verifying records and running bounded smoke validation; configurations allow 1..32 IPv4-capable ENRs.

Distance is XOR of 32-byte routing IDs; logarithmic distance uses upstream enode.LogDist. Native geth maintains 17 buckets of 16 entries, ten replacements each, liveness checks, sequence refresh and subnet diversity limits; its lookup concurrency is three. The helper additionally keeps a 256-node candidate frontier, orders it by target distance, queries the target bucket and adjacent valid buckets, and terminates a round after all frontier candidates or 64 queries. Received nodes become further query candidates; NODES provenance identifies the responding peer. A signed higher sequence updates the frontier; equal/older variants remain observation evidence. An emit cache is bounded to 4096 identities, including distinct record/session states.

The foreground has one request at a time. Native refresh has three lookup workers; revalidation requests are bounded by the 272-entry table and upstream per-peer serialization (a conservative global ceiling of 276 request tasks, ordinarily far fewer). There is no caller-supplied task spawning or unbounded Java future accumulation. Session caches are 1024 entries per codec, at most 2048 across native/fallback codecs. Both are destroyed every ten-minute engine epoch, imposing a maximum key lifetime even if continuously used. Challenges expire after one second. Standard requests use 700 ms; a handshake may reset a request deadline once. The IPv4 adapter has the same bound.

Each IPv4 socket permits at most 128 received packets per second and retains at most 4096 packet fingerprints. Exact packet replays are dropped before codec processing; geth additionally consumes handshake challenges and correlates responses. The discrete rate windows bound live challenge state to at most 256 challenges per codec. Packets outside 63..1280 bytes are rejected before parsing, including oversized datagrams whose truncation could otherwise look valid. Invalid tags, proofs, headers, requests and ENRs produce bounded diagnostics. No sensitive material is exported or persisted.

Helper JSON output queue is 256 events; valid evidence uses cancellation-aware backpressure and verbose diagnostics may be dropped on saturation. Java provider queues are 512 discovery bundles and 1024 ENRs; composite queues are 1024 of each. Queue saturation affects that provider without monopolizing the sibling. No session/routing/private state is stored in SQLite.

## Persistence, projection and UI

Existing tables represent discv5 losslessly, so **no new migration** is introduced. All source-specific observations and exact ENR evidence retain identity, endpoints, timestamp and acquisition provenance. Existing version 1/2 migrations remain. A same-endpoint discv5 observation cannot replace a selected discv4 TCP endpoint or source; it may update Last Seen. UDP-only advertisements have no P2P endpoint (projection port zero remains unavailable). ENR-advertised TCP is a candidate claim, not a successful reachability result.

Observatory counts canonical identities as documented above; storage and CSV preserve endpoint projections. CSV appends DISCOVERY_SOURCE and DISCV5_PROVENANCE after existing columns. Deep Inspection adds compact OBSERVED/source/timestamp/advertised endpoint/acquisition-session rows and reuses existing ENR display. Hello listenPort, API evidence, client detection, ETH negotiation and NetworkVerification rules are untouched. Clear stops both providers before atomically clearing existing collected tables; migration metadata remains.

## Validation and exclusions

Deterministic tests cover raw ENR revalidation, identity mismatch/invalid signatures, passive IPv6, UDP-only records, shared identity, helper failure/isolation, queue saturation, repeated shutdown, canonical deduplication, timestamps/provenance/reopen/clear, real loopback WHOAREYOU/handshake/session reuse, multipacket NODES and routing peers. Pinned upstream wire/transport tests additionally exercise official ECDH/KDF/signature/packet vectors, handshake timeout/rekey/bad-proof attacks, malformed packets, invalid NODES/correlation/distance and lookup convergence. These tests use no public network.

Public smoke results and final command results follow. Topic discovery, active IPv6, NAT detection/traversal, GeoIP/ASN, ETH/68, new RLPx or API capabilities and new API probing heuristics remain out of scope.

### Results (2026-09-28)

All deterministic Java tests passed: 80 tests, zero failures/errors. Go discovery
fixtures exercise real UDP4 packets and pinned codec cryptography, including
WHOAREYOU, handshake, authenticated session reuse, PING/PONG, FINDNODE/NODES,
multipacket aggregation, routing results beyond the bootstrap, malformed packets,
invalid tags/proofs, replay rejection, unknown/IPv6 ENR preservation, request
correlation, excessive/inconsistent NODES totals, silent-peer timeout and
cancellation during an outstanding handshake. Queue saturation/provider isolation
and repeated shutdown are covered in Java. The pinned upstream discover/v5wire
and discover packages passed separately, including authoritative crypto/packet
vectors and routing/transport attacks. The discovery fixtures also passed the Go
race detector.

Commands executed successfully from the repository root:

```shell
mvn test
mvn package
node tools/check-jsp-javascript.mjs
git diff --check
```

Commands executed successfully from p2p-helper (the specified GOCACHE was used):

```shell
GOCACHE=/private/tmp/torchnode-go-build go test ./...
GOCACHE=/private/tmp/torchnode-go-build go vet ./...
GOCACHE=/private/tmp/torchnode-go-build go build .
GOCACHE=/private/tmp/torchnode-go-build go build -o ../target/torchnode-discovery-helper ./cmd/discovery
GOCACHE=/private/tmp/torchnode-go-build go test -race ./cmd/discovery
GOCACHE=/private/tmp/torchnode-go-build go test github.com/ethereum/go-ethereum/p2p/discover/v5wire github.com/ethereum/go-ethereum/p2p/discover
```

Generated helper binaries are kept under ignored target/, not committed. No Go
module/dependency version was changed.

#### Bounded public-network evidence

A 30-second helper run used the three configured signed bootstrap ENRs. It exited
normally in 30.89 seconds and emitted 128 raw node records for 95 distinct
canonical identities. Captured protocol traces contained 56 WHOAREYOU entries,
171 sent FINDNODE messages and 184 received NODES messages. Seventeen records
were acquired from peers after successful PING/PONG plus distance-zero FINDNODE:
this requires authenticated handshake/session communication, not just receipt of
an unverified UDP datagram. Other NODES records are third-party advertisements
from authenticated responding peers, explicitly not claimed as authenticated
sessions with the returned identities. Public-peer timeouts occurred normally.

**Java revalidated all 128 captured records with the existing EnrDecoder**:
structure/signature/associated identity passed (VALID/MATCH); helper validity was
not used as a substitute for Java validation. Signed records with unknown keys
were retained. Sample authenticated peer:

- IPv4 UDP: 18.223.219.100:9000; TCP absent; ENR sequence 1; identity scheme v4.
- Canonical public identity:
  95a61903a9a9784333cc92c739c27a6e0b782f482f007db14e9d963f3a7df8c01ad6a387e8021f13e684e576ca9e5d8759b3116df521b280a887a0bdc72e457f.
- Signature VALID; identity MATCH; unknown attnets/eth2 preserved. eth2 is kept
  opaque, not misinterpreted as the execution eth fork entry.
- Another responding peer advertised 150.136.148.96 UDP 12000 / TCP 13000,
  demonstrating independent transport ports.

Application smoke evidence additionally included:

- 148.113.224.126 TCP/UDP 9000, sequence 1713, IPv6 advertisement
  2607:5300:221:7e00:0:0:0:0 TCP6/UDP6 9000. IPv6 was preserved passively.
- 157.180.55.78 TCP/UDP 30303, sequence 1753036782237, execution eth fork hash
  0x07c9462e / next 0, unknown snap preserved, Java VALID/MATCH.
- 143.189.126.102 UDP 62421 / TCP 30303, execution eth fork hash 0x07c9462e /
  next 0. Advertised endpoints remain evidence, not reachability verification.

#### Application and coexistence smoke

The user's existing IntelliJ application occupied UDP 30303. It was not stopped
or changed. The temporary dashboard on port 18080 correctly isolated the discv4
bind failure and continued discv5 discovery/inspection. Two 30-second scanner
cycles produced 164 canonical identities (205 discv5 observations) and, after
Clear All Data, 84 canonical identities (114 discv5 observations). Stop took
32 ms and 18 ms. The database retained multiple source observations despite
identity deduplication. Observatory and Deep Inspection compiled/loaded; the
inspection exposed saved authenticated discv5/ENR evidence and independently
executed the unchanged RPC/Beacon candidates. A UDP-only node had no P2P TCP
claim; Auth/Hello/Status stayed NOT_TESTED. CSV preserved its first 15 columns
and appended the two source/provenance columns. Clear removed nodes, discovery,
ENR and P2P collected evidence, retaining the two migration entries. Restart and
SQLite integrity passed; no helper process remained after shutdown.

A separate temporary Java harness shared one LocalNodeIdentity across both real
providers, binding discv4 to an ephemeral IPv4 port to avoid the user's socket.
In 40 seconds it persisted 130 discv4 observations and 145 discv5 observations,
with 204 canonical identities and 34 endpoint rows having UDP != TCP. Both
providers progressed independently; shutdown took 27 ms. API inspection is
separate from provider draining, so its existing timeouts did not stall discovery.
A final repeat test ran both real providers through two fresh 20-second
start/stop cycles: cycle one produced 61 discv4 / 103 discv5 observations
(stop 19 ms), cycle two produced 71 discv4 / 79 discv5 observations
(stop 16 ms). Both used ephemeral IPv4 discv4 ports and the shared run identity.
These were bounded smoke runs, not a throughput benchmark.

#### Existing data checks

No new schema migration was introduced. Reopen/new-write tests used copies only:

| Copy baseline | Nodes | Discovery observations | P2P observations | ENR observations |
| --- | ---: | ---: | ---: | ---: |
| Representative refactor dataset | 2507 | 2507 | 13 | 0 |
| Representative ENR dataset | 53 | 64 | 4 | 26 |

All existing rows survived opening/reopening. A new validated discv5-acquired ENR
was written and recovered in each copy (one additional ENR and its source ENR
observation); raw record/provenance survived. Both retained migration versions
1/2 and passed integrity_check and foreign_key_check. No migration rollback test
was needed for a nonexistent new migration; existing transactional migration
regressions remained passing.

The original database was not opened for writes by these validation tools. Its
checksum changed during validation because the already-running user application
continued scanning/writing it; therefore checksum invariance cannot be claimed.
No attempt was made to stop that application or restore its live data.

### Limitations and follow-up observations

Transport remains IPv4-only. IPv6-only ENRs can be retained as passive evidence,
not active frontier candidates. The local ENR does not claim a public endpoint;
TorchNode is an outbound observer, not a publicly advertised discovery service.
Private keys/session secrets are never exported. Session state is intentionally
not persisted across runs; ten-minute epochs require fresh sessions.

Some application-smoke ENRs were rejected by the existing Java ETH-entry rules
(INVALID_ETH_ENTRY). They were persisted diagnostically and contributed no
trusted endpoints. Broader interpretation of those entries requires a separate
ENR-spec/fixture investigation; this milestone does not weaken existing validation.
Public responses and latency vary. API successes are demonstrated by existing
known-good fixtures/counter regressions, not assumed from silent public peers.
No P2P protocol implementation or RPC/Beacon probing heuristic was changed.

Active IPv6, NAT, GeoIP/ASN, ETH/68 and all other excluded roadmap features were
not implemented. Topic discovery is not part of this milestone.
