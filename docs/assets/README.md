# README preview provenance

`dashboard-preview.png` and `deep-inspection-preview.png` are unretouched PNG captures from the actual Java 21 application, built with `mvn -o -DskipTests package` at upstream commit `5ceb1e8c3a716198836233dad316caf4ee5f88ea` on 2026-11-16. No application code or rendered DOM was changed for the images.

- Native headless Chrome, driven through the Chrome DevTools Protocol, used a fresh browser profile and a 1440 × 1120 CSS-pixel viewport at device scale 1.
- The existing top account bar was excluded through the screenshot clip, producing 1440 × 1058 images without account details, browser chrome, cookies or tokens.
- The normal `Main` / packaged-JAR entrypoint ran on an ephemeral loopback port with a new disposable SQLite database. An ephemeral ADMIN account was provisioned through `--provision-admin` using an interactive PTY; login used the ordinary CSRF-protected browser form.
- `TORCHNODE_COLLECTION_DISABLED=true` and `TORCHNODE_SECURE_COOKIES=false` applied only to this isolated loopback HTTP fixture. A process sandbox restricted application networking to loopback; browser requests were also restricted to the fixture origin.
- Sixteen synthetic identities and 64 synthetic discovery occurrences were generated locally through `SqliteNodeStore.saveObservation`, following the existing `HistoryReportsTest` fixture pattern. IPv4 addresses use RFC 5737 documentation ranges; IPv6 uses `2001:db8::/32`.
- Two locally signed ENRs use the existing `EnrFixtures` test signer with documentation-only endpoints, pass the real `EnrDecoder` signature/identity checks, and are saved through `saveEnrEvidence`. Their two derived discovery occurrences account for the Dashboard's 66 occurrences. All fixture provenance explicitly says `SYNTHETIC README DEMONSTRATION` and that no peer was contacted or packet received.
- No TCP/RLPx/ETH/RPC/Beacon probe or topology relationship was fabricated. Inspection runs are absent and protocol stages remain untested. The UI's zero attempt/response counts describe this synthetic fixture only, not absence of real-world connectivity.

Only these final screenshots and this provenance note belong in Git. Fixture generators, databases, session records, credentials, browser profiles and application logs remain outside the checkout and are not publication materials. Recovery remains **NOT VERIFIED**; these previews establish neither retained-checkpoint recovery nor production readiness.
