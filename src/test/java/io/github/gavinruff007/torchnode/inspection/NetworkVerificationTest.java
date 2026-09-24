package io.github.gavinruff007.torchnode.inspection;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NetworkVerificationTest {
    @Test
    void oneRpcSourceIsObservedEvenIfItsChainAndNetworkIdsDiffer() {
        Map<String, Object> result = NetworkVerification.from(
                Map.of("chainId", 1L, "networkId", "11155111", "network", "Ethereum Mainnet"), null);
        assertEquals("OBSERVED", result.get("status"));
        assertFalse((Boolean) result.get("warning"));
        assertEquals("OBSERVED", row(result, "RPC chain ID").get("status"));
        assertEquals("OBSERVED", row(result, "RPC network ID").get("status"));
    }

    @Test
    void verifiesHoodiOnlyAcrossIndependentRpcAndBeaconSources() {
        Map<String, Object> result = NetworkVerification.from(
                Map.of("chainId", 560048L, "networkId", "560048", "network", "Hoodi"),
                hoodiBeacon());
        assertEquals("VERIFIED", result.get("status"));
        assertEquals("Hoodi", result.get("network"));
        assertEquals("OBSERVED", row(result, "RPC chain ID").get("status"));
        assertEquals("MATCH", row(result, "RPC chain ID ↔ Beacon").get("status"));
    }

    @Test
    void preservesRawValuesAndFlagsIndependentSourceDisagreement() {
        Map<String, Object> result = NetworkVerification.from(
                Map.of("chainId", 1L, "networkId", "1"), hoodiBeacon(),
                Map.of("networkId", 560048L));
        assertEquals("MISMATCH", result.get("status"));
        assertEquals(null, result.get("network"));
        assertEquals("MISMATCH", row(result, "RPC ↔ P2P network ID").get("status"));
        assertEquals("MATCH", row(result, "P2P network ID ↔ Beacon").get("status"));
        assertEquals(560048L, row(result, "P2P network ID").get("value"));
    }

    @Test
    void warnsWhenKnownBeaconRootHasWrongGenesisTime() {
        Map<String, Object> result = NetworkVerification.from(null,
                Map.of("genesisValidatorsRoot",
                        "0x4b363db94e286120d76eb905340fdd4e54bfe9f06bf33ff6cf5ad27f511bfe95",
                        "genesisTime", 123L));
        assertEquals("MISMATCH", result.get("status"));
        assertEquals("OBSERVED", row(result, "Beacon genesis").get("status"));
    }

    @Test
    void p2pGenesisAndRpcAreIndependentEvidenceButP2pAloneIsObserved() {
        Map<String, Object> p2p = Map.of("networkId", 560048L,
                "genesisHash", "0xbbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b");
        assertEquals("OBSERVED", NetworkVerification.from(null, null, p2p).get("status"));
        Map<String, Object> verified = NetworkVerification.from(
                Map.of("chainId", 560048L, "networkId", "560048"), null, p2p);
        assertEquals("VERIFIED", verified.get("status"));
        assertEquals("MATCH", row(verified, "RPC chain ID ↔ P2P genesis").get("status"));
    }

    @Test
    void conflictingFieldsWithinOneP2pStatusNeverVerifyNetwork() {
        Map<String, Object> result = NetworkVerification.from(null, null,
                Map.of("networkId", 1L,
                        "genesisHash", "0xbbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b"));
        assertEquals("OBSERVED", result.get("status"));
        assertEquals(null, result.get("network"));
        assertEquals(true, result.get("warning"));
    }

    private static Map<String, Object> hoodiBeacon() {
        return Map.of("genesisValidatorsRoot",
                "0x212f13fc4df078b6cb7db228f1c8307566dcecf900867401a92023d7ba99cb5f",
                "genesisTime", 1742213400L);
    }

    private static Map<?, ?> row(Map<String, Object> result, String label) {
        return ((List<?>) result.get("rows")).stream().map(value -> (Map<?, ?>) value)
                .filter(value -> label.equals(value.get("label"))).findFirst().orElseThrow();
    }
}
