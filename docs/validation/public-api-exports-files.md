# Public API and richer exports staged file manifest

Stage only these milestone-owned files. This list must exactly match `git diff --cached --name-only` before commit.

```text
README.md
docs/adr/public-api-exports.md
docs/api.md
docs/validation/public-api-exports-files.md
docs/validation/public-api-exports.md
src/main/java/io/github/gavinruff007/torchnode/dashboard/DashboardServer.java
src/main/java/io/github/gavinruff007/torchnode/dashboard/DashboardServlet.java
src/main/java/io/github/gavinruff007/torchnode/dashboard/PublicApiServlet.java
src/main/java/io/github/gavinruff007/torchnode/storage/SqliteNodeStore.java
src/main/resources/webapp/WEB-INF/views/dashboard.jsp
src/main/resources/webapp/WEB-INF/views/inspection.jsp
src/main/resources/webapp/WEB-INF/views/report.jsp
src/test/java/io/github/gavinruff007/torchnode/dashboard/DashboardServletTest.java
src/test/java/io/github/gavinruff007/torchnode/dashboard/EnrDashboardTest.java
src/test/java/io/github/gavinruff007/torchnode/dashboard/PublicApiTest.java
```
