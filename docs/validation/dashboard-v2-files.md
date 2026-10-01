# Dashboard v2 file manifest

Milestone-owned files:

| File | Purpose |
| --- | --- |
| `README.md` | Dashboard v2 overview |
| `docs/adr/dashboard-v2.md` | Presentation architecture and semantics |
| `docs/validation/dashboard-v2.md` | Reproduced validation and limits |
| `docs/validation/dashboard-v2-files.md` | Exact file manifest |
| `src/main/java/io/github/gavinruff007/torchnode/dashboard/DashboardServlet.java` | Local asset routes |
| `src/main/resources/webapp/WEB-INF/views/dashboard.jsp` | Page hierarchy, scope, latest-state labels and responsive styling |
| `src/main/resources/webapp/assets/dashboard-v2.js` | Bounded report rendering and Clear coordination |
| `src/main/resources/webapp/assets/world-countries.svg` | Local country outlines without peer data |
| `src/test/java/io/github/gavinruff007/torchnode/dashboard/DashboardV2Test.java` | Empty/repeated HTTP and asset integration |
| `tools/build-dashboard-map.py` | Deterministic map asset generation from external public-domain input |
| `tools/check-dashboard-v2.mjs` | DOM semantic and stale-response validation |

The map source GeoJSON, representative DB copy, optional MMDBs, binaries, IDE files and build output are not milestone files.
