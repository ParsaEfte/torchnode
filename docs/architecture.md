# Architecture and evidence boundaries

Java 21 / embedded Tomcat serves authenticated JSP/vanilla JavaScript pages and read-only APIs. SQLite stores historical observations and latest projections. Go helpers isolate discv5 and bounded P2P protocol exchanges. ADMIN-triggered collection and inspection are separate from reading stored evidence.

```text
discv4/discv5 -> bounded capture -> observer/run evidence batches
                                  -> single background writer -> SQLite v6
legacy receipts / ENRs / inspections ---------------------------> historical tables
SQLite read-only snapshots -> validation / bounded topology -> API -> DOM/SVG
```

Schema v6 retains historical v5 tables and adds append-only attribution/evidence tables. Writable Main startup, including credential provisioning/reset, initializes storage and may migrate v5 before prompting. Authentication uses an independent schema. Read-only reporting must not construct writable stores.

The evidence writer polls bounded provider rings with per-pass work limits, bounded fingerprint deduplication and failure counters. These are not complete ingestion/loss metrics exposed to operators. Shutdown drains existing evidence through lifecycle barriers; HTTP `ok` is static liveness, not a writer-health assertion.

Only eligible independently revalidated discv5 responder/request/run/ENR evidence produces directed REPORTED_NEIGHBOR edges. This means an authenticated responder reported a target ENR, not TCP/RLPx/ETH connectivity. DiscV4 candidates, partial records and legacy identities remain non-promotable/unlinked. Neighbor endpoints can be omitted by page limits; high-watermarks constrain relationship observations, not all concurrent historical backfills. No degree/population/network-completeness inference is valid.

APIs preserve independent protocol outcomes and provenance. Unknown differs from zero, NOT_TESTED differs from FAILED, observed identities differ from observation occurrences, and address context differs from identity. See [API](api.md), [security](../SECURITY.md) and [migration safety](release-validation.md).

Release verification is separate from collection: source-only temporary copies build the Java artifact and CGO-disabled Go helpers; synthetic containers use private volumes, no network or published ports, disabled collection, non-root identity, a read-only root and bounded resources. The image pins its SQLite JNI library outside writable temporary storage. The full recovery harness compares historical schema and row multiset hashes; only exact disposable auth-schema initialization and auth records are permitted. It does not migrate observation history.

The v6 recovery adapter, real Main/JAR lifecycle tests, browser checks and retained-data authorization serve different purposes. Synthetic success is not real-checkpoint recovery acceptance. [Recovery policy](recovery-rollback-policy.md) and [third-party licensing](third-party-licenses.md) remain explicit distribution/deployment gates.
