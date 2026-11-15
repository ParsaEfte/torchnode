# Configuration and troubleshooting

Configuration is read at startup; changes are not live settings. Do not restart an existing production instance without separate authorization.

| Variable | Default / meaning |
| --- | --- |
| TORCHNODE_DB | `torchnode.db` relative to cwd; use an explicit absolute path |
| TORCHNODE_PORT | 8080 |
| TORCHNODE_BIND | Native 127.0.0.1; image 0.0.0.0 internally |
| TORCHNODE_COLLECTION_DISABLED | `true` blocks discovery and active inspection before acquisition; required for isolated recovery. It does not make the database read-only |
| JAVA_TOOL_OPTIONS | Image sets `-Dorg.sqlite.lib.path=/app/lib`; preserve it when appending JVM limits. Never put secrets here: the JVM prints this setting |
| TORCHNODE_SECURE_COOKIES | true; false only for isolated loopback HTTP development |
| TORCHNODE_P2P_HELPER | Executable helper override; invalid explicit paths do not silently fall back |
| TORCHNODE_DISCV5_HELPER | `target/torchnode-discovery-helper`; image uses `/app/torchnode-discovery-helper` |
| TORCHNODE_DISCV5_BOOTSTRAPS | Optional ENR file; see discovery ADR |
| TORCHNODE_P2P_STATUS_RPC_URL | Optional operator-trusted execution RPC, never auto-filled from a discovered peer |
| TORCHNODE_GEOIP_COUNTRY_DB / TORCHNODE_GEOIP_ASN_DB | Optional local MMDB files; not bundled/downloaded |

P2P timeout variables and exact bounds are documented in [README](../README.md). API limits and filters are in [API](api.md).

- Login loops over HTTP: check cookie Secure setting, scheme and same host; use false only on loopback development. Do not disable authentication.
- API 401/403: establish a CSRF-protected login session; exports need ADMIN. Keep cookie jars private.
- 429: respect Retry-After; login and topology admission are bounded. 413/503: narrow the time window/identity scope; do not interpret errors as empty evidence.
- Empty current window: evidence may be old; verify selected UTC scope using existing evidence views. No ingestion success is implied by HTTP liveness.
- Missing helper: build executable Go helpers in the installation layout; missing Auth/Hello/Status stays NOT_TESTED. Public IPv6/multi-client interoperability is not guaranteed by loopback tests.
- Missing GeoIP: DATASET_UNAVAILABLE is expected; no network download is attempted.
- Database failure / newer schema: stop the proposed operation and preserve diagnostics. Do not delete sidecars, checkpoint, repair or point an older binary at v6 as an improvised fix.
- Python safety checks reject SQLite: provide a compliant native Python runtime; do not lower the >=3.51.3 policy.

No production database query or restart is authorized by these troubleshooting notes. Use metadata-only reconciliation and separate approval gates.

Container SQLite startup diagnostics: the image carries the matching JNI library in read-only `/app/lib`, with searchable directory permissions and a readable library. Hardened tests keep `/tmp` `noexec`. A `failed to map segment` error requires reviewing the candidate library/path/mount configuration; it does not authorize changing production mounts or permissions.

Dependency downloads blocked by HTTP 403: preserve the error and use the pinned official advisory snapshot/checksum-verified scanner inputs in `tools/security-policy.json`. Security checks fail closed on missing inventory, unsupported ranges, stale policy snapshots or unresolved requests. Do not disable checksum verification or suppress findings to pass CI.


Current security/license engineering evidence (2026-11-14): [candidate closure report](release-validation.md). The candidate uses SQLite JDBC3.54.1.0 and a minimal pinned musl/Temurin Java21 runtime with a Java-only healthcheck. No shell, curl, package manager or system crypto/SQLite libraries are shipped. Zero image findings does not approve distribution, retained recovery, v5-policy replacement or deployment. Those gates remain explicit; deployment is NO-GO.
Use the configured Java entrypoint and Docker logs/health inspection for diagnostics; `docker exec sh`, curl and OS SQLite are unavailable by design. Keep `JAVA_TOOL_OPTIONS=-Dorg.sqlite.lib.path=/app/lib` when supplying additional JVM options. No production access is authorized by troubleshooting instructions.
