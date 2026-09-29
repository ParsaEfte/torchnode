import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gavinruff007.torchnode.analysis.EndpointAnalysis;
import io.github.gavinruff007.torchnode.inspection.InspectionService;
import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Opt-in public validation: reuse existing bounded scan/lifecycle harness; inspect at most two identities. */
public class ValidateEndpointNat {
    static final ObjectMapper JSON=new ObjectMapper();
    public static void main(String[] args) throws Exception {
        ValidateActiveIpv6.main(args);
        Path directory=Path.of(args[0]);String path=directory.resolve("public-scan.db").toString();
        var report=new LinkedHashMap<String,Object>();
        var inspections=new ArrayList<Map<String,Object>>();
        var inspectionLifecycle=new ArrayList<Map<String,Object>>();
        List<NodeRecord> candidates;
        try(var store=new SqliteNodeStore(path)) {
            candidates=CanonicalNodes.views(store.findAll()).stream().filter(n->n.p2pEndpoints().stream()
                    .anyMatch(e->e.addressFamily()==NodeEndpoint.AddressFamily.IPV4 && e.port()==30303 && EndpointAddress.activeTarget(e.address())))
                    .sorted(Comparator.comparing(NodeRecord::getNodeId)).limit(2).toList();
        }
        for(var node:candidates) {
            var service=new InspectionService(path);
            try {
                String id=service.inspect(node);long deadline=System.nanoTime()+40_000_000_000L;
                Map<String,Object> snapshot;
                do { Thread.sleep(100);snapshot=service.snapshot(id).orElseThrow(); }
                while(!Boolean.TRUE.equals(snapshot.get("complete")) && System.nanoTime()<deadline);
                inspections.add(snapshot);
            } finally {
                long start=System.nanoTime();service.close();
                var life=new LinkedHashMap<String,Object>();life.put("stopMs",(System.nanoTime()-start)/1_000_000);
                var executor=(java.util.concurrent.ThreadPoolExecutor)field(service,"executor");
                life.put("terminated",executor.isTerminated());life.put("queuedTasks",executor.getQueue().size());life.put("activeTasks",executor.getActiveCount());life.put("workers",executor.getPoolSize());
                life.put("tcpSockets",((Set<?>)field(service,"activeSockets")).size());
                life.put("httpCalls",((Set<?>)field(field(service,"rpcProber"),"active")).size()+((Set<?>)field(field(service,"beaconProber"),"active")).size());
                life.put("helperProcesses",ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count());inspectionLifecycle.add(life);
            }
        }
        report.put("boundedPublicInspections",inspections);report.put("inspectionLifecycle",inspectionLifecycle);
        var before=analyses(path);var after=analyses(path);
        report.put("reopenDerivedEquality",before.equals(after));
        report.put("canonicalIdentities",before.size());
        var outcomes=new TreeMap<String,Long>();var nat=new TreeMap<String,Long>();var family=new TreeMap<String,Long>();
        long comparable=0,helloComparable=0,helloMatch=0,helloMismatch=0;
        var examples=new ArrayList<Map<String,Object>>();
        for(var result:before) {
            if(result.comparisons().stream().anyMatch(c->List.of(EndpointAnalysis.Outcome.MATCH,EndpointAnalysis.Outcome.MISMATCH).contains(c.outcome()))) comparable++;
            nat.merge(result.natEvidence().name(),1L,Long::sum);
            for(var c:result.comparisons()) {
                outcomes.merge(c.outcome().name(),1L,Long::sum);
                if(c.label().startsWith("Hello") && List.of(EndpointAnalysis.Outcome.MATCH,EndpointAnalysis.Outcome.MISMATCH).contains(c.outcome())) {
                    helloComparable++;if(c.outcome()==EndpointAnalysis.Outcome.MATCH)helloMatch++;else helloMismatch++;
                }
                var left=result.evidence().stream().filter(e->e.id().equals(c.leftEvidence())).findFirst();
                if(left.isPresent() && left.get().endpoint()!=null)family.merge(left.get().endpoint().addressFamily().name()+"_"+c.outcome().name(),1L,Long::sum);
            }
            if(examples.size()<5 && result.comparisons().stream().anyMatch(c->c.outcome()==EndpointAnalysis.Outcome.MISMATCH))examples.add(result.toMap());
        }
        report.put("nodesWithComparableEvidence",comparable);report.put("comparisonOutcomes",outcomes);report.put("natAssessments",nat);
        report.put("helloPortComparable",helloComparable);report.put("helloPortMatch",helloMatch);report.put("helloPortMismatch",helloMismatch);
        report.put("familySpecificComparisons",family);report.put("mismatchExamples",examples);
        report.put("postInspectionChildProcesses",ProcessHandle.current().descendants().filter(ProcessHandle::isAlive).count());
        report.put("postInspectionWorkerThreads",Thread.getAllStackTraces().keySet().stream().filter(t->t.isAlive() && t.getName().startsWith("inspection-")).map(Thread::getName).toList());
        byte[] stopped=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(path)));Thread.sleep(1000);
        report.put("noLateInspectionMutation",Arrays.equals(stopped,java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(path)))));
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var s=c.createStatement()) {
            report.put("integrity",ValidateActiveIpv6.query(s,"pragma integrity_check"));report.put("foreignKeys",ValidateActiveIpv6.query(s,"pragma foreign_key_check"));
        }
        report.put("historicalAnalysis",summarizeHistorical(directory.resolve("historical-copy.db").toString()));
        JSON.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("endpoint-nat-report.json").toFile(),report);
        System.out.println("Endpoint analysis report: "+directory.resolve("endpoint-nat-report.json"));
    }
    static Object field(Object object,String name) throws Exception { var field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object); }
    static List<EndpointAnalysis.Result> analyses(String path) throws Exception {
        try(var store=new SqliteNodeStore(path)) {
            var results=new ArrayList<EndpointAnalysis.Result>();
            for(var node:CanonicalNodes.views(store.findAll()))results.add(EndpointAnalysis.fromStore(store,node.identity()));
            return results;
        }
    }
    static Map<String,Object> summarizeHistorical(String path) throws Exception {
        var report=new LinkedHashMap<String,Object>();var values=analyses(path);report.put("canonicalIdentities",values.size());
        report.put("reopenDerivedEquality",values.equals(analyses(path)));
        report.put("legacyHelloClaimsWithoutSessionTarget",values.stream().flatMap(r->r.evidence().stream()).filter(e->e.source().equals("Retained Hello")).count());
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var s=c.createStatement()) {
            for(String table:List.of("nodes","discovery_observations","enr_observations","p2p_observations"))report.put(table,ValidateActiveIpv6.query(s,"select count(*) from "+table));
            report.put("integrity",ValidateActiveIpv6.query(s,"pragma integrity_check"));report.put("foreignKeys",ValidateActiveIpv6.query(s,"pragma foreign_key_check"));
        }
        return report;
    }
}
