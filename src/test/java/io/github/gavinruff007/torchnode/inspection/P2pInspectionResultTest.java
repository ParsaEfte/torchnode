package io.github.gavinruff007.torchnode.inspection;

import org.junit.jupiter.api.Test;
import io.github.gavinruff007.torchnode.model.NodeRecord;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;

class P2pInspectionResultTest {
    @Test
    void acceptsIndependentAuthHelloAndStatusObservations() throws Exception {
        P2pInspectionResult result = P2pInspectionResult.decode(json("""
                {"tcp":{"state":"PASS","durationMs":19},
                 "auth":{"state":"PASS","durationMs":44},
                 "hello":{"state":"PASS","durationMs":12},
                 "status":{"state":"PASS","durationMs":25},
                 "helloInfo":{"clientId":"Geth/test","devp2pVersion":5,
                    "nodeId":"0x%s","capabilities":[],"negotiatedEthVersion":69},
                 "statusTrace":{"localStatusSent":true,"remoteStatusReceived":true},
                 "statusInfo":{"protocolVersion":69,"networkId":1,
                    "genesisHash":"0x%s","earliestBlock":10,"latestBlock":12,
                    "latestBlockHash":"0x%s"}}
                """.formatted("ab".repeat(64), "00".repeat(32), "01".repeat(32))));
        assertEquals(InspectionResult.State.PASS, result.status().state());
        assertEquals(1, result.statusInfo().get("networkId"));
    }

    @Test
    void rejectsImpossibleDownstreamPassOrMalformedFailureCode() {
        assertThrows(IllegalArgumentException.class, () -> P2pInspectionResult.decode(json("""
                {"tcp":{"state":"FAILED"},"auth":{"state":"PASS"},
                 "hello":{"state":"NOT_TESTED"},"status":{"state":"NOT_TESTED"}}
                """)));
        assertThrows(IllegalArgumentException.class, () -> P2pInspectionResult.decode(json("""
                {"tcp":{"state":"FAILED","reasonCode":"raw exception: private/path"},
                 "auth":{"state":"NOT_TESTED"},"hello":{"state":"NOT_TESTED"},
                 "status":{"state":"NOT_TESTED"}}
                """)));
    }

    @Test
    void preservesHelloDisconnectWithoutClaimingHelloOrStatusPass() throws Exception {
        P2pInspectionResult observation = P2pInspectionResult.decode(json("""
                {"tcp":{"state":"PASS","durationMs":12},
                 "auth":{"state":"PASS","durationMs":20},
                 "hello":{"state":"FAILED","reasonCode":"DISCONNECT_RECEIVED"},
                 "status":{"state":"NOT_TESTED","reasonCode":"HELLO_NOT_COMPLETED"},
                 "helloTrace":{"localHelloSent":true,
                   "framesDecoded":1,"lastMessageCode":1,
                   "disconnect":{"code":4,"name":"TOO_MANY_PEERS","description":"too many peers"}}}
                """));
        assertEquals(InspectionResult.State.FAILED, observation.hello().state());
        assertEquals(InspectionResult.State.NOT_TESTED, observation.status().state());
        assertEquals("TOO_MANY_PEERS", ((Map<?, ?>) observation.helloTrace().get("disconnect")).get("name"));
    }

    @Test
    void candidateApiPortsAreNotConfirmedEndpoints() {
        InspectionResult inspection = new InspectionResult("test", new NodeRecord("127.0.0.1", 30303, 9999, ""));
        inspection.setRpcProbeEndpoints(List.of("http://127.0.0.1:8545"));
        inspection.setBeaconProbeEndpoints(List.of("http://127.0.0.1:5052"));
        Map<String, Object> snapshot = inspection.snapshot();
        Map<?, ?> node = (Map<?, ?>) snapshot.get("node");
        assertNull(node.get("rpcEndpoint"));
        assertNull(node.get("beaconEndpoint"));
        assertEquals(List.of("http://127.0.0.1:8545"), snapshot.get("rpcProbeEndpoints"));
        assertEquals("127.0.0.1:9999", node.get("p2pEndpoint"));
    }

    private static byte[] json(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
