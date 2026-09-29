# GeoIP and ASN endpoint enrichment

Date: 2026-09-29. Baseline: b8e8f39f890bf9e450d4ff0079d9c71755b9b580.

## Audit and decision

EndpointAddress already validates numeric IP literals without DNS, retains typed
IPv4/IPv6 families and brackets host:port/HTTP presentation. NodeEndpoint keeps
transport/purpose/port. DiscoveryObservation stores identity, source, endpoint
list, timestamp and provenance. Source observations and trusted ENR endpoint
claims persist as JSON in discovery_observations/enr_observations; Java signature
and identity validation gates ENR topology evidence. P2P attempt targets/stages,
retained Hello/Status and independent API URI evidence persist in
p2p_observations. Hello listenPort remains a port claim. CanonicalNodes deduplicates
cryptographic identities, never IPs. EndpointAnalysis reconstructs bounded,
source-specific comparisons from these rows, excluding untrusted ENRs.

Deep Inspection loads store evidence into InspectionResult snapshots. CSV reads
store-derived endpoint evidence. Neither rendering path previously had GeoIP
lookup ownership. SQLite uses transactional numbered additive migrations (1:
discovery, 2: ENR). Legacy nodes.country exists but is not populated by this
milestone. Environment variables configure optional helpers/databases. Scanner
API inspection retains its ten-worker bound; scanner/InspectionService each own
cancellation and persistence guards. Existing ENR acquisition has a bounded cache
and executor, which are independent of enrichment.

A dedicated NetworkEnrichmentProvider and NetworkEnrichmentService add reusable
address/dataset evidence. Geo/ASN is not a NodeIdentity property. Different
identities at the same address can reuse the address lookup without merging.
Dual-stack paths may legitimately have different countries/ASNs. An endpoint
change cannot inherit enrichment for its previous address. No enrichment field
is input to endpoint comparison, NAT assessment or protocol verification.

## Offline provider, licensing and provenance

OfflineGeoIpProvider uses pinned com.maxmind.db:maxmind-db:3.1.1 (Apache 2.0)
with optional GeoLite2 Country or GeoIP2 Country and GeoLite2 ASN MMDB files.
Separate country/ASN results retain source/database type, SHA-256 and metadata
build date, record prefix, lookup timestamp and explanation. Only country
iso_code/names.en and autonomous_system_number/organization are exposed. City,
region and hosting classification are not available; organization is never
classified by substrings. Hosting is always NOT_AVAILABLE.

Users obtain files separately, accept the applicable terms and configure
TORCHNODE_GEOIP_COUNTRY_DB / TORCHNODE_GEOIP_ASN_DB. Files must be local regular
files no larger than 512 MiB each. Readers are memory mapped, shared safely and
closed by the owner. No downloader, external lookup, key/token storage or online
provider is implemented. Dataset hashes are streamed once on owner startup.
Absent/invalid/wrong-type files degrade independently to DATASET_UNAVAILABLE;
other Ethereum evidence continues normally.

References: [official setup](https://dev.maxmind.com/geoip/geolite2-free-geolocation-data/),
[GeoLite terms](https://www.maxmind.com/en/geolite/eula),
[ASN fields](https://dev.maxmind.com/geoip/docs/databases/asn/),
[reader license](https://github.com/maxmind/MaxMind-DB-Reader-java/blob/main/LICENSE).
Dataset terms are separate from the reader license; no production or fixture
MMDB files are committed. UI/README retain MaxMind attribution when configured.

## Address eligibility and results

Numeric literals are normalized with the existing validated representation;
compressed/expanded IPv6 deduplicate, bracketed endpoint strings are not lookup
keys. IPv4-mapped IPv6 remains explicit IPv6 and is excluded from public lookup.
The conservative PublicAddress policy excludes private/loopback/link-local,
unspecified, multicast, shared/documentation/benchmark/reserved IPv4 and IPv6
outside native 2000::/3 or listed special-purpose ranges (documentation, Teredo,
6to4, benchmarking and ORCHID). This is an eligibility policy, not an exhaustive
routing/reachability classifier; special-use exceptions may be conservatively
excluded. It performs no DNS/network requests.

Each country/ASN lookup distinguishes FOUND, NOT_FOUND, NOT_APPLICABLE,
LOOKUP_FAILED and DATASET_UNAVAILABLE, retaining a reason and no fabricated
unknown values. Country is approximate network location, not peer/person physical
location. Prefix belongs to its particular dataset. Missing country does not
prevent a successful ASN lookup, or vice versa.

## Cache, persistence and lifecycle

Each scanner or inspection owner has one enrichment worker, a queue of 128 and a
1024-entry address LRU. In-flight futures deduplicate concurrent submissions.
Excess queue work is rejected independently; discovery packet handlers only
submit, and P2P exchanges never wait for enrichment. Persistent exact
(address,datasetKey) reuse avoids repeated work across owners/restarts and LRU
eviction. An owner holds a fixed combined country/ASN dataset descriptor;
restarting after file replacement obtains a new descriptor. Stored negative
results are reused for that version, with no retry loop or TTL refresh.

Migration 3 adds network_enrichment, primary key (normalized address,dataset key),
family, lookup time and complete evidence JSON. This is necessary for persistent
lookups and leaves existing tables/rows untouched. Country/ASN provenance stays
separate inside the normalized record. No derived NAT truth is persisted.
Versions coexist, but there is no general historical timeline or evolution model.
Store views join the latest known record to original endpoint source/time/purpose
and provenance, and label lookup time separately from observation time. Reopen
reconstructs identical records; this is not a claim about infrastructure at the
original discovery time.

Service generation tokens guard lookup counters and database publication.
Store initialization (which can migrate/backfill) is also inside the lifecycle
barrier, so an obsolete task cannot initialize/mutate storage after a clear.
clear() increments generation, cancels all pending futures, discards queued work,
interrupts the active worker and invalidates its cache. Stale work cannot write
or publish its result even if the provider ignores interruption and returns late.
Future completion runs outside the service lock, preventing lock inversion with
inspection persistence callbacks. Inspection callbacks acquire the existing
persistence lock and check the originating owner generation/closed flag before
loading UI-visible state. Stop invalidates those guards before draining enrichment,
closes the reader, interrupts/awaits the owned worker and returns with zero owned
work. Callback execution already underway is drained before shutdown returns.
Providers must be local, finite and promptly closeable; arbitrary permanently
blocking third-party plugins are not supported.

Clear All Data follows the existing servlet scanner stop/await before acquiring
inspection's persistence lock. That lock blocks new inspection submissions and
callbacks; enrichment generation invalidation precedes the transactional clear.
After successful clear, inspection generation and caches/results are invalidated
before releasing the lock. An already completed old future blocked on that lock
is rejected afterward. Database clear failure still rolls back all persisted
rows; cancelling obsolete pending enrichment does not weaken that transaction.
A fresh scanner creates a fresh owner; old stopped workers are joined before
restart. No generation N result can write into generation N+1.

## UI, export, validation and scope

Deep Inspection adds one compact Network Location / Infrastructure section,
grouped by address with original endpoint observations in details, status,
country/ASN, source/version/prefix/time and hosting NOT_AVAILABLE. It consumes
snapshots, never calls a provider. CSV keeps all 21 existing columns in order and
appends NETWORK_ENRICHMENT_JSON with endpoint multiplicity/provenance intact.
No dashboard redesign, map, analytics or public API redesign occurs.

Deterministic fixtures cover both families, dual stack, non-public filtering,
miss/failure/unavailable distinctions, bounded reuse, version separation,
identity/endpoint changes, reopen, migration rollback, original P2P/ENR/NAT
semantics and synchronized lifecycle races. Official MaxMind synthetic MMDB
fixtures additionally exercise the actual reader; they are not public GeoIP data.
Exact evidence and public/degraded validation are in the
[validation report](../validation/geoip-asn-enrichment.md).

Limitations: no production dataset was available during validation, so no public
country/ASN success is claimed. No city/region, hosting classification, automatic
updates/retries, historical enrichment evolution, NAT redesign, reputation,
VPN/proxy/Tor detection, maps/analytics or distributed observation is added.
