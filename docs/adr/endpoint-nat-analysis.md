# Endpoint / NAT evidence analysis

Date: 2026-09-29. Clean baseline: `main`, `8abe34df5f2d0c4e30a9cbf5c12aea5b1028c6d6`.

## Motivation and audit

Endpoint disagreement is evidence, not proof of NAT. Preserve canonical cryptographic identity and the existing observations rather than choose a true IP/port.

| Existing evidence | Representation and persistence | Analysis treatment |
| --- | --- | --- |
| discv4 | `DiscoveryObservation`, typed `NodeEndpoint`, source/provenance/Instant; SQLite JSON | Discovery claim; NEIGHBORS does not authenticate a returned peer |
| discv5 | Java validates returned ENRs before projecting both endpoint families | Source-specific discovery claims, separately comparable with trusted ENRs |
| ENR | Raw/unknown fields, sequence, Java structure/signature/identity results, endpoints and provenance | Only usable, identity-matched records contribute trusted endpoints |
| TCP/P2P | `_endpointAttempts` in existing `hello_json`, actual target/family/time/provenance/stage outcomes | TCP reachability, Auth and Hello are separate strengths with prerequisite checks |
| Retained Hello/Status | Existing latest P2P row preserves fields after failure | A detached old Hello cannot be assigned to a newer failed attempt |
| RPC/Beacon | Live inspection has literal URI, response evidence and address; old database flags lack precise port/time | New precise URI/source/time evidence uses existing JSON; legacy flags are not invented endpoints |
| Identity | `NodeIdentity` and `CanonicalNodes`, source observations joined by public-key identity | No IP-based merging; dual-stack remains one canonical node |

Existing generic addresses, JSON observations and latest inspection envelopes survive reopen. No tables, columns, schema version or migration are added. Derived conclusions are recomputed rather than persisted.

## Architecture and output

`analysis.EndpointAnalysis` is a pure, deterministic layer over identity-scoped original evidence. It performs no network calls, changes no endpoint, and starts no worker/helper. Output includes identity, typed evidence strengths, exact endpoints or port-only claims, timestamps, provenance, comparison fields/outcomes/reasons, conservative NAT assessment and limits. Evidence IDs link comparison sides to their full source details.

A store loader joins P2P envelopes through `nodes.node_id`; identical addresses belonging to different keys remain separate. Live inspection loads saved evidence and uses the same analyzer as CSV. When a new inspection replaces the existing latest envelope, live analysis replaces that row's old attempt projection while retaining other endpoint rows. Original source discovery observations remain intact. The original endpoint, Hello port and authenticated Status are not mutated to make comparisons agree.

API evidence is stored as `_apiEndpointEvidence` in the existing JSON envelope: URI, source, observation timestamp and provenance. It remains independent of P2P. If a later inspection has no successful API observation, previously retained API evidence keeps its original timestamp; it is not refreshed as new success. This is an extension of the existing latest-observation slot, not a general historical architecture.

## Comparison semantics

Outcomes: `MATCH`, `MISMATCH`, `INSUFFICIENT_EVIDENCE`, `NOT_COMPARABLE`, `NOT_OBSERVED`.

Compare discv4/discv5 with trusted ENR, discovery/ENR with reachable TCP, trusted ENR with authenticated RLPx, advertised TCP ports with reachable TCP ports, and Hello listenPort with its own authenticated TCP session. Endpoint comparison uses address and port; advertised port-only comparisons require the same address context. Hello compares only a port and does not supply an observed IP or family.

Different families, transports or purposes are not equivalent. IPv4 versus IPv6 is `NOT_COMPARABLE`, not a mismatch. UDP is not TCP; RPC/Beacon purpose is not P2P. Zero endpoint ports lack usable positive port evidence. Hello zero is retained as valid evidence without a positive listening-port claim. Missing sources are `NOT_OBSERVED`; failed-only attempts and missing session association remain insufficient.

Trust never follows a source-name string alone: projected `ENR` discovery rows are not used as trusted evidence without the corresponding usable Java `EnrEvidence`. Both associated identity and record identity must match the requested canonical identity. Failed TCP attempts do not produce reachable endpoints. Auth requires actual TCP PASS; Hello port claims additionally require Auth and Hello PASS in the same attempt. Legacy Hello fields without original target/stages remain detached claims, never authenticated reachability at a guessed endpoint.

## Time and bounds

Compatible evidence with missing timestamps or a gap greater than **five minutes** yields insufficient evidence. This fixed policy is conservative, not a protocol-defined expiry. Comparisons describe recorded observations and never establish current reachability or simultaneous truth. There is no refresh, change detector or time-series model. Multiple retained source/time observations are not reduced to a latest true endpoint.

Analysis displays at most 512 evidence items (newest first) and 256 comparisons; partial results are explicitly flagged and NAT remains insufficient. Malformed attempts also lower the assessment to insufficient. Existing probe concurrency, deadlines, scanner ten-worker bound, helper limits and lifecycle cleanup are unchanged.

## NAT assessment

- `NO_NAT_EVIDENCE`: compatible trusted advertisements/authenticated targets agree, with no mismatches or material ambiguity. This does not rule out NAT.
- `POSSIBLE_NAT_OR_ENDPOINT_TRANSLATION`: a compatible trusted advertisement differs from an authenticated RLPx target within the time window, without temporal/input/failed-probe ambiguity. The explanation lists stale records, multi-homing, proxies, migration and configuration as alternatives. NAT is not proven.
- `NAT_EVIDENCE_INSUFFICIENT`: default, including missing authenticated evidence, timing separation, Hello-only mismatch, failed family, incomplete data and bounded partial analysis.

No `NAT_PROVEN`, NAT score, topology classification or cause selection is introduced. A TCP-only mismatch does not authenticate the remote identity. Failed IPv6/no-route evidence does not invalidate an IPv4 match and does not identify NAT. RPC/Beacon evidence remains independent.

## UI and compatibility

Deep Inspection adds a compact dark/cyan section with assessment, scope and collapsible comparison/source details. Mismatch has neutral evidence presentation, not alarming error styling. Endpoint strings retain bracketed IPv6 formatting. Legacy `enrComparisons` API fields remain for compatibility, but simplistic flattened endpoint comparisons are replaced in the UI by the new model; existing fork comparisons remain.

CSV retains the existing 19 columns and appends `ENDPOINT_ANALYSIS` JSON and `NAT_EVIDENCE`. Structured JSON retains family, source, provenance and timestamps. No public API redesign or dashboard redesign is introduced.

## Validation

See [the validation report](../validation/endpoint-nat-analysis.md) for exact semantic cases, regression commands, isolated public observations, lifecycle and database evidence, and checkpoint requirements. Deterministic tests cover agreement, independent mismatches, family coexistence/failure, trust exclusion, stale/missing timestamps, identity isolation, port-only claims, bounds, legacy evidence retention and reopen equality. Existing geth fixtures still prove actual encrypted protocol exchanges.

Final public validation observed 382 canonical identities, 278 discv4 and 245 discv5 observations. There were 881 MATCH, zero MISMATCH and 12 insufficient comparisons; all 382 NAT assessments remained insufficient. One public authenticated Hello advertised listenPort zero, correctly NOT_COMPARABLE; no positive Hello-port comparison or public ETH Status success is claimed. Scan stops took 715/92 ms, reached ten workers and left no owned work or measured late mutation. Both fresh and historical derived results survived reopen unchanged.

Public validation is bounded and reports comparison counts, comparable nodes, Hello-port outcomes, family-specific outcomes and exact mismatch evidence where present. Public observations are not used to prove NAT. The existing observer's public IPv6 routing limitations remain observation limitations, not peer defects.

## Limitations and exclusions

Five minutes is an explicit comparison policy and cannot prove simultaneous truth. Old Hello observations can lack session targets; old RPC/Beacon booleans lack exact endpoint/time data. Existing latest P2P storage can replace its prior attempt projection; authenticated Hello/Status fields are preserved and detached safely when needed. Inspection/analysis caps can leave evidence untested or undisplayed. An apparent agreement cannot exclude translation hidden from this observer.

No GeoIP/ASN/provider lookup, historical architecture, change detection, analytics, charts/maps, Dashboard v2, distributed/cross-region measurements, propagation, alerts or ETH/68 is added.
