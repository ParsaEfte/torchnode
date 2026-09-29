import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gavinruff007.torchnode.daemon.ScanDaemon;
import io.github.gavinruff007.torchnode.discovery.*;
import io.github.gavinruff007.torchnode.enr.*;
import io.github.gavinruff007.torchnode.inspection.*;
import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.storage.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Explicit opt-in bounded public validation. Never opens the live database. */
public class ValidateActiveIpv6 {
    static final ObjectMapper JSON = new ObjectMapper();
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Provide a validation directory containing historical-copy.db");
        Path out = Path.of(args[0]).toAbsolutePath();
        if (!Files.isRegularFile(out.resolve("historical-copy.db"))) throw new IllegalArgumentException("Historical copy missing");
        Path database = out.resolve("public-scan.db");
        if (Files.exists(database)) throw new IllegalArgumentException("Refusing to overwrite an existing validation database");
        Map<String,Object> report = new LinkedHashMap<>(); report.put("startedAt", java.time.Instant.now().toString());
        List<String> extraBootstraps = new ArrayList<>(); List<NodeRecord> ipv6Targets = new ArrayList<>();
        try (var historical = new SqliteNodeStore(out.resolve("historical-copy.db").toString())) {
            var rows = historical.findAll();
            report.put("historicalRowsRead", rows.size()); report.put("historicalCanonicalIdentities", CanonicalNodes.views(rows).size());
            for (NodeRecord row : CanonicalNodes.views(rows)) {
                for (EnrEvidence evidence : historical.findEnrEvidence(row.identity())) {
                    if (!evidence.usable() || evidence.record().fields().ip6() == null) continue;
                    if (!extraBootstraps.contains(evidence.record().text())) extraBootstraps.add(evidence.record().text());
                    var endpoints = evidence.record().endpoints().stream().filter(e -> e.addressFamily() == NodeEndpoint.AddressFamily.IPV6).toList();
                    if (endpoints.stream().anyMatch(e -> e.purpose() == NodeEndpoint.Purpose.DISCOVERY) && ipv6Targets.size() < 2)
                        ipv6Targets.add(new NodeRecord(new DiscoveryObservation(row.identity(), "discv5", endpoints, evidence.observedAt(), evidence.provenance())));
                }
            }
        }
        report.put("historicalPublicIpv6Enrs", extraBootstraps);
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("2606:4700:4700::1111", 443), 2000);
            report.put("observerIpv6InternetCheck", "TCP connected to [2606:4700:4700::1111]:443");
        } catch (Exception e) { report.put("observerIpv6InternetCheck", e.getClass().getSimpleName() + ": " + e.getMessage()); }
        var boots = new ArrayList<>(Files.readAllLines(Path.of("src/main/resources/discv5-bootnodes.txt")).stream()
            .map(String::trim).filter(s -> !s.isBlank() && !s.startsWith("#")).toList());
        extraBootstraps.stream().limit(2).forEach(boots::add);
        List<Map<String,Object>> runs = new ArrayList<>();
        for (int seconds : new int[]{45, 12}) {
            var identity = new LocalNodeIdentity(); var socket = new DatagramSocket(0); int port = socket.getLocalPort();
            var v4 = new Discv4DiscoveryProvider(identity, socket, new String[]{"18.138.108.67:30303", "3.209.45.79:30303"});
            var v5 = new Discv5DiscoveryProvider(identity, Path.of("target/torchnode-discovery-helper"), boots);
            var daemon = new ScanDaemon(database.toString());
            Map<String,Object> run = new LinkedHashMap<>(); run.put("durationSeconds", seconds);
            daemon.start(new CompositeDiscoveryProvider(List.of(v4, v5)));
            Set<java.util.concurrent.ThreadPoolExecutor> executors = new HashSet<>();
            int peakWorkers = 0, peakHttpCalls = 0;
            Set<String> endpointDiagnostics = new LinkedHashSet<>();
            try {
                long until = System.nanoTime() + seconds * 1_000_000_000L;
                while (System.nanoTime() < until) {
                    for (var entry : Thread.getAllStackTraces().entrySet()) {
                        if (Arrays.stream(entry.getValue()).noneMatch(f -> f.getClassName().endsWith(".NodeInspector"))) continue;
                        Object holder = field(entry.getKey(), "holder");
                        Object task = field(holder, "task");
                        if (task != null && task.getClass().getName().equals("java.util.concurrent.ThreadPoolExecutor$Worker"))
                            executors.add((java.util.concurrent.ThreadPoolExecutor)field(task, "this$0"));
                    }
                    peakWorkers = Math.max(peakWorkers, executors.stream().mapToInt(java.util.concurrent.ThreadPoolExecutor::getPoolSize).sum());
                    Object ni = field(daemon, "nodeInspector");
                    peakHttpCalls = Math.max(peakHttpCalls, active(field(ni,"rpcProber")) + active(field(ni,"beaconProber")));
                    v5.diagnostics().stream().filter(d -> d.startsWith("ENDPOINT_") && d.contains("[")) .forEach(endpointDiagnostics::add);
                    Thread.sleep(100);
                }
                run.put("runningBeforeStop", daemon.isRunning());
            }
            finally {
                long start = System.nanoTime(); daemon.stop(); daemon.awaitStopped();
                run.put("stopMs", (System.nanoTime() - start) / 1_000_000);
            }
            run.put("enrichmentAfterStop", daemon.enrichmentMetrics());
            run.put("ipv6EndpointDiagnostics", endpointDiagnostics);
            run.put("peakScannerWorkers", peakWorkers); run.put("peakHttpCalls", peakHttpCalls);
            run.put("capturedExecutors", executors.size());
            run.put("executorsTerminated", executors.stream().allMatch(java.util.concurrent.ThreadPoolExecutor::isTerminated));
            run.put("queuedTasksAfterStop", executors.stream().mapToInt(e -> e.getQueue().size()).sum());
            run.put("activeTasksAfterStop", executors.stream().mapToInt(java.util.concurrent.ThreadPoolExecutor::getActiveCount).sum());
            run.put("workersAfterStop", executors.stream().mapToInt(java.util.concurrent.ThreadPoolExecutor::getPoolSize).sum());
            Object ni = field(daemon,"nodeInspector");
            run.put("tcpSocketsAfterStop", active(ni));
            run.put("httpCallsAfterStop", active(field(ni,"rpcProber")) + active(field(ni,"beaconProber")));
            byte[] stoppedHash = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(database));
            Thread.sleep(1000);
            run.put("noLateDatabaseMutation", Arrays.equals(stoppedHash, java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(database))));
            run.put("transportCounts", v5.transportCounts()); run.put("diagnostics", v5.diagnostics());
            run.put("runningAfterStop", daemon.isRunning());
            run.put("childProcessesAfterStop", ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count());
            try (var rebound = new DatagramSocket(port)) { run.put("discv4SocketReleased", true); }
            run.put("discoveryThreadsAfterStop", Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && (t.getName().startsWith("discovery-") || t.getName().equals("discv5-events") || t.getName().equals("scanner-api-inspection")))
                .map(Thread::getName).toList());
            run.put("backgroundInspectionThreadsAfterStop", Thread.getAllStackTraces().entrySet().stream()
                .filter(e -> java.util.Arrays.stream(e.getValue()).anyMatch(frame -> frame.getClassName().endsWith(".NodeInspector")))
                .map(e -> e.getKey().getName()).toList());
            runs.add(run);
            report.put("scannerRuns", runs);
            JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("public-report.json").toFile(), report);
        }
        report.put("scannerRuns", runs);
        List<Map<String,Object>> attempts = new ArrayList<>();
        var inspector = new GoEthereumP2pInspector();
        for (NodeRecord node : ipv6Targets) {
            var endpoint = node.getP2pEndpoint(); Map<String,Object> attempt = new LinkedHashMap<>();
            attempt.put("identity", node.getNodeId()); attempt.put("endpoint", endpoint.hostPort()); attempt.put("addressFamily", endpoint.addressFamily().name());
            try { attempt.put("result", inspector.inspect(node).orElse(null)); }
            catch (Exception e) { attempt.put("failure", e.getClass().getSimpleName() + ": " + e.getMessage()); }
            attempts.add(attempt);
        }
        report.put("publicIpv6P2pAttempts", attempts);
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + database); var s = c.createStatement()) {
            for (String table : List.of("nodes", "discovery_observations", "enr_observations", "p2p_observations"))
                try (var rows = s.executeQuery("SELECT count(*) FROM " + table)) { rows.next(); report.put(table + "Count", rows.getLong(1)); }
            report.put("sourceCounts", query(s, "SELECT source,count(*) FROM discovery_observations GROUP BY source"));
            report.put("enrOutcomes", query(s, "SELECT outcome,count(*) FROM enr_observations GROUP BY outcome"));
            report.put("endpointFamilies", query(s, "SELECT json_extract(e.value,'$.addressFamily'),count(*) FROM discovery_observations d,json_each(d.endpoints_json) e GROUP BY 1"));
            report.put("uniqueEndpointFamilies", query(s, "SELECT family,count(*) FROM (SELECT DISTINCT d.node_id,json_extract(e.value,'$.addressFamily') family,json_extract(e.value,'$.address') address,json_extract(e.value,'$.transport') transport,json_extract(e.value,'$.port') port,json_extract(e.value,'$.purpose') purpose FROM discovery_observations d,json_each(d.endpoints_json) e) GROUP BY family"));
            report.put("dualStackIdentities", query(s, "SELECT count(*) FROM (SELECT d.node_id FROM discovery_observations d,json_each(d.endpoints_json) e GROUP BY d.node_id HAVING count(DISTINCT json_extract(e.value,'$.addressFamily'))=2)"));
            report.put("bothProviders", query(s, "SELECT count(*) FROM (SELECT node_id FROM discovery_observations WHERE source IN ('discv4','discv5') GROUP BY node_id HAVING count(DISTINCT source)=2)"));
            report.put("authenticatedDiscv5Observations", query(s, "SELECT count(*) FROM discovery_observations WHERE source='discv5' AND provenance LIKE '%authenticated returned-node session=true%'"));
            report.put("integrity", query(s, "PRAGMA integrity_check")); report.put("foreignKeys", query(s, "PRAGMA foreign_key_check"));
        }
        try (var store = new SqliteNodeStore(database.toString())) {
            var rows = store.findAll(); report.put("reopenedRows", rows.size()); report.put("canonicalIdentities", CanonicalNodes.views(rows).size());
        }
        report.put("finishedAt", java.time.Instant.now().toString());
        JSON.writerWithDefaultPrettyPrinter().writeValue(out.resolve("public-report.json").toFile(), report);
        System.out.println("Validation report: " + out.resolve("public-report.json"));
    }
    static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    static int active(Object object) throws Exception { return ((Set<?>)field(object,"active")).size(); }
    static List<List<Object>> query(Statement statement, String sql) throws Exception {
        List<List<Object>> values = new ArrayList<>();
        try (var result = statement.executeQuery(sql)) {
            while (result.next()) { List<Object> row = new ArrayList<>(); for(int i=1;i<=result.getMetaData().getColumnCount();i++) row.add(result.getObject(i)); values.add(row); }
        }
        return values;
    }
}
