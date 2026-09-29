package io.github.gavinruff007.torchnode.inspection;

import io.github.gavinruff007.torchnode.model.EndpointAddress;

import io.github.gavinruff007.torchnode.enr.EnrAcquirer;
import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.model.NodeType;
import io.github.gavinruff007.torchnode.storage.NodeStore;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class InspectionService implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int[] RPC_PORTS = {8545, 8546, 30303};
    private static final int[] BEACON_PORTS = {5052, 5051, 9000};

    private final String databasePath;
    private final RpcProber rpcProber = new RpcProber();
    private final BeaconProber beaconProber = new BeaconProber();
    private final EnrAcquirer enrAcquirer = new EnrAcquirer();
    private final GoEthereumP2pInspector p2pInspector = new GoEthereumP2pInspector();
    private final ExecutorService executor = Executors.newFixedThreadPool(12);
    private final Map<String, InspectionResult> results = new ConcurrentHashMap<>();
    private final Object persistenceLock = new Object();
    private long generation;
    private volatile boolean closed;
    private final io.github.gavinruff007.torchnode.enrichment.NetworkEnrichmentService enrichment;
    private final java.util.Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();

    public InspectionService(String databasePath) {
        this(databasePath, io.github.gavinruff007.torchnode.enrichment.OfflineGeoIpProvider.configured());
    }

    public InspectionService(String databasePath, io.github.gavinruff007.torchnode.enrichment.NetworkEnrichmentProvider provider) {
        this.databasePath = databasePath;
        enrichment=new io.github.gavinruff007.torchnode.enrichment.NetworkEnrichmentService(databasePath, provider);
    }

    public String inspect(NodeRecord node) {
        synchronized (persistenceLock) {
            if (closed) throw new IllegalStateException("Inspection service closed");
            prune();
            String id = UUID.randomUUID().toString();
            InspectionResult result = new InspectionResult(id, node);
            results.put(id, result);
            try (SqliteNodeStore store = new SqliteNodeStore(databasePath)) {
                result.loadEnrEvidence(store.findEnrEvidence(node.identity()));
                result.loadEndpointEvidence(store.findEndpointEvidence(node.identity()));
                node.setObservations(store.findObservations(node.identity()));
                result.loadNetworkEnrichment(store.networkEnrichmentView(node.identity()));
            }
            catch (Exception e) { result.event("Saved ENR evidence unavailable", concise(e)); }
            CompletableFuture<Void> enr;
            if (node.getDiscoverySource().equals("discv5")) {
                // This provider already delivered ENR evidence; do not send discv4 packets to its UDP endpoint.
                try (SqliteNodeStore store = new SqliteNodeStore(databasePath)) {
                    var saved = io.github.gavinruff007.torchnode.enr.EnrEvidence.latestValidated(store.findEnrEvidence(node.identity()));
                    result.setEnrEvidence(saved.orElseGet(() -> io.github.gavinruff007.torchnode.enr.EnrEvidence.unavailable(
                        node.identity(), "DISCV5 provider evidence", "ENR_NOT_AVAILABLE", "Provider ENR not yet persisted")));
                } catch (Exception e) { result.event("Provider ENR unavailable", concise(e)); }
                enr = CompletableFuture.completedFuture(null);
            } else enr = enrAcquirer.acquire(node).thenAccept(result::setEnrEvidence);

            enr = enr.handle((ignored, error) -> null).thenRun(() -> {
                var saved = result.selectedEnr();
                if (saved != null && saved.usable() && saved.associatedIdentity().equals(node.identity())) {
                    var observations = new ArrayList<>(node.getObservations());
                    var claim = saved.observation().orElseThrow();
                    if (!observations.contains(claim)) observations.add(claim);
                    node.setObservations(observations);
                }
            });
            CompletableFuture<Void> tcp = enr.thenRunAsync(() -> inspectP2p(result), executor);
            CompletableFuture<Void> rpc = enr.thenRunAsync(() -> inspectRpc(result), executor);
            CompletableFuture<Void> beacon = enr.thenRunAsync(() -> inspectBeacon(result), executor);
            long startedGeneration = generation;
            CompletableFuture.allOf(tcp, rpc, beacon, enr).whenComplete(
                    (ignored, error) -> finish(result, error, startedGeneration));
            return id;
        }
    }

    public void clearCollectedData(SqliteNodeStore store) throws SQLException {
        synchronized (persistenceLock) {
            enrichment.clear();
            store.clearCollectedData();
            generation++;
            enrAcquirer.clear();
            results.clear();
        }
    }

    public Optional<Map<String, Object>> snapshot(String id) {
        InspectionResult result = results.get(id);
        return result == null ? Optional.empty() : Optional.of(result.snapshot());
    }

    private void inspectTcp(InspectionResult result) {
        NodeRecord node = result.node();
        if (node.getP2pEndpoint().port() <= 0) {
            result.diagnostic("P2P TCP", InspectionResult.State.UNAVAILABLE, null,
                    "No TCP port was advertised", "Discovery");
            return;
        }
        long started = System.nanoTime();
        Socket socket = new Socket(); activeSockets.add(socket);
        try (socket) {
            if (closed || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Inspection cancelled");
            socket.connect(EndpointAddress.socket(node.getP2pEndpoint().address(), node.getP2pEndpoint().port()), CONNECT_TIMEOUT_MS);
            long duration = elapsedMs(started);
            node.setP2pConnectMs(duration);
            result.diagnostic("P2P TCP", InspectionResult.State.PASS, duration, null, "Scanner");
            result.event("TCP connection established", duration + " ms");
        } catch (SocketTimeoutException e) {
            result.diagnostic("P2P TCP", InspectionResult.State.TIMEOUT, null,
                    "Connection timed out after 3 seconds", "Scanner");
            result.event("TCP connection timed out", null);
        } catch (ConnectException e) {
            result.diagnostic("P2P TCP", InspectionResult.State.FAILED, null,
                    concise(e), "Scanner");
            result.event("TCP connection failed", concise(e));
        } catch (Exception e) {
            result.diagnostic("P2P TCP", InspectionResult.State.FAILED, null,
                    concise(e), "Scanner");
            result.event("TCP connection failed", concise(e));
        } finally { activeSockets.remove(socket); }
    }

    private void inspectP2p(InspectionResult result) {
        var original = result.node().getP2pEndpoint();
        try {
            var endpoints = result.node().p2pEndpoints();
            if (endpoints.isEmpty()) {
                result.diagnostic("P2P TCP", InspectionResult.State.UNAVAILABLE, null, "No usable advertised P2P endpoint", "Discovery");
                return;
            }
            for (var endpoint : endpoints) {
                if (closed || Thread.currentThread().isInterrupted()) break;
                result.node().selectEndpoint(endpoint);
                result.resetP2p();
                inspectP2pEndpoint(result);
                result.endpointAttempt(endpoint);
            }
        } finally { result.node().selectEndpoint(original); result.summarizeEndpointAttempts(); }
    }

    private void inspectP2pEndpoint(InspectionResult result) {
        if (result.node().getP2pEndpoint().port() <= 0) {
            inspectTcp(result);
            result.diagnostic("RLPx Auth", InspectionResult.State.NOT_TESTED, null,
                    "No advertised P2P TCP endpoint", "NO_TCP_ENDPOINT", "RLPx");
            return;
        }
        if (!p2pInspector.available()) {
            inspectTcp(result);
            result.diagnostic("RLPx Auth", InspectionResult.State.NOT_TESTED, null,
                    p2pInspector.unavailableReason(), "P2P_HELPER_UNAVAILABLE", "RLPx");
            return;
        }
        try {
            Optional<P2pInspectionResult> observation = p2pInspector.inspect(result.node());
            if (observation.isEmpty()) { inspectTcp(result); return; }
            P2pInspectionResult inspected = observation.get();
            recordP2pStage(result, "P2P TCP", inspected.tcp(), "Scanner");
            recordP2pStage(result, "RLPx Auth", inspected.auth(), "RLPx");
            recordP2pStage(result, "RLPx Hello", inspected.hello(), "RLPx Hello");
            recordP2pStage(result, "ETH Status", inspected.status(), "ETH Status");
            if (inspected.tcp().state() == InspectionResult.State.PASS)
                result.node().setP2pConnectMs(inspected.tcp().durationMs());
            if (inspected.helloInfo() != null || inspected.statusInfo() != null ||
                    inspected.helloTrace() != null || inspected.statusTrace() != null) {
                Map<String, Object> p2p = new LinkedHashMap<>();
                p2p.put("hello", inspected.helloInfo());
                p2p.put("status", inspected.statusInfo());
                p2p.put("helloTrace", inspected.helloTrace());
                p2p.put("statusTrace", inspected.statusTrace());
                result.setP2p(p2p);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.diagnostic("P2P TCP", InspectionResult.State.NOT_TESTED, null,
                    "Inspection cancelled", "CANCELLED", "Scanner");
            result.diagnostic("RLPx Auth", InspectionResult.State.NOT_TESTED, null,
                    "Inspection cancelled", "CANCELLED", "RLPx");
        } catch (Exception e) {
            inspectTcp(result);
            result.diagnostic("RLPx Auth", InspectionResult.State.NOT_TESTED, null,
                    "P2P helper could not complete inspection", "P2P_HELPER_ERROR", "RLPx");
            result.event("P2P helper unavailable", null);
        }
    }

    private void recordP2pStage(InspectionResult result, String name,
                                P2pInspectionResult.Stage stage, String source) {
        result.diagnostic(name, stage.state(), stage.durationMs(), stage.explanation(), stage.reasonCode(), source);
        if (stage.state() == InspectionResult.State.PASS)
            result.event(name + " completed", stage.durationMs() == null ? null : stage.durationMs() + " ms");
        else if (stage.state() == InspectionResult.State.FAILED || stage.state() == InspectionResult.State.TIMEOUT)
            result.event(name + " " + stage.state().name().toLowerCase(), stage.explanation());
    }

    private void inspectRpc(InspectionResult result) {
        List<String> attempted = new ArrayList<>();
        boolean endpointOpen = false;
        String lastError = null;
        for (String ip : probeAddresses(result.node())) for (int port : RPC_PORTS) {
            if (closed || Thread.currentThread().isInterrupted()) return;
            attempted.add(EndpointAddress.http(ip, port));
            result.setRpcProbeEndpoints(attempted);
            if (!tcpOpen(ip, port, 800)) continue;
            endpointOpen = true;
            RpcProber.RpcInfo info = rpcProber.probeDetailed(ip, port);
            if (info.reachable) {
                NodeRecord node = result.node();
                node.setRpcAvailable(true);
                if (info.clientVersion != null) node.setClientVersion(info.clientVersion);
                node.setSyncing(info.syncing);
                node.setBlockNumber(info.blockNumber);
                node.setPendingTransactions(info.pendingTxCount);
                result.setRpc(rpcMap(info, ip));
                result.diagnostic("JSON-RPC", InspectionResult.State.PASS, info.responseMs, null, "RPC");
                result.event("JSON-RPC detected", EndpointAddress.hostPort(ip, port));
                if (info.clientVersion != null) result.event("Client identified", info.clientVersion);
                return;
            }
            lastError = info.error;
        }
        InspectionResult.State state = !endpointOpen ? InspectionResult.State.UNAVAILABLE
                : isTimeout(lastError) ? InspectionResult.State.TIMEOUT : InspectionResult.State.FAILED;
        String reason = endpointOpen && lastError != null ? lastError
                : "No JSON-RPC response on supported ports " + String.join(", ", attempted);
        result.diagnostic("JSON-RPC", state, null, reason, "RPC");
    }

    private void inspectBeacon(InspectionResult result) {
        List<String> attempted = new ArrayList<>();
        boolean endpointOpen = false;
        String lastError = null;
        for (String ip : probeAddresses(result.node())) for (int port : BEACON_PORTS) {
            if (closed || Thread.currentThread().isInterrupted()) return;
            attempted.add(EndpointAddress.http(ip, port));
            result.setBeaconProbeEndpoints(attempted);
            if (!tcpOpen(ip, port, 800)) continue;
            endpointOpen = true;
            BeaconProber.BeaconInfo info = beaconProber.probeDetailed(ip, port);
            if (info.reachable) {
                NodeRecord node = result.node();
                node.setBeaconAvailable(true);
                var evidence = beaconMap(info); evidence.put("endpoint", EndpointAddress.http(ip, port)); result.setBeacon(evidence);
                result.diagnostic("Beacon API", InspectionResult.State.PASS, info.responseMs, null, "Beacon");
                result.event("Beacon API detected", EndpointAddress.hostPort(ip, port));
                return;
            }
            lastError = info.error;
        }
        InspectionResult.State state = !endpointOpen ? InspectionResult.State.UNAVAILABLE
                : isTimeout(lastError) ? InspectionResult.State.TIMEOUT : InspectionResult.State.FAILED;
        String reason = endpointOpen && lastError != null ? lastError
                : "No Beacon API response on supported ports " + String.join(", ", attempted);
        result.diagnostic("Beacon API", state, null, reason, "Beacon");
    }

    private void finish(InspectionResult result, Throwable error, long startedGeneration) {
        synchronized (persistenceLock) {
            if (startedGeneration != generation) return;
            NodeRecord node = result.node();
            if (node.isRpcAvailable() && node.isBeaconAvailable()) node.setNodeType(NodeType.FULL_NODE);
            else if (node.isRpcAvailable()) node.setNodeType(NodeType.EXECUTION);
            else if (node.isBeaconAvailable()) node.setNodeType(NodeType.CONSENSUS);
            if (error != null) result.event("Inspection partially completed", concise(error));
            try (NodeStore store = new SqliteNodeStore(databasePath)) {
                store.update(node);
                if (store instanceof SqliteNodeStore sqlite && result.enrEvidence() != null)
                    sqlite.saveEnrEvidence(result.enrEvidence());
                if (store instanceof SqliteNodeStore sqlite && (!result.endpointAttempts().isEmpty() || !result.apiEndpointEvidence().isEmpty())) {
                    var p2p = result.p2p();
                    @SuppressWarnings("unchecked") Map<String,Object> hello = p2p == null ? null : (Map<String,Object>)p2p.get("hello");
                    @SuppressWarnings("unchecked") Map<String,Object> status = p2p == null ? null : (Map<String,Object>)p2p.get("status");
                    sqlite.saveEndpointInspection(node.getKey(), hello, status, result.endpointAttempts(), result.apiEndpointEvidence());
                }
                if(store instanceof SqliteNodeStore sqlite) {
                    var view=sqlite.networkEnrichmentView(node.identity());result.loadNetworkEnrichment(view);
                    for(var address:view.stream().map(v->((io.github.gavinruff007.torchnode.model.NodeEndpoint)v.get("endpoint")).address()).distinct().toList())
                        enrichment.request(address).thenAccept(value->{
                            synchronized(persistenceLock) {
                                if(closed || generation!=startedGeneration)return;
                                try(var enrichedStore=new SqliteNodeStore(databasePath)){result.loadNetworkEnrichment(enrichedStore.networkEnrichmentView(node.identity()));}
                                catch(Exception ignored){} // Independent enrichment must never fail protocol inspection.
                            }
                        });
                }
            } catch (Exception e) {
                result.event("Database update failed", concise(e));
            }
            result.complete();
        }
    }

    private Map<String, Object> rpcMap(RpcProber.RpcInfo info, String ip) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("endpoint", EndpointAddress.http(ip, info.port));
        map.put("port", info.port);
        map.put("responseMs", info.responseMs);
        map.put("clientVersion", info.clientVersion);
        map.put("chainId", info.chainId);
        map.put("network", networkName(info.chainId));
        map.put("networkId", info.networkId);
        map.put("blockNumber", info.blockNumber);
        map.put("peerCount", info.peerCount);
        map.put("syncing", info.syncing);
        map.put("methodStatus", info.methodStatus);
        return map;
    }

    private Map<String, Object> beaconMap(BeaconProber.BeaconInfo info) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("port", info.port);
        map.put("responseMs", info.responseMs);
        map.put("version", info.version);
        map.put("headSlot", info.slot);
        map.put("syncing", info.syncing);
        map.put("syncDistance", info.syncDistance);
        map.put("optimistic", info.optimistic);
        map.put("executionOffline", info.executionOffline);
        map.put("genesisTime", info.genesisTime);
        map.put("genesisValidatorsRoot", info.genesisValidatorsRoot);
        return map;
    }

    private String networkName(Long chainId) {
        if (chainId == null) return null;
        return switch (chainId.intValue()) {
            case 1 -> "Ethereum Mainnet";
            case 11155111 -> "Sepolia";
            case 17000 -> "Holesky";
            case 560048 -> "Hoodi";
            default -> "Chain " + chainId;
        };
    }

    private List<String> probeAddresses(NodeRecord node) {
        var addresses = new ArrayList<String>(); addresses.add(node.getIp());
        node.getObservations().stream().flatMap(o -> o.endpoints().stream()).map(e -> e.address()).forEach(addresses::add);
        return java.util.Arrays.stream(io.github.gavinruff007.torchnode.model.NodeEndpoint.AddressFamily.values())
            .flatMap(family -> addresses.stream().map(ip -> EndpointAddress.parse(ip).getHostAddress()).distinct().filter(EndpointAddress::activeTarget).filter(ip -> EndpointAddress.family(ip) == family).limit(2)).toList();
    }

    private boolean tcpOpen(String ip, int port, int timeoutMs) {
        Socket socket = new Socket(); activeSockets.add(socket);
        try (socket) {
            if (closed || Thread.currentThread().isInterrupted()) return false;
            socket.connect(EndpointAddress.socket(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally { activeSockets.remove(socket); }
    }

    private long elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000; }
    private boolean isTimeout(String message) {
        if (message == null) return false;
        String normalized = message.toLowerCase();
        return normalized.contains("timeout") || normalized.contains("timed out");
    }
    private String concise(Throwable e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private void prune() {
        if (results.size() < 200) return;
        results.keySet().stream().limit(50).toList().forEach(results::remove);
    }

    @Override
    public void close() {
        synchronized (persistenceLock) { closed = true; generation++; }
        enrichment.close();
        enrAcquirer.close(); executor.shutdownNow();
        activeSockets.forEach(socket -> { try { socket.close(); } catch (java.io.IOException ignored) {} });
        rpcProber.close(); beaconProber.close();
        boolean interrupted = false;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!executor.isTerminated() && System.nanoTime() < deadline) {
            try { executor.awaitTermination(100, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
