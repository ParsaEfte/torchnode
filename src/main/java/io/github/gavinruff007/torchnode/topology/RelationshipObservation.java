package io.github.gavinruff007.torchnode.topology;

import io.github.gavinruff007.torchnode.model.NodeIds;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Contract for fully attributed evidence, NOT an inference from legacy provenance strings. */
public record RelationshipObservation(String sourceIdentity, String targetIdentity, String observerIdentity,
        Relation relationType, String protocol, Instant observedAt, EvidenceReference sourceEvidence,
        String provenance, Validation validationStatus) {
    public enum Relation { DISCOVERED_BY, REPORTED_NEIGHBOR, DIRECT_SESSION }
    public enum Validation { VALIDATED_DISCOVERY_RESPONSE, AUTHENTICATED_RLPX_SESSION }
    public record EvidenceReference(String domain,String occurrenceId) {
        public EvidenceReference {
            if(!Set.of("discovery","inspection").contains(Objects.requireNonNull(domain)) || occurrenceId==null || !occurrenceId.matches("[A-Za-z0-9_-]{1,128}"))
                throw new IllegalArgumentException("A bounded source occurrence reference is required");
        }
    }
    public RelationshipObservation {
        sourceIdentity=requiredIdentity(sourceIdentity);targetIdentity=requiredIdentity(targetIdentity);observerIdentity=requiredIdentity(observerIdentity);
        Objects.requireNonNull(relationType);Objects.requireNonNull(observedAt);Objects.requireNonNull(sourceEvidence);Objects.requireNonNull(validationStatus);
        if(sourceIdentity.equals(targetIdentity))throw new IllegalArgumentException("Self relationships are not session/neighbor evidence");
        if(provenance==null || provenance.isBlank() || provenance.length()>4096)throw new IllegalArgumentException("Bounded provenance is required");
        if(relationType==Relation.DIRECT_SESSION) {
            if(!"rlpx".equals(protocol) || validationStatus!=Validation.AUTHENTICATED_RLPX_SESSION || !sourceIdentity.equals(observerIdentity) || !sourceEvidence.domain().equals("inspection"))
                throw new IllegalArgumentException("A direct session requires its actual local identity and authenticated RLPx evidence");
        } else if(!Set.of("discv4","discv5").contains(Objects.requireNonNull(protocol)) || validationStatus!=Validation.VALIDATED_DISCOVERY_RESPONSE || !sourceEvidence.domain().equals("discovery") || relationType==Relation.DISCOVERED_BY && !targetIdentity.equals(observerIdentity))
            throw new IllegalArgumentException("Discovery attribution must retain the actual observer and validated response evidence");
    }
    public static String requiredIdentity(String value) {
        String normalized=NodeIds.normalize(value);
        if(normalized.isEmpty())throw new IllegalArgumentException("Canonical public-key identity required; endpoints and routing hashes are not substitutes");
        return normalized;
    }
}
