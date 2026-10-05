# Changelog

## [0.0.1] — 2026-10-05

First public release of TorchNode Observatory. See the
[release notes](docs/release-notes-v0.0.1.md) for capabilities, deployment,
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
