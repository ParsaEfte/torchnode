# Dashboard v2 validation — 2026-10-01

## Baseline, scope and audit

Initial `git status` was clean on `main`; `git log -1 --oneline --decorate` showed `8861da0 (HEAD -> main, origin/main, origin/HEAD) docs: freeze core data model`. `git rev-parse HEAD` and `git rev-parse @{u}` both returned `8861da0a48b3e4f6c2c390982d599cfb8080ec78`. Schema was expected to remain v5; no migration was added.

The previous dashboard had a scanner header, latest-projection cards, searchable/paginated latest-identity table and Deep Inspection links. Its cards used ambiguous “Discovered,” “RPC online” and “Beacon API” labels while the separate analytics page carried the rigorous historical counts. The table/search was useful and was preserved; its representative endpoint and response flags are now labelled as latest projection context. The old dashboard loaded projections on every page request but did not poll; Deep Inspection polls its active inspection separately. The analytics page uses one bounded 24-hour default or explicit interval, plus explicit all-available mode. Dashboard v2 loads its HTML/latest table first and then one bounded historical report, with a manual refresh and 24-hour/7-day/30-day selector. Filters affect only the latest table.

## Representative copy and read-only behavior

Python SQLite `Connection.backup` copied the live DB to `/private/tmp/torchnode-dashboard-representative.db`. Only that copy was served or queried. Source-copy SHA-256: `01b5a16315df2ea6c9380d7284849381a85f3aa6dcc345483feff86d644865e4`; size 410,537,984 bytes. Schema version: **5**. Counts: `nodes` 13,290; `discovery_observations` 55,580; `discovery_endpoint_index` 113,638; `enr_observations` 22,789; `inspection_runs` 6,442; `network_enrichment_lookups` 11,363; `change_events` 7. `integrity_check=ok`; zero foreign-key violations. After Dashboard HTML, JSON and asset requests, the copy retained the same SHA-256, schema version, counts, integrity and FK results. This is a checkpoint on the copy, not a general SQLite/WAL file-hash guarantee.

The explicit UTC window `[2026-09-30T00:00:00Z, 2026-10-01T00:00:00Z)` returned 38 typed metrics in a 60,459-byte JSON response: 11,085 historical identities, 44,596 discovery receipts, 10,962 identities without usable client classification, 10,952 IPv4-observed and 530 IPv6-observed identities (overlapping), 8 TCP PASS stage results, 13,544 RPC candidate attempts with 152 PASS, 8,196 Beacon candidate attempts with 26 PASS, and 7 rule-versioned ChangeEvents. Country lookup status included 895 FOUND address/dataset memberships, 7,225 DATASET_UNAVAILABLE and 2 NOT_APPLICABLE. These are observations from this copy and window, not Ethereum population estimates. The UI consumes these exact metrics rather than recalculating denominators in JSP or JavaScript.

## Map asset and dependency check

The input public-domain Natural Earth GeoJSON copy had SHA-256 `45f41865adec4f86602c2cd05c0e29cd8b437614bf2f5b5a863d12463202cae4`. `python3 tools/build-dashboard-map.py /private/tmp/torchnode-countries.geojson src/main/resources/webapp/assets/world-countries.svg` generated a 96,686-byte SVG with SHA-256 `f2ff466e37b5b7357d2b1421ec19039653648a73e2f3961ae22f4752c2157c79`. The asset has static country boundaries/codes/names only. HTTP integration fetched it from `/assets/world-countries.svg` with status 200. `dashboard-v2.js` requests only local `/analytics.json` and `/assets/world-countries.svg`; no new frontend package, CDN, map tile service, geocoder, telemetry or peer-IP transmission was added. The map source URL and public-domain terms are in the ADR.

## Deterministic validation mapping

`DashboardV2Test` starts real Tomcat against a temporary v5 database. It checks empty HTML, local assets, exact JSON zeros, repeated receipts (one identity/two occurrences), source identity count and a Deep Inspection link. `tools/check-dashboard-v2.mjs` executes the actual dashboard script in a controlled DOM with a mixed typed-report fixture. It checks conflict/unknown wording, repeated occurrence versus identity values, DATASET_UNAVAILABLE, NOT_TESTED, IPv6 observation versus reachability, candidate failures, ASN/hosting wording, local aggregate map coloring, zero changes and a delayed pre-Clear response that cannot repopulate the visual root. Existing `NetworkAnalyticsTest`, `DashboardServletTest`, `HistoricalLifecycleTest`, `EnrPersistenceTest` and `NetworkEnrichmentTest` continue to establish underlying evidence semantics and scanner/Clear ordering.

| Matrix cases | Evidence |
| --- | --- |
| A–F | `DashboardV2Test`; `NetworkAnalyticsTest.familyBucketsOverlapButCanonicalIdentityAndReceiptCountsDoNot` |
| G–H | DOM fixture provider-overlap wording; `NetworkAnalyticsTest.windowDeduplicationStageSemanticsConflictAndClear`; unknown-source fallback in `bars()` |
| I–M | DOM fixture conflict/unknown/version text; `NetworkAnalyticsTest.repeatedCompatibleClientEvidenceAndRawVersionKeepTheirOwnUnits` and conflicting-client cases; top-N evidence rule shown |
| N–Q | DOM fixture IPv6 and NOT_TESTED text; `NetworkAnalyticsTest` stage denominator/capability assertions; funnel uses original metric denominator/buckets |
| R–T | DOM fixture independent RPC/Beacon panels and candidate-failure wording; existing `NetworkAnalyticsTest` independent path assertions |
| U–V | `NetworkAnalyticsTest.enrTrustGeoStatusAndRuleVersionStayDistinct`; trusted ENR card uses `enr-trust` as returned |
| W–Z | Local map uses country code only from address/dataset memberships; `NetworkAnalyticsTest.twoAddressesOfOneIdentityRetainDifferentCountryAsnAndDatasetContexts`; DOM and HTTP asset checks; no hosting label inferred |
| AA–AB | DOM zero state; representative JSON and existing ChangeEvent taxonomy metric |
| AC–AF | Exact returned scope shown; latest inventory separated; no percentages or pie charts; DOM fixture/analytics boundary tests |
| AG–AI | DOM delayed-response Clear test; existing latch/generation/new-generation tests in Maven suite |
| AJ–AL | Representative copy hash/schema/count checks; explicit 413 path preserved; local SVG has no peer data |
| AM | Real HTTP test confirms latest table retains `/node?key=` links |
| AN | Responsive CSS at 980, 720, 620 and 430 px was inspected structurally; actual browser viewport validation is separately recorded below |

No full time series was added. The frozen typed report has no time buckets, and the current normalized timestamp predicates scan retained history. A separate bounded series query may be justified later for History UI/Reports after a concrete consumer/query measurement.

## Performance and query cost

Three localhost requests against the copy measured initial dashboard HTML at **2,703.6 / 523.9 / 492.6 ms** (first includes JSP compilation), the full 24-hour analytics JSON at **2,374.0 / 1,425.7 / 1,423.5 ms**, and the 96,686-byte static map at **2.0 / 1.5 / 1.2 ms**. The dashboard makes one full analytics request per load or explicit refresh, not one per chart; it does not poll. The latest table uses the existing canonicalized projection query and bounded page rendering.

`python3 tools/benchmark-network-analytics.py /private/tmp/torchnode-dashboard-representative.db` reproduced controlled 1,000-receipt deduplication (one repeated identity remained one identity; 1,000 diverse identities remained 1,000) and five-run representative country/ASN aggregation ranges of **34.451–125.865 ms** and **34.244–35.618 ms**. Other representative ranges in ms: identity 23.821–190.162; provider 55.086–710.724; family 106.188–365.843; client 18.623–103.060; P2P 12.381–12.562; RPC 19.080–19.749; Beacon 16.392–16.525; changes 0.009–0.218; latest snapshot 38.521–53.947. Plans include existing discovery time/source index scans, inspection-evidence hash joins, JSON virtual-table scans and grouped endpoint/enrichment scans with temporary B-trees. These one-machine timings are not production guarantees.

## Visual validation status

The local representative server and HTTP assets returned successfully, and the deterministic DOM renderer produced the intended text/map state. `sips` rasterized the generated SVG to a local PNG; its world outline was inspected at 960×480 and showed no obvious broken country geometry or clipping. This checks the static outline only.

On 2026-10-01 the validated packaged JAR was restarted on `127.0.0.1:7087` with `TORCHNODE_DB=/private/tmp/torchnode-dashboard-representative.db`. Chrome briefly became available through Computer Use. A rendered desktop-sized screenshot showed the header/scanner controls, 24-hour selector, exact UTC window, four overview cards and empty geography presentation. The controls and cards were legible, with no visible overlap in that top portion. Its accessibility tree showed the remaining sections and latest table, including an `Inspect` link, but these were not visually inspected. In this 24-hour scope the page reported one observed identity, one discovery occurrence, one inspected identity and one run; geography had zero FOUND contexts and showed an explicit empty state. The exact window displayed was `2026-09-30T12:43:36.800Z` to `2026-10-01T12:43:36.800Z` UTC `[start, end)`.

Computer Use then returned `noWindowsAvailable` during scrolling, switched browser windows unexpectedly, and returned a screenshot of a different window. The in-app browser remained unavailable. The 7-day/30-day selector, lower panels, Deep Inspection navigation, and laptop/mobile widths were therefore **not** validated by Codex browser automation. This was a partial desktop observation, not a responsive or full-page pass.

A fresh attempt on 2026-10-01 restarted the immutable validated JAR on `127.0.0.1:7087` with only `TORCHNODE_DB=/private/tmp/torchnode-dashboard-representative.db`; a direct dashboard request returned HTTP 200 and 37,649 bytes of HTML. Computer Use reported `Browser is not available: chrome` for a new Chrome session and `Browser is not available: iab` for its in-app browser. Native Chrome Computer Use could bind a window and navigate to both `127.0.0.1:7087` and `localhost:7087`, but its screenshot was uniformly gray and its accessibility tree contained Chrome controls without dashboard content. Reload and reconnect did not change that observation. No fresh dashboard visuals, selector interaction, full-page scroll, or viewport resizing could be verified by Codex browser automation.

### MANUAL USER VISUAL VALIDATION — PASS

The user reported completing rendered-page validation in a real local browser on their Mac against the Dashboard v2 server at `127.0.0.1:7087`. They inspected the complete page at approximately 1440 px desktop, 1100 px laptop and 390 px mobile widths, and tested the 24h, 7d and 30d scope controls. They reported no blocking clipping, page-wide horizontal overflow, chart/map rendering failure, unreadable or overlapping labels, broken responsive layout, unusable controls, misleading missing scope/denominator presentation, disappearing unknown states or uncontrolled table overflow. They found the map, overview cards, client evidence/distribution, discovery/address-family views, P2P funnel, RPC/Beacon panels, ChangeEvent section and node table visually usable. The narrow/mobile layout reflowed acceptably, and a representative Deep Inspection link opened successfully. This is user-provided manual evidence; Codex did not perform or witness the complete browser pass.

Codex browser automation: **BLOCKED BY ENVIRONMENT**. Manual user browser validation: **PASS**.

## Frozen-model compliance

| Required question | Answer and evidence |
| --- | --- |
| 1. Schema v5? | Yes; representative copy remained v5 after dashboard reads. |
| 2. Migration 6 avoided? | Yes; no migration or SQL schema change in the milestone file set. |
| 3. Identity unchanged? | Yes; dashboard consumes `observed-identities` and the existing canonical latest views. |
| 4. Endpoint multiplicity/provenance unchanged? | Yes; map counts address/dataset memberships, table labels its selected latest endpoint. |
| 5. Historical observations unchanged? | Yes; only existing analytics reads history. |
| 6. ChangeEvents unchanged? | Yes; existing rule-versioned taxonomy is displayed directly. |
| 7. Analytics denominators unchanged? | Yes; the dashboard uses metric denominator/unknown/buckets as returned. |
| 8. ENR trust unchanged? | Yes; trusted count comes from `enr-trust`. |
| 9. NOT_TESTED/failure unchanged? | Yes; funnel names them separately. |
| 10. Geography address scoped? | Yes; local map uses address/dataset/country memberships. |
| 11. Hosting inference avoided? | Yes; ASN is labelled registration context. |
| 12. New persisted truth? | No; SVG contains static boundaries only, visuals are recomputed. |
| 13. Dashboard state discardable? | Yes; page-local DOM state and abort controller only. |
| 14. UI semantic compromise? | None identified in the tests or reviewed labels. |

`mvn test` passed 150 tests and `mvn package` passed 150 tests, each with zero failures/errors/skips. `go test ./...` and `go test -race -count=1 ./...` each passed 43 tests in two packages; `go vet ./...` passed. `p2p-helper/go.mod` still pins go-ethereum to 1.17.6. JSP script checks, enrichment renderer checks and `tools/check-dashboard-v2.mjs` passed. `ValidateNetworkAnalytics` under `-Xmx128m` completed the representative window in 3,794 ms with the same key metric counts as the HTTP route. A real HTTP test also confirmed that a hostile client string is HTML-escaped before rendering.

For a packaged lifecycle check, the final JAR was copied to `/private/tmp/torchnode-dashboard-final.jar`, started against the representative copy, served the dashboard/JS/SVG with HTTP 200, and stopped without a shutdown exception; no listener remained on port 7087. An earlier check was invalid because `mvn package` replaced the very JAR a running JVM was loading, causing a shutdown `NoClassDefFoundError`. That procedure error was corrected by using the immutable copy; it is not counted as an application lifecycle pass.

The pre-stage audit found exactly 11 milestone-owned modified/untracked files; programmatic comparison with `dashboard-v2-files.md` returned `manifest 11 actual 11 missing [] extra []`. `git diff --check` and whitespace checks on untracked files passed. Ignored local DB/MMDB/IDE/build artifacts remain excluded. Staging, staged-diff audit, commit and push were deferred pending the visual-validation gate; the manual user validation above subsequently passed that gate.

After the fresh Codex browser attempt, repository checks were rerun: `DashboardV2Test` passed (1 test, no failures/errors/skips), `tools/check-dashboard-v2.mjs` passed, `git diff --check` passed, and the manifest comparison again returned `manifest 11 actual 11 missing [] extra []`. These checks are distinct from the user-provided manual visual pass.
