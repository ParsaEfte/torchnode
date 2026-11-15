package io.github.gavinruff007.torchnode.dashboard;

import io.github.gavinruff007.torchnode.discovery.DiscoveryEvidenceBatch;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TopologyB6AcceptanceTest {
    @TempDir Path root;
    private Process child;
    private int port;
    private HttpClient client;
    private final String window="?start=2026-11-10T00:00:00Z&end=2026-11-12T00:00:00Z";
    private void start(String mode)throws Exception {
        Files.deleteIfExists(root.resolve("STOP"));Files.deleteIfExists(root.resolve("ready"));
        try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),"io.github.gavinruff007.torchnode.discovery.B6IsolatedApp",root.toString(),Integer.toString(port),mode);
        builder.environment().put("TORCHNODE_SECURE_COOKIES","false");builder.redirectErrorStream(true).redirectOutput(root.resolve("app-"+mode+".log").toFile());child=builder.start();
        long until=System.nanoTime()+Duration.ofSeconds(25).toNanos();while(!Files.exists(root.resolve("ready")) && child.isAlive() && System.nanoTime()<until)Thread.sleep(50);
        assertTrue(Files.exists(root.resolve("ready")),()->{try{return Files.readString(root.resolve("app-"+mode+".log"));}catch(Exception e){return e.toString();}});
        client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();assertEquals(302,AuthTestSupport.login(client,port,"b6-fixture","b6-fixture-password").statusCode());
    }
    private void stop()throws Exception {Files.writeString(root.resolve("STOP"),"");if(!child.waitFor(15,java.util.concurrent.TimeUnit.SECONDS)){child.destroyForcibly();fail("Isolated application shutdown timeout");}assertEquals(0,child.exitValue());}
    private HttpResponse<String> get(String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).build(),HttpResponse.BodyHandlers.ofString());}
    private com.fasterxml.jackson.databind.JsonNode graph()throws Exception {var r=get("/api/v1/topology/graph"+window);assertEquals(200,r.statusCode(),r.body());return DiscoveryEvidenceBatch.JSON.readTree(r.body()).path("data");}
    @Test void actualProviderReaderDaemonWriterHttpAndProcessRestart()throws Exception {
        Files.writeString(root.resolve("B6-ISOLATED"),"");
        try {
            start("ingest");long initialPid=child.pid();int initialPort=port;var first=graph();assertEquals(0,first.path("edges").size());assertFalse(first.at("/evidenceCoverage/relationshipMeasurementAvailable").asBoolean());Files.writeString(root.resolve("RELEASE-EVENTS"),"");
            long until=System.nanoTime()+Duration.ofSeconds(12).toNanos();var complete=graph();while(complete.at("/evidenceCoverage/relationshipObservationCount").asInt()!=3 && System.nanoTime()<until){Thread.sleep(100);complete=graph();}
            long traces=0;long retainedUntil=System.nanoTime()+Duration.ofSeconds(8).toNanos();
            while(traces<7 && System.nanoTime()<retainedUntil){try(var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+root.resolve("acceptance.db").toUri()+"?mode=ro");var statement=db.createStatement();var rows=statement.executeQuery("SELECT COUNT(*) FROM discovery_exchange_evidence WHERE kind='TRACE'")){traces=rows.getLong(1);}if(traces<7)Thread.sleep(100);}
            assertEquals(7,traces,"Every controlled trace, including final truncated capture, must reach persistence");complete=graph();
            assertEquals(3,complete.at("/evidenceCoverage/relationshipObservationCount").asInt(),complete.toPrettyString());assertEquals(2,complete.path("edges").size());assertEquals(3,complete.path("nodes").size());
            assertTrue(complete.at("/evidenceCoverage/nonPromotableCandidateCount").asInt()>0);
            Files.createDirectories(Path.of("target/b6a"));Files.writeString(Path.of("target/b6a/graph.json"),complete.toPrettyString());
            assertEquals(0,DiscoveryEvidenceBatch.JSON.readTree(get("/api/v1/topology/graph"+window+"&protocol=discv4").body()).at("/data/edges").size());
            assertEquals(0,DiscoveryEvidenceBatch.JSON.readTree(get("/api/v1/topology/graph"+window+"&observer="+UUID.randomUUID()).body()).at("/data/edges").size());
            var anonymous=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/topology/graph"+window)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,anonymous.statusCode());
            for(var edge:complete.path("edges")){assertEquals("REPORTED_NEIGHBOR",edge.path("relationType").asText());for(var observation:edge.path("observations")){var detail=get("/api/v1/topology/edges/"+observation.path("id").asText()+window);assertEquals(200,detail.statusCode());assertEquals("ATTRIBUTABLE_EXCHANGE",DiscoveryEvidenceBatch.JSON.readTree(detail.body()).at("/data/evidence/classification").asText());}}
            assertEquals(413,get("/api/v1/topology/graph"+window+"&limit=201").statusCode());assertEquals(400,get("/api/v1/topology/graph"+window+"&observer=bad").statusCode());
            var empty=DiscoveryEvidenceBatch.JSON.readTree(get("/api/v1/topology/graph?start=2026-01-01T00:00:00Z&end=2026-01-02T00:00:00Z").body()).path("data");assertEquals(0,empty.path("edges").size());assertFalse(empty.at("/evidenceCoverage/relationshipMeasurementAvailable").asBoolean());
            var compare=DiscoveryEvidenceBatch.JSON.readTree(get("/api/v1/topology/compare"+window+"&baselineStart=2026-11-10T00:00:00Z&baselineEnd=2026-11-12T00:00:00Z").body()).path("data");assertTrue(compare.at("/relationships/available").asBoolean());assertEquals(2,compare.at("/relationships/reobserved").size());
            if(System.getenv("TORCHNODE_B6_VISUAL")!=null){System.out.println("B6_BROWSER http://127.0.0.1:"+port+"/topology ROOT="+root);long deadline=System.nanoTime()+Duration.ofMinutes(10).toNanos();while(!Files.exists(root.resolve("VISUAL-DONE")) && System.nanoTime()<deadline)Thread.sleep(200);}
            var evidenceIds=new TreeSet<String>();for(var edge:complete.path("edges"))for(var observation:edge.path("observations"))evidenceIds.add(observation.path("evidenceId").asText());
            stop();start("readonly");var reopened=graph();assertEquals(complete.path("edges"),reopened.path("edges"));assertEquals(3,reopened.at("/evidenceCoverage/relationshipObservationCount").asInt());
            for(String id:evidenceIds)assertEquals(200,get("/api/v1/topology/evidence/"+id+window).statusCode());
            Files.writeString(Path.of("target/b6a/acceptance.json"),DiscoveryEvidenceBatch.json(Map.of("initialPid",initialPid,"restartedPid",child.pid(),"initialPort",initialPort,"restartPort",port,"traceCount",traces,"relationshipObservations",3,"directedRelationships",2,"immutableEvidenceIds",evidenceIds,"freshLoopbackCapture",System.getenv("TORCHNODE_B6_TRACE_ROOT")!=null)));
            System.out.println("B6 acceptance: provider stdout -> B3 capture -> Composite -> ScanDaemon writer -> SQLite -> authenticated HTTP; 3 immutable observations / 2 directed relationships; process restart PASS");
        }finally{if(child!=null && child.isAlive())stop();}
    }
}
