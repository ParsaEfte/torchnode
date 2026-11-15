# v1.0 v6-compatible recovery policy — pending explicit approval

v6-only recovery is the selected TARGET. The original v5 rollback requirement remains ACTIVE and UNFULFILLED until the operator explicitly approves its replacement. Deployment is NO-GO. This document authorizes no retained-data use, backup, migration, shutdown, restore, restart or publication.

The retained standalone snapshot has previously verified observation history 1–6 and the v6 observer/run, exchange, validation and immutable relationship objects. Its filename `backup-v5.sqlite` does not establish its schema. The earlier integrity/foreign-key/schema and canonical verification passed, including all 9,109 canonical evidence hashes. Zero promoted relationships is consistent with no eligible attributable discv5 exchanges. These are previously recorded facts; no fresh retained-source inspection was performed for this engineering work.

A v5-only binary cannot be assumed to understand or safely write the v6 evidence model. Do not change marker 6, remove v6 objects, invent a v5 checkpoint or use a legacy binary as a security rollback. The target recovery artifact must be the proven v6-compatible application/helper/image tuple, with its configuration, observer provenance and compatible checkpoint explicitly bound.

## Required recovery evidence

1. Build and identify the exact candidate JAR, Linux helpers, native SQLite library and immutable image. Verify reproducibility, container isolation, real entrypoint/authentication/topology, disabled collection, controlled process shutdown/restart and security/license dispositions.
2. Separately authorize exactly one retained-data rehearsal, naming source SHA-256 `CHECKPOINT_SHA256`, the recorded identity/permissions, final image/artifact hashes, a fresh private disposable workspace, safety budgets and cleanup ownership. No broad completion request substitutes for this approval.
3. After that approval only, verify original identity/hash, create disposable copies, verify inventories and exercise the approved recovery design. Require preserved historical schema/counts/digests and unchanged original bytes/metadata. Only the explicitly permitted disposable security-schema/auth records may change. Abort on failure; consume the attempt without automatic retry.
4. Review the results and accept retained-checkpoint recovery evidence explicitly. Synthetic tests and a successful HTTP response alone do not satisfy this requirement.

The adapter rehearsal proves checkpoint readability/startup/authenticated bounded topology and preservation for the pinned tuple. Actual Main/JAR/process-manager behavior is tested separately on synthetic data. It does not prove Main on retained history, abrupt-crash/WAL recovery, checkpoint completeness or production restore/cutover behavior.

## Final checkpoint before a future cutover

The retained diagnostic snapshot is not a final deployment checkpoint and says nothing about current production ingestion, durable-write progress or data since its capture. Before any future cutover, a separate authorization must name the actual process manager and all writers, previous/new artifacts/configuration/observer identities, health criteria, downtime limits and recovery authority. Only then may writers be cleanly stopped and a fresh consistent final checkpoint of the verified current schema be taken and independently checked. A v6 target requires a proven v6-compatible checkpoint/recovery tuple; an earlier online snapshot omits later writes.

No such production action is part of Tasks 1–4. Preserve failed artifacts and account for writes after any checkpoint when reviewing future recovery decisions.

## Exact policy decision for later approval

> I approve replacement of the historical v5-only rollback requirement with v6-compatible checkpoint recovery for the reviewed v1.0 deployment scope, conditional on successful separately authorized retained-data rehearsal, explicit acceptance of its evidence and a separately approved fresh final checkpoint/cutover plan. No v5-only rollback guarantee is made. This policy decision authorizes no retained-data rehearsal, production backup, migration, shutdown, restart, restore, deployment, publication or acceptance-marker publication.

This statement has **not** been approved. Until it is, the original v5 gate stays active even if engineering tests pass. See the [rehearsal boundary](release-validation.md), [license review](third-party-licenses.md) and [release checklist](release-checklist-v1.0.md).

## Device-identity preflight stop

The two consumed attempts did not establish retained-data recovery. The second reported only a filesystem device-ID mismatch; original bytes matched. The [device identity assessment](release-validation.md) does not change the policy, expected metadata or authorization requirements. Resolve provenance and obtain separate reviewed decisions before preparing any third attempt.

## Approved non-production publication exception

The current release checklist and publication plan require successful retained-checkpoint recovery and policy acceptance before publication. The owner-approved exception below does not mark recovery checks passed.

Exact operator decision: “I approve NONPROD-V1-RECOVERY-DEFER-1 solely for public v1.0.0 source and Docker publication for new, empty non-production installations. Successful retained-data recovery, acceptance of recovery evidence and replacement of the v5 rollback requirement are deferred for that publication scope only. Recovery remains NOT VERIFIED and must be stated in release notes and installation guidance. The original v5 rollback/deployment gate remains active and unfulfilled. No retained-data access, recovery attempt, existing-data use or production deployment is authorized.”

The owner explicitly approved this exception for non-production source/Docker publication. It does not change production rollback requirements, authorize recovery or assert verified recovery. Non-production remains tested/support scope, not a recipient license restriction.
