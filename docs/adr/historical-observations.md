# Historical observations

## Decision

Latest state is a projection; observations are durable truth. Version 4 of the
SQLite schema adds inspection occurrences, shared inspection evidence, an
address index over discovery evidence, and immutable enrichment lookup/context
references. It reuses discovery and ENR history. It does not generate change
events or infer disappearance.

## Storage audit

| Data | Before this decision | Treatment |
| --- | --- | --- |
| `discovery_observations` | Historical identity, provider, provenance, timestamp, endpoint JSON; a uniqueness constraint suppresses exact duplicate rows | Reused, with an indexed endpoint relation |
| `enr_observations` | Historical raw/decoded validation evidence and timestamp | Reused; invalid ENRs stay diagnostic and cannot become trusted endpoint claims |
| `nodes` | Endpoint keyed latest projection and service summary | Retained for dashboard and CSV |
| `p2p_observations` | One latest Hello/Status envelope per endpoint key; preserves earlier authenticated success across later failure | Retained as latest projection, not treated as a complete run archive |
| RPC/Beacon | Deep Inspection result primarily in memory; background scan updated `nodes` | Now stored per inspection occurrence |
| `network_enrichment` | Latest cached lookup per address/dataset key, with lookup time and source details in JSON | Retained; version 4 also preserves lookup records and run associations |
| Endpoint/NAT | Derived from discovery, ENR, Hello and API endpoint evidence | Remains derived; no historical conclusion snapshot |
| Deep Inspection timeline | In-memory UI events | Current run UI only; factual run evidence is durable |

Schema versions 1, 2 and 3 respectively introduced discovery history and
identity normalization, ENR history, and dataset-aware offline enrichment.
Version 1 may have backfilled a legacy discovery row from `nodes.last_seen`.
That inherited timestamp has only the semantics available in the old row.
When a pre-version-1 row has a missing or nonpositive `last_seen`, migration
now leaves it without a discovery observation; it does not create a false
1970 receipt. The version 4 migration does not redate it or manufacture
inspection runs.

The scanner has two measurement operations: background API inspection and
user-triggered Deep Inspection. An `inspection_runs` row is one completed
operation, identified by UUID, cryptographic identity, endpoint key, discovery
source, start and completion times, and trigger. A run groups only evidence produced by that
operation. It does not group unrelated discovery receipts or imply that all
its stage results were simultaneous. There is no invented observer geography.
The current observer is the local TorchNode process; no persistent observer ID
or executable version is stored because neither is reliably available at each
write. A future observer dimension can be added to this run context without
changing identity or occurrence semantics.

`inspection_evidence` holds a SHA-256 keyed JSON payload. `inspection_runs`
holds every occurrence and a reference to that payload. Attempt timestamps
and RPC/Beacon response timestamps live in `timing_json` on the occurrence,
then are restored on reads. Thus repeated identical evidence can share a
payload without losing measurement frequency or per-attempt times. Changed
facts get a different hash. Discovery and ENR retain their existing rows;
they are not copied into this payload. No automatic retention or lossy
compaction is applied. Growth is linear in occurrences, with payload growth
only when normalized facts differ; discovery/ENR still grow with receipts.

The background scanner records candidate RPC/Beacon endpoints, attempt times,
TCP-open results, response information and any collected client/network facts.
It marks P2P stages `NOT_TESTED`, because it does not perform P2P inspection.
Deep Inspection retains per-endpoint TCP, Auth, Hello and Status diagnostics,
authenticated Hello and decoded Status fields, RPC/Beacon evidence, probe
targets and factual errors. A failed attempt does not erase a prior successful
run. `NOT_TESTED` means the stage was not executed; `UNAVAILABLE` or `FAILED`
describes an attempted probe from this observer, not the absence of a service
anywhere. `NOT_OBSERVED` is not used as a persisted stage state.

`discovery_endpoint_index` normalizes address/family/transport/purpose/port
relations from the existing observation JSON. It preserves identity, source,
provenance and receipt time through its parent row. The index supports
address/time queries while allowing different identities on one address and
multiple IPv4/IPv6 endpoints for one identity. The JSON remains the original
discovery payload; the indexed relation is a query projection.

`network_enrichment_lookups` preserves exact saved lookup evidence under a
content hash, with address, dataset key and lookup time. This also permits
different results with equal lookup timestamps. The migration copies existing version 3
cache rows without changing their times. `inspection_enrichment_context`
references the exact lookup returned to an inspection, including cached
lookups. The endpoint observation time and the MMDB lookup time remain
distinct. A changed dataset result is not a node movement assertion. Offline
lookup, dataset-aware reuse and `NOT_AVAILABLE` hosting semantics remain.

## Transactions and lifecycle

One `saveInspectionRun` transaction writes the occurrence, shared payload,
latest `nodes` service projection, ENR evidence (when obtained), and the latest
P2P envelope. Any failure rolls back the group. Partial protocol outcomes are
valid evidence when explicitly recorded in diagnostics. Enrichment is an
independent asynchronous lookup; its exact run association is written after
the lookup/cached result returns under the existing generation barrier. A
failed enrichment does not invalidate protocol evidence.

Clear All Data deletes dependent context, run, payload, endpoint index,
discovery/ENR, P2P, node and enrichment rows in one transaction. Schema and
`schema_migrations` remain. The dashboard stops and joins the scanner before
clear; Deep Inspection uses its persistence lock/generation guard, and the
enrichment worker cancels queued requests and protects write-capable store
construction. A stale completion cannot reopen or republish after clear.
Shutdown closes existing workers; History adds no worker or queue.

## Queries and latest state

The normal dashboard reads `nodes` and never scans inspection history.
Identity history is ordered by `(started_at DESC, id DESC)` with an explicit
limit of 1–100 and cursor support; direct run-ID, recent and bounded time-range repository
queries use the same ordering. `NULL` time sorts last and is never replaced
with a made-up time. Address history uses `(observed_at DESC, observation_id
DESC)` and the indexed endpoint relation. Discovery and ENR have bounded
identity/cursor queries ordered by observation time and row ID. Deep Inspection loads 20 run rows
per request, with load-more to 50 per HTTP page; the existing CSV schema is
unchanged. Other existing Deep Inspection views can still load all discovery
and ENR evidence for one identity to derive Endpoint/NAT analysis. This is a
known per-identity scale limit, not a dashboard table query.

The canonical identity remains the cryptographic node ID. Multiple endpoint
projection rows may exist for one identity. Existing canonical preference
uses discv4 source, then `last_seen`, then key; successful service evidence is
attached to the endpoint row actually inspected. Within one endpoint key,
equal discovery times choose the greater advertised TCP port, then raw node ID,
so write order does not determine that tie. Exact endpoint-key lookup takes
precedence over the legacy IP:UDP alias when identities share an endpoint.
Latest projection is not a
claim that an endpoint has disappeared or that untested services are absent.

## Migration, reproducibility and limits

Version 4 is transactional and recorded in `schema_migrations`. It backfills
only typed endpoint index rows from persisted discovery JSON and lookup rows
from persisted enrichment cache JSON/time. Legacy `p2p_observations` is not
backfilled into runs: its last write time and retained earlier Hello/Status
cannot reconstruct one coherent inspection operation. Legacy RPC/Beacon
attempts, candidate endpoints, per-stage times and failed runs are unavailable.
The migration does not alter ENR trust decisions or endpoint/NAT comparison
rules. Indexes cover inspection identity/time, recent inspection time,
discovery identity/source/time, ENR identity/time, endpoint address/time and
enrichment address/time. No speculative full-payload index is added.

Rejected alternatives: a giant generic event table would obscure typed trust
and attempt semantics; copying all old observations into new rows would add
redundancy and false simultaneity; overwriting the latest P2P envelope alone
would lose run history; automatic deletion of repeated rows would lose
measurement frequency; and snapshotting derived NAT assessments would risk
presenting an algorithm's conclusion as direct evidence.

Current discovery is from one observer and is not a census of Ethereum.
Discovery sampling, bootstrap selection, reachability, local IPv6 routing,
candidate API ports, peer responsiveness, and observer network policy bias the
dataset. A failed probe is a statement about one attempted path and observer
context. No population prevalence or transition claim follows without a
separate method and denominator. Future observation domains can use the same
identity/time/context pattern, but transaction gossip, distributed vantage
points, Change Detection and Network Analytics are outside this decision.
