package io.github.gavinruff007.torchnode.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gavinruff007.torchnode.model.DiscoveryObservation;
import io.github.gavinruff007.torchnode.model.NodeEndpoint;
import io.github.gavinruff007.torchnode.model.NodeIdentity;
import io.github.gavinruff007.torchnode.model.NodeRecord;
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

class DashboardV2Test {
    @TempDir Path temp;

    @Test void emptyAndRepeatedReceiptsKeepHistoricalUnitsSeparateFromLatestTable() throws Exception {
        String db = temp.resolve("dashboard-v2.db").toString();
        try (var ignored = new SqliteNodeStore(db)) { }
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var server = new DashboardServer(port, db);
        server.start();
        try {
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + port;
            String page = get(client, base + "/").body();
            assertTrue(page.contains("Network measurements"));
            assertTrue(page.contains("Latest endpoint projections"));
            assertTrue(page.contains("Last 24 hours"));
            assertTrue(page.contains("No nodes discovered yet."));
            String script = get(client, base + "/assets/dashboard-v2.js").body();
            assertTrue(script.contains("/analytics.json?"));
            assertFalse(script.contains("innerHTML"));
            assertFalse(script.contains("https://"));
            String map = get(client, base + "/assets/world-countries.svg").body();
            assertTrue(map.contains("data-country=\"US\""));
            assertFalse(map.contains("192.0.2."));
            String query = "/analytics.json?start=2026-01-01T00:00:00Z&end=2026-01-02T00:00:00Z";
            var empty = new ObjectMapper().readTree(get(client, base + query).body());
            assertEquals(0, bucket(empty, "observed-identities", "observed"));
            assertEquals(0, bucket(empty, "discovery-occurrences", "receipts"));

            var id = new NodeIdentity("ab".repeat(64));
            try (var store = new SqliteNodeStore(db)) {
                for (int second = 0; second < 2; second++) {
                    store.saveObservation(new DiscoveryObservation(id, "discv4", List.of(
                            new NodeEndpoint("192.0.2.1", NodeEndpoint.Transport.UDP, 30303,
                                    NodeEndpoint.AddressFamily.IPV4, NodeEndpoint.Purpose.DISCOVERY)),
                            Instant.parse("2026-01-01T00:00:0" + second + "Z"), "fixture"));
                }
            }
            var observed = new ObjectMapper().readTree(get(client, base + query).body());
            assertEquals(1, bucket(observed, "observed-identities", "observed"));
            assertEquals(2, bucket(observed, "discovery-occurrences", "receipts"));
            assertEquals(1, bucket(observed, "discovery-providers", "discv4"));
            assertTrue(get(client, base + "/").body().contains("/node?key="));
            try (var store = new SqliteNodeStore(db)) {
                var untrusted = new NodeRecord("192.0.2.2", 30303, 30303, "cd".repeat(64));
                untrusted.setClientVersion("<script>alert('peer')</script>");
                store.save(untrusted);
            }
            String escaped = get(client, base + "/").body();
            assertFalse(escaped.contains("<script>alert('peer')</script>"));
            assertTrue(escaped.contains("&lt;script&gt;alert(&#39;peer&#39;)&lt;/script&gt;"));
        } finally { server.stop(); }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), url + " " + response.body());
        return response;
    }

    private static long bucket(com.fasterxml.jackson.databind.JsonNode report, String metric, String label) {
        for (var item : report.path("metrics")) if (metric.equals(item.path("id").asText()))
            for (var value : item.path("buckets")) if (label.equals(value.path("label").asText()))
                return value.path("count").asLong();
        fail("Missing " + metric + "/" + label);
        return -1;
    }
}
