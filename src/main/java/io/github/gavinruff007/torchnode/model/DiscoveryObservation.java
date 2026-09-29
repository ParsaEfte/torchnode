package io.github.gavinruff007.torchnode.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable source evidence, never a statement of reachability or verification. */
public record DiscoveryObservation(NodeIdentity identity, String source, List<NodeEndpoint> endpoints,
                                   Instant observedAt, String provenance) {
    public DiscoveryObservation {
        Objects.requireNonNull(identity);
        Objects.requireNonNull(source);
        endpoints = List.copyOf(endpoints);
        Objects.requireNonNull(observedAt);
        Objects.requireNonNull(provenance);
    }
    public java.util.Map<String,Object> toMap() {
        return java.util.Map.of("identity", identity.nodeId(), "source", source, "endpoints", endpoints,
                "observedAt", observedAt.toString(), "provenance", provenance);
    }
    public NodeEndpoint endpoint(NodeEndpoint.Purpose purpose, NodeEndpoint.Transport transport) {
        return endpoints.stream().filter(e -> e.purpose() == purpose && e.transport() == transport)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing " + purpose + " endpoint"));
    }
}
