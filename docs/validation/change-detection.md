# Change detection validation

## Methodology and claims

An observation is a timestamped factual receipt, decoded record or probe
outcome. A measurement attempt is the operation against a stated target;
success belongs only to the stage that completed. Comparable observations
share canonical cryptographic identity and the domain's source, target,
family, transport, purpose and prerequisite rules. A first-observed event has
no predecessor. A factual transition compares two real compatible values.
A `ChangeEvent` is a **derived statement about recorded observations under
defined comparison rules**, not direct wire evidence. Each persisted event
records derivation rule version 1 and references its source observation IDs.

`NOT_TESTED` means the stage was not attempted. `FAILED` and `TIMEOUT` are
outcomes of actual attempts. `UNAVAILABLE` is retained as recorded but is not
silently converted into a peer failure; `NOT_OBSERVED` is not a persisted
stage state. Missing legacy evidence has no invented time or value. Observer
policy errors and local routing limitations are distinct from peer behavior.
An attempt-outcome transition reports what this observer measured at one
target. **Absence of an observation is not automatically evidence of
absence.** Source references resolve to the original history, including
provenance. Endpoint events are scoped to address/family/transport/purpose and
discovery source; RPC, Beacon, and P2P are independent.

Single-observer changes are changes in evidence observed by that methodology,
not necessarily globally synchronized Ethereum node transitions. These
samples cannot support network-wide client shares, endpoint disappearance,
service absence, node movement from MMDB revisions, ASN-based hosting claims,
or peer fault from observer restrictions. A transition timestamp marks the
later observation, not the exact instant of any underlying peer action.

## Deterministic tests

`ChangeDetectionTest` exercises repeated identical Hello and API evidence,
version versus implementation transition, normalized capability set and
successful-Hello prerequisite, strict P2P target/family matching, RLPx
attempt transitions, `NOT_TESTED`, explicit observer permission restriction,
independent RPC/Beacon outcomes and flapping. It verifies trusted ENR sequence
advance after out-of-order insertion, repeat/invalid/mismatched ENR exclusion,
identity/source/family-scoped first endpoint and IPv6 evidence, no inferred
disappearance, equal-time ambiguity, bounded pagination, deterministic
rebuild/reopen, dataset-version non-movement, Clear, atomic observation/change
write rollback, and migration 5 failure rollback. Existing History and ENR
tests continue to protect the source facts, while `DashboardServletTest`
compares the bounded change HTTP response to repository data.

`HistoricalLifecycleTest` uses latches and generation barriers to prove a
blocked old completion cannot reopen storage or add an inspection/change after
Clear or shutdown. A new generation can write normally. No arbitrary sleep is
used for those assertions. Migration 5 injects a failure when inserting the
version marker; the v5 table rolls back and historical rows remain. Reopening
and a subsequent valid migration succeed.

## Representative database copy

The live `torchnode.db` was never opened for validation writes. A SQLite
`.backup` copy was made at `/private/tmp/torchnode-change-closure.db`.
Its pre-migration SHA-256 was
`bce7fb67edf0aef8209f4ae104f2f29ed7eafa87e9846a4c3ebd60f308c18ebf`.
The live data had legitimately grown since the Historical Observations
milestone; the following are this copy's actual counts.

| Fact | Before v5 | After v5 |
| --- | ---: | ---: |
| Node projections | 13,290 | 13,290 |
| Discovery observations | 55,579 | 55,579 |
| ENR observations | 22,788 | 22,788 |
| Latest P2P slots | 11 | 11 |
| Enrichment cache / lookup rows | 11,363 / 11,363 | 11,363 / 11,363 |
| Typed endpoint entries | 113,636 | 113,636 |
| Inspection runs / normalized evidence | 6,441 / 5,911 | 6,441 / 5,911 |
| Inspection-enrichment references | 6 | 6 |
| Migration version | 4 | 5 |
| Derived change events | absent | 0 |

Migration 5 created `change_events` and identity/time, precise inspection-time
and precise ENR-time expression indexes. It left every
historical and latest row intact. The expression ordering is necessary because
valid `Instant.toString()` timestamps can have different fractional precision.

The final packaged-code upgrade open took 109 ms and second open less than 1 ms on this
machine. Both reopened queries succeeded. `PRAGMA integrity_check` returned
`ok`; `PRAGMA foreign_key_check` returned no violations. No legacy timestamp,
provenance or change event was fabricated. A later explicit rebuild for one
identity in the **copy** derived source-referenced events from its existing
timestamped history; that is separate from migration backfill.

The existing History validation utility then reopened that final copy twice:
loading all 13,290 latest rows took 723 ms and 467 ms, a bounded identity
inspection page took 5,481 µs and 280 µs, and a bounded endpoint page took
277 µs and 119 µs. These are machine/copy measurements, not service promises.

## Bounded public observations

Two 10-second background inspections on the upgraded copy selected canonical
identity `fbb92391327aa96fd0f2fd8c7ef85988c792255d023ed092aec531ec8a894bc5073c5b00052e885b58c59458353c978a46a6479d604a68f48b5196ee18d08046`,
IPv4 target `148.251.253.243:30308`. Run IDs were
`04255fc6-7a0f-4d67-996f-c946ed9caac5` and
`5e9061ac-115d-4cbf-aa0e-f5c7b3c77549`. Three run occurrences for this
identity remained queryable after both writes, including its older historical
run. P2P stages were `NOT_TESTED`. RPC was `UNAVAILABLE` at the run summary
level on attempted candidate ports; Beacon was `NOT_TESTED` in the first run
and `UNAVAILABLE` in the second. The derived event references those two new
runs and reports **RPC candidate probe outcome `FAILED` →
`NO_TCP_CONNECTION`** at `http://148.251.253.243:30303`, IPv4. This is a
factual local attempt-outcome comparison, not a claim that the peer removed
RPC. The copy reopened with clean integrity and foreign keys.

An explicit copy-only rebuild for the identity used in the earlier History
validation found three currently retained runs and one RLPx Auth attempt
transition `TIMEOUT` → `PASS` at a compatible IPv4 target, plus five
source-scoped endpoint-first events. Those are current-copy facts; this copy
does **not** contain the earlier two restricted `Operation not permitted`
occurrences from the former isolated validation copy. The deterministic
restricted-observer fixture proves permission-denied attempts do not create a
peer-state transition. The earlier restricted run remains documented as an
observer limitation; it was never rewritten as peer failure.

Separate bounded scanner windows of 45 and 12 seconds used a fresh isolated
public-scan database. They observed 140 canonical identities in 141 endpoint
rows, 207 discovery receipts, 111 ENR diagnostic rows, 416 typed endpoints
(414 IPv4 and 2 IPv6), 118 inspection runs, and 61 normalized inspection
payloads. One payload was referenced by 58 occurrences. There were 349
source-scoped endpoint-first events, one `IPV6_FIRST_OBSERVED` event, and
**zero** protocol transition events in this sampled scan. The IPv6 event
describes advertised evidence, not reachability. No public client upgrade or
endpoint disappearance is claimed. The observer's IPv6 internet check
returned `NoRouteToHostException: No route to host`, an observer routing
limit. Enrichment yielded 127 `DATASET_UNAVAILABLE` results; no MMDB contents
or online lookup was used. The public-scan database
reopened with integrity `ok` and no foreign key violations.

## Performance and lifecycle

`BenchmarkChangeDetection` refuses an existing database path. On a fresh
temporary DB, 1,000 identical RPC measurement occurrences produced 1,000
inspection runs, one normalized payload, and **zero additional change
events** beyond two initial endpoint-first events. Two later controlled
`PASS → FAILED → PASS` attempts produced exactly two derived transitions.

| Measurement | Observed |
| --- | ---: |
| Initial DB bytes | 172,032 |
| DB after 1,000 repeated occurrences | 1,421,312 |
| Repeated-occurrence growth | 1,249,280 bytes |
| DB after two transitions | 1,425,408 |
| Transition growth | 4,096 bytes |
| 1,000 repeated writes including derivation | 2,778 ms |
| Two transition writes | 11 ms |
| Bounded change query | 799 µs |
| Latest change query | 86 µs |
| Heap used before / after | 23,823,712 / 179,797,776 bytes |

Heap numbers are process snapshots, not a leak proof or throughput promise.
No history occurrence was deleted. The change query has `LIMIT` and uses the
precise identity/time expression index; the ordered inspection comparison
query uses its matching index. The normal dashboard does not read change events.
Recomputation scans one identity's relevant history per new observation, so
very long per-identity histories increase write cost. No change worker or
queue was added, and no automatic retention exists.

The bounded scanner stop/restart validation reported peak 10 existing scanner
workers and 8 then 7 HTTP calls. Stop took 855 ms then 94 ms. After both
stops, captured executors had terminated; queued and active tasks, workers,
TCP sockets, HTTP calls, and enrichment queued/active/workers were zero. The
post-stop database-hash checks reported no late mutation. Two Deep Inspection
close checks likewise had zero queued/active workers, sockets, HTTP calls and
helper processes, no late inspection mutation, and identical derived
Endpoint/NAT results after reopen.

## Commands and gates

Commands used on isolated copies or fresh temporary paths:

```sh
sqlite3 torchnode.db ".backup '/private/tmp/torchnode-change-closure.db'"
shasum -a 256 /private/tmp/torchnode-change-closure.db
mvn test
mvn package
java --class-path target/torchnode-1.0-SNAPSHOT-jar-with-dependencies.jar tools/ValidateChangeDetection.java /private/tmp/torchnode-change-closure.db
java --class-path target/torchnode-1.0-SNAPSHOT-jar-with-dependencies.jar tools/ValidateHistoricalObservations.java /private/tmp/torchnode-change-closure.db
java --class-path target/torchnode-1.0-SNAPSHOT-jar-with-dependencies.jar tools/ValidateHistoricalPublic.java /private/tmp/torchnode-change-validation-20260930.db
java --class-path target/torchnode-1.0-SNAPSHOT-jar-with-dependencies.jar tools/BenchmarkChangeDetection.java /private/tmp/torchnode-change-benchmark-closure.db
node tools/check-jsp-javascript.mjs
node tools/check-network-enrichment.mjs
git diff --check
```

The existing `ValidateEndpointNat` utility was compiled and run against a
separate validation directory containing a **copy** named
`historical-copy.db`. Its two bounded public scanner windows and Deep
Inspection lifecycle checks passed. Go gates are run from `p2p-helper/`:
`go test ./...`, `go vet ./...`, and `go test -race -count=1 ./...`.
The pinned go-ethereum version remains v1.17.6. Build artifacts and database
copies are outside Git or ignored; benchmark DBs were removed.

Final Java gates passed: `mvn test` and `mvn package` each ran 133 tests with
zero failures, errors or skips. Both JSP scripts passed `node --check`; the
network-enrichment renderer check passed. Go tests passed 43 cases in two
packages, Go vet passed, and the race rerun passed the same 43 cases. The
representative upgrade/reopen, benchmark, bounded public runs, and lifecycle
utilities completed. No Go source or go-ethereum pin changed. Earlier
sandboxed Go socket testing in the History milestone needed approved loopback
access; this milestone's final Go gates passed in the available environment.

Final gate and Git results are reported in the milestone closure report.
