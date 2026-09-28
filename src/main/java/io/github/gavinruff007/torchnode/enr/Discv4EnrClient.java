package io.github.gavinruff007.torchnode.enr;

import io.github.gavinruff007.torchnode.model.*;
import org.web3j.crypto.*;
import org.web3j.rlp.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded IPv4-only EIP-868 exchange on isolated sockets, independent from scanner packet reception. */
public final class Discv4EnrClient implements AutoCloseable {
    public static final Duration TIMEOUT = Duration.ofSeconds(6);
    private final ECKeyPair key;
    private final Duration timeout;
    private final Set<DatagramSocket> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    public Discv4EnrClient() {
        this(newKey(), TIMEOUT);
    }
    public Discv4EnrClient(ECKeyPair key, Duration timeout) {
        this.key = key; this.timeout = timeout;
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Positive timeout required");
    }
    private static ECKeyPair newKey() {
        try { return Keys.createEcKeyPair(); } catch (Exception e) { throw new IllegalStateException("Cannot create ENR acquisition identity", e); }
    }
    public EnrEvidence fetch(NodeRecord node) { return fetch(node, () -> false); }
    EnrEvidence fetch(NodeRecord node, java.util.function.BooleanSupplier cancelled) {
        long startedGeneration = generation.get();
        String provenance = "discv4 ENRRequest/ENRResponse at " + node.getIp() + ":" + node.getUdpPort();
        if (closed || cancelled.getAsBoolean() || startedGeneration != generation.get() || Thread.currentThread().isInterrupted()) return failure(node, provenance, "CANCELLED", "ENR acquisition cancelled");
        // No DNS resolution and no IPv6 network activity, including for legacy/manual records.
        if (!node.getIp().matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}") || node.getUdpPort() <= 0 || !node.identity().available())
            return failure(node, provenance, "NO_DISCOVERY_ENDPOINT", "An IPv4 discovery endpoint and public-key identity are required");
        DatagramSocket socket = null;
        boolean pong = false, requestSent = false;
        String rejected = null;
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            String[] octets = node.getIp().split("\\.");
            byte[] ipv4 = new byte[4];
            for (int i = 0; i < 4; i++) {
                int value = Integer.parseInt(octets[i]);
                if (value > 255) return failure(node, provenance, "NO_IPV4_ENDPOINT", "Invalid IPv4 address");
                ipv4[i] = (byte)value;
            }
            InetAddress address = InetAddress.getByAddress(ipv4);
            if (!(address instanceof Inet4Address)) return failure(node, provenance, "NO_IPV4_ENDPOINT", "IPv6 acquisition is disabled");
            InetSocketAddress remote = new InetSocketAddress(address, node.getUdpPort());
            socket = new DatagramSocket(new InetSocketAddress(InetAddress.getByAddress(new byte[4]), 0));
            active.add(socket);
            if (closed || cancelled.getAsBoolean() || startedGeneration != generation.get()) return failure(node, provenance, "CANCELLED", "ENR acquisition cancelled");
            long expiration = Instant.now().getEpochSecond() + 20;
            RlpList from = endpoint(new byte[4], socket.getLocalPort());
            RlpList to = endpoint(address.getAddress(), node.getUdpPort());
            byte[] ping = Discv4Packet.encode(1, RlpEncoder.encode(new RlpList(RlpString.create(4), from, to, RlpString.create(expiration))), key);
            byte[] request = Discv4Packet.encode(5, RlpEncoder.encode(new RlpList(RlpString.create(expiration))), key);
            send(socket, remote, ping);
            byte[] pingHash = Arrays.copyOf(ping, 32), requestHash = Arrays.copyOf(request, 32);
            long requestAt = Long.MAX_VALUE;
            while (System.nanoTime() < deadline) {
                if (closed || cancelled.getAsBoolean() || startedGeneration != generation.get() || Thread.currentThread().isInterrupted()) return failure(node, provenance, "CANCELLED", "ENR acquisition cancelled");
                if (pong && !requestSent && System.nanoTime() >= requestAt) {
                    send(socket, remote, request); requestSent = true;
                }
                socket.setSoTimeout((int)Math.max(1, Math.min(100, (deadline - System.nanoTime()) / 1_000_000)));
                byte[] buffer = new byte[Discv4Packet.MAX_BYTES + 1];
                DatagramPacket datagram = new DatagramPacket(buffer, buffer.length);
                try { socket.receive(datagram); } catch (SocketTimeoutException e) { continue; }
                if (!datagram.getAddress().equals(address) || datagram.getPort() != node.getUdpPort()) continue;
                Discv4Packet.Packet packet;
                try { packet = Discv4Packet.decode(Arrays.copyOf(buffer, datagram.getLength())); }
                catch (IllegalArgumentException e) { rejected = "MALFORMED_RESPONSE: " + e.getMessage(); continue; }
                if (!packet.signer().equals(node.identity())) { rejected = "RESPONSE_SIGNER_MISMATCH"; continue; }
                List<CanonicalRlp.Value> values = packet.payload().children();
                try {
                    if (packet.type() == 1) {
                        if (values.size() < 4 || expired(values.get(3))) continue;
                        byte[] reply = Discv4Packet.encode(2, RlpEncoder.encode(new RlpList(to, RlpString.create(packet.hash()), RlpString.create(expiration))), key);
                        send(socket, remote, reply);
                    } else if (packet.type() == 2) {
                        if (values.size() < 3 || expired(values.get(2)) || !Arrays.equals(values.get(1).string(), pingHash)) {
                            rejected = "UNSOLICITED_PONG"; continue;
                        }
                        if (!pong) { pong = true; requestAt = System.nanoTime() + 500_000_000L; }
                    } else if (packet.type() == 6) {
                        if (!requestSent || values.size() < 2 || !Arrays.equals(values.get(0).string(), requestHash)) {
                            rejected = "REQUEST_HASH_MISMATCH"; continue;
                        }
                        // The decoder also compares the record key with this authenticated packet signer.
                        return new EnrDecoder().decode(values.get(1).encoded(), node.identity(), Instant.now(), provenance);
                    }
                } catch (IllegalArgumentException e) { rejected = "MALFORMED_RESPONSE: " + e.getMessage(); }
            }
            String outcome = rejected != null ? rejected.split(":", 2)[0] : requestSent ? "REQUEST_TIMEOUT" : "BOND_TIMEOUT";
            return failure(node, provenance, outcome, rejected != null ? rejected : requestSent
                    ? "No ENRResponse received; timeout does not prove peer lacks ENR support" : "No authenticated matching Pong received");
        } catch (Exception e) {
            return failure(node, provenance, closed || cancelled.getAsBoolean() || startedGeneration != generation.get() || Thread.currentThread().isInterrupted() ? "CANCELLED" : "TRANSPORT_FAILURE", e.getMessage());
        } finally { if (socket != null) { active.remove(socket); socket.close(); } }
    }
    private static boolean expired(CanonicalRlp.Value value) { return value.uint(8).compareTo(java.math.BigInteger.valueOf(Instant.now().getEpochSecond())) < 0; }
    private static RlpList endpoint(byte[] ip, int udp) { return new RlpList(RlpString.create(ip), RlpString.create(udp), RlpString.create(0)); }
    private static void send(DatagramSocket socket, InetSocketAddress address, byte[] bytes) throws Exception {
        socket.send(new DatagramPacket(bytes, bytes.length, address));
    }
    private static EnrEvidence failure(NodeRecord node, String provenance, String outcome, String detail) {
        return EnrEvidence.unavailable(node.identity(), provenance, outcome, detail);
    }
    public void cancelPending() { generation.incrementAndGet(); active.forEach(DatagramSocket::close); }
    @Override public void close() { closed = true; cancelPending(); }
}
