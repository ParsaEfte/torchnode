# Data Model Freeze (schema v5)

## Decision and meaning

The v0.0.1 core observation model is ready to freeze at schema version 5. This is a semantic contract, not a ban on future migrations. No migration, persisted marker, or core production change is needed. A new observation domain may add versioned tables. A change to an existing frozen meaning requires a concrete defect or requirement, an ADR, migration and compatibility analysis, and deterministic regression tests. Views, bounded queries, exports, caches that preserve truth, indexes, and deployment packaging may be added without redefining evidence.

## Persisted structure inventory

All names and relationships below were checked against SQLite migration SQL and the representative v5 schema. `schema_migrations` records versions 1–5. Legacy base tables predate that metadata. `nodes` and `p2p_observations` are mutable latest projections; `network_enrichment` is a latest address/dataset lookup projection. They are not historical truth.

| Structure | Role and key | Clock and provenance | Owner, relationships, Clear, legacy limits |
| --- | --- | --- | --- |
| `nodes` | Latest endpoint/node projection; `key` | Integer `last_seen`, `discovery_source`; latest client/API fields | Discovery/inspection store; Clear deletes; may have multiple rows per cryptographic identity; not rebuildable from all legacy history |
| `p2p_observations` | Latest P2P projection; `node_key` | Integer `observed_at`, Hello/Status JSON | Inspection store; Clear deletes; legacy latest rows lack run chronology |
| `discovery_observations` | Durable receipt; integer `id` | UTC text `observed_at`, source, provenance, endpoint JSON | Discovery store; Clear deletes; source-specific; unique exact receipt tuple |
| `discovery_endpoint_index` | Rebuildable typed index of discovery endpoint evidence; compound observation/address/transport/purpose/port key | Inherits receipt clock/source via parent | FK to discovery receipt with cascade; Clear deletes; migration 4 |
| `enr_observations` | Durable received/diagnostic ENR; integer `id` | UTC text `observed_at`, provenance, outcome, validation, identity comparison, raw RLP | ENR store; Clear deletes; migration 2; invalid evidence remains diagnostic |
| `inspection_evidence` | Durable content-addressed payload; SHA-256 `hash` | Payload fields, with occurrence clocks extracted | Inspection store; referenced by runs; Clear deletes; migration 4; dedup never dedups runs |
| `inspection_runs` | Durable inspection occurrence; run UUID/text `id` | UTC `started_at`/`completed_at`, trigger, discovery source, per-fact `timing_json` | FK to evidence hash; Clear deletes; migration 4; nullable legacy clock stays unknown |
| `network_enrichment` | Latest lookup projection per normalized address/dataset key | `looked_up_at`, evidence JSON | Enrichment store; Clear deletes; migration 3; endpoint context only |
| `network_enrichment_lookups` | Durable lookup occurrence; `lookup_id` | UTC `looked_up_at`, dataset key, evidence JSON | Enrichment store; Clear deletes; migration 4; no online lookup |
| `inspection_enrichment_context` | Durable run-to-lookup association; `(run_id,lookup_id)` | References the exact lookup context | FKs to runs/lookups; Clear deletes; migration 4 |
| `change_events` | Derived/rebuildable comparison; deterministic hash `id` | Earlier/later fact clocks, kind, source refs, derivation version | ChangeDeriver in writer transaction; Clear deletes; migration 5; refs are typed IDs, not SQL FKs |
| `schema_migrations` | Schema metadata; integer `version` | No observation clock | Migration owner; Clear preserves |

There are no persisted analytics aggregates, analytics caches, or database settings tables. The schema has 12 tables and 27 indexes (16 explicitly named plus 11 SQLite autoindexes). Explicit indexes are `idx_change_identity_time`, `idx_change_enr_order`, `idx_change_inspection_order`, `idx_country`, `idx_discovery_endpoint_time`, `idx_discovery_identity`, `idx_discovery_identity_time`, `idx_discovery_source_time`, `idx_enr_identity`, `idx_enr_identity_time`, `idx_enrichment_address_time`, `idx_inspection_identity_time`, `idx_inspection_time`, `idx_ip`, `idx_last_seen`, and `idx_type`. UI and existing CSV expose latest projections; inspection/history/analytics routes expose their respective bounded evidence. The database tables themselves are not an API contract.

## Frozen evidence contracts

**Identity.** `NodeIdentity` normalizes the cryptographic node ID. Address, port, client, country, ASN, RPC URL and Beacon URL never define or replace it. discv4/discv5, IPv4/IPv6 and multiple endpoints may converge on one identity. An unavailable ID stays unavailable; a latest projection row count is not an identity count.

**Endpoints.** Address, normalized family, transport, port, purpose, source and observation clock remain separable. Discovery UDP/TCP, trusted ENR advertisements, P2P target attempts, RPC/Beacon candidates and authenticated Hello listenPort claims have different evidence roles. A Hello port claim never overwrites discovery TCP truth. Shared addresses never merge identities. Advertised IPv6 is not proven reachable IPv6.

**Observations and projections.** A durable observation is evidence this observer actually received or measured with the domain's available clock, source, outcome and endpoint context. Repeated occurrences remain distinct even when payload hashes match. A later failed inspection does not delete an earlier success. Latest `nodes`, `p2p_observations` and `network_enrichment` can change independently of older history and are not substituted for historical windows. Untimed legacy evidence has no invented chronology, including row-ID chronology.

**Inspection and outcomes.** Run ID preserves attempt occurrence; evidence hash stores common payload, while `timing_json` stores per-run fact clocks. TCP, authenticated RLPx, Hello and ETH Status are separate stages. `PASS` means the named exchange completed; `FAILED`, `TIMEOUT`, `UNAVAILABLE` and `NOT_TESTED` retain their domain meanings. A blocked prerequisite leaves downstream stages `NOT_TESTED`. RPC and Beacon are independent candidate-probe paths. Observer routing/permission limits are not peer failure or service absence.

**ENR and verification.** The single trust predicate is `EnrEvidence.usable()`: structural validity, valid signature and identity `MATCH`. Invalid or mismatched ENRs remain diagnostic but provide no trusted endpoint. `NetworkVerification` uses independent interface evidence: one source is `OBSERVED`, comparison rows are `MATCH`/`MISMATCH`, and `VERIFIED` requires sufficient consistent independent evidence. Analytics applies this rule; it does not promote observations.

**Endpoint/NAT and enrichment.** Endpoint/NAT analysis is derived, with a five-minute temporal compatibility limit. Disagreement is possible translation or another configuration/timing explanation, never NAT proof. GeoIP/ASN lookups are normalized address and dataset scoped; one identity can have several countries/ASNs. Country is approximate network context; ASN organization is registration context, not hosting proof. `FOUND`, `NOT_FOUND`, `NOT_APPLICABLE`, `LOOKUP_FAILED`, and `DATASET_UNAVAILABLE` remain distinct. Hosting stays `NOT_AVAILABLE` absent independent support. Dataset changes are not node movement.

**ChangeEvents.** Source observations remain factual truth. Events are deterministic comparisons of compatible evidence under `derivation_version`, with typed source IDs. First-observed and transition events differ. Equal clocks are not causally ordered. `NOT_TESTED`, missing evidence, observer limits, unrelated endpoint/source/family, and independent RPC/Beacon paths cannot create fabricated transitions. Rebuilding derived events is idempotent and cannot alter source observations.

**Analytics.** On-demand read-only queries distinguish identity, endpoint, address, receipt, run, attempt, stage PASS and event units. Each metric states denominator, unknowns, eligibility, and overlap. Windows are UTC `[start,end)` and use fact clocks; latest snapshot is separate. Public sample is not Ethereum population. Limits reject the whole report rather than silently truncate. All-available mode is explicit and can scan retained history; the ordinary dashboard does not invoke it.

**Clocks and provenance.** Discovery/ENR `observed_at` is receipt/evidence time; inspection `started_at`/`completed_at` delimit an occurrence; per-stage attempt and API response clocks are stored in `timing_json`; enrichment `looked_up_at` is dataset lookup time; `change_events.current_observed_at` is the later fact's clock, not a known physical change instant; latest projection clocks describe their update. An event's fact clock need not equal its run start. Equal instants imply window co-membership, not cause. Provenance is retained through discovery/ENR source, endpoint context, run and protocol stage, lookup dataset/version, and change source IDs/rule version. Historical observer vantage beyond recorded local context cannot be fabricated.

**Clear and lifecycle.** Clear transactionally removes collected and derived evidence while preserving DB file, schema and migration metadata. Inspection generation coordination prevents stale work from reopening write storage or publishing/persisting old-generation results after Clear. Analytics has no worker, queue or cache; its route coordinates response publication with Clear. Restart/reopen uses schema v5.

## Consumer and extension policy

Maps must show endpoint-scoped approximate geography with family, multiplicity, coverage and dataset context, never one canonical node location. Charts and Dashboard v2 can consume bounded analytics and separate latest/history queries. History UI may collapse repeated display rows without deleting occurrences. Reports and richer exports can carry window, counting unit, denominator, unknowns, methodology, observer limits and dataset versions. A future API can expose bounded domain resources rather than mirroring SQL tables. SQLite on a persistent container volume remains viable with ordinary migration and backup discipline; deployment mechanics are later work.

Transaction gossip, block propagation, peer sessions, distributed vantage evidence, cross-vantage analytics, Explorer context, alerts, hosting, and research datasets may need new domains or tables. They can be additive. A future transaction observation could reference cryptographic identity and endpoint context while recording its own hash, session, evidence type, observer and clock. None justifies redefining current discovery, inspection, enrichment, change or analytics semantics now.
