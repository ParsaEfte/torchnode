# Distribution compliance engineering — owner-directed disposition

This is the evidence for the owner-authorized internal compliance review, not an independent legal opinion. No GitHub release or image publication has occurred. The project MIT license does not cover all dependencies or combined executables.

| Component | Applicable evidence / obligation | Engineering implementation | Operator decision |
|---|---|---|---|
| RLPx helper / geth1.17.6 | Compiler inputs include GPL3+ bintrie source and LGPL3+ library code; conservative GPL3+ helper route; complete matching source/build instructions/notices with combined binary | Own helper source, complete vendor tree, source-built Go1.26.11 helpers, offline vendor rebuild/tests/vet, geth COPYING files and per-file declaration | Owner-directed review adopts GPLv3 application/helper scope; no runtime ECJ definitions remain. Immediate matching source and notices must be delivered before binaries. |
| Lanterna3.1.2 | LGPL3+ source, GPL/LGPL texts, copyright, modification/reverse-engineering and replacement/relink rights | Source JAR and notices preserved; all206 main Java sources rebuilt; thin application/separate libraries and replacement JAR prepared; Main auth/topology and controlled restart passed with replacement | Immediate source/relink delivery route implemented; no conflicting end-user restrictions. Test the final replacement layout and verify actual public source access at delivery. |
| JNR-POSIX3.1.15 | Exact tagged root LICENSE grants EPL2/GPL2/LGPL2.1;26 per-file headers also offer later GPL/LGPL versions;98 files have no individual header | All124 source files exactly match tagged project; full root LICENSE preserved. Candidate elects LGPL2.1 for the whole module, retaining original alternatives and per-file extra grants | Historical references and root alternatives preserved; LGPL2.1 route retained without inventing later-version grants. |
| ECJ3.33.0 | EPL2 source/notices/source access for redistribution | Original candidate includes runtime ECJ. Default v1.0.0 build removes runtime ECJ; matching materials retain the unmodified build tool and notices | Owner-directed disposition records separate EPL2 build-tool treatment, full source/notices and generated-output provenance; no secondary-license grant asserted. |
| JFFI/native classifier1.3.9 | Apache/LGPL alternatives where expressly offered; libffi and native third-party notices | Exact native/source JARs plus full JFFI source archive including276 libffi C/header files, build.xml and native build files | Per-file source/declarations retained; no blanket Apache election. Complete source/native build materials and replacement mechanism supplied. |
| OpenJDK/Temurin21.0.13+9 | GPL2 and applicable per-file Classpath Exception; independent third-party notices | Exact official115MB source archive verified against release SHA256, matching Alpine JRE build metadata, pinned vendor build scripts, full runtime legal directory and build recipe | Exact corresponding source/build/legal files retained with actual per-file exception scope; no independent JRE binary rebuild claim. |
| Four final Alpine packages | muslMIT; timezone public-domain; alpine-releaseMIT; CA bundleMPL2+MIT | Exact package versions/commit recipes, patches and upstream sources verified against recipe SHA512s; complete614 file records copied into final image | Verify matching material access before image publication. No BusyBox, GNU baselayout, system zlib or crypto executable/library is distributed in final layers. |
| Go standard runtime | BSD3 notices; compiler/runtime source provenance | Exact Go1.26.11 LICENSE/PATENTS and source tree archived from the pinned builder; vendor source rebuild verified | No blanket GPL exception asserted; actual source and notices supplied. |
| Other Maven/Go dependencies | Apache/BSD/MIT/CC0 and component-specific notices |46 Maven components and51 Go license entries, source/binary hashes, license texts and copyright notices; exact-hash reconciliation of scanner metadata gaps | Bloomfilter repository-root full MIT license recovered; every Go source/go.mod matches tagged v2 subtree. Earlier missing-license finding is resolved, not waived. Original copyright preserved. |

The [distribution notice](../third-party/DISTRIBUTION.md) and [component inventory](release-validation.md) preserve actual declarations. Raw image scanners omit many license fields and sometimes infer incorrect Maven coordinates. The reconciliation matches exact JAR hashes to resolved POM/source evidence; it does not silently declare missing metadata permissive. The original scanner outputs remain intact.

## Intended GitHub and container delivery

Publish the exact matching source bundle, notices, checksums and replaceable Java-library layout beside binary downloads, with equivalent access and no extra terms limiting rights to modify/relink or debug library modifications. Publish corresponding source access information alongside the container digest; the candidate supplies `/app/THIRD_PARTY_NOTICES.md`, `/app/third-party/` and `/opt/java/openjdk/legal/`. No speculative public URL or written-offer promise substitutes for sources actually prepared. No URL has been published.

The helper source/vendor tree can be rebuilt offline with Go1.26.11 and the documented `-mod=vendor -buildvcs=false -trimpath` flags. Java source/POM and dependency JARs/source JARs are supplied. A thin app permits replacing an interface-compatible library without rebuilding the application:

```sh
java -cp 'replacement/lanterna-rebuilt.jar:lib/*:torchnode.jar' io.github.gavinruff007.torchnode.Main
```

This is recipient launch syntax, not authorization to start the operator application or use its data. Actual validation used a disposable synthetic database. Recovery image pins protect the one specific authorized attempt; they do not impose a licensing lock on a recipient's modified build. The isolated-source marker is self-created build safety metadata, not an installation key.

## Modification record

On2026-11-14 the project changed its SQLite JDBC selection to3.54.1.0, added a Java loopback healthcheck, moved the smoke HTTP client to test-only classes, replaced the Python controlled-trace fixture with the equivalent bounded Java fixture, and assembled a minimal musl/JRE image. Runtime passwd/group records are generated by the project. Runtime timestamps are normalized for reproducibility. Dependency source code was not patched; source-built Lanterna is unmodified upstream source compiled for validation. Copyright/alternative-license declarations were retained verbatim. Original upstream APK patches and vendor build recipes are supplied as corresponding material, not misrepresented as project-authored patches.

The v1.0.0 distribution route and owner-led governance are recorded in public-distribution-resolution.md. Final commit/artifact approval and actual source-first delivery remain required; this inventory does not assert they have occurred. Deployment is NO-GO. Retained snapshot inspection/copy/rehearsal and replacement of the existing v5 rollback policy remain separately unauthorized.

Additional [linkage evidence](release-validation.md) distinguishes compiler inputs from retained symbols. The RLPx binary retains bintrie package code, while standalone BitArray symbols are not named; this alone cannot rule out inlining/derived code or establish legal scope. The candidate conservatively supplies GPL3-compatible complete materials and does not claim an LGPL/MIT-only clearance from dead-code elimination.

The [final component-specific decision table and rehearsal authorization gate](release-validation.md) distinguish prepared engineering evidence from remaining operator/legal and source-delivery decisions.

Focused [GPL/EPL boundary evidence and disposition options](release-validation.md) document the unresolved work-scope question and distinguish private use from distribution. The existing distribution-review prerequisite remains unchanged; historical private-rehearsal approvals do not authorize public distribution or production deployment.

Current [public distribution resolution](public-distribution-resolution.md) identifies the qualified decision and minimal engineering fallback. Private amendment renewals and consumed attempts do not clear public licensing; no compatibility or legal approval has been inferred.
