package io.github.gavinruff007.torchnode.analysis;

import io.github.gavinruff007.torchnode.topology.*;
import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TopologyServiceTest {
    @TempDir Path temp;
    final Instant start=Instant.parse("2026-01-01T00:00:00Z"),end=start.plus(Duration.ofDays(1));
    final String a="ab".repeat(64),b="cd".repeat(64),c="ef".repeat(64);
    TopologyService.Query query(){return new TopologyService.Query(new NetworkAnalytics.Scope(start,end),"all",null,"",100,null);}
    DiscoveryObservation receipt(String identity,Instant at,String provenance){return new DiscoveryObservation(new NodeIdentity(identity),"discv4",List.of(new NodeEndpoint("192.0.2.1",NodeEndpoint.Transport.UDP,30303,NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.DISCOVERY)),at,provenance);}
    String database(String name)throws Exception{String db=temp.resolve(name).toString();try(var ignored=new SqliteNodeStore(db)){}return db;}
    @Test void duplicatesFractionalBoundariesAndSharedEndpointsNeverInventEdges()throws Exception {
        String db=database("scope.db");try(var store=new SqliteNodeStore(db)) {
            var occurrence=receipt(a,start,"NEIGHBORS from 192.0.2.1:30303");store.saveObservation(occurrence);store.saveObservation(occurrence);
            store.saveObservation(receipt(a,start.plusNanos(1),"NEIGHBORS from 192.0.2.1:30303"));
            store.saveObservation(receipt(b,end.minusNanos(1),"NEIGHBORS from 192.0.2.1:30303"));
            store.saveObservation(receipt(c,end,"outside window"));
        }
        var service=new TopologyService(db);var graph=service.graph(query());
        assertEquals(2,graph.summary().observedIdentityCount());assertEquals(3,graph.coverage().sourceOccurrences());assertEquals(2,graph.nodes().getFirst().observationCount());
        assertEquals(start,graph.nodes().getFirst().firstObservedAt());assertEquals(start.plusNanos(1),graph.nodes().getFirst().lastObservedAt());assertEquals(end.minusNanos(1),graph.nodes().getLast().lastObservedAt());
        assertTrue(graph.edges().isEmpty());assertFalse(graph.summary().relationshipAnalyticsAvailable());assertNull(graph.summary().degreeDistribution());assertNull(graph.summary().connectedComponents());
        assertEquals(List.of(a,b),graph.nodes().stream().map(TopologyService.Vertex::identity).toList());
        var next=service.graph(new TopologyService.Query(query().scope(),"all",null,"",1,null));assertEquals(a,next.nextCursor());
        var tail=service.graph(new TopologyService.Query(query().scope(),"all",null,"",1,next.nextCursor()));assertEquals(b,tail.nodes().getFirst().identity());assertEquals(2,tail.summary().observedIdentityCount());assertTrue(tail.nextCursor().isEmpty());
        var neighbor=service.neighborhood(query(),a);assertEquals(1,neighbor.nodes().size());assertTrue(neighbor.edges().isEmpty());assertTrue(service.neighborhood(query(),c).nodes().isEmpty());
        var evidence=service.evidence(query(),a);assertEquals(2,evidence.observations().size());assertEquals("192.0.2.1",evidence.observations().getFirst().payload().get(0).path("address").asText());
        assertThrows(IllegalArgumentException.class,()->service.neighborhood(query(),"192.0.2.1"));
        assertEquals(0,service.graph(new TopologyService.Query(query().scope(),"discv5",null,"",100,null)).nodes().size());
        assertEquals(2,service.graph(new TopologyService.Query(query().scope(),"all",RelationshipObservation.Relation.DIRECT_SESSION,"",100,null)).nodes().size());
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+db);var statement=connection.createStatement()) {
            try(var r=statement.executeQuery("SELECT COUNT(*) FROM discovery_observations")){r.next();assertEquals(4,r.getInt(1));}
            try(var r=statement.executeQuery("SELECT MAX(version) FROM schema_migrations")){r.next();assertEquals(6,r.getInt(1));}
            try(var r=statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'topology%'")){r.next();assertEquals(0,r.getInt(1));}
        }
    }
    @Test void enrValidationAndSessionTargetsNeverBecomeConnectivity()throws Exception {
        String db=database("validation.db");var json=new ObjectMapper();
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+db)) {
            for(int i=0;i<3;i++)try(var q=connection.prepareStatement("INSERT INTO enr_observations(node_id,observed_at,provenance,outcome,signature_validation,identity_comparison,evidence_json) VALUES(?,?,?,'fixture',?,?,?)")) {
                String identity=List.of(a,b,c).get(i);q.setString(1,identity);q.setString(2,start.toString());q.setString(3,"validation fixture");q.setString(4,i==1?"INVALID":"VALID");q.setString(5,i==2?"MISMATCH":"MATCH");q.setString(6,json.writeValueAsString(Map.of("structurallyValid",true,"record",Map.of("identity",Map.of("nodeId",identity)))));q.executeUpdate();
            }
        }
        var service=new TopologyService(db);var graph=service.graph(query());assertEquals(1,graph.nodes().size());assertEquals(a,graph.nodes().getFirst().identity());assertEquals("VALIDATED_ENR_IDENTITY",graph.nodes().getFirst().validationStatus());assertEquals(2,graph.coverage().excludedInvalidEnrs());assertTrue(graph.edges().isEmpty());
        assertEquals("INVALID_OR_MISMATCHED_ENR",service.evidence(query(),b).observations().getFirst().validationStatus());
        try(var store=new SqliteNodeStore(db)) {
            var node=new NodeRecord("192.0.2.1",30303,30303,c);store.save(node);
            store.saveInspectionRun(node,"session-target",start.toString(),start.plusSeconds(1).toString(),Map.of("diagnostics",List.of(Map.of("name","RLPx Auth","state","PASS"),Map.of("name","RLPx Hello","state","PASS"))),null,null,null,List.of(),List.of());
        }
        graph=service.graph(query());assertEquals(2,graph.nodes().size());assertTrue(graph.edges().isEmpty());assertFalse(graph.coverage().completeRelationshipAttribution());
    }
    @Test void identityWindowComparisonDoesNotClaimRelationshipChanges()throws Exception {
        String db=database("compare.db");try(var store=new SqliteNodeStore(db)) {
            store.saveObservation(receipt(a,start.minusSeconds(2),"baseline"));store.saveObservation(receipt(b,start.minusSeconds(1),"baseline"));
            store.saveObservation(receipt(b,start,"current"));store.saveObservation(receipt(c,start.plusSeconds(1),"current"));
        }
        var comparison=new TopologyService(db).compare(query(),new NetworkAnalytics.Scope(start.minus(Duration.ofDays(1)),start));
        assertEquals(1,comparison.identities().newlyObserved());assertEquals(1,comparison.identities().reobserved());assertEquals(1,comparison.identities().notReobserved());assertEquals(List.of(a),comparison.identities().notReobservedIdentities());
        assertFalse(comparison.relationships().available());assertNull(comparison.relationships().newlyObserved());assertNull(comparison.relationships().notReobserved());
    }
    @Test void attributionContractRejectsEndpointsRoutingHashesMissingObserversAndWrongProofKinds() {
        var reference=new RelationshipObservation.EvidenceReference("discovery","123");
        var relation=new RelationshipObservation(a,b,c,RelationshipObservation.Relation.REPORTED_NEIGHBOR,"discv5",start,reference,"validated response fixture",RelationshipObservation.Validation.VALIDATED_DISCOVERY_RESPONSE);
        assertEquals(a,relation.sourceIdentity());assertEquals(c,relation.observerIdentity());
        for(String invalid:List.of("","192.0.2.1","ab".repeat(32)))assertThrows(IllegalArgumentException.class,()->new RelationshipObservation(invalid,b,c,RelationshipObservation.Relation.REPORTED_NEIGHBOR,"discv5",start,reference,"fixture",RelationshipObservation.Validation.VALIDATED_DISCOVERY_RESPONSE));
        assertThrows(IllegalArgumentException.class,()->new RelationshipObservation(a,b,null,RelationshipObservation.Relation.REPORTED_NEIGHBOR,"discv5",start,reference,"fixture",RelationshipObservation.Validation.VALIDATED_DISCOVERY_RESPONSE));
        assertThrows(IllegalArgumentException.class,()->new RelationshipObservation(a,b,c,RelationshipObservation.Relation.DIRECT_SESSION,"discv5",start,reference,"fixture",RelationshipObservation.Validation.VALIDATED_DISCOVERY_RESPONSE));
    }
    void seed(String db,int rows,boolean distinct)throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+db);var q=connection.prepareStatement("INSERT INTO discovery_observations(node_id,source,provenance,observed_at,endpoints_json) VALUES(?,'discv4',?,?,'[]')")) {
            connection.setAutoCommit(false);for(int i=0;i<rows;i++){q.setString(1,distinct?String.format("%0128x",i+1):a);q.setString(2,"receipt-"+i);q.setString(3,start.toString());q.addBatch();}q.executeBatch();connection.commit();
        }
    }
    @Test void largeRetainedSampleHasBoundedPagesAndExplicitEvidenceBudget()throws Exception {
        String db=database("large.db");seed(db,10000,true);long started=System.nanoTime();
        var graph=assertTimeoutPreemptively(Duration.ofSeconds(10),()->new TopologyService(db).graph(new TopologyService.Query(query().scope(),"all",null,"",200,null)));
        assertEquals(10000,graph.summary().observedIdentityCount());assertEquals(200,graph.nodes().size());assertTrue(graph.displayedSubset());assertFalse(graph.nextCursor().isEmpty());
        System.out.printf("Topology 10,000-occurrence query: %.1f ms; displayed 200 identities; 0 inferred edges%n",(System.nanoTime()-started)/1_000_000.0);
        String over=database("over.db");seed(over,TopologyService.MAX_OCCURRENCES+1,false);assertThrows(TopologyService.ScopeLimit.class,()->new TopologyService(over).graph(query()));
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+over);var statement=connection.createStatement()){statement.execute("DELETE FROM discovery_observations WHERE id=(SELECT MAX(id) FROM discovery_observations)");}
        var exact=new TopologyService(over).graph(query());assertEquals(50000,exact.coverage().sourceOccurrences());assertTrue(exact.coverage().safetyLimitReached());assertTrue(exact.coverage().queryComplete());assertFalse(exact.coverage().aggregateTruncated());assertFalse(exact.coverage().networkCoverageKnown());
        assertThrows(IllegalArgumentException.class,()->new TopologyService.Query(query().scope(),"all",null,"",201,null));
    }
    @Test void keysetPaginationHasUniqueTieBreakerAcrossConcurrentBackfills()throws Exception {
        String db=database("paging.db");String one="11".repeat(64),two="22".repeat(64),three="33".repeat(64),four="44".repeat(64),five="55".repeat(64);
        try(var store=new SqliteNodeStore(db)){for(String id:List.of(one,three,five))store.saveObservation(receipt(id,start,"same timestamp"));}
        var service=new TopologyService(db);var first=service.graph(new TopologyService.Query(query().scope(),"all",null,"",2,null));assertEquals(List.of(one,three),first.nodes().stream().map(TopologyService.Vertex::identity).toList());assertEquals(three,first.nextCursor());
        Thread writer=new Thread(()->{try(var store=new SqliteNodeStore(db)){store.saveObservation(receipt(two,start,"backfill before cursor"));store.saveObservation(receipt(four,start,"backfill after cursor"));}catch(Exception e){throw new RuntimeException(e);}});writer.start();writer.join();
        var next=service.graph(new TopologyService.Query(query().scope(),"all",null,"",2,first.nextCursor()));assertEquals(List.of(four,five),next.nodes().stream().map(TopologyService.Vertex::identity).toList());assertEquals(5,next.summary().observedIdentityCount());
        assertEquals(5,service.graph(query()).nodes().size());assertTrue(next.paginationConsistency().contains("reload"));assertTrue(next.coverage().queryComplete());assertFalse(next.coverage().aggregateTruncated());assertFalse(next.coverage().networkCoverageKnown());
    }
    @Test void sqliteVmDeadlineAndCancellationActuallyInterruptCpuBoundSql()throws Exception {
        String db=database("deadline.db");String cpu="WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<100000000) SELECT SUM(x) FROM n";
        try(var connection=DriverManager.getConnection("jdbc:sqlite:"+db);var statement=connection.createStatement()) {
            long started=System.nanoTime();
            try(var guard=new SqliteReadDeadline(connection,Duration.ofMillis(20))) {
                var error=assertThrows(org.sqlite.SQLiteException.class,()->statement.executeQuery(cpu));assertEquals(org.sqlite.SQLiteErrorCode.SQLITE_INTERRUPT,error.getResultCode());
            }
            assertTrue(Duration.ofNanos(System.nanoTime()-started).compareTo(Duration.ofSeconds(2))<0,"VM deadline must not depend on busy_timeout");
            try(var guard=new SqliteReadDeadline(connection,Duration.ofSeconds(5))) {
                Thread.currentThread().interrupt();
                try{var error=assertThrows(org.sqlite.SQLiteException.class,()->statement.executeQuery(cpu));assertEquals(org.sqlite.SQLiteErrorCode.SQLITE_INTERRUPT,error.getResultCode());}
                finally{Thread.interrupted();}
            }
            try(var rows=statement.executeQuery("SELECT 42")){assertEquals(42,rows.getInt(1));}
        }
        seed(db,1000,true);assertThrows(SQLException.class,()->new TopologyService(db,Duration.ofNanos(1)).graph(query()));
    }

    @Test void emptyEvidenceAndPayloadBoundsAreExplicit()throws Exception {
        String db=database("empty.db");var service=new TopologyService(db);assertTrue(service.graph(query()).nodes().isEmpty());assertTrue(service.evidence(query(),a).observations().isEmpty());
        try(var store=new SqliteNodeStore(db)){for(int i=0;i<25;i++)store.saveObservation(receipt(a,start.plusSeconds(i),"<img src=x onerror=alert(1)> "+i));}
        var page=service.evidence(query(),a);assertEquals(20,page.observations().size());assertTrue(page.truncated());assertTrue(page.observations().getFirst().provenance().startsWith("<img"));
        var endpoints=new ArrayList<NodeEndpoint>();for(int i=1;i<=100;i++)endpoints.add(new NodeEndpoint("2001:db8::"+Integer.toHexString(i),NodeEndpoint.Transport.UDP,30303,NodeEndpoint.AddressFamily.IPV6,NodeEndpoint.Purpose.DISCOVERY));
        try(var store=new SqliteNodeStore(db)){store.saveObservation(new DiscoveryObservation(new NodeIdentity(a),"discv4",endpoints,start.plusSeconds(100),"p".repeat(5000)));}
        var bounded=service.evidence(query(),a).observations().getFirst();assertTrue(bounded.payloadOmitted());assertNull(bounded.payload());assertTrue(bounded.provenanceTruncated());assertEquals(4096,bounded.provenance().length());
    }
}
