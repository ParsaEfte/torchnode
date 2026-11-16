# Changelog

## [Unreleased]

No changes recorded since v1.0.0.

## [1.0.0] — 2026-11-15

First public v1.0.0 release, with GitHub source and binary assets and the pinned Docker Hub `linux/amd64` image. The release is supported for **new, empty non-production storage only**; **Recovery is NOT VERIFIED** and production deployment remains **NO-GO**. See the [release record](docs/release-notes-v1.0.0-DRAFT.md) and [recovery policy](docs/recovery-rollback-policy.md).

### Features

- Session authentication with ADMIN/VIEWER permissions, CSRF protection, login throttling, console provisioning/reset and security audit records.
- Responsive dashboard, Deep Inspection, History, Changes, network analytics and printable reports.
- Append-only SQLite schema-v6 observations, attributed observers/runs, background evidence persistence and immutable provenance views.
- Directed discv5 topology from independently revalidated `REPORTED_NEIGHBOR` reports, bounded graph filters/neighborhoods and authenticated read-only APIs. Reported neighbors **do not** prove live TCP/RLPx/ETH sessions.
- Bounded online-backup and offline migration safety tools for controlled workflows.

### Build, security and distribution

- Patched the flagged Go modules; pinned the Go 1.26.11 helper toolchain and added offline vulnerability and dependency checks.
- Precompiled all JSP views in Maven/Docker builds; removed runtime ECJ; made JAR and container builds reproducible and verified the exact image with isolated smoke tests.
- Upgraded SQLite JDBC to 3.54.1.0; improved runtime hardening, security notices and tested replaceable LGPL-library packaging.
- Published corresponding source, third-party notices, checksums, SBOM, JAR and image archive. The covered application/geth-helper distribution follows GPLv3 while original MIT and component grants remain preserved.

### Upgrade warnings

Anonymous API access is removed. Opening a database for writable startup can apply schema migrations. **Do not attach existing or production storage to v1.0.0**: real-data recovery/rollback and migration compatibility are not approved. See [deployment](docs/deployment.md) and the [release checklist](docs/release-checklist-v1.0.md).

## [0.0.1] — 2026-10-05

First public release of TorchNode Observatory. See the
[release notes](docs/release-validation.md) for capabilities, deployment,
and known limitations.

- Discovery via discv4 and discv5, with cryptographic node identity, ENR
  validation, and endpoint provenance.
- Bounded P2P TCP, RLPx Auth, Hello, and ETH Status inspection, alongside
  independent RPC and Beacon probes.
- Durable historical observations, derived ChangeEvents, endpoint and
  network analytics, Dashboard, Deep Inspection, History, and reports.
- Read-only Public API v1 and bounded identity-scoped and analytics exports.
- Single-container Docker distribution with packaged Go helpers, persistent
  SQLite, health checks, non-root execution, and optional GeoLite2 datasets.

The SQLite schema is v5. This release adds no new observation domain or
persistence migration.
