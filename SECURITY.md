# Security and privacy

This candidate requires session authentication. ADMIN can mutate collection state and export; VIEWER can read stored evidence. Use TLS and Secure cookies for exposed installations, access control and external aggregate rate limiting. Login throttling is in-memory and not a substitute for an edge policy. Forwarded IP headers are not trusted. See [security implementation](docs/security-and-analytics.md).

Observation data can contain IP addresses, node identities, advertised endpoints, software strings and clocks; authentication audit records include user and remote-address context. Treat databases, exports, backups and console output as sensitive. No automated retention/deletion policy is provided. GeoIP is optional local dataset context; no remote map/geolocation lookup is required. Historical evidence and raw provenance are not proof of an actual peer connection.

Never put passwords, cookie jars, private discovery keys, production database files or one-use approval documents into issues or commits. Local approval reviews are ignored by Git and Docker context. An owner/root can still replace local files; private permissions are not adversarial immutability. Runtime/tool hashes do not attest the whole operating system or every linked library.

Report suspected vulnerabilities privately through the repository's private vulnerability reporting facility if enabled; otherwise ask the maintainer for a private reporting channel without posting exploit details or secrets publicly. No private contact address is invented here. No external penetration test or complete dependency audit is claimed.

Existing v5 startup/provisioning can migrate storage: follow [release gates](docs/release-checklist-v1.0.md), never use startup to inspect production. Backup, offline real-backup migration validation and production cutover each require separate approval.

## Candidate dependency and image gates

Jackson databind/core/annotations are aligned at 2.18.11. The ten previously flagged Go modules were upgraded to patched versions; release helper binaries use Go 1.26.11. Canonical JSON/hash implementation semantics are unchanged and compatibility fixtures remain mandatory. Exact affected ranges, baseline import/symbol reachability and dispositions are in [the advisory evidence](docs/release-validation.md).

`tools/check-release-security.py` uses the complete pinned official Go advisory snapshot plus primary GitHub advisory metadata, and OSV for Maven runtime inventory. It rejects stale review inputs, incomplete responses and unsupported version ranges. `tools/check-image-security.py` uses checksum-pinned Grype without ignore rules or severity filtering; any finding keeps its gate failed until remediation or explicit reviewed disposition. An empty application-dependency result is not a clean OS image result. No unresolved risk is automatically accepted.

The image preinstalls the exact SQLite JNI library from the candidate JAR so a read-only root and `noexec` temporary filesystem work together. Preserve private auth/audit data, avoid credentials in JVM options/logs, and use collection-disabled environments for recovery validation.

Dependency licensing is a separate release requirement: [notices](THIRD_PARTY_NOTICES.md) include GPL/LGPL/EPL declarations and matching source materials. Project MIT licensing does not waive them. No publication or replacement of the v5 rollback gate is approved by these engineering changes.


Current security/license engineering evidence (2026-11-14): [candidate closure report](docs/release-validation.md). The candidate uses SQLite JDBC3.54.1.0 and a minimal pinned musl/Temurin Java21 runtime with a Java-only healthcheck. No shell, curl, package manager or system crypto/SQLite libraries are shipped. Zero image findings does not approve distribution, retained recovery, v5-policy replacement or deployment. Those gates remain explicit; deployment is NO-GO.
