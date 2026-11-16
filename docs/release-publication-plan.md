# v1.0.0 publication record

**Status: PUBLISHED — 2026-11-15.** This document records the completed non-production public release. It supersedes the earlier pre-publication action plan; do **not** re-run release/tag/upload steps based on an old draft.

- Source repository: [ParsaEfte/torchnode](https://github.com/ParsaEfte/torchnode).
- Release and tag: [v1.0.0](https://github.com/ParsaEfte/torchnode/releases/tag/v1.0.0).
- Pinned release commit: `a1997628d0561259601157c262c6652bf049a4be`.
- Docker Hub: [parsa202089/torchnode:1.0.0](https://hub.docker.com/r/parsa202089/torchnode/tags), `linux/amd64`, OCI manifest `sha256:491d00e1af431f87226ad7b9ce651b97afe5b6b2b97d40d679ba027fe198f13c`.
- Matching source and notices, checksums, SBOM, JAR and image archive: available as GitHub release assets.

## Completed publication checks

1. The approved sanitized commit and immutable tag were published. Remote CI passed on `main` and `v1.0.0` at publication time.
2. Complete matching corresponding-source and notices assets were uploaded before object code; anonymous downloads and SHA-256 checks succeeded.
3. GitHub JAR, image archive and other assets were uploaded; anonymous download verification matched their approved checksums.
4. The approved image was copied to Docker Hub without rebuilding. A fresh unauthenticated pull of all image blobs completed and resolved to the exact approved OCI manifest digest.
5. The documented owner-directed distribution disposition, required source/relink materials and non-production publication exception were recorded. These do not imply independent legal advice or production approval.

## Operational boundary

> [!WARNING]
> **Recovery is NOT VERIFIED.** Retained-data recovery, existing-database migration, v5 rollback, crash/WAL restoration and production deployment are **not** accepted. This release is intended for **new, empty non-production storage** only. Operational support limitations do not restrict open-source recipient rights.

The source code, tag, release assets and pinned Docker manifest must remain consistent. Never silently substitute a rebuilt image or overwrite an immutable version tag. A future release or production deployment requires its own validation, approvals and recovery plan.

See [the release checklist](release-checklist-v1.0.md), [deployment guidance](deployment.md), [recovery policy](recovery-rollback-policy.md), [distribution resolution](public-distribution-resolution.md) and [release notes](release-notes-v1.0.0-DRAFT.md).
