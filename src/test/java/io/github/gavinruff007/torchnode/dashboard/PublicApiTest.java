package io.github.gavinruff007.torchnode.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gavinruff007.torchnode.enr.EnrEvidence;
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

class PublicApiTest {
    @TempDir Path temp;
    private static final ObjectMapper JSON=new ObjectMapper();

    @Test void boundedVersionedEvidenceAndCsvSurviveClear() throws Exception {
        String db=temp.resolve("api.db").toString();
        String a="ab".repeat(64), b="cd".repeat(64);
        var endpoint=new NodeEndpoint("192.0.2.8",NodeEndpoint.Transport.UDP,30303,
                NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.DISCOVERY);
        var ipv6=new NodeEndpoint("2001:db8::8",NodeEndpoint.Transport.UDP,30303,
                NodeEndpoint.AddressFamily.IPV6,NodeEndpoint.Purpose.DISCOVERY);
        try(var store=new SqliteNodeStore(db)) {
            store.saveObservation(new DiscoveryObservation(new NodeIdentity(a),"=HYPERLINK(\"https://example.invalid\")",List.of(endpoint),
                    Instant.parse("2025-12-31T23:59:59Z"),"formula source"));
            store.saveObservation(new DiscoveryObservation(new NodeIdentity(a),"discv4",List.of(endpoint),
                    Instant.parse("2026-01-01T00:00:00Z"),"=hostile,\"quoted\"\nline"));
            store.saveObservation(new DiscoveryObservation(new NodeIdentity(a),"discv5",List.of(ipv6),
                    Instant.parse("2026-01-01T00:00:01Z"),"fixture"));
            store.saveObservation(new DiscoveryObservation(new NodeIdentity(b),"discv4",List.of(endpoint),
                    Instant.parse("2026-01-01T00:00:02Z"),"fixture"));
            store.saveEnrEvidence(new EnrEvidence(new NodeIdentity(a),Instant.parse("2026-01-01T00:00:03Z"),
                    "fixture","INVALID_SIGNATURE","fixture",true,true,true,
                    EnrEvidence.Signature.INVALID,EnrEvidence.IdentityComparison.MATCH,null,null));
            store.saveEnrEvidence(new EnrEvidence(new NodeIdentity(a),Instant.parse("2026-01-01T00:00:04Z"),
                    "fixture","IDENTITY_MISMATCH","fixture",true,true,true,
                    EnrEvidence.Signature.VALID,EnrEvidence.IdentityComparison.MISMATCH,null,null));
        }
        try(var readOnly=new SqliteNodeStore(db,true)) {
            assertThrows(RuntimeException.class,()->readOnly.saveObservation(new DiscoveryObservation(
                    new NodeIdentity(a),"discv4",List.of(endpoint),Instant.parse("2026-02-01T00:00:00Z"),"blocked write")));
        }
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        var server=new DashboardServer(port,db);server.start();
        try {
            var client=HttpClient.newHttpClient();String base="http://127.0.0.1:"+port+"/api/v1";
            assertEquals("v1",JSON.readTree(get(client,base).body()).path("apiVersion").asText());
            assertEquals("no-store",get(client,base).headers().firstValue("Cache-Control").orElseThrow());
            var first=JSON.readTree(get(client,base+"/identities?limit=1").body());
            assertEquals(1,first.path("data").size());
            assertEquals(a,first.path("data").get(0).path("identity").asText());
            String cursor=first.path("pagination").path("nextCursor").asText();
            var second=JSON.readTree(get(client,base+"/identities?limit=1&cursor="+cursor).body());
            assertEquals(b,second.path("data").get(0).path("identity").asText());
            assertTrue(second.path("pagination").path("nextCursor").isNull());
            var detail=JSON.readTree(get(client,base+"/identities/"+a).body());
            assertEquals(a,detail.path("data").path("identity").asText());
            assertTrue(detail.path("data").path("latestProjection").isObject());
            var discovery=JSON.readTree(get(client,base+"/identities/"+a+"/evidence/discovery?limit=1").body());
            assertEquals("discv5",discovery.path("data").get(0).path("source").asText());
            assertEquals("IPV6",discovery.path("data").get(0).path("endpoints").get(0).path("addressFamily").asText());
            String evidenceCursor=discovery.path("pagination").path("nextCursor").asText();
            var older=JSON.readTree(get(client,base+"/identities/"+a+"/evidence/discovery?limit=1&cursor="+evidenceCursor).body());
            assertEquals("discv4",older.path("data").get(0).path("source").asText());
            assertEquals("=hostile,\"quoted\"\nline",older.path("data").get(0).path("provenance").asText());
            var enr=JSON.readTree(get(client,base+"/identities/"+a+"/evidence/enr").body()).path("data");
            assertEquals(2,enr.size());
            assertFalse(enr.get(0).path("usable").asBoolean());
            assertEquals("MISMATCH",enr.get(0).path("identityComparison").asText());
            assertFalse(enr.get(1).path("usable").asBoolean());
            assertEquals("INVALID",enr.get(1).path("signature").asText());
            var changes=JSON.readTree(get(client,base+"/identities/"+a+"/changes").body());
            assertFalse(changes.path("data").isEmpty());
            var change=changes.path("data").get(0);
            assertTrue(change.path("derived").asBoolean());
            assertEquals("ENDPOINT_FIRST_OBSERVED",change.path("changeType").asText());
            assertEquals(200,request(client,"http://127.0.0.1:"+port+change.path("currentSource").asText()).statusCode());
            var csv=get(client,base+"/identities/"+a+"/exports/discovery.csv?limit=99");
            assertTrue(csv.headers().firstValue("Content-Type").orElseThrow().startsWith("text/csv"));
            assertTrue(csv.body().startsWith("api_version,identity,evidence_type"));
            assertTrue(csv.body().contains("\"'=HYPERLINK"));
            assertTrue(csv.body().contains("\\nline"));
            assertEquals(400,request(client,base+"/identities?limit=-1").statusCode());
            assertEquals(413,request(client,base+"/identities?limit=51").statusCode());
            assertEquals(400,request(client,base+"/identities?cursor=bad").statusCode());
            assertEquals(404,request(client,base+"/identities/"+"ef".repeat(64)).statusCode());
            assertEquals(405,client.send(HttpRequest.newBuilder(URI.create(base+"/identities")).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200,request(client,base+"/analytics?start=2026-01-01T00:00:00Z&end=2026-01-02T00:00:00Z").statusCode());
            try(var store=new SqliteNodeStore(db)){store.clearCollectedData();}
            assertEquals(0,JSON.readTree(get(client,base+"/identities").body()).path("data").size());
            assertEquals(404,request(client,base+"/identities/"+a).statusCode());
            assertEquals(404,request(client,base+"/identities/"+a+"/exports/discovery.csv").statusCode());
            assertEquals(400,request(client,base+"/identities?cursor="+cursor).statusCode());
            assertEquals(200,request(client,base+"/exports/analytics.csv?start=2026-01-01T00:00:00Z&end=2026-01-02T00:00:00Z").statusCode());
            try(var store=new SqliteNodeStore(db)) {
                store.saveObservation(new DiscoveryObservation(new NodeIdentity(b),"discv5",List.of(ipv6),
                        Instant.parse("2026-02-01T00:00:00Z"),"after clear"));
            }
            var renewed=JSON.readTree(get(client,base+"/identities").body());
            assertEquals(1,renewed.path("data").size());
            assertEquals(b,renewed.path("data").get(0).path("identity").asText());
        } finally {server.stop();}
    }

    private static HttpResponse<String> request(HttpClient client,String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> get(HttpClient client,String url) throws Exception {
        var response=request(client,url);assertEquals(200,response.statusCode(),url+" "+response.body());return response;
    }
}
