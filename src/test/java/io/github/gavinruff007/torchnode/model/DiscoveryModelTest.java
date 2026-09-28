package io.github.gavinruff007.torchnode.model;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryModelTest {
    @Test void identityDoesNotChangeWithNetworkLocationAndEndpointsAreImmutable() {
        var identity = new NodeIdentity("0x" + "AB".repeat(64));
        assertEquals(new NodeIdentity("ab".repeat(64)), identity);
        var endpoints = new ArrayList<>(List.of(new NodeEndpoint("2001:db8::1", NodeEndpoint.Transport.TCP,
                30305, NodeEndpoint.AddressFamily.IPV6, NodeEndpoint.Purpose.P2P)));
        var first = new DiscoveryObservation(identity, "source-a", endpoints, Instant.now(), "advertisement");
        endpoints.clear();
        var second = new DiscoveryObservation(identity, "source-b", List.of(new NodeEndpoint("192.0.2.1",
                NodeEndpoint.Transport.TCP, 30303, NodeEndpoint.AddressFamily.IPV4, NodeEndpoint.Purpose.P2P)),
                Instant.now(), "independent advertisement");
        assertEquals(first.identity(), second.identity());
        assertEquals(NodeEndpoint.AddressFamily.IPV6, first.endpoints().get(0).addressFamily());
        assertNotEquals(first.endpoints(), second.endpoints());
        assertThrows(UnsupportedOperationException.class, () -> first.endpoints().clear());
    }
}
