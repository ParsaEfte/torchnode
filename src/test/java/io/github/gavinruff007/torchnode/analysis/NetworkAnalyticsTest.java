package io.github.gavinruff007.torchnode.analysis;

import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import static org.junit.jupiter.api.Assertions.*;

class NetworkAnalyticsTest {
    @TempDir Path temp;
    private static final Instant START=Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END=Instant.parse("2026-01-02T00:00:00Z");
    private static NetworkAnalytics.Metric metric(NetworkAnalytics.Report report,String id) {
        return report.metrics().stream().filter(m->m.id().equals(id)).findFirst().orElseThrow();
    }
    private static long bucket(NetworkAnalytics.Metric metric,String label) {
        return metric.buckets().stream().filter(b->b.label().equals(label)).mapToLong(NetworkAnalytics.Bucket::count).sum();
    }
    private static DiscoveryObservation observation(NodeIdentity id,String source,String address,Instant time) {
        var family=EndpointAddress.family(address);
        return new DiscoveryObservation(id,source,List.of(new NodeEndpoint(address,NodeEndpoint.Transport.UDP,30303,
                family,NodeEndpoint.Purpose.DISCOVERY)),time,"fixture");
    }
    private static Map<String,Object> attempt(String time,String tcp,String auth,String hello,String status,String client) {
        var stages=List.of(Map.of("name","P2P TCP","state",tcp),Map.of("name","RLPx Auth","state",auth),
                Map.of("name","RLPx Hello","state",hello),Map.of("name","ETH Status","state",status));
        var value=new LinkedHashMap<String,Object>();value.put("endpoint","192.0.2.1:30303");
        value.put("addressFamily","IPV4");value.put("observedAt",time);value.put("diagnostics",stages);
        value.put("p2p",client==null?null:Map.of("hello",Map.of("clientId",client,"capabilities",List.of("eth/68","snap/1"))));
        return value;
    }
    @Test void windowDeduplicationStageSemanticsConflictAndClear() throws Exception {
        var path=temp.resolve("analytics.db").toString();
        var a=new NodeIdentity("ab".repeat(64));var b=new NodeIdentity("cd".repeat(64));
        try(var store=new SqliteNodeStore(path)) {
            assertEquals(0,metric(new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END)),"observed-identities").denominator());
            store.saveObservation(observation(a,"discv4","192.0.2.1",START));
            store.saveObservation(observation(a,"discv4","192.0.2.1",START.plusNanos(1)));
            store.saveObservation(observation(a,"discv5","2001:db8::1",START.plusSeconds(1)));
            store.saveObservation(observation(b,"discv5","192.0.2.1",END.minusNanos(1)));
            store.saveObservation(observation(b,"discv4","192.0.2.2",END));
            var node=new NodeRecord("192.0.2.1",30303,30303,a.nodeId());
            store.save(node);
            var evidence=new LinkedHashMap<String,Object>();evidence.put("endpointAttempts",List.of(
                    attempt(START.plusSeconds(2).toString(),"PASS","PASS","PASS","NOT_TESTED","Geth/v1.0/Linux"),
                    attempt(START.plusSeconds(3).toString(),"FAILED","NOT_TESTED","NOT_TESTED","NOT_TESTED",null)));
            evidence.put("rpc",Map.of("clientVersion","Reth/v2.0/Linux","endpoint","http://192.0.2.1:8545","observedAt",START.plusSeconds(2).toString()));
            evidence.put("rpcAttempts",List.of(Map.of("endpoint","http://192.0.2.1:8545","attemptedAt",START.plusSeconds(2).toString(),"tcpOpen",true,"rpcReachable",true)));
            evidence.put("beaconAttempts",List.of(Map.of("endpoint","http://192.0.2.1:5052","attemptedAt",START.plusSeconds(2).toString(),"tcpOpen",false,"beaconReachable",false)));
            store.saveInspectionRun(node,"run",START.plusSeconds(2).toString(),START.plusSeconds(4).toString(),
                    evidence,null,null,null,List.of(),List.of());
            store.save(new NodeRecord("192.0.2.9",30303,30303,"ef".repeat(64)));
            var report=new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END));
            assertEquals(2,metric(report,"observed-identities").denominator());
            assertEquals(3,metric(new NetworkAnalytics(path).measure(NetworkAnalytics.Scope.allAvailable()),
                    "observed-identities").denominator());
            assertEquals(3,new NetworkAnalytics(path).snapshot().distinctProjectionIdentities());
            assertEquals(4,metric(report,"discovery-occurrences").denominator());
            assertEquals(2,metric(report,"inspected-identities").denominator());
            assertEquals(1,metric(report,"inspected-identities").unknown());
            assertEquals(1,bucket(metric(report,"discovery-providers"),"discv4"));
            assertEquals(2,bucket(metric(report,"discovery-providers"),"discv5"));
            assertTrue(metric(report,"discovery-providers").bucketSemantics().contains("overlap"));
            assertEquals(1,bucket(metric(report,"discv4-discv5-overlap"),"both"));
            assertEquals(1,bucket(metric(report,"dual-family"),"both"));
            assertEquals(2,bucket(metric(report,"address-families"),"IPV4"));
            assertEquals(1,bucket(metric(report,"address-families"),"IPV6"));
            assertEquals(0,bucket(metric(report,"ipv6-tcp-pass"),"IPv6 TCP PASS"));
            assertEquals(1,bucket(metric(report,"p2p-tcp"),"PASS"));
            assertEquals(1,bucket(metric(report,"p2p-tcp"),"FAILED"));
            assertEquals(2,metric(report,"p2p-tcp").denominator());
            assertEquals(1,metric(report,"p2p-rlpx").denominator());
            assertEquals(1,metric(report,"p2p-rlpx").unknown());
            assertEquals(1,metric(report,"p2p-hello").denominator());
            assertEquals(1,metric(report,"p2p-hello").unknown());
            assertEquals(0,metric(report,"p2p-status").denominator());
            assertEquals(2,metric(report,"p2p-status").unknown());
            assertEquals(2,bucket(metric(report,"p2p-status"),"NOT_TESTED"));
            assertEquals(1,bucket(metric(report,"execution-clients"),"CONFLICT"));
            assertEquals(1,bucket(metric(report,"execution-versions"),"Geth/1.0"));
            assertEquals(1,bucket(metric(report,"execution-versions"),"Reth/2.0"));
            assertEquals(1,bucket(metric(report,"hello-capabilities"),"eth/68"));
            assertEquals(0,bucket(metric(report,"p2p-status"),"PASS"));
            assertEquals(1,bucket(metric(report,"rpc-attempts"),"PASS"));
            assertEquals(1,bucket(metric(report,"beacon-attempts"),"NO_TCP_CONNECTION"));
            assertEquals(0,new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(END,END.plusSeconds(1)))
                    .metrics().stream().filter(m->m.id().equals("discovery-occurrences")).findFirst().orElseThrow().denominator()-1);
            store.clearCollectedData();
            assertEquals(0,metric(new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END)),"observed-identities").denominator());
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement();var rows=s.executeQuery("PRAGMA integrity_check")) {
            assertTrue(rows.next());assertEquals("ok",rows.getString(1));
        }
        assertThrows(IllegalArgumentException.class,()->new NetworkAnalytics.Scope(START,START));
        assertThrows(IllegalArgumentException.class,()->new NetworkAnalytics.Scope(START,START.plusSeconds(32L*86400)));
    }

    @Test void enrTrustGeoStatusAndRuleVersionStayDistinct() throws Exception {
        String path=temp.resolve("evidence.db").toString();
        try(var store=new SqliteNodeStore(path)) {
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+path)) {
                try(var e=db.prepareStatement("INSERT INTO enr_observations(node_id,observed_at,provenance,outcome,sequence,signature_validation,identity_comparison,raw_rlp_hex,evidence_json) VALUES(?,?,?,?,?,?,?,?,?)")) {
                    for(int i=0;i<3;i++) {
                        e.setString(1,"ab".repeat(64));e.setString(2,START.plusSeconds(i).toString());e.setString(3,"fixture"+i);
                        e.setString(4,"RECEIVED");e.setString(5,"1");e.setString(6,i==1?"INVALID":"VALID");
                        e.setString(7,i==2?"MISMATCH":"MATCH");e.setString(8,null);
                        e.setString(9,"{\"structurallyValid\":true}");e.executeUpdate();
                    }
                }
                try(var l=db.prepareStatement("INSERT INTO network_enrichment_lookups VALUES(?,?,?,?,?)")) {
                    for(var item:List.of(new String[]{"FOUND","FOUND","US","64500"},
                            new String[]{"DATASET_UNAVAILABLE","DATASET_UNAVAILABLE",null,null},
                            new String[]{"NOT_APPLICABLE","LOOKUP_FAILED",null,null})) {
                        int i=List.of("FOUND","DATASET_UNAVAILABLE","NOT_APPLICABLE").indexOf(item[0]);
                        l.setString(1,"lookup"+i);l.setString(2,"192.0.2."+(i+1));l.setString(3,"dataset-v1");
                        l.setString(4,START.plusSeconds(i).toString());
                        l.setString(5,"{\"country\":{\"status\":\""+item[0]+"\",\"countryCode\":"+
                                (item[2]==null?"null":"\""+item[2]+"\"")+"},\"asn\":{\"status\":\""+item[1]+"\",\"asn\":"+
                                (item[3]==null?"null":item[3])+"},\"hostingClassification\":\"NOT_AVAILABLE\"}");l.executeUpdate();
                    }
                }
                try(var c=db.prepareStatement("INSERT INTO change_events VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    for(int version:List.of(1,2,3)) {
                        int i=1;c.setString(i++,"event"+version);c.setString(i++,"ab".repeat(64));c.setString(i++,"P2P");
                        c.setString(i++,version==3?"ENDPOINT_FIRST_OBSERVED":"CLIENT_VERSION_CHANGED");c.setString(i++,"fixture");c.setString(i++,"INSPECTION");
                        c.setString(i++,"old");c.setString(i++,"new");c.setString(i++,START.toString());
                        c.setString(i++,START.plusSeconds(version).toString());c.setString(i++,"1");c.setString(i++,"2");
                        c.setString(i++,"P2P");c.setString(i++,null);c.setString(i++,null);c.setInt(i,version);c.executeUpdate();
                    }
                }
            }
            var report=new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END));
            assertEquals(1,bucket(metric(report,"enr-trust"),"trusted"));
            assertEquals(1,bucket(metric(report,"enr-trust"),"diagnostic or untrusted"));
            assertEquals(1,bucket(metric(report,"country-lookup-status"),"dataset-v1:FOUND"));
            assertEquals(1,bucket(metric(report,"country-lookup-status"),"dataset-v1:DATASET_UNAVAILABLE"));
            assertEquals(1,bucket(metric(report,"country-lookup-status"),"dataset-v1:NOT_APPLICABLE"));
            assertEquals(1,bucket(metric(report,"asn-lookup-status"),"dataset-v1:LOOKUP_FAILED"));
            assertEquals(1,bucket(metric(report,"country-distribution-top-100"),"dataset-v1:US"));
            assertEquals(1,bucket(metric(report,"asn-distribution-top-100"),"dataset-v1:64500"));
            assertEquals(3,bucket(metric(report,"hosting-classification"),"dataset-v1:NOT_AVAILABLE"));
            assertEquals(1,bucket(metric(report,"change-activity"),"1:CLIENT_VERSION_CHANGED"));
            assertEquals(1,bucket(metric(report,"change-activity"),"2:CLIENT_VERSION_CHANGED"));
            assertEquals(1,bucket(metric(report,"change-activity"),"3:ENDPOINT_FIRST_OBSERVED"));
        }
    }

    @Test void missingLegacyClocksAreExcludedAndCounted() throws Exception {
        String path=temp.resolve("untimed.db").toString();
        try(var ignored=new SqliteNodeStore(path)) { }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement()) {
            s.executeUpdate("INSERT INTO inspection_evidence VALUES('legacy','{\"endpointAttempts\":[{\"endpoint\":\"192.0.2.1:30303\"}],\"rpc\":{\"clientVersion\":\"Geth/v1\"}}')");
            s.executeUpdate("INSERT INTO inspection_runs VALUES('legacy','"+"ab".repeat(64)+"','key',NULL,NULL,'legacy','unknown','legacy','{}')");
        }
        var report=new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END));
        assertEquals(0,metric(report,"observed-identities").denominator());
        assertEquals(1,report.excludedUntimedRuns());
        assertEquals(1,report.excludedUntimedEndpointAttempts());
        assertEquals(1,report.excludedUntimedRpcResponses());
        assertEquals(0,metric(report,"execution-clients").denominator());
    }

    @Test void repeatedCompatibleClientEvidenceAndRawVersionKeepTheirOwnUnits() throws Exception {
        String path=temp.resolve("clients.db").toString();
        var known=new NodeIdentity("ab".repeat(64));
        var unknown=new NodeIdentity("cd".repeat(64));
        var raw=new NodeIdentity("ef".repeat(64));
        var platform=new NodeIdentity("12".repeat(64));
        try(var store=new SqliteNodeStore(path)) {
            store.saveObservation(observation(known,"discv4","192.0.2.1",START));
            store.saveObservation(observation(unknown,"discv5","192.0.2.2",START));
            store.saveObservation(observation(raw,"discv4","192.0.2.3",START));
            store.saveObservation(observation(platform,"discv4","192.0.2.4",START));
            var node=new NodeRecord("192.0.2.1",30303,30303,known.nodeId());
            for(int i=0;i<2;i++) {
                var at=START.plusSeconds(i+1);
                var evidence=new LinkedHashMap<String,Object>();
                evidence.put("endpointAttempts",List.of(attempt(at.toString(),"PASS","PASS","PASS","NOT_TESTED","Geth/v1.0/Linux")));
                evidence.put("rpc",Map.of("clientVersion","Geth/v2.0/Linux","observedAt",at.toString()));
                store.saveInspectionRun(node,"run-"+i,at.toString(),at.plusSeconds(1).toString(),evidence,null,null,null,List.of(),List.of());
            }
            var rawNode=new NodeRecord("192.0.2.3",30303,30303,raw.nodeId());
            var rawEvidence=new LinkedHashMap<String,Object>();
            rawEvidence.put("rpc",Map.of("clientVersion","Geth","observedAt",START.plusSeconds(3).toString()));
            store.saveInspectionRun(rawNode,"raw",START.plusSeconds(3).toString(),START.plusSeconds(4).toString(),
                    rawEvidence,null,null,null,List.of(),List.of());
            var platformNode=new NodeRecord("192.0.2.4",30303,30303,platform.nodeId());
            var platformEvidence=new LinkedHashMap<String,Object>();
            platformEvidence.put("rpc",Map.of("clientVersion","Other/v1/GethOS","observedAt",START.plusSeconds(4).toString()));
            store.saveInspectionRun(platformNode,"platform",START.plusSeconds(4).toString(),START.plusSeconds(5).toString(),
                    platformEvidence,null,null,null,List.of(),List.of());
            var report=new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END));
            assertEquals(4,metric(report,"observed-identities").denominator());
            assertEquals(2,bucket(metric(report,"execution-clients"),"Geth"));
            assertEquals(1,bucket(metric(report,"execution-clients"),"Other"));
            assertEquals(0,bucket(metric(report,"execution-clients"),"CONFLICT"));
            assertEquals(1,metric(report,"execution-clients").unknown());
            assertEquals(1,bucket(metric(report,"execution-versions"),"Geth/1.0"));
            assertEquals(1,bucket(metric(report,"execution-versions"),"Geth/2.0"));
            assertEquals(1,bucket(metric(report,"execution-versions"),"Geth/UNKNOWN_VERSION"));
            assertTrue(metric(report,"execution-versions").bucketSemantics().contains("overlap"));
        }
    }

    @Test void twoAddressesOfOneIdentityRetainDifferentCountryAsnAndDatasetContexts() throws Exception {
        String path=temp.resolve("geography.db").toString();
        var identity=new NodeIdentity("ab".repeat(64));
        try(var store=new SqliteNodeStore(path)) {
            store.saveObservation(observation(identity,"discv4","192.0.2.1",START));
            store.saveObservation(observation(identity,"discv5","192.0.2.2",START.plusNanos(1)));
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);
                var insert=db.prepareStatement("INSERT INTO network_enrichment_lookups VALUES(?,?,?,?,?)")) {
                String[][] rows={{"a","192.0.2.1","v1","US","64500"},
                        {"b","192.0.2.2","v1","DE","64501"},
                        {"c","192.0.2.1","v2","CA","64502"}};
                for(var row:rows) {
                    insert.setString(1,row[0]);insert.setString(2,row[1]);insert.setString(3,row[2]);
                    insert.setString(4,START.toString());
                    insert.setString(5,"{\"country\":{\"status\":\"FOUND\",\"countryCode\":\""+row[3]+"\"},"+
                            "\"asn\":{\"status\":\"FOUND\",\"asn\":"+row[4]+"},"+
                            "\"hostingClassification\":\"NOT_AVAILABLE\"}");
                    insert.executeUpdate();
                }
            }
            var report=new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END));
            assertEquals(1,metric(report,"observed-identities").denominator());
            assertEquals(2,metric(report,"normalized-addresses").denominator());
            for(String label:List.of("v1:US","v1:DE","v2:CA"))
                assertEquals(1,bucket(metric(report,"country-distribution-top-100"),label));
            for(String label:List.of("v1:64500","v1:64501","v2:64502"))
                assertEquals(1,bucket(metric(report,"asn-distribution-top-100"),label));
            assertEquals(3,bucket(metric(report,"hosting-classification"),"v1:NOT_AVAILABLE")+
                    bucket(metric(report,"hosting-classification"),"v2:NOT_AVAILABLE"));
        }
    }

    @Test void familyBucketsOverlapButCanonicalIdentityAndReceiptCountsDoNot() throws Exception {
        String path=temp.resolve("families.db").toString();
        var ipv4=new NodeIdentity("ab".repeat(64));
        var ipv6=new NodeIdentity("cd".repeat(64));
        var dual=new NodeIdentity("ef".repeat(64));
        try(var store=new SqliteNodeStore(path)) {
            store.saveObservation(observation(ipv4,"discv4","192.0.2.1",START));
            store.saveObservation(observation(ipv4,"discv4","192.0.2.1",START.plusNanos(1)));
            store.saveObservation(observation(ipv6,"discv5","2001:db8::1",START));
            store.saveObservation(observation(dual,"discv4","192.0.2.1",START));
            store.saveObservation(observation(dual,"discv5","2001:db8::2",START));
            var report=new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END));
            assertEquals(3,metric(report,"observed-identities").denominator());
            assertEquals(5,metric(report,"discovery-occurrences").denominator());
            assertEquals(2,bucket(metric(report,"address-families"),"IPV4"));
            assertEquals(2,bucket(metric(report,"address-families"),"IPV6"));
            assertEquals(1,bucket(metric(report,"dual-family"),"both"));
            assertEquals(1,bucket(metric(report,"discv4-discv5-overlap"),"both"));
            assertEquals(2,bucket(metric(report,"normalized-addresses"),"IPV6"));
            assertEquals(1,bucket(metric(report,"normalized-addresses"),"IPV4"));
            assertEquals(0,bucket(metric(report,"ipv6-tcp-pass"),"IPv6 TCP PASS"));
            assertTrue(metric(report,"address-families").bucketSemantics().contains("overlap"));
        }
    }

    @Test void verificationUsesIndependentSourcesAndNeverPromotesOneSource() throws Exception {
        String path=temp.resolve("verification.db").toString();
        var hoodi=Map.<String,Object>of("genesisValidatorsRoot",
                "0x212f13fc4df078b6cb7db228f1c8307566dcecf900867401a92023d7ba99cb5f",
                "genesisTime",1742213400L);
        try(var store=new SqliteNodeStore(path)) {
            for(int i=0;i<3;i++) {
                var at=START.plusSeconds(i);
                var node=new NodeRecord("192.0.2."+(i+1),30303,30303,("ab"+i).repeat(42)+"ab");
                var evidence=new LinkedHashMap<String,Object>();
                evidence.put("rpc",i==2?Map.of("chainId",1L,"networkId","1","observedAt",at.toString()):
                        Map.of("chainId",560048L,"networkId","560048","observedAt",at.toString()));
                if(i>0) {
                    var beacon=new LinkedHashMap<String,Object>(hoodi);
                    beacon.put("observedAt",at.toString());evidence.put("beacon",beacon);
                }
                store.saveInspectionRun(node,"verification-"+i,at.toString(),at.plusNanos(1).toString(),
                        evidence,null,null,null,List.of(),List.of());
            }
            var verification=metric(new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END)),
                    "network-verification");
            assertEquals(1,bucket(verification,"OBSERVED"));
            assertEquals(1,bucket(verification,"VERIFIED"));
            assertEquals(1,bucket(verification,"MISMATCH"));
        }
    }

    @Test void oversizedWindowFailsExplicitlyWithoutAPartialAggregate() throws Exception {
        String path=temp.resolve("bounded.db").toString();
        try(var ignored=new SqliteNodeStore(path)) { }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var statement=db.createStatement()) {
            statement.executeUpdate("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<250001) "+
                    "INSERT INTO discovery_observations(node_id,source,provenance,observed_at,endpoints_json) "+
                    "SELECT '"+"ab".repeat(64)+"','discv4',CAST(x AS TEXT),'2026-01-01T00:00:00Z','[]' FROM n");
        }
        var analytics=new NetworkAnalytics(path);
        var window=assertThrows(SQLException.class,()->analytics.measure(new NetworkAnalytics.Scope(START,END)));
        assertTrue(window.getMessage().contains("250,000 evidence-row safety limit"));
        var all=assertThrows(SQLException.class,()->analytics.measure(NetworkAnalytics.Scope.allAvailable()));
        assertTrue(all.getMessage().contains("250,000 evidence-row safety limit"));
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var statement=db.createStatement();
            var rows=statement.executeQuery("SELECT COUNT(*) FROM discovery_observations")) {
            assertTrue(rows.next());assertEquals(250001,rows.getLong(1));
        }
    }

    @Test void analyticsCannotCreateAMissingDatabaseOrMutateExistingEvidence() throws Exception {
        Path absent=temp.resolve("missing.db");
        var missing=new NetworkAnalytics(absent.toString());
        assertThrows(SQLException.class,missing::snapshot);
        assertThrows(SQLException.class,()->missing.measure(new NetworkAnalytics.Scope(START,END)));
        assertFalse(Files.exists(absent));

        Path path=temp.resolve("readonly.db");
        try(var store=new SqliteNodeStore(path.toString())) {
            store.saveObservation(observation(new NodeIdentity("ab".repeat(64)),"discv4","192.0.2.1",START));
        }
        long[] before;
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var statement=db.createStatement()) {
            before=new long[]{count(statement,"discovery_observations"),count(statement,"nodes"),
                    count(statement,"change_events"),count(statement,"sqlite_master")};
        }
        var analytics=new NetworkAnalytics(path.toString());
        assertEquals(1,metric(analytics.measure(new NetworkAnalytics.Scope(START,END)),"observed-identities").denominator());
        analytics.snapshot();
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var statement=db.createStatement()) {
            assertArrayEquals(before,new long[]{count(statement,"discovery_observations"),count(statement,"nodes"),
                    count(statement,"change_events"),count(statement,"sqlite_master")});
        }
    }

    @Test void verificationRunLimitFailsTheWholeReportBeforeAnyPartialResult() throws Exception {
        String path=temp.resolve("verification-bound.db").toString();
        try(var ignored=new SqliteNodeStore(path)) { }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var statement=db.createStatement()) {
            statement.executeUpdate("INSERT INTO inspection_evidence VALUES('shared','{\"endpointAttempts\":[]}')");
            statement.executeUpdate("WITH RECURSIVE n(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM n WHERE x<25001) "+
                    "INSERT INTO inspection_runs(id,node_id,node_key,started_at,completed_at,trigger,discovery_source,evidence_hash,timing_json) "+
                    "SELECT 'run'||x,'"+"ab".repeat(64)+"','key','2026-01-01T00:00:00Z','2026-01-01T00:00:01Z',"+
                    "'fixture','discv4','shared','{}' FROM n");
        }
        var error=assertThrows(SQLException.class,()->new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END)));
        assertTrue(error.getMessage().contains("25,000 inspections"));
    }

    @Test void analyticsAfterClearAndReopenUsesOnlyTheNewGenerationEvidence() throws Exception {
        String path=temp.resolve("restart.db").toString();
        var oldIdentity=new NodeIdentity("ab".repeat(64));
        var newIdentity=new NodeIdentity("cd".repeat(64));
        try(var store=new SqliteNodeStore(path)) {
            store.saveObservation(observation(oldIdentity,"discv4","192.0.2.1",START));
            assertEquals(1,metric(new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END)),
                    "observed-identities").denominator());
            store.clearCollectedData();
        }
        assertEquals(0,metric(new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END)),
                "observed-identities").denominator());
        try(var reopened=new SqliteNodeStore(path)) {
            reopened.saveObservation(observation(newIdentity,"discv5","2001:db8::2",START.plusSeconds(1)));
        }
        var report=new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END));
        assertEquals(1,metric(report,"observed-identities").denominator());
        assertEquals(0,bucket(metric(report,"discovery-providers"),"discv4"));
        assertEquals(1,bucket(metric(report,"discovery-providers"),"discv5"));
    }

    @Test void inspectionOnlyIdentityRemainsUnknownForMissingDomainEvidence() throws Exception {
        String path=temp.resolve("unknown-domains.db").toString();
        var at=START.plusSeconds(1);
        var node=new NodeRecord("192.0.2.1",30303,30303,"ab".repeat(64));
        try(var store=new SqliteNodeStore(path)) {
            var evidence=new LinkedHashMap<String,Object>();
            evidence.put("rpc",Map.of("observedAt",at.toString()));
            evidence.put("beacon",Map.of("observedAt",at.toString()));
            store.saveInspectionRun(node,"inspection-only",at.toString(),at.plusSeconds(1).toString(),
                    evidence,null,null,null,List.of(),List.of());
        }
        var report=new NetworkAnalytics(path).measure(new NetworkAnalytics.Scope(START,END));
        assertEquals(1,metric(report,"observed-identities").denominator());
        for(String id:List.of("discovery-providers","discv4-discv5-overlap","address-families","dual-family"))
            assertEquals(1,metric(report,id).unknown(),id);
        for(String id:List.of("rpc-chainId","rpc-networkId","beacon-clients")) {
            assertEquals(1,metric(report,id).denominator(),id);
            assertEquals(1,metric(report,id).unknown(),id);
        }
    }

    @Test void latestProjectionSnapshotExcludesNonCryptographicLegacyIdentifiers() throws Exception {
        String path=temp.resolve("legacy-projection.db").toString();
        try(var store=new SqliteNodeStore(path)) {
            store.save(new NodeRecord("192.0.2.1",30303,30303,"legacy-id"));
        }
        var snapshot=new NetworkAnalytics(path).snapshot();
        assertEquals(1,snapshot.latestProjectionRows());
        assertEquals(0,snapshot.distinctProjectionIdentities());
    }

    private static long count(java.sql.Statement statement,String table) throws SQLException {
        try(var rows=statement.executeQuery("SELECT COUNT(*) FROM "+table)) {
            rows.next();return rows.getLong(1);
        }
    }
}
