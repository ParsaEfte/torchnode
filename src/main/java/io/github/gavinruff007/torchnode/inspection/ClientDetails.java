package io.github.gavinruff007.torchnode.inspection;

import java.util.LinkedHashMap;
import java.util.Map;

public record ClientDetails(String name, String version, String platform, String runtime, String raw) {
    public static ClientDetails parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split("/");
        String name = parts.length > 0 ? normalizeName(parts[0]) : null;
        String version = parts.length > 1 ? parts[1].replaceFirst("^[vV]", "") : null;
        String platform = parts.length > 2 ? parts[2] : null;
        String runtime = parts.length > 3 ? parts[3] : null;
        return new ClientDetails(name, version, platform, runtime, raw);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name);
        values.put("version", version);
        values.put("platform", platform);
        values.put("runtime", runtime);
        values.put("raw", raw);
        return values;
    }

    private static String normalizeName(String value) {
        String lower = value.toLowerCase();
        if (lower.contains("geth")) return "Geth";
        if (lower.contains("nethermind")) return "Nethermind";
        if (lower.contains("besu")) return "Besu";
        if (lower.contains("erigon")) return "Erigon";
        if (lower.contains("reth")) return "Reth";
        return value;
    }
}
