package io.github.gavinruff007.torchnode.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gavinruff007.torchnode.model.DiscoveryObservation;
import io.github.gavinruff007.torchnode.model.NodeEndpoint;
import io.github.gavinruff007.torchnode.model.NodeIdentity;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryReportsTest {
    @TempDir Path temp;

    @Test void fractionalClocksSortByObservedTimeRatherThanTextOrRowId() throws Exception {
        String db=temp.resolve("fractional.db").toString();
        var identity=new NodeIdentity("cd".repeat(64));
        var endpoint=new NodeEndpoint("192.0.2.8",NodeEndpoint.Transport.UDP,30303,
                NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.DISCOVERY);
        try(var store=new SqliteNodeStore(db)) {
            store.saveObservation(new DiscoveryObservation(identity,"discv4",List.of(endpoint),
                    Instant.parse("2026-01-01T00:00:00.9Z"),"fractional"));
            store.saveObservation(new DiscoveryObservation(identity,"discv4",List.of(endpoint),
                    Instant.parse("2026-01-01T00:00:00Z"),"fractional"));
            store.saveObservation(new DiscoveryObservation(identity,"discv4",List.of(endpoint),
                    Instant.parse("2026-01-01T00:00:00.01Z"),"fractional"));
            var first=store.discoveryHistory(identity,2,0);
            assertEquals(Instant.parse("2026-01-01T00:00:00.9Z"),first.get(0).observation().observedAt());
            assertEquals(Instant.parse("2026-01-01T00:00:00.01Z"),first.get(1).observation().observedAt());
            var next=store.discoveryHistory(identity,2,first.get(1).id());
            assertEquals(1,next.size());
            assertEquals(Instant.parse("2026-01-01T00:00:00Z"),next.get(0).observation().observedAt());
        }
    }

    @Test void boundedEvidenceAndPrintableReportPreserveOccurrenceSemantics() throws Exception {
        String db = temp.resolve("history-report.db").toString();
        var identity = new NodeIdentity("ab".repeat(64));
        var ipv4 = new NodeEndpoint("192.0.2.1", NodeEndpoint.Transport.UDP, 30303,
                NodeEndpoint.AddressFamily.IPV4, NodeEndpoint.Purpose.DISCOVERY);
        var ipv6 = new NodeEndpoint("2001:db8::1", NodeEndpoint.Transport.UDP, 30303,
                NodeEndpoint.AddressFamily.IPV6, NodeEndpoint.Purpose.DISCOVERY);
        try(var store = new SqliteNodeStore(db)) {
            for(int n=0;n<4;n++) store.saveObservation(new DiscoveryObservation(identity,
                    n==2?"discv5":"discv4",List.of(n==3?ipv6:ipv4),
                    Instant.parse("2026-01-01T00:00:0"+n+"Z"),"fixture"));
            store.saveNetworkEnrichment(io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment.state(
                    "192.0.2.1","fixture-dataset",
                    io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment.Status.DATASET_UNAVAILABLE,
                    "fixture unavailable"));
        }
        int port;
        try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var server=new DashboardServer(port,db);
        server.start();
        try {
            var client=HttpClient.newHttpClient();
            String base="http://127.0.0.1:"+port;
            String key="192.0.2.1:30303:"+identity.nodeId();
            // The latest projection key is supplied by the dashboard link.
            String dashboard=get(client,base+"/").body();
            var matcher=java.util.regex.Pattern.compile("/node\\?key=([^\"]+)").matcher(dashboard);
            assertTrue(matcher.find());
            key=java.net.URLDecoder.decode(matcher.group(1).replace("&amp;","&"),java.nio.charset.StandardCharsets.UTF_8);
            var mapper=new ObjectMapper();
            String url=base+"/inspection/evidence?domain=discovery&key="+
                    java.net.URLEncoder.encode(key,java.nio.charset.StandardCharsets.UTF_8)+"&limit=2";
            var first=mapper.readTree(get(client,url).body());
            assertEquals(2,first.size());
            assertEquals("2026-01-01T00:00:03Z",first.get(0).path("observation").path("observedAt").asText());
            var second=mapper.readTree(get(client,url+"&before="+first.get(1).path("id").asLong()).body());
            assertEquals(2,second.size());
            assertNotEquals(first.get(0).path("id").asLong(),second.get(0).path("id").asLong());
            assertEquals("discv5",first.get(1).path("observation").path("source").asText());
            assertEquals("IPV6",first.get(0).path("observation").path("endpoints").get(0).path("addressFamily").asText());
            var enrichment=mapper.readTree(get(client,base+"/inspection/evidence?domain=enrichment&key="+
                    java.net.URLEncoder.encode(key,java.nio.charset.StandardCharsets.UTF_8)+"&limit=20").body());
            assertEquals(1,enrichment.size());
            assertEquals("192.0.2.1",enrichment.get(0).path("address").asText());
            assertEquals("fixture-dataset",enrichment.get(0).path("datasetKey").asText());
            assertEquals("DATASET_UNAVAILABLE",enrichment.get(0).path("evidence").path("country").path("status").asText());
            assertEquals(400,request(client,base+"/inspection/evidence?domain=discovery&key=x&before=-1").statusCode());
            var report=get(client,base+"/reports/network?start=2026-01-01T00:00:00Z&end=2026-01-02T00:00:00Z").body();
            assertTrue(report.contains("Network Measurement Summary"));
            assertTrue(report.contains("2026-01-01T00:00:00Z"));
            assertTrue(report.contains("Denominator:"));
            assertTrue(report.contains("Methodology and limits"));
            assertTrue(report.contains("@media print"));
            assertTrue(report.contains("Repeated observations count as repeated receipts"));
            assertEquals(400,request(client,base+"/reports/network?start=2026-01-01T00:00:00Z&end=2026-03-01T00:00:00Z").statusCode());
            assertEquals(400,request(client,base+"/reports/network?mode=unknown").statusCode());
            assertEquals(400,request(client,base+"/reports/network?start=not-a-clock").statusCode());
            assertTrue(get(client,base+"/").body().contains("/reports/network"));
            try(var store=new SqliteNodeStore(db)) {store.clearCollectedData();}
            assertEquals(404,request(client,url).statusCode(),"deleted identity cannot return stale history");
            var emptyReport=get(client,base+"/reports/network?start=2026-01-01T00:00:00Z&end=2026-01-02T00:00:00Z").body();
            assertTrue(emptyReport.contains("0 all identities with timestamped discovery"));
        } finally {server.stop();}
    }

    private static HttpResponse<String> request(HttpClient client,String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> get(HttpClient client,String url) throws Exception {
        var response=request(client,url);
        assertEquals(200,response.statusCode(),url+" "+response.body());
        return response;
    }
}
