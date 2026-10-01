# Network Analytics validation

## Baseline and schema

The continuation adopted the audited 13-file work in progress on `main` at `e5c6fb86239397b81ac88a6ac9a1eb499f0bf582`, equal to upstream. No migration 6 was added. The analytics service opens SQLite with `PRAGMA query_only=ON`; no aggregate or cache writes occur.

The live source database was copied using Python SQLite `Connection.backup` to `/private/tmp/torchnode-network-analytics-continuation.db`. Only this copy was queried. Initial SHA-256 was `01b5a16315df2ea6c9380d7284849381a85f3aa6dcc345483feff86d644865e4`; after pure analytics it was identical. Schema version was 5, and 39 schema objects remained. `PRAGMA integrity_check` returned `ok`; `PRAGMA foreign_key_check` returned zero violations. The matching file hash is additional evidence for this checkpoint, not a general SQLite/WAL read-only proof.

Copy counts before measurement: `nodes` 13,290; `discovery_observations` 55,580; `discovery_endpoint_index` 113,638; `enr_observations` 22,789; `inspection_runs` 6,442; `inspection_evidence` 5,912; `network_enrichment_lookups` 11,363; `inspection_enrichment_context` 7; `change_events` 7. Counts of `nodes`, discovery, inspection, and change rows were unchanged after reads. Relevant existing indexes: `idx_discovery_identity_time`, `idx_discovery_source_time`, `idx_discovery_endpoint_time`, `idx_enr_identity_time`, `idx_inspection_identity_time`, `idx_inspection_time`, `idx_enrichment_address_time`, `idx_change_identity_time`, and change-order expression indexes. No new indexes were added; measured whole-report latency and query plans did not justify a migration at this size.

## Representative measurement

The explicit UTC interval was `[2026-09-29T00:00:00Z, 2026-10-01T00:00:00Z)`. The copied data produced 13,254 observed identities and 55,580 discovery receipts. Identity provider buckets were ENR 11,131, discv4 3,009, discv5 10,407, with 265 identities observed by both discv4 and discv5; 103 identities lacked provider evidence in this window. Provider receipt counts were ENR 22,049, discv4 12,365, discv5 21,166. Identity family buckets were IPv4 13,151 and IPv6 578; 578 identities had both families observed and 103 lacked endpoint-family evidence. Distinct normalized addresses were IPv4 10,217 and IPv6 535. These are overlapping, observer-local measurements, not network population statistics or proof of IPv6 reachability.

There were 6,442 inspection runs across 6,318 identities. ENR evidence covered 11,642 identities; 11,131 had trusted ENR and 589 had diagnostic or untrusted ENR. Trusted ENR endpoint families covered 11,130 IPv4 and 578 IPv6 identities, with overlap; one trusted-ENR identity lacked typed endpoint-family evidence. Five identities had timestamped P2P endpoint attempts across eight endpoint attempts. TCP had 8 PASS; RLPx had 2 PASS, 3 TIMEOUT and 3 NOT_TESTED; Hello had 1 PASS, 1 FAILED and 6 NOT_TESTED; ETH Status had 8 NOT_TESTED. There was no successful active IPv6 TCP attempt in this copy. The Status denominator is zero actual attempts, with eight NOT_TESTED diagnostics.

Execution implementation evidence was usable for 123 of 13,254 identities, leaving 13,131 unknown. Buckets included Geth 80, Reth 19, Nethermind 9, Besu 1 and other observed implementations. These are evidence-subset counts; no Ethereum-wide percentage is claimed. One successful authenticated Hello advertised `eth/66`, `eth/67`, `eth/68`, and `eth/69`; none of these advertised capabilities counted as ETH Status PASS. Exact version buckets retained parser output and raw evidence remains in inspection history.

There were 13,544 timestamped RPC candidate attempts: 152 PASS, 3,601 no supported response, 9,791 no TCP connection. There were 8,196 Beacon candidate attempts: 26 PASS, 184 no supported response, 7,986 no TCP connection. These attempts covered 6,307 RPC and 3,394 Beacon identities respectively. Among 152 identities with timestamped RPC responses, three lacked chain ID and seven lacked network ID; value buckets may overlap across observations. Beacon client implementation evidence covered 26 identities (Lighthouse 18, Nimbus 3, Prysm 4, teku 1). Failed candidates were not treated as service absence. Verification was recomputed using existing comparison rules from timestamp-eligible run evidence: 146 identities had OBSERVED, 12 VERIFIED, two MISMATCH and 6,160 UNAVAILABLE run outcomes; 26 comparison occurrences were MATCH and four MISMATCH. Overlapping identity buckets may occur across runs.

Of 11,363 address lookup records, country and ASN each had 1,562 FOUND under the configured dataset, 9,796 DATASET_UNAVAILABLE under the unconfigured dataset, and five NOT_APPLICABLE across dataset contexts. The dataset key remains attached to every bucket. Country and ASN distributions count normalized address/category memberships, never canonical node country or hosting. No hosting provider was inferred. Seven persisted change events occurred: six `ENDPOINT_FIRST_OBSERVED` and one `RPC_PROBE_OUTCOME_CHANGED`, all rule version 1. They were not interpreted as instability.

The latest projection snapshot had 13,290 rows and 13,151 distinct identities, demonstrating that latest projection and historical interval are different datasets. This copy had zero untimed inspection runs, endpoint attempts, and RPC responses. The synthetic legacy test inserted one of each untimed case and verified explicit exclusion accounting.

## Public probe

The inspection-only identity had unknown provider, endpoint-family, and client evidence in this one-second window; zero new discovery receipts did not become a negative provider observation.

A separate copy was used for two bounded inspections of one previously discovered public endpoint on 2026-10-01 at 10:47:51 UTC. The runs lasted about 14 ms and 3 ms. The measurement window `[10:47:51Z,10:47:52Z)` contained one inspected identity, two inspection runs, zero new discovery receipts, zero P2P endpoint attempts, six RPC and six Beacon candidate attempts with no TCP connection, no client evidence, zero enrichment lookups, and one persisted rule-version-1 RPC probe outcome change. This is a comparison of candidate outcomes, not an assertion of peer instability or service absence. The environment or peer cause of unsuccessful candidates was not identified. The copy's earlier discovery and enrichment history is independent of this probe. No proprietary dataset was downloaded and no online enrichment was used.

## Performance and bounds

The final packaged-code `ValidateNetworkAnalytics` run took 3,104 ms under `-Xmx128m` on the same representative copy; its identity, attempt, enrichment, and change counts matched the measurements below.

`python3 tools/benchmark-network-analytics.py /private/tmp/torchnode-network-analytics-continuation.db` used five query repetitions. Controlled 1,000 repeated receipts produced **1** identity and **1,000** occurrences (identity query 0.034–0.047 ms); 1,000 diverse receipts produced **1,000** identities and **1,000** occurrences (0.145–0.174 ms). On the representative copy, final min–max timings in ms were: identity 24.672–236.275, provider 54.283–901.622, family 100.798–438.705, client 18.309–84.282, capability 12.448–12.622, P2P 12.501–12.723, RPC 18.475–19.900, Beacon 15.786–15.902, ENR 40.300–468.404, country 33.492–61.790, ASN 33.482–34.438, changes 0.009–0.665, latest snapshot 37.382–40.692. The full Java report took 3,863 ms after unknown-denominator corrections, and completed under `-Xmx128m` in another representative run. Expression-normalized clock predicates scan discovery, endpoint, ENR, enrichment, and inspection rows using an index or table scan; inspection evidence then uses its hash index, with temporary B-trees for distinct/grouping. These local times are not guarantees. The report rejects scopes over 250,000 timestamp-eligible evidence rows and verification windows over 25,000 potentially relevant runs; it never returns partial buckets. SQL returns aggregate buckets, except the bounded verification reducer. There are zero new workers, queues, caches, tables, or database growth from analytics.

## Gates and limits

In this continuation, final `mvn test` and `mvn package` each passed 148 tests with zero failures/errors/skips. `go test ./...`, `go vet ./...`, and `go test -race -count=1 ./...` passed (43 Go tests); `p2p-helper/go.mod` pins go-ethereum to 1.17.6. `node tools/check-jsp-javascript.mjs` passed the dashboard and inspection JSP scripts; `node tools/check-network-enrichment.mjs` passed the enrichment renderer states. The JSON and HTML-route model regression test matches a direct query. The latch test holds an analytics response while Clear waits on the same monitor; the next query is empty. Existing lifecycle tests prevent stale generation persistence and store reopening after close; the analytics reopen test sees only new evidence. The copied database hash remained unchanged after pure analytics reads. Final file audit results are recorded at closure.

The analytics UI is a separate bounded route; the ordinary dashboard and Deep Inspection do not execute its historical queries. The existing node CSV contract was left intact. Rich aggregate export remains for Public API + Richer Exports.

The pre-stage audit found exactly 13 changed/untracked repository files, all in `network-analytics-files.md`; programmatic manifest comparison reported `manifest 13 actual 13 missing [] extra []`. `git diff --check` passed, and the eight untracked source/document files had no trailing whitespace. `git ls-files` returned no tracked `torchnode.db`, GeoLite2 MMDB, `.idea`, or `target` paths. The representative copy remained schema v5 with 39 schema objects, unchanged counts and SHA-256, `integrity_check=ok`, and zero foreign-key violations after final reads.

## Deterministic matrix audit

The following maps every case from the milestone matrix to assertions or controlled validation performed in this continuation. Domain tests are cited when they establish the underlying evidence invariant consumed by analytics. `N/A` means the implementation deliberately has no cache.

| Cases | Coverage | Evidence |
| --- | --- | --- |
| A–D | Covered | `NetworkAnalyticsTest.familyBucketsOverlapButCanonicalIdentityAndReceiptCountsDoNot`, `twoAddressesOfOneIdentityRetainDifferentCountryAsnAndDatasetContexts` |
| E–J | Covered | `NetworkAnalyticsTest.windowDeduplicationStageSemanticsConflictAndClear`, `familyBucketsOverlapButCanonicalIdentityAndReceiptCountsDoNot` |
| K | Covered | `NetworkAnalyticsTest` asserts IPv6 endpoint evidence without IPv6 TCP PASS |
| L–N | Covered | `NetworkAnalyticsTest.enrTrustGeoStatusAndRuleVersionStayDistinct`; `EnrPersistenceTest.roundTripKeepsRawTypedUnknownIpv6SequenceValidationAndConflictingDiscovery` and `enrSaveIsAtomicWithNeutralObservationInsertion` establish only usable ENR creates trusted endpoint observation |
| O–S | Covered | `NetworkAnalyticsTest.windowDeduplicationStageSemanticsConflictAndClear`, `repeatedCompatibleClientEvidenceAndRawVersionKeepTheirOwnUnits`; raw platform text cannot impersonate a different implementation |
| T–V | Covered | `NetworkAnalyticsTest.windowDeduplicationStageSemanticsConflictAndClear` asserts successful Hello capability, NOT_TESTED downstream, and no Status PASS |
| W–AC | Covered | Same stage test asserts TCP PASS/FAILED, stage-specific denominators, downstream NOT_TESTED, and zero attempted Status |
| AD–AG | Covered | Same test asserts independent RPC PASS and Beacon candidate failure; `ChangeDetectionTest.RpcAndBeaconAreIndependentAndFlappingRemainsFactual` establishes side-path independence; API failures are labeled candidate outcomes |
| AH | Covered | `NetworkAnalyticsTest.verificationUsesIndependentSourcesAndNeverPromotesOneSource` plus `NetworkVerificationTest` OBSERVED/MATCH/MISMATCH/VERIFIED rules |
| AI–AN | Covered | `NetworkAnalyticsTest.enrTrustGeoStatusAndRuleVersionStayDistinct`, `twoAddressesOfOneIdentityRetainDifferentCountryAsnAndDatasetContexts` |
| AO–AP | Covered | `ChangeDetectionTest.EnrichmentDatasetSuccessionIsNotNodeMovement`; hosting `NOT_AVAILABLE` assertions in `NetworkAnalyticsTest` |
| AQ–AT | Covered | `NetworkAnalyticsTest.enrTrustGeoStatusAndRuleVersionStayDistinct` groups first-observed and transition events by rule version; `ChangeDetectionTest.repeatedClientEvidenceAndSuccessfulHelloAreComparedOnlyWithCompatibleFacts` prevents repeat transition inflation |
| AU–AV | Covered | `NetworkAnalyticsTest.windowDeduplicationStageSemanticsConflictAndClear` asserts exact start inclusion and end exclusion |
| AW | Covered | `NetworkAnalyticsTest.missingLegacyClocksAreExcludedAndCounted` |
| AX | Covered | Equal-time observations in `familyBucketsOverlapButCanonicalIdentityAndReceiptCountsDoNot` have deterministic membership; `HistoricalObservationsTest.equalTimesOrderByIdAndClearRemovesHistory` and `ChangeDetectionTest.EqualTimestampIsNotCausalAndPaginationIsStableAcrossReopen` establish no causal claim |
| AY–BA | Covered | `NetworkAnalyticsTest.windowDeduplicationStageSemanticsConflictAndClear` checks snapshot/window divergence, empty DB, and post-Clear empty report |
| BB | N/A | No analytics cache exists |
| BC | Covered | `DashboardServletTest.clearWaitsForInFlightAnalyticsResponseAndNextQueryIsEmpty` uses latches and verifies Clear is blocked on the analytics request's monitor before release |
| BD | Covered | `HistoricalLifecycleTest.clearWinsAgainstStalePendingHistoryWriteAndRestartPersists` and `closePreventsPendingGenerationFromOpeningStore` |
| BE | Covered | `NetworkAnalyticsTest.oversizedWindowFailsExplicitlyWithoutAPartialAggregate` proves both modes fail above 250,000 rows; 31-day invalid scope is rejected; verification fails before output above 25,000 candidate runs |
| BF | Covered | `DashboardServletTest.analyticsJsonSerializesUtcScopeAndMatchesDirectQuery` compares JSON and the HTML route's report model to a direct query |
| BG | Covered | Representative-copy full report, controlled/representative benchmark, and recorded query plans |
| BH–BJ | Covered | `NetworkAnalyticsTest.analyticsCannotCreateAMissingDatabaseOrMutateExistingEvidence` checks missing-file behavior and observation/projection/change/schema counts; representative-copy hash/schema/count/integrity checks |
| BK | Covered | Controlled 1,000-repeat benchmark asserts one identity and 1,000 occurrences |

The 25,000 verification-run SQL `LIMIT 25001` is only a detection bound: row 25,001 raises an error. It never computes a metric from a truncated prefix. The 250,000-row preflight likewise rejects the whole report. The Java verification reducer is the only historical row decoder; all other metric paths use SQL aggregate results. Normal dashboard and Deep Inspection paths do not call this report.

## Query-cost audit

The copy has 209,819 timestamp-eligible rows across the six preflight tables in this interval. `ALL_AVAILABLE` is explicit only (`mode=all`) and completed in 3,388 ms on the copy; the default `/analytics` request is a 24-hour window with the same 250,000-row safety limit. The normal dashboard and node/inspection routes have no analytics query call. The preflight itself may scan retained history because normalized clock expressions are not sargable with all existing indexes; its cost can grow with retention even for a short window. The 15-second statement timeout and explicit scope rejection are operational bounds, not proof of fixed work. No index was added from this dataset's measurements.

| Query family | Scanned evidence and observed plan | Maximum relevant dimension on this copy |
| --- | --- | --- |
| Identity union, discovery receipts/provider/overlap | `discovery_observations` via identity/source indexes plus `inspection_runs` and `enr_observations`; distinct/group temp B-tree | 55,580 discovery receipts |
| Address family, address, purpose | `discovery_endpoint_index` scan joined by observation PK; group/distinct temp B-tree | 113,638 typed endpoint entries |
| Inspection and client/capability/funnel/RPC/Beacon | `inspection_runs` scan/index, `inspection_evidence` hash lookup, bounded `json_each` expansion per stored run | 6,442 runs; 13,544 RPC and 8,196 Beacon candidate attempts |
| Verification | At most 25,001 candidate run rows read from `inspection_runs` joined to evidence; row 25,001 fails the whole report | 6,442 candidate runs here |
| ENR trust | `enr_observations` scan and group/distinct temp B-tree | 22,789 ENR rows |
| Country/ASN/hosting | `network_enrichment_lookups` scan and group/distinct temp B-tree; dataset key retained | 11,363 lookups |
| Change activity | `change_events` scan grouped by rule version/type | 7 events |
| Snapshot | `nodes` scan with distinct temp B-tree, separate latest-projection route | 13,290 rows |

The 31-day duration limit alone does not bound row count. The preflight counts timestamp-eligible discovery observations, typed endpoint entries, ENR observations, inspection runs, enrichment lookups and change events; above 250,000 it fails before producing a report. Repeated receipts remain occurrences but cannot inflate identity counts. The top-100 version/geographic buckets explicitly state the omitted tail; they never claim to be a complete distribution. The only Java history reducer is verification, and its 25,000-run limit is a failure boundary, never a truncation boundary.
