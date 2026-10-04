package io.github.gavinruff007.torchnode.dashboard;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.model.NodeType;
import io.github.gavinruff007.torchnode.inspection.InspectionService;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DashboardServletTest {
    @TempDir Path tempDir;

    @Test
    void analyticsJsonSerializesUtcScopeAndMatchesDirectQuery() throws Exception {
        String path=tempDir.resolve("analytics-http.db").toString();
        try(var store=new SqliteNodeStore(path)) {
            var node=new NodeRecord("192.0.2.1",30303,30303,"ab".repeat(64));
            node.setLastSeen(Instant.parse("2026-01-01T00:00:00Z"));store.save(node);
        }
        var service=new InspectionService(path);
        try {
            var servlet=new DashboardServlet(path,new ScannerService(path),service);
            var body=new java.io.StringWriter();var writer=new java.io.PrintWriter(body);
            HttpServletRequest request=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{HttpServletRequest.class},(proxy,method,args)->switch(method.getName()) {
                        case "getServletPath" -> "/analytics.json";
                        case "getParameter" -> "start".equals(args[0])?"2026-01-01T00:00:00Z":
                                "end".equals(args[0])?"2026-01-02T00:00:00Z":null;
                        default -> null;
                    });
            HttpServletResponse response=(HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{HttpServletResponse.class},(proxy,method,args)->
                            "getWriter".equals(method.getName())?writer:null);
            servlet.doGet(request,response);writer.flush();
            var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.toString());
            assertEquals("2026-01-01T00:00:00Z",json.path("scope").path("startInclusive").asText());
            assertEquals("WINDOW",json.path("scope").path("mode").asText());
            long direct=new io.github.gavinruff007.torchnode.analysis.NetworkAnalytics(path).measure(
                    new io.github.gavinruff007.torchnode.analysis.NetworkAnalytics.Scope(
                            Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2026-01-02T00:00:00Z")))
                    .metrics().stream().filter(m->m.id().equals("observed-identities")).findFirst().orElseThrow().denominator();
            assertEquals(direct,json.path("metrics").get(0).path("denominator").asLong());
            var attributes=new java.util.HashMap<String,Object>();
            var rendered=new AtomicReference<String>();
            RequestDispatcher dispatcher=(RequestDispatcher)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{RequestDispatcher.class},(proxy,method,args)->{
                        if("forward".equals(method.getName())) {
                            var report=(io.github.gavinruff007.torchnode.analysis.NetworkAnalytics.Report)attributes.get("analytics");
                            rendered.set(report.metrics().get(0).denominator()+":"+report.scope().startInclusive());
                        }
                        return null;
                    });
            HttpServletRequest html=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{HttpServletRequest.class},(proxy,method,args)->switch(method.getName()) {
                        case "getServletPath" -> "/analytics";
                        case "getParameter" -> "start".equals(args[0])?"2026-01-01T00:00:00Z":
                                "end".equals(args[0])?"2026-01-02T00:00:00Z":null;
                        case "setAttribute" -> { attributes.put((String)args[0],args[1]);yield null; }
                        case "getRequestDispatcher" -> dispatcher;
                        default -> null;
                    });
            servlet.doGet(html,response);
            assertEquals(direct+":2026-01-01T00:00:00Z",rendered.get());
        } finally { service.close(); }
    }

    @Test
    void clearWaitsForInFlightAnalyticsResponseAndNextQueryIsEmpty() throws Exception {
        String path=tempDir.resolve("analytics-clear.db").toString();
        try(var store=new SqliteNodeStore(path)) {
            var node=new NodeRecord("192.0.2.1",30303,30303,"ab".repeat(64));
            node.setLastSeen(Instant.parse("2026-01-01T00:00:00Z"));store.save(node);
        }
        var scanner=new ScannerService(path);var inspection=new InspectionService(path);
        try {
            var servlet=new DashboardServlet(path,scanner,inspection);
            var writing=new java.util.concurrent.CountDownLatch(1);
            var release=new java.util.concurrent.CountDownLatch(1);
            var clearEntered=new java.util.concurrent.CountDownLatch(1);
            var completed=new java.util.concurrent.CountDownLatch(1);
            var failure=new AtomicReference<Throwable>();
            var blockingWriter=new java.io.PrintWriter(new java.io.Writer() {
                @Override public void write(char[] chars,int offset,int length) throws java.io.IOException {
                    writing.countDown();
                    try { release.await(); } catch(InterruptedException e) { Thread.currentThread().interrupt();throw new java.io.IOException(e); }
                }
                @Override public void flush() { }
                @Override public void close() { }
            });
            HttpServletRequest analytics=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{HttpServletRequest.class},(proxy,method,args)->switch(method.getName()) {
                        case "getServletPath" -> "/analytics.json";
                        case "getParameter" -> "start".equals(args[0])?"2026-01-01T00:00:00Z":
                                "end".equals(args[0])?"2026-01-02T00:00:00Z":null;
                        default -> null;
                    });
            HttpServletResponse analyticsResponse=(HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{HttpServletResponse.class},(proxy,method,args)->"getWriter".equals(method.getName())?blockingWriter:null);
            var queryThread=new Thread(()->{try {servlet.doGet(analytics,analyticsResponse);}catch(Throwable t){failure.set(t);}});
            queryThread.start();
            assertTrue(writing.await(10,java.util.concurrent.TimeUnit.SECONDS));
            HttpSession session=(HttpSession)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{HttpSession.class},(proxy,method,args)->"getAttribute".equals(method.getName())?"token":null);
            HttpServletRequest clear=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{HttpServletRequest.class},(proxy,method,args)->switch(method.getName()) {
                        case "getServletPath" -> "/data/clear";
                        case "getParameter" -> "csrf".equals(args[0])?"token":null;
                        case "getSession" -> session;
                        default -> null;
                    });
            HttpServletResponse clearResponse=(HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{HttpServletResponse.class},(proxy,method,args)->null);
            var clearThread=new Thread(()->{clearEntered.countDown();try {servlet.doPost(clear,clearResponse);}catch(Throwable t){failure.set(t);}finally{completed.countDown();}});
            clearThread.start();
            assertTrue(clearEntered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            long blockedDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while(clearThread.getState()!=Thread.State.BLOCKED && System.nanoTime()<blockedDeadline)
                Thread.onSpinWait();
            assertEquals(Thread.State.BLOCKED,clearThread.getState());
            assertEquals(queryThread.getId(),java.lang.management.ManagementFactory.getThreadMXBean()
                    .getThreadInfo(clearThread.getId()).getLockOwnerId());
            assertEquals(1,completed.getCount());
            release.countDown();queryThread.join(10_000);clearThread.join(10_000);
            assertTrue(!queryThread.isAlive() && !clearThread.isAlive());
            assertEquals(null,failure.get());
            assertEquals(0,new io.github.gavinruff007.torchnode.analysis.NetworkAnalytics(path).measure(
                    new io.github.gavinruff007.torchnode.analysis.NetworkAnalytics.Scope(
                            Instant.parse("2026-01-01T00:00:00Z"),Instant.parse("2026-01-02T00:00:00Z")))
                    .metrics().get(0).denominator());
        } finally { inspection.close();scanner.stop(); }
    }

    @Test
    void clearRequiresPostAndResetsCachedInspectionResults() throws Exception {
        String path = tempDir.resolve("dashboard.db").toString();
        NodeRecord node = new NodeRecord("192.0.2.1", 30303, 0, "id");
        try (SqliteNodeStore store = new SqliteNodeStore(path)) { store.save(node); }
        InspectionService inspections = new InspectionService(path);
        try {
            String inspectionId = inspections.inspect(node);
            DashboardServlet servlet = new DashboardServlet(path, new ScannerService(path), inspections);
            AtomicInteger error = new AtomicInteger();
            AtomicReference<String> redirect = new AtomicReference<>();
            HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class[]{HttpServletResponse.class}, (proxy, method, args) -> {
                        if ("sendError".equals(method.getName())) error.set((int) args[0]);
                        if ("sendRedirect".equals(method.getName())) redirect.set((String) args[0]);
                        return null;
                    });
            HttpSession session = (HttpSession) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class[]{HttpSession.class}, (proxy, method, args) ->
                            "getAttribute".equals(method.getName()) ? "token" : null);
            HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class[]{HttpServletRequest.class}, (proxy, method, args) ->
                            switch (method.getName()) {
                                case "getServletPath" -> "/data/clear";
                                case "getParameter" -> "csrf".equals(args[0]) ? "token" : null;
                                case "getSession" -> session;
                                default -> null;
                            });
            servlet.doGet(request, response);
            assertEquals(404, error.get());
            try (SqliteNodeStore store = new SqliteNodeStore(path)) { assertEquals(1, store.count()); }
            servlet.doPost(request, response);
            assertTrue(redirect.get().contains("message="));
            try (SqliteNodeStore store = new SqliteNodeStore(path)) { assertEquals(0, store.count()); }
            servlet.doPost(request, response);
            assertTrue(redirect.get().contains("message="));
            assertTrue(inspections.snapshot(inspectionId).isEmpty());
        } finally {
            inspections.close();
        }
    }
    @Test
    void inspectedNodeHasHigherDetailScore() {
        NodeRecord basic = new NodeRecord("127.0.0.1", 30303, 30303, "basic");
        NodeRecord detailed = new NodeRecord("127.0.0.2", 30303, 30303, "detailed");
        detailed.setNodeType(NodeType.EXECUTION);
        detailed.setRpcAvailable(true);
        detailed.setLatency(18L);
        detailed.setClientVersion("Geth/v1.14");
        detailed.setBlockNumber(20_000_000L);

        assertTrue(DashboardServlet.detailScore(detailed) > DashboardServlet.detailScore(basic));
    }

    @Test
    void lastSeenSortIgnoresDetailRankingAndPrecedesPagination() {
        NodeRecord oldDetailed = node("192.0.2.1", "2026-01-01T00:00:00Z");
        oldDetailed.setClientVersion("Geth/v1.0");
        oldDetailed.setRpcAvailable(true);
        NodeRecord middle = node("192.0.2.2", "2026-02-01T00:00:00Z");
        NodeRecord newest = node("192.0.2.3", "2026-03-01T00:00:00Z");
        List<NodeRecord> nodes = List.of(oldDetailed, middle, newest);

        assertEquals(List.of(newest, middle, oldDetailed),
                nodes.stream().sorted(DashboardServlet.ordering("seen_desc")).toList());
        assertEquals(List.of(oldDetailed, middle, newest),
                nodes.stream().sorted(DashboardServlet.ordering("seen_asc")).toList());
        assertEquals(oldDetailed,
                nodes.stream().sorted(DashboardServlet.ordering("detail")).findFirst().orElseThrow());
        assertEquals(List.of(newest, middle), nodes.stream()
                .sorted(DashboardServlet.ordering("seen_desc")).limit(2).toList());
    }

    private static NodeRecord node(String ip, String seen) {
        NodeRecord node = new NodeRecord(ip, 30303, 30303, null);
        node.setLastSeen(Instant.parse(seen));
        return node;
    }
    @Test
    void successfulDeepApisPersistCountAndExportDespiteUnavailableP2p() throws Exception {
        String path = tempDir.resolve("api-summary.db").toString();
        var rpc = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 8545), 0);
        var beacon = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 5052), 0);
        rpc.createContext("/", exchange -> {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String method = mapper.readTree(exchange.getRequestBody()).path("method").asText();
            Object result = switch (method) {
                case "web3_clientVersion" -> "Geth/v1.17/test";
                case "eth_syncing" -> false;
                case "net_version" -> "1";
                default -> "0x1";
            };
            byte[] response = mapper.writeValueAsBytes(java.util.Map.of("jsonrpc", "2.0", "id", 1, "result", result));
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        beacon.createContext("/", exchange -> {
            byte[] response = "{\"data\":{\"version\":\"Lighthouse/v1.0\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        rpc.start(); beacon.start();
        try (InspectionService inspections = new InspectionService(path)) {
            NodeRecord node = new NodeRecord("127.0.0.1", 30301, 0, "ab".repeat(64));
            try (SqliteNodeStore store = new SqliteNodeStore(path)) { store.save(node); }
            String id = inspections.inspect(node);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (!Boolean.TRUE.equals(inspections.snapshot(id).orElseThrow().get("complete")) && System.nanoTime() < deadline)
                Thread.sleep(20);
            var snapshot = inspections.snapshot(id).orElseThrow();
            assertEquals(true, snapshot.get("complete"));
            assertTrue(snapshot.get("rpc") != null); assertTrue(snapshot.get("beacon") != null);
            try (SqliteNodeStore store = new SqliteNodeStore(path)) {
                NodeRecord stored = store.findByKey(node.getKey()).orElseThrow();
                assertTrue(stored.isRpcAvailable()); assertTrue(stored.isBeaconAvailable());
                assertEquals(NodeType.FULL_NODE, stored.getNodeType());
                var history=store.inspectionHistory(node.identity(),10,null);
                assertEquals(1,history.size());
                assertTrue(history.get(0).evidence().get("rpc") != null);
                assertTrue(history.get(0).evidence().get("beacon") != null);
            }
            var attributes = new java.util.HashMap<String, Object>();
            var csv = new java.io.StringWriter();
            var writer = new java.io.PrintWriter(csv);
            var servlet = new DashboardServlet(path, new ScannerService(path), inspections);
            HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class[]{HttpServletResponse.class}, (proxy, method, args) ->
                            "getWriter".equals(method.getName()) ? writer : null);
            HttpSession session = (HttpSession) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class[]{HttpSession.class}, (proxy, method, args) ->
                            "getAttribute".equals(method.getName()) ? "token" : null);
            jakarta.servlet.RequestDispatcher dispatcher = (jakarta.servlet.RequestDispatcher) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class[]{jakarta.servlet.RequestDispatcher.class}, (proxy, method, args) -> null);
            for (String route : List.of("/")) {
                HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class[]{HttpServletRequest.class}, (proxy, method, args) -> {
                            return switch (method.getName()) {
                                case "getServletPath" -> route;
                                case "getSession" -> session;
                                case "setAttribute" -> { attributes.put((String) args[0], args[1]); yield null; }
                                case "getRequestDispatcher" -> dispatcher;
                                default -> null;
                            };
                        });
                servlet.doGet(request, response);
            }
            assertEquals(1L, attributes.get("rpcNodes")); assertEquals(1L, attributes.get("beaconNodes"));
            writer.flush();
            assertEquals(1L, attributes.get("rpcNodes"));
        } finally { rpc.stop(0); beacon.stop(0); }
    }

    @Test void historyHttpResponseAgreesWithBoundedRepositoryQuery() throws Exception {
        String path=tempDir.resolve("history-ui.db").toString();
        var node=new NodeRecord("192.0.2.5",30303,30303,"ab".repeat(64));
        try(var store=new SqliteNodeStore(path)) {
            store.save(node);
            for(int i=0;i<3;i++)store.saveInspectionRun(node,"run-"+i,"2026-01-0"+(i+1)+"T00:00:00Z",
                    "2026-01-0"+(i+1)+"T00:00:01Z",java.util.Map.of("diagnostics",java.util.List.of(
                            java.util.Map.of("name","P2P TCP","state",i==2?"FAILED":"PASS"))),
                    null,null,null,java.util.List.of(),java.util.List.of());
        }
        try(var inspections=new InspectionService(path)) {
            var servlet=new DashboardServlet(path,new ScannerService(path),inspections);
            var out=new java.io.StringWriter();var writer=new java.io.PrintWriter(out);
            HttpServletResponse response=(HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{HttpServletResponse.class},(proxy,method,args)->"getWriter".equals(method.getName())?writer:null);
            HttpServletRequest request=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{HttpServletRequest.class},(proxy,method,args)->switch(method.getName()){
                        case "getServletPath" -> "/inspection/history";
                        case "getParameter" -> switch((String)args[0]){case "key" -> node.getKey();case "limit" -> "2";default -> null;};
                        default -> null;
                    });
            servlet.doGet(request,response);writer.flush();
            var rows=new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toString());
            assertEquals(2,rows.size());assertEquals("run-2",rows.get(0).path("id").asText());
            assertEquals("FAILED",rows.get(0).path("evidence").path("diagnostics").get(0).path("state").asText());
            try(var store=new SqliteNodeStore(path)) {assertEquals(store.inspectionHistory(node.identity(),2,null).get(1).id(),rows.get(1).path("id").asText());}
        }
    }

    @Test void changeHttpResponseAgreesWithDerivedRepositoryQuery() throws Exception {
        String path=tempDir.resolve("change-ui.db").toString();
        var node=new NodeRecord("192.0.2.5",30303,30303,"ab".repeat(64));
        try(var store=new SqliteNodeStore(path)){
            store.save(node);
            for(int i=0;i<2;i++){
                String at="2026-01-0"+(i+1)+"T00:00:00Z";
                var attempt=java.util.Map.<String,Object>of("endpoint","http://192.0.2.5:8545",
                        "attemptedAt",at,"tcpOpen",true,"rpcReachable",i==0);
                store.saveInspectionRun(node,"rpc-"+i,at,at,java.util.Map.of("rpcAttempts",java.util.List.of(attempt)),
                        null,null,null,java.util.List.of(),java.util.List.of());
            }
        }
        try(var inspections=new InspectionService(path)){
            var servlet=new DashboardServlet(path,new ScannerService(path),inspections);
            var out=new java.io.StringWriter();var writer=new java.io.PrintWriter(out);
            HttpServletResponse response=(HttpServletResponse)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{HttpServletResponse.class},(proxy,method,args)->"getWriter".equals(method.getName())?writer:null);
            HttpServletRequest request=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class[]{HttpServletRequest.class},(proxy,method,args)->switch(method.getName()){
                        case "getServletPath" -> "/inspection/changes";
                        case "getParameter" -> switch((String)args[0]){case "key" -> node.getKey();case "limit" -> "1";default -> null;};
                        default -> null;
                    });
            servlet.doGet(request,response);writer.flush();
            var rows=new com.fasterxml.jackson.databind.ObjectMapper().readTree(out.toString());
            assertEquals(1,rows.size());
            try(var store=new SqliteNodeStore(path)){
                var event=store.changeHistory(node.identity(),1,null).get(0);
                assertEquals(event.id(),rows.get(0).path("id").asText());
                assertTrue(store.changeHistory(node.identity(),10,null).stream()
                        .anyMatch(change->change.changeType().equals("RPC_PROBE_OUTCOME_CHANGED")));
            }
        }
    }
}
