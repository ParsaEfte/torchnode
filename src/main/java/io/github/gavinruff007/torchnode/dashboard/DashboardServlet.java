package io.github.gavinruff007.torchnode.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.core.JsonGenerator;
import io.github.gavinruff007.torchnode.enr.EnrEvidence;
import io.github.gavinruff007.torchnode.analysis.NetworkAnalytics;
import io.github.gavinruff007.torchnode.inspection.InspectionService;
import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.model.NodeType;
import io.github.gavinruff007.torchnode.storage.NodeStore;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

public class DashboardServlet extends HttpServlet {
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final Set<Integer> PAGE_SIZES = Set.of(10, 25, 50, 100);
    private final String databasePath;
    private final ScannerService scannerService;
    private final InspectionService inspectionService;
    private final ObjectMapper objectMapper = analyticsMapper();

    private static ObjectMapper analyticsMapper() {
        var module=new SimpleModule();
        module.addSerializer(Instant.class,new JsonSerializer<Instant>() {
            @Override public void serialize(Instant value,JsonGenerator generator,SerializerProvider provider) throws IOException {
                generator.writeString(value.toString());
            }
        });
        return new ObjectMapper().registerModule(module);
    }

    public DashboardServlet(String databasePath, ScannerService scannerService,
                            InspectionService inspectionService) {
        this.databasePath = databasePath;
        this.scannerService = scannerService;
        this.inspectionService = inspectionService;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        if ("/assets/dashboard-v2.js".equals(request.getServletPath()) ||
                "/assets/world-countries.svg".equals(request.getServletPath())) {
            serveDashboardAsset(request.getServletPath(), response);
            return;
        }
        if ("/export.csv".equals(request.getServletPath())) {
            exportCsv(response);
            return;
        }
        if ("/analytics".equals(request.getServletPath()) || "/analytics.json".equals(request.getServletPath())) {
            showAnalytics(request,response);
            return;
        }
        if ("/reports".equals(request.getServletPath()) || "/reports/network".equals(request.getServletPath())) {
            showAnalytics(request,response);
            return;
        }
        if ("/analytics/snapshot.json".equals(request.getServletPath())) {
            try {
                synchronized(scannerService) {
                    response.setContentType("application/json;charset=UTF-8");
                    objectMapper.writeValue(response.getWriter(),new NetworkAnalytics(databasePath).snapshot());
                }
            } catch(Exception e) { throw new ServletException("Unable to load latest projection snapshot",e); }
            return;
        }
        if ("/node".equals(request.getServletPath())) {
            showInspection(request, response);
            return;
        }
        if ("/inspection/status".equals(request.getServletPath())) {
            inspectionStatus(request, response);
            return;
        }
        if ("/inspection/history".equals(request.getServletPath())) {
            inspectionHistory(request, response);
            return;
        }
        if ("/inspection/changes".equals(request.getServletPath())) {
            changeHistory(request, response);
            return;
        }
        if ("/inspection/evidence".equals(request.getServletPath())) {
            observationHistory(request, response);
            return;
        }
        if (!"/".equals(request.getServletPath())) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String query = normalize(request.getParameter("q"));
        String type = normalize(request.getParameter("type"));
        int requestedPage = positiveInt(request.getParameter("page"), 1);
        int requestedPageSize = positiveInt(request.getParameter("size"), DEFAULT_PAGE_SIZE);
        int pageSize = PAGE_SIZES.contains(requestedPageSize) ? requestedPageSize : DEFAULT_PAGE_SIZE;
        String sort = normalize(request.getParameter("sort"));
        if (!"seen_desc".equals(sort) && !"seen_asc".equals(sort)) sort = "detail";

        try (NodeStore store = new SqliteNodeStore(databasePath)) {
            List<NodeRecord> endpointRows = store.findAll();
            List<NodeRecord> allNodes = io.github.gavinruff007.torchnode.model.CanonicalNodes.views(endpointRows);
            Comparator<NodeRecord> ordering = ordering(sort);
            List<NodeRecord> filteredNodes = allNodes.stream()
                    .filter(node -> type == null || node.getNodeType().name().equalsIgnoreCase(type))
                    .filter(node -> matches(node, query))
                    .sorted(ordering)
                    .toList();
            int totalResults = filteredNodes.size();
            int totalPages = Math.max(1, (totalResults + pageSize - 1) / pageSize);
            int page = Math.min(requestedPage, totalPages);
            int fromIndex = Math.min((page - 1) * pageSize, totalResults);
            int toIndex = Math.min(fromIndex + pageSize, totalResults);
            List<NodeRecord> nodes = filteredNodes.subList(fromIndex, toIndex);

            Instant activeCutoff = Instant.now().minus(15, ChronoUnit.MINUTES);
            request.setAttribute("nodes", nodes);
            request.setAttribute("totalNodes", allNodes.size());
            request.setAttribute("activeNodes", io.github.gavinruff007.torchnode.model.CanonicalNodes.count(endpointRows,
                    node -> node.getLastSeen() != null && node.getLastSeen().isAfter(activeCutoff)));
            request.setAttribute("rpcNodes", io.github.gavinruff007.torchnode.model.CanonicalNodes.count(endpointRows, NodeRecord::isRpcAvailable));
            request.setAttribute("beaconNodes", io.github.gavinruff007.torchnode.model.CanonicalNodes.count(endpointRows, NodeRecord::isBeaconAvailable));
            OptionalDouble averageP2pConnect = allNodes.stream()
                    .filter(node -> node.getP2pConnectMs() != null)
                    .mapToLong(NodeRecord::getP2pConnectMs)
                    .average();
            request.setAttribute("averageLatency", averageP2pConnect.isPresent()
                    ? averageP2pConnect.getAsDouble() : null);
            request.setAttribute("nodeTypes", Arrays.asList(NodeType.values()));
            request.setAttribute("query", query == null ? "" : query);
            request.setAttribute("selectedType", type == null ? "" : type);
            request.setAttribute("sort", sort);
            request.setAttribute("page", page);
            request.setAttribute("pageSize", pageSize);
            request.setAttribute("totalPages", totalPages);
            request.setAttribute("totalResults", totalResults);
            request.setAttribute("resultFrom", totalResults == 0 ? 0 : fromIndex + 1);
            request.setAttribute("resultTo", toIndex);
            request.setAttribute("scannerRunning", scannerService.isRunning());
            request.setAttribute("csrfToken", csrfToken(request));
            request.setAttribute("message", normalize(request.getParameter("message")));
            request.setAttribute("error", normalize(request.getParameter("error")));
            request.getRequestDispatcher("/WEB-INF/views/dashboard.jsp").forward(request, response);
        } catch (Exception e) {
            throw new ServletException("Unable to load dashboard data", e);
        }
    }

    private void serveDashboardAsset(String path, HttpServletResponse response) throws IOException {
        try (InputStream asset = getClass().getResourceAsStream("/webapp" + path)) {
            if (asset == null) { response.sendError(HttpServletResponse.SC_NOT_FOUND); return; }
            response.setContentType(path.endsWith(".svg") ? "image/svg+xml;charset=UTF-8" :
                    "text/javascript;charset=UTF-8");
            response.setHeader("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'");
            asset.transferTo(response.getOutputStream());
        }
    }

    private void showAnalytics(HttpServletRequest request,HttpServletResponse response)
            throws ServletException,IOException {
        try {
            String mode=request.getParameter("mode");
            if(mode!=null && !"all".equals(mode))throw new IllegalArgumentException("Invalid mode");
            if("all".equals(mode) && (request.getParameter("start")!=null || request.getParameter("end")!=null))
                throw new IllegalArgumentException("All available mode cannot have bounds");
            Instant end=request.getParameter("end")==null?Instant.now():Instant.parse(request.getParameter("end"));
            Instant start=request.getParameter("start")==null?end.minus(1,ChronoUnit.DAYS):Instant.parse(request.getParameter("start"));
            var scope="all".equals(mode)?NetworkAnalytics.Scope.allAvailable():
                    new NetworkAnalytics.Scope(start,end);
            synchronized(scannerService) {
                var report=new NetworkAnalytics(databasePath).measure(scope);
                if("/analytics.json".equals(request.getServletPath())) {
                    response.setContentType("application/json;charset=UTF-8");
                    objectMapper.writeValue(response.getWriter(),report);
                } else {
                    request.setAttribute("analytics",report);
                    if (request.getServletPath().startsWith("/reports"))
                        request.getRequestDispatcher("/WEB-INF/views/report.jsp").forward(request,response);
                    else {
                        request.setAttribute("analyticsSnapshot",new NetworkAnalytics(databasePath).snapshot());
                        request.getRequestDispatcher("/WEB-INF/views/analytics.jsp").forward(request,response);
                    }
                }
            }
        } catch(IllegalArgumentException | java.time.DateTimeException e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,"Invalid analytics window (maximum 31 days)");
        } catch(SQLException e) {
            if(e.getMessage()!=null && (e.getMessage().contains("evidence-row safety limit") ||
                    e.getMessage().contains("25,000 inspections")))
                response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,e.getMessage());
            else throw new ServletException("Unable to measure network observations",e);
        } catch(Exception e) { throw new ServletException("Unable to measure network observations",e); }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!csrfToken(request).equals(request.getParameter("csrf"))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Invalid form token");
            return;
        }

        try {
            switch (request.getServletPath()) {
                case "/scanner/start" -> {
                    scannerService.start();
                    redirect(response, "message", "Scanner started");
                }
                case "/scanner/stop" -> {
                    scannerService.stop();
                    redirect(response, "message", "Scanner stopped");
                }
                case "/data/clear" -> {
                    synchronized (scannerService) {
                        scannerService.stop();
                        try (SqliteNodeStore store = new SqliteNodeStore(databasePath)) {
                            inspectionService.clearCollectedData(store);
                        }
                    }
                    redirect(response, "message", "All collected data cleared. Scanner stopped.");
                }
                default -> response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
        } catch (Exception e) {
            redirect(response, "error", e.getMessage() == null ? "Action failed" : e.getMessage());
        }
    }

    private void showInspection(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        String key = normalize(request.getParameter("key"));
        if (key == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing node key");
            return;
        }
        synchronized (scannerService) {
            try (NodeStore store = new SqliteNodeStore(databasePath)) {
                NodeRecord node = store.findByKey(key).orElse(null);
                if (node == null) {
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "Node not found");
                    return;
                }
                request.setAttribute("node", node);
                request.setAttribute("inspectionId", inspectionService.inspect(node));
                request.getRequestDispatcher("/WEB-INF/views/inspection.jsp").forward(request, response);
            } catch (java.sql.SQLException e) {
                throw new ServletException("Unable to load node", e);
            }
        }
    }

    private void inspectionStatus(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        var snapshot = inspectionService.snapshot(request.getParameter("id"));
        if (snapshot.isEmpty()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "Inspection not found");
            return;
        }
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        objectMapper.writeValue(response.getWriter(), snapshot.get());
    }

    private void inspectionHistory(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String key=normalize(request.getParameter("key"));
        if(key==null){response.sendError(HttpServletResponse.SC_BAD_REQUEST);return;}
        int limit=Math.min(50,positiveInt(request.getParameter("limit"),20));
        try(var store=new SqliteNodeStore(databasePath)) {
            var node=store.findByKey(key);
            if(node.isEmpty()){response.sendError(HttpServletResponse.SC_NOT_FOUND);return;}
            var history=store.inspectionHistory(node.get().identity(),limit,normalize(request.getParameter("before")));
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getWriter(),history);
        } catch(Exception e){throw new IOException("Unable to load inspection history",e);}
    }

    private void changeHistory(HttpServletRequest request,HttpServletResponse response) throws IOException {
        String key=normalize(request.getParameter("key"));
        if(key==null){response.sendError(HttpServletResponse.SC_BAD_REQUEST);return;}
        int limit=Math.min(50,positiveInt(request.getParameter("limit"),20));
        try(var store=new SqliteNodeStore(databasePath)){
            var node=store.findByKey(key);
            if(node.isEmpty()){response.sendError(HttpServletResponse.SC_NOT_FOUND);return;}
            var changes=store.changeHistory(node.get().identity(),limit,normalize(request.getParameter("before")));
            response.setContentType("application/json;charset=UTF-8");
            objectMapper.writeValue(response.getWriter(),changes);
        }catch(Exception e){throw new IOException("Unable to load change history",e);}
    }

    private void observationHistory(HttpServletRequest request,HttpServletResponse response) throws IOException {
        String key=normalize(request.getParameter("key"));
        String domain=normalize(request.getParameter("domain"));
        if(key==null || !("discovery".equals(domain) || "enr".equals(domain) || "enrichment".equals(domain))) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);return;
        }
        long before=0;
        String enrichmentBefore=normalize(request.getParameter("before"));
        try {
            if(!"enrichment".equals(domain)) {
                before=request.getParameter("before")==null?0:Long.parseLong(request.getParameter("before"));
                if(before<0)throw new NumberFormatException();
            } else if(enrichmentBefore!=null && !enrichmentBefore.matches("[0-9a-f]{64}"))
                throw new NumberFormatException();
        } catch(NumberFormatException e) {response.sendError(HttpServletResponse.SC_BAD_REQUEST);return;}
        int limit=Math.min(50,positiveInt(request.getParameter("limit"),20));
        synchronized(scannerService) {
            try(var store=new SqliteNodeStore(databasePath)) {
                var node=store.findByKey(key);
                if(node.isEmpty()){response.sendError(HttpServletResponse.SC_NOT_FOUND);return;}
                Object history=switch(domain) {
                    case "discovery" -> store.discoveryHistory(node.get().identity(),limit,before);
                    case "enr" -> store.enrHistory(node.get().identity(),limit,before);
                    default -> store.enrichmentHistory(node.get().identity(),limit,enrichmentBefore);
                };
                response.setContentType("application/json;charset=UTF-8");
                objectMapper.writeValue(response.getWriter(),history);
            } catch(Exception e) {throw new IOException("Unable to load observation history",e);}
        }
    }

    private void exportCsv(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=torchnode-nodes.csv");
        try (NodeStore store = new SqliteNodeStore(databasePath)) {
            var writer = response.getWriter();
            writer.println("IP,UDP_PORT,TCP_PORT,NODE_ID,TYPE,RPC,BEACON,LATENCY,CLIENT,BLOCK,LAST_SEEN,ENR,ENR_SEQUENCE,ENR_SIGNATURE,ENR_IDENTITY_COMPARISON,DISCOVERY_SOURCE,DISCV5_PROVENANCE,ADDRESS_FAMILY,ENDPOINT_OBSERVATIONS_JSON,ENDPOINT_ANALYSIS,NAT_EVIDENCE,NETWORK_ENRICHMENT_JSON");
            for (NodeRecord node : store.findAll()) {
                EnrEvidence enr = store instanceof SqliteNodeStore sqlite
                        ? EnrEvidence.latestValidated(sqlite.findEnrEvidence(node.identity())).orElse(null) : null;
                var analysis = io.github.gavinruff007.torchnode.analysis.EndpointAnalysis.fromStore((SqliteNodeStore)store,node.identity());
                writer.println(String.join(",",
                        csv(node.getIp()), csv(node.getUdpPort()), csv(node.getTcpPort()),
                        csv(node.getNodeId()), csv(node.getNodeType()), csv(node.isRpcAvailable()),
                        csv(node.isBeaconAvailable()), csv(node.getLatency()), csv(node.getClientVersion()),
                        csv(node.getBlockNumber()), csv(node.getLastSeen()),
                        csv(enr == null ? null : enr.record().text()), csv(enr == null ? null : enr.record().sequence()),
                        csv(enr == null ? null : enr.signature()), csv(enr == null ? null : enr.identityComparison()),
                        csv(node.getDiscoverySource()), csv(store.findObservations(node.identity()).stream()
                            .filter(o -> o.source().equals("discv5")).map(o -> o.observedAt() + " " + o.provenance()).collect(java.util.stream.Collectors.joining(" | "))),
                        csv(io.github.gavinruff007.torchnode.model.EndpointAddress.family(node.getIp())),
                        csv(objectMapper.writeValueAsString(store.findObservations(node.identity()).stream().map(io.github.gavinruff007.torchnode.model.DiscoveryObservation::toMap).toList())),
                        csv(objectMapper.writeValueAsString(analysis.toMap())),csv(analysis.natEvidence()),
                        csv(objectMapper.writeValueAsString(((SqliteNodeStore)store).networkEnrichmentView(node.identity())))));
            }
        } catch (Exception e) {
            throw new IOException("Unable to export nodes", e);
        }
    }

    private String csv(Object value) {
        if (value == null) return "";
        return "\"" + value.toString().replace("\"", "\"\"") + "\"";
    }

    private String csrfToken(HttpServletRequest request) {
        Object existing = request.getSession().getAttribute("csrfToken");
        if (existing != null) return existing.toString();
        String token = UUID.randomUUID().toString();
        request.getSession().setAttribute("csrfToken", token);
        return token;
    }

    private void redirect(HttpServletResponse response, String type, String message) throws IOException {
        response.sendRedirect("/?" + type + "=" + URLEncoder.encode(message, StandardCharsets.UTF_8));
    }

    private boolean matches(NodeRecord node, String query) {
        if (query == null) return true;
        String needle = query.toLowerCase(Locale.ROOT);
        return contains(node.getIp(), needle) || contains(node.getCountry(), needle)
                || contains(node.getClientVersion(), needle) || contains(node.getNodeId(), needle);
    }

    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int positiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    public static int detailScore(NodeRecord node) {
        int score = 0;
        if (node.getNodeType() != null && node.getNodeType() != NodeType.UNKNOWN) score += 2;
        if (node.getCountry() != null && !node.getCountry().isBlank()) score += 1;
        if (node.getLatency() != null) score += 2;
        if (node.getClientVersion() != null && !node.getClientVersion().isBlank()) score += 3;
        if (node.isRpcAvailable()) score += 3;
        if (node.isBeaconAvailable()) score += 3;
        if (node.getSyncing() != null) score += 1;
        if (node.getBlockNumber() != null) score += 2;
        if (node.getPendingTransactions() != null) score += 1;
        return score;
    }

    static Comparator<NodeRecord> ordering(String sort) {
        Comparator<NodeRecord> newest = Comparator.comparing(NodeRecord::getLastSeen,
                Comparator.nullsLast(Comparator.reverseOrder()));
        Comparator<NodeRecord> oldest = Comparator.comparing(NodeRecord::getLastSeen,
                Comparator.nullsLast(Comparator.naturalOrder()));
        return switch (sort) {
            case "seen_desc" -> newest.thenComparing(NodeRecord::getKey);
            case "seen_asc" -> oldest.thenComparing(NodeRecord::getKey);
            default -> Comparator.comparingInt(DashboardServlet::detailScore).reversed()
                    .thenComparing(newest).thenComparing(NodeRecord::getKey);
        };
    }
}
