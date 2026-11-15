# v1.0.0 mandatory release gates

Final executable JAR: `89f9391bad5ce3d8a5b8230a11dc478708049786efa5c8f37bf19123f675e02e`. Final linux/amd64 image: `sha256:491d00e1af431f87226ad7b9ce651b97afe5b6b2b97d40d679ba027fe198f13c`. Nothing is published by this checklist.

| Gate | Recorded state at preparation |
|---|---|
| Default Maven/Docker JSP precompilation and ECJ-free runtime | PASS; exact eight-view registry, fail-closed missing view, no compiler definitions in final layers |
| Java/canonical tests | PASS:249 tests, zero failures/errors/skips; canonical implementation unchanged |
| Go tests/race/vet and source security | Prior PASS preserved; no helper source/dependency changes; final helper hashes checked against frozen binaries |
| Reproducible Java/image | PASS: default package rebuild; pinned-container and host JAR byte identity after UTC timestamp fix; two image manifest/config/archive builds match |
| Exact-image synthetic integration | PASS: authentication/viewer/topology/schema6/persistence/shutdown, disabled collection, network none, no published ports |
| Image vulnerabilities | PASS: zero matches and suppressions; checksum-verified primary Grype DB within seven-day freshness policy |
| Automated accessibility | PASS: exact-image JAR native Chrome/axe desktop/mobile/login/focus/contrast; incomplete checks need human review |
| Corresponding source/notices | Prepared and hash-inventoried; actual public availability required at publication |
| Distribution disposition | Owner-directed documented review completed for corrected sources; actual source-first delivery required before object-code distribution |
| Human accessibility acceptance | Owner-confirmed acceptance; actual detailed scope/environment not supplied; no new human test results invented |
| Final commit / clean-checkout and remote CI | PENDING exact-tree commit approval, then actual clean-checkout CI; no remote PASS inferred from local results |
| Publication recovery prerequisite | NONPROD-V1-RECOVERY-DEFER-1 approved for new-storage/non-production publication; recovery remains NOT VERIFIED |
| GitHub/tag/image publication | Owner authorization conditional on all gates; exact revised commit/artifacts require amendment approval |
| Production deployment | NO-GO; outside this release mission |

**Recovery NOT VERIFIED.** No third attempt is planned. Existing retained evidence and identity policy are preserved. The original v5 rollback requirement remains active and unfulfilled for production. The [non-production publication exception](recovery-rollback-policy.md) is explicitly owner-approved and does not waive production requirements. Tested installation scope is new, empty non-production storage; recipient license rights are unrestricted by this support scope.
