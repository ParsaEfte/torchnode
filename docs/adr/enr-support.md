# ENR evidence over discv4

Status: accepted. This extends the discovery architecture; discv4 remains the only discovery provider.

## Architecture and acquisition

Previously discovery observations and P2P/API evidence were preserved independently, but ENR fields were unavailable. ENR now follows:

`discv4 ENR exchange → bounded RLP parser → v4 signature/identity validation → EnrEvidence → optional DiscoveryObservation → SQLite → inspection/CSV`.

`Discv4EnrClient` uses isolated IPv4 UDP sockets and a local secp256k1 acquisition identity. It authenticates packet hashes and recovered packet signers, obtains a matching Pong, answers reciprocal Pings, waits 500 ms for endpoint bonding, then sends ENRRequest (type 5). ENRResponse (type 6) must come from the expected IP/UDP endpoint and node identity and echo the signed request packet's hash. Unsolicited, wrongly signed, and mismatched responses are rejected. Packet handling supports the EIP-8 trailing-data rule without weakening record parsing.

This client does not change the existing FINDNODE/NEIGHBORS implementation. Scanner callbacks submit additional evidence asynchronously. Deep inspection also acquires ENR independently of TCP, RLPx, RPC and Beacon. An unsupported or silent peer remains a valid discovered node. Silence is reported as a bond/request timeout: absence of a response cannot prove lack of ENR support.

There are four acquisition workers, a queue of 64 and four shared network permits across scanner and inspection. Each exchange has a six-second deadline; network-slot waiting is bounded to one second. A maximum 4096-entry identity/discovery-endpoint cache reuses pending requests and results for ten minutes. Saturation returns BUSY without blocking the discovery callback. Cancellation closes sockets and completes pending work; scanner stop joins acquisition workers before final persistence. Clear discards pending acquisition/cache state.

## Records and validation

`EnrRecord` contains deterministic unpadded base64url `enr:` text, decimal uint64 sequence, identity scheme, compressed public key, derived cryptographic identity/DHT address, typed standard fields, endpoint values and every original key/value pair. Exact received RLP is also stored as hex. Unknown/binary keys are represented by key hex and exact value RLP hex; printable keys additionally have a display name. Unknown semantics are never inferred. Raw data is not the only source of truth.

`CanonicalRlp` rejects truncation, noncanonical lengths/single-byte encodings, trailing record bytes and pathological nesting. `EnrDecoder` enforces the EIP-778 300-byte limit, an outer list, alternating string keys/values, unique ascending binary keys, canonical uint64 sequence and uint16 ports, exact IP lengths, and required identity scheme. The maximum depth is 32; the 300-byte limit also bounds key count and value sizes. Discv4 packets are limited to 1280 bytes. Rejected oversized packets are not accumulated. Received records within that packet bound can retain their raw bytes even when validation fails.

The supported identity scheme is `v4`: compressed secp256k1 key (33 bytes), valid curve point, and signature (64-byte r || s) over Keccak-256 of the RLP list excluding the signature. Existing Bouncy Castle/web3j crypto is used; scalars and low-S signatures follow the pinned geth verifier. Canonical TorchNode identity remains the uncompressed 64-byte public key; the Keccak-derived 32-byte DHT address is separate metadata. No discovery identity is replaced.

`EnrEvidence` distinguishes received, decoded, structurally valid, signature VALID/INVALID/NOT_TESTED and identity MATCH/MISMATCH/NOT_AVAILABLE. A valid signature alone is not independent verification. Only a structurally valid, correctly signed, identity-matching record contributes a neutral source=ENR discovery observation. Invalid signatures, unsupported schemes and mismatches retain diagnostics/raw/decoded evidence without contributing trusted endpoints. Exceptions remain local to the acquisition/decoder.

## Endpoint and sequence semantics

ENR endpoints are advertisements, not confirmed reachability. They use existing `NodeEndpoint` family, transport and purpose fields. Both discv4 TCP=30305 and ENR TCP=30303 survive with their own observation source, timestamp and provenance. Existing NodeRecord endpoint projection and P2P selection continue to use discovery; there is no ENR endpoint fallback. Hello listenPort, RPC and Beacon evidence are unchanged.

IPv4 `ip`, `tcp`, `udp` and IPv6 `ip6`, `tcp6`, `udp6` are decoded, stored and displayed. Where tcp6/udp6 are absent, EIP-778's tcp/udp fallback is represented in IPv6 endpoint evidence; typed raw fields still show their absence. IPv6 evidence never activates discovery, P2P, RPC or Beacon probing.

Sequence is stored as a decimal string to preserve the full unsigned 64-bit range. Records and attempts are appended rather than overwriting older evidence. Inspection/CSV select the highest validated identity-matching sequence, using observation time to resolve equal sequence ordering. A failed new acquisition does not hide previously validated evidence. Different validated records at the same sequence are explicitly displayed as conflicting records, not treated as newer canonical truth.

The standardized `eth` entry is `[[forkHash, forkNext], ...]`: a four-byte fork hash and canonical uint64 next fork value. Extension elements remain raw and are ignored according to the entry specification. Inspection compares ENR fork hash/next with actual ETH Status when available, and ENR IPv4/TCP/UDP with discovery. Results are MATCH/MISMATCH/NOT_AVAILABLE, never VERIFIED from one source. Network classification is unchanged.

## Persistence and UI

Schema migration **2** adds `enr_observations` with expected node identity, exact timestamp/provenance, outcome, sequence, signature/identity status, raw RLP and a complete JSON representation. It is transactional and additive. Existing node, discovery and P2P tables survive; no database is recreated. Saving an ENR and its eligible neutral observation is atomic. Exact duplicate delivery is deduplicated, while distinct records/times remain. Reopening reconstructs typed/unknown fields. Clear All Data deletes ENR/discovery/P2P/node collected rows atomically and preserves migrations/schema.

Deep inspection uses existing source badges, View full and Copy controls. It displays validation, identity, sequence, advertised endpoints, passive IPv6 evidence, fork data, unknown values, raw bytes and comparisons. ENR unavailable remains normal; malformed evidence has a concise diagnostic. CSV preserves all existing columns and appends ENR, sequence, signature and identity comparison. Advertisements are never displayed as successful probes.

## Deferred work

Discv5, active IPv6, ENR serving/advertising a local TorchNode record, NAT analysis/traversal, GeoIP/ASN, ETH/68 and new RPC/Beacon heuristics are outside this milestone. Endpoint disagreement alone is not evidence of NAT. Unknown identity schemes are diagnosed and preserved without custom verification. Public UDP failures remain transport observations and do not invalidate independently successful P2P/API evidence.

## Protocol references

- [EIP-778: Ethereum Node Records](https://eips.ethereum.org/EIPS/eip-778): canonical record, size, fields, v4 signature and official test vector.
- [EIP-868: ENR discovery v4 extension](https://eips.ethereum.org/EIPS/eip-868): request/response and endpoint proof.
- [Ethereum discovery v4](https://github.com/ethereum/devp2p/blob/master/discv4.md): signed packet framing, correlation and EIP-8 compatibility.
- [Ethereum ENR eth entry](https://github.com/ethereum/devp2p/blob/master/enr-entries/eth.md) and [EIP-2124](https://eips.ethereum.org/EIPS/eip-2124): fork ID representation.
- Pinned [geth v1.17.6 identity validation](https://github.com/ethereum/go-ethereum/blob/v1.17.6/p2p/enode/idscheme.go) and [discv4 exchange](https://github.com/ethereum/go-ethereum/blob/v1.17.6/p2p/discover/v4_udp.go): verification and bonding compatibility reference.

## Validation

Deterministic tests include the EIP-778 fixture, signed IPv4/IPv6/unknown/eth fixtures, hostile RLP/crypto, authenticated loopback exchanges, saturation/cancellation, scanner survival, transactional migration/rollback/integrity, conflict persistence, independent inspection stages and rendered HTTP UI/CSV/Clear flows. Unit tests require no public network. Bounded public-network validation and representative database migration results are recorded below after execution.

### Bounded public and migration smoke, 2026-09-28

Two scanner start/stop cycles ran against a temporary database (20 seconds each). Cycle one produced 95 node projections and seven validated ENRs; after Clear All Data, cycle two produced 46 nodes and two validated ENRs. Cancellation records on stop are expected; scanner shutdown completed. Observatory, Deep Inspection, compiled JSP script syntax, and the 15-column CSV loaded. Clear emptied nodes/discovery/P2P/ENR tables and retained both migration markers. Temporary database integrity was `ok`.

Examples from cycle one (all v4, signature VALID, discovered identity MATCH):

| Advertised IPv4 | TCP / UDP | Sequence | ETH fork hash / next | Unknown keys |
| --- | --- | --- | --- | --- |
| 148.251.142.145 | 30315 / 30315 | 1784880838 | 0x22d523b2 / 0 | snap |
| 192.3.170.82 | 30303 / 30303 | 1781257878636 | 0x07c9462e / 0 | snap |
| 180.216.98.135 | 30404 / 30404 | 1790228309718 | 0x07c9462e / 0 | none |
| 46.166.143.70 | 30303 / 30303 | 1738362890124 | 0x07c9462e / 0 | snap |

Corresponding canonical identities, in table order:

- `d4540d28966e49cdb88127e4dff1e6f952c0ccfaca63baf78eddeba16e143932b586657072cefa1f72ece460479b595cf2e67b2893bc954685f2ec350fe05e26`
- `fcf81c6263d8e7d8e3e0f90f91cf0a77a94d4f7fda2e3685c0e20691a6ac166d796c8fc2d9b4313f385563521dd409240693a4070058eac8183018d498410e73`
- `470d535c8c3f30b361f6da5b394c0f54726b91c591c4e7ee4641c633d7bc308a39e64836effa56a216305d26da4ce37569f2efaf78deec2fef9000aaa26946f5`
- `481c0fc197d8cfa0375ca27c6e4e9bdde2ff4031ec24cd0658197f0737c5398764f03f9b7beed1db3de52056ccce3dc2b877262922bc9c92004c4ed4f9fd4506`

These examples contained no IPv6 fields or endpoint conflicts; deterministic signed fixtures cover those cases. Additional public peers timed out without being classified as invalid nodes.

Read-only backups of the user's legacy database and a representative version-1 database were migrated and reopened. Each retained 2507 readable nodes, 2507 discovery observations and 13 attached P2P observations; both migration versions were present and `PRAGMA integrity_check` returned `ok`. The original database was not opened for writes.

Additional bounded inspections completed Hello against Reth, Nethermind and a Geth-family peer. Reth on discovery TCP 30316 advertised Hello listenPort=0 and only eth/66–68; Auth and Hello passed and Status stayed NOT_TESTED. A separate peer advertised bsc/1, bsc/2 and eth/68 with an empty Hello client string; client identification remained unavailable. Successful Hello and ENR evidence survived API failures/timeouts. A cached validated ENR remained visible when a later exchange timed out.

Validation commands: `mvn test` (70 tests), `mvn package` (70 tests and packaged JAR), `go test ./...`, `go vet ./...`, `go build .` in p2p-helper, `node tools/check-jsp-javascript.mjs`, and `git diff --check` all passed. Shell commands used the repository-required `rtk` prefix; Go commands used `GOCACHE=/private/tmp/torchnode-go-build`. Known-good loopback RPC/Beacon observations still persisted and each dashboard counter became one despite unavailable P2P. No new API heuristics were added.

Existing public API probing can take substantially longer than ENR acquisition because it tries the existing candidate ports and multiple methods with individual deadlines. That timeout behavior is unchanged and is potential follow-up work outside this milestone.
