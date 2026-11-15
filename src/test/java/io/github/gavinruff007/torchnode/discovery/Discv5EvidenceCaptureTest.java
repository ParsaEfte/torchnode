package io.github.gavinruff007.torchnode.discovery;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.github.gavinruff007.torchnode.enr.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Discv5EvidenceCaptureTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private JsonNode fixture(String mode)throws Exception {
        try(var in=getClass().getResourceAsStream("/discv5-trace-v2-"+mode+".json")) {assertNotNull(in);return JSON.readTree(in);}
    }
    private ObserverInstance.Run run(JsonNode trace){return new ObserverInstance.Run(UUID.fromString(trace.path("observer").path("instanceId").asText()),
            UUID.fromString(trace.path("observer").path("runId").asText()),Instant.parse(trace.path("startedAt").asText()),
            trace.path("localIdentity").asText(),ObserverInstance.KeyLifecycle.EPHEMERAL_FIRST_SEEN);}
    private Discv5EvidenceCapture capture(JsonNode trace){var c=new Discv5EvidenceCapture(run(trace),trace.path("localIdentity").asText());c.ready(2,trace.path("localIdentity").asText());return c;}
    @Test void realAuthenticatedGoFixtureQualifiesAndRetainsEvidence()throws Exception {
        var envelope=fixture("valid");var trace=envelope.path("trace");var capture=capture(trace);capture.accept(trace);
        var snapshot=capture.snapshot();assertEquals(1,snapshot.reports().size());
        var report=snapshot.reports().getFirst();assertTrue(report.qualifiesForGraphPromotion());
        assertEquals("REPORTED_NEIGHBOR",report.relationType());assertEquals("discv5",report.protocol());
        assertEquals(trace.path("responderIdentity").asText(),report.sourceResponderIdentity());assertEquals(run(trace),report.observer());
        assertEquals(trace.path("traceId").asText(),report.sourceEvidenceId());assertEquals(trace.path("requestId").asText(),report.requestId());
        var raw=snapshot.traces().getFirst();assertEquals("COMPLETE_ATTRIBUTABLE",raw.outcome());
        assertTrue(raw.records().getFirst().enr().usable());assertEquals(trace,JSON.readTree(raw.rawJson()));assertFalse(snapshot.durable());
    }
    @Test void multipartAndDuplicatesPreserveDistinctSameEndpointIdentities()throws Exception {
        for(String mode:List.of("multi","duplicate","replay")) {
            var trace=fixture(mode).path("trace");var c=capture(trace);c.accept(trace);var s=c.snapshot();
            assertEquals(2,s.reports().stream().filter(Discv5EvidenceCapture.Report::qualifiesForGraphPromotion).count());
            var valid=s.traces().getFirst().records().stream().filter(r->r.enr().usable()).toList();
            assertTrue(valid.stream().map(r->r.enr().record().identity()).distinct().count()==2);
            assertTrue(valid.stream().allMatch(r->r.enr().record().fields().udp()==32003));
            assertTrue(valid.stream().allMatch(r->r.enr().record().fields().ip().equals("127.0.0.1")));
            if(mode.equals("duplicate"))assertTrue(s.reports().stream().anyMatch(r->!r.qualifiesForGraphPromotion()));
        }
    }
    @Test void partialDelayedUnsolicitedWrongSessionAndMalformedStayNonPromotable()throws Exception {
        for(String mode:List.of("partial","delayed","wrong-request","inconsistent","wrong-key","malformed")) {
            var trace=fixture(mode).path("trace");var c=capture(trace);c.accept(trace);var s=c.snapshot();
            assertFalse(s.traces().getFirst().eligibleContext(),mode);
            assertTrue(s.reports().stream().noneMatch(Discv5EvidenceCapture.Report::qualifiesForGraphPromotion),mode);
            if(mode.equals("partial"))assertFalse(s.traces().getFirst().records().isEmpty());
        }
    }
    @Test void invalidAndWrongDistanceEnrsHaveIndividualOutcomesWithoutPromotion()throws Exception {
        for(String mode:List.of("invalid-enr","distance-mismatch")) {
            var trace=fixture(mode).path("trace");var c=capture(trace);c.accept(trace);var s=c.snapshot();
            assertTrue(s.reports().stream().noneMatch(Discv5EvidenceCapture.Report::qualifiesForGraphPromotion));
            assertTrue(s.counters().containsKey("INVALID_OR_MISMATCHED_ENR"));
            if(mode.equals("invalid-enr")) {
                assertEquals(2,s.traces().getFirst().records().size());
                assertEquals(EnrEvidence.Signature.INVALID,s.traces().getFirst().records().get(0).enr().signature());
                assertTrue(s.traces().getFirst().records().get(1).enr().usable());
            }
        }
    }
    @Test void metadataCannotOverrideEnrIdentityOrAuthenticatedRoutingId()throws Exception {
        for(String field:List.of("identity","sourceRoutingId","responderIdentity","requestId")) {
            ObjectNode trace=fixture("valid").path("trace").deepCopy();
            if(field.equals("identity"))((ObjectNode)trace.path("packets").get(0).path("records").get(0)).put(field,"ab".repeat(64));
            else if(field.equals("sourceRoutingId"))((ObjectNode)trace.path("packets").get(0)).put(field,"ab".repeat(32));
            else trace.put(field,field.equals("responderIdentity")?"ab".repeat(64):"0000000000000000");
            var c=capture(trace);c.accept(trace);
            assertTrue(c.snapshot().reports().stream().noneMatch(Discv5EvidenceCapture.Report::qualifiesForGraphPromotion),field);
            if(field.equals("identity"))assertEquals(EnrEvidence.IdentityComparison.MISMATCH,c.snapshot().traces().getFirst().records().getFirst().enr().identityComparison());
        }
    }
    @Test void missingLegacyRotatedOrReplacedObserverContextDoesNotQualify()throws Exception {
        var trace=fixture("valid").path("trace");
        var noObserver=new Discv5EvidenceCapture(null,trace.path("localIdentity").asText());noObserver.ready(2,trace.path("localIdentity").asText());noObserver.accept(trace);
        assertTrue(noObserver.snapshot().reports().stream().noneMatch(Discv5EvidenceCapture.Report::qualifiesForGraphPromotion));
        var noReady=new Discv5EvidenceCapture(run(trace),trace.path("localIdentity").asText());assertNull(noReady.snapshot().helperDropHighWaterMark());noReady.accept(trace);
        assertFalse(noReady.snapshot().traces().getFirst().eligibleContext());
        var legacy=capture(trace);legacy.ready(0,trace.path("localIdentity").asText());legacy.accept(trace);assertFalse(legacy.snapshot().traces().getFirst().eligibleContext());
        assertThrows(IllegalStateException.class,()->legacy.ready(2,"ab".repeat(64)));
        assertThrows(IllegalArgumentException.class,()->new Discv5EvidenceCapture(run(trace),"ab".repeat(64)));
        var missing=new ObserverInstance.Run(run(trace).observerInstanceId(),run(trace).discoveryRunId(),run(trace).startedAt(),null,ObserverInstance.KeyLifecycle.MISSING);
        assertThrows(IllegalArgumentException.class,()->new Discv5EvidenceCapture(missing,trace.path("localIdentity").asText()));
        ObjectNode rotated=trace.deepCopy();((ObjectNode)rotated.path("observer")).put("runId",UUID.randomUUID().toString());
        var c=capture(trace);c.accept(rotated);assertFalse(c.snapshot().traces().getFirst().eligibleContext());
    }
    @Test void duplicateTracesIdempotentConflictsRevokeAndBufferEvictionsExplicit()throws Exception {
        var trace=fixture("valid").path("trace");var c=capture(trace);c.accept(trace);c.accept(trace);
        assertEquals(1,c.snapshot().reports().size());assertEquals(1L,c.snapshot().counters().get("DUPLICATE_TRACE"));
        ObjectNode conflict=trace.deepCopy();conflict.put("outcome","INCOMPLETE");c.accept(conflict);
        assertTrue(c.snapshot().reports().isEmpty());assertEquals("CONTRADICTORY_TRACE",c.snapshot().traces().getFirst().outcome());
        assertEquals(2,c.snapshot().traces().size());assertEquals(trace,JSON.readTree(c.snapshot().traces().getFirst().rawJson()));
        c=capture(trace);
        for(int i=0;i<Discv5EvidenceCapture.MAX_TRACES+1;i++){ObjectNode next=trace.deepCopy();next.put("traceId",String.format("%032x",i));c.accept(next);}
        var s=c.snapshot();assertEquals(Discv5EvidenceCapture.MAX_TRACES,s.traces().size());assertEquals(1,s.evictedTraces());assertEquals(1,s.evictedReports());
        Set<String> sources=new HashSet<>();s.traces().forEach(t->sources.add(t.id()));assertTrue(s.reports().stream().allMatch(r->sources.contains(r.sourceEvidenceId())));
        c.helperDrops(9);c.helperDrops(2);assertEquals(Long.valueOf(9),c.snapshot().helperDropHighWaterMark());
    }
    @Test void truncatedOversizedAndMalformedEvidenceNeverQualifies()throws Exception {
        ObjectNode trace=fixture("valid").path("trace").deepCopy();var c=capture(trace);trace.put("omittedPackets",1);c.accept(trace);assertFalse(c.snapshot().traces().getFirst().eligibleContext());
        c=capture(trace);ObjectNode huge=trace.deepCopy();huge.put("extra","x".repeat(60001));c.accept(huge);assertEquals(1L,c.snapshot().counters().get("OVERSIZED_TRACE"));assertTrue(c.snapshot().traces().isEmpty());
        c=capture(trace);ObjectNode malformed=trace.deepCopy();((ObjectNode)malformed.path("packets").get(0)).put("payload","00");c.accept(malformed);
        assertEquals("MALFORMED_TRACE",c.snapshot().traces().getFirst().outcome());assertTrue(c.snapshot().reports().isEmpty());
    }
    @Test void aRenamedReplayOfTheSameCodecResponderRequestCannotQualifyAgain()throws Exception {
        var trace=fixture("valid").path("trace");var c=capture(trace);c.accept(trace);
        ObjectNode replay=trace.deepCopy();replay.put("traceId","f".repeat(32));c.accept(replay);
        assertEquals(1,c.snapshot().reports().stream().filter(Discv5EvidenceCapture.Report::qualifiesForGraphPromotion).count());
        assertEquals(1L,c.snapshot().counters().get("DUPLICATE_REQUEST_CONTEXT"));
    }
    @Test void correctlyRotatedKeyContextRequiresTheNewRunIdentity()throws Exception {
        ObjectNode trace=fixture("valid").path("trace").deepCopy();
        var rotated=new ObserverInstance.Run(run(trace).observerInstanceId(),UUID.randomUUID(),run(trace).startedAt(),trace.path("localIdentity").asText(),ObserverInstance.KeyLifecycle.EPHEMERAL_ROTATED);
        var c=new Discv5EvidenceCapture(rotated,rotated.localDiscoveryIdentity());c.ready(2,rotated.localDiscoveryIdentity());
        ((ObjectNode)trace.path("observer")).put("runId",rotated.discoveryRunId().toString()).put("keyLifecycle","EPHEMERAL_ROTATED");c.accept(trace);
        assertTrue(c.snapshot().reports().getFirst().qualifiesForGraphPromotion());
    }
    @Test void selfAndEmptyResponsesDoNotInventNeighborEdges()throws Exception {
        for(String mode:List.of("self","empty")) {
            var trace=fixture(mode).path("trace");var c=capture(trace);c.accept(trace);
            assertTrue(c.snapshot().traces().getFirst().eligibleContext());
            assertTrue(c.snapshot().reports().stream().noneMatch(Discv5EvidenceCapture.Report::qualifiesForGraphPromotion));
            if(mode.equals("empty"))assertTrue(c.snapshot().traces().getFirst().records().isEmpty());
            else assertEquals("SELF_RECORD",c.snapshot().reports().getFirst().reason());
        }
        var repeated=fixture("repeated-enr").path("trace");var c=capture(repeated);c.accept(repeated);
        assertEquals(2,c.snapshot().reports().stream().filter(Discv5EvidenceCapture.Report::qualifiesForGraphPromotion).count());
        assertEquals(1L,c.snapshot().counters().get("DUPLICATE_ENR"));
    }
    @Test void independentReportBufferBudgetExposesEviction()throws Exception {
        ObjectNode trace=fixture("valid").path("trace").deepCopy();
        JsonNode template=trace.path("packets").get(0);var packetRoot=CanonicalRlp.decode(HexFormat.of().parseHex(template.path("payload").asText()),1280);
        byte[] enr=packetRoot.children().get(2).children().getFirst().encoded();
        ArrayNode packets=JSON.createArrayNode();
        for(int n:new int[]{1,2,3,4,6}) {
            var rawRecords=new ArrayList<byte[]>();var claims=JSON.createArrayNode();
            for(int i=0;i<n;i++){rawRecords.add(enr);ObjectNode claim=template.path("records").get(0).deepCopy();claim.put("index",i);claims.add(claim);}
            ObjectNode p=template.deepCopy();p.put("payload",HexFormat.of().formatHex(CanonicalRlp.list(List.of(packetRoot.children().get(0).encoded(),new byte[]{5},CanonicalRlp.list(rawRecords)))));p.set("records",claims);packets.add(p);
        }
        trace.set("packets",packets);var c=capture(trace);
        for(int i=0;i<70;i++){ObjectNode next=trace.deepCopy();next.put("traceId",String.format("%032x",i));next.put("codecContext",String.format("%032x",i));c.accept(next);}
        var snapshot=c.snapshot();assertEquals(1024,snapshot.reports().size());assertEquals(96,snapshot.evictedReports());assertEquals(0,snapshot.evictedTraces());
        assertEquals(96L,snapshot.counters().get("REPORT_EVICTED"));
    }
    @Test void providerVersionedIngestionAndLegacyNodeStreamRemainSeparate()throws Exception {
        var envelope=fixture("valid");var trace=envelope.path("trace");
        var identity=new LocalNodeIdentity(org.web3j.crypto.ECKeyPair.create(java.math.BigInteger.ONE));
        var provider=new Discv5DiscoveryProvider(identity,java.nio.file.Path.of("unused"),List.of("unused"),run(trace));
        provider.accept(JSON.valueToTree(Map.of("type","ready","version",2,"nodeId",identity.getNodeId())));
        provider.accept(envelope);assertTrue(provider.relationshipEvidence().reports().getFirst().qualifiesForGraphPromotion());
        assertTrue(provider.discover().isEmpty()); // Capture does not perform persistence or fabricate legacy discovery events.
        provider.close();
    }
}
