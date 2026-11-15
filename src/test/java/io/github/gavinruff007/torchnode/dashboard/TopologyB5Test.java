package io.github.gavinruff007.torchnode.dashboard;
import io.github.gavinruff007.torchnode.storage.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gavinruff007.torchnode.discovery.*;
import io.github.gavinruff007.torchnode.enr.*;
import io.github.gavinruff007.torchnode.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TopologyB5Test {
    @TempDir Path temporary;
    private static final String STREAM="00000000-0000-0000-0000-000000000003";
    private JsonNode fixture(String mode)throws Exception {try(var in=getClass().getResourceAsStream("/discv5-trace-v2-"+mode+".json")){assertNotNull(in);return DiscoveryEvidenceBatch.JSON.readTree(in).path("trace");}}
    private ObserverInstance.Run run()throws Exception {var raw=fixture("valid");return new ObserverInstance.Run(UUID.fromString(raw.path("observer").path("instanceId").asText()),UUID.fromString(raw.path("observer").path("runId").asText()),Instant.parse(raw.path("startedAt").asText()).minusSeconds(1),raw.path("localIdentity").asText(),ObserverInstance.KeyLifecycle.EPHEMERAL_FIRST_SEEN);}
    private DiscoveryEvidenceBatch batch(JsonNode raw,ObserverInstance.Run context)throws Exception {
        var capture=new Discv5EvidenceCapture(context,raw.path("localIdentity").asText());capture.ready(2,raw.path("localIdentity").asText());capture.accept(raw);
        return DiscoveryEvidenceBatch.v5(context,STREAM,capture.snapshot()).getFirst();
    }
    private DiscoveryEvidenceBatch batch(String mode)throws Exception{return batch(fixture(mode),run());}
    private DiscoveryEvidenceRepository.Query query()throws Exception {Instant at=Instant.parse(fixture("valid").path("startedAt").asText());return new DiscoveryEvidenceRepository.Query(at.minusSeconds(60),at.plusSeconds(86400),null,"all",null,200);}
    private String path(String file){return temporary.resolve(file).toString();}
    private long count(String path,String table)throws Exception {try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(r.next());return r.getLong(1);}}
    private void sql(String path,String sql)throws Exception {try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement()){s.execute(sql);}}

    private io.github.gavinruff007.torchnode.topology.TopologyService.Query topologyQuery()throws Exception {
        var q=query();return new io.github.gavinruff007.torchnode.topology.TopologyService.Query(new io.github.gavinruff007.torchnode.analysis.NetworkAnalytics.Scope(q.start(),q.end()),"all",null,"",200,null);
    }
    @Test void persistedDirectedEdgesRetainImmutableHistoryAndSeparateIdentities()throws Exception {
        String db=path("graph.db");try(var store=new DiscoveryEvidenceStore(db)){store.ingest(batch("valid"));store.ingest(batch("multi"));}
        var service=new io.github.gavinruff007.torchnode.topology.TopologyService(db);var graph=service.graph(topologyQuery());
        assertEquals(2,graph.edges().size());assertEquals(3,graph.edges().stream().mapToInt(e->((Number)e.get("observationCount")).intValue()).sum());assertEquals(3,graph.nodes().size());assertEquals(2,graph.edges().stream().map(e->e.get("targetIdentity")).distinct().count());
        for(var edge:graph.edges()){assertEquals(fixture("valid").path("responderIdentity").asText(),edge.get("sourceIdentity"));assertNotNull(service.edgeEvidence(topologyQuery(),(String)edge.get("id")));}
        var firstAt=Instant.parse((String)graph.edges().getFirst().get("observedAt"));var exact=new io.github.gavinruff007.torchnode.topology.TopologyService.Query(new io.github.gavinruff007.torchnode.analysis.NetworkAnalytics.Scope(firstAt,firstAt.plusSeconds(1)),"discv5",null,"",200,null);assertFalse(service.graph(exact).edges().isEmpty());assertTrue(service.graph(exact).summary().relationshipAnalyticsAvailable());
        assertTrue(graph.summary().relationshipAnalyticsAvailable());assertFalse(graph.coverage().networkCoverageKnown());
        var q=topologyQuery();var other=new io.github.gavinruff007.torchnode.topology.TopologyService.Query(q.scope(),"all",null,"",200,null,UUID.randomUUID().toString());assertTrue(service.graph(other).edges().isEmpty());
        var frozen=new io.github.gavinruff007.torchnode.topology.TopologyService.Query(q.scope(),"all",null,"",200,null,null,0L);assertTrue(service.graph(frozen).edges().isEmpty());
    }
    @Test void diagnosticFixturesNeverBecomeEdgesAndMeasuredEmptyIsAvailable()throws Exception {
        for(String mode:List.of("invalid-enr","distance-mismatch","partial","wrong-request","inconsistent","wrong-key","empty")) {
            String db=path(mode+".db");try(var store=new DiscoveryEvidenceStore(db)){store.ingest(batch(mode));}
            var graph=new io.github.gavinruff007.torchnode.topology.TopologyService(db).graph(topologyQuery());assertTrue(graph.edges().isEmpty(),mode);
            assertEquals(mode.equals("empty"),graph.summary().relationshipAnalyticsAvailable(),mode);
        }
    }

    @Test void neighborhoodRetainsAttributedNeighborsBoundsAndProvenance()throws Exception {
        String db=path("neighborhood.db");try(var store=new DiscoveryEvidenceStore(db)){store.ingest(batch("valid"));store.ingest(batch("multi"));}
        var service=new io.github.gavinruff007.torchnode.topology.TopologyService(db);
        var q=topologyQuery();String source=fixture("valid").path("responderIdentity").asText();
        var graph=service.neighborhood(q,source);
        assertEquals(3,graph.nodes().size());assertEquals(2,graph.edges().size());
        assertFalse(graph.displayedSubset());
        var bounded=new io.github.gavinruff007.torchnode.topology.TopologyService.Query(q.scope(),q.protocol(),null,"",2,null);
        var page=service.neighborhood(bounded,source);
        assertEquals(2,page.nodes().size());assertTrue(page.nodes().stream().anyMatch(n->n.identity().equals(source)));
        assertEquals(1,page.edges().size());assertTrue(page.displayedSubset());assertTrue(page.relationshipEdgesTruncated());
        var frozen=new io.github.gavinruff007.torchnode.topology.TopologyService.Query(q.scope(),q.protocol(),null,"",200,null,null,0L);
        assertTrue(service.neighborhood(frozen,source).edges().isEmpty());
        for(var node:graph.nodes()) {
            assertEquals("relationship",node.latestEvidence().kind());
            assertEquals("/api/v1/topology/evidence/"+node.latestEvidence().occurrenceId(),node.latestEvidence().href());
            assertNotNull(service.persistedEvidence(node.latestEvidence().occurrenceId()));
        }
    }

    @Test void isolatedAuthenticatedHttpFixtureAcceptance()throws Exception {
        String db=path("http-fixture.db");try(var store=new DiscoveryEvidenceStore(db)){for(String mode:List.of("valid","multi","invalid-enr","empty","partial"))store.ingest(batch(mode));}
        try(var store=new DiscoveryEvidenceStore(db)){for(int key:List.of(2,4))try(var in=getClass().getResourceAsStream("/discv5-b4-responder-"+key+".json")){store.ingest(batch(DiscoveryEvidenceBatch.JSON.readTree(in).path("trace"),run()));}}
        var capture=new Discv4EvidenceCapture(run());
        byte[] key=org.web3j.utils.Numeric.toBytesPadded(org.web3j.crypto.ECKeyPair.create(java.math.BigInteger.valueOf(3)).getPublicKey(),64);
        var descriptor=new org.web3j.rlp.RlpList(org.web3j.rlp.RlpString.create(new byte[]{127,0,0,1}),org.web3j.rlp.RlpString.create(30303),org.web3j.rlp.RlpString.create(30305),org.web3j.rlp.RlpString.create(key));
        byte[] raw=Discv4Packet.encode(4,org.web3j.rlp.RlpEncoder.encode(new org.web3j.rlp.RlpList(new org.web3j.rlp.RlpList(descriptor),org.web3j.rlp.RlpString.create(Instant.now().plusSeconds(60).getEpochSecond()))),EnrFixtures.KEY);
        var remote=new java.net.InetSocketAddress("127.0.0.1",30303);
        byte[] request=Discv4Packet.encode(3,org.web3j.rlp.RlpEncoder.encode(new org.web3j.rlp.RlpList(org.web3j.rlp.RlpString.create(HexFormat.of().parseHex(run().localDiscoveryIdentity())),org.web3j.rlp.RlpString.create(Instant.now().plusSeconds(60).getEpochSecond()))),EnrFixtures.KEY);
        capture.sendFindNode(request,remote,EnrFixtures.ID.nodeId(),HexFormat.of().parseHex(run().localDiscoveryIdentity()),()->{});
        capture.sendFindNode(request,remote,EnrFixtures.ID.nodeId(),HexFormat.of().parseHex(run().localDiscoveryIdentity()),()->{});
        capture.receive(raw,remote);
        try(var store=new DiscoveryEvidenceStore(db)){for(var b:DiscoveryEvidenceBatch.v4(run(),STREAM,capture.snapshot()))store.ingest(b);}
        int port;try(var socket=new java.net.ServerSocket(0)){port=socket.getLocalPort();}
        var server=new DashboardServer(port,db,"127.0.0.1",false);server.start();try {
            var client=AuthTestSupport.client(db,port);var q=query();String window="?start="+q.start()+"&end="+q.end();
            java.util.function.Function<String,java.net.http.HttpResponse<String>> get=route->{try{return client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+route)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());}catch(Exception e){throw new RuntimeException(e);}};
            var response=get.apply("/api/v1/topology/graph"+window);assertEquals(200,response.statusCode());var graph=DiscoveryEvidenceBatch.JSON.readTree(response.body()).path("data");assertEquals(3,graph.path("edges").size());assertEquals(3,graph.path("nodes").size());
            for(var edge:graph.path("edges"))for(var observation:edge.path("observations")){var detail=get.apply("/api/v1/topology/edges/"+observation.path("id").asText()+window);assertEquals(200,detail.statusCode());assertEquals(observation.path("evidenceId").asText(),DiscoveryEvidenceBatch.JSON.readTree(detail.body()).at("/data/evidence/id").asText());}
            assertEquals(0,DiscoveryEvidenceBatch.JSON.readTree(get.apply("/api/v1/topology/graph"+window+"&protocol=discv4").body()).at("/data/edges").size());
            assertEquals(0,DiscoveryEvidenceBatch.JSON.readTree(get.apply("/api/v1/topology/graph"+window+"&observer="+UUID.randomUUID()).body()).at("/data/edges").size());
            assertEquals(400,get.apply("/api/v1/topology/graph"+window+"&observer=invalid").statusCode());
            assertEquals(200,get.apply("/topology").statusCode());
            var candidates=DiscoveryEvidenceBatch.JSON.readTree(get.apply("/api/v1/topology/candidates"+window).body()).at("/data/candidates");assertFalse(candidates.isEmpty());
            assertEquals(200,get.apply("/api/v1/topology/evidence/"+candidates.get(0).path("evidence_id").asText()+window).statusCode());



        }finally{server.stop();}
    }
    @Test void relationshipHighWatermarkExcludesConcurrentInsert()throws Exception {
        String db=path("concurrent.db");try(var store=new DiscoveryEvidenceStore(db)){store.ingest(batch("valid"));}
        var service=new io.github.gavinruff007.torchnode.topology.TopologyService(db);var first=service.graph(topologyQuery());
        try(var store=new DiscoveryEvidenceStore(db)){store.ingest(batch("multi"));}
        var q=topologyQuery();var frozen=new io.github.gavinruff007.torchnode.topology.TopologyService.Query(q.scope(),q.protocol(),q.relation(),q.search(),q.limit(),q.after(),q.observer(),first.highWatermark());
        assertEquals(1,service.graph(frozen).edges().size());assertEquals(2,service.graph(q).edges().size());
    }
}
