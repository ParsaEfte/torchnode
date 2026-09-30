package io.github.gavinruff007.torchnode.storage;

import io.github.gavinruff007.torchnode.enr.EnrDecoder;
import io.github.gavinruff007.torchnode.enr.EnrFixtures;
import io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment;
import io.github.gavinruff007.torchnode.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ChangeDetectionTest {
    @TempDir Path temp;
    private static final String ID="ab".repeat(64);
    private static final String ENDPOINT="192.0.2.1:30303";

    private NodeRecord node(){return new NodeRecord("192.0.2.1",30303,30303,ID);}
    private static String time(int day){return "2026-01-%02dT00:00:00Z".formatted(day);}
    private static Map<String,Object> stage(String name,String state,String reason){
        var value=new LinkedHashMap<String,Object>();value.put("name",name);value.put("state",state);
        value.put("reason",reason);value.put("source","fixture");return value;
    }
    private static Map<String,Object> p2p(int day,String tcp,String auth,String hello,String status,String client,
                                          List<?> capabilities,String reason){
        var stages=List.of(stage("P2P TCP",tcp,reason),stage("RLPx Auth",auth,null),
                stage("RLPx Hello",hello,null),stage("ETH Status",status,null));
        var claim=new LinkedHashMap<String,Object>();if(client!=null)claim.put("clientId",client);
        if(capabilities!=null)claim.put("capabilities",capabilities);
        var packet=new LinkedHashMap<String,Object>();if(!claim.isEmpty())packet.put("hello",claim);
        var attempt=new LinkedHashMap<String,Object>();attempt.put("endpoint",ENDPOINT);
        attempt.put("addressFamily","IPV4");attempt.put("observedAt",time(day));
        attempt.put("diagnostics",stages);attempt.put("p2p",packet);
        var evidence=new LinkedHashMap<String,Object>();evidence.put("endpointAttempts",List.of(attempt));
        evidence.put("diagnostics",stages);evidence.put("p2p",packet);
        return evidence;
    }
    private static Map<String,Object> api(int day,String domain,String outcome,String client){
        String endpoint="http://192.0.2.1:"+(domain.equals("RPC")?8545:5052);
        var attempt=new LinkedHashMap<String,Object>();attempt.put("endpoint",endpoint);
        attempt.put("attemptedAt",time(day));attempt.put("tcpOpen",!outcome.equals("TCP_CLOSED"));
        if(!outcome.equals("TCP_CLOSED"))attempt.put(domain.equals("RPC")?"rpcReachable":"beaconReachable",outcome.equals("PASS"));
        var response=new LinkedHashMap<String,Object>();if(outcome.equals("PASS")){
            response.put("endpoint",endpoint);response.put("observedAt",time(day));
            if(client!=null)response.put(domain.equals("RPC")?"clientVersion":"version",client);
        }
        var evidence=new LinkedHashMap<String,Object>();evidence.put(domain.equals("RPC")?"rpcAttempts":"beaconAttempts",List.of(attempt));
        evidence.put(domain.equals("RPC")?"rpc":"beacon",response);return evidence;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> atEndpoint(Map<String,Object> evidence,String endpoint,String family){
        var attempt=(Map<String,Object>)((List<?>)evidence.get("endpointAttempts")).get(0);
        attempt.put("endpoint",endpoint);attempt.put("addressFamily",family);return evidence;
    }
    private static void run(SqliteNodeStore store,NodeRecord node,String id,int day,Map<String,Object> evidence) throws Exception {
        store.saveInspectionRun(node,id,time(day),time(day),evidence,null,null,null,List.of(),List.of());
    }
    private static List<SqliteNodeStore.ChangeEvent> type(SqliteNodeStore store,NodeIdentity id,String type){
        return store.changeHistory(id,100,null).stream().filter(event->event.changeType().equals(type)).toList();
    }

    @Test void repeatedClientEvidenceAndSuccessfulHelloAreComparedOnlyWithCompatibleFacts() throws Exception {
        try(var store=new SqliteNodeStore(temp.resolve("client.db").toString())){
            var node=node();store.save(node);
            run(store,node,"a",1,p2p(1,"PASS","PASS","PASS","PASS","Geth/v1.0",List.of("eth/71"),null));
            run(store,node,"b",2,p2p(2,"PASS","PASS","PASS","PASS","Geth/v1.0",
                    List.of(Map.of("name","eth","version",71)),null));
            assertTrue(type(store,node.identity(),"CLIENT_VERSION_CHANGED").isEmpty());
            run(store,node,"c",3,p2p(3,"PASS","PASS","PASS","PASS","Geth/v1.1",List.of("eth/72","snap/1"),null));
            var version=type(store,node.identity(),"CLIENT_VERSION_CHANGED");
            assertEquals(1,version.size());assertEquals("b",version.get(0).previousObservationId());
            assertEquals("c",version.get(0).currentObservationId());
            assertEquals("1.0",version.get(0).previousValue());assertEquals("1.1",version.get(0).currentValue());
            assertEquals(1,version.get(0).derivationVersion());
            assertEquals(1,type(store,node.identity(),"CAPABILITIES_CHANGED").size());
            run(store,node,"d",4,p2p(4,"FAILED","NOT_TESTED","NOT_TESTED","NOT_TESTED",null,null,null));
            assertEquals(1,type(store,node.identity(),"CAPABILITIES_CHANGED").size());
            assertEquals(1,type(store,node.identity(),"TCP_ATTEMPT_OUTCOME_CHANGED").size());
            run(store,node,"e",5,p2p(5,"PASS","PASS","PASS","PASS","Reth/v2.0",List.of("eth/72"),null));
            assertEquals(1,type(store,node.identity(),"CLIENT_IMPLEMENTATION_CHANGED").size());
            assertEquals(1,type(store,node.identity(),"CLIENT_VERSION_CHANGED").size());
            assertEquals(2,type(store,node.identity(),"TCP_ATTEMPT_OUTCOME_CHANGED").size());
            var ids=store.changeHistory(node.identity(),100,null).stream().map(SqliteNodeStore.ChangeEvent::id).toList();
            store.rebuildChanges(node.identity());store.rebuildChanges(node.identity());
            assertEquals(ids,store.changeHistory(node.identity(),100,null).stream().map(SqliteNodeStore.ChangeEvent::id).toList());
        }
    }

    @Test void notTestedAndObserverRestrictionDoNotCreatePeerOutcomeTransition() throws Exception {
        try(var store=new SqliteNodeStore(temp.resolve("negative.db").toString())){
            var node=node();store.save(node);
            run(store,node,"restricted",1,p2p(1,"FAILED","NOT_TESTED","NOT_TESTED","NOT_TESTED",null,null,"Operation not permitted"));
            run(store,node,"not-tested",2,p2p(2,"NOT_TESTED","NOT_TESTED","NOT_TESTED","NOT_TESTED",null,null,null));
            run(store,node,"success",3,p2p(3,"PASS","PASS","PASS","PASS","Geth/v1.0",List.of("eth/71"),null));
            assertTrue(type(store,node.identity(),"TCP_ATTEMPT_OUTCOME_CHANGED").isEmpty());
            assertTrue(type(store,node.identity(),"CLIENT_VERSION_CHANGED").isEmpty());
            assertEquals(3,store.inspectionHistory(node.identity(),10,null).size());
        }
    }

    @Test void P2pStageTransitionsRequireTheSameTargetAndActualAttempt() throws Exception {
        try(var store=new SqliteNodeStore(temp.resolve("p2p-scope.db").toString())){
            var node=node();store.save(node);
            run(store,node,"a",1,p2p(1,"PASS","PASS","PASS","PASS","Geth/v1.0",List.of("eth/71"),null));
            run(store,node,"other-endpoint",2,atEndpoint(p2p(2,"PASS","FAILED","NOT_TESTED","NOT_TESTED",null,null,null),
                    "192.0.2.2:30303","IPV4"));
            run(store,node,"other-family",3,atEndpoint(p2p(3,"PASS","FAILED","NOT_TESTED","NOT_TESTED",null,null,null),
                    "[2001:db8::1]:30303","IPV6"));
            assertTrue(type(store,node.identity(),"RLPX_ATTEMPT_OUTCOME_CHANGED").isEmpty());
            run(store,node,"not-tested",4,p2p(4,"PASS","NOT_TESTED","NOT_TESTED","NOT_TESTED",null,null,null));
            assertTrue(type(store,node.identity(),"RLPX_ATTEMPT_OUTCOME_CHANGED").isEmpty());
            run(store,node,"failed",5,p2p(5,"PASS","FAILED","NOT_TESTED","NOT_TESTED",null,null,null));
            var events=type(store,node.identity(),"RLPX_ATTEMPT_OUTCOME_CHANGED");
            assertEquals(1,events.size());assertEquals("a",events.get(0).previousObservationId());
            assertEquals("failed",events.get(0).currentObservationId());
        }
    }

    @Test void RpcAndBeaconAreIndependentAndFlappingRemainsFactual() throws Exception {
        try(var store=new SqliteNodeStore(temp.resolve("api.db").toString())){
            var node=node();store.save(node);
            for(int day=1;day<=3;day++){
                var evidence=new LinkedHashMap<String,Object>();
                evidence.putAll(api(day,"RPC",day==2?"FAILED":"PASS",day==2?null:"Geth/v1.0"));
                evidence.putAll(api(day,"Beacon",day==3?"FAILED":"PASS",day==3?null:"Lighthouse/v1.0"));
                run(store,node,"run-"+day,day,evidence);
            }
            assertEquals(2,type(store,node.identity(),"RPC_PROBE_OUTCOME_CHANGED").size());
            assertEquals(1,type(store,node.identity(),"BEACON_PROBE_OUTCOME_CHANGED").size());
            assertTrue(type(store,node.identity(),"TCP_ATTEMPT_OUTCOME_CHANGED").isEmpty());
            store.clearCollectedData();assertTrue(store.changeHistory(node.identity(),10,null).isEmpty());
            store.save(node);run(store,node,"new",4,api(4,"RPC","PASS","Geth/v1.0"));
            assertTrue(type(store,node.identity(),"RPC_PROBE_OUTCOME_CHANGED").isEmpty());
        }
    }

    @Test void TrustedEnrSequenceOnlyAndSourceScopedEndpointFirstObservations() throws Exception {
        var id=EnrFixtures.ID;var node=new NodeRecord("127.0.0.1",30303,30303,id.nodeId());
        try(var store=new SqliteNodeStore(temp.resolve("enr.db").toString())){
            store.save(node);
            var decoder=new EnrDecoder();
            var second=decoder.decode(EnrFixtures.complete(2,30304),id,Instant.ofEpochSecond(2),"fixture");
            var first=decoder.decode(EnrFixtures.complete(1,30303),id,Instant.ofEpochSecond(1),"fixture");
            store.saveEnrEvidence(second);store.saveEnrEvidence(first);store.saveEnrEvidence(second);
            byte[] corrupt=EnrFixtures.complete(3,30305);corrupt[5]^=1;
            store.saveEnrEvidence(decoder.decode(corrupt,id,Instant.ofEpochSecond(3),"invalid"));
            store.saveEnrEvidence(decoder.decode(EnrFixtures.complete(4,30305),new NodeIdentity("cd".repeat(64)),Instant.ofEpochSecond(4),"mismatch"));
            var advances=type(store,id,"ENR_SEQUENCE_ADVANCED");assertEquals(1,advances.size());
            assertEquals("1",advances.get(0).previousValue());assertEquals("2",advances.get(0).currentValue());
            assertFalse(type(store,id,"IPV6_FIRST_OBSERVED").isEmpty());
            int firstEndpoints=type(store,id,"ENDPOINT_FIRST_OBSERVED").size();
            store.saveEnrEvidence(second);assertEquals(firstEndpoints,type(store,id,"ENDPOINT_FIRST_OBSERVED").size());
        }
    }

    @Test void EqualTimestampIsNotCausalAndPaginationIsStableAcrossReopen() throws Exception {
        String path=temp.resolve("ties.db").toString();var node=node();
        try(var store=new SqliteNodeStore(path)){
            store.save(node);
            run(store,node,"a",1,api(1,"RPC","PASS","Geth/v1.0"));
            run(store,node,"b",1,api(1,"RPC","FAILED",null));
            assertTrue(type(store,node.identity(),"RPC_PROBE_OUTCOME_CHANGED").isEmpty());
            run(store,node,"c",2,api(2,"RPC","PASS","Geth/v1.0"));
            assertTrue(type(store,node.identity(),"RPC_PROBE_OUTCOME_CHANGED").isEmpty());
            run(store,node,"d",3,api(3,"RPC","FAILED",null));
            assertEquals(1,type(store,node.identity(),"RPC_PROBE_OUTCOME_CHANGED").size());
        }
        try(var store=new SqliteNodeStore(path)){
            var page=store.changeHistory(node.identity(),1,null);assertEquals(1,page.size());
            var rest=store.changeHistory(node.identity(),100,page.get(0).id());
            assertEquals(store.changeHistory(node.identity(),100,null).size(),1+rest.size());
            assertTrue(rest.stream().noneMatch(event->event.id().equals(page.get(0).id())));
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()){
            try(var rows=sql.executeQuery("PRAGMA integrity_check")){assertEquals("ok",rows.getString(1));}
            try(var rows=sql.executeQuery("PRAGMA foreign_key_check")){assertFalse(rows.next());}
        }
    }

    @Test void DiscoveryFirstObservedIsIdentitySourceAndFamilyScopedWithoutDisappearance() throws Exception {
        var x=new NodeIdentity(ID);var y=new NodeIdentity("cd".repeat(64));
        try(var store=new SqliteNodeStore(temp.resolve("endpoints.db").toString())){
            var first=new NodeEndpoint("192.0.2.1",NodeEndpoint.Transport.TCP,30303,
                    NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.P2P);
            var firstUdp=new NodeEndpoint("192.0.2.1",NodeEndpoint.Transport.UDP,30303,
                    NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.DISCOVERY);
            var ipv6=new NodeEndpoint("2001:db8::1",NodeEndpoint.Transport.TCP,30303,
                    NodeEndpoint.AddressFamily.IPV6,NodeEndpoint.Purpose.P2P);
            var ipv6Udp=new NodeEndpoint("2001:db8::1",NodeEndpoint.Transport.UDP,30303,
                    NodeEndpoint.AddressFamily.IPV6,NodeEndpoint.Purpose.DISCOVERY);
            store.saveObservation(new DiscoveryObservation(x,"discv4",List.of(firstUdp,first),
                    Instant.parse("2026-01-01T00:00:00.100Z"),"fixture"));
            store.saveObservation(new DiscoveryObservation(x,"discv4",List.of(firstUdp,first),Instant.parse(time(1)),"fixture"));
            store.saveObservation(new DiscoveryObservation(x,"discv4",List.of(firstUdp,first),Instant.parse(time(2)),"fixture"));
            store.saveObservation(new DiscoveryObservation(x,"discv4",List.of(ipv6Udp,ipv6),Instant.parse(time(3)),"fixture"));
            store.saveObservation(new DiscoveryObservation(y,"discv4",List.of(firstUdp,first),Instant.parse(time(4)),"fixture"));
            assertEquals(4,type(store,x,"ENDPOINT_FIRST_OBSERVED").size());
            assertTrue(type(store,x,"ENDPOINT_FIRST_OBSERVED").stream()
                    .filter(event->event.source().equals("discv4") && event.addressFamily().equals("IPV4"))
                    .allMatch(event->event.currentObservedAt().equals(time(1))));
            assertEquals(1,type(store,x,"IPV6_FIRST_OBSERVED").size());
            assertEquals(2,type(store,y,"ENDPOINT_FIRST_OBSERVED").size());
            assertTrue(store.changeHistory(x,100,null).stream().noneMatch(e->e.changeType().contains("REMOVED")));
            store.saveObservation(new DiscoveryObservation(x,"discv5",List.of(firstUdp,first),Instant.parse(time(5)),"fixture"));
            assertEquals(6,type(store,x,"ENDPOINT_FIRST_OBSERVED").size());
        }
    }

    @Test void VersionFiveMigrationRollbackAndReopenPreserveHistory() throws Exception {
        String path=temp.resolve("rollback.db").toString();var node=node();
        try(var store=new SqliteNodeStore(path)){
            store.save(node);run(store,node,"prior",1,api(1,"RPC","PASS","Geth/v1.0"));
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()){
            sql.execute("DROP TABLE change_events");sql.execute("DELETE FROM schema_migrations WHERE version=5");
            sql.execute("DROP INDEX idx_change_inspection_order");sql.execute("DROP INDEX idx_change_enr_order");
            sql.execute("CREATE TRIGGER block_v5 BEFORE INSERT ON schema_migrations WHEN NEW.version=5 BEGIN SELECT RAISE(ABORT,'blocked'); END");
        }
        assertThrows(java.sql.SQLException.class,()->new SqliteNodeStore(path));
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()){
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM inspection_runs")){assertEquals(1,rows.getInt(1));}
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='change_events'")){assertEquals(0,rows.getInt(1));}
            try(var rows=sql.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('idx_change_inspection_order','idx_change_enr_order')")){
                assertEquals(0,rows.getInt(1));
            }
            sql.execute("DROP TRIGGER block_v5");
        }
        try(var store=new SqliteNodeStore(path)){
            assertEquals(1,store.inspectionHistory(node.identity(),10,null).size());
            assertTrue(store.changeHistory(node.identity(),10,null).stream().noneMatch(e->e.observationKind().equals("INSPECTION")));
            run(store,node,"next",2,api(2,"RPC","FAILED",null));
            assertEquals(1,type(store,node.identity(),"RPC_PROBE_OUTCOME_CHANGED").size());
        }
    }

    @Test void EnrichmentDatasetSuccessionIsNotNodeMovement() throws Exception {
        String path=temp.resolve("dataset.db").toString();
        try(var store=new SqliteNodeStore(path)){
            var node=node();store.save(node);
            int before=store.changeHistory(node.identity(),100,null).size();
            for(var item:List.of(new Object[]{"fixture-v1","US",64500L,time(1)},
                    new Object[]{"fixture-v2","DE",64501L,time(2)})){
                String version=(String)item[0],country=(String)item[1],at=(String)item[3];
                var countryResult=new NetworkEnrichment.Lookup(NetworkEnrichment.Status.FOUND,null,country,country,
                        null,null,"192.0.2.0/24",at,"fixture-country",version,"offline fixture");
                var asnResult=new NetworkEnrichment.Lookup(NetworkEnrichment.Status.FOUND,null,null,null,
                        (Long)item[2],"fixture-org","192.0.2.0/24",at,"fixture-asn",version,"offline fixture");
                store.saveNetworkEnrichment(new NetworkEnrichment("192.0.2.1",NodeEndpoint.AddressFamily.IPV4,
                        version,countryResult,asnResult,"NOT_AVAILABLE"));
            }
            assertEquals(before,store.changeHistory(node.identity(),100,null).size());
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement();
            var rows=sql.executeQuery("SELECT COUNT(*) FROM network_enrichment_lookups WHERE address='192.0.2.1'")){
            assertEquals(2,rows.getInt(1));
        }
    }

    @Test void FailedChangeInsertRollsBackTheObservationTransaction() throws Exception {
        String path=temp.resolve("atomic.db").toString();var node=node();
        try(var store=new SqliteNodeStore(path)){
            store.save(node);run(store,node,"first",1,api(1,"RPC","PASS","Geth/v1.0"));
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()){
                sql.execute("CREATE TRIGGER reject_change BEFORE INSERT ON change_events WHEN NEW.change_type='RPC_PROBE_OUTCOME_CHANGED' BEGIN SELECT RAISE(ABORT,'reject'); END");
            }
            assertThrows(Exception.class,()->run(store,node,"second",2,api(2,"RPC","FAILED",null)));
            assertEquals(List.of("first"),store.inspectionHistory(node.identity(),10,null).stream()
                    .map(SqliteNodeStore.InspectionHistory::id).toList());
            assertTrue(type(store,node.identity(),"RPC_PROBE_OUTCOME_CHANGED").isEmpty());
        }
    }

    @Test void DifferentIsoFractionPrecisionUsesActualTimestampOrder() throws Exception {
        try(var store=new SqliteNodeStore(temp.resolve("precision.db").toString())){
            var node=node();node.setLastSeen(Instant.parse("2025-12-31T00:00:00Z"));store.save(node);
            String early="2026-01-01T00:00:00Z",later="2026-01-01T00:00:00.100Z";
            for(var item:List.of(new Object[]{"early",early,true},new Object[]{"later",later,false})){
                String at=(String)item[1];var evidence=Map.<String,Object>of("rpcAttempts",List.of(
                        Map.of("endpoint","http://192.0.2.1:8545","attemptedAt",at,"tcpOpen",true,
                                "rpcReachable",item[2])));
                store.saveInspectionRun(node,(String)item[0],at,at,evidence,null,null,null,List.of(),List.of());
            }
            String last="2026-01-01T00:00:00.200Z";
            store.saveInspectionRun(node,"last",last,last,Map.of("rpcAttempts",List.of(
                    Map.of("endpoint","http://192.0.2.1:8545","attemptedAt",last,"tcpOpen",true,
                            "rpcReachable",true))),null,null,null,List.of(),List.of());
            var changes=type(store,node.identity(),"RPC_PROBE_OUTCOME_CHANGED");
            assertEquals(2,changes.size());assertEquals("later",changes.get(0).previousObservationId());
            assertEquals("last",changes.get(0).currentObservationId());
            assertEquals("early",changes.get(1).previousObservationId());
            assertEquals("later",changes.get(1).currentObservationId());
            assertEquals(changes.get(0).id(),store.changeHistory(node.identity(),1,null).get(0).id());
        }
    }

    @Test void EnrSequenceUsesActualTimeAcrossFractionPrecision() throws Exception {
        try(var store=new SqliteNodeStore(temp.resolve("enr-precision.db").toString())){
            var id=EnrFixtures.ID;var decoder=new EnrDecoder();
            var earlier=decoder.decode(EnrFixtures.complete(1,30303),id,
                    Instant.parse("2026-01-01T00:00:00Z"),"fixture");
            var later=decoder.decode(EnrFixtures.complete(2,30303),id,
                    Instant.parse("2026-01-01T00:00:00.100Z"),"fixture");
            store.saveEnrEvidence(later);store.saveEnrEvidence(earlier);
            var changes=type(store,id,"ENR_SEQUENCE_ADVANCED");
            assertEquals(1,changes.size());assertEquals("1",changes.get(0).previousValue());
            assertEquals("2",changes.get(0).currentValue());
        }
    }

    @Test void MissingLegacyTimeAndFirstClientDoNotInventAChange() throws Exception {
        try(var store=new SqliteNodeStore(temp.resolve("missing-time.db").toString())){
            var node=node();node.setLastSeen(null);store.save(node);
            assertTrue(store.changeHistory(node.identity(),10,null).isEmpty());
            run(store,node,"first-client",1,api(1,"RPC","PASS","Geth/v1.0"));
            assertTrue(type(store,node.identity(),"CLIENT_VERSION_CHANGED").isEmpty());
            assertTrue(type(store,node.identity(),"CLIENT_IMPLEMENTATION_CHANGED").isEmpty());
        }
    }
}
