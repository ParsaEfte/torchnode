# Active IPv6 support

Date: 2026-09-29. Baseline: `7ea56662fea97093a9e637e3836a4962e0df3aac`, verified clean before edits.

## Decision and original boundary audit

Extend the existing identity/observation/provider architecture. Cryptographic identity is canonical; addresses are claims and probe targets. A failed endpoint is not an invalid identity. No NAT inference, additional protocol implementation, ETH/68, or dashboard redesign is introduced.

| Component at baseline | Boundary found | Extension |
| --- | --- | --- |
| `NodeIdentity` / `CanonicalNodes` | Public-key identity and canonical counts already independent of IP | Retained unchanged |
| `NodeEndpoint` / `DiscoveryObservation` | Address string plus family, transport, port, purpose; immutable provenance/timestamp/identity | Validate numeric literals, normalize typed addresses, preserve family and every source observation |
| ENR decoder/evidence | Already decodes signed `ip`, `ip6`, `tcp`, `tcp6`, `udp`, `udp6`, including IPv6 port fallback; invalid signature/identity cannot create trusted observations | Retained trust path and raw/unknown-field evidence; trusted IPv6 can now be selected |
| Java discv5 provider | Explicitly filtered projected endpoints to IPv4 | Project both families only after Java validation |
| Go discovery engine | IPv4 geth routing socket; explicit IPv4 codec adapter for ENRs whose upstream preferred IP is IPv6; IPv4-only bootstrap/frontier/packet filters | Generalize the existing adapter, add one optional IPv6 socket, select both signed tuples, isolate failures |
| discv4 | Java datagram sockets can carry IPv6; NEIGHBORS parser rejected 16-byte addresses; observations always labeled IPv4; bootstraps split at colons | Accept 4/16-byte wire addresses, type family, parse bracketed bootstrap authorities, bracket bond keys/logs |
| EIP-868 acquisition | Numeric IPv4 regex and IPv4 bind | Validated numeric literal and matching-family bind; same authentication/correlation rules |
| TCP / P2P | Java sockets and Go `DialContext("tcp", net.JoinHostPort(...))` already support IPv6; Deep Inspection chose one endpoint | Bounded sequential observation-based selection; retain separate results and actual targets |
| RPC / Beacon | Literal-IP URLs were concatenated without brackets | Central validated URL formatting, bounded address selection in Deep Inspection, unchanged port sets |
| SQLite | TEXT addresses and JSON endpoint observations already preserve IPv6 and provenance; identity-based views deduplicate endpoint rows | No schema migration; extend existing P2P JSON metadata for endpoint attempts |
| UI / CSV | Dashboard and enode/HTTP strings could be ambiguous; IPv6 labeled passive; CSV exported a primary IP plus ENR | Bracket endpoints, show family/source/attempts, wrap long values; append two CSV columns |
| Tests / helper protocol | IPv4-only fixtures and intentional passive-IPv6 assertions; helper input already has separate IP/port | Actual IPv6 fixtures and dual-stack regressions; JSON input remains compatible |

Audited colon splitting, regexes, Inet4 assumptions, socket binds, URI construction, endpoint keys, deduplication, SQL, JSP/JavaScript, helper JSON, bootstraps, and diagnostics. Remaining colon splitting in EIP-868 separates a diagnostic code from text, not an address. No IP-based canonical deduplication was introduced.

The pre-implementation review concluded that the generic endpoint and observation schema already represented IPv6 losslessly. No address schema migration was justified. Historical data was tested only through a SQLite backup.

## Address semantics and endpoint selection

`EndpointAddress` accepts numeric IP literals without DNS. IPv4 dotted quads are range checked; IPv6 is parsed with Java's address parser after lexical validation. Scoped literals are rejected because ENRs contain no observer-specific scope identifier. Explicit IPv4-mapped IPv6 retains its 16-byte representation; an IPv4 value exposed by the runtime is labeled IPv4. Mapped addresses are not evidence of native IPv6 connectivity.

`NodeEndpoint` normalizes numeric addresses and checks the supplied family. Raw ENR bytes/text and original decoded fields remain available. Combined IPv6 address/port strings always use brackets, including keys, URLs, diagnostics, bootstrap authorities, and enode links. IPv4 key spelling is unchanged. Unspecified and multicast claims can be retained as evidence but are excluded from active P2P/API selection.

Deep Inspection selects distinct TCP/P2P endpoint observations, IPv4 then IPv6, at most four per family, sequentially in the existing worker. It includes the latest newly acquired usable ENR and previously persisted trusted observations. UDP and TCP ports are never substituted. Every completed attempt carries its endpoint, family, transport, port, time, provenance, stage outcomes and authenticated protocol evidence. The summary selects the most progressed actual exchange; all attempted endpoints remain visible. Hello listenPort, including zero, never changes an endpoint.

Deep Inspection API probes retain the existing RPC ports (8545, 8546, 30303) and Beacon ports (5052, 5051, 9000), with at most two observed addresses per family. They stop at a successful API as before and run independently of P2P. The scanner's lightweight API sweep retains its primary observation projection and ten-second per-node budget; full multi-endpoint P2P inspection remains an explicit Deep Inspection operation, as before.

## Discovery and transport

### discv5

The geth dependency remains **v1.17.6**. The existing IPv4 geth engine/routing socket is retained. `endpoint_client.go` generalizes the previous IPv4 adapter and delegates packet encoding, WHOAREYOU, handshake proofs, encryption and sessions to geth's `v5wire.Codec` for either socket family. The IPv6 client is an outbound observation client, not a new public routing service.

Remote IPv6 selection loads the signed `ip6` and `udp6`, falling back to `udp` only when `udp6` is absent. Invalid, zero, unspecified, multicast or mapped IPv6 UDP targets are not used. An IPv6-only bootstrap is accepted. Both tuples of a dual-stack peer can be queried without changing its identity or trusting geth as the Java ENR authority. Returned raw ENRs still pass `EnrDecoder` structural/signature/identity checks before projection and persistence.

A cycle retains the **64 total endpoint-query** limit across both families, 256-entry frontier, existing response deadlines, five-packet/16-record response bounds, 1280-byte datagram ceiling, and bounded replay state. It does not allocate a worker per family. There are at most three discovery UDP sockets per helper: existing IPv4 engine and explicit adapter plus one optional IPv6 adapter. Failure to open the IPv6 socket disables that transport and leaves IPv4 available. Each endpoint failure is isolated. All sockets close on cancellation; ten-minute session recycling and one scanner-run identity remain intact. Endpoint attempt/success/failure counters have a fixed family/code key space.

### discv4

The wire representation accepts four- or sixteen-byte IP values. Java's existing dual-stack datagram socket is used; the NEIGHBORS parser, family labels, bootstrap parser and bond keys are now compatible. PING/PONG, FINDNODE/NEIGHBORS and the authenticated EIP-868 exchange have real `::1` fixtures. The IPv6 sender does not advertise an unrelated IPv4 source address. Individual bootstrap transport failures do not terminate the provider.

This does not upgrade discv4's existing trust model: a NEIGHBORS claim remains discovery evidence, not authenticated reachability of the returned node. EIP-868 retains packet signer, request hash, expiration and Java ENR checks. Java configurations that disable IPv6 sockets cannot actively use IPv6; a transport failure is reported without invalidating IPv4 observations. Public IPv6 discv4 success has not been demonstrated.

### TCP / RLPx / APIs

No cryptography, encrypted framing, Hello negotiation or ETH Status semantics changed. The existing Go TCP dialer already accepts literal IPv6. Deterministic IPv4 and IPv6 peers complete TCP, Auth, encrypted Hello and bidirectional compatible ETH Status. Observer network errors now distinguish no route, unreachable network, unavailable family and unavailable local address. They do not claim peer protocol incompatibility. Prerequisite failure keeps downstream stages NOT_TESTED.

RPC and Beacon use bracketed IPv6 HTTP authorities and actual loopback HTTP tests. Independent API evidence remains available when P2P fails.

## Persistence and evidence compatibility

No tables or columns were added. `discovery_observations.endpoints_json` retains identity, full addresses, explicit family, transport, purpose, ports, source, provenance and timestamp. `enr_observations` retains raw records and all validation outcomes. Canonical counts still use `CanonicalNodes`, not IP addresses.

The existing latest-P2P JSON slot can carry reserved metadata: `_evidenceType: endpoint-inspection`, `_endpointAttempts`, and `_helloObservedAt`. Actual authenticated Hello fields remain at their existing positions. A failure-only inspection contains no fabricated Hello fields and does not erase a previously authenticated Hello or Status. Consumers must use recorded stage outcomes, not merely the existence of `hello_json`, to infer success. Each inspection retains its bounded per-endpoint results; this is not a new historical observation architecture.

CSV retains all existing columns in order and appends `ADDRESS_FAMILY` and `ENDPOINT_OBSERVATIONS_JSON`. The appended JSON includes every stored source claim for the identity rather than flattening dual-stack evidence into the legacy IP column. Dashboard counts remain canonical; Deep Inspection exposes source observations and active attempts.

## Lifecycle and security

Validation found that the baseline scanner spawned API work in the common thread pool behind its ten-worker pool. Stopping the outer workers left network work running. The fix runs those probes directly in the existing ten workers with the existing ten-second budget, closes registered sockets and HTTP calls on stop, and cancels queued work. It does not add another pool. Deep Inspection closes active sockets/HTTP calls, interrupts workers, and waits for helper termination; the existing four-process P2P semaphore and helper deadlines remain.

No private keys, session keys, ephemeral secrets or secret configuration are logged or persisted. IPv4 and IPv6 discovery use the same supplied scanner identity. ENR trust, request correlation, replay protection, packet bounds and returned-record limits remain in force.

## Validation

See [the validation report](../validation/active-ipv6-support.md) for final command results, deterministic evidence, exact bounded public counts, stop/restart measurements, and database preservation checks. The opt-in `tools/ValidateActiveIpv6.java` uses only a supplied historical copy and a new validation database, refuses to overwrite a prior result database, scans for 45 seconds followed by a 12-second restart, and attempts at most two historical public IPv6 P2P targets.

Deterministic coverage includes numeric parsing/formatting/mapped addresses, identity deduplication and same-address different identities, trust rejection, IPv6 discv4 and EIP-868, geth IPv6 authentication/session reuse/multi-packet NODES, response correlation and hostile input, replay/packet limits, dual-stack restart, IPv6 failure isolation, encrypted IPv6 P2P/Status, real HTTP requests, cancellation, persistence/reopen/integrity, JSP rendering and CSV.

Final validation includes a fresh two-provider run with 220 discv4 and 157 discv5 observations, 271 canonical identities and four dual-stack identities; its stops took 714 ms and 12 ms. A separate exact-target capture acquired five new validated public IPv6 ENRs and recorded nine IPv6 discovery failures plus two TCP failures, all caused by the observer's lack of route. The capture had discv4 PING/PONG traffic but zero discv4 observation rows; no counts are conflated between runs. Both sequences reached ten workers, terminated all captured executors, left zero queued/active tasks, TCP sockets, HTTP calls or helper processes, and had no measured late database mutation.

Public IPv6 evidence is distinct from public IPv6 transport success. This observer reported no IPv6 route. Loopback integrations prove active protocol support; public failures do not establish that the peer is unreachable from other observers.

## Limitations and later work

- No public native IPv6 transport success is claimed on this observer; public discv4 IPv6 interoperability remains unmeasured.
- The geth routing engine remains IPv4; IPv6 observation uses the bounded explicit-family adapter and frontier. There is no new inbound IPv6 routing service.
- Inspection caps can leave additional retained endpoints untested. There is no endpoint racing or routing policy beyond bounded sequential selection.
- Scoped/link-local addressing requiring an interface identifier is not supported by ENR-derived targets. Mapped literals are preserved but are not native IPv6 transport proof.
- Existing latest-P2P storage remains a latest observation projection; this milestone does not introduce historical timelines.
- NAT classification, GeoIP/ASN, analytics, maps, distributed observers, alerts, ETH/68 and other later milestones remain out of scope.
