package io.github.gavinruff007.torchnode.inspection;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InspectionResultTest {
    @Test
    void exposesDerivedEnodeAndHonestUnsupportedChecks() {
        String nodeId = "ab".repeat(64);
        NodeRecord node = new NodeRecord("192.0.2.10", 30303, 30304, nodeId);
        InspectionResult result = new InspectionResult("test", node);

        Map<String, Object> snapshot = result.snapshot();
        Map<?, ?> nodeData = (Map<?, ?>) snapshot.get("node");
        List<?> diagnostics = (List<?>) snapshot.get("diagnostics");

        assertEquals("enode://" + nodeId + "@192.0.2.10:30304?discport=30303", nodeData.get("enode"));
        assertEquals("0x" + nodeId, nodeData.get("nodeId"));
        assertEquals("192.0.2.10:30303", nodeData.get("discoveryEndpoint"));
        assertEquals("192.0.2.10:30304", nodeData.get("p2pEndpoint"));
        assertEquals("RLPx Auth", ((Map<?, ?>) diagnostics.get(2)).get("name"));
        assertEquals("RLPx Hello", ((Map<?, ?>) diagnostics.get(3)).get("name"));
        assertEquals("ETH Status", ((Map<?, ?>) diagnostics.get(4)).get("name"));
        result.diagnostic("P2P TCP", InspectionResult.State.PASS, 42L, null, "Scanner");
        diagnostics = (List<?>) result.snapshot().get("diagnostics");
        for (int i = 2; i <= 4; i++) {
            assertEquals("NOT_TESTED", ((Map<?, ?>) diagnostics.get(i)).get("state"));
        }
    }
    @Test
    void helloZeroPortAndMissingClientCannotOverwriteDiscoveryOrInferBscClient() {
        NodeRecord node = new NodeRecord("192.0.2.1", 30301, 30305, "ab".repeat(64));
        var result = new InspectionResult("test", node);
        result.setP2p(Map.of("hello", Map.of("listenPort", 0, "capabilities", List.of("bsc/1", "bsc/2", "eth/68"))));
        result.diagnostic("RLPx Auth", InspectionResult.State.PASS, 1L, null, "RLPx");
        result.diagnostic("RLPx Hello", InspectionResult.State.PASS, 1L, null, "RLPx Hello");
        result.diagnostic("ETH Status", InspectionResult.State.NOT_TESTED, null,
                "Peer offers no mutually supported ETH capability", "ETH Status");
        var snapshot = result.snapshot();
        assertEquals(30305, node.getP2pEndpoint().port());
        assertEquals(30301, node.getUdpPort());
        assertEquals(null, snapshot.get("client"));
        var diagnostics = (List<?>) snapshot.get("diagnostics");
        assertEquals("PASS", ((Map<?, ?>) diagnostics.get(2)).get("state"));
        assertEquals("PASS", ((Map<?, ?>) diagnostics.get(3)).get("state"));
        assertEquals("NOT_TESTED", ((Map<?, ?>) diagnostics.get(4)).get("state"));
    }
    @Test
    void helloClientAndCapabilitiesRemainAvailableWhenBothApisFail() {
        var result = new InspectionResult("test", new NodeRecord("192.0.2.1", 30301, 30305, "ab".repeat(64)));
        result.setP2p(Map.of("hello", Map.of("clientId", "reth/v1.7.0-9d56da5/x86_64-unknown-linux-gnu",
                "listenPort", 0, "capabilities", List.of(Map.of("name", "eth", "version", 68)))));
        result.diagnostic("JSON-RPC", InspectionResult.State.UNAVAILABLE, null, "No API", "RPC");
        result.diagnostic("Beacon API", InspectionResult.State.UNAVAILABLE, null, "No API", "Beacon");
        var snapshot = result.snapshot();
        assertEquals("Reth", ((Map<?, ?>) snapshot.get("client")).get("name"));
        assertEquals("RLPx Hello", ((Map<?, ?>) snapshot.get("client")).get("source"));
        assertEquals(null, snapshot.get("rpc")); assertEquals(null, snapshot.get("beacon"));
        assertEquals(30305, result.node().getP2pEndpoint().port());
    }
}
