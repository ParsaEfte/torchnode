> **Recovery NOT VERIFIED.** These installation examples apply only to new, empty non-production storage. Existing-data use is outside tested support. The original operator’s production deployment remains prohibited without a verified recovery policy and separate authorization; these operational limits do not restrict recipients’ open-source license rights.

# Deployment and controlled upgrade

v1.0.0 is publicly released for new, empty non-production installations. Authentication is required, observation schema is v6, health is static `/healthz`, and a production upgrade is not yet accepted. The old published 0.0.1 image has different security/schema behavior. Do not substitute it for this candidate or use it as an unverified rollback binary on v6.

## New, empty installation only

Docker builds both Go helpers and the Java JAR. The image runs UID/GID 10001, stores `/data/torchnode.db`, binds internally to 0.0.0.0:8080, and Compose publishes host loopback. Use one instance per database volume. Do not mount an existing installation into these commands.

```sh
docker compose build
# Interactive provisioning against a NEW volume; initializes schema v6.
docker compose run --rm --no-deps torchnode --provision-admin
# For loopback HTTP development only:
TORCHNODE_SECURE_COOKIES=false docker compose up -d
docker compose logs -f torchnode
```

Provisioning needs a TTY and no default credentials exist. Secure cookies remain required for HTTPS exposure. Add external TLS/access control/aggregate rate limiting. Login throttling alone is insufficient. `docker compose down` preserves volume data; `down -v` destroys it and is not a routine upgrade step. No inbound Ethereum port is published. Optional MMDB mounts are in `compose.geoip.yaml`; datasets have separate terms.

The released linux/amd64 image is `parsa202089/torchnode:1.0.0`; the internal Maven JAR version may retain `1.0-SNAPSHOT`. The published v1.0.0 image was verified by manifest digest `sha256:491d00e1af431f87226ad7b9ce651b97afe5b6b2b97d40d679ba027fe198f13c`; current distribution supports linux/amd64. Arm64 needs separate runtime validation.

## Existing installation: distinct approval gates

1. **Online backup:** fresh metadata/process/runtime/hash-bound expiring single-use approval for one source. Monitoring/abort/retention are mandatory. Risk acceptance does not authorize execution. Do not copy an active DB file.
2. **Offline migration validation:** after human acceptance of the verified online backup, obtain separate permission naming its immutable hash, disposable workspace, tool/JDK hashes and resource bounds. Only that copy may undergo v5-to-v6 dry-run. Compare historical schemas/counts/digests, integrity/FK/history, idempotence, injected failure recovery and application fixture startup. Preserve original backup and reports.
3. **Cutover:** separate maintenance approval must identify old/new binary/helper/config hashes, exact source/target paths, process manager, stop/start order and rollback authority. No production migration, shutdown or restart is implied by either earlier approval.

## Controlled cutover plan — NOT executed

Record current process/config/observer identity and approved health evidence. Obtain an independently stored, verified rollback checkpoint. Stop the approved writer cleanly through its actual process manager, verify every writer closed, and take a **fresh final consistent v5 backup** after shutdown. An earlier online snapshot omits later writes and is not that final checkpoint. Preserve required observer identity/key material and configuration privately under separate authority.

Verify final backup integrity, exact history 1–5, historical counts/digests and hash before migration. Freeze reviewed artifacts/config. Start one approved upgrader/new instance only; writable startup performs schema initialization. Verify exact history 1–6, historical preservation, FK/integrity, authentication/roles, static HTTP, explicitly observed collector behavior, bounded topology provenance, and error/latency changes. No parallel writers or automated retry. Capture results before declaring cutover accepted.

## Rollback — separate explicit approval

Old-binary-on-v6 compatibility is unverified. Never point an old binary at migrated storage. Stop/reap the approved new writers, preserve failed artifacts, then restore the verified final **v5** checkpoint into a separately reviewed location with the previous compatible binary/helpers/configuration/observer state and correct ownership. Account for lost writes since that checkpoint. Test restore on a disposable volume first. Authentication behavior of legacy code may differ; keep access isolated. Check integrity/schema, historical counts/digests and operator-observed runtime health before rollback acceptance.

## Current v6 recovery target

The v5-specific procedure above remains the original, unfulfilled policy; a marker-6 retained snapshot cannot satisfy it. The operator selected v6-only recovery as the target, subject to [the explicit replacement policy](recovery-rollback-policy.md). No migration, backup, stop/start, restore or deployment is authorized by that target selection. Prepare and separately authorize only the exact disposable retained-data rehearsal package in [the recovery boundary](release-validation.md). A future v6 cutover needs a fresh final v6-compatible checkpoint after an explicitly approved writer shutdown, plus the accepted recovery artifact/configuration/observer tuple.

The candidate image carries the matching SQLite JNI library at `/app/lib/libsqlitejdbc.so`; preserve `JAVA_TOOL_OPTIONS=-Dorg.sqlite.lib.path=/app/lib` when setting other JVM options. Temporary storage can remain `noexec`. Synthetic validation publishes no host port and mounts only owned disposable storage. Never use the production Compose stack for these tests.

## Validation and release

CI runs Java/Python/Go/race/vet/DOM checks, enforces Python SQLite policy, builds linux/amd64 and tests a disposable container volume. Local Docker execution remains unverified when its daemon is unavailable. Never start a daemon or alter a production service just to satisfy this check. See [release checklist](release-checklist-v1.0.md), [configuration](configuration.md), [security](../SECURITY.md) and detailed [B6-B safety](release-validation.md).

Reproducible image engineering uses `tools/build-isolated-image.py` on an explicitly marked temporary source copy or a clean CI checkout. It fixes source timestamps, recompiles Java/Go and compares manifests/configs/archives. No registry push occurs. Container smoke consumes an immutable local ID. CI includes inventory, symbol and actual-image security gates; publication is manual and subject to the release environment. A failed image-security gate is not overridden by passing runtime tests.

The [third-party notices](../THIRD_PARTY_NOTICES.md) and [source distribution review](third-party-licenses.md) accompany the binaries. Source/notice completeness and the exact final release commit/version/checksums still require review before separately authorized publication.


Current security/license engineering evidence (2026-11-14): [candidate closure report](release-validation.md). The candidate uses SQLite JDBC3.54.1.0 and a minimal pinned musl/Temurin Java21 runtime with a Java-only healthcheck. No shell, curl, package manager or system crypto/SQLite libraries are shipped. Zero image findings does not approve distribution, retained recovery, v5-policy replacement or deployment. Those gates remain explicit; deployment is NO-GO.

## Current recovery stop

Both retained-checkpoint attempts are consumed and stopped before copying/container startup. Attempt 2 identified an st_dev difference; [cause and policy tradeoffs](release-validation.md) remain under review. No attempt 3 is justified under the unchanged binding. New installation examples above do not authorize touching an existing installation. Publication preparation is in [the release plan](release-publication-plan.md); publication and deployment remain separate approvals.
