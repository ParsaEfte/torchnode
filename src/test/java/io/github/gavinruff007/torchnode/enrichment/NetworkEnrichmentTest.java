package io.github.gavinruff007.torchnode.enrichment;

import io.github.gavinruff007.torchnode.analysis.EndpointAnalysis;
import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkEnrichmentTest {
    @TempDir Path temp;
    static class Fixture implements NetworkEnrichmentProvider {
        final String version;final AtomicInteger calls=new AtomicInteger();Fixture(String version){this.version=version;}
        public String datasetKey(){return version;}
        public NetworkEnrichment lookup(String address) {
            calls.incrementAndGet();if(address.equals("9.9.9.9"))throw new IllegalStateException("fixture failure");
            if(address.equals("8.8.4.4"))return state(address,version,Status.NOT_FOUND,"fixture miss");
            boolean v6=EndpointAddress.family(address)==NodeEndpoint.AddressFamily.IPV6;
            var country=new Lookup(Status.FOUND,null,v6?"DE":"GB",v6?"Germany":"United Kingdom",null,null,v6?"2606:4700::/32":"1.2.3.0/24",Instant.now().toString(),"fixture country",version,"deterministic test, not public geography");
            var asn=new Lookup(Status.FOUND,null,null,null,v6?64501L:64500L,"Fixture Cloud",country.networkPrefix(),country.lookedUpAt(),"fixture ASN",version,"deterministic test, no hosting classification");
            return new NetworkEnrichment(address,EndpointAddress.family(address),version,country,asn,"NOT_AVAILABLE");
        }
    }
    static DiscoveryObservation observation(NodeIdentity id,String address,String source) {
        return new DiscoveryObservation(id,source,List.of(new NodeEndpoint(address,NodeEndpoint.Transport.UDP,30301,EndpointAddress.family(address),NodeEndpoint.Purpose.DISCOVERY),new NodeEndpoint(address,NodeEndpoint.Transport.TCP,30303,EndpointAddress.family(address),NodeEndpoint.Purpose.P2P)),Instant.parse("2026-09-29T00:00:00Z"),"fixture source "+source);
    }
    static NetworkEnrichment get(NetworkEnrichmentService service,String address) throws Exception {return service.request(address).get(5,TimeUnit.SECONDS);}
    @Test void publicFamiliesDualStackIdentityChangedEndpointAndReuseSurviveReopen() throws Exception {
        String path=temp.resolve("roundtrip.db").toString();var id=new NodeIdentity("ab".repeat(64));var other=new NodeIdentity("cd".repeat(64));var fixture=new Fixture("fixture-v1");NetworkEnrichment v4,v6;
        List<Map<String,Object>> before;
        try(var service=new NetworkEnrichmentService(path,fixture);var store=new SqliteNodeStore(path)) {
            var a=observation(id,"1.2.3.4","discv4");var b=observation(id,"2606:4700::1111","discv5");store.saveObservation(a);store.saveObservation(b);store.saveObservation(b);
            assertEquals(1,CanonicalNodes.views(store.findAll()).size());
            v4=get(service,"1.2.3.4");v6=get(service,"2606:4700::1111");assertEquals(v6,get(service,"2606:4700:0:0:0:0:0:1111"));assertEquals(2,fixture.calls.get());
            assertEquals(Status.FOUND,v4.country().status());assertEquals(Status.FOUND,v4.asn().status());assertEquals(NodeEndpoint.AddressFamily.IPV6,v6.addressFamily());assertEquals("DE",v6.country().countryCode());assertEquals("GB",v4.country().countryCode());assertEquals("NOT_AVAILABLE",v4.hostingClassification());
            var oldAnalysis=EndpointAnalysis.fromStore(store,id);assertTrue(oldAnalysis.comparisons().stream().noneMatch(c->c.outcome()==EndpointAnalysis.Outcome.MISMATCH));
            before=store.networkEnrichmentView(id);assertTrue(before.stream().anyMatch(e->e.get("source").equals("discv4")));assertTrue(before.stream().anyMatch(e->e.get("source").equals("discv5")));
            store.saveObservation(observation(other,"1.2.3.4","discv4"));assertEquals(v4,get(service,"1.2.3.4"));assertEquals(2,CanonicalNodes.views(store.findAll()).size());assertEquals(2,fixture.calls.get());
            assertEquals(oldAnalysis,EndpointAnalysis.fromStore(store,id));
            store.saveObservation(observation(id,"5.6.7.8","discv4"));assertTrue(store.findNetworkEnrichment("5.6.7.8",null).isEmpty());assertTrue(store.networkEnrichmentView(id).stream().anyMatch(e->((NodeEndpoint)e.get("endpoint")).address().equals("5.6.7.8") && e.get("enrichment")==null));
        }
        var reopened=new Fixture("fixture-v1");try(var service=new NetworkEnrichmentService(path,reopened);var store=new SqliteNodeStore(path)) {
            assertEquals(v4,get(service,"1.2.3.4"));assertEquals(v6,get(service,"2606:4700::1111"));assertEquals(0,reopened.calls.get());
            for(var view:before)assertTrue(store.networkEnrichmentView(id).contains(view));assertEquals(2,CanonicalNodes.views(store.findAll()).size());
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var s=c.createStatement()){try(var r=s.executeQuery("pragma integrity_check")){assertTrue(r.next());assertEquals("ok",r.getString(1));}try(var r=s.executeQuery("pragma foreign_key_check")){assertFalse(r.next());}}
        }
        var changed=new Fixture("fixture-v2");try(var service=new NetworkEnrichmentService(path,changed);var store=new SqliteNodeStore(path)){assertEquals("fixture-v2",get(service,"1.2.3.4").country().dataSourceVersion());assertEquals(1,changed.calls.get());assertEquals(v4,store.findNetworkEnrichment("1.2.3.4","fixture-v1").orElseThrow());}
    }
    @Test void nonPublicMappedReservedAndInvalidAddressesNeverReachProvider() throws Exception {
        var fixture=new Fixture("fixture-v1");try(var service=new NetworkEnrichmentService(temp.resolve("local.db").toString(),fixture)) {
            for(String ip:List.of("10.1.2.3","172.16.1.1","192.168.1.1","127.0.0.1","169.254.1.1","0.0.0.0","224.0.0.1","100.64.1.1","203.0.113.1","::1","::","fc00::1","fe80::1","ff02::1","::ffff:8.8.8.8","2001:db8::1")){var value=get(service,ip);assertEquals(Status.NOT_APPLICABLE,value.country().status(),ip);assertNotNull(value.country().reason());assertNull(value.country().countryCode());}
            assertEquals(0,fixture.calls.get());assertThrows(IllegalArgumentException.class,()->service.request("not-an-ip"));assertThrows(IllegalArgumentException.class,()->service.request("[2606:4700::1111]:30303"));
        }
    }
    @Test void missFailureAndUnavailableRemainIndependentFromEndpointAnalysisAndP2p() throws Exception {
        String path=temp.resolve("failure.db").toString();var id=new NodeIdentity("ab".repeat(64));var obs=observation(id,"9.9.9.9","discv5");
        try(var store=new SqliteNodeStore(path);var service=new NetworkEnrichmentService(path,new Fixture("fixture-v1"))) {
            store.saveObservation(obs);store.saveEndpointInspection(new NodeRecord(obs).getKey(),Map.of("listenPort",30303,"clientId","authenticated fixture"),Map.of("networkId",1),List.of());
            var before=EndpointAnalysis.fromStore(store,id);assertEquals(Status.NOT_FOUND,get(service,"8.8.4.4").country().status());assertEquals(Status.LOOKUP_FAILED,get(service,"9.9.9.9").asn().status());assertEquals(before,EndpointAnalysis.fromStore(store,id));assertEquals(1,store.findObservations(id).size());
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var s=c.createStatement();var r=s.executeQuery("select hello_json,status_json from p2p_observations")){assertTrue(r.next());assertTrue(r.getString(1).contains("authenticated fixture"));assertTrue(r.getString(2).contains("networkId"));}
        }
        for(Path dataset:Arrays.asList(null,temp.resolve("absent.mmdb"),Files.writeString(temp.resolve("invalid.mmdb"),"not MMDB")))try(var provider=new OfflineGeoIpProvider(dataset,dataset)) {
            var value=provider.lookup("1.2.3.4");assertEquals(Status.DATASET_UNAVAILABLE,value.country().status());assertEquals(Status.DATASET_UNAVAILABLE,value.asn().status());assertEquals(Status.NOT_APPLICABLE,provider.lookup("::1").country().status());assertEquals("NOT_AVAILABLE",value.hostingClassification());
        }
    }
    @Test void concurrentDeduplicationAndCacheBounds() throws Exception {
        var fixture=new Fixture("fixture-v1");try(var service=new NetworkEnrichmentService(temp.resolve("cache.db").toString(),fixture)) {
            var executor=Executors.newFixedThreadPool(8);try {var requests=new ArrayList<Future<NetworkEnrichment>>();for(int i=0;i<64;i++)requests.add(executor.submit(()->get(service,"1.2.3.4")));for(var request:requests)assertEquals(Status.FOUND,request.get(5,TimeUnit.SECONDS).country().status());}finally{executor.shutdownNow();}
            assertEquals(1,fixture.calls.get());
            for(int i=0;i<1030;i++)get(service,"11."+(i/256)+"."+(i%256)+".1");assertTrue((int)service.metrics().get("cacheSize")<=1024);assertEquals(0L,service.metrics().get("rejections"));
            get(service,"1.2.3.4");assertEquals(1031,fixture.calls.get()); // Evicted address reloads persisted evidence, no second provider call.
        }
    }
    @Test void migrationUpgradePreservesRowsAndFailureRollsBackAtomically() throws Exception {
        String path=temp.resolve("legacy.db").toString();var id=new NodeIdentity("ab".repeat(64));var obs=observation(id,"1.2.3.4","discv4");
        try(var store=new SqliteNodeStore(path)){store.saveObservation(obs);}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var s=c.createStatement()){s.execute("drop table network_enrichment");s.execute("delete from schema_migrations where version=3");s.execute("create trigger reject_v3 before insert on schema_migrations when NEW.version=3 begin select raise(ABORT,'fixture migration failure');end");}
        assertThrows(SQLException.class,()->new SqliteNodeStore(path));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var s=c.createStatement()){try(var r=s.executeQuery("select count(*) from sqlite_master where name='network_enrichment'")){assertTrue(r.next());assertEquals(0,r.getInt(1));}s.execute("drop trigger reject_v3");}
        try(var store=new SqliteNodeStore(path)){assertEquals(1,store.count());assertEquals(List.of(obs),store.findObservations(id));assertTrue(store.findNetworkEnrichment("1.2.3.4",null).isEmpty());}
    }
}
