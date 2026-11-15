package io.github.gavinruff007.torchnode.discovery;

import io.github.gavinruff007.torchnode.enr.*;
import io.github.gavinruff007.torchnode.model.*;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import org.bouncycastle.util.encoders.Hex;
import java.net.*;
import java.time.*;
import java.util.*;

/** B2 diagnostic staging, NOT a durable topology store or trusted-edge producer. */
public final class Discv4EvidenceCapture {
    public static final int MAX_RESPONSES = 256, MAX_REQUESTS = 128, MAX_REPORTS = 1024;
    public static final Duration REQUEST_WINDOW = Duration.ofSeconds(10);
    public enum Correlation {
        LOCAL_CONTEXT_ONLY_NO_WIRE_TOKEN, AMBIGUOUS_LOCAL_REQUESTS, UNPINNED_LOCAL_REQUEST,
        RESPONDER_IDENTITY_MISMATCH, NO_RETAINED_REQUEST_CONTEXT
    }
    public record Request(String id, ObserverInstance.Run observer, Instant sentAt, String endpoint,
                          String expectedResponderIdentity, String lookupTargetIdentity, String packetHash,
                          String rawPacketHex, String sendOutcome) {}
    public record Descriptor(int index, String targetIdentity, String address, Integer udpPort,
                             Integer tcpPort, String validation) {}
    public record Response(String id, ObserverInstance.Run observer, Instant observedAt, String protocol,
                           String endpoint, String responderIdentity, String packetDigest, String rawPacketHex,
                           boolean rawTruncated, String packetDigestScope, String validation, Correlation correlation,
                           List<String> localRequestIds, String duplicateOf, List<Descriptor> descriptors,
                           List<String> diagnostics) {
        public Response { localRequestIds = List.copyOf(localRequestIds); descriptors = List.copyOf(descriptors); diagnostics = List.copyOf(diagnostics); }
        public boolean cryptographicRequestCorrelation() { return false; }
    }
    /** Valid signed report of a structurally valid curve-point identity; target remains an unverified claim. */
    public record Report(String sourceEvidenceId, int descriptorIndex, ObserverInstance.Run observer, String sourceResponderIdentity,
                         String targetIdentity, String responderEndpoint, String returnedAddress,
                         int returnedUdpPort, int returnedTcpPort, Instant observedAt,
                         Correlation correlation, List<String> localRequestIds) {
        public Report { localRequestIds = List.copyOf(localRequestIds); }
        public String protocol() { return "discv4"; }
        public String relationType() { return "REPORTED_NEIGHBOR"; }
        public String validation() { return "SIGNED_REPORT_REQUEST_UNBOUND_TARGET_UNVERIFIED"; }
        public boolean graphEligible() { return false; }
        public boolean cryptographicRequestCorrelation() { return false; }
    }
    public record Snapshot(List<Request> requests, List<Response> responses, List<Report> reports,
                           Map<String, Long> diagnosticCounts, long evictedRequests,
                           long evictedResponses, long evictedReports, boolean durable) {}
    public record Received(Discv4Packet.Packet packet, List<DiscoveredNode> nodes) {
        public Received { nodes = List.copyOf(nodes); }
    }
    @FunctionalInterface public interface PacketSender { void send() throws Exception; }
    private final ObserverInstance.Run observer;
    private final Clock clock;
    private final ArrayDeque<Request> requests = new ArrayDeque<>();
    private final ArrayDeque<Response> responses = new ArrayDeque<>();
    private final ArrayDeque<Report> reports = new ArrayDeque<>();
    private final LinkedHashMap<String, String> receivedHashes = new LinkedHashMap<>();
    private final Map<String, Long> diagnosticCounts = new TreeMap<>();
    private long evictedRequests, evictedResponses, evictedReports;
    public Discv4EvidenceCapture(ObserverInstance.Run observer) { this(observer, Clock.systemUTC()); }
    public Discv4EvidenceCapture(ObserverInstance.Run observer, Clock clock) { this.observer = observer; this.clock = Objects.requireNonNull(clock); }

    /** Registration and kernel send share the lock so a response cannot race request registration. */
    public synchronized void sendFindNode(byte[] packet, InetSocketAddress remote, String expectedResponder,
                                           byte[] lookupTarget, PacketSender sender) throws Exception {
        var decoded = Discv4Packet.decode(packet);
        if (decoded.type() != 3 || lookupTarget.length != 64) throw new IllegalArgumentException("Signed FINDNODE required");
        if (decoded.payload().children().size() < 2 ||
                !Arrays.equals(decoded.payload().children().getFirst().string(), lookupTarget))
            throw new IllegalArgumentException("Request metadata contradicts signed FINDNODE target");
        if (observer != null && !decoded.signer().nodeId().equals(observer.localDiscoveryIdentity()))
            throw new IllegalArgumentException("Outgoing discovery identity differs from observer run");
        String expected = expectedResponder == null ? null : NodeIds.normalize(expectedResponder);
        if (expected != null && expected.isEmpty()) throw new IllegalArgumentException("Expected responder must be a canonical identity, never an endpoint");
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        String outcome = "SENT";
        try { sender.send(); }
        catch (Exception e) { outcome = "SEND_FAILED"; count(outcome); throw e; }
        finally {
            if (requests.size() == MAX_REQUESTS) { requests.removeFirst(); evictedRequests++; count("REQUEST_CONTEXT_EVICTED"); }
            requests.addLast(new Request(id, observer, now, endpoint(remote), expected, Hex.toHexString(lookupTarget),
                    Hex.toHexString(decoded.hash()), Hex.toHexString(packet), outcome));
        }
    }
    /** Returns only verified, unexpired frames. Invalid inputs remain diagnostic raw evidence. */
    public synchronized Received receive(byte[] raw, InetSocketAddress remote) {
        Objects.requireNonNull(raw); Objects.requireNonNull(remote);
        if (raw.length > Discv4Packet.MAX_BYTES + 1) raw = Arrays.copyOf(raw, Discv4Packet.MAX_BYTES + 1);
        Instant now = clock.instant();
        String id = UUID.randomUUID().toString();
        Discv4Packet.Packet packet;
        try { packet = Discv4Packet.decode(raw); }
        catch (IllegalArgumentException e) {
            record(raw, remote, now, id, null, "INVALID_PACKET", Correlation.NO_RETAINED_REQUEST_CONTEXT,
                    List.of(), null, List.of(), List.of("HASH_SIGNATURE_OR_FRAMING_INVALID"));
            return null;
        }
        var fields = packet.payload().children();
        int expiryIndex = switch (packet.type()) { case 1 -> 3; case 2 -> 2; case 4 -> 1; default -> -1; };
        try {
            if (expiryIndex < 0) throw new IllegalArgumentException("Unsupported packet");
            long expires = fields.get(expiryIndex).uint(8).longValueExact();
            if (expires < now.getEpochSecond()) {
                record(raw, remote, now, id, packet.signer().nodeId(), "EXPIRED_PACKET", Correlation.NO_RETAINED_REQUEST_CONTEXT,
                        List.of(), null, List.of(), List.of("EXPIRED_PACKET"));
                return null;
            }
            if (packet.type() != 4) return new Received(packet, List.of());
            if (!fields.getFirst().list() || fields.getFirst().children().size() > 16)
                throw new IllegalArgumentException("Invalid NEIGHBORS list");
        } catch (RuntimeException e) {
            record(raw, remote, now, id, packet.signer().nodeId(), "INVALID_PAYLOAD", Correlation.NO_RETAINED_REQUEST_CONTEXT,
                    List.of(), null, List.of(), List.of("INVALID_PAYLOAD"));
            return null;
        }
        String signer = packet.signer().nodeId();
        String digest = Hex.toHexString(Hash.sha3(raw));
        String duplicate = receivedHashes.get(digest);
        if (duplicate == null) {
            if (receivedHashes.size() == 1024) { receivedHashes.remove(receivedHashes.keySet().iterator().next()); count("REPLAY_CACHE_EVICTED"); }
            receivedHashes.put(digest, id);
        }
        var contexts = requests.stream().filter(r -> r.sendOutcome().equals("SENT") && r.endpoint().equals(endpoint(remote))
                && !r.sentAt().isAfter(now) && r.sentAt().plus(REQUEST_WINDOW).isAfter(now)).toList();
        Correlation correlation = contexts.size() > 1 ? Correlation.AMBIGUOUS_LOCAL_REQUESTS
                : contexts.isEmpty() ? Correlation.NO_RETAINED_REQUEST_CONTEXT
                : contexts.getFirst().expectedResponderIdentity() == null ? Correlation.UNPINNED_LOCAL_REQUEST
                : !signer.equals(contexts.getFirst().expectedResponderIdentity()) ? Correlation.RESPONDER_IDENTITY_MISMATCH
                : Correlation.LOCAL_CONTEXT_ONLY_NO_WIRE_TOKEN;
        List<String> requestIds = contexts.stream().map(Request::id).toList();
        var diagnostics = new ArrayList<String>();
        diagnostics.add(correlation.name());
        diagnostics.add("NO_CRYPTOGRAPHIC_REQUEST_BINDING");
        if (observer == null || observer.localDiscoveryIdentity() == null) diagnostics.add("MISSING_OBSERVER_CONTEXT");
        if (duplicate != null) diagnostics.add("DUPLICATE_RESPONSE");
        var descriptors = new ArrayList<Descriptor>();
        var nodes = new ArrayList<DiscoveredNode>();
        var descriptorKeys = new HashSet<String>();
        int index = 0;
        for (var value : fields.getFirst().children()) {
            Descriptor descriptor = descriptor(index++, value);
            descriptors.add(descriptor);
            if (!descriptor.validation().equals("VALID_CURVE_POINT_UNVERIFIED_CLAIM")) { diagnostics.add("INVALID_RETURNED_DESCRIPTOR"); continue; }
            if (!descriptorKeys.add(descriptor.targetIdentity() + "|" + descriptor.address() + "|" + descriptor.udpPort() + "|" + descriptor.tcpPort())) {
                diagnostics.add("DUPLICATE_DESCRIPTOR"); continue;
            }
            if (duplicate != null) continue;
            byte[] key = Hex.decode(descriptor.targetIdentity());
            nodes.add(new DiscoveredNode(descriptor.address(), descriptor.udpPort(), descriptor.tcpPort(), key, descriptor.targetIdentity()));
            // Candidates are retained separately from raw evidence; no promotion even for one local request.
            if (observer != null && observer.localDiscoveryIdentity() != null) {
                if (reports.size() == MAX_REPORTS) { reports.removeFirst(); evictedReports++; count("REPORT_CANDIDATE_EVICTED"); }
                reports.addLast(new Report(id, descriptor.index(), observer, signer, descriptor.targetIdentity(), endpoint(remote), descriptor.address(),
                        descriptor.udpPort(), descriptor.tcpPort(), now, correlation, requestIds));
            }
        }
        record(raw, remote, now, id, signer, duplicate == null ? "VALID_SIGNED_REPORT" : "DUPLICATE_RESPONSE",
                correlation, requestIds, duplicate, descriptors, diagnostics);
        return new Received(packet, nodes);
    }
    private static Descriptor descriptor(int index, CanonicalRlp.Value value) {
        String target = null, address = null;
        Integer udp = null, tcp = null;
        try {
            if (!value.list() || value.children().size() < 4) throw new IllegalArgumentException();
            var fields = value.children();
            byte[] ip = fields.get(0).string();
            if (ip.length != 4 && ip.length != 16) throw new IllegalArgumentException();
            var parsed = InetAddress.getByAddress(ip);
            address = parsed.getHostAddress();
            if (parsed.isAnyLocalAddress() || parsed.isMulticastAddress()) throw new IllegalArgumentException();
            udp = fields.get(1).uint(2).intValueExact();
            tcp = fields.get(2).uint(2).intValueExact();
            if (udp == 0) throw new IllegalArgumentException();
            byte[] key = fields.get(3).string();
            if (key.length != 64) throw new IllegalArgumentException();
            byte[] encoded = new byte[65]; encoded[0] = 4; System.arraycopy(key, 0, encoded, 1, 64);
            var point = Sign.CURVE_PARAMS.getCurve().decodePoint(encoded);
            if (point.isInfinity() || !point.isValid()) throw new IllegalArgumentException();
            target = Hex.toHexString(key);
            return new Descriptor(index, target, address, udp, tcp, "VALID_CURVE_POINT_UNVERIFIED_CLAIM");
        } catch (Exception e) { return new Descriptor(index, target, address, udp, tcp, "INVALID_RETURNED_DESCRIPTOR"); }
    }
    private void record(byte[] raw, InetSocketAddress remote, Instant now, String id, String signer, String validation,
                        Correlation correlation, List<String> requests, String duplicate, List<Descriptor> descriptors, List<String> diagnostics) {
        if (responses.size() == MAX_RESPONSES) {
            String evictedId = responses.removeFirst().id(); evictedResponses++; count("RAW_RESPONSE_EVICTED");
            for (var iterator = reports.iterator(); iterator.hasNext();) {
                if (iterator.next().sourceEvidenceId().equals(evictedId)) {
                    iterator.remove(); evictedReports++; count("REPORT_SOURCE_EVICTED");
                }
            }
        }
        count(validation);
        for (String code : diagnostics) if (!code.equals(validation)) count(code);
        byte[] retained = Arrays.copyOf(raw, Math.min(raw.length, Discv4Packet.MAX_BYTES));
        boolean truncated = raw.length > Discv4Packet.MAX_BYTES;
        responses.addLast(new Response(id, observer, now, "discv4", endpoint(remote), signer, Hex.toHexString(Hash.sha3(retained)),
                Hex.toHexString(retained), truncated, truncated ? "RETAINED_PREFIX_ONLY" : "COMPLETE_DATAGRAM",
                validation, correlation, requests, duplicate, descriptors, diagnostics));
    }
    private void count(String code) { diagnosticCounts.merge(code, 1L, Long::sum); }
    private static String endpoint(InetSocketAddress address) {
        if (address.isUnresolved() || address.getPort() == 0) throw new IllegalArgumentException("Resolved UDP endpoint required");
        return EndpointAddress.hostPort(address.getAddress().getHostAddress(), address.getPort());
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(List.copyOf(requests), List.copyOf(responses), List.copyOf(reports), Map.copyOf(diagnosticCounts),
                evictedRequests, evictedResponses, evictedReports, false);
    }
}
