package io.github.gavinruff007.torchnode.inspection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Typed boundary for one authenticated P2P inspection, independent of RPC/Beacon. */
public record P2pInspectionResult(Stage tcp, Stage auth, Stage hello, Stage status,
                                  Map<String, Object> helloInfo, Map<String, Object> statusInfo,
                                  Map<String, Object> helloTrace, Map<String, Object> statusTrace) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Stage(InspectionResult.State state, Long durationMs, String reasonCode) {
        static Stage decode(JsonNode node) {
            if (node == null || !node.isObject()) throw new IllegalArgumentException("Missing P2P stage");
            InspectionResult.State state = InspectionResult.State.valueOf(node.path("state").asText());
            if (state == InspectionResult.State.CHECKING || state == InspectionResult.State.UNAVAILABLE)
                throw new IllegalArgumentException("Invalid P2P stage state");
            Long duration = node.path("durationMs").isIntegralNumber() ? node.path("durationMs").longValue() : null;
            if (duration != null && (duration < 0 || duration > 60_000))
                throw new IllegalArgumentException("Invalid P2P duration");
            if (state == InspectionResult.State.PASS && duration == null)
                throw new IllegalArgumentException("Missing P2P stage timing");
            String code = node.path("reasonCode").isTextual() ? node.path("reasonCode").textValue() : null;
            if (code != null && !code.matches("[A-Z0-9_]{1,80}"))
                throw new IllegalArgumentException("Invalid P2P failure code");
            return new Stage(state, state == InspectionResult.State.PASS ? duration : null, code);
        }

        String explanation() {
            if (reasonCode == null) return null;
            return switch (reasonCode) {
                case "LOCAL_STATUS_RPC_NOT_CONFIGURED" -> "A trusted local Status RPC is not configured";
                case "NO_COMPATIBLE_ETH_CAPABILITY" -> "Peer offers no mutually supported ETH capability";
                case "INVALID_NODE_ID" -> "Discovery did not provide a usable secp256k1 node ID";
                case "TCP_CONNECTION_REFUSED" -> "P2P TCP connection refused";
                case "TCP_TIMEOUT" -> "P2P TCP connection timed out";
                default -> reasonCode.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
            };
        }
    }

    public static P2pInspectionResult decode(byte[] bytes) throws Exception {
        JsonNode node = JSON.readTree(bytes);
        Stage tcp = Stage.decode(node.path("tcp"));
        Stage auth = Stage.decode(node.path("auth"));
        Stage hello = Stage.decode(node.path("hello"));
        Stage status = Stage.decode(node.path("status"));
        if (tcp.state() != InspectionResult.State.PASS && auth.state() != InspectionResult.State.NOT_TESTED
                || auth.state() != InspectionResult.State.PASS && hello.state() != InspectionResult.State.NOT_TESTED
                || hello.state() != InspectionResult.State.PASS && status.state() != InspectionResult.State.NOT_TESTED)
            throw new IllegalArgumentException("Invalid P2P stage transition");
        Map<String, Object> helloInfo = map(node.path("helloInfo"));
        Map<String, Object> statusInfo = map(node.path("statusInfo"));
        Map<String, Object> helloTrace = map(node.path("helloTrace"));
        Map<String, Object> statusTrace = map(node.path("statusTrace"));
        if (hello.state() == InspectionResult.State.PASS && helloInfo == null
                || status.state() == InspectionResult.State.PASS && statusInfo == null)
            throw new IllegalArgumentException("Missing authenticated P2P observation");
        if (hello.state() == InspectionResult.State.PASS &&
                (!node.path("helloInfo").path("nodeId").asText().matches("0x[0-9a-fA-F]{128}")
                        || !node.path("helloInfo").path("capabilities").isArray()
                        || !node.path("helloInfo").path("devp2pVersion").isIntegralNumber()))
            throw new IllegalArgumentException("Malformed Hello observation");
        if (status.state() == InspectionResult.State.PASS &&
                (!node.path("statusInfo").path("networkId").isIntegralNumber()
                        || !node.path("statusInfo").path("genesisHash").asText().matches("0x[0-9a-fA-F]{64}")
                        || !node.path("statusInfo").path("latestBlockHash").asText().matches("0x[0-9a-fA-F]{64}")
                        || !node.path("statusInfo").path("earliestBlock").isIntegralNumber()
                        || !node.path("statusInfo").path("latestBlock").isIntegralNumber()
                        || node.path("statusInfo").path("latestBlock").asLong() <
                        node.path("statusInfo").path("earliestBlock").asLong()
                        || node.path("statusInfo").path("protocolVersion").asInt()
                        != node.path("helloInfo").path("negotiatedEthVersion").asInt()))
            throw new IllegalArgumentException("Malformed ETH Status observation");
        if (helloTrace != null && (auth.state() != InspectionResult.State.PASS
                || !node.path("helloTrace").path("localHelloSent").isBoolean()
                || !node.path("helloTrace").path("framesDecoded").canConvertToInt()
                || node.path("helloTrace").path("framesDecoded").asInt() < 0))
            throw new IllegalArgumentException("Malformed Hello trace");
        if (statusTrace != null && (hello.state() != InspectionResult.State.PASS
                || !node.path("statusTrace").path("localStatusSent").isBoolean()
                || !node.path("statusTrace").path("remoteStatusReceived").isBoolean()
                || status.state() == InspectionResult.State.PASS &&
                (!node.path("statusTrace").path("localStatusSent").asBoolean()
                        || !node.path("statusTrace").path("remoteStatusReceived").asBoolean())))
            throw new IllegalArgumentException("Malformed Status trace");
        return new P2pInspectionResult(tcp, auth, hello, status, helloInfo, statusInfo, helloTrace, statusTrace);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(JsonNode node) {
        return node.isObject() ? JSON.convertValue(node, LinkedHashMap.class) : null;
    }
}
