# Endpoint / NAT analysis material files

All paths are relative to the repository root. No Go protocol source, discovery implementation, scanner lifecycle code or schema migration was changed.

- `src/main/java/io/github/gavinruff007/torchnode/analysis/EndpointAnalysis.java`
- `src/main/java/io/github/gavinruff007/torchnode/dashboard/DashboardServlet.java`
- `src/main/java/io/github/gavinruff007/torchnode/inspection/InspectionResult.java`
- `src/main/java/io/github/gavinruff007/torchnode/inspection/InspectionService.java`
- `src/main/java/io/github/gavinruff007/torchnode/model/NodeEndpoint.java`
- `src/main/java/io/github/gavinruff007/torchnode/storage/SqliteNodeStore.java`
- `src/main/resources/webapp/WEB-INF/views/inspection.jsp`
- `src/test/java/io/github/gavinruff007/torchnode/analysis/EndpointAnalysisTest.java`
- `src/test/java/io/github/gavinruff007/torchnode/dashboard/EnrDashboardTest.java`
- `tools/ValidateEndpointNat.java`
- `docs/adr/endpoint-nat-analysis.md`
- `docs/validation/endpoint-nat-analysis.md`
- `docs/validation/endpoint-nat-public-evidence.json`
- `docs/validation/endpoint-nat-files.md`

Build artifacts, validation databases/backups, compiled harnesses and logs remain ignored under `target/` and are excluded from the checkpoint.
