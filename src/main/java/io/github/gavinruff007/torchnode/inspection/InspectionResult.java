package io.github.gavinruff007.torchnode.inspection;

import io.github.gavinruff007.torchnode.model.NodeRecord;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class InspectionResult {
    public enum State { CHECKING, PASS, FAILED, TIMEOUT, UNAVAILABLE, NOT_TESTED }

    private final String id;
    private final NodeRecord node;
    private final Instant startedAt = Instant.now();
    private final Map<String, Map<String, Object>> diagnostics = new LinkedHashMap<>();
    private final List<Map<String, Object>> timeline = new ArrayList<>();
    private Map<String, Object> rpc;
    private Map<String, Object> beacon;
    private Map<String, Object> p2p;
    private List<String> rpcProbeEndpoints = List.of();
    private List<String> beaconProbeEndpoints = List.of();
    private boolean complete;

    public InspectionResult(String id, NodeRecord node) {
        this.id = id;
        this.node = node;
        diagnostic("Discovery", State.PASS, null, "Observed through discv4", "Discovery");
        diagnostic("P2P TCP", State.CHECKING, null, null, "Scanner");
        diagnostic("RLPx Auth", State.NOT_TESTED, null,
                "Waiting for P2P TCP connection", "RLPx");
        diagnostic("RLPx Hello", State.NOT_TESTED, null,
                "Requires a successful authenticated RLPx session", "RLPx");
        diagnostic("ETH Status", State.NOT_TESTED, null,
                "Requires a negotiated ETH capability and Status exchange", "ETH");
        diagnostic("JSON-RPC", State.CHECKING, null, null, "RPC");
        diagnostic("Beacon API", State.CHECKING, null, null, "Beacon");
        event("Inspection started", node.getIp() + ":" + node.getTcpPort());
    }

    public synchronized void diagnostic(String name, State state, Long durationMs,
                                        String reason, String source) {
        diagnostic(name, state, durationMs, reason, null, source);
    }

    public synchronized void diagnostic(String name, State state, Long durationMs,
                                        String reason, String reasonCode, String source) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("state", state.name());
        value.put("durationMs", durationMs);
        value.put("reason", reason);
        value.put("reasonCode", reasonCode);
        value.put("source", source);
        diagnostics.put(name, value);
    }

    public synchronized void event(String label, String detail) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("timestamp", Instant.now().toString());
        event.put("label", label);
        event.put("detail", detail);
        timeline.add(event);
    }

    public synchronized void setRpc(Map<String, Object> rpc) { this.rpc = rpc; }
    public synchronized void setBeacon(Map<String, Object> beacon) { this.beacon = beacon; }
    public synchronized void setP2p(Map<String, Object> p2p) { this.p2p = p2p; }
    public synchronized void setRpcProbeEndpoints(List<String> endpoints) { this.rpcProbeEndpoints = List.copyOf(endpoints); }
    public synchronized void setBeaconProbeEndpoints(List<String> endpoints) { this.beaconProbeEndpoints = List.copyOf(endpoints); }
    public synchronized void complete() {
        complete = true;
        event("Inspection completed", (System.currentTimeMillis() - startedAt.toEpochMilli()) + " ms");
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", id);
        root.put("complete", complete);
        root.put("startedAt", startedAt.toString());
        root.put("node", nodeMap());
        root.put("client", clientMap());
        root.put("nodeStack", nodeStack());
        root.put("rpc", rpc);
        root.put("beacon", beacon);
        root.put("rpcProbeEndpoints", rpcProbeEndpoints);
        root.put("beaconProbeEndpoints", beaconProbeEndpoints);
        root.put("p2p", p2p);
        root.put("networkVerification", NetworkVerification.from(rpc, beacon,
                p2p == null ? null : statusMap()));
        root.put("diagnostics", new ArrayList<>(diagnostics.values()));
        root.put("timeline", new ArrayList<>(timeline));
        return root;
    }

    public NodeRecord node() { return node; }
    public synchronized Map<String, Object> p2p() { return p2p; }

    private Map<String, Object> nodeStack() {
        ClientDetails execution = ClientDetails.parse(rpc != null ? (String) rpc.get("clientVersion")
                : p2p == null || p2p.get("hello") == null ? null
                : (String) ((Map<?, ?>) p2p.get("hello")).get("clientId"));
        ClientDetails consensus = beacon == null ? null
                : ClientDetails.parse((String) beacon.get("version"));
        if (execution == null || consensus == null) return null;
        Map<String, Object> stack = new LinkedHashMap<>();
        stack.put("execution", execution.toMap());
        stack.put("consensus", consensus.toMap());
        stack.put("network", NetworkVerification.from(rpc, beacon, statusMap()).get("network"));
        return stack;
    }

    private Map<String, Object> clientMap() {
        String currentRpcClient = rpc == null ? null : (String) rpc.get("clientVersion");
        String p2pClient = p2p == null || p2p.get("hello") == null ? null
                : (String) ((Map<?, ?>) p2p.get("hello")).get("clientId");
        ClientDetails details = ClientDetails.parse(currentRpcClient != null
                ? currentRpcClient : p2pClient != null ? p2pClient : node.getClientVersion());
        if (details == null) return null;
        Map<String, Object> value = new LinkedHashMap<>(details.toMap());
        value.put("source", currentRpcClient != null ? "RPC" : p2pClient != null ? "RLPx Hello" : "Scanner");
        value.put("kind", currentRpcClient == null && p2pClient == null ? "Last known client" : "Execution client");
        return value;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> statusMap() {
        return p2p == null ? null : (Map<String, Object>) p2p.get("status");
    }

    private Map<String, Object> nodeMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("key", node.getKey());
        value.put("ip", node.getIp());
        value.put("udpPort", node.getUdpPort());
        value.put("tcpPort", node.getTcpPort());
        value.put("nodeId", node.getNodeId().isBlank() ? null : "0x" + node.getNodeId());
        value.put("enode", enodeUrl());
        value.put("discovery", "discv4");
        value.put("discoveryEndpoint", node.getIp() + ":" + node.getUdpPort());
        value.put("p2pEndpoint", node.getTcpPort() > 0 ? node.getIp() + ":" + node.getTcpPort() : null);
        value.put("rpcEndpoint", rpc == null ? null : rpc.get("endpoint"));
        value.put("beaconEndpoint", beacon == null ? null
                : "http://" + node.getIp() + ":" + beacon.get("port"));
        value.put("country", node.getCountry());
        value.put("nodeType", node.getNodeType().name());
        value.put("lastSeen", node.getLastSeen() == null ? null : node.getLastSeen().toString());
        value.put("p2pConnectMs", node.getP2pConnectMs());
        value.put("rpcAvailable", node.isRpcAvailable());
        value.put("beaconAvailable", node.isBeaconAvailable());
        return value;
    }

    private String enodeUrl() {
        String id = node.getNodeId();
        if (id == null || !id.matches("(?i)[0-9a-f]{128}") || node.getTcpPort() <= 0) return null;
        String url = "enode://" + id + "@" + node.getIp() + ":" + node.getTcpPort();
        return node.getUdpPort() > 0 && node.getUdpPort() != node.getTcpPort()
                ? url + "?discport=" + node.getUdpPort() : url;
    }
}
