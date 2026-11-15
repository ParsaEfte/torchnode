# TorchNode Observatory public API v1

Authentication and export authorization now apply to all dashboard/API requests. Read [security and upgrade instructions](security-and-analytics.md) before following legacy unauthenticated examples; Docker health uses `/healthz`.

The local server exposes a read-only evidence API at `/api/v1`. `GET /api/v1` returns capability links and limits; `GET /api/v1/help` is browser help. API v1 is an external representation; SQLite observation schema v6 is a separate persistence version (v5 historical tables are retained). Results describe this datastore from one observer, not all Ethereum nodes.

## Resources

| GET path | Meaning |
| --- | --- |
| `/api/v1/identities` | Bounded cryptographic identity listing, lexical order |
| `/api/v1/identities/{identity}` | Identity plus labelled latest projection summary and links |
| `/api/v1/identities/{identity}/evidence/discovery` | Separate discv4, discv5, and ENR sourced discovery receipts with endpoint claims |
| `/api/v1/identities/{identity}/evidence/enr` | ENR observations and validation/trust evidence |
| `/api/v1/identities/{identity}/evidence/enrichment` | Address and dataset scoped lookup evidence |
| `/api/v1/identities/{identity}/runs` | Inspection run occurrences with P2P, RPC, and Beacon evidence |
| `/api/v1/identities/{identity}/changes` | Derived ChangeEvents and source references |
| `/api/v1/analytics` | Existing Network Analytics report, once per request |
| `/api/v1/exports/analytics.csv` | Aggregate metric CSV |
| `/api/v1/identities/{identity}/exports/{kind}.csv` | Identity scoped occurrence CSV; kind is `discovery`, `enr`, `enrichment`, `runs`, or `changes` |

Discovery, ENR, and run source occurrences have single resource paths ending in `/{occurrenceId}`. `previousSource` and `currentSource` in changes link to these paths where present. IDs are source scoped references, not timestamps or a promise of contiguous observations. Unknown timestamps remain null. Equal timestamps do not establish causal order.

The identity path uses a normalized 128 digit hexadecimal public key. Shared IP addresses do not merge identities. One identity can have multiple addresses, address families, ports, and observations. Advertised IPv6 does not prove reachable IPv6. A Hello listen port is a session claim. The latest projection is a convenience summary, not a canonical endpoint or complete history. RPC and Beacon outcomes remain independent of P2P stages. `NOT_TESTED` is distinct from `FAILED`; unsuccessful candidate probes do not prove absence. ENR `usable` follows structural validity, signature validity, and identity match. Geography is address and dataset context; ASN organization is not hosting proof. ChangeEvents are derived observations, and a first observation is not physical creation.

## Requests and responses

Collections use `limit=20` by default, at most 50. A response has `apiVersion`, `data`, and `pagination` with `limit` and nullable `nextCursor`. Pass the opaque cursor as `cursor=...`; do not parse it. Identity listing orders by public key. Histories order by domain timestamp descending and occurrence ID for equal times. The ID tie breaker is deterministic, not causal. Deleted or wrong identity cursors return 400. No arbitrary search, sort, or SQL expression is accepted.

Analytics accepts `start` and `end` as UTC ISO-8601 instants for a `[start,end)` window of at most 31 days. With no bounds, it defaults to the previous day. `mode=all` requests all available evidence subject to existing safety limits. The scope, units, denominators, unknowns, overlapping bucket semantics, and single-observer population warning are in the report. Future enum values may appear. Clients should ignore unknown fields and handle unknown enum values; breaking semantic changes require a future API version.

Examples require a private authenticated session cookie jar obtained through the CSRF-protected login flow described in [security](security-and-analytics.md). Never commit or share the jar. Values below are synthetic. Replace `{identity}` with a full 128 digit hex key and `{cursor}` with a returned cursor:

```sh
curl -b /PRIVATE/session.cookies http://127.0.0.1:8080/api/v1
curl -b /PRIVATE/session.cookies 'http://127.0.0.1:8080/api/v1/identities?limit=20'
curl -b /PRIVATE/session.cookies 'http://127.0.0.1:8080/api/v1/identities?limit=20&cursor={cursor}'
curl -b /PRIVATE/session.cookies 'http://127.0.0.1:8080/api/v1/identities/{identity}'
curl -b /PRIVATE/session.cookies 'http://127.0.0.1:8080/api/v1/identities/{identity}/evidence/discovery?limit=20'
curl -b /PRIVATE/session.cookies 'http://127.0.0.1:8080/api/v1/identities/{identity}/runs'
curl -b /PRIVATE/session.cookies 'http://127.0.0.1:8080/api/v1/identities/{identity}/changes'
curl -b /PRIVATE/session.cookies 'http://127.0.0.1:8080/api/v1/analytics?start=2026-01-01T00:00:00Z&end=2026-01-02T00:00:00Z'
curl -b /PRIVATE/session.cookies -OJ 'http://127.0.0.1:8080/api/v1/identities/{identity}/exports/discovery.csv'
```

An identity collection response has this shape:

```json
{"apiVersion":"v1","data":[{"identity":"<128 hex digits>","links":{"self":"/api/v1/identities/<128 hex digits>"}}],"pagination":{"limit":20,"nextCursor":null}}
```

An error is JSON, for example:

```json
{"apiVersion":"v1","error":{"code":"invalid_limit","message":"limit must be a positive integer"}}
```

Invalid parameters return 400, unknown identities or occurrences 404, unsupported mutations 405, and safety limit violations 413. Unexpected errors return a generic 500. No SQL, paths, or stack traces are returned. JSON and CSV use UTF-8; responses set `Cache-Control: no-store`. Authentication and role checks are enforced; unauthenticated API reads return 401 and VIEWER export requests return 403. Login is throttled; there is no aggregate API rate limiter or permissive CORS. Keep deployment operator controlled; loopback binding is the default.

## CSV

Identity scoped occurrence CSV columns are fixed: `api_version,identity,evidence_type,occurrence_id,observed_at,source,payload_json`. The JSON payload preserves domain fields, source, provenance, endpoint family/transport/purpose, protocol outcomes, ENR status, dataset context, or derivation references as applicable. Empty CSV cells mean unavailable values, never zero or false. The scope is the identity and kind in the request URL; API version, generated time, and row unit are also response headers. Identity scoped exports default to 99 and reject larger results with 413. There is no network wide peer endpoint CSV.

Analytics CSV columns are `scope_mode,start_inclusive,end_exclusive,metric_id,counting_unit,denominator_meaning,denominator,unknown,bucket_semantics,bucket_label,count`. Categories may overlap. Text cells beginning `=`, `+`, `-`, or `@` gain a leading apostrophe before CSV quoting to prevent spreadsheet formula execution. Fixed server filenames prevent header injection. The old `/export.csv` returns 410 because it exposed an unbounded peer endpoint inventory.

## Topology evidence explorer

Authenticated read-only endpoints under `/api/v1/topology` include `/graph`,
`/summary`, `/nodes/{identity}/neighborhood`, `/nodes/{identity}/evidence`,
`/compare`, `/candidates`, `/evidence/{evidenceId}`, `/edges/{observationId}` and
ADMIN-only `/exports/graph.json`. Bound all queries with UTC `start`/`end`
(maximum 31 days); filters include `protocol=all|discv4|discv5`, canonical identity
prefix `q`, observer UUID, `relation`, and
`availability=all|attributable|identity-only`.

Graph `limit` is 1–200 (default 100); `after` is a canonical identity keyset cursor.
Carry the returned `highWatermark` when paging to exclude newer relationship
observations. Legacy identities have per-request read snapshots; concurrent
backfills before the cursor require reloading. No cursor promises a frozen global
network snapshot. Neighborhoods include the focus and bounded attributed neighbors;
node/evidence limits can omit edges/endpoints and are explicitly reported.

Only independently revalidated eligible discv5 `REPORTED_NEIGHBOR` observations
become directed edges: a responder reported a target ENR, **not an active TCP/RLPx/
ETH connection**. Preserve each edge's observation references and inspect immutable
source evidence. DiscV4/partial/unattributed candidates and legacy identities do
not become inferred connections. Missing relationship measurements remain unknown;
a measured empty response is distinct from unavailable evidence. Degree,
components, network coverage and population/connectivity claims remain unavailable.

Work limits: 50,000 legacy occurrences, 2,000 relationship observations, 50,000
bounded diagnostic rows, 200 graph identities, 400 rendered relationships, and 20
identity evidence occurrences. Oversized work returns 413 without a partial aggregate;
native SQLite deadline/cancellation returns 503. Two concurrent topology reads are
admitted; excess reads return 429. Use the response's coverage, truncation and
pagination fields rather than treating a displayed page as complete coverage.
