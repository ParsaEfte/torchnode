package io.github.gavinruff007.torchnode.inspection;

import io.github.gavinruff007.torchnode.model.EndpointAddress;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.enr.EnrEvidence;

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
    private final List<Map<String,Object>> endpointAttempts = new ArrayList<>();
    private boolean complete;
    private EnrEvidence enrEvidence;
    private final List<EnrEvidence> enrHistory = new ArrayList<>();

    public InspectionResult(String id, NodeRecord node) {
        this.id = id;
        this.node = node;
        diagnostic("Discovery", State.PASS, null, "Observed through " + node.getDiscoverySource(), "Discovery");
        diagnostic("P2P TCP", State.CHECKING, null, null, "Scanner");
        diagnostic("RLPx Auth", State.NOT_TESTED, null,
                "Waiting for P2P TCP connection", "RLPx");
        diagnostic("RLPx Hello", State.NOT_TESTED, null,
                "Requires a successful authenticated RLPx session", "RLPx");
        diagnostic("ETH Status", State.NOT_TESTED, null,
                "Requires a negotiated ETH capability and Status exchange", "ETH");
        diagnostic("JSON-RPC", State.CHECKING, null, null, "RPC");
        diagnostic("Beacon API", State.CHECKING, null, null, "Beacon");
        diagnostic("ENR", State.CHECKING, null, null, "ENR");
        event("Inspection started", EndpointAddress.hostPort(node.getIp(), node.getTcpPort()));
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
        if (List.of("P2P TCP", "RLPx Auth", "RLPx Hello", "ETH Status").contains(name)) {
            value.put("endpoint", node.getP2pEndpoint().hostPort());
            value.put("addressFamily", node.getP2pEndpoint().addressFamily().name());
        }
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
    public synchronized void loadEnrEvidence(List<EnrEvidence> evidence) { enrHistory.addAll(evidence); }
    public synchronized EnrEvidence enrEvidence() { return enrEvidence; }
    public synchronized void setEnrEvidence(EnrEvidence evidence) {
        enrEvidence = evidence; enrHistory.add(evidence);
        State state = evidence.usable() ? State.PASS : evidence.received() ? State.FAILED
                : evidence.outcome().endsWith("TIMEOUT") ? State.TIMEOUT : State.UNAVAILABLE;
        diagnostic("ENR", state, null, evidence.detail(), evidence.outcome(), "ENR");
    }
    public synchronized EnrEvidence selectedEnr() {
        return EnrEvidence.latestValidated(enrHistory).orElse(enrEvidence);
    }
    private Map<String, Object> enrComparisons(EnrEvidence selected) {
        Map<String, Object> comparisons = new LinkedHashMap<>();
        if (selected == null || !selected.usable()) return comparisons;
        var fields = selected.record().fields();
        comparisons.put("IPv4 vs discovery", compare(fields.ip(), node.getIp()));
        comparisons.put("TCP vs discovery", compare(fields.tcp(), node.getTcpPort()));
        comparisons.put("UDP vs discovery", compare(fields.udp(), node.getUdpPort()));
        Map<String, Object> status = statusMap();
        if (fields.eth() != null && status != null) {
            comparisons.put("Fork hash vs ETH Status", compare(fields.eth().forkHash(), status.get("forkHash")));
            comparisons.put("Fork next vs ETH Status", compare(fields.eth().forkNext(), status.get("forkNext")));
        }
        long variants = enrHistory.stream().filter(EnrEvidence::usable)
                .filter(e -> e.record().sequence().equals(selected.record().sequence())).map(EnrEvidence::rawRlpHex).distinct().count();
        if (variants > 1) comparisons.put("Sequence", "CONFLICTING_RECORDS");
        return comparisons;
    }
    private static String compare(Object a, Object b) {
        return a == null || b == null ? "NOT_AVAILABLE" : a.toString().equalsIgnoreCase(b.toString()) ? "MATCH" : "MISMATCH";
    }

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
        root.put("discv5", node.getObservations().stream().filter(o -> o.source().equals("discv5")).map(o -> {
            Map<String,Object> value = new LinkedHashMap<>(); value.put("identity", o.identity().nodeId());
            value.put("observedAt", o.observedAt().toString()); value.put("endpoints", o.endpoints());
            value.put("provenance", o.provenance()); return value;
        }).toList());
        root.put("endpointAttempts", new ArrayList<>(endpointAttempts));
        root.put("endpointObservations", node.getObservations().stream().map(io.github.gavinruff007.torchnode.model.DiscoveryObservation::toMap).toList());
        root.put("client", clientMap());
        root.put("nodeStack", nodeStack());
        root.put("rpc", rpc);
        root.put("beacon", beacon);
        root.put("rpcProbeEndpoints", rpcProbeEndpoints);
        root.put("beaconProbeEndpoints", beaconProbeEndpoints);
        root.put("p2p", p2p);
        EnrEvidence selected = selectedEnr();
        root.put("enr", selected == null ? null : selected.toMap());
        root.put("enrAttempt", enrEvidence == null ? null : enrEvidence.toMap());
        root.put("enrComparisons", enrComparisons(selected));
        root.put("networkVerification", NetworkVerification.from(rpc, beacon,
                p2p == null ? null : statusMap()));
        root.put("diagnostics", new ArrayList<>(diagnostics.values()));
        root.put("timeline", new ArrayList<>(timeline));
        return root;
    }

    public synchronized void endpointAttempt(io.github.gavinruff007.torchnode.model.NodeEndpoint endpoint) {
        Map<String,Object> attempt = new LinkedHashMap<>();
        attempt.put("endpoint", endpoint.hostPort()); attempt.put("addressFamily", endpoint.addressFamily().name());
        attempt.put("transport", endpoint.transport().name()); attempt.put("port", endpoint.port());
        attempt.put("observedAt", Instant.now().toString());
        attempt.put("provenance", node.getObservations().stream().filter(o -> o.endpoints().contains(endpoint))
            .map(o -> o.source() + ": " + o.provenance()).distinct().toList());
        attempt.put("diagnostics", new ArrayList<>(diagnostics.values()).stream()
            .filter(d -> List.of("P2P TCP", "RLPx Auth", "RLPx Hello", "ETH Status").contains(d.get("name"))).toList());
        attempt.put("p2p", p2p);
        endpointAttempts.add(attempt);
    }
    public synchronized List<Map<String,Object>> endpointAttempts() { return List.copyOf(endpointAttempts); }
    public synchronized void resetP2p() {
        p2p = null;
        for (String name : List.of("RLPx Auth", "RLPx Hello", "ETH Status"))
            diagnostic(name, State.NOT_TESTED, null, "Prerequisite has not completed for this endpoint", "Scanner");
    }
    @SuppressWarnings("unchecked")
    public synchronized void summarizeEndpointAttempts() {
        var best = endpointAttempts.stream().max(java.util.Comparator.comparingInt(a -> {
            var stages = (List<Map<String,Object>>)a.get("diagnostics");
            return (int)stages.stream().filter(d -> "PASS".equals(d.get("state"))).count();
        })).orElse(null);
        if (best == null) return;
        for (var stage : (List<Map<String,Object>>)best.get("diagnostics")) diagnostics.put((String)stage.get("name"), stage);
        p2p = (Map<String,Object>)best.get("p2p");
        var tcp = diagnostics.get("P2P TCP");
        if ("PASS".equals(tcp.get("state"))) node.setP2pConnectMs((Long)tcp.get("durationMs"));
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
        value.put("discovery", node.getDiscoverySource());
        value.put("discoveryEndpoint", EndpointAddress.hostPort(node.getIp(), node.getUdpPort()));
        value.put("p2pEndpoint", diagnostics.get("P2P TCP").get("endpoint"));
        value.put("addressFamily", diagnostics.get("P2P TCP").get("addressFamily"));
        value.put("rpcEndpoint", rpc == null ? null : rpc.get("endpoint"));
        value.put("beaconEndpoint", beacon == null ? null
                : beacon.getOrDefault("endpoint", EndpointAddress.http(node.getIp(), ((Number)beacon.get("port")).intValue())));
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
        String url = "enode://" + id + "@" + EndpointAddress.hostPort(node.getIp(), node.getTcpPort());
        return node.getUdpPort() > 0 && node.getUdpPort() != node.getTcpPort()
                ? url + "?discport=" + node.getUdpPort() : url;
    }
}
