package io.github.gavinruff007.torchnode.dashboard;

import io.github.gavinruff007.torchnode.topology.*;
import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.security.AuthStore;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TopologyApiTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper();
    final String identity="ab".repeat(64);
    final String window="?start=2026-01-01T00:00:00Z&end=2026-01-02T00:00:00Z";
    HttpResponse<String> get(HttpClient client,int port,String path)throws Exception{return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).build(),HttpResponse.BodyHandlers.ofString());}
    long count(String db)throws Exception{try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM discovery_observations")){return r.getLong(1);}}
    @Test void authenticatedBoundedReadsExportsAndEmptyRelationshipResponses()throws Exception {
        String db=temp.resolve("api.db").toString();
        try(var store=new SqliteNodeStore(db)){store.saveObservation(new DiscoveryObservation(new NodeIdentity(identity),"discv4",List.of(new NodeEndpoint("192.0.2.1",NodeEndpoint.Transport.UDP,30303,NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.DISCOVERY)),Instant.parse("2026-01-01T00:00:00Z"),"NEIGHBORS from 192.0.2.2:30303"));}
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var server=new DashboardServer(port,db,"127.0.0.1",false);server.start();
        try {
            var anonymous=HttpClient.newHttpClient();assertEquals(401,get(anonymous,port,"/api/v1/topology/graph"+window).statusCode());
            var admin=AuthTestSupport.client(db,port);long before=count(db);
            var page=get(admin,port,"/topology");assertEquals(200,page.statusCode());assertTrue(page.body().contains("Network Topology"));assertTrue(page.body().contains("Directed discovery reports"));
            assertEquals(200,get(admin,port,"/assets/topology.js").statusCode());assertTrue(get(admin,port,"/assets/topology.css").headers().firstValue("Content-Type").orElseThrow().contains("text/css"));
            for(String path:List.of("","/graph","/summary","/compare","/nodes/"+identity+"/neighborhood","/nodes/"+identity+"/evidence")) {
                var response=get(admin,port,"/api/v1/topology"+path+window);assertEquals(200,response.statusCode(),path+" "+response.body());assertTrue(response.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));assertEquals("v1",json.readTree(response.body()).path("apiVersion").asText());
            }
            var graph=json.readTree(get(admin,port,"/api/v1/topology/graph"+window).body()).path("data");assertEquals(1,graph.path("nodes").size());assertTrue(graph.path("edges").isEmpty());assertEquals(0,graph.at("/summary/attributableEdgeCount").asInt());assertFalse(graph.at("/summary/relationshipAnalyticsAvailable").asBoolean());assertTrue(graph.at("/summary/degreeDistribution").isNull());assertTrue(graph.at("/summary/connectedComponents").isNull());assertFalse(graph.at("/coverage/networkCoverageKnown").asBoolean());assertTrue(graph.at("/coverage/queryComplete").asBoolean());
            var exported=get(admin,port,"/api/v1/topology/exports/graph.json"+window);assertEquals(200,exported.statusCode());assertTrue(exported.headers().firstValue("Content-Disposition").orElseThrow().contains("attachment"));assertTrue(json.readTree(exported.body()).at("/data/summary/relationshipFreshness").isNull());
            assertEquals(404,get(admin,port,"/api/v1/topology/edges/guessed"+window).statusCode());
            for(String suffix:List.of("&limit=201","&limit=99999"))assertEquals(413,get(admin,port,"/api/v1/topology/graph"+window+suffix).statusCode());
            for(String suffix:List.of("&limit=-1","&after=garbage","&q=192.0.2.1","&q=%25","&relation=PEERS","&protocol=rpc","&mode=all"))assertEquals(400,get(admin,port,"/api/v1/topology/graph"+window+suffix).statusCode(),suffix);
            assertEquals(400,get(admin,port,"/api/v1/topology/graph?start=2026-01-01T00:00:00Z&end=2026-02-03T00:00:00Z").statusCode());
            assertEquals(405,AuthTestSupport.post(admin,port,"/api/v1/topology/graph","").statusCode());
            String password=UUID.randomUUID().toString();new AuthStore(db).create("test-admin","topology-viewer",password,"VIEWER","test");
            var viewer=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();AuthTestSupport.login(viewer,port,"topology-viewer",password);
            assertEquals(200,get(viewer,port,"/topology").statusCode());assertEquals(200,get(viewer,port,"/api/v1/topology/graph"+window).statusCode());assertEquals(403,get(viewer,port,"/api/v1/topology/exports/graph.json"+window).statusCode());assertEquals(405,AuthTestSupport.post(viewer,port,"/api/v1/topology/graph","").statusCode());
            assertEquals(before,count(db),"GET must not collect or persist observations");
            try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var q=c.prepareStatement("INSERT INTO discovery_observations(node_id,source,provenance,observed_at,endpoints_json) VALUES(?,'discv4',?,'2026-01-01T00:00:00Z','[]')")) {
                c.setAutoCommit(false);for(int i=0;i<TopologyService.MAX_OCCURRENCES;i++){q.setString(1,identity);q.setString(2,"budget-"+i);q.addBatch();}q.executeBatch();c.commit();
            }
            var capped=get(admin,port,"/api/v1/topology/graph"+window);assertEquals(413,capped.statusCode());var cap=json.readTree(capped.body());assertFalse(cap.has("data"));assertFalse(cap.at("/query/queryComplete").asBoolean());assertFalse(cap.at("/query/aggregateReturned").asBoolean());assertFalse(cap.at("/query/partialResultReturned").asBoolean());assertEquals(50000,cap.at("/query/safetyLimitOccurrences").asInt());
        }finally{server.stop();}
    }
    @Test void activeReadsAreBoundedRatherThanQueuedWithoutLimit()throws Exception {
        String db=temp.resolve("admission.db").toString();try(var ignored=new SqliteNodeStore(db)){}
        var servlet=new TopologyServlet(db);
        var request=(jakarta.servlet.http.HttpServletRequest)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.http.HttpServletRequest.class},(p,m,a)->m.getName().equals("getPathInfo")?"/graph":null);
        var writing=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        java.util.function.Supplier<jakarta.servlet.http.HttpServletResponse> blocked=()->{
            var once=new java.util.concurrent.atomic.AtomicBoolean();var writer=new java.io.PrintWriter(new java.io.Writer(){
                public void write(char[] value,int offset,int length)throws java.io.IOException {
                    if(once.compareAndSet(false,true))writing.countDown();
                    try{if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new java.io.IOException("Test writer release timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}
                }
                public void flush(){}public void close(){}
            });
            return (jakarta.servlet.http.HttpServletResponse)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.http.HttpServletResponse.class},(p,m,a)->m.getName().equals("getWriter")?writer:null);
        };
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var first=executor.submit(()->{servlet.doGet(request,blocked.get());return null;});var second=executor.submit(()->{servlet.doGet(request,blocked.get());return null;});
        try {
            assertTrue(writing.await(5,java.util.concurrent.TimeUnit.SECONDS));var body=new java.io.StringWriter();int[] status={200};
            var response=(jakarta.servlet.http.HttpServletResponse)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.http.HttpServletResponse.class},(p,m,a)->{if(m.getName().equals("setStatus"))status[0]=(int)a[0];return m.getName().equals("getWriter")?new java.io.PrintWriter(body):null;});
            servlet.doGet(request,response);assertEquals(429,status[0]);assertEquals("topology_busy",json.readTree(body.toString()).at("/error/code").asText());
        }finally{release.countDown();executor.shutdown();}
        first.get(5,java.util.concurrent.TimeUnit.SECONDS);second.get(5,java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test void realSqliteDeadlineReturns503WithoutAnEmptyOrPartialAggregate()throws Exception {
        String db=temp.resolve("timeout.db").toString();try(var ignored=new SqliteNodeStore(db)){}
        try(var c=DriverManager.getConnection("jdbc:sqlite:"+db);var q=c.prepareStatement("INSERT INTO discovery_observations(node_id,source,provenance,observed_at,endpoints_json) VALUES(?,'discv4',?,'2026-01-01T00:00:00Z','[]')")){c.setAutoCommit(false);for(int i=0;i<1000;i++){q.setString(1,identity);q.setString(2,"row-"+i);q.addBatch();}q.executeBatch();c.commit();}
        var request=(jakarta.servlet.http.HttpServletRequest)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.http.HttpServletRequest.class},(p,m,a)->switch(m.getName()){case "getPathInfo"->"/graph";case "getParameter"->a[0].equals("start")?"2026-01-01T00:00:00Z":a[0].equals("end")?"2026-01-02T00:00:00Z":null;default->null;});
        var body=new java.io.StringWriter();int[] status={200};
        var response=(jakarta.servlet.http.HttpServletResponse)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.http.HttpServletResponse.class},(p,m,a)->{if(m.getName().equals("setStatus"))status[0]=(int)a[0];if(m.getName().equals("getWriter"))return new java.io.PrintWriter(body);return null;});
        new TopologyServlet(new TopologyService(db,Duration.ofNanos(1))).doGet(request,response);
        assertEquals(503,status[0]);var result=json.readTree(body.toString());assertEquals("topology_query_timeout",result.at("/error/code").asText());assertFalse(result.has("data"));
    }
}
