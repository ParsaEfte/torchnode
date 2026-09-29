package io.github.gavinruff007.torchnode.enr;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class Discv4EnrClientTest {
    @Test void authenticatedExchangeObtainsRealSignedRecordAfterEndpointProof() throws Exception {
        try (var peer = new LoopbackEnrPeer(LoopbackEnrPeer.Mode.VALID);
             var client = new Discv4EnrClient(EnrFixtures.KEY, Duration.ofSeconds(2))) {
            var e = client.fetch(peer.node());
            assertTrue(e.usable(), e.outcome() + ": " + e.detail());
            assertEquals("7", e.record().sequence()); assertEquals(1, peer.requests.get());
            assertTrue(e.provenance().contains("ENRRequest/ENRResponse"));
            assertEquals(30303, e.record().fields().tcp()); assertEquals(30305, peer.node().getTcpPort());
        }
    }
    @Test void rejectsUnsolicitedWrongRequestWrongSignerAndBadPacketBeforeAcceptingValidResponse() throws Exception {
        try (var peer = new LoopbackEnrPeer(LoopbackEnrPeer.Mode.MISLEADING_THEN_VALID);
             var client = new Discv4EnrClient(EnrFixtures.KEY, Duration.ofSeconds(2))) {
            assertTrue(client.fetch(peer.node()).usable());
        }
    }
    @Test void separatesBondTimeoutRequestTimeoutAndMalformedTransport() throws Exception {
        for (var mode : new LoopbackEnrPeer.Mode[]{LoopbackEnrPeer.Mode.SILENT, LoopbackEnrPeer.Mode.NO_ENR, LoopbackEnrPeer.Mode.MALFORMED_PACKET}) {
            try (var peer = new LoopbackEnrPeer(mode);
                 var client = new Discv4EnrClient(EnrFixtures.KEY, Duration.ofMillis(850))) {
                var e = client.fetch(peer.node());
                assertEquals(mode == LoopbackEnrPeer.Mode.SILENT ? "BOND_TIMEOUT" : mode == LoopbackEnrPeer.Mode.NO_ENR ? "REQUEST_TIMEOUT" : "MALFORMED_RESPONSE", e.outcome());
                assertFalse(e.received()); assertFalse(e.usable());
            }
        }
    }
    @Test void malformedAndInvalidSignatureRecordsAreReceivedButNeverTrusted() throws Exception {
        for (var mode : new LoopbackEnrPeer.Mode[]{LoopbackEnrPeer.Mode.BAD_RECORD, LoopbackEnrPeer.Mode.BAD_SIGNATURE}) {
            try (var peer = new LoopbackEnrPeer(mode);
                 var client = new Discv4EnrClient(EnrFixtures.KEY, Duration.ofSeconds(2))) {
                var e = client.fetch(peer.node());
                assertTrue(e.received()); assertFalse(e.usable()); assertNotNull(e.rawRlpHex());
                assertEquals(mode == LoopbackEnrPeer.Mode.BAD_RECORD ? "INVALID_STRUCTURE" : "INVALID_SIGNATURE", e.outcome());
            }
        }
    }
    @Test void cancellationAndInterruptionBoundPendingNetworkWork() throws Exception {
        try (var peer = new LoopbackEnrPeer(LoopbackEnrPeer.Mode.SILENT);
             var client = new Discv4EnrClient(EnrFixtures.KEY, Duration.ofSeconds(30))) {
            var future = new CompletableFuture<EnrEvidence>();
            Thread worker = new Thread(() -> future.complete(client.fetch(peer.node()))); worker.start();
            assertTrue(peer.pingReceived.await(1, TimeUnit.SECONDS)); worker.interrupt();
            assertEquals("CANCELLED", future.get(1, TimeUnit.SECONDS).outcome()); worker.join(1000);
            assertFalse(worker.isAlive());
        }
        try (var peer = new LoopbackEnrPeer(LoopbackEnrPeer.Mode.SILENT);
             var client = new Discv4EnrClient(EnrFixtures.KEY, Duration.ofSeconds(30))) {
            var future = CompletableFuture.supplyAsync(() -> client.fetch(peer.node()));
            assertTrue(peer.pingReceived.await(1, TimeUnit.SECONDS)); client.cancelPending();
            assertEquals("CANCELLED", future.get(1, TimeUnit.SECONDS).outcome());
        }
    }
    @Test void boundedCachePreventsDuplicateRequestsAndClearCancelsQueuedWork() throws Exception {
        try (var peer = new LoopbackEnrPeer(LoopbackEnrPeer.Mode.VALID);
             var acquirer = new EnrAcquirer(new Discv4EnrClient(EnrFixtures.KEY, Duration.ofSeconds(2)))) {
            var first = acquirer.acquire(peer.node());
            assertSame(first, acquirer.acquire(peer.node()));
            assertTrue(first.get(3, TimeUnit.SECONDS).usable());
            assertSame(first, acquirer.acquire(peer.node())); assertEquals(1, peer.requests.get());
            acquirer.clear();
            assertNotSame(first, acquirer.acquire(peer.node()));
        }
    }
    @Test void ipv6AuthenticatedExchangePreservesTrustAndEndpointProof() throws Exception {
        try (var peer = new LoopbackEnrPeer(LoopbackEnrPeer.Mode.MISLEADING_THEN_VALID, "::1");
             var client = new Discv4EnrClient(EnrFixtures.KEY, Duration.ofSeconds(2))) {
            var evidence = client.fetch(peer.node());
            assertTrue(evidence.usable(), evidence.outcome() + ": " + evidence.detail());
            assertTrue(evidence.provenance().contains("[0:0:0:0:0:0:0:1]:"));
            assertEquals(1, peer.requests.get());
        }
    }
    @Test void boundedQueueRejectsExcessWorkAndCloseCompletesAllPendingIdentities() throws Exception {
        var acquirer=new EnrAcquirer(new Discv4EnrClient(EnrFixtures.KEY,Duration.ofSeconds(30)));
        var futures=new java.util.ArrayList<CompletableFuture<EnrEvidence>>();
        try {
            for(int i=0;i<100;i++) futures.add(acquirer.acquire(new io.github.gavinruff007.torchnode.model.NodeRecord(
                    "127.0.0.1",40000+i,0,EnrFixtures.ID.nodeId())));
            assertTrue(futures.stream().anyMatch(f->f.isDone() && f.join().outcome().equals("BUSY")));
        } finally { acquirer.close(); }
        assertTrue(futures.stream().allMatch(CompletableFuture::isDone));
        assertTrue(futures.stream().allMatch(f->f.join().associatedIdentity().equals(EnrFixtures.ID)));
    }
}
