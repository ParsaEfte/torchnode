package io.github.gavinruff007.torchnode.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gavinruff007.torchnode.inspection.*;
import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.security.AuthStore;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NodeDetailLoadingTest {
    @TempDir Path temp;
    static final ObjectMapper JSON=new ObjectMapper();
    static HttpResponse<String> get(HttpClient client,int port,String path)throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).build(),HttpResponse.BodyHandlers.ofString());
    }
    static String key(NodeRecord node){return AuthTestSupport.encode(node.getKey());}
    @Test void storedEvidenceIsIndependentOfScannerAndJobsAndAllReadsAuthenticate()throws Exception {
        String db=temp.resolve("nodes.db").toString();
        var first=new NodeRecord("192.0.2.1",30303,30303,"ab".repeat(64));
        var second=new NodeRecord("2001:db8::1",30303,30303,"cd".repeat(64));
        var third=new NodeRecord("192.0.2.3",0,0,"ef".repeat(64));
        try(var store=new SqliteNodeStore(db)) {
            store.save(first);store.save(second);store.save(third);
            var evidence=Map.<String,Object>of("diagnostics",List.of(Map.of("name","P2P TCP","state","PASS"),Map.of("name","ETH Status","state","NOT_TESTED")),
                    "rpc",Map.of("clientVersion","Geth/v1.0/Linux","endpoint","http://192.0.2.1:8545"),
                    "beacon",Map.of("version","Lighthouse/v1","endpoint","http://192.0.2.1:5052"));
            store.saveInspectionRun(first,"saved-run","2026-01-01T00:00:00Z","2026-01-01T00:00:01Z",evidence,null,null,null,List.of(),List.of());
        }
        class Scanner extends ScannerService {
            boolean running=true; Scanner(){super(db);}
            @Override public synchronized boolean isRunning(){return running;}
            @Override public synchronized void stop(){running=false;}
        }
        var scanner=new Scanner();var inspections=new InspectionService(db);
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var server=new DashboardServer(port,db,"127.0.0.1",false,scanner,inspections);server.start();
        try {
            var anonymous=HttpClient.newHttpClient();
            for(String path:List.of("/inspection/current.json?key="+key(first),"/inspection/history?key="+key(first),"/inspection/status?id=missing","/inspection/changes?key="+key(first),"/inspection/evidence?domain=discovery&key="+key(first))) {
                var response=get(anonymous,port,path);assertEquals(401,response.statusCode(),path);assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));assertFalse(response.headers().firstValue("Location").isPresent());
            }
            var admin=AuthTestSupport.client(db,port);
            for(var node:List.of(first,second,third)) {
                var page=get(admin,port,"/node?key="+key(node));assertEquals(200,page.statusCode());assertTrue(page.body().contains("◈ TorchNode"));assertFalse(page.body().contains("â"));assertTrue(page.body().contains("Discovery scan running"));
                var response=get(admin,port,"/inspection/current.json?key="+key(node));assertEquals(200,response.statusCode(),response.body());assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
                var data=JSON.readTree(response.body());assertTrue(data.path("scannerRunning").asBoolean());assertEquals("",data.path("activeInspectionId").asText());assertTrue(data.path("canInspect").asBoolean());assertTrue(data.at("/data/complete").asBoolean());
                assertEquals(node.getIp(),data.at("/data/node/ip").asText());assertEquals(node==first,data.at("/data/hasStoredInspection").asBoolean());
                for(var stage:data.at("/data/diagnostics"))assertNotEquals("CHECKING",stage.path("state").asText());
                assertTrue(inspections.activeId(node.getKey()).isEmpty());
            }
            var data=JSON.readTree(get(admin,port,"/inspection/current.json?key="+key(first)).body());
            assertEquals("saved-run",data.at("/data/id").asText());assertEquals("2026-01-01T00:00:00Z",data.at("/data/startedAt").asText());assertEquals("http://192.0.2.1:5052",data.at("/data/node/beaconEndpoint").asText());
            scanner.running=false;
            assertFalse(JSON.readTree(get(admin,port,"/inspection/current.json?key="+key(first)).body()).path("scannerRunning").asBoolean());
            assertEquals(404,get(admin,port,"/inspection/status?id=missing").statusCode());
            assertEquals(400,get(admin,port,"/inspection/current.json").statusCode());
            for(String domain:List.of("discovery","enr","enrichment"))assertTrue(JSON.readTree(get(admin,port,"/inspection/evidence?domain="+domain+"&key="+key(first)).body()).isArray());
            assertTrue(JSON.readTree(get(admin,port,"/inspection/history?key="+key(first)).body()).isArray());
            assertTrue(JSON.readTree(get(admin,port,"/inspection/changes?key="+key(first)).body()).isArray());
            assertEquals(403,AuthTestSupport.post(admin,port,"/inspection/start","key="+key(first)+"&csrf=wrong").statusCode());
            var auth=new AuthStore(db);String password=UUID.randomUUID().toString();auth.create("test-admin","viewer",password,"VIEWER","test");
            var viewer=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();AuthTestSupport.login(viewer,port,"viewer",password);
            assertFalse(JSON.readTree(get(viewer,port,"/inspection/current.json?key="+key(first)).body()).path("canInspect").asBoolean());
            String token=AuthTestSupport.token(get(viewer,port,"/").body());assertEquals(403,AuthTestSupport.post(viewer,port,"/inspection/start","key="+key(first)+"&csrf="+token).statusCode());
            try(var store=new SqliteNodeStore(db,true)){assertEquals(1,store.inspectionHistory(first.identity(),20,null).size());assertTrue(store.inspectionHistory(second.identity(),20,null).isEmpty());}
        } finally {server.stop();}
    }
    @Test void explicitPostStartsJsonJobAndReadRequestsOnlyObserveItsState()throws Exception {
        String db=temp.resolve("jobs.db").toString();var node=new NodeRecord("192.0.2.1",0,0,"ab".repeat(64));
        try(var store=new SqliteNodeStore(db)){store.save(node);}
        class Jobs extends InspectionService {
            InspectionResult job;int starts;
            Jobs(){super(db);}
            @Override public String inspect(NodeRecord node){starts++;job=new InspectionResult("controlled-job",node);return job.id();}
            @Override public Optional<String> activeId(String key){return job!=null && !job.isComplete()?Optional.of(job.id()):Optional.empty();}
            @Override public Optional<Map<String,Object>> snapshot(String id){return job!=null && job.id().equals(id)?Optional.of(job.snapshot()):Optional.empty();}
        }
        var jobs=new Jobs();int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var server=new DashboardServer(port,db,"127.0.0.1",false,new ScannerService(db),jobs);server.start();
        try {
            var client=AuthTestSupport.client(db,port);String csrf=AuthTestSupport.token(get(client,port,"/").body());
            assertEquals(200,get(client,port,"/node?key="+key(node)).statusCode());assertEquals(0,jobs.starts);
            var started=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/inspection/start")).header("Accept","application/json").header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString("key="+key(node)+"&csrf="+csrf)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,started.statusCode());assertEquals("controlled-job",JSON.readTree(started.body()).path("id").asText());
            assertEquals("controlled-job",JSON.readTree(get(client,port,"/inspection/current.json?key="+key(node)).body()).path("activeInspectionId").asText());
            assertFalse(JSON.readTree(get(client,port,"/inspection/status?id=controlled-job").body()).path("complete").asBoolean());
            jobs.job.finish("FAILED","controlled failure");var finished=JSON.readTree(get(client,port,"/inspection/status?id=controlled-job").body());assertTrue(finished.path("complete").asBoolean());assertEquals("FAILED",finished.path("jobState").asText());
            assertTrue(JSON.readTree(get(client,port,"/inspection/current.json?key="+key(node)).body()).path("activeInspectionId").asText().isEmpty());assertEquals(1,jobs.starts);
        } finally {server.stop();}
    }

    @Test void terminalJobsNeverRetainCheckingStages() {
        for(String state:List.of("COMPLETED","FAILED","TIMED_OUT","CANCELLED")) {
            var result=new InspectionResult("test",new NodeRecord("192.0.2.1",0,0,"ab".repeat(64)));
            result.finish(state,"test terminal reason");var snapshot=result.snapshot();assertEquals(true,snapshot.get("complete"));assertEquals(state,snapshot.get("jobState"));
            for(var stage:(List<Map<String,Object>>)snapshot.get("diagnostics"))assertNotEquals("CHECKING",stage.get("state"));
            result.resetP2p();result.summarizeEndpointAttempts();
            result.diagnostic("P2P TCP",InspectionResult.State.CHECKING,null,null,"late worker");
            assertTrue(((List<Map<String,Object>>)result.snapshot().get("diagnostics")).stream().noneMatch(s->"CHECKING".equals(s.get("state"))));
        }
    }
}
