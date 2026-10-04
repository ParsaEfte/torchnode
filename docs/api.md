# TorchNode Observatory public API v1

The local server exposes a read-only evidence API at `/api/v1`. `GET /api/v1` returns capability links and limits; `GET /api/v1/help` is browser help. API v1 is an external representation; SQLite schema v5 is a separate persistence version. Results describe this datastore from one observer, not all Ethereum nodes.

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

Examples use synthetic values. Replace `{identity}` with a full 128 digit hex key and `{cursor}` with a returned cursor:

```sh
curl http://127.0.0.1:8080/api/v1
curl 'http://127.0.0.1:8080/api/v1/identities?limit=20'
curl 'http://127.0.0.1:8080/api/v1/identities?limit=20&cursor={cursor}'
curl 'http://127.0.0.1:8080/api/v1/identities/{identity}'
curl 'http://127.0.0.1:8080/api/v1/identities/{identity}/evidence/discovery?limit=20'
curl 'http://127.0.0.1:8080/api/v1/identities/{identity}/runs'
curl 'http://127.0.0.1:8080/api/v1/identities/{identity}/changes'
curl 'http://127.0.0.1:8080/api/v1/analytics?start=2026-01-01T00:00:00Z&end=2026-01-02T00:00:00Z'
curl -OJ 'http://127.0.0.1:8080/api/v1/identities/{identity}/exports/discovery.csv'
```

An identity collection response has this shape:

```json
{"apiVersion":"v1","data":[{"identity":"<128 hex digits>","links":{"self":"/api/v1/identities/<128 hex digits>"}}],"pagination":{"limit":20,"nextCursor":null}}
```

An error is JSON, for example:

```json
{"apiVersion":"v1","error":{"code":"invalid_limit","message":"limit must be a positive integer"}}
```

Invalid parameters return 400, unknown identities or occurrences 404, unsupported mutations 405, and safety limit violations 413. Unexpected errors return a generic 500. No SQL, paths, or stack traces are returned. JSON and CSV use UTF-8; responses set `Cache-Control: no-store`. No permissive CORS, authentication system, or rate limiter is supplied. Keep deployment operator controlled; loopback binding is the default.

## CSV

Identity scoped occurrence CSV columns are fixed: `api_version,identity,evidence_type,occurrence_id,observed_at,source,payload_json`. The JSON payload preserves domain fields, source, provenance, endpoint family/transport/purpose, protocol outcomes, ENR status, dataset context, or derivation references as applicable. Empty CSV cells mean unavailable values, never zero or false. The scope is the identity and kind in the request URL; API version, generated time, and row unit are also response headers. Identity scoped exports default to 99 and reject larger results with 413. There is no network wide peer endpoint CSV.

Analytics CSV columns are `scope_mode,start_inclusive,end_exclusive,metric_id,counting_unit,denominator_meaning,denominator,unknown,bucket_semantics,bucket_label,count`. Categories may overlap. Text cells beginning `=`, `+`, `-`, or `@` gain a leading apostrophe before CSV quoting to prevent spreadsheet formula execution. Fixed server filenames prevent header injection. The old `/export.csv` returns 410 because it exposed an unbounded peer endpoint inventory.
