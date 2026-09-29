# Active IPv6 closure validation

2026-09-29. Implementation baseline: `7ea5666`, clean before implementation. This final phase changed only validation tests, the opt-in harness, and documentation. No additional application feature or refactor was made during closure validation.

Architecture is documented in [the ADR](../adr/active-ipv6-support.md); the [exact changed-file manifest](active-ipv6-files.md) identifies material paths. [Public evidence JSON](active-ipv6-public-evidence.json) preserves timestamps, raw validated ENRs, identity associations, source counts, endpoint diagnostics, helper stages and lifecycle measurements. Local isolated databases/logs remain under the run directories below.

## Bounded public validation

Each run uses a new database, two discv4 bootstraps, the existing discv5 bootstrap set and at most two validated historical IPv6 ENRs. Discovery runs for 45 seconds, stops completely, then restarts for 12 seconds and stops completely. P2P targets are limited to two. Application jars are snapshotted for measurement, preventing a simultaneous package operation from replacing the running artifact.

Two fresh runs are reported separately; counts must not be combined as if they were one dataset. The earlier lifecycle run demonstrated both providers producing observations. The later exact-target capture retained every IPv6 attempt diagnostic. Public peers need not return discovery rows on every short run.

| Measurement | Two-provider/lifecycle run | Exact-target capture run |
| --- | ---: | ---: |
| discv4 observations | 220 | 0 |
| discv5 observations | 157 | 46 |
| ENR source observations | 172 | 46 |
| Canonical identities | 271 | 45 |
| Endpoint rows | 271 | 45 |
| All discovery observations | 549 | 92 |
| ENR evidence records | 263 | 47 |
| Identities seen through both providers | 0 | 0 |
| Dual-stack identities | 4 | 5 |
| Authenticated returned-node discv5 observations | 12 | 2 |
| Distinct IPv4 endpoints | 545 | 88 |
| Distinct IPv6 endpoints | 7 | 10 |
| IPv4 endpoint evidence entries | 1094 | 180 |
| IPv6 endpoint evidence entries | 14 | 20 |

Distinct endpoints are counted by identity + address + family + transport + port + purpose; evidence entries include repeated source observations. Canonical identity counts are independent of addresses. Both source histories remain available. The exact-target capture had signed discv4 PING/PONG traffic but no NEIGHBORS-derived observation rows; this is reported as zero, not fabricated provider success. The earlier two-provider dataset supplies the real discv4/discv5 production regression.

Run directories: `target/ipv6-closure-final-20260929` and `target/ipv6-closure-evidence-20260929`. A preceding measurement attempt completed scanner cleanup but its final report failed because Maven replaced its classpath jar; that incomplete report is not used as closure evidence.

### Public IPv6 evidence and attempts

Two historical public IPv6 ENRs were revalidated through Java's existing trust path. The exact-target capture acquired five additional distinct public IPv6 ENRs with `signature_validation=VALID` and `identity_comparison=MATCH`. These are public discovery evidence, not proof of reachable IPv6 transport.

| New public identity prefix (full key/raw ENR in JSON) | IPv6 claim |
| --- | --- |
| `392943d0eee4a4e0` | `2a01:4f8:2200:42cb:0:0:0:2` |
| `6284346cb77a3f5f` | `2a01:4f9:3070:3013:0:0:0:2` |
| `77dff52e35f6832b` | `2607:5300:221:7e00:0:0:0:0` |
| `85b0a2b29455c03f` | `2a01:4f9:3a:3c68:0:0:0:2` |
| `8cd7adfb51bfbca2` | `2a01:4f8:241:4b81:0:0:0:2` |

Exact active discv5 targets, each attempted once in the indicated cycle:

| Cycle | Target | Result |
| --- | --- | --- |
| 1 | `[2607:fdc0:781:3:0:1:0:37]:30303` | Local UDP `sendto: no route to host` |
| 1 | `[2001:41d0:24c:600::]:30303` | Local UDP `sendto: no route to host` |
| 1 | `[2a01:4f8:2200:42cb::2]:9000` | Local UDP `sendto: no route to host` |
| 1 | `[2a01:4f9:3070:3013::2]:9002` | Local UDP `sendto: no route to host` |
| 1 | `[2a01:4f8:241:4b81::2]:9001` | Local UDP `sendto: no route to host` |
| 1 | `[2a01:4f9:3a:3c68::2]:9000` | Local UDP `sendto: no route to host` |
| 1 | `[2607:5300:221:7e00::]:9000` | Local UDP `sendto: no route to host` |
| 2 | `[2001:41d0:24c:600::]:30303` | Local UDP `sendto: no route to host` |
| 2 | `[2607:fdc0:781:3:0:1:0:37]:30303` | Local UDP `sendto: no route to host` |

Capture totals: nine IPv6 UDP attempts, nine failures, zero successes. The separate earlier lifecycle dataset recorded three IPv6 UDP attempts/failures; its bounded final diagnostic buffer did not preserve exact targets, so it is not used for exact-target claims.

TCP/P2P targets: `[2001:41d0:24c:600::]:30303` and `[2607:fdc0:781:3:0:1:0:37]:30303`, one attempt each. Both returned `TCP_NO_ROUTE_TO_HOST`. Auth, Hello and Status were **NOT_TESTED**, not PASS. No active public IPv6 discv4 success is claimed.

Independent observer connectivity check: TCP to `[2606:4700:4700::1111]:443` returned `NoRouteToHostException: No route to host`. UDP helper diagnostics independently identify the same local routing limitation. These results cannot establish peer unreachability or lack of IPv6 protocol support. **No public IPv6 transport success is claimed.**

IPv4 operation continued after IPv6 failure: the exact-target capture recorded three explicit authenticated IPv4 PING/PONG endpoint successes and two authenticated returned-node observations. The earlier lifecycle run recorded fourteen explicit IPv4 endpoint successes and twelve authenticated returned-node observations. Upstream geth routing traffic is separate from these explicit-adapter counters; restart produced ordinary discv5 observations and API inspection work even though its four explicit IPv4 endpoint queries timed out.

Java ENR validation outcomes (timeouts/cancellation are acquisition outcomes, not invalid signatures):

- Two-provider/lifecycle: BOND_TIMEOUT=14, CANCELLED=68, INVALID_ETH_ENTRY=5, REQUEST_TIMEOUT=4, VALID=172.
- Exact-target capture: INVALID_ETH_ENTRY=1, VALID=46.

## Deterministic protocol and isolation evidence

All following fixtures passed in the final gates. PASS means the named exchange or rejection occurred; public transport success is not inferred from fixtures.

| Exact behavior | Evidence | Result |
| --- | --- | --- |
| discv4 `::1` PING/PONG and FINDNODE/NEIGHBORS | `ActiveIpv6Discv4Test.ipv6BondFindnodeNeighborsAndShutdown` | PASS |
| Authenticated IPv6 EIP-868 ENR request/response | `Discv4EnrClientTest`, IPv6 loopback peer | PASS |
| IPv6 UDP socket, WHOAREYOU, authenticated handshake/session | `TestIPv6AuthenticatedSessionPingPongFindnodeAndShutdown`, production helper asserts IPv6 challenge and authenticated result | PASS |
| IPv6 PING/PONG and session reuse | Same fixture, two completed pings | PASS |
| IPv6 FINDNODE/NODES | Same fixture, distance-zero ENR plus at least eight returned routing records across packets | PASS |
| Duplicate datagram replay rejection / oversized input rejection | `TestIPv6GuardRejectsOversizedPacketsAndReplay` | PASS |
| Encrypted response request correlation, inconsistent/excessive totals | `TestIpv6AdapterRejectsUnsolicitedAndExcessiveResponses` | PASS |
| Silent-peer timeout and cancellation during handshake | `TestIpv6CancellationDuringOutstandingHandshakeAndSilentPeerTimeout` | PASS |
| IPv4 + IPv6 production helper restart, IPv6 failure leaves IPv4 working | `TestDualStackCrawlRestartAndIPv6FailureIsolation` | PASS |
| Actual IPv6 TCP connection, RLPx Auth and encrypted Hello | `TestAuthenticatedHelloAndETHStatus/::1` | PASS |
| Bidirectional compatible ETH Status over IPv6 | Same fixture, negotiated ETH/69; asserts actual local send and remote receive | PASS |
| IPv4 encrypted P2P regression | Same fixture `/127.0.0.1` | PASS |
| Independent endpoint results, IPv6 failure retains IPv4 TCP PASS and reverse | Both cases of `ActiveIpv6InspectionTest` | PASS |
| Later failed inspection preserves stored authenticated Hello/Status fields | `ActiveIpv6PersistenceTest.failedIpv6AttemptDoesNotErasePreviouslyAuthenticatedEvidence`, seeded prior evidence storage fixture | PASS |
| Provider start failure / helper failure isolation | Existing composite-provider and discv5 helper tests | PASS |
| RPC/Beacon literal IPv4 and IPv6 real HTTP requests, cancellation | `ActiveIpv6HttpTest` | PASS |

The evidence-preservation test explicitly exercises persistence with prior authenticated-evidence fields; it does not claim a new protocol exchange. Actual encrypted protocol exchanges are demonstrated by the Go fixture above. ETH/68 remains unsupported and was not faked.

## Identity, persistence and database safety

`ActiveIpv6PersistenceTest` proves one canonical identity with IPv4 + IPv6, retention of both source observations and exact provenance, repeated observation deduplication, and two distinct identities sharing IPv4/IPv6 addresses. Close/reopen retains both families, UDP/TCP purposes, ports and timestamps; integrity is `ok`, foreign-key violations are zero. No schema migration was introduced.

Both public databases were closed/reopened with unchanged canonical counts, `integrity_check=ok` and empty `foreign_key_check`. The production two-provider database retained four dual-stack identities; exact-target capture retained five. Their counts remain identity-based rather than IP-based.

Historical validation-copy actual counts: **2,943 endpoint rows, 2,842 canonical identities, 5,719 discovery observations, 532 ENR observations, 17 P2P observations**. The current application reads the copy successfully; integrity is `ok` and there are zero foreign-key violations. The older 2,507-row baseline is not substituted for these counts.

The live user database was opened read-only solely for SQLite backup; all runtime/migration/reopen checks used isolated copies. Live SHA-256 before backup and at closure is unchanged:

`d8c2b628996b2de3ac9d152a69dd7ab296a88a2e402edf20aa47266550e2b62e`

## Lifecycle and resource bounds

Each measured sequence was start → active discovery/API/inspection → stop → cleanup → restart → normal operation → stop → cleanup. The harness sampled active NodeInspector worker executors and registered HTTP calls; reflection is confined to the validation harness, with explicit JDK module opens. No production instrumentation was added.

| Run / cycle | Stop + await (ms) | Peak workers | Peak HTTP calls | Captured executors | Post-stop queue / active tasks / workers / TCP / HTTP / child processes | Delayed DB mutation |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| Two-provider / 1 | 714 | 10 | 5 | 2 | 0 / 0 / 0 / 0 / 0 / 0 | None |
| Two-provider / 2 | 12 | 10 | 10 | 1 | 0 / 0 / 0 / 0 / 0 / 0 | None |
| Exact-target / 1 | 17 | 10 | 7 | 2 | 0 / 0 / 0 / 0 / 0 / 0 | None |
| Exact-target / 2 | 11 | 10 | 6 | 1 | 0 / 0 / 0 / 0 / 0 / 0 | None |

Every captured executor was terminated, every discv4 UDP port was successfully rebound, discovery/inspection thread lists were empty, and no Go helper child process remained. Helper-owned IPv4 and IPv6 UDP sockets are released by process termination; Java discovery socket release is demonstrated by rebind, and registered TCP sockets/calls count zero. These are ownership/cleanup checks, not a claim that unrelated system sockets are absent. No scanner-owned work survives completed stop. SHA-256 of each isolated database immediately after stop matched a second reading one second later, demonstrating no late result mutation during that check.

The ten-worker bound is unchanged and reached in every cycle. No common-pool NodeInspector work remains. Existing helper-process limits, bounded frontier/requests, packet limits, timeouts and cancellation remain. IPv6 shares the scanner-run identity and does not create a second cryptographic node or worker pool.

## UI, CSV, trust and scope

The real Tomcat/JSP acquisition fixture and inline JavaScript checks pass. Dashboard and Deep Inspection render bracketed IPv6 host:port, wrap long values, expose IPv4/IPv6 source claims and endpoint-specific results/provenance. Literal IPv6 HTTP URLs use brackets. CSV retains the original 17 columns in order, appends `ADDRESS_FAMILY` and `ENDPOINT_OBSERVATIONS_JSON`, and exports IPv6/source observations losslessly. Hello listenPort remains independent evidence, including zero; it never replaces transport endpoints.

Java remains the ENR trust authority; existing invalid-signature/identity-mismatch rejection and unknown-field preservation tests pass. geth v1.17.6 remains the wire/crypto implementation. Packet/replay/correlation/resource bounds remain, and no private key/session secret is logged or persisted.

No NAT analysis, GeoIP/ASN, historical observation architecture, change detection, analytics, Dashboard v2, ETH/68 or distributed observation was added. Endpoint disagreement remains evidence only.

## Final quality gates

| Command/check | Result |
| --- | --- |
| `mvn test` | PASS: 89 tests, zero failures/errors/skips |
| `mvn package` | PASS: 89 tests; packaged jars |
| Go helper `go test ./...` | PASS: both packages |
| `go vet ./...` | PASS |
| `go build .` and discovery helper build | PASS |
| `go test -race ./...` | PASS: both affected packages |
| Pinned geth v1.17.6 v5wire and RLPx selected vectors/handshake/framing/replay tests | PASS |
| Explicit verbose IPv6 discovery and encrypted P2P fixtures | PASS |
| `node tools/check-jsp-javascript.mjs` | PASS: both JSP scripts |
| Real JSP/CSV integration | PASS in Maven suite |
| `git diff --check` | PASS |

Pinned test selection: `go test github.com/ethereum/go-ethereum/p2p/discover/v5wire github.com/ethereum/go-ethereum/p2p/rlpx -run 'Test(Vector|TestVectors|Handshake|FrameReadWrite|ReadWriteMsg|DecodeErrorsV5|EncodeWhoareyouResend)'`.

## Closure and limitations

Active IPv6 transport is proven over loopback for discv4, discv5 authentication/session/querying, TCP, RLPx, encrypted Hello, ETH/69 Status and RPC/Beacon HTTP. IPv4 production discovery, bounded restart/cleanup, endpoint evidence retention, trust and persistence invariants are demonstrated independently of unit-test totals.

Public native IPv6 transport could not be validated because this observer has no usable route. No public IPv6 success or peer fault is inferred. Public IPv6 discv4 interoperability is unmeasured. The geth routing engine remains IPv4; bounded outbound IPv6 observation uses the explicit-family adapter, not a new inbound IPv6 routing service. Scoped/interface-specific addresses and endpoint racing remain out of scope; configured endpoint caps can leave retained endpoints untested. P2P storage remains its existing latest projection rather than a new historical architecture.

ACTIVE IPV6 MILESTONE: CLOSED
