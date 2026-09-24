package io.github.gavinruff007.torchnode.inspection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Keeps observations separate from comparisons between independent interfaces. */
public final class NetworkVerification {
    private record Canonical(String name, long chainId, long networkId, Long genesisTime) { }

    private static final Map<String, Canonical> BEACON_GENESIS = Map.of(
            "0x4b363db94e286120d76eb905340fdd4e54bfe9f06bf33ff6cf5ad27f511bfe95",
            new Canonical("Ethereum Mainnet", 1, 1, 1606824023L),
            "0xd8ea171f3c94aea21ebc42a1ed61052acf3f9209c00e4efbaaddac09ed9b8078",
            new Canonical("Sepolia", 11155111, 11155111, null),
            "0x9143aa7c615a7f7115e2b6aac319c03529df8242ae705fba9df39b79c59fa8b1",
            new Canonical("Holesky", 17000, 17000, null),
            "0x212f13fc4df078b6cb7db228f1c8307566dcecf900867401a92023d7ba99cb5f",
            new Canonical("Hoodi", 560048, 560048, null));
    private static final Map<String, Canonical> EXECUTION_GENESIS = Map.of(
            "0xd4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3",
            new Canonical("Ethereum Mainnet", 1, 1, null),
            "0x25a5cc106eea7138acab33231d7160d69cb777ee0c2c553fcddf5138993e6dd9",
            new Canonical("Sepolia", 11155111, 11155111, null),
            "0xbbe312868b376a3001692a646dd2d7d1e4406380dfd86b98aa8a34d1557c971b",
            new Canonical("Hoodi", 560048, 560048, null));

    private NetworkVerification() { }

    public static Map<String, Object> from(Map<String, Object> rpc, Map<String, Object> beacon) {
        return from(rpc, beacon, null);
    }

    /** p2p is populated only from an authenticated ETH Status exchange, never discovery guesses. */
    public static Map<String, Object> from(Map<String, Object> rpc, Map<String, Object> beacon,
                                           Map<String, Object> p2p) {
        List<Map<String, Object>> rows = new ArrayList<>();
        Long chainId = number(rpc, "chainId");
        Long rpcNetworkId = number(rpc, "networkId");
        Long p2pNetworkId = number(p2p, "networkId");
        String p2pGenesis = string(p2p, "genesisHash");
        Canonical p2pIdentity = p2pGenesis == null ? null
                : EXECUTION_GENESIS.get(p2pGenesis.toLowerCase(Locale.ROOT));
        boolean inconsistentP2p = p2pIdentity != null && p2pNetworkId != null
                && p2pNetworkId != p2pIdentity.networkId();
        String root = string(beacon, "genesisValidatorsRoot");
        Long genesisTime = number(beacon, "genesisTime");
        Canonical beaconIdentity = root == null ? null : BEACON_GENESIS.get(root.toLowerCase(Locale.ROOT));
        boolean validBeaconIdentity = beaconIdentity != null &&
                (beaconIdentity.genesisTime() == null || genesisTime == null
                        || beaconIdentity.genesisTime().equals(genesisTime));

        if (chainId != null) rows.add(row("RPC chain ID", chainId, "RPC", "OBSERVED", null));
        if (rpcNetworkId != null) rows.add(row("RPC network ID", rpcNetworkId, "RPC", "OBSERVED", null));
        rows.add(row("P2P network ID", p2pNetworkId, "ETH Status",
                p2pNetworkId == null ? "NOT_TESTED" : "OBSERVED", null));
        if (p2pGenesis != null) rows.add(row("P2P genesis hash", p2pGenesis, "ETH Status", "OBSERVED",
                p2pIdentity == null ? "Unknown execution genesis" : inconsistentP2p
                        ? p2pIdentity.name() + " genesis conflicts with P2P network ID"
                        : p2pIdentity.name()));
        if (root != null) rows.add(row("Beacon genesis", root, "Beacon", "OBSERVED",
                beaconIdentity == null ? "Unknown genesis root" : validBeaconIdentity
                        ? beaconIdentity.name() : "Genesis time differs from known " + beaconIdentity.name()));
        if (genesisTime != null) rows.add(row("Beacon genesis time", genesisTime, "Beacon", "OBSERVED", null));

        int comparisons = 0;
        boolean mismatch = beaconIdentity != null && !validBeaconIdentity;
        if (mismatch) {
            rows.add(row("Beacon genesis time ↔ canonical", genesisTime + " ↔ "
                    + beaconIdentity.genesisTime(), "Comparison", "MISMATCH", null));
        }
        if (rpcNetworkId != null && p2pNetworkId != null) {
            mismatch |= compare(rows, "RPC ↔ P2P network ID", rpcNetworkId, p2pNetworkId);
            comparisons++;
        }
        if (p2pIdentity != null) {
            if (chainId != null) {
                mismatch |= compare(rows, "RPC chain ID ↔ P2P genesis", chainId, p2pIdentity.chainId());
                comparisons++;
            }
            if (validBeaconIdentity) {
                mismatch |= compare(rows, "P2P genesis ↔ Beacon", p2pIdentity.chainId(),
                        beaconIdentity.chainId());
                comparisons++;
            }
        }
        if (validBeaconIdentity) {
            if (chainId != null) {
                mismatch |= compare(rows, "RPC chain ID ↔ Beacon", chainId, beaconIdentity.chainId());
                comparisons++;
            }
            if (rpcNetworkId != null) {
                mismatch |= compare(rows, "RPC network ID ↔ Beacon", rpcNetworkId,
                        beaconIdentity.networkId());
                comparisons++;
            }
            if (p2pNetworkId != null) {
                mismatch |= compare(rows, "P2P network ID ↔ Beacon", p2pNetworkId,
                        beaconIdentity.networkId());
                comparisons++;
            }
        }
        // Chain ID and network ID from one RPC endpoint are not independent evidence,
        // and the ETH specification explicitly permits their values to differ.
        String status = mismatch ? "MISMATCH" : inconsistentP2p ? "OBSERVED" : comparisons > 0 ? "VERIFIED"
                : chainId != null || rpcNetworkId != null || p2pNetworkId != null || root != null
                ? "OBSERVED" : "UNAVAILABLE";
        String network = validBeaconIdentity ? beaconIdentity.name()
                : p2pIdentity != null ? p2pIdentity.name()
                : chainId != null && rpc != null ? string(rpc, "network") : null;
        if (mismatch || inconsistentP2p) network = null;
        if (!"UNAVAILABLE".equals(status)) {
            rows.add(row("Network", network, "Derived", status, null));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows);
        result.put("warning", mismatch || inconsistentP2p);
        result.put("status", status);
        result.put("network", network);
        return result;
    }

    private static boolean compare(List<Map<String, Object>> rows, String label, long left, long right) {
        boolean match = left == right;
        rows.add(row(label, left + " ↔ " + right, "Comparison", match ? "MATCH" : "MISMATCH", null));
        return !match;
    }

    private static Long number(Map<String, Object> map, String key) {
        if (map == null || map.get(key) == null) return null;
        Object value = map.get(key);
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(value.toString()); }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String string(Map<String, Object> map, String key) {
        return map == null || map.get(key) == null ? null : map.get(key).toString();
    }

    private static Map<String, Object> row(String label, Object value, String source,
                                           String status, String note) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("value", value);
        row.put("source", source);
        row.put("status", status);
        row.put("note", note);
        return row;
    }
}
