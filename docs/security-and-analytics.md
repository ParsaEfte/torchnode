# Secure administration and observation analytics

## Repository findings and implementation order

TorchNode remains a Java 21 application with embedded Tomcat 10.1, Jakarta servlets/JSP, vanilla JavaScript, SQLite, and Go discovery/P2P helpers. `DashboardServlet` owns UI routes; `PublicApiServlet` owns the read-only `/api/v1` contract. `SqliteNodeStore` retains v5 historical evidence and latest projections; new writable startup now adds topology evidence schema v6. Existing v5 upgrades require the [v1.0 approval gates](release-checklist-v1.0.md). Existing Dashboard v2 uses local DOM/SVG charts and a bundled world map, not a chart framework. History and Changes already retain source clocks and derivation rules. Reports already print through the browser.

Historical measurement counts in earlier development reports are not current production evidence or release acceptance. This documentation does not authorize querying or upgrading an existing database.

The approved implementation order was: (1) authentication and backend authorization, (2) shared responsive administration shell, (3) functional evidence pages, (4) statistical charts and historical comparisons, (5) reporting, deployment and regression tests. Existing collection safety constraints, helpers, observation tables and historical rows were retained.

## Authentication architecture

`AuthFilter` gates every HTTP request to dashboard, assets and API. Only GET/POST `/login` and GET `/healthz` are public. Health returns process readiness without observations. Login uses a session-bound CSRF token, including protection against login CSRF. Successful login invalidates the anonymous session and creates a fresh opaque cookie identifier. No password, role claim or bearer secret is placed in the cookie.

Passwords use existing Bouncy Castle Argon2id v19: 19,456 KiB memory, two passes, one lane, random 16-byte salt, 32-byte output. Passwords must be 12–256 characters; hashes include the algorithm parameters. Unknown usernames still perform password verification against a dummy hash. Passwords never enter observation records or audit messages. The configuration follows the [OWASP password-storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).

Tomcat holds sessions in memory with persistence disabled, cookie-only tracking, a 10,000-session cap, 30-minute inactivity expiry, and an additional eight-hour absolute expiry enforced by the authentication gate. Cookies are named `TNSESSION`, `Path=/`, HttpOnly, SameSite=Strict and Secure by default. Logout is CSRF-protected POST, invalidates the server session and expires the cookie. Sessions end on restart. Account version checks on every authenticated request revoke all old sessions after disabling, resetting a password or changing a role. See [OWASP session guidance](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html).

Login admission is limited to five attempts per normalized username and twenty per remote IP in fifteen minutes, including successful attempts. At most two password verifications run concurrently; the limiter holds at most 10,000 expiring keys. Rejections return 429 and Retry-After. Counters reset on restart, so exposed deployments need persistent edge throttling as well. Forwarded IP headers are deliberately not trusted; users behind one proxy share the remote-IP limit.

### Permission policy

| Operation | ADMIN | VIEWER |
|---|---|---|
| Dashboard, nodes, existing history, charts, reports | Yes | Yes |
| Read-only API GET/HEAD | Yes | Yes |
| Named `/exports/` API routes | Yes | No |
| Start/stop scans, run inspection, clear collected data | Yes, POST + CSRF | No |
| User management, audit, settings controls | Yes | No |
| API POST/PUT/DELETE/other mutation methods | Rejected | Rejected |

Viewing `/node?key=...` now reads stored evidence without probing. ADMIN starts a deep inspection with POST `/inspection/start` and a CSRF token. This intentionally changes the old GET-triggered inspection behavior. Existing scan/clear lifecycle barriers are unchanged. The existing destructive clear action remains an explicit administrator action; it does not clear authentication records.

The read-only API is intentionally no longer anonymous. Unauthenticated API requests receive 401 JSON, restricted export requests receive 403 JSON. Consumers must GET `/login`, retain the anonymous cookie, extract its form token, POST username/password/token with the same cookie jar, then retain the rotated session cookie for reads. No Basic authentication, API tokens, CORS access or bypass mode is introduced. VIEWER access to readable JSON still grants access to the underlying information; export-route permissions are not a data-loss-prevention boundary.

`/admin/users` creates users, changes roles/access, resets passwords, and displays the latest 100 audit events. An administrator cannot disable or demote their own account. Audit records cover provisioning, login outcomes, throttling, logout, account changes, action requests and detected expired/revoked sessions. Actions record requests, not a guarantee that the scanner operation succeeded. Database failures fail closed. Audit storage currently has no retention policy or external tamper-evident sink.

## Additive database changes

Historical v5 tables are retained; observation schema v6 adds append-only discovery evidence, observer/run attribution and validation. Startup, including provisioning/reset, constructs writable storage and can migrate v5 before prompting. Do not use these commands against an existing installation until separately approved. Security has its own transactional, idempotent schema version:

| Table | Purpose |
|---|---|
| `auth_schema(version)` | Security schema version 1; future unsupported versions fail closed |
| `auth_users(username, password_hash, role, enabled, version)` | Normalized authorized accounts and session revocation generation |
| `auth_audit(id, occurred_at, actor, event, remote_address)` | UTC authentication/administration audit events |

No historical observation columns, derivation versions, source clocks or evidence payloads are rewritten. Security tables survive clearing observations. Back up SQLite with the application stopped or SQLite's backup API before upgrading. A rollback to old application code would remove the authentication gate, so never expose an old version as a security rollback.

## Functional pages and reusable charts

The shared `layout.jsp` provides a top bar with user/role, logout, current scan status, application status and UTC label. A labelled hamburger toggles a collapsible icon sidebar on desktop and an off-canvas menu on mobile. Desktop preference is local to the browser. Keyboard focus, native buttons, disclosure and responsive wrapping remain available. No React or frontend dependency was added.

| Navigation | Route | Function |
|---|---|---|
| Overview Dashboard | `/` | Window KPIs, existing map/client/protocol charts, activity/comparison charts, latest-node search |
| Node Explorer | `/explorer` | Search/filter/sort/paginate latest identity projections; open stored evidence and run authorized inspections |
| Discovery Analytics | `/discovery` | Provider identity overlap, receipt activity, repeat sampling and endpoint categories |
| P2P Inspection | `/p2p` | Stacked stage states, capabilities, family outcomes and inspection comparisons |
| RPC & Beacon Monitoring | `/apis` | Independent candidate outcomes, recorded response-duration histograms, comparison and evidence tables |
| Network Statistics | `/statistics` | Typed endpoints, family outcomes, complete searchable metric definitions |
| Client Distribution | `/clients` | Evidence-based classifications, exact versions, advertised capabilities and prior-window comparison |
| Geographic Analytics | `/geography` | Bundled map, country/ASN memberships, lookup statuses and comparison |
| Historical Trends | `/trends` | UTC discovery/inspection activity, repeat receipts, failure-state evidence and historical comparison |
| Changes & Events | `/events` | Derived change categories, UTC event activity and window comparison |
| Reports | `/reports/network` | Methodology, measurements, optional previous-window comparison, CSV/JSON links and browser print/PDF |
| User Management | `/admin/users` | Account management and latest authentication audit records |
| Settings | `/settings` | Working scan start/stop controls, security policy and user-management access |

Deployment-only settings such as bind address, helper paths and offline GeoIP datasets remain environment configuration; the settings page does not pretend these can be changed live.

`dashboard-v2.js` reuses the original horizontal bars and map. It adds keyboard-focusable SVG marks with exact tooltips, stacked protocol bars, vertical grouped comparison bars, activity stems/points with lines only between adjacent recorded buckets, legends, UTC custom dates, quick 24-hour/7-day/30-day ranges, searchable evidence tables, JSON/CSV links and printable reports. Empty measurements and failed requests do not become invented samples. Zero-count bars have zero width. The exact table values are available alongside charts. Current-window counts and previous-window counts remain independent measurements.

Chart categories include execution clients/versions, discv4/discv5, endpoint families, each recorded P2P stage state, independent RPC/Beacon outcomes and durations, countries/ASNs, changes, capability memberships, and discovery/inspection activity. Some high-cardinality displays retain the existing explicit top-category limits; tables and methodology identify these limits. Individual identity drill-downs are bounded to 50 identities per page in the requested evidence window and link to Node Explorer and stored API evidence. Latest projections may differ from historical evidence.

## Analytics and report additions

| Endpoint | Representation |
|---|---|
| `/analytics.json?start=...&end=...` | Existing UI report with additive metrics |
| `/analytics/compare.json?start=...&end=...` | `current` and `baseline` reports; baseline defaults to preceding equal-duration window; optional `baselineStart`/`baselineEnd` |
| `/api/v1/analytics/compare?start=...&end=...` | v1 envelope containing current and previous equal-duration reports |
| `/analytics/identities.json?start=...&end=...&domain=all` | Window-scoped distinct identities, max 50; `after` identity cursor, domains all/discovery/inspection/enr/changes |
| `/reports/network?start=...&end=...&compare=true` | Printable two-window report with denominator deltas and unknowns |

Positive bounded windows remain UTC [start-inclusive,end-exclusive), at most 31 days. Comparisons require bounded windows. Existing 250,000 eligible-evidence-row / 25,000 verification-run safety limits remain; each comparison report is checked independently. No partial aggregate silently replaces an oversized query. Identity drill-down is separately bounded by row count and SQL timeout. Existing read-only API identity/evidence resources and safe CSV serialization remain.

New metric IDs: `discovery-events-time`, `discovery-identities-time`, `inspection-runs-time`, `changes-time`, `repeat-discovery`, `distinct-endpoints`, `tcp-outcomes-time`, `family-tcp-outcomes`, `inspection-triggers`, `rpc-response-latency`, `beacon-response-latency`. Calendar buckets are hourly for windows at most two days, otherwise daily, clipped to the requested bounds. Identity counts are distinct within a bucket, not additive distinct identities across buckets. Endpoint uniqueness uses address/transport/purpose/port, not identity. A repeated receipt does not imply uptime.

Response-duration histograms use only nonnegative numeric `responseMs` from timestamped responding candidate attempts. The probes store the first usable method response duration; the selected method is not consistently identified in old evidence. These samples are neither whole-probe durations nor per-method service latency estimates. Missing or invalid duration samples remain unknown. RPC, Beacon and P2P are never combined into one reachability score.

Reports state generation time, UTC window, counting units, denominators, unknowns, evidence rules, bucket semantics, provenance domains and observer-local limits. Comparison deltas describe recorded activity, not population growth. The full JSON retains both bucket distributions and their denominators. Reports are computed from retained evidence, not durable report snapshots; later backfills may change a repeated query.

## Additional observability feasibility

A = available from stored evidence; B = needs additional collection/storage; C = not reliably inferable with this observer.

| Proposed feature | Class | Evidence / needed instrumentation |
|---|---|---|
| Client/version distribution | A | Authenticated Hello and responding RPC strings; retain conflicts/unknowns and parser limitations |
| Capability / ETH version advertisement | A | Successful authenticated Hello capability lists; advertisement is not protocol exercise |
| Discovery receipt trends / protocol overlap | A | Timestamped discovery receipts and canonical identity deduplication |
| Discovery attempt success/failure rate | B | Persist each request/attempt, provider, target, clock, outcome and observer limitation; receipts are not attempt denominators |
| Endpoint changes / observed churn | A | Existing endpoint ChangeEvents; detection frequency, not physical change time |
| Repeated identities / observed reappearance | A | Receipt clocks and gaps; implement configurable gap bins for a richer future report |
| ENR update frequency | A | Trusted sequence-change events and compatible prior ENRs; first-seen/invalid records are not updates |
| RPC / Beacon first-response duration | A | Stored numeric responseMs + candidate attempt clocks, now histogrammed separately |
| Per-method API latency / timeouts / percentiles across comparable requests | B | Method/path identity, monotonic duration for every attempt, timeout/cancel classification and consistent sampling |
| ASN concentration / country context | A | Address/dataset/category memberships; not identity ownership or hosting proof |
| Observed IPv6 endpoint adoption / TCP outcomes | A | Typed endpoints and actual family-specific stage attempts; preserve NOT_TESTED |
| Recorded inspection failure states | A | Actual diagnostic states; observer restrictions identifiable in existing stage metric rules |
| Stable cross-version failure categories | B | Versioned reason codes at every helper/probe boundary; free text alone is not a durable taxonomy |
| Protocol compatibility evidence / network ID disagreements | A | Existing NetworkVerification comparisons and actual stage states; not a consensus-membership assertion |
| Fork ID evidence | A | Recorded successful ETH Status payloads where present; can expose raw fields in reports |
| Fork compatibility trend against a reference | B | Persist trusted reference chain/fork context, comparison algorithm/version and each comparison result |
| Scan performance / measurement throughput | B | Persist scan IDs, start/end/cancel clocks, queue lengths, per-stage counters and monotonic durations; run-start activity is only a partial activity measure |
| Observation coverage changes | A | Independent window identity/provider/inspection coverage counts; no extrapolation to unseen peers |
| Data collection health | A/B | Current scan state, trigger activity, missing timestamps and unknowns are available; durable helper failures, queue health and scan completion need new events |
| Global Ethereum node count / adoption / uptime | C | Single-vantage discovery is biased and incomplete |
| Disappearance / actual downtime from missing receipts | C | Observer gaps cannot distinguish sampling, routing, filtering and peer behavior |
| Physical location, hosting ownership, NAT certainty from GeoIP/ASN alone | C | Approximate endpoint registration/network context does not prove these properties |

## Security and deployment

Native default binding remains `127.0.0.1`. Compose publishes `127.0.0.1:8080`; the container still binds internally to `0.0.0.0` for port mapping. Non-root UID, dropped capabilities, no-new-privileges and SQLite volume remain. `/healthz` replaces the protected API root in Docker health checks. Tomcat error pages suppress stack reports/server-version details. The HTTP gate adds no-store, nosniff, DENY framing, no-referrer, restrictive permissions, a per-response script CSP nonce and Secure-mode HSTS. Inline styles remain permitted for existing JSP/SVG charts; inline scripts require a nonce. Request form size is capped at 8 KiB and parameter count at 40.

The same Tomcat 10.1 line was updated to 10.1.61, and Bouncy Castle to the Java-compatible `bcprov-jdk18on` 1.86 artifact; the older transitive provider is excluded. No additional library was added. Release references: [Tomcat downloads](https://tomcat.apache.org/download-10.cgi), [Bouncy Castle releases](https://www.bouncycastle.org/download/bouncy-castle-java/). This is not a complete dependency vulnerability audit.

### First administrator and native launch

Use Java 21. Provisioning is console-only, prompts without echo, confirms the password and succeeds only when no authorized user exists. It has no HTTP setup endpoint, default credential, password command-line argument or password environment variable.

```sh
mvn test
mvn -DskipTests package
export TORCHNODE_DB=/absolute/private/path/torchnode.db
java -jar target/torchnode-1.0-SNAPSHOT-jar-with-dependencies.jar --provision-admin
# Loopback development over HTTP only:
TORCHNODE_SECURE_COOKIES=false java -jar target/torchnode-1.0-SNAPSHOT-jar-with-dependencies.jar
```

For production, retain Secure cookies and terminate HTTPS at a trusted reverse proxy on the same host/private network. Expose only HTTPS, restrict direct backend access, and keep database files/backup permissions private. The application does not trust forwarded headers or implement TLS itself. Do not set insecure cookies on a public HTTP deployment.

### Recover an existing administrator

There is no recoverable default password. If another administrator can sign in, use User Management to reset the account. Otherwise stop the application, select the same database and run this local interactive command:

```sh
export TORCHNODE_DB=/absolute/private/path/torchnode.db
java -jar target/torchnode-1.0-SNAPSHOT-jar-with-dependencies.jar --reset-admin-password YOUR_ADMIN_USERNAME
```

The command requires an existing ADMIN account, prompts for a new password twice without echo, replaces its Argon2id hash, enables the administrator, increments the account version to revoke all old sessions, and records `LOCAL_ADMIN_PASSWORD_RESET`. It refuses unknown users and VIEWER accounts. Restart afterward. Console recovery relies on trusted local operating-system/database access; it has no public HTTP endpoint. Do not edit hashes directly or put passwords in shell history.

### Docker

```sh
docker compose build
# Same named SQLite volume; interactive terminal required:
docker compose run --rm -it torchnode --provision-admin
# For loopback-only local HTTP development:
TORCHNODE_SECURE_COOKIES=false docker compose up -d
# For HTTPS reverse-proxy production, omit the override (default true).
```

| Variable | Default | Meaning |
|---|---|---|
| `TORCHNODE_DB` | `torchnode.db` native; `/data/torchnode.db` container | SQLite evidence and authorization datastore |
| `TORCHNODE_BIND` | `127.0.0.1` native; `0.0.0.0` container | HTTP listener; preserve host/network isolation |
| `TORCHNODE_PORT` | `8080` | Listener port |
| `TORCHNODE_SECURE_COOKIES` | `true` | Only exact `false` enables HTTP development cookies; Compose passes it explicitly |
| `TORCHNODE_P2P_HELPER` / `TORCHNODE_DISCV5_HELPER` | Existing helper defaults / packaged Docker paths | Unchanged inspection/discovery helpers |

Existing GeoIP/ASN variables and scan restrictions remain documented in deployment/enrichment docs. No credentials or new secrets belong in Compose or the repository.

## Verification and limits

See the final validation entry below for commands actually executed. Automated coverage includes password salts/verification, login success/failure, CSRF, secure cookie attributes, session rotation/logout/expiry/revocation, login throttling, both roles, protected API exports, navigation JSP rendering, additive/idempotent migrations, schema-v5 integrity, comparison responses, empty measurements, unknowns, NOT_TESTED and exact time boundaries. The existing Java discovery/inspection/history tests and Go helper tests remain part of regression validation. DOM tests exercise page-specific rendering, state labels, counts, map aggregation, and stale-response rejection after Clear.

Known limits: process-local sessions/rate limits; no MFA, SSO, token-based machine accounts, password recovery email, audit retention/sink or cross-vantage population model. Settings supports scan controls rather than live editing deployment variables. Queries retain existing scan/safety limits and can scan history because normalized timestamp expressions are not index-sargable. Data can arrive between independently calculated comparison windows; no cross-window immutable snapshot is promised. The map remains approximate offline enrichment. A controlled browser visual pass and container build/smoke should be run before public deployment when those facilities are available.

## Created and modified files

Created:

- `src/main/java/io/github/gavinruff007/torchnode/security/{Passwords,AuthStore,AuthFilter}.java`
- `src/main/resources/webapp/WEB-INF/views/{layout,workspace,admin}.jsp`
- `src/main/resources/webapp/assets/observatory.css`
- `src/test/java/io/github/gavinruff007/torchnode/dashboard/{AuthenticationTest,AuthTestSupport}.java`
- `tools/check-admin-layout.mjs`, `tools/check-provisioning.py`
- `docs/security-and-analytics.md`

Modified:

- Backend: `Main.java`, `analysis/NetworkAnalytics.java`, `dashboard/{DashboardServer,DashboardServlet,PublicApiServlet}.java`
- UI: `WEB-INF/views/{dashboard,inspection,analytics,report}.jsp`, `assets/dashboard-v2.js`
- Tests: `analysis/NetworkAnalyticsTest.java`, `dashboard/{DashboardV2Test,EnrDashboardTest,HistoryReportsTest,PublicApiTest}.java`
- Verification: `tools/check-dashboard-v2.mjs`, `tools/check-jsp-javascript.mjs`, `scripts/container-smoke.sh`, `.github/workflows/ci.yml`
- Deployment/docs: `pom.xml`, `Dockerfile`, `compose.yaml`, `README.md`, `docs/api.md`, `docs/deployment.md`

No discovery/inspection helper or observation-storage implementation was replaced.

## Validation commands

Validation uses the installed Microsoft OpenJDK 21.0.9 through JAVA_HOME. The default shell JVM was 25; final Java verification explicitly selected 21.

- `mvn -q test package` — 158 tests passed, zero failures/errors/skips; packaged JAR built successfully.
- `go test ./...` in `p2p-helper` — 43 tests passed in two packages.
- `node --check src/main/resources/webapp/assets/dashboard-v2.js` — passed.
- `node tools/check-jsp-javascript.mjs` — dashboard, inspection and shared-layout inline scripts passed syntax checks.
- `node tools/check-dashboard-v2.mjs` — counts, unknowns, map aggregate, page-specific rendering and stale Clear response checks passed.
- `node tools/check-admin-layout.mjs` — desktop collapse, mobile menu, aria-expanded and Escape focus checks passed.
- `node tools/check-history-ux.mjs` — history counts, grouping, run distinctions and escaping passed.
- `node tools/check-network-enrichment.mjs` — enrichment statuses, family and provenance rendering passed.
- `python3 tools/check-provisioning.py` — actual packaged console provisioning/recovery, repeated-bootstrap refusal and schema-v5 preservation passed; disposable credentials were not printed.
- `bash -n scripts/container-smoke.sh` — passed shell syntax validation.
- `git diff --check` — passed.

Docker build/container smoke was not executed because the Docker daemon socket was unavailable. Browser adapters were unavailable, and the native browser capture did not produce a usable rendered preview, so no successful visual/mobile browser QA is claimed. HTTP tests render every navigation JSP, and DOM regression tests exercise layout/chart behavior; a real visual pass remains recommended. Local database inspection used read-only connections, and the temporary preview used a SQLite backup copy rather than serving the original datastore.

The later node-detail loading correction, its data-loading contract and verification record are documented in [node-detail-loading.md](release-validation.md).

## v1.0 candidate reconciliation (2026-11-13)

Earlier validation entries describe their historical source state. Current writable initialization/provisioning creates observation history 1–6; the updated isolated provisioning test checks that exact history and its preservation through credential reset. Earlier claims of schema-v5 preservation must not be used as evidence that current Main avoids migration. See [current validation](release-validation.md) and [release gates](release-checklist-v1.0.md).

## Current candidate security/recovery engineering

The historical results above describe earlier source states. Current dependency pins, the completed inventory, real Main/JAR and container checks, immutable image evidence, browser/contrast results and remaining release gates are maintained in [release gates](release-checklist-v1.0.md). The original v5 rollback policy remains active; [v6 policy](recovery-rollback-policy.md) is pending explicit replacement approval.

Authentication regressions now cover malformed/injection-like topology parameters, viewer API write rejection, absence of database/SQLite implementation details in validation errors, and no password/password-hash leakage in auth audit/server logs. These checks use isolated fixtures. `TORCHNODE_COLLECTION_DISABLED=true` blocks both discovery and active inspections, and no production state is used to validate it.

Image verification uses a preinstalled, pinned SQLite JNI library with read-only permissions; `/tmp` remains `noexec`. CI security checks fail on image findings and unavailable evidence, and publication requires a deliberate manual request plus the release environment. Remote CI on the final reviewed commit, distribution licensing and human acceptance remain separate gates.
