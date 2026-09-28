package io.github.gavinruff007.torchnode.discovery;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import org.junit.jupiter.api.Test;
import org.web3j.rlp.*;
import java.net.DatagramSocket;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryProviderTest {
    @Test void translatesActualNeighborsEntriesThroughProviderWithoutCollapsingConflicts() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            var discv4 = new Discv4DiscoveryProvider(new LocalNodeIdentity(), socket, new String[0]);
            DiscoveryProvider provider = discv4;
            assertEquals("discv4", provider.protocol());
            var nodes = new ConcurrentHashMap<String, DiscoveredNode>();
            byte[] id = new byte[64]; java.util.Arrays.fill(id, (byte) 0xab);
            for (int tcp : new int[]{30305, 30303}) {
                byte[] neighbors = RlpEncoder.encode(new RlpList(new RlpList(new RlpList(
                        RlpString.create(new byte[]{(byte)192, 0, 2, 1}), RlpString.create(30301),
                        RlpString.create(tcp), RlpString.create(id))), RlpString.create(2000000000L)));
                P2PListener.handleNeighbors(neighbors, nodes, discv4::observe, "192.0.2.9:30303");
            }
            // Closing drains received evidence without sending network traffic.
            provider.close();
            var observations = provider.discover();
            assertEquals(2, observations.size());
            assertEquals(observations.get(0).identity(), observations.get(1).identity());
            assertEquals("NEIGHBORS from 192.0.2.9:30303", observations.get(0).provenance());
            assertEquals(30301, new NodeRecord(observations.get(0)).getUdpPort());
            assertEquals(30305, new NodeRecord(observations.get(0)).getP2pEndpoint().port());
            assertEquals(30303, new NodeRecord(observations.get(1)).getP2pEndpoint().port());
            assertTrue(provider.discover().isEmpty());
        }
    }
    @Test void failedObservationDeliveryCanBeRetriedWithoutLosingEvidence() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            var provider = new Discv4DiscoveryProvider(new LocalNodeIdentity(), socket, new String[0]);
            byte[] id = new byte[64]; java.util.Arrays.fill(id, (byte)0xab);
            provider.observe(new DiscoveredNode("192.0.2.1", 30301, 30305, id, "ab".repeat(64)), "192.0.2.9:30303");
            provider.close();
            assertThrows(IllegalStateException.class, () -> provider.discover(o -> { throw new IllegalStateException("storage failed"); }));
            assertEquals(1, provider.discover().size());
            assertTrue(provider.discover().isEmpty());
        }
    }
}
