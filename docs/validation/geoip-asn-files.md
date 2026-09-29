# GeoIP / ASN milestone files

Baseline b8e8f39f890bf9e450d4ff0079d9c71755b9b580; one coherent endpoint-enrichment checkpoint.

- `.gitignore`
- `README.md`
- `docs/adr/geoip-asn-enrichment.md`
- `docs/validation/geoip-asn-enrichment.md`
- `docs/validation/geoip-asn-files.md`
- `pom.xml`
- `src/main/java/io/github/gavinruff007/torchnode/daemon/ScanDaemon.java`
- `src/main/java/io/github/gavinruff007/torchnode/dashboard/DashboardServlet.java`
- `src/main/java/io/github/gavinruff007/torchnode/enrichment/NetworkEnrichment.java`
- `src/main/java/io/github/gavinruff007/torchnode/enrichment/NetworkEnrichmentProvider.java`
- `src/main/java/io/github/gavinruff007/torchnode/enrichment/NetworkEnrichmentService.java`
- `src/main/java/io/github/gavinruff007/torchnode/enrichment/OfflineGeoIpProvider.java`
- `src/main/java/io/github/gavinruff007/torchnode/enrichment/PublicAddress.java`
- `src/main/java/io/github/gavinruff007/torchnode/inspection/InspectionResult.java`
- `src/main/java/io/github/gavinruff007/torchnode/inspection/InspectionService.java`
- `src/main/java/io/github/gavinruff007/torchnode/storage/SqliteNodeStore.java`
- `src/main/resources/webapp/WEB-INF/views/inspection.jsp`
- `src/test/java/io/github/gavinruff007/torchnode/dashboard/EnrDashboardTest.java`
- `src/test/java/io/github/gavinruff007/torchnode/enrichment/NetworkEnrichmentLifecycleTest.java`
- `src/test/java/io/github/gavinruff007/torchnode/enrichment/NetworkEnrichmentTest.java`
- `src/test/java/io/github/gavinruff007/torchnode/storage/EnrPersistenceTest.java`
- `tools/ValidateActiveIpv6.java`
- `tools/ValidateGeoIp.java`
- `tools/check-network-enrichment.mjs`

Only source, tests, docs, dependency/configuration declarations and validation tools are included.
No database, MMDB, binary, build artifact, log, credential or unrelated file is included.
The MMDB reader is pinned in pom.xml; no Go dependency/protocol implementation changes occur.
