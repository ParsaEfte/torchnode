# Data Model Freeze validation — 2026-10-01

## Baseline and scope

Initial `git status` was clean on `main`; `git log -1 --oneline --decorate` showed `dc3aac7 (HEAD -> main, origin/main, origin/HEAD) feat: add network analytics`; `git rev-parse HEAD` and `git rev-parse @{u}` both returned `dc3aac77026124b7728d47dd89b240f9c9b16c08`. This audit changed only one focused test and the three freeze documents. No live database, MMDB file, or network endpoint was modified. A new public crawl was unnecessary: the freeze questions concern retained evidence semantics and are exercised by deterministic fixtures plus a representative historical copy.

## Actual schema and representative copy

Python SQLite `Connection.backup` copied the live DB to `/private/tmp/torchnode-freeze-copy.db`; only the copy was queried. Initial and post-analytics SHA-256: `01b5a16315df2ea6c9380d7284849381a85f3aa6dcc345483feff86d644865e4` (410,537,984 bytes). This identical file hash is a checkpoint, not a general claim about SQLite/WAL behavior. The copied schema has version **5**, **39 schema objects**, **12 tables**, and **27 indexes**. `PRAGMA integrity_check` returned `ok` and `PRAGMA foreign_key_check` returned zero rows. No migration 6 or analytics schema object exists. The 16 explicit index names and all persisted structures are inventoried in the ADR.

| Table | Rows on copy | Role |
| --- | ---: | --- |
| `nodes` | 13,290 | latest projection rows |
| `p2p_observations` | 12 | latest P2P slots |
| `discovery_observations` | 55,580 | durable receipts |
| `discovery_endpoint_index` | 113,638 | typed endpoint index |
| `enr_observations` | 22,789 | durable ENR evidence |
| `inspection_runs` | 6,442 | durable occurrences |
| `inspection_evidence` | 5,912 | deduplicated payloads |
| `network_enrichment` | 11,363 | latest address/dataset lookup projection |
| `network_enrichment_lookups` | 11,363 | durable lookup records |
| `inspection_enrichment_context` | 7 | run-to-lookup references |
| `change_events` | 7 | derived comparisons |
| `schema_migrations` | 5 | schema metadata |

Relationship queries found **12,929** identities with multiple endpoint contexts, **265** identities seen by both discv4/discv5, **578** identities with both IPv4/IPv6 evidence, **1,727** addresses shared by different identities, **294** evidence hashes referenced by multiple inspection runs (**824** runs in those groups), and **611** addresses with multiple enrichment dataset keys. These are relationship counts, not population estimates. Six DISCOVERY and one INSPECTION ChangeEvents use derivation rule 1. All non-null source references resolve to the expected observation table and cryptographic identity. The inspection change uses per-attempt clocks from `timing_json` (10:19:31.918643Z and 11:48:43.429795Z), a few microseconds after the respective run starts; this is the intended fact-clock distinction.

## Reproduced analytics on the copy

`javac -cp target/torchnode-1.0-SNAPSHOT-jar-with-dependencies.jar -d /private/tmp tools/ValidateNetworkAnalytics.java` and `java -Xmx128m -cp /private/tmp:target/torchnode-1.0-SNAPSHOT-jar-with-dependencies.jar ValidateNetworkAnalytics /private/tmp/torchnode-freeze-copy.db 2026-09-29T00:00:00Z 2026-10-01T00:00:00Z` completed in **3,839 ms**. The same utility with `all` completed in **1,981 ms**. Both are local single-run times, not guarantees. The explicit window yielded 13,254 distinct observed cryptographic identities, 55,580 discovery receipts, 6,442 inspection runs, 6,318 inspected identities, 11,131 trusted ENR identities, 578 IPv6-observed identities, 10,752 distinct normalized addresses, 123 identities with usable execution-client classification, and 7 versioned change events. The latest projection snapshot independently contained 13,290 rows and 13,151 distinct cryptographic identities. These differing counts verify that latest projections, identities and historical receipts are separate units.

The window had 8 TCP PASS endpoint-stage results, 5 RLPx attempts (2 PASS, 3 TIMEOUT, plus 3 NOT_TESTED), 2 Hello attempts (1 PASS, 1 FAILED, plus 6 NOT_TESTED), and 0 Status attempts (8 NOT_TESTED). It had 13,544 RPC candidate attempts (152 PASS), 8,196 Beacon candidate attempts (26 PASS), and one successful Hello advertising four capability versions. Candidate failure is not service absence. For lookup context, 1,562 address/dataset memberships were FOUND and 9,796 were DATASET_UNAVAILABLE; 5 were NOT_APPLICABLE across the two dataset contexts. Hosting was NOT_AVAILABLE for all 11,363 address/dataset memberships. ENR trust counted 11,131 trusted and 589 diagnostic/untrusted identity memberships among 11,642 ENR-evidenced identities; those buckets may overlap when one identity has both kinds of ENR evidence. Change activity was six `ENDPOINT_FIRST_OBSERVED` and one `RPC_PROBE_OUTCOME_CHANGED`, all rule version 1. The copy's SHA-256 remained unchanged after these read-only analytics calls.

## Deterministic freeze matrix

The newly added `DataModelFreezeContractTest.identityAndOccurrencesSurviveProjectionAndReadOnlyAnalytics` crosses identity, IPv4/IPv6 endpoint history, provider overlap, latest projections, on-demand analytics, derived events, Clear, new insertion, reopen, integrity and FK checks. Existing tests cover domain-specific rules without duplicating their fixtures. The full suite passed after the new test was added.

| Case | Evidence |
| --- | --- |
| A–C | New freeze contract test; `HistoricalObservationsTest.discoveryRetainsIdentityEndpointAndFamilyEvidence`; `NetworkAnalyticsTest.familyBucketsOverlapButCanonicalIdentityAndReceiptCountsDoNot` |
| D–E | `EnrPersistenceTest.roundTripKeepsRawTypedUnknownIpv6SequenceValidationAndConflictingDiscovery`; `EndpointAnalysisTest.caseFRealInvalidSignatureAndIdentityMismatchExcluded`; `NetworkAnalyticsTest.enrTrustGeoStatusAndRuleVersionStayDistinct` |
| F | `EndpointAnalysisTest.caseCHelloPortOnlyMismatchZeroAndPrerequisites` and `incompatibleProtocolsPurposesAndHelloSessions` |
| G–H, K–L | `HistoricalObservationsTest.repeatedInspectionsRetainOccurrencesAndDeduplicateFacts`, `equalTimeLatestEndpointProjectionIsIndependentOfWriteOrder`; `NetworkAnalyticsTest.windowDeduplicationStageSemanticsConflictAndClear` |
| I–J | `DashboardServletTest.successfulDeepApisPersistCountAndExportDespiteUnavailableP2p`; `ChangeDetectionTest` independent RPC/Beacon cases; `NetworkAnalyticsTest.windowDeduplicationStageSemanticsConflictAndClear` |
| M | `EndpointAnalysisTest.caseAAgreementAndCaseBTrustedAddressDisagreement`, `caseGTimeGapMissingTimestampAndNoObservation`, `casesHICanonicalIdentityAndReopenDerivedEquality` |
| N–P | `HistoricalObservationsTest.enrichmentLookupContextKeepsDatasetAndLookupTimeSeparate`; `NetworkAnalyticsTest.twoAddressesOfOneIdentityRetainDifferentCountryAsnAndDatasetContexts`; `NetworkEnrichmentTest` |
| Q–R | `ChangeDetectionTest` source-reference and idempotent rebuild assertions; representative-copy source-reference check |
| S–U | New freeze contract test; `NetworkAnalyticsTest.analyticsCannotCreateAMissingDatabaseOrMutateExistingEvidence`, `missingLegacyClocksAreExcludedAndCounted`, `windowDeduplicationStageSemanticsConflictAndClear`; post-read copy hash |
| V | `HistoricalObservationsTest.versionFourMigrationRollsBackAndLegacyLatestHasNoInventedRun`; `NetworkAnalyticsTest.missingLegacyClocksAreExcludedAndCounted` |
| W–Y | New freeze contract test; `HistoricalLifecycleTest.clearWinsAgainstStalePendingHistoryWriteAndRestartPersists`, `closePreventsPendingGenerationFromOpeningStore`; `DashboardServletTest.clearWaitsForInFlightAnalyticsResponseAndNextQueryIsEmpty` uses a latch/barrier |
| Z–AB | New freeze contract reopen/integrity/FK assertions, representative-copy checks, schema version 5 and no migration 6 |

The test initially assumed latest projection rows equal identities and that discovery creates no ChangeEvents. Both assumptions were false by design: one identity with two address contexts yields two projection rows, and first-observed events are derived on write. The corrected assertions explicitly separate those units and prove analytics reads do not add events.

## Query plans and scaling

`python3 tools/benchmark-network-analytics.py /private/tmp/torchnode-freeze-copy.db` passed controlled 1,000-receipt cases: one repeated identity stayed **1 identity/1,000 receipts** (0.033–0.046 ms), and a diverse set stayed **1,000 identities/1,000 receipts** (0.135–0.161 ms). Representative five-run min–max timings in ms: identity 26.753–206.697; provider 58.188–533.246; family 110.137–117.041; client 23.170–24.659; capability 13.744–13.803; P2P 13.370–13.937; RPC 20.788–21.062; Beacon 17.813–18.406; ENR 42.467–49.627; country 34.849–35.198; ASN 34.326–35.144; changes 0.010–0.043; latest snapshot 38.647–41.691. `EXPLAIN QUERY PLAN` showed discovery identity/source index scans, endpoint/ENR/lookup and several run scans, hash-index evidence joins, JSON virtual-table scans, and temporary B-trees for distinct/grouping. Normalized timestamp expressions can scan retained history even for a short window. The 250,000 eligible-row and 25,000 verification-run limits fail the complete report rather than truncate it; all-available is explicit and may grow costly. These are scaling limits for later measured optimization, not a current schema blocker. Analytics adds zero workers, queues, caches or DB growth.

## Gates and decision

`mvn test` and `mvn package` each passed **149 tests**, zero failures/errors/skips. In `p2p-helper`, `go test ./...` and `go test -race -count=1 ./...` each passed **43 tests in two packages**, and `go vet ./...` passed. `go.mod` pins `github.com/ethereum/go-ethereum v1.17.6`. `node tools/check-jsp-javascript.mjs` passed dashboard and inspection JSP scripts. `node tools/check-network-enrichment.mjs` passed unavailable/miss/non-public/failure/found renderer states with no online provider/fetch. Existing deterministic lifecycle/Clear tests ran as part of Maven. Final Git/manifest checks are recorded at closure.

No concrete pre-freeze schema blocker was reproduced. Planned v0.0.1 maps, charts, Dashboard v2, History UI, reports, bounded API, richer exports and persistent-volume deployment can use the existing facts while preserving multiplicity, scope and unknowns. Future transaction, block, peer-session and cross-vantage evidence can be additive domains. Legacy untimed rows, observer sampling bias, approximate endpoint geography and non-sargable clock predicates remain documented limits. No public crawl was run because it could not establish an additional data-model invariant beyond these fixtures and retained real observations.
