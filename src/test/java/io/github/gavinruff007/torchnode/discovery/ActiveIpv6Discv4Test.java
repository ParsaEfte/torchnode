package io.github.gavinruff007.torchnode.discovery;

import io.github.gavinruff007.torchnode.enr.*;
import io.github.gavinruff007.torchnode.model.*;
import org.junit.jupiter.api.Test;
import org.web3j.rlp.*;
import org.web3j.utils.Numeric;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ActiveIpv6Discv4Test {
    @Test void ipv6BondFindnodeNeighborsAndShutdown() throws Exception {
        try (var peer = new DatagramSocket(new InetSocketAddress("::1", 0));
             var socket = new DatagramSocket(new InetSocketAddress("::", 0))) {
            var found = new CountDownLatch(1);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread responder = new Thread(() -> {
                try {
                    while (!peer.isClosed()) {
                        byte[] bytes = new byte[1281]; var datagram = new DatagramPacket(bytes, bytes.length); peer.receive(datagram);
                        var packet = Discv4Packet.decode(Arrays.copyOf(bytes, datagram.getLength()));
                        long expires = Instant.now().getEpochSecond() + 60;
                        var endpoint = new RlpList(RlpString.create(datagram.getAddress().getAddress()), RlpString.create(datagram.getPort()), RlpString.create(0));
                        if (packet.type() == 1) {
                            send(peer, datagram, 2, new RlpList(endpoint, RlpString.create(packet.hash()), RlpString.create(expires)));
                            send(peer, datagram, 1, new RlpList(RlpString.create(4), endpoint, endpoint, RlpString.create(expires)));
                        } else if (packet.type() == 3) {
                            var neighbor = new RlpList(RlpString.create(InetAddress.getByName("::1").getAddress()),
                                RlpString.create(peer.getLocalPort()), RlpString.create(30305), RlpString.create(Numeric.hexStringToByteArray(EnrFixtures.ID.nodeId())));
                            send(peer, datagram, 4, new RlpList(new RlpList(neighbor), RlpString.create(expires)));
                            found.countDown();
                        }
                    }
                } catch (Throwable e) { if (!peer.isClosed()) failure.set(e); }
            }, "discv4-ipv6-fixture");
            responder.start();
            var provider = new Discv4DiscoveryProvider(new LocalNodeIdentity(), socket, new String[]{"[::1]:" + peer.getLocalPort()});
            try {
                provider.start(); assertTrue(found.await(2, TimeUnit.SECONDS));
                var observations = new CopyOnWriteArrayList<DiscoveryObservation>();
                Thread crawler = new Thread(() -> {
                    try { provider.discover(observations::add); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }, "discv4-ipv6-crawl-fixture");
                crawler.start();
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (observations.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
                } finally { provider.close(); crawler.interrupt(); crawler.join(1000); }
                assertFalse(crawler.isAlive());
                provider.discover(observations::add);
                assertFalse(observations.isEmpty());
                assertEquals(EnrFixtures.ID, observations.get(0).identity());
                assertTrue(observations.get(0).endpoints().stream().allMatch(e -> e.addressFamily() == NodeEndpoint.AddressFamily.IPV6));
                assertEquals(30305, new NodeRecord(observations.get(0)).getP2pEndpoint().port());
                assertTrue(observations.get(0).provenance().contains("["));
            } finally { provider.close(); peer.close(); responder.join(1000); }
            assertFalse(responder.isAlive()); assertNull(failure.get()); assertTrue(socket.isClosed());
        }
    }
    private static void send(DatagramSocket socket, DatagramPacket target, int type, RlpList payload) throws Exception {
        byte[] bytes = Discv4Packet.encode(type, RlpEncoder.encode(payload), EnrFixtures.KEY);
        socket.send(new DatagramPacket(bytes, bytes.length, target.getSocketAddress()));
    }
}
