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
}
