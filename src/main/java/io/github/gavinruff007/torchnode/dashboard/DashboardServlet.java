package io.github.gavinruff007.torchnode.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DashboardServlet(String databasePath, ScannerService scannerService,
                            InspectionService inspectionService) {
        this.databasePath = databasePath;
        this.scannerService = scannerService;
        this.inspectionService = inspectionService;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if ("/export.csv".equals(request.getServletPath())) {
            exportCsv(response);
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
            List<NodeRecord> allNodes = store.findAll();
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
            request.setAttribute("activeNodes", allNodes.stream()
                    .filter(node -> node.getLastSeen() != null && node.getLastSeen().isAfter(activeCutoff))
                    .count());
            request.setAttribute("rpcNodes", allNodes.stream().filter(NodeRecord::isRpcAvailable).count());
            request.setAttribute("beaconNodes", allNodes.stream().filter(NodeRecord::isBeaconAvailable).count());
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

    private void exportCsv(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=torchnode-nodes.csv");
        try (NodeStore store = new SqliteNodeStore(databasePath)) {
            var writer = response.getWriter();
            writer.println("IP,UDP_PORT,TCP_PORT,NODE_ID,TYPE,RPC,BEACON,LATENCY,CLIENT,BLOCK,LAST_SEEN");
            for (NodeRecord node : store.findAll()) {
                writer.println(String.join(",",
                        csv(node.getIp()), csv(node.getUdpPort()), csv(node.getTcpPort()),
                        csv(node.getNodeId()), csv(node.getNodeType()), csv(node.isRpcAvailable()),
                        csv(node.isBeaconAvailable()), csv(node.getLatency()), csv(node.getClientVersion()),
                        csv(node.getBlockNumber()), csv(node.getLastSeen())));
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
