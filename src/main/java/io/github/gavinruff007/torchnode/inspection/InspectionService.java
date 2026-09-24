package io.github.gavinruff007.torchnode.inspection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.model.NodeType;
import io.github.gavinruff007.torchnode.storage.NodeStore;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String databasePath;
    private final RpcProber rpcProber = new RpcProber();
    private final BeaconProber beaconProber = new BeaconProber();
    private final GoEthereumP2pInspector p2pInspector = new GoEthereumP2pInspector();
    private final ExecutorService executor = Executors.newFixedThreadPool(12);
    private final Map<String, InspectionResult> results = new ConcurrentHashMap<>();

    public InspectionService(String databasePath) {
        this.databasePath = databasePath;
    }

    public String inspect(NodeRecord node) {
        prune();
        String id = UUID.randomUUID().toString();
        InspectionResult result = new InspectionResult(id, node);
        results.put(id, result);

        CompletableFuture<Void> tcp = CompletableFuture.runAsync(() -> inspectP2p(result), executor);
        CompletableFuture<Void> rpc = CompletableFuture.runAsync(() -> inspectRpc(result), executor);
        CompletableFuture<Void> beacon = CompletableFuture.runAsync(() -> inspectBeacon(result), executor);
        CompletableFuture.allOf(tcp, rpc, beacon).whenComplete((ignored, error) -> finish(result, error));
        return id;
    }

    public Optional<Map<String, Object>> snapshot(String id) {
        InspectionResult result = results.get(id);
        return result == null ? Optional.empty() : Optional.of(result.snapshot());
    }

    private void inspectTcp(InspectionResult result) {
        NodeRecord node = result.node();
        if (node.getTcpPort() <= 0) {
            result.diagnostic("P2P TCP", InspectionResult.State.UNAVAILABLE, null,
                    "No TCP port was advertised", "Discovery");
            return;
        }
        long started = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(node.getIp(), node.getTcpPort()), CONNECT_TIMEOUT_MS);
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
                    "Connection refused from scanner", "Scanner");
            result.event("TCP connection refused", null);
        } catch (Exception e) {
            result.diagnostic("P2P TCP", InspectionResult.State.FAILED, null,
                    concise(e), "Scanner");
            result.event("TCP connection failed", concise(e));
        }
    }

    private void inspectP2p(InspectionResult result) {
        if (result.node().getTcpPort() <= 0) {
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
        for (int port : RPC_PORTS) {
            attempted.add("http://" + result.node().getIp() + ":" + port);
            result.setRpcProbeEndpoints(attempted);
            if (!tcpOpen(result.node().getIp(), port, 800)) continue;
            endpointOpen = true;
            RpcProber.RpcInfo info = rpcProber.probeDetailed(result.node().getIp(), port);
            if (info.reachable) {
                NodeRecord node = result.node();
                node.setRpcAvailable(true);
                if (info.clientVersion != null) node.setClientVersion(info.clientVersion);
                node.setSyncing(info.syncing);
                node.setBlockNumber(info.blockNumber);
                node.setPendingTransactions(info.pendingTxCount);
                result.setRpc(rpcMap(info, node.getIp()));
                result.diagnostic("JSON-RPC", InspectionResult.State.PASS, info.responseMs, null, "RPC");
                result.event("JSON-RPC detected", node.getIp() + ":" + port);
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
        for (int port : BEACON_PORTS) {
            attempted.add("http://" + result.node().getIp() + ":" + port);
            result.setBeaconProbeEndpoints(attempted);
            if (!tcpOpen(result.node().getIp(), port, 800)) continue;
            endpointOpen = true;
            BeaconProber.BeaconInfo info = beaconProber.probeDetailed(result.node().getIp(), port);
            if (info.reachable) {
                NodeRecord node = result.node();
                node.setBeaconAvailable(true);
                result.setBeacon(beaconMap(info));
                result.diagnostic("Beacon API", InspectionResult.State.PASS, info.responseMs, null, "Beacon");
                result.event("Beacon API detected", node.getIp() + ":" + port);
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

    private void finish(InspectionResult result, Throwable error) {
        NodeRecord node = result.node();
        if (node.isRpcAvailable() && node.isBeaconAvailable()) node.setNodeType(NodeType.FULL_NODE);
        else if (node.isRpcAvailable()) node.setNodeType(NodeType.EXECUTION);
        else if (node.isBeaconAvailable()) node.setNodeType(NodeType.CONSENSUS);
        if (error != null) result.event("Inspection partially completed", concise(error));
        try (NodeStore store = new SqliteNodeStore(databasePath)) {
            store.update(node);
            if (store instanceof SqliteNodeStore sqlite && result.p2p() != null &&
                    (result.p2p().get("hello") != null || result.p2p().get("status") != null)) {
                Map<String, Object> p2p = result.p2p();
                sqlite.saveP2pObservation(node.getKey(),
                        p2p.get("hello") == null ? null : JSON.writeValueAsString(p2p.get("hello")),
                        p2p.get("status") == null ? null : JSON.writeValueAsString(p2p.get("status")));
            }
        } catch (Exception e) {
            result.event("Database update failed", concise(e));
        }
        result.complete();
    }

    private Map<String, Object> rpcMap(RpcProber.RpcInfo info, String ip) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("endpoint", "http://" + ip + ":" + info.port);
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

    private boolean tcpOpen(String ip, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        }
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
    public void close() { executor.shutdownNow(); }
}
