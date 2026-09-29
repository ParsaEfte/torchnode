import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gavinruff007.torchnode.enrichment.*;
import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.analysis.EndpointAnalysis;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in bounded validation. All databases/data files must be isolated copies/fixtures. */
public class ValidateGeoIp {
    static final ObjectMapper JSON=new ObjectMapper();
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[0]);
        if(args.length==1)ValidateActiveIpv6.main(args); // Existing 45s + 12s two-provider lifecycle bounds.
        var report=new LinkedHashMap<String,Object>();
        report.put("historicalCopy",enrich(directory.resolve("historical-copy.db").toString()));
        report.put("freshPublicScan",enrich(directory.resolve("public-scan.db").toString()));
        Path fixtures=directory.resolve("mmdb-fixtures");
        try(var provider=new OfflineGeoIpProvider(fixtures.resolve("GeoIP2-Country-Test.mmdb"),fixtures.resolve("GeoLite2-ASN-Test.mmdb"))) {
            var evidence=new ArrayList<NetworkEnrichment>();
            for(String address:List.of("2.125.160.216","2001:218::","1.0.0.1","2001:1700::","8.8.8.8","10.0.0.1"))evidence.add(provider.lookup(address));
            for(var family:NodeEndpoint.AddressFamily.values())for(boolean country:List.of(true,false))
                if(evidence.stream().noneMatch(e->e.addressFamily()==family && (country?e.country():e.asn()).status()==NetworkEnrichment.Status.FOUND))throw new AssertionError("MMDB fixture proof missing "+family+" "+(country?"country":"ASN"));
            report.put("SYNTHETIC / OFFICIAL TEST-DATABASE VALIDATION",evidence);
            report.put("fixtureManifest",JSON.readTree(fixtures.resolve("manifest.json").toFile()));
        }
        report.put("boundedPublicP2pInspections",inspect(directory.resolve("public-scan.db").toString()));
        report.put("completedAt",java.time.Instant.now().toString());
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("geoip-asn-report.json").toFile(),report);
        System.out.println("GeoIP/ASN report: "+directory.resolve("geoip-asn-report.json"));
    }
    static Map<String,Object> enrich(String path) throws Exception {
        var report=new LinkedHashMap<String,Object>();var addresses=new TreeSet<String>();var identities=new LinkedHashMap<String,Set<String>>();var baseline=new LinkedHashMap<String,EndpointAnalysis.Result>();
        try(var store=new SqliteNodeStore(path)) {
            for(var node:CanonicalNodes.views(store.findAll())) {
                var analysis=EndpointAnalysis.fromStore(store,node.identity());baseline.put(node.getNodeId(),analysis);
                var perIdentity=new TreeSet<String>();analysis.evidence().stream().filter(e->e.endpoint()!=null).forEach(e->perIdentity.add(e.endpoint().address()));addresses.addAll(perIdentity);identities.put(node.getNodeId(),perIdentity);
            }
        }
        if(addresses.size()>4096)throw new IllegalArgumentException("Offline validation bound exceeded (4096 addresses)");
        report.put("canonicalIdentities",identities.size());report.put("uniqueObservedAddresses",addresses.size());
        report.put("eligiblePublicIpv4",addresses.stream().filter(a->EndpointAddress.family(a)==NodeEndpoint.AddressFamily.IPV4 && PublicAddress.exclusion(a)==null).count());
        report.put("eligiblePublicIpv6",addresses.stream().filter(a->EndpointAddress.family(a)==NodeEndpoint.AddressFamily.IPV6 && PublicAddress.exclusion(a)==null).count());
        var states=new TreeMap<String,Long>();var countries=new TreeSet<String>();var asns=new TreeSet<Long>();var results=new HashMap<String,NetworkEnrichment>();
        var service=new NetworkEnrichmentService(path);
        try {
            for(String address:addresses) {
                var value=service.request(address).get(5,TimeUnit.SECONDS);results.put(address,value);
                states.merge("COUNTRY_"+value.country().status(),1L,Long::sum);states.merge("ASN_"+value.asn().status(),1L,Long::sum);
                if(value.country().countryCode()!=null)countries.add(value.country().countryCode());if(value.asn().asn()!=null)asns.add(value.asn().asn());
                if(!value.equals(service.request(address).get(5,TimeUnit.SECONDS)))throw new AssertionError("Cache changed evidence");
            }
            report.put("lookupStates",states);report.put("distinctCountries",countries.size());report.put("distinctAsns",asns.size());report.put("reuseBeforeStop",service.metrics());
        } finally {service.close();report.put("enrichmentAfterStop",service.metrics());}
        report.put("dualStackIdentitiesWithBothFamiliesFound",identities.values().stream().filter(set->Arrays.stream(NodeEndpoint.AddressFamily.values()).allMatch(family->set.stream().map(results::get).anyMatch(e->e.addressFamily()==family && e.country().status()==NetworkEnrichment.Status.FOUND && e.asn().status()==NetworkEnrichment.Status.FOUND))).count());
        try(var store=new SqliteNodeStore(path)) {
            boolean unchanged=true,roundtrip=true;for(var entry:baseline.entrySet())unchanged &= entry.getValue().equals(EndpointAnalysis.fromStore(store,new NodeIdentity(entry.getKey())));
            for(var entry:results.entrySet())roundtrip &= entry.getValue().equals(store.findNetworkEnrichment(entry.getKey(),entry.getValue().datasetKey()).orElseThrow());
            report.put("endpointNatConclusionsUnchanged",unchanged);report.put("reopenExactEnrichmentEquality",roundtrip);
        }
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var s=c.createStatement()) {
            for(String table:List.of("nodes","discovery_observations","enr_observations","p2p_observations","network_enrichment"))report.put(table,ValidateActiveIpv6.query(s,"select count(*) from "+table));
            report.put("integrity",ValidateActiveIpv6.query(s,"pragma integrity_check"));report.put("foreignKeys",ValidateActiveIpv6.query(s,"pragma foreign_key_check"));report.put("migrations",ValidateActiveIpv6.query(s,"select version from schema_migrations order by version"));
        }
        return report;
    }
    static List<Map<String,Object>> inspect(String path) throws Exception {
        List<NodeRecord> nodes;try(var store=new SqliteNodeStore(path)){nodes=CanonicalNodes.views(store.findAll()).stream().filter(n->n.p2pEndpoints().stream().anyMatch(e->e.addressFamily()==NodeEndpoint.AddressFamily.IPV4 && e.port()==30303)).sorted(Comparator.comparing(NodeRecord::getNodeId)).limit(2).toList();}
        var report=new ArrayList<Map<String,Object>>();
        for(var node:nodes) {
            var inspection=new io.github.gavinruff007.torchnode.inspection.InspectionService(path);var run=new LinkedHashMap<String,Object>();
            try {
                String id=inspection.inspect(node);long deadline=System.nanoTime()+40_000_000_000L;Map<String,Object> snapshot;
                do{Thread.sleep(100);snapshot=inspection.snapshot(id).orElseThrow();}while(!Boolean.TRUE.equals(snapshot.get("complete")) && System.nanoTime()<deadline);
                run.put("snapshot",snapshot);
            } finally {
                long start=System.nanoTime();inspection.close();run.put("stopMs",(System.nanoTime()-start)/1_000_000);
                var executor=(java.util.concurrent.ThreadPoolExecutor)ValidateActiveIpv6.field(inspection,"executor");
                run.put("workersAfterStop",executor.getPoolSize());run.put("activeAfterStop",executor.getActiveCount());run.put("queuedAfterStop",executor.getQueue().size());run.put("terminated",executor.isTerminated());
                run.put("enrichmentAfterStop",((NetworkEnrichmentService)ValidateActiveIpv6.field(inspection,"enrichment")).metrics());
                run.put("tcpSocketsAfterStop",((Set<?>)ValidateActiveIpv6.field(inspection,"activeSockets")).size());
                run.put("httpCallsAfterStop",((Set<?>)ValidateActiveIpv6.field(ValidateActiveIpv6.field(inspection,"rpcProber"),"active")).size()+((Set<?>)ValidateActiveIpv6.field(ValidateActiveIpv6.field(inspection,"beaconProber"),"active")).size());
                run.put("helpersAfterStop",ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count());
            }
            report.add(run);
        }
        return report;
    }

}
