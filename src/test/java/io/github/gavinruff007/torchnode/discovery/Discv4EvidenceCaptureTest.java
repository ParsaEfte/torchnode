package io.github.gavinruff007.torchnode.discovery;

import io.github.gavinruff007.torchnode.enr.Discv4Packet;
import org.junit.jupiter.api.Test;
import org.web3j.crypto.*;
import org.web3j.rlp.*;
import org.web3j.utils.Numeric;
import java.math.BigInteger;
import java.net.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Discv4EvidenceCaptureTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final ECKeyPair LOCAL = ECKeyPair.create(BigInteger.ONE);
    private static final ECKeyPair RESPONDER = ECKeyPair.create(BigInteger.TWO);
    private static final ECKeyPair TARGET = ECKeyPair.create(BigInteger.valueOf(3));
    private static final InetSocketAddress REMOTE = new InetSocketAddress("127.0.0.1", 30303);
    private static String id(ECKeyPair key) { return Numeric.toHexStringNoPrefixZeroPadded(key.getPublicKey(), 128); }
    private static byte[] key(ECKeyPair key) { return Numeric.toBytesPadded(key.getPublicKey(), 64); }
    private static ObserverInstance.Run run() {
        return new ObserverInstance.Run(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), NOW, id(LOCAL), ObserverInstance.KeyLifecycle.EPHEMERAL_FIRST_SEEN);
    }
    private static Discv4EvidenceCapture capture() { return new Discv4EvidenceCapture(run(), Clock.fixed(NOW, ZoneOffset.UTC)); }
    private static byte[] request() {
        return Discv4Packet.encode(3, RlpEncoder.encode(new RlpList(RlpString.create(key(LOCAL)), RlpString.create(NOW.getEpochSecond() + 60))), LOCAL);
    }
    private static void sent(Discv4EvidenceCapture capture, String expected) throws Exception {
        capture.sendFindNode(request(), REMOTE, expected, key(LOCAL), () -> {});
    }
    private static RlpList descriptor(byte[] key, int udp) {
        return new RlpList(RlpString.create(new byte[]{127, 0, 0, 1}), RlpString.create(udp), RlpString.create(30305), RlpString.create(key));
    }
    private static byte[] response(ECKeyPair signer, long expires, RlpList... descriptors) {
        return Discv4Packet.encode(4, RlpEncoder.encode(new RlpList(new RlpList(descriptors), RlpString.create(expires))), signer);
    }
    private static byte[] response() { return response(RESPONDER, NOW.getEpochSecond() + 60, descriptor(key(TARGET), 30301)); }

    @Test void signatureAttributesReporterAndClaimsButNotRequestOrConnectivity() throws Exception {
        var capture = capture(); sent(capture, id(RESPONDER));
        var received = capture.receive(response(), REMOTE);
        assertNotNull(received); assertEquals(1, received.nodes().size());
        var snapshot = capture.snapshot();
        var raw = snapshot.responses().getFirst(); var report = snapshot.reports().getFirst();
        assertEquals(id(RESPONDER), raw.responderIdentity());
        assertEquals(id(TARGET), report.targetIdentity());
        assertEquals(run(), raw.observer());
        assertEquals("discv4", raw.protocol());
        assertEquals("REPORTED_NEIGHBOR", report.relationType());
        assertEquals(NOW, report.observedAt());
        assertEquals(raw.id(), report.sourceEvidenceId());
        assertEquals(report.targetIdentity(), raw.descriptors().get(report.descriptorIndex()).targetIdentity());
        assertEquals("COMPLETE_DATAGRAM", raw.packetDigestScope());
        assertArrayEquals(response(), HexFormat.of().parseHex(raw.rawPacketHex()));
        assertEquals(snapshot.requests().getFirst().id(), raw.localRequestIds().getFirst());
        assertEquals(Discv4EvidenceCapture.Correlation.LOCAL_CONTEXT_ONLY_NO_WIRE_TOKEN, raw.correlation());
        assertFalse(raw.cryptographicRequestCorrelation()); assertFalse(report.graphEligible());
        assertFalse(snapshot.durable());
        assertTrue(raw.diagnostics().contains("NO_CRYPTOGRAPHIC_REQUEST_BINDING"));
        assertEquals("VALID_CURVE_POINT_UNVERIFIED_CLAIM", raw.descriptors().getFirst().validation());
    }
    @Test void ambiguityUnsolicitedUnpinnedAndMismatchAreDiagnosticNeverTrusted() throws Exception {
        for (int context : new int[]{0, 1, 2, 3}) {
            var capture = capture();
            if (context == 1) { sent(capture, id(RESPONDER)); sent(capture, id(RESPONDER)); }
            if (context == 2) sent(capture, null);
            if (context == 3) sent(capture, id(TARGET));
            capture.receive(response(), REMOTE);
            assertEquals(switch (context) {
                case 0 -> Discv4EvidenceCapture.Correlation.NO_RETAINED_REQUEST_CONTEXT;
                case 1 -> Discv4EvidenceCapture.Correlation.AMBIGUOUS_LOCAL_REQUESTS;
                case 2 -> Discv4EvidenceCapture.Correlation.UNPINNED_LOCAL_REQUEST;
                default -> Discv4EvidenceCapture.Correlation.RESPONDER_IDENTITY_MISMATCH;
            }, capture.snapshot().responses().getFirst().correlation());
            assertEquals(id(RESPONDER), capture.snapshot().responses().getFirst().responderIdentity());
            assertTrue(capture.snapshot().reports().stream().noneMatch(Discv4EvidenceCapture.Report::graphEligible));
        }
    }
    @Test void invalidHashAndInvalidSignatureRetainedWithoutInventedSource() {
        byte[] changed = response(); changed[0] ^= 1;
        byte[] signature = response();
        Arrays.fill(signature, 32, 96, (byte) 0);
        System.arraycopy(Hash.sha3(Arrays.copyOfRange(signature, 32, signature.length)), 0, signature, 0, 32);
        for (byte[] raw : List.of(changed, signature, new byte[10])) {
            var capture = capture(); assertNull(capture.receive(raw, REMOTE));
            var evidence = capture.snapshot().responses().getFirst();
            assertNull(evidence.responderIdentity());
            assertEquals("INVALID_PACKET", evidence.validation());
            assertTrue(capture.snapshot().reports().isEmpty());
            assertFalse(evidence.rawPacketHex().isEmpty());
        }
    }
    @Test void expiredMalformedAndOversizedResponsesRemainDiagnostics() {
        var capture = capture();
        assertNull(capture.receive(response(RESPONDER, NOW.minusSeconds(1).getEpochSecond(), descriptor(key(TARGET), 30301)), REMOTE));
        byte[] bad = Discv4Packet.encode(4, RlpEncoder.encode(new RlpList(RlpString.create("bad"))), RESPONDER);
        assertNull(capture.receive(bad, REMOTE));
        assertNull(capture.receive(new byte[65535], REMOTE));
        var raws = capture.snapshot().responses();
        assertEquals("EXPIRED_PACKET", raws.get(0).validation());
        assertEquals("INVALID_PAYLOAD", raws.get(1).validation());
        assertTrue(raws.get(2).rawTruncated()); assertEquals(2560, raws.get(2).rawPacketHex().length());
        assertEquals("RETAINED_PREFIX_ONLY", raws.get(2).packetDigestScope());
        assertArrayEquals(Hash.sha3(HexFormat.of().parseHex(raws.get(2).rawPacketHex())), HexFormat.of().parseHex(raws.get(2).packetDigest()));
        assertTrue(capture.snapshot().reports().isEmpty());
    }
    @Test void invalidAndRepeatedDescriptorsRetainIndividualOutcomes() {
        var capture = capture();
        byte[] wire = response(RESPONDER, NOW.getEpochSecond() + 60,
                descriptor(new byte[64], 30301), descriptor(key(TARGET), 0),
                descriptor(key(TARGET), 30301), descriptor(key(TARGET), 30301));
        var received = capture.receive(wire, REMOTE);
        assertEquals(1, received.nodes().size());
        assertEquals(1, capture.snapshot().reports().size());
        var raw = capture.snapshot().responses().getFirst();
        assertEquals(4, raw.descriptors().size());
        assertEquals(2L, capture.snapshot().diagnosticCounts().get("INVALID_RETURNED_DESCRIPTOR"));
        assertTrue(raw.diagnostics().contains("DUPLICATE_DESCRIPTOR"));
    }
    @Test void replayKeepsRawEvidenceButDoesNotDuplicateCandidatesOrDiscovery() {
        var capture = capture(); capture.receive(response(), REMOTE);
        assertTrue(capture.receive(response(), REMOTE).nodes().isEmpty());
        var snapshot = capture.snapshot();
        assertEquals(2, snapshot.responses().size()); assertEquals(1, snapshot.reports().size());
        assertEquals(snapshot.responses().getFirst().id(), snapshot.responses().getLast().duplicateOf());
        assertEquals("DUPLICATE_RESPONSE", snapshot.responses().getLast().validation());
    }
    @Test void absentObserverOrLocalKeyNeverCreatesRelationshipCandidate() {
        for (var capture : List.of(new Discv4EvidenceCapture(null, Clock.fixed(NOW, ZoneOffset.UTC)),
                new Discv4EvidenceCapture(new ObserverInstance.Run(run().observerInstanceId(), run().discoveryRunId(), NOW,
                        null, ObserverInstance.KeyLifecycle.MISSING), Clock.fixed(NOW, ZoneOffset.UTC)))) {
            assertEquals(1, capture.receive(response(), REMOTE).nodes().size()); // Compatible independent identity claims.
            assertTrue(capture.snapshot().reports().isEmpty());
            assertTrue(capture.snapshot().responses().getFirst().diagnostics().contains("MISSING_OBSERVER_CONTEXT"));
        }
    }
    @Test void endpointReuseDoesNotMergeRespondersOrReturnedIdentities() throws Exception {
        var capture = capture(); sent(capture, id(RESPONDER));
        capture.receive(response(), REMOTE);
        capture.receive(response(TARGET, NOW.getEpochSecond() + 60, descriptor(key(RESPONDER), 30301)), REMOTE);
        var reports = capture.snapshot().reports(); assertEquals(2, reports.size());
        assertNotEquals(reports.get(0).sourceResponderIdentity(), reports.get(1).sourceResponderIdentity());
        assertNotEquals(reports.get(0).targetIdentity(), reports.get(1).targetIdentity());
        assertEquals(reports.get(0).responderEndpoint(), reports.get(1).responderEndpoint());
        assertEquals(Discv4EvidenceCapture.Correlation.RESPONDER_IDENTITY_MISMATCH, reports.get(1).correlation());
    }
    @Test void sendFailureAndContradictoryRequestAreExplicit() throws Exception {
        var capture = capture();
        assertThrows(java.io.IOException.class, () -> capture.sendFindNode(request(), REMOTE, id(RESPONDER), key(LOCAL),
                () -> { throw new java.io.IOException("fixture"); }));
        capture.receive(response(), REMOTE);
        assertEquals("SEND_FAILED", capture.snapshot().requests().getFirst().sendOutcome());
        assertEquals(Discv4EvidenceCapture.Correlation.NO_RETAINED_REQUEST_CONTEXT, capture.snapshot().responses().getFirst().correlation());
        assertThrows(IllegalArgumentException.class, () -> capture.sendFindNode(request(), REMOTE, null, key(TARGET), () -> fail("must not send")));
        assertThrows(IllegalArgumentException.class, () -> sent(capture, "127.0.0.1"));
        byte[] otherSigner = Discv4Packet.encode(3, RlpEncoder.encode(new RlpList(RlpString.create(key(LOCAL)),
                RlpString.create(NOW.getEpochSecond() + 60))), RESPONDER);
        assertThrows(IllegalArgumentException.class, () -> capture.sendFindNode(otherSigner, REMOTE, null, key(LOCAL), () -> fail("must not send")));
    }
    @Test void localContextExpiresAndDoesNotProveLaterResponseAttribution() throws Exception {
        var clock = new Clock() {
            Instant now = NOW;
            @Override public Instant instant() { return now; }
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
        };
        var capture = new Discv4EvidenceCapture(run(), clock); sent(capture, id(RESPONDER));
        clock.now = NOW.plus(Discv4EvidenceCapture.REQUEST_WINDOW);
        capture.receive(response(), REMOTE);
        assertEquals(Discv4EvidenceCapture.Correlation.NO_RETAINED_REQUEST_CONTEXT, capture.snapshot().responses().getFirst().correlation());
    }
    @Test void boundedBuffersExposeEvictionAndNeverLeaveCandidatesWithoutRawEvidence() throws Exception {
        var capture = capture();
        for (int i = 0; i < Discv4EvidenceCapture.MAX_REQUESTS + 1; i++) sent(capture, id(RESPONDER));
        for (int i = 0; i < Discv4EvidenceCapture.MAX_RESPONSES + 1; i++)
            capture.receive(response(RESPONDER, NOW.getEpochSecond() + 60, descriptor(key(TARGET), i + 1)), REMOTE);
        var snapshot = capture.snapshot();
        assertEquals(Discv4EvidenceCapture.MAX_REQUESTS, snapshot.requests().size());
        assertEquals(Discv4EvidenceCapture.MAX_RESPONSES, snapshot.responses().size());
        assertEquals(1, snapshot.evictedRequests()); assertEquals(1, snapshot.evictedResponses()); assertEquals(1, snapshot.evictedReports());
        var sources = snapshot.responses().stream().map(Discv4EvidenceCapture.Response::id).collect(java.util.stream.Collectors.toSet());
        assertTrue(snapshot.reports().stream().allMatch(r -> sources.contains(r.sourceEvidenceId())));
        assertEquals(257L, snapshot.diagnosticCounts().get("VALID_SIGNED_REPORT"));
    }
    @Test void reportCandidateBudgetHasExplicitIndependentEvictionCount() {
        var capture = capture();
        for (int packet = 0; packet < 90; packet++) {
            RlpList[] entries = new RlpList[12];
            for (int j = 0; j < entries.length; j++) entries[j] = descriptor(key(TARGET), packet * 12 + j + 1);
            capture.receive(response(RESPONDER, NOW.getEpochSecond() + 60, entries), REMOTE);
        }
        var snapshot = capture.snapshot();
        assertEquals(Discv4EvidenceCapture.MAX_REPORTS, snapshot.reports().size());
        assertEquals(56, snapshot.evictedReports()); assertEquals(0, snapshot.evictedResponses());
        assertEquals(56L, snapshot.diagnosticCounts().get("REPORT_CANDIDATE_EVICTED"));
        assertTrue(snapshot.responses().stream().allMatch(raw -> raw.descriptors().size() == 12));
    }
}
