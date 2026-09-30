package io.github.gavinruff007.torchnode.storage;

import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalObservationsTest {
    @TempDir Path temp;

    private static Map<String,Object> evidence(String endpoint,String at,String tcp,String hello,String client,List<String> capabilities) {
        var stages=new ArrayList<Map<String,Object>>();
        String auth=tcp.equals("PASS")?"PASS":"NOT_TESTED";
        for(var pair:List.of(new String[]{"P2P TCP",tcp},new String[]{"RLPx Auth",auth},
                new String[]{"RLPx Hello",hello},new String[]{"ETH Status",hello.equals("PASS")?"PASS":"NOT_TESTED"}))
            stages.add(Map.of("name",pair[0],"state",pair[1],"source","fixture"));
        var attempt=new LinkedHashMap<String,Object>();attempt.put("endpoint",endpoint);attempt.put("addressFamily",endpoint.startsWith("[")?"IPV6":"IPV4");
        attempt.put("observedAt",at);attempt.put("diagnostics",stages);
        var p2p=new LinkedHashMap<String,Object>();
        if(hello.equals("PASS")){
            p2p.put("hello",Map.of("clientId",client,"capabilities",capabilities,"listenPort",30303));
            p2p.put("status",Map.of("networkId",1,"genesisHash","fixture-genesis"));
        }
        var result=new LinkedHashMap<String,Object>();result.put("endpointAttempts",List.of(attempt));result.put("diagnostics",stages);
        result.put("p2p",p2p);result.put("rpc",null);result.put("beacon",null);return result;
    }

    private static void run(SqliteNodeStore store,NodeRecord node,String id,String at,Map<String,Object> evidence) throws Exception {
        store.saveInspectionRun(node,id,at,at,evidence,null,null,null,List.of(),List.of());
    }

    @Test void repeatedInspectionsRetainOccurrencesAndDeduplicateFacts() throws Exception {
        String path=temp.resolve("repeat.db").toString();var node=new NodeRecord("192.0.2.1",30303,30303,"ab".repeat(64));
        try(var store=new SqliteNodeStore(path)) {
            store.save(node);
            run(store,node,"a","2026-01-01T00:00:00Z",evidence("192.0.2.1:30303","2026-01-01T00:00:01Z","PASS","PASS","Geth/A",List.of("eth/67")));
            run(store,node,"b","2026-01-02T00:00:00Z",evidence("192.0.2.1:30303","2026-01-02T00:00:01Z","PASS","PASS","Geth/A",List.of("eth/67")));
            run(store,node,"c","2026-01-03T00:00:00Z",evidence("192.0.2.1:30303","2026-01-03T00:00:01Z","PASS","PASS","Geth/B",List.of("eth/68")));
            run(store,node,"d","2026-01-04T00:00:00Z",evidence("192.0.2.1:30303","2026-01-04T00:00:01Z","PASS","FAILED",null,List.of()));
            run(store,node,"e","2026-01-05T00:00:00Z",evidence("192.0.2.1:30303","2026-01-05T00:00:01Z","FAILED","NOT_TESTED",null,List.of()));
            var page=store.inspectionHistory(node.identity(),2,null);
            assertEquals(List.of("e","d"),page.stream().map(SqliteNodeStore.InspectionHistory::id).toList());
            assertEquals(List.of("b","a"),store.inspectionHistory(node.identity(),2,"c").stream().map(SqliteNodeStore.InspectionHistory::id).toList());
            var all=store.inspectionHistory(node.identity(),100,null);
            assertEquals("2026-01-01T00:00:01Z",all.get(4).evidence()
                    .get("endpointAttempts") instanceof List<?> a ? ((Map<?,?>)a.get(0)).get("observedAt") : null);
            assertEquals("Geth/A",((Map<?,?>)((Map<?,?>)all.get(4).evidence().get("p2p")).get("hello")).get("clientId"));
            assertEquals("fixture-genesis",((Map<?,?>)((Map<?,?>)all.get(4).evidence().get("p2p")).get("status")).get("genesisHash"));
            assertEquals(List.of("eth/68"),((Map<?,?>)((Map<?,?>)all.get(2).evidence().get("p2p")).get("hello")).get("capabilities"));
            assertEquals("NOT_TESTED",((List<?>)all.get(0).evidence().get("diagnostics")).stream()
                    .map(v->(Map<?,?>)v).filter(v->"RLPx Auth".equals(v.get("name"))).findFirst().orElseThrow().get("state"));
            assertEquals("Geth/A",((Map<?,?>)((Map<?,?>)store.findInspectionRun("a").orElseThrow().evidence().get("p2p")).get("hello")).get("clientId"));
            assertTrue(store.findInspectionRun("missing").isEmpty());
            assertThrows(IllegalArgumentException.class,()->store.inspectionHistory(node.identity(),101,null));
            assertEquals(1,store.count());
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()) {
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM inspection_runs")){assertTrue(rows.next());assertEquals(5,rows.getInt(1));}
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM inspection_evidence")){assertTrue(rows.next());assertEquals(4,rows.getInt(1));}
        }
    }

    @Test void equalTimesOrderByIdAndClearRemovesHistory() throws Exception {
        String path=temp.resolve("clear.db").toString();var node=new NodeRecord("192.0.2.1",30303,30303,"ab".repeat(64));
        try(var store=new SqliteNodeStore(path)) {
            store.save(node);var facts=evidence("192.0.2.1:30303","2026-01-01T00:00:01Z","FAILED","NOT_TESTED",null,List.of());
            run(store,node,"a","2026-01-01T00:00:00Z",facts);run(store,node,"b","2026-01-01T00:00:00Z",facts);
            assertEquals(List.of("b","a"),store.inspectionHistory(node.identity(),10,null).stream().map(SqliteNodeStore.InspectionHistory::id).toList());
            store.clearCollectedData();assertEquals(0,store.count());assertTrue(store.inspectionHistory(node.identity(),10,null).isEmpty());
            store.save(node);run(store,node,"new","2026-01-02T00:00:00Z",facts);
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()) {
            try(var rows=sql.executeQuery("PRAGMA integrity_check")){assertEquals("ok",rows.getString(1));}
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM schema_migrations")){assertEquals(5,rows.getInt(1));}
        }
    }

    @Test void equalTimeLatestEndpointProjectionIsIndependentOfWriteOrder() throws Exception {
        var id=new NodeIdentity("ab".repeat(64));var at=Instant.parse("2026-01-01T00:00:00Z");
        for(int order=0;order<2;order++)try(var store=new SqliteNodeStore(temp.resolve("tie-"+order+".db").toString())){
            int[] ports=order==0?new int[]{30301,30305}:new int[]{30305,30301};
            for(int port:ports)store.saveObservation(new DiscoveryObservation(id,"discv4",List.of(
                    new NodeEndpoint("192.0.2.1",NodeEndpoint.Transport.UDP,30303,NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.DISCOVERY),
                    new NodeEndpoint("192.0.2.1",NodeEndpoint.Transport.TCP,port,NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.P2P)),at,"fixture"));
            assertEquals(30305,store.findAll().get(0).getTcpPort());
            assertEquals(2,store.discoveryHistory(id,10,0).size());
        }
    }

    @Test void discoveryRetainsIdentityEndpointAndFamilyEvidence() throws Exception {
        var x=new NodeIdentity("ab".repeat(64));var y=new NodeIdentity("cd".repeat(64));
        try(var store=new SqliteNodeStore(temp.resolve("discovery.db").toString())) {
            for(var item:List.of(new Object[]{x,"192.0.2.1","2026-01-01T00:00:00Z"},
                    new Object[]{x,"2001:db8::1","2026-01-02T00:00:00Z"},
                    new Object[]{y,"192.0.2.1","2026-01-03T00:00:00Z"})) {
                var id=(NodeIdentity)item[0];var address=(String)item[1];var family=EndpointAddress.family(address);
                store.saveObservation(new DiscoveryObservation(id,"discv4",List.of(
                        new NodeEndpoint(address,NodeEndpoint.Transport.UDP,30303,family,NodeEndpoint.Purpose.DISCOVERY),
                        new NodeEndpoint(address,NodeEndpoint.Transport.TCP,30303,family,NodeEndpoint.Purpose.P2P)),
                        Instant.parse((String)item[2]),"fixture"));
            }
            assertEquals(2,store.findObservations(x).size());assertEquals(1,store.findObservations(y).size());
            assertEquals(NodeEndpoint.AddressFamily.IPV6,store.findObservations(x).get(1).endpoints().get(0).addressFamily());
            var page=store.discoveryHistory(x,1,0);
            assertEquals(EndpointAddress.parse("2001:db8::1").getHostAddress(),page.get(0).observation().endpoints().get(0).address());
            assertEquals("192.0.2.1",store.discoveryHistory(x,1,page.get(0).id()).get(0).observation().endpoints().get(0).address());
            assertEquals(4,store.endpointHistory("192.0.2.1",10,0).size());
            assertEquals(2,store.endpointHistory("2001:db8::1",10,0).size());
            for(var identity:List.of(x,y)){
                String key=new NodeRecord(store.findObservations(identity).get(0)).getKey();
                assertEquals(identity,store.findByKey(key).orElseThrow().identity());
            }
        }
    }

    @Test void versionFourMigrationRollsBackAndLegacyLatestHasNoInventedRun() throws Exception {
        String path=temp.resolve("legacy.db").toString();
        try(var store=new SqliteNodeStore(path)){store.save(new NodeRecord("192.0.2.1",30303,30303,"ab".repeat(64)));}
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()) {
            sql.execute("DELETE FROM schema_migrations WHERE version=4");
            for(String table:List.of("inspection_enrichment_context","network_enrichment_lookups","discovery_endpoint_index","inspection_runs","inspection_evidence"))sql.execute("DROP TABLE "+table);
            sql.execute("CREATE TRIGGER block_v4 BEFORE INSERT ON schema_migrations WHEN NEW.version=4 BEGIN SELECT RAISE(ABORT,'blocked'); END");
        }
        assertThrows(java.sql.SQLException.class,()->new SqliteNodeStore(path));
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()) {
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM nodes")){assertEquals(1,rows.getInt(1));}
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='inspection_runs'")){assertEquals(0,rows.getInt(1));}
            sql.execute("DROP TRIGGER block_v4");
        }
        try(var store=new SqliteNodeStore(path)){assertEquals(1,store.count());assertTrue(store.inspectionHistory(new NodeIdentity("ab".repeat(64)),10,null).isEmpty());}
    }

    @Test void enrichmentLookupContextKeepsDatasetAndLookupTimeSeparate() throws Exception {
        String path=temp.resolve("enrichment.db").toString();var node=new NodeRecord("1.2.3.4",30303,30303,"ab".repeat(64));
        try(var store=new SqliteNodeStore(path)) {
            store.save(node);
            run(store,node,"first","2026-01-01T00:00:00Z",evidence("1.2.3.4:30303","2026-01-01T00:00:01Z","PASS","PASS","Geth/A",List.of("eth/67")));
            var first=NetworkEnrichment.state("1.2.3.4","fixture-v1",NetworkEnrichment.Status.DATASET_UNAVAILABLE,"fixture");
            store.saveNetworkEnrichment(first);store.linkInspectionEnrichment("first",first);
            var old=first.country();
            var sameTimeDifferent=new NetworkEnrichment(first.address(),first.addressFamily(),first.datasetKey(),
                    new NetworkEnrichment.Lookup(old.status(),"different factual result",old.countryCode(),old.countryName(),
                            old.asn(),old.organization(),old.networkPrefix(),old.lookedUpAt(),old.dataSource(),
                            old.dataSourceVersion(),old.provenance()),first.asn(),first.hostingClassification());
            store.saveNetworkEnrichment(sameTimeDifferent);store.linkInspectionEnrichment("first",sameTimeDifferent);
            run(store,node,"second","2026-01-02T00:00:00Z",evidence("1.2.3.4:30303","2026-01-02T00:00:01Z","PASS","PASS","Geth/A",List.of("eth/67")));
            var second=NetworkEnrichment.state("1.2.3.4","fixture-v2",NetworkEnrichment.Status.DATASET_UNAVAILABLE,"fixture");
            store.saveNetworkEnrichment(second);store.linkInspectionEnrichment("second",second);
            assertEquals(2,store.inspectionEnrichmentContext("first").size());
            assertTrue(store.inspectionEnrichmentContext("first").contains(first));
            assertTrue(store.inspectionEnrichmentContext("first").contains(sameTimeDifferent));
            assertEquals("fixture-v2",store.inspectionEnrichmentContext("second").get(0).datasetKey());
            assertNotEquals(store.inspectionHistory(node.identity(),10,null).get(1).startedAt(),first.country().lookedUpAt());
            store.clearCollectedData();
            assertTrue(store.inspectionEnrichmentContext("first").isEmpty());
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()){
            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")){assertFalse(rows.next());}
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM network_enrichment_lookups")){rows.next();assertEquals(0,rows.getInt(1));}
        }
    }

    @Test void failedLatestProjectionRollsBackRunAndPayload() throws Exception {
        String path=temp.resolve("atomic.db").toString();var node=new NodeRecord("192.0.2.1",30303,30303,"ab".repeat(64));
        try(var store=new SqliteNodeStore(path)){
            store.save(node);
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()){
                sql.execute("CREATE TRIGGER reject_projection BEFORE UPDATE ON nodes BEGIN SELECT RAISE(ABORT,'blocked'); END");
            }
            assertThrows(Exception.class,()->run(store,node,"failed","2026-01-01T00:00:00Z",
                    evidence("192.0.2.1:30303","2026-01-01T00:00:01Z","PASS","PASS","Geth/A",List.of("eth/67"))));
            assertTrue(store.inspectionHistory(node.identity(),10,null).isEmpty());
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement();var rows=sql.executeQuery("SELECT COUNT(*) FROM inspection_evidence")){
                rows.next();assertEquals(0,rows.getInt(1));
            }
        }
    }
}
