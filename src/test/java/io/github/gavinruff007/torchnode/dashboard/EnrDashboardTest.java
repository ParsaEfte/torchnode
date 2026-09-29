package io.github.gavinruff007.torchnode.dashboard;

import io.github.gavinruff007.torchnode.enr.*;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class EnrDashboardTest {
    @TempDir Path temp;
    @Test void realAcquisitionFlowsThroughInspectionJspPersistenceCsvAndClear() throws Exception {
        int port;
        try(var socket=new java.net.ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
        String path=temp.resolve("dashboard.db").toString();
        try(var peer=new LoopbackEnrPeer(LoopbackEnrPeer.Mode.VALID)) {
            var node=peer.node();node.setTcpPort(0);
            try(var store=new SqliteNodeStore(path)){
                store.save(node);
                var ipv6 = new io.github.gavinruff007.torchnode.model.NodeRecord("2001:db8::42", 30301, 0, "cd".repeat(64));
                ipv6.setDiscoverySource("discv5"); store.save(ipv6);
            }
            var server=new DashboardServer(port,path);server.start();
            try {
                var cookies=new CookieManager();cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
                var http=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NORMAL).build();
                String root="http://127.0.0.1:"+port;
                String dashboard=http.send(HttpRequest.newBuilder(URI.create(root+"/")).build(),HttpResponse.BodyHandlers.ofString()).body();
                assertTrue(dashboard.contains("[2001:db8::42]:30301"));
                var token=java.util.regex.Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"").matcher(dashboard);assertTrue(token.find());
                String csrf=token.group(1);
                var page=http.send(HttpRequest.newBuilder(URI.create(root+"/node?key="+URLEncoder.encode(node.getKey(),java.nio.charset.StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,page.statusCode());assertTrue(page.body().contains("Advertised IPv6"));
                var match=java.util.regex.Pattern.compile("const inspectionId = '([^']+)' ".trim()).matcher(page.body());assertTrue(match.find());
                String id=match.group(1);var json=new ObjectMapper();com.fasterxml.jackson.databind.JsonNode snapshot=null;
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
                while(System.nanoTime()<deadline) {
                    snapshot=json.readTree(http.send(HttpRequest.newBuilder(URI.create(root+"/inspection/status?id="+id)).build(),HttpResponse.BodyHandlers.ofString()).body());
                    if(snapshot.path("complete").asBoolean())break;Thread.sleep(20);
                }
                assertNotNull(snapshot);assertTrue(snapshot.path("complete").asBoolean());
                assertEquals("VALID",snapshot.at("/enr/signature").asText());assertEquals("MATCH",snapshot.at("/enr/identityComparison").asText());
                assertEquals("7",snapshot.at("/enr/record/sequence").asText());assertTrue(snapshot.at("/enr/record/fields/ip6").asText().contains("2001:db8"));
                assertEquals(0,snapshot.at("/node/tcpPort").asInt());
                assertEquals("MISMATCH",snapshot.at("/enrComparisons/TCP vs discovery").asText());
                try(var store=new SqliteNodeStore(path)){assertEquals(1,store.findEnrEvidence(node.identity()).size());}
                String csv=http.send(HttpRequest.newBuilder(URI.create(root+"/export.csv")).build(),HttpResponse.BodyHandlers.ofString()).body();
                assertTrue(csv.contains("ENDPOINT_OBSERVATIONS_JSON"));assertTrue(csv.contains("IPV6"));assertTrue(csv.contains("2001:db8:0:0:0:0:0:42"));
                assertTrue(csv.contains("ENR_SEQUENCE"));assertTrue(csv.contains(snapshot.at("/enr/record/text").asText()));
                var request=HttpRequest.newBuilder(URI.create(root+"/data/clear")).header("Content-Type","application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("csrf="+URLEncoder.encode(csrf,java.nio.charset.StandardCharsets.UTF_8))).build();
                assertEquals(200,http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
                try(var store=new SqliteNodeStore(path)){assertEquals(0,store.count());assertTrue(store.findEnrEvidence(node.identity()).isEmpty());assertTrue(store.findObservations(node.identity()).isEmpty());}
            } finally {server.stop();}
        }
    }
}
