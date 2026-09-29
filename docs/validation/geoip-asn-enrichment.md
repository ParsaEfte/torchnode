# GeoIP / ASN enrichment validation

Date: 2026-09-29. Starting branch main, exact baseline
b8e8f39f890bf9e450d4ff0079d9c71755b9b580 (`feat: add endpoint and NAT evidence analysis`).
Branch/HEAD/status were verified before edits; the working tree was clean.

## Validation boundary

- Historical endpoint rows: **2,943**.
- Discovery observations: **5,719**.
- ENR records: **532**.
- P2P records: **17**.
- Production GeoIP/ASN dataset: **unavailable**.
- Real public country enrichment: **not validated in this environment**.
- Real public ASN enrichment: **not validated in this environment**.
- Real public hosting classification: **not validated in this environment**.

Both GeoIP environment settings were absent, and standard local GeoIP directories
contained no MMDB files. No production dataset was downloaded/configured, no
license key was requested, and no geolocation service received endpoint IPs.
Hosting classification remains NOT_AVAILABLE throughout, including when an ASN
organization is present. Synthetic data is never counted as public enrichment.

Initial completed evidence artifacts are under ignored `target/geoip-asn-closure-20260929`:
safety-before.json, public-report.json, geoip-asn-report.json, safety-after.json,
isolated databases, logs and test MMDB files. An earlier public harness accounting
probe failed because java.util.concurrent was not opened for reflection; its
scanner cleanup completed, but its partial run is excluded from closure evidence.
The final fresh run uses all three required module-opening flags.

## Deterministic semantic and lifecycle proof

The new suites contain eleven tests (six lifecycle, five semantic), all passing.

| Requirement | Demonstrated evidence |
| --- | --- |
| Public IPv4 / IPv6 FOUND | Mock local provider returns distinct country/ASN results, family, prefix, source/version/time; synthetic only |
| Dual stack | One canonical identity, separate family/address enrichment, different fixture countries/ASNs without false mismatch |
| Non-public | Private/shared/local/loopback/link-local/unspecified/multicast/documentation and mapped forms NOT_APPLICABLE, zero provider calls |
| Miss / failure | NOT_FOUND distinct from LOOKUP_FAILED; no fabricated values; discovery rows and retained Hello/Status unchanged |
| Missing / invalid MMDB | DATASET_UNAVAILABLE for country and ASN; independent non-public exclusion remains NOT_APPLICABLE |
| Dedup / cache bounds | 64 concurrent same-address submissions cause one lookup; 1,030 further addresses keep LRU <=1024; eviction reloads persisted evidence |
| Dataset provenance | Separate source/version/prefix/time survive exact JSON reopen; different dataset keys coexist |
| Identity / endpoint changes | Different identities sharing address remain distinct; new endpoint has no inherited enrichment; repeated observations stay canonical |
| NAT independence | Derived Result equality before/after failed enrichment and public-copy enrichment; no Geo/ASN inputs to comparison model |
| Migration | Upgrade version 2 copy to 3; injected version-3 INSERT failure rolls back new table/version; original rows survive |
| Queued cancellation | Held first lookup + 128 queued addresses; excess rejected; all queued futures cancelled without execution |
| Late stop completion | Fake provider ignores interrupt until latch released; cancelled future suppresses callback/write; close drains to zero |
| Late clear completion | Clear returns before late fake lookup returns; old result cannot repopulate DB/cache/counters |
| Restart / generations | Same-address generation N result discarded; generation N+1 result persists normally; reopened owner reuses it |
| Executing callback | Completion held by latch; shutdown drains it before returning; no service lock held around callback |
| Actual inspection callback | Real finish callback waits on persistenceLock during clear; generation check suppresses old UI-state publication afterward |

No arbitrary sleeps determine these lifecycle races: CountDownLatch synchronization
and bounded future waits establish the relevant ordering. Stop/cancellation tests
finish with zero queue, active tasks, workers and cache, terminated executor.
Provider failures do not erase protocol rows. Clear All Data still uses the
original all-or-nothing transaction; existing rollback assertions are retained.
The old migration fixture now genuinely removes versions 2 and 3 when constructing
a version-1 database; its final migration count is updated to 3. No assertion is
weakened to hide an application regression.

## SYNTHETIC / OFFICIAL TEST-DATABASE VALIDATION

Official MaxMind-DB test inputs, not production databases, are pinned to commit
`276926d23b4109ca5452709bfb5931c338afb34c` from
[MaxMind-DB](https://github.com/maxmind/MaxMind-DB/tree/276926d23b4109ca5452709bfb5931c338afb34c/test-data).
Its Apache/MIT license texts are retained beside the ignored fixtures.

| File | SHA-256 |
| --- | --- |
| GeoIP2-Country-Test.mmdb | b37601903448683d241af52893c8cbf0fed461e0cdebe0bfaca01891fdeb6db9 |
| GeoLite2-ASN-Test.mmdb | 75901b98ed6e58d3bd41af9985044b747a7ec0be1369f930c24f5e044427181a |

The real OfflineGeoIpProvider opened both files through reader 3.1.1 and produced:

| Synthetic test query | Country | ASN |
| --- | --- | --- |
| 2.125.160.216 | FOUND GB | NOT_FOUND |
| 2001:218:: | FOUND JP | NOT_FOUND |
| 1.0.0.1 | NOT_FOUND | FOUND AS15169 / Google Inc. |
| 2001:1700:: | NOT_FOUND | FOUND AS6730 / Sunrise Communications AG |
| 8.8.8.8 | NOT_FOUND | NOT_FOUND |
| 10.0.0.1 | NOT_APPLICABLE | NOT_APPLICABLE |

These results prove MMDB file opening, actual IPv4/IPv6 decoding, organization,
normalized IPv6, miss handling and independent dataset metadata (type, hash,
build date, prefix, lookup time). They are **not observed Ethereum-node geography,
public-network enrichment, production validation or a real country/ASN distribution**.
No hosting classification is inferred from either organization. Fixtures remain
under target, and *.mmdb is ignored; none belongs in the staged manifest.

## Bounded public and historical-copy evidence

The existing scanner harness runs discv4 + authenticated discv5 for 45 seconds,
stops/awaits cleanup, restarts for 12 seconds and stops/awaits again. Same existing
bootstrap and resource policies; no increased concurrency. Two additional IPv4
Deep Inspections are bounded to 40 seconds each, using the existing pipeline.
Offline copy validation is capped at 4,096 distinct addresses, one worker.

Fresh discovery produced **331 canonical identities**, **649 discovery rows**:
268 discv4, 180 discv5, 201 ENR-source observations; zero identities appeared
through both discovery providers in this sample. There were 305 ENR records,
201 VALID outcomes, and 15 authenticated discv5 observations. Discovery endpoint
occurrences: IPv4 1,294 / IPv6 54; distinct typed endpoints IPv4 668 / IPv6 18.
Nine identities had dual-stack evidence. This is evidence acquisition, not GeoIP.

| Offline address validation | Historical copy | Fresh public scan |
| --- | ---: | ---: |
| Canonical identities | 2,842 | 331 |
| Unique observed addresses | 2,293 | 333 |
| Eligible public IPv4 | 2,286 | 324 |
| Eligible public IPv6 | 2 | 9 |
| Country DATASET_UNAVAILABLE | 2,288 | 333 |
| ASN DATASET_UNAVAILABLE | 2,288 | 333 |
| Country / ASN NOT_APPLICABLE (each) | 5 | 0 |
| Country / ASN FOUND (each) | 0 | 0 |
| Country / ASN NOT_FOUND (each) | 0 | 0 |
| Country / ASN LOOKUP_FAILED (each) | 0 | 0 |
| Distinct countries / ASNs | 0 / 0 | 0 / 0 |
| Dual-stack identities with both families FOUND | 0 | 0 |

Zeros here reflect absent datasets and cannot be interpreted as actual network
country/ASN distributions. All acquired unavailable states persist explicitly.
Initial offline historical pass obtained 2,288 unavailable provider results and
five exclusion results, with 2,293 cache reuses. Re-running/reopening all addresses
made **zero provider calls**, with 4,586 historical reuse events and 666 fresh-scan
reuse events (saved + immediate-cache reuse). No download/retry storm; LRU stayed
<=1024 and all queue rejection counters were zero in public validation.

DATASET_UNAVAILABLE remained independent of protocol observations. Public IPv4
Deep Inspection at **65.21.95.162:30303** completed TCP, RLPx Auth, encrypted Hello,
JSON-RPC and valid ENR acquisition; ETH Status remained NOT_TESTED because local
Status RPC was not configured. Beacon attempt failed independently. At
**87.104.98.252:30303**, TCP/Auth completed; peer disconnected during Hello, so
Hello FAILED and Status NOT_TESTED. Independent RPC failed and Beacon was
unavailable. Both inspection snapshots retained DATASET_UNAVAILABLE enrichment.
No fake Status or Beacon PASS is asserted. Final fresh DB has two P2P rows.

Public IPv6 Internet check was **NoRouteToHostException: No route to host**.
Exact discovery endpoints attempted included [2001:41d0:24c:600::]:30303,
[2607:fdc0:781:3:0:1:0:37]:30303, [2a01:4f8:271:11f1::2]:9000 and
[2604:2dc0:106:be00::]:9000; both scanner runs recorded IPv6 failures. IPv6
TCP/P2P attempts to the first two addresses failed TCP_NO_ROUTE_TO_HOST, with
Auth/Hello/Status NOT_TESTED. This is an observer routing limitation, not peer
invalidity or public IPv6 success. IPv4 discovery/inspection continued.

## Persistence and database safety

The live database was opened read-only for SQLite backup, and every application
migration/validation used copies. Historical copy upgraded migrations [1,2] to
[1,2,3], preserving **2,943 nodes, 5,719 discovery, 532 ENR and 17 P2P rows**, plus
2,293 independent address enrichment rows. Final public copy holds 331 nodes,
649 discovery, 305 ENR, two P2P and 333 enrichment rows. Both copies have
integrity_check **ok** and empty foreign_key_check, and exact enrichment equality
across application reopen. Endpoint/NAT Result lists were unchanged across the
enrichment-only phase; subsequent real protocol inspection adds its own evidence.

Live SHA-256 before backup and at the end of the initial completed validation was identical:
`d8c2b628996b2de3ac9d152a69dd7ab296a88a2e402edf20aa47266550e2b62e`.
All validation writers used isolated paths; backup/audit live connections were read-only.
Concurrent normal application activity later changed the live file, as documented below.
No validation migration or clear targeted the live database.

## Lifecycle and UI/export

| Lifecycle measurement | First scanner | Restarted scanner |
| --- | ---: | ---: |
| Duration | 45 s | 12 s |
| Shutdown | 104 ms | 53 ms |
| Peak scanner API workers | 10 | 10 |
| Enrichment provider calls | 274 | 59 |
| Enrichment reuse | 704 | 297 |
| Enrichment queued / active / workers / cache after stop | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| Scanner queued / active / workers after stop | 0 / 0 / 0 | 0 / 0 / 0 |
| TCP sockets / HTTP calls / Go child processes after stop | 0 / 0 / 0 | 0 / 0 / 0 |
| Executors terminated / no late DB mutation | true / true | true / true |

Both discv4 sockets could be rebound; discovery/background-inspection thread
lists were empty. Go helpers exited, closing owned IPv4/IPv6 discovery transport.
IPv6 failures did not affect IPv4 operation. Separate enrichment owners stopped
with terminated executors and zero queues/workers/cache. The two Deep Inspection
shutdowns took 1 ms and 0 ms, with zero sockets, HTTP calls and helpers as well.

Actual Tomcat/JSP integration passes all previous ENR/NAT/IPv6 assertions plus the
new section and **22-column CSV**, preserving all previous 21 columns in order.
NETWORK_ENRICHMENT_JSON exactly matches store evidence for the inspected identity;
CSV contains explicit DATASET_UNAVAILABLE and NOT_AVAILABLE records for a separate
synthetic export-only IPv6 address. It preserves endpoint family and provenance.
The actual renderer is executed with a recorded DOM in check-network-enrichment.mjs:
unavailable/miss/non-public/failure/found states, normalized IPv6, source/version,
observation provenance and honest hosting all PASS. Neither UI/export path invokes
a provider, fetches geolocation or stores an identity country/provider.

## Final quality gates and reproducibility

- mvn test: **PASS, 108 tests, zero failures/errors/skips**.
- mvn package: **PASS, 108 tests**, both application jars built.
- go test -count=1 ./...: **PASS**, P2P and discovery helper packages.
- go vet ./...: **PASS**.
- Both helper go builds: **PASS**.
- go test -race -count=1 ./...: **PASS**, both packages.
- Pinned geth v1.17.6 v5wire/RLPx vectors, handshake/replay/framing selection:
  **PASS**, cache disabled. Selection:
  `Test(Vector|TestVectors|Handshake|FrameReadWrite|ReadWriteMsg|DecodeErrorsV5|EncodeWhoareyouResend)`.
- tools/check-jsp-javascript.mjs: **PASS**, both JSP scripts.
- tools/check-network-enrichment.mjs: **PASS**, actual renderer semantics.
- Opt-in ValidateGeoIp.java compilation/execution: **PASS**, real MMDB reader and
  bounded public/copy/inspection validation. Optional second argument reuse-scan
  reuses the completed isolated public run, not a new network crawl.
- git diff --check and staged whitespace: checked before the final checkpoint.

To repeat, prepare a read-only backup as historical-copy.db in a new target
validation directory, place the pinned official MMDB test files/license/manifest
under mmdb-fixtures, copy the packaged dependency jar as validation-app.jar, and
compile tools/ValidateActiveIpv6.java + tools/ValidateGeoIp.java against it. Invoke
ValidateGeoIp with that directory and JVM --add-opens for java.base/java.lang,
java.base/java.net and java.base/java.util.concurrent to ALL-UNNAMED. The scanner
harness refuses to overwrite an existing public-scan.db. No test needs a live
third-party geolocation API; ordinary Maven semantic tests use local mocks only.

## Final store-initialization barrier revalidation

A last lifecycle audit also put SqliteNodeStore construction inside the service
lock/generation guard: schema initialization/backfill can write and must not run
from obsolete work after clear. After this change, mvn test and mvn package again
passed all 108 tests. JavaScript semantic/syntax checks passed again. Unchanged Go
sources retain their successful test/vet/race/build/geth gate results above.

A second fresh final run used the final packaged implementation, application jar
SHA-256 `238fe5aa8361a0818fc48af569c29679e634982c9bebeaec0ce84d5dcb4ed2e0`.
Its separate artifacts are under `target/geoip-asn-final-20260929`; its copied
safety-before.json records the ORIGINAL pre-validation baseline, not the later
concurrent application's state. The initial run figures above remain independent
evidence; the final run produced:

- 392 canonical identities; 968 discovery rows (221 discv4, 359 discv5, 388 ENR).
- 490 ENR rows before the two inspections, 388 VALID outcomes, 24 authenticated
  discv5 observations; zero identities common to both discovery providers.
- 787 distinct IPv4 and 16 distinct IPv6 typed endpoints, eight dual-stack identities.
- 390 unique addresses: 381 eligible IPv4, eight eligible IPv6, one excluded.
  Country/ASN DATASET_UNAVAILABLE each 389; NOT_APPLICABLE each one; FOUND,
  NOT_FOUND and LOOKUP_FAILED each zero. No public geography/ASN/hosting claimed.
- Scanner shutdowns 657 ms / 74 ms; peak ten scanner API workers each; zero
  owned enrichment/scanner queues, active work, workers, sockets, HTTP calls and
  helpers after each stop, terminated executors and no late DB mutation.
- Enrichment worker lookups 284 / 76 and reuse events 1,131 / 332 during scans.
  Bounded post-scan reprocessing made 29 lookups and 751 reuse events; no queue
  rejections. Final public DB has 392 nodes, 968 discovery, 491 ENR, two P2P and
  390 enrichment rows, integrity ok / foreign keys empty.
- The final two public inspection targets were 65.21.94.126:30303 (TCP/Auth PASS,
  Hello peer disconnect, Status NOT_TESTED) and 135.125.170.164:30303 (TCP timeout,
  downstream NOT_TESTED). API probes continued independently with failed or
  unavailable results. Shutdowns 1 ms / 0 ms; zero owned resources. The earlier
  completed 65.21.95.162 Hello/RPC success remains separate evidence above.
- Real reader synthetic MMDB checks passed again; fixtures stay outside Git.
  Enrichment-only Endpoint/NAT equality and exact reopen equality passed again.

### Concurrent live application activity

At 17:14:57 local time, an independent IntelliJ-owned JVM started
io.github.gavinruff007.torchnode.Main (PID 14497, parent IntelliJ PID 1645), outside
the validation process tree. One existing identity's ENR was reacquired at
13:45:06 UTC and address enrichment recorded at 13:45:08 UTC. This independently
running application used the default live database and applied normal migration 3.
We did not stop it, revert its updates or overwrite live data.

The later read-only backup therefore contains **2,943 nodes, 5,720 discovery,
533 ENR, 17 P2P rows**, rather than assuming the earlier baseline counts. It
retains 2,842 canonical identities and 2,293 addresses (2,286 eligible IPv4, two
eligible IPv6, five excluded). Country/ASN unavailable each 2,288; exclusions each
five. One already persisted address was reused; offline lookups were 2,287 and
reuse events 2,294. It has 2,293 enrichment rows after validation, exact reopen
and Endpoint/NAT equality, integrity ok and no foreign-key violations.

The live hash consequently changed to
`34fd5c20fc2a43995b00908024ceb3b5ace8df14fa35ed719312fc7b41f6fd26`;
whole-file hash stability is NOT claimed across this independent application run.
Read-only comparison with the original copy proves zero missing baseline node,
discovery, ENR or P2P rows. All original discovery/ENR evidence rows are identical.
One latest-slot P2P row gained its new attempt; its original Hello fields and
Status remain intact. Live integrity is ok and foreign_key_check is empty.
The concurrent-live-audit.json records this distinction. Our validation tasks
never selected the live path for migration/enrichment/inspection/clear; every
owned validation store has an explicit isolated/copy path.

## Limitations, scope and checkpoint

No production country/ASN/hosting enrichment is claimed. Country precision is
network-level/approximate. Hosting/city/region classification is unavailable.
Same-version negative results are reused with no automatic refresh/retry; users
manage dataset updates and restart owners. Eligibility policy is conservative.
Provider contract assumes finite local lookup/close, not permanently blocking
arbitrary third-party plugins. This is address/dataset evidence, not a historical
network-state timeline.

No Historical Observations, change detection, analytics, maps/charts, Dashboard
v2, trends, distributed/cross-region measurement, propagation, alerts, public API
redesign, ETH/68, NAT redesign, reputation/VPN/proxy/Tor detection, threat
intelligence, arbitrary web geolocation, traceroute or triangulation is added.
Cryptographic identity, ENR trust and endpoint/NAT semantics remain unchanged.

The [manifest](geoip-asn-files.md) contains only milestone source, tests, docs and
validation tooling. Local databases/MMDB files/artifacts/logs/credentials are
excluded. Final closure requires the single requested commit, normal push,
HEAD/upstream equality and clean tree; the resulting SHA is recorded in the
final delivery report and Git log, without rewriting this validation evidence.
