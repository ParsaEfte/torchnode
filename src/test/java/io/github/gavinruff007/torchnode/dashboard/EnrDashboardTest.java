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
                // Synthetic addresses only exported; they never become active test probe targets.
                var public6=new io.github.gavinruff007.torchnode.model.NodeRecord("2606:4700::1111",30301,0,"ef".repeat(64));public6.setDiscoverySource("discv5");store.save(public6);
                try(var provider=new io.github.gavinruff007.torchnode.enrichment.OfflineGeoIpProvider(null,null)){store.saveNetworkEnrichment(provider.lookup(public6.getIp()));}
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
                assertEquals(200,page.statusCode());assertTrue(page.body().contains("Advertised IPv6"));assertTrue(page.body().contains("Endpoint Analysis"));assertTrue(page.body().contains("Network Location / Infrastructure"));
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
                assertEquals(node.getNodeId(),snapshot.at("/endpointAnalysis/identity").asText());
                assertEquals("NAT_EVIDENCE_INSUFFICIENT",snapshot.at("/endpointAnalysis/natEvidence").asText());
                assertTrue(snapshot.at("/endpointAnalysis/comparisons").isArray());
                try(var store=new SqliteNodeStore(path)){assertEquals(1,store.findEnrEvidence(node.identity()).size());}
                assertEquals(410,http.send(HttpRequest.newBuilder(URI.create(root+"/export.csv")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
                var export=http.send(HttpRequest.newBuilder(URI.create(root+"/api/v1/identities/"+node.getNodeId()+"/exports/enr.csv?limit=99")).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,export.statusCode());
                String csv=export.body();
                assertTrue(csv.contains("evidence_type,occurrence_id,observed_at,source,payload_json"));
                assertTrue(csv.contains("VALID"));assertTrue(csv.contains("MATCH"));
                assertTrue(csv.contains(snapshot.at("/enr/record/text").asText()));
                assertEquals(7,parseCsvRow(csv.lines().findFirst().orElseThrow()).size());
                try(var store=new SqliteNodeStore(path)) {
                    var expected=json.readTree(json.writeValueAsString(io.github.gavinruff007.torchnode.analysis.EndpointAnalysis.fromStore(store,node.identity()).toMap()));
                    assertEquals(expected.path("natEvidence"),snapshot.at("/endpointAnalysis/natEvidence"));
                }

                var request=HttpRequest.newBuilder(URI.create(root+"/data/clear")).header("Content-Type","application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("csrf="+URLEncoder.encode(csrf,java.nio.charset.StandardCharsets.UTF_8))).build();
                assertEquals(200,http.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
                try(var store=new SqliteNodeStore(path)){assertEquals(0,store.count());assertTrue(store.findEnrEvidence(node.identity()).isEmpty());assertTrue(store.findObservations(node.identity()).isEmpty());}
            } finally {server.stop();}
        }
    }
    private static java.util.List<String> parseCsvRow(String line) {
        var fields=new java.util.ArrayList<String>();var value=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++) {
            char c=line.charAt(i);
            if(c=='"') {
                if(quoted && i+1<line.length() && line.charAt(i+1)=='"'){value.append('"');i++;}
                else quoted=!quoted;
            } else if(c==',' && !quoted){fields.add(value.toString());value.setLength(0);}else value.append(c);
        }
        assertFalse(quoted);fields.add(value.toString());return fields;
    }
}
