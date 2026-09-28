package io.github.gavinruff007.torchnode.enr;

import io.github.gavinruff007.torchnode.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;

/** ENR validation composed with neutral discovery evidence, never a mutable node property. */
public record EnrEvidence(NodeIdentity associatedIdentity, Instant observedAt, String provenance,
                          String outcome, String detail, boolean received, boolean decoded,
                          boolean structurallyValid, Signature signature, IdentityComparison identityComparison,
                          String rawRlpHex, EnrRecord record) {
    public enum Signature { VALID, INVALID, NOT_TESTED }
    public enum IdentityComparison { MATCH, MISMATCH, NOT_AVAILABLE }
    public boolean usable() {
        return structurallyValid && signature == Signature.VALID && identityComparison == IdentityComparison.MATCH;
    }
    public static Optional<EnrEvidence> latestValidated(Collection<EnrEvidence> evidence) {
        return evidence.stream().filter(EnrEvidence::usable)
                .max(Comparator.comparing((EnrEvidence e) -> new java.math.BigInteger(e.record().sequence()))
                        .thenComparing(EnrEvidence::observedAt));
    }
    public Optional<DiscoveryObservation> observation() {
        return usable() ? Optional.of(new DiscoveryObservation(record.identity(), "ENR", record.endpoints(), observedAt, provenance))
                : Optional.empty();
    }
    public static EnrEvidence unavailable(NodeIdentity identity, String provenance, String outcome, String detail) {
        return new EnrEvidence(identity, Instant.now(), provenance, outcome, detail, false, false, false,
                Signature.NOT_TESTED, IdentityComparison.NOT_AVAILABLE, null, null);
    }
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("source", "ENR"); map.put("associatedIdentity", associatedIdentity.nodeId());
        map.put("observedAt", observedAt.toString()); map.put("provenance", provenance);
        map.put("outcome", outcome); map.put("detail", detail); map.put("received", received);
        map.put("decoded", decoded); map.put("structurallyValid", structurallyValid);
        map.put("signature", signature.name()); map.put("identityComparison", identityComparison.name());
        map.put("rawRlpHex", rawRlpHex); map.put("record", record);
        return map;
    }
    public static EnrEvidence fromMap(Map<String, Object> map, ObjectMapper json) {
        return new EnrEvidence(new NodeIdentity((String)map.get("associatedIdentity")),
                Instant.parse((String)map.get("observedAt")), (String)map.get("provenance"),
                (String)map.get("outcome"), (String)map.get("detail"), Boolean.TRUE.equals(map.get("received")),
                Boolean.TRUE.equals(map.get("decoded")), Boolean.TRUE.equals(map.get("structurallyValid")),
                Signature.valueOf((String)map.get("signature")), IdentityComparison.valueOf((String)map.get("identityComparison")),
                (String)map.get("rawRlpHex"), map.get("record") == null ? null : json.convertValue(map.get("record"), EnrRecord.class));
    }
}
