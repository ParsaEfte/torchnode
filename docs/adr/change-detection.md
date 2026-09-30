# Change detection from historical observations

## Audited baseline and decision

Historical migration 4 keeps inspection occurrences in `inspection_runs`, shares
identical `inspection_evidence` payloads by hash, and stores per-run timing.
Discovery receipts and ENRs have their own identity/time histories; typed
discovery endpoints are indexed separately. P2P, node, and enrichment cache
tables are latest projections. Enrichment lookup history and exact inspection
references preserve dataset and lookup time. Endpoint/NAT is derived from its
original inputs. Deep Inspection reads bounded history pages; Clear removes
collected history under the existing scanner/inspection generation barriers.

The comparable domains are successful Hello client/capabilities, like-source
RPC or Beacon client and probe outcomes, actually attempted P2P stages on the
same endpoint, trusted ENR sequences, and source-scoped endpoint receipts.
Enrichment dataset results, missing legacy fields, different probe targets,
and absent endpoints in later discovery samples are not node transitions.

Change events will be **persisted derived records** with references to the
source history rows. This gives bounded Deep Inspection queries and future
analytics a stable access path without rescanning unbounded inspection payloads
on every read. The source observations remain authoritative. Derivation is
synchronous inside the transaction that writes the historical observation and
latest projection; it uses no new worker or queue. Rebuilding derived events
from ordered historical rows is the correction path. Migration 5 creates an
empty event table and performs no speculative legacy backfill. A subsequent
new observation may cause recomputation for its identity using existing
historical evidence with genuine timestamps.

`change_events` contains a stable SHA-256 ID over identity, observation kind,
type, subject and source references; it stores compact before/after values,
times, endpoint/family, source and derivation rule version. The referenced inspection run, discovery
receipt or ENR supplies full provenance and raw evidence. Polymorphic source
references are resolved through the repository rather than declared as a
single foreign key. An identity/time index serves bounded history pages;
the current UI does not query all identities by type. No latest-change column is copied into `nodes`;
`changeHistory(identity, 1, null)` is the bounded latest-change query.
Migration 5 also creates precise expression indexes for inspection and ENR
timestamp ordering. ISO timestamps with zero, three, six or nine fractional
digits do not sort reliably as raw strings within one second. The expression
pads observed fractions for change derivation without changing History's
stored timestamps or fabricating legacy times.

Each new run, ENR or discovery receipt rebuilds only its canonical identity's
corresponding derived domain in the writer transaction. This handles late or
out-of-order inserts and is idempotent. `rebuildChanges(identity)` recomputes
all three domains transactionally after a correction. Work is proportional to
that identity's history; repeated receipts add no fake events but still incur
recomputation cost. This is an explicit operational limit, not a reason to
discard observations or add an unbounded worker queue.

## Comparison contract

An event references the current observation and, for a transition, the most
recent earlier **comparable** observation, including identical occurrences.
Identical values emit no event. Equal timestamps have a stable display order
but no established temporal precedence and emit no transition. Missing times
cannot establish a transition. First-observed events have no previous value
and are classified separately. A failed or unavailable attempt records only
the observer's outcome at that target; it does not establish peer fault or
service absence elsewhere. `NOT_TESTED`, missing evidence, and non-observation
cannot supply negative evidence. Observer-policy errors are not treated as
peer reachability evidence.

The recorded times are the source attempt/observation times when available.
The history run's start time orders pages, but a run start is not substituted
for a missing stage timestamp. Distinct source observations with equal times
and conflicting values form an ambiguous group; no transition is emitted
through that group. Overlapping runs whose actual stage times disagree with
run order are skipped conservatively. IDs break display ties only.

P2P stages require the same canonical identity, TCP target and address family.
Only real stage attempts (`PASS`, `FAILED`, `TIMEOUT`) are comparable; later
prerequisite failure does not remove Hello capabilities or Status. RPC and
Beacon are independent and require the same actual candidate endpoint and
probe method. Client values are compared only within the same evidence source
and target. Discovery first-observed endpoints are scoped by source, transport,
purpose and address family. A later incomplete discovery sample never removes
an endpoint. Trusted, identity-matching ENRs can establish sequence advance;
invalid or mismatched ENRs remain diagnostic. No ENR sequence advance alone
establishes an endpoint change. Endpoint/NAT's five-minute comparison window
remains local to Endpoint/NAT and is not applied here.

## Taxonomy and limits

The initial taxonomy is explicit: client implementation/version transitions,
Hello capability-set transition, trusted ENR sequence advancement, source-
scoped endpoint first observation, IPv6 first observation, and per-protocol
attempt-outcome transitions. First observed is never encoded as a change from
NULL. Client and service first-observed events are deferred; newly available
client evidence does not create a version transition. Capability strings and
typed name/version pairs are normalized into a sorted set. Flapping comparable
outcomes remain separate events. `NO_TCP_CONNECTION` means the observer did not
establish TCP to that candidate; it does not identify why. No endpoint
disappearance, node movement, hosting, network-wide, or cross-source
disagreement event is inferred. GeoIP/ASN dataset changes remain lookup
history, not node-change events. The normal dashboard and CSV do not query or
export change history; Deep Inspection requests bounded pages.

## Rejected alternatives and later work

Fully on-demand comparisons would repeatedly scan source payloads for each UI
page and complicate stable bounded pagination. A generic event-sourcing layer
would duplicate History's identity, time and provenance model. A separate
asynchronous change worker would require ordering and generation barriers for
no current benefit. The chosen synchronous derived table avoids those costs.

Discovery sampling cannot justify endpoint removal or source-wide endpoint
replacement from non-observation, so `ENDPOINT_VALUE_CHANGED` is deferred
until a precise trusted advertised-slot comparison is implemented. ENR
sequence advance is deliberately not interpreted as endpoint replacement.
Cross-source network IDs are verification evidence, not a generic network
change. Deep Inspection's RPC/Beacon failures do not retain per-candidate
outcome timestamps, so their aggregate failures are not compared as if a
specific endpoint failed. Background scans supply timestamped candidate
attempts and can support candidate outcome comparisons. No execution and
consensus client identities are merged. Dataset revisions can change
GeoIP/ASN answers for an unchanged address; they remain enrichment history,
not node events. Network Analytics will define denominators and sampling
methodology later; Change Detection adds no population claims.

Events are indexed by identity/time. Uniqueness is based on
source references and event type/subject, never processing clock time. Clear
removes derived events in the same collected-data transaction. Restart and
reprocessing cannot duplicate a logical event. No destructive retention is
introduced.

This model is intentionally narrower than a generic event-sourcing framework.
It preserves the History schema and permits later Network Analytics to consume
derived events while retaining their underlying evidence and comparison rules.
