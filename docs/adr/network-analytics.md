# Network Analytics

## Decision

All analytics connections use SQLite URI `mode=ro` plus `PRAGMA query_only=ON`. A missing path fails without creating a database file.

Network Analytics is an on-demand, read-only query layer over schema v5. It adds no migration, aggregate tables, cache, worker, queue, or retention policy. A window query is limited to a positive UTC interval of at most 31 days. Every report, including an explicit all-available report, rejects a scope with more than 250,000 timestamp-eligible evidence rows; it does not return a truncated aggregate. SQL statements have a 15-second timeout. The verification reducer rejects a scope with more than 25,000 potentially relevant inspection rows before returning a report. The HTTP route surfaces either limit as 413. A separate `Snapshot` queries only `nodes` latest projections; it is never substituted for a historical window.

The measured v5 copy (55,580 discovery receipts, 6,442 inspection runs) completed full window reports in roughly 2–4 seconds in this continuation. This supports on-demand aggregation for the present dataset. Query plans still scan several retained tables because expression-normalized ISO timestamps do not use a leading time index. The row preflight and timeout are safety limits, not a constant-work guarantee. If growth makes a bounded window too expensive, measure again before adding a time index or rebuildable cache. Persistent aggregates would add invalidation, Clear, restart, migration, and rule-version complexity without present evidence of need.

## Evidence and ownership

`discovery_observations`, `discovery_endpoint_index`, `enr_observations`, `inspection_runs`, `inspection_evidence`, `network_enrichment_lookups`, `inspection_enrichment_context`, and `change_events` are durable evidence or derived event history. `nodes`, `p2p_observations`, and `network_enrichment` are latest projections/caches. `inspection_evidence` contains content-hashed payloads; `inspection_runs.timing_json` retains occurrence-specific clocks. Inspection-to-enrichment references preserve the actual lookup context used by a run. `change_events` is derived under `derivation_version`, with source observation references; analytics does not create events. Failed and mismatched ENRs are diagnostic. Only validated ENRs contribute endpoint observations.

Discovery and ENR are identity associated. Typed endpoints, normalized addresses, country and ASN are endpoint/address scoped. RPC and Beacon candidate attempts are inspection scoped and independent of P2P. Source/provider is provenance, not truth. The Endpoint/NAT reducer stays separate; NAT classifications are not inferred from analytics. Network verification is recomputed by the existing comparison rule from inspection evidence; single-source OBSERVED never becomes VERIFIED.

## Scope and units

The historical scope is `[startInclusive, endExclusive)` using actual observation clocks and UTC `Instant` parsing. Run counts use `started_at`; P2P stages use endpoint-attempt `observedAt` from `timing_json`; RPC/Beacon candidate attempts use `attemptedAt`; client API responses use response `observedAt`; ENR and discovery use their own `observed_at`; enrichment uses `looked_up_at`; changes use `current_observed_at`. No row ID creates chronology. Equal clocks have equal window membership and make no causal claim. Missing clocks are excluded, with untimed run, endpoint-attempt and RPC-response counts reported separately.

Each typed metric identifies its counting unit, denominator meaning, unknown count, evidence rule, bucket semantics, and category buckets. Each bucket count is a numerator. Identity metrics deduplicate by canonical cryptographic `node_id`; occurrence metrics intentionally retain repeated receipts or attempts. Provider, address-family, capability, version, geographic, and verification buckets can overlap because an identity/address can have multiple observations. Such bucket sums are not automatically a population denominator; no percentages are emitted. Inspection run and inspected-identity counts are separate metrics. Exact endpoint purpose, family, and provider are preserved in endpoint occurrence buckets; each P2P stage has a separate metric. There is no implicit all-time/latest/window mix.

Scope-selector decision: the time interval and all-available mode are the only global selectors needed for this initial report. Discovery source, address family, endpoint purpose, and protocol stage are metric dimensions, named in buckets or metric identifiers and eligibility rules. A global filter would change the denominator of unrelated domains without a defensible meaning. Identity deduplication is fixed to canonical `node_id` for identity metrics. More general filtering is deferred until a concrete query needs it.

`observed-identities` is the union of timestamped discovery, ENR and inspection identities in the window. Client unknown is the difference between that union and identities with usable Hello or RPC implementation evidence. A successful authenticated Hello and a responding RPC `clientVersion` are eligible, and distinct implementations for one identity become `CONFLICT`. Version buckets use the existing slash parser behavior, retain `UNKNOWN_VERSION`, and can overlap over time. Raw strings remain in inspection history. Client source provenance remains in those original records. No canonical client identity is written.

The execution-client classification buckets are mutually exclusive per canonical identity within a report: each identity with usable evidence has one implementation label or `CONFLICT`; identities lacking evidence remain in `unknown` against the observed-identity denominator. The version buckets are a separate overlapping top-100 membership view. RPC chain/network and Beacon client buckets use responding identities as denominators and account for missing values as unknown; repeated or conflicting values can overlap.

P2P funnel rows count endpoint stage diagnostics. PASS, FAILED, TIMEOUT, NOT_TESTED and identifiable observer limitations are separate. A downstream NOT_TESTED is not a failure. IPv6 endpoint observation is not reachability; only an IPv6 TCP PASS contributes the active reachability metric. Hello capabilities are included only from successful Hello and remain advertised capabilities, not exercised Status. RPC/Beacon candidate failures mean no supported candidate responded during that attempt, not service absence. Their results never enter P2P denominators.

ENR trust requires structural validity, valid signature and identity MATCH. Country is approximate address network context. ASN identifies network registration context, not hosting. Hosting stays `NOT_AVAILABLE` absent independent evidence. Geographic status buckets retain FOUND, NOT_FOUND, NOT_APPLICABLE, LOOKUP_FAILED and DATASET_UNAVAILABLE separately by dataset key. Country/ASN distributions count address/category memberships per dataset, so one identity can contribute multiple places or ASNs; changed dataset evidence is not node movement. Change-event buckets include `derivation_version`, keeping first-observed events distinguishable from transitions. Event count is not an instability score.

## Research limits and freeze readiness

Observation count differs from node count. Endpoint and address counts differ from identity count. An inspected subset differs from discovered identities. No observation is not a negative observation; NOT_TESTED is not FAILED. Advertised IPv6 is not reachable IPv6; advertised capability is not exercised protocol. Latest projection counts are not historical distributions. Change counts are not instability scores. Routing-table, bootstrap/source, observer vantage, temporal, candidate-port, IPv6 connectivity, and inspection selection biases remain uncorrected. Results should be described as “among identities observed by TorchNode under this measurement scope,” never as an Ethereum population estimate.

The existing durable observations support a bounded Dashboard v2, maps/charts, historical reports, a later bounded Public API, and richer exports without schema redesign. The identity, endpoint, observation, inspection, enrichment, change, and analytics roles are sufficiently separated. Provenance and timestamps support reproducible queries, subject to explicitly excluded untimed legacy evidence. Transaction propagation can later be a new observation domain. No desired future UI/export feature justifies schema changes now. **Network Analytics found no required pre-freeze data-model redesign.**

The pre-freeze review answers the planned questions explicitly:

1. Dashboard v2 can consume the typed bounded report and separate latest snapshot.
2. Maps and charts can use typed endpoint history and address-scoped enrichment without changing schema; they must retain address/dataset multiplicity.
3. Historical reports can query timestamped discovery, ENR, inspection, enrichment and versioned change history under explicit windows.
4. A future Public API can expose bounded typed reports; HTTP authentication/rate policy is a later API concern.
5. Richer exports can serialize bounded aggregate reports separately from the existing node CSV.
6. Canonical identity, typed endpoint, occurrence, inspection, enrichment, derived change and on-demand analytics have separate owners and counting units.
7. Per-domain clocks and provenance suffice for reproducible eligible-evidence queries; untimed legacy evidence is explicitly excluded and counted.
8. The representative v5 copy and deterministic tests revealed no schema deficiency requiring repair before freeze. Expression-clock query cost is a performance limitation to remeasure as data grows, not a present data-model blocker.
9. Dashboard visuals, exports, hosted access and alerts do not justify schema additions in this milestone.
10. Transaction propagation can be added later as a new observation domain without rewriting the existing domains.
