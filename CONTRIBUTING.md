# Contributing

Use Java 21+, Maven, Go as specified in `p2p-helper/go.mod`, Node.js and Python with native SQLite >=3.51.3. Read [architecture](docs/architecture.md), [security](SECURITY.md) and [release gates](docs/release-checklist-v1.0.md).

Work only with disposable databases and ephemeral loopback servers. Never use a live database, observer key, credentials, approval package or public peer as a test fixture. If IntelliJ is running from `target/classes`, build in a separate source checkout/copy; do not replace its classes. Do not copy runtime data into that checkout.

Run `mvn test`, `mvn -DskipTests package`, Python checks in `tools/check-*.py`, Node DOM checks in `tools/check-*.mjs`, and Go test/vet/race. Build both helpers separately. Container validation requires an already available Docker daemon and disposable volume. See CI for exact commands.

Keep changes focused and add reproducible regressions for behavior fixes. Preserve timestamp/protocol/provenance distinctions and explicit unknowns. REPORTED_NEIGHBOR is a responder's report, not an active peer connection. Describe validation and limitations in PRs. Do not commit databases, sidecars, observer state, secrets, local approval reviews or generated outputs. MIT licensing applies to project contributions; retain third-party notices and review distribution obligations before releasing binaries.
