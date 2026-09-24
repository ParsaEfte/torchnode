# ADR: isolated go-ethereum RLPx inspector

Status: Accepted for this milestone (2026-09-24).

## Context

TorchNode is a Java 21/Maven dashboard and discv4 scanner. Discovery stores the
advertised TCP port and 64-byte secp256k1 node ID separately from the UDP port.
`InspectionService` already runs independent TCP, RPC and Beacon checks in a
bounded pool, but neither Web3j nor the application provides authenticated
RLPx, an ETH chain head, or a local ETH Status. A TCP connection is not proof
of RLPx or ETH protocol support.

## Alternatives

* **Besu modules in the JVM:** actively maintained and Apache-licensed, but
  its transport is coupled to Besu's peer/network abstractions and a large
  Gradle dependency graph. It would also introduce version and Java-runtime
  coupling into TorchNode's small Maven application.
* **Apache Tuweni:** contains RLPx code, but the upstream repository was
  archived in 2023. It is unsuitable as a new security-critical dependency.
* **Custom Java RLPx:** avoids another runtime but requires implementing
  ECIES authentication, encrypted framing, MACs and compression. This is a
  high-risk protocol/security maintenance burden.
* **Isolated Go helper using go-ethereum:** reuses an actively maintained
  Ethereum RLPx transport and ETH packet types. It adds a Go build and a
  separately deployed binary but isolates malformed peers and keeps the Java
  scanner independent of RLPx internals.

## Decision

Use a pinned go-ethereum release in a small, one-shot helper. Java starts the
helper only for an inspected node with a valid discovered node ID and advertised
TCP endpoint. A bounded Java semaphore limits concurrent child processes. One
JSON request is sent over stdin and one bounded JSON result is read from stdout;
stderr is diagnostic-only and never shown raw to users. Both sides enforce
deadlines. Java kills a timed-out/cancelled child; the helper uses a single
socket with deadlines and closes it on all exits. Neither discovery nor RPC/
Beacon probing depends on helper success. Missing helper means RLPx stages
remain **NOT_TESTED**, not PASS.

The helper uses go-ethereum's `p2p/rlpx` for ECIES auth, encrypted frames,
MACs and Snappy. Its small application layer validates bounded devp2p Hello
and ETH Status RLP using go-ethereum's RLP codec. It advertises only ETH
69–72, the versions supported by the pinned go-ethereum 1.17.6 release.
The Go 1.25 toolchain is required to build it. Capability negotiation follows
the shared-highest-version rule. A peer without a compatible ETH capability
still has successful Auth/Hello, with ETH Status **NOT_TESTED**.

## Truthful local ETH Status

ETH Status is bidirectional; TorchNode must not send a made-up chain head.
An operator may configure a trusted, read-only local execution JSON-RPC URL
(`TORCHNODE_P2P_STATUS_RPC_URL`). Before attempting Status, the helper obtains
`eth_chainId`, `net_version`, and genesis/latest block headers from that
endpoint. It accepts only known chain profiles whose canonical genesis hash
matches the RPC header. The head number/hash/time are observed from RPC and
the fork ID is derived with go-ethereum's chain config and EIP-2124 algorithm.
For ETH/69, the helper also confirms the latest block body is retrievable and
advertises the conservative one-block availability range `[head, head]`; it
never assumes an archival history boundary. ETH/69–72 do not carry total
difficulty in their Status packet.
For all four advertised versions, the Status RLP shape is the same seven fields:
`[version, network ID, genesis hash, fork ID, earliest available full block,
latest available full block, latest available full block hash]`. ETH/70–72
change other messages, not this Status shape. `latestHash` must not be labeled
as the chain head. This follows the pinned geth packet type and the
[Ethereum ETH wire specification](https://github.com/ethereum/devp2p/blob/master/caps/eth.md).
If this context is absent, stale, inconsistent or unsupported, Auth/Hello may
still pass, but ETH Status remains **NOT_TESTED** with a structured reason.
The configured RPC source is independent of the inspected peer; the resulting
P2P Status is compared rather than assumed to match. No account, admin,
debug, transaction or state-changing method is used.

## Deployment and limits

The Java jar remains buildable without Go, preserving existing dashboard,
discovery, RPC, Beacon and CSV behavior. To enable RLPx, build the helper with
the documented Go toolchain and set `TORCHNODE_P2P_HELPER` to its absolute path.
The helper's go-ethereum version is pinned in `go.mod`/`go.sum`; upgrades need
protocol interoperability and security review. The helper is not a general
scanner: it accepts only the single discovered IP/TCP/node-ID input passed by
TorchNode. Timeouts and process concurrency bound resource consumption.

Only networks with a supported canonical chain profile and a trustworthy local
RPC context can reach ETH Status PASS. Other nodes may still yield authentic
RLPx Auth/Hello observations. A PASS means that particular stage completed on
the current connection; OBSERVED, MATCH/MISMATCH and VERIFIED are separate
network-evidence semantics.

go-ethereum's RLPx transport enforces a 24-bit (under 16 MiB) encrypted-frame
bound; this helper additionally rejects Hello and Status payloads over 64 KiB,
limits capability/client fields, and runs in a disposable process. The transport
frame is decrypted before the smaller application limit is checked. ETH/68 and
older are intentionally not advertised by this pinned release; support would
require a separately reviewed compatibility implementation. No public-client
interoperability is asserted by the deterministic local fixture tests.

## Validation boundary (2026-09-24)

The pinned geth 1.17.6 binary built from source (`darwin-arm64/go1.26.8`) was
run as a real, no-discovery Mainnet client. TCP, Auth and Hello passed, it
advertised `eth/69–72` and `snap/1`, and ETH/72 was negotiated. Its local RPC
was explicitly configured as trusted context for this test, but its unsynced
head failed freshness validation (`LOCAL_HEAD_STALE`). No local Status was sent
and no remote Status was received. Geth `--dev` was evaluated but it disables
P2P listening, so it cannot be used as the non-fixture network peer.

Previously observed public `bor/v2.9.0/linux-amd64/go1.26.4` peers completed
Auth/Hello but offered only `eth/66–68` and `snap/1`, so Status was not tested.
`210.188.255.27:30303` completed Auth, then sent authenticated devp2p
Disconnect code 4 (`TOO_MANY_PEERS`) after TorchNode sent Hello. In this pass,
recorded Reth 2.6.0 endpoints `23.155.252.226:30303` and `5.56.0.208:9999`
both closed during Auth; recorded Nethermind 1.39.3 endpoint
`152.53.17.75:9999` timed out during Auth. Those client names come from earlier
scanner observations, not the current failed P2P exchanges. Besu and Erigon
were not evaluated. No non-fixture bidirectional ETH Status exchange or
multi-client Status interoperability has been proven; this milestone is open.
