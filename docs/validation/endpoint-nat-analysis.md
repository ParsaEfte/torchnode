# Endpoint / NAT analysis validation

Date: 2026-09-29. Starting branch `main`, clean HEAD `8abe34df5f2d0c4e30a9cbf5c12aea5b1028c6d6`, equal to `origin/main`. Baseline status, decorated log and full SHA were checked before edits.

See [ADR](../adr/endpoint-nat-analysis.md), [material files](endpoint-nat-files.md) and [exact public evidence](endpoint-nat-public-evidence.json). Analysis is derived from observations, not a generic NAT detector. No NAT proof is claimed.

## Deterministic semantic evidence

`EndpointAnalysisTest` has eight tests covering all requested cases A–I:

| Case / invariant | Explicit assertion | Result |
| --- | --- | --- |
| A: compatible sources agree | discv4/discv5, signed ENR, reachable/authenticated target and Hello agree; no mismatches; NO_NAT_EVIDENCE does not exclude NAT | PASS |
| B: ENR address differs | Trusted ENR versus authenticated target MISMATCH; possible translation explanation includes alternative causes and says NAT is not proven | PASS |
| C: Hello port differs | Port-only MISMATCH, no endpoint/IP on Hello, NAT remains insufficient; zero is NOT_COMPARABLE | PASS |
| D: dual-stack | Same identity, separate family paths; family matches coexist with cross-family NOT_COMPARABLE, never false address mismatch | PASS |
| E: failed family | Failed IPv6 retains exact failure while IPv4 match remains; no global invalidation or NAT conclusion | PASS |
| F: invalid/mismatched ENR | Actual Java-decoded tampered signature and wrong identity excluded; a projected ENR source name alone cannot confer trust | PASS |
| G: timing | More than five minutes or missing timestamps → insufficient; unknown simultaneous truth stated | PASS |
| H: shared address | Two cryptographic identities remain distinct; other identity provenance cannot enter analysis | PASS |
| I: multiple sources/endpoints | Canonical count stays one for dual-stack/repeated claims, both observations survive; independent source comparisons | PASS |
| Protocol/purpose separation | UDP/TCP and RPC/P2P are NOT_COMPARABLE; Hello cannot compare to another session; Hello fields are always port-only | PASS |
| Evidence strength | TCP PASS without Auth does not create authenticated RLPx or Hello evidence | PASS |
| Persistence | Derived Result equality after reopen, precise IPv6 API URI/time survives, later failed inspection preserves Hello/Status and detaches old session association | PASS |
| Limits/input | 512 evidence/256 comparison limits flagged, malformed input stays insufficient, unavailable identity never gains another node's evidence | PASS |

Semantic test attempts are input fixtures representing previously verified stage outcomes; they do not assert that this Java analysis layer performs a handshake. Existing Go integration tests independently perform IPv4/IPv6 TCP, Auth, encrypted Hello and bidirectional ETH/69 Status, plus authenticated discv5 and replay/correlation checks. No ETH/68 implementation was added.

The real Tomcat/JSP/CSV test retains every prior assertion and adds Endpoint Analysis rendering, identity/NAT output, 21-column CSV parsing, exact JSON equality with store-derived analysis and UI/export assessment agreement. Existing 19 columns are preserved; appended columns are ENDPOINT_ANALYSIS and NAT_EVIDENCE. A numeric-node-type discrepancy in the test's in-memory JSON comparison was resolved by comparing the serialized JSON representation; actual exported values were already identical. No application behavior/assertion was weakened.

## Bounded public evidence

Opt-in `tools/ValidateEndpointNat.java` reuses the existing IPv6 lifecycle harness: a fresh isolated database, 45-second scan, full stop/cleanup, 12-second restart, full stop/cleanup. It then performs at most two sequential Deep Inspections, each with a 40-second outer deadline and existing bounded cancellation. It does not increase scanner/probe concurrency or retry until a mismatch appears.

Artifacts: `target/endpoint-nat-validation-20260929/` contains copied historical DB, fresh public DB, snapshot jar, harness classes, logs and detailed reports. None is staged. Exact public summaries and endpoint/session evidence are retained in the JSON linked above.

| Measurement | Result |
| --- | ---: |
| discv4 observations | 278 |
| discv5 observations | 245 |
| ENR source observations | 260 |
| All discovery observations | 783 |
| Canonical identities / endpoint rows | 382 / 382 |
| Distinct IPv4 / IPv6 endpoints | 765 / 16 |
| Dual-stack identities | 8 |
| Authenticated returned-node discv5 observations | 16 |
| Nodes with MATCH/MISMATCH comparable evidence | 198 |
| MATCH comparisons | 881 |
| MISMATCH comparisons | 0 |
| INSUFFICIENT_EVIDENCE comparisons | 12 |
| NOT_COMPARABLE comparisons | 1,039 |
| NOT_OBSERVED comparisons | 2,474 |
| NAT_EVIDENCE_INSUFFICIENT identities | 382 |
| Hello positive-port comparable / match / mismatch | 0 / 0 / 0 |

Counts are comparisons, not independent reachability proofs. In particular discv5 and trusted ENR can be projections of the same signed record. IPv6 claim matches do not establish active public IPv6 connectivity. No interesting public MISMATCH occurred in this bounded sample; none is fabricated. Deterministic B/C fixtures demonstrate exact mismatches and conservative explanations.

Family-specific comparisons by left endpoint family: IPv4 MATCH 841, insufficient 11, NOT_COMPARABLE 918, NOT_OBSERVED 1,305; IPv6 MATCH 40, NOT_COMPARABLE 120, NOT_OBSERVED 12. Port-only/no-endpoint comparisons are reported separately rather than assigned a fictitious family.

Two exact public P2P targets:

- `37.187.71.163:30303`: TCP TIMEOUT; Auth/Hello/Status NOT_TESTED.
- `120.78.227.96:30303`: TCP PASS, RLPx Auth PASS, encrypted Hello PASS; Status NOT_TESTED. The authenticated peer claims Hello listenPort **0** and eth/66, eth/67 capabilities. Zero is valid evidence without a positive listening-port claim, so its Hello comparison is NOT_COMPARABLE rather than a fabricated mismatch. The successful transport endpoint remains 30303. No compatible/configured Status exchange is claimed.

Observer IPv6 check: `NoRouteToHostException: No route to host`. Explicit discovery attempts: four IPv6 failures in cycle one and two in cycle two; exact endpoints/errors are retained in JSON. IPv4 authenticated endpoint successes continued (10 then 6). The two inherited historical IPv6 P2P attempts also returned TCP_NO_ROUTE_TO_HOST with downstream stages NOT_TESTED. This is a local observer/network limitation, not peer NAT evidence. No successful public IPv6 transport is claimed.

Final ENR acquisition outcomes: VALID 260, BOND_TIMEOUT 19, CANCELLED 79, REQUEST_TIMEOUT 3. Timeouts/cancellation are acquisition results, not rejected signatures. The invalid-signature/identity mismatch exclusion is demonstrated deterministically, not invented as a public rejection count.

## Lifecycle and resources

| Measurement | Scan cycle one | Restart cycle two |
| --- | ---: | ---: |
| Stop + await milliseconds | 715 | 92 |
| Peak scanner workers | 10 | 10 |
| Peak registered HTTP calls | 7 | 8 |
| Captured executors | 2 | 1 |
| Executors terminated | yes | yes |
| Queued / active tasks / workers after stop | 0 / 0 / 0 | 0 / 0 / 0 |
| Registered TCP sockets / HTTP calls | 0 / 0 | 0 / 0 |
| Go child processes | 0 | 0 |
| Discovery/inspection background threads | 0 | 0 |
| discv4 UDP port rebind | PASS | PASS |
| Database unchanged one second after stop | PASS | PASS |

Helper IPv4/IPv6 UDP sockets are released when their owning processes terminate; Java UDP release is proven by rebind. These checks refer to owned work, not unrelated system sockets. Existing ten-worker scanner bound remains unchanged. Both additional inspection executors terminated with zero queue/active/workers/TCP/HTTP/helper counts; each close took less than one millisecond at measurement resolution. The public database also stayed unchanged during the delayed post-inspection check. Analysis itself creates no socket, helper, executor, retry or additional probe.

## Persistence and historical safety

Public derived Result lists are exactly equal before/after close/reopen. An offline check with the final packaged analyzer reproduces the public comparison/NAT totals exactly; no additional network request is needed. Public integrity_check is ok; foreign_key_check is empty. Eight dual-stack identities, source observations, stage results and provenance survive. No new schema version/table/column or destructive migration exists.

The historical validation copy is readable under the new code: **2,943 endpoint rows, 2,842 canonical identities, 5,719 discovery observations, 532 ENR observations, 17 P2P observations**. Its derived Result list is unchanged across reopen. Seventeen legacy Hello claims lack original session target/stages and are retained as detached evidence, not invented authenticated endpoints. Historical integrity_check is ok and foreign_key_check is empty.

The live DB was opened read-only for SQLite backup; every application/store validation used copies. Live SHA-256 before backup and after validation remains:

`d8c2b628996b2de3ac9d152a69dd7ab296a88a2e402edf20aa47266550e2b62e`

## Final regression gates

- `mvn test`: PASS, 97 tests, zero failures/errors/skips.
- `mvn package`: PASS, 97 tests; packaged jars.
- `go test -count=1 ./...`: PASS, both helper packages.
- `go vet ./...`: PASS; Go sources/dependency pin unchanged.
- `go test -race -count=1 ./...`: PASS, both packages.
- Both Go helper builds: PASS.
- Pinned geth v1.17.6 v5wire/RLPx selected vectors, handshake/replay/framing tests: PASS. Selection: `Test(Vector|TestVectors|Handshake|FrameReadWrite|ReadWriteMsg|DecodeErrorsV5|EncodeWhoareyouResend)` with cache disabled.
- Existing actual IPv6 discovery/P2P, API, persistence and failure-isolation tests: PASS in their respective suites.
- JSP JavaScript checks: PASS for both pages; actual JSP compilation/CSV integration passes in Maven.
- `git diff --check` and staged whitespace check: PASS before checkpoint.

## Scope, limitations and checkpoint

No GeoIP/ASN/provider classification, historical architecture, change detection, analytics, maps/charts, Dashboard v2, public API redesign, distributed/cross-region observer, propagation, alert or ETH/68 was added. Existing trust/identity/cryptography/protocol semantics remain unchanged.

Limits: a five-minute policy cannot prove simultaneity; endpoint agreement cannot exclude NAT; legacy APIs lack exact endpoint/time provenance; old Hello may lack session association; the existing latest P2P slot is not a historical timeline; partial bounded analysis is insufficient. Public mismatches/positive Hello ports were absent and public IPv6 routing unavailable. No causal NAT claim follows.

The semantic, protocol, lifecycle and persistence evidence above supports implementation closure independently of test count. Final milestone closure additionally requires the requested single commit and normal push, HEAD/upstream equality and a clean tree. The resulting Git checkpoint is recorded in the final delivery report and repository log; no history rewrite or force push is authorized.
