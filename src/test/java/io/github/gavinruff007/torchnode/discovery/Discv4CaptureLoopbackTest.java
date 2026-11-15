package io.github.gavinruff007.torchnode.discovery;

import io.github.gavinruff007.torchnode.enr.Discv4Packet;
import io.github.gavinruff007.torchnode.model.BondState;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.ECKeyPair;
import org.web3j.rlp.*;
import org.web3j.utils.Numeric;
import java.math.BigInteger;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class Discv4CaptureLoopbackTest {
    @Test void realSendReceivePathCapturesSignedReporterAndRejectsTamperedReplay() throws Exception {
        var local = new LocalNodeIdentity(ECKeyPair.create(BigInteger.ONE));
        var responderKey = ECKeyPair.create(BigInteger.TWO);
        String responderId = Numeric.toHexStringNoPrefixZeroPadded(responderKey.getPublicKey(), 128);
        byte[] targetKey = Numeric.toBytesPadded(ECKeyPair.create(BigInteger.valueOf(3)).getPublicKey(), 64);
        var run = new ObserverInstance.Run(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), local.getNodeId(),
                ObserverInstance.KeyLifecycle.EPHEMERAL_FIRST_SEEN);
        var capture = new Discv4EvidenceCapture(run);
        var nodes = new ConcurrentHashMap<String, DiscoveredNode>();
        var observations = new CopyOnWriteArrayList<DiscoveredNode>();
        try (var peer = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
             var socket = new DatagramSocket(new InetSocketAddress("127.0.0.1", 0))) {
            peer.setSoTimeout(2000);
            Thread listener = P2PListener.startListening(socket, local, nodes, new ConcurrentHashMap<String, BondState>(),
                    (node, endpoint) -> observations.add(node), capture);
            try {
                FindNodeSender.sendFindNode(local, "127.0.0.1", peer.getLocalPort(), socket, local.getPublicKey(), capture, responderId);
                byte[] buffer = new byte[1281]; var request = new DatagramPacket(buffer, buffer.length); peer.receive(request);
                var decoded = Discv4Packet.decode(Arrays.copyOf(buffer, request.getLength()));
                assertEquals(3, decoded.type()); assertEquals(local.getNodeId(), decoded.signer().nodeId());
                var descriptor = new RlpList(RlpString.create(new byte[]{127, 0, 0, 1}), RlpString.create(30301),
                        RlpString.create(30305), RlpString.create(targetKey));
                byte[] wire = Discv4Packet.encode(4, RlpEncoder.encode(new RlpList(new RlpList(descriptor),
                        RlpString.create(Instant.now().getEpochSecond() + 60))), responderKey);
                byte[] tampered = wire.clone(); tampered[0] ^= 1;
                for (byte[] raw : List.of(tampered, wire, wire))
                    peer.send(new DatagramPacket(raw, raw.length, request.getSocketAddress()));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (capture.snapshot().responses().size() < 3 && System.nanoTime() < deadline) Thread.sleep(5);
                var snapshot = capture.snapshot();
                assertEquals(3, snapshot.responses().size());
                assertEquals(1, snapshot.reports().size());
                assertEquals(responderId, snapshot.reports().getFirst().sourceResponderIdentity());
                assertEquals(run, snapshot.reports().getFirst().observer());
                assertEquals(Discv4EvidenceCapture.Correlation.LOCAL_CONTEXT_ONLY_NO_WIRE_TOKEN, snapshot.reports().getFirst().correlation());
                assertFalse(snapshot.reports().getFirst().graphEligible());
                assertEquals("INVALID_PACKET", snapshot.responses().getFirst().validation());
                assertEquals("DUPLICATE_RESPONSE", snapshot.responses().getLast().validation());
            } finally { socket.close(); listener.join(2000); }
            assertFalse(listener.isAlive());
            assertEquals(1, observations.size()); assertEquals(1, nodes.size());
            assertArrayEquals(targetKey, observations.getFirst().nodeId);
        }
    }
}
