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
    public NodeEndpoint endpoint(NodeEndpoint.Purpose purpose, NodeEndpoint.Transport transport) {
        return endpoints.stream().filter(e -> e.purpose() == purpose && e.transport() == transport)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing " + purpose + " endpoint"));
    }
}
