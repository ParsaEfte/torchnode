package io.github.gavinruff007.torchnode.enr;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import org.web3j.rlp.*;
import org.web3j.crypto.ECKeyPair;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Signed, deterministic EIP-868 peer fixture with endpoint-proof enforcement. */
public final class LoopbackEnrPeer implements AutoCloseable {
    public enum Mode { VALID, SILENT, NO_ENR, BAD_RECORD, BAD_SIGNATURE, MISLEADING_THEN_VALID, MALFORMED_PACKET }
    private final DatagramSocket socket;
    private final Thread thread;
    private final Mode mode;
    public final CountDownLatch pingReceived = new CountDownLatch(1);
    public final AtomicInteger requests = new AtomicInteger();
    private volatile Throwable failure;
    public LoopbackEnrPeer(Mode mode) throws Exception {
        this(mode, "127.0.0.1");
    }
    public LoopbackEnrPeer(Mode mode, String ip) throws Exception {
        this.mode = mode; socket = new DatagramSocket(new InetSocketAddress(ip, 0));
        thread = new Thread(this::run, "loopback-enr-peer"); thread.setDaemon(true); thread.start();
    }
    public NodeRecord node() { return new NodeRecord(socket.getLocalAddress().getHostAddress(), socket.getLocalPort(), 30305, EnrFixtures.ID.nodeId()); }
    private void run() {
        byte[] expectedPong = null;
        boolean bonded = false;
        try {
            while (!socket.isClosed()) {
                byte[] bytes = new byte[1281]; var datagram = new DatagramPacket(bytes, bytes.length); socket.receive(datagram);
                var packet = Discv4Packet.decode(Arrays.copyOf(bytes, datagram.getLength()));
                InetSocketAddress from = new InetSocketAddress(datagram.getAddress(), datagram.getPort());
                long expiration = Instant.now().getEpochSecond() + 20;
                var endpoint = new RlpList(RlpString.create(datagram.getAddress().getAddress()), RlpString.create(datagram.getPort()), RlpString.create(0));
                if (packet.type() == 1) {
                    pingReceived.countDown(); if (mode == Mode.SILENT) continue;
                    send(from, Discv4Packet.encode(2, RlpEncoder.encode(new RlpList(endpoint, RlpString.create(packet.hash()), RlpString.create(expiration), RlpString.create(7))), EnrFixtures.KEY));
                    byte[] ping = Discv4Packet.encode(1, RlpEncoder.encode(new RlpList(RlpString.create(4), endpoint, endpoint, RlpString.create(expiration))), EnrFixtures.KEY);
                    expectedPong = Arrays.copyOf(ping, 32); send(from, ping);
                    if (mode == Mode.MISLEADING_THEN_VALID) send(from, response(new byte[32], EnrFixtures.complete(7, 30303), EnrFixtures.KEY));
                } else if (packet.type() == 2) {
                    bonded = expectedPong != null && Arrays.equals(expectedPong, packet.payload().children().get(1).string());
                } else if (packet.type() == 5) {
                    requests.incrementAndGet();
                    if (!bonded) throw new AssertionError("ENR request before endpoint proof");
                    if (mode == Mode.NO_ENR) continue;
                    byte[] record = EnrFixtures.complete(7, 30303);
                    if (mode == Mode.BAD_SIGNATURE) record[5] ^= 1;
                    if (mode == Mode.BAD_RECORD) record = RlpEncoder.encode(RlpString.create("invalid"));
                    if (mode == Mode.MALFORMED_PACKET) {
                        byte[] bad = response(packet.hash(), record, EnrFixtures.KEY); bad[0] ^= 1; send(from, bad); continue;
                    }
                    if (mode == Mode.MISLEADING_THEN_VALID) {
                        send(from, response(new byte[32], record, EnrFixtures.KEY));
                        send(from, response(packet.hash(), record, ECKeyPair.create(java.math.BigInteger.TWO)));
                        byte[] bad = response(packet.hash(), record, EnrFixtures.KEY); bad[0] ^= 1; send(from, bad);
                    }
                    send(from, response(packet.hash(), record, EnrFixtures.KEY));
                }
            }
        } catch (Exception | AssertionError e) { if (!socket.isClosed()) failure = e; }
    }
    private static byte[] response(byte[] hash, byte[] raw, ECKeyPair key) {
        byte[] payload = CanonicalRlp.list(List.of(RlpEncoder.encode(RlpString.create(hash)), raw));
        return Discv4Packet.encode(6, payload, key);
    }
    private void send(InetSocketAddress target, byte[] bytes) throws Exception { socket.send(new DatagramPacket(bytes, bytes.length, target)); }
    @Override public void close() throws Exception {
        socket.close(); thread.join(1000);
        if (thread.isAlive()) throw new AssertionError("Peer listener leaked");
        if (failure != null) throw new AssertionError("Fixture protocol error", failure);
    }
}
