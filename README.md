# TorchNode Ethereum node crawler

TorchNode discovers Ethereum execution nodes, inspects their public services, and stores the results in SQLite.

## Build and run

```shell
mvn package
java -jar target/torchnode-1.0-SNAPSHOT-jar-with-dependencies.jar
```

Open `http://localhost:8080`. Scanning, stopping, inspection, filtering, statistics, and CSV export are available in the dashboard.

Set `TORCHNODE_PORT` or `TORCHNODE_DB` to override the default port and database path.

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
the `p2p_observations` SQLite table.
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
necessarily the canonical chain head. As of 2026-09-24, a non-fixture ETH
Status exchange has **not** been observed. A locally built geth 1.17.6 Mainnet
node completed Auth and Hello with ETH/72 negotiated, but its unsynced head was
rejected as `LOCAL_HEAD_STALE` before TorchNode sent Status. The P2P milestone
therefore remains open.
