# History UX and reports validation (2026-10-01)

## Baseline and audit

`main` was clean at `78fd1877aa0058d3691c071f98ccef4ef8b2698e`, equal to its configured upstream before edits. The read-only UX audit is recorded at the start of `docs/adr/history-ux-reports.md`.

## Deterministic checks reproduced

`HistoryReportsTest` exercised empty and repeated discovery receipts, source/family differences, two-page cursor navigation, fractional UTC ordering, an address/dataset enrichment lookup, bounded report HTML with scope and denominator text, malformed/oversized scope rejection, Clear's empty report and deleted identity 404, and Dashboard report navigation. `tools/check-history-ux.mjs` exercised DOM grouping by exact endpoint/source/purpose, exact page occurrence counts, first/last wording, distinct runs, and hostile strings as text nodes. Existing analytics, dashboard, enrichment, and lifecycle tests remained in the Java suite. The implementation did not change `NetworkAnalytics` counting rules.

## Representative SQLite copy

SQLite `Connection.backup` copied the prior representative DB to `/private/tmp/torchnode-history-reports-validation.db`. Initial SHA-256 was `01b5a16315df2ea6c9380d7284849381a85f3aa6dcc345483feff86d644865e4`. Schema version was **5**. Counts were `nodes=13,290`, `discovery_observations=55,580`, `discovery_endpoint_index=113,638`, `enr_observations=22,789`, `inspection_runs=6,442`, `network_enrichment_lookups=11,363`, `change_events=7`; `integrity_check=ok`. The same SHA-256 was observed after report and evidence GETs on the copy. A separate disposable copy was used for a rendered Deep Inspection because opening that route starts a real inspection and writes a new run.

An identity with 162 discovery observations was queried with `limit=20`: HTTP 200, 12,425 bytes, 0.050 s in one local request. A 20-row ENR request returned HTTP 200, 48,274 bytes, 1.568 s while a report request ran concurrently. A bounded report request returned HTTP 200, 72,399 bytes, 1.556 s under that same concurrency. A later local run returned 72,510 bytes in 1.473 s, with a repeat request at 1.364 s. The initial Deep Inspection response was 57,006 bytes in 0.023 s. These are local observations, not repeatability or production latency claims. No index or schema change was introduced.

## Rendered page and print status

Chrome Computer Use rendered the report at `127.0.0.1:7088/reports/network`; its screenshot showed the title, scope, report controls, and first metric cards without visible overlap. The accessibility tree exposed all metric tables, units, denominators, unknowns, and methodology. Chrome also rendered a representative Deep Inspection on a disposable copy; its accessibility tree exposed the current summary, diagnostics, historical overview, discovery/ENR/enrichment links, run list, and a trusted ENR. Current P2P was unavailable while historical evidence remained visible. A screenshot of the discovery-history section showed readable but noisy alternating ENR/discv5 rows; the final code groups compatible receipts across the fetched page to address that finding. Print preview initially showed a white 14-page report with scope and methodology, but an ordinary metric card split across pages. Print CSS was revised to keep ordinary cards together while allowing long tables to break by row. Automated control of the final browser preview was inconsistent, so a final automated print pass is **not** claimed. The user then inspected the final local UI on the disposable copy at desktop, about 1100px, and about 390px, expanded discovery, ENR, and run details, and checked the report Print preview. They reported: “Checked all views; no issues.” This is user-provided manual visual and print validation, not an agent automation result.

## Gates and limitations

Java `mvn test` passed 152 tests, and `mvn package` passed 152 tests after the final code edits. `go test ./...` and `go test -race -count=1 ./...` passed 43 tests in two packages; `go vet ./...` passed. `tools/check-dashboard-v2.mjs`, `tools/check-jsp-javascript.mjs`, `tools/check-network-enrichment.mjs`, and `tools/check-history-ux.mjs` passed in their most recent runs. `p2p-helper/go.mod` pins go-ethereum `v1.17.6`. The deterministic checks target representative semantics; they do not constitute one standalone test for every item in the requested A–AT matrix. Exact staged manifest verification and Git closure are recorded in the final milestone report rather than claimed here before they occur.

## Data Model Freeze audit

The schema remains v5 and migration 6 is absent. Cryptographic identity, endpoint multiplicity, historical occurrences, latest/history separation, distinct run IDs despite shared payloads, NOT_TESTED stages, ENR trust, derived ChangeEvents, NetworkAnalytics metrics, and address/dataset-scoped geography retain their existing semantics. No hosting inference, report-specific persisted truth, destructive grouping, invented chronology, or gap-based disappearance claim was introduced. The Public API and richer export milestone remains outside this work.
