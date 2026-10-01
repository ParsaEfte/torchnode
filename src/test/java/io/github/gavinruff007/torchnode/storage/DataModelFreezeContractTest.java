package io.github.gavinruff007.torchnode.storage;

import io.github.gavinruff007.torchnode.analysis.NetworkAnalytics;
import io.github.gavinruff007.torchnode.model.DiscoveryObservation;
import io.github.gavinruff007.torchnode.model.NodeEndpoint;
import io.github.gavinruff007.torchnode.model.NodeIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-domain contract: identity, endpoint history, analytics and Clear share one meaning. */
class DataModelFreezeContractTest {
    @TempDir Path temp;

    @Test void identityAndOccurrencesSurviveProjectionAndReadOnlyAnalytics() throws Exception {
        String path = temp.resolve("freeze.db").toString();
        var first = new NodeIdentity("ab".repeat(64));
        var second = new NodeIdentity("cd".repeat(64));
        var start = Instant.parse("2026-01-01T00:00:00Z");
        var end = start.plusSeconds(3);
        try (var store = new SqliteNodeStore(path)) {
            store.saveObservation(observation(first, "discv4", "192.0.2.1", start));
            store.saveObservation(observation(first, "discv5", "2001:db8::1", start.plusSeconds(1)));
            store.saveObservation(observation(second, "discv4", "192.0.2.1", start.plusSeconds(2)));
            assertEquals(2, store.findObservations(first).size());
            assertEquals(1, store.findObservations(second).size());
            assertEquals(3, store.count()); // Latest endpoint projections are not identity counts.

            long changesBeforeRead = count(path, "change_events", "1=1");
            var analytics = new NetworkAnalytics(path);
            var scope = new NetworkAnalytics.Scope(start, end);
            var report = analytics.measure(scope);
            assertEquals(2, bucket(report, "observed-identities", "observed"));
            assertEquals(3, bucket(report, "discovery-occurrences", "receipts"));
            assertEquals(1, bucket(report, "discv4-discv5-overlap", "both"));
            assertEquals(2, count(path, "discovery_observations", "node_id='" + first.nodeId() + "'"));
            assertEquals(3, count(path, "discovery_observations", "1=1"));
            assertEquals(changesBeforeRead, count(path, "change_events", "1=1"));
            assertEquals(5, count(path, "schema_migrations", "1=1"));

            store.clearCollectedData();
            assertEquals(0, bucket(analytics.measure(scope), "observed-identities", "observed"));
            assertEquals(5, count(path, "schema_migrations", "1=1"));
            store.saveObservation(observation(first, "discv4", "192.0.2.2", start.plusSeconds(1)));
        }
        try (var reopened = new SqliteNodeStore(path)) {
            assertEquals(1, reopened.count());
            assertEquals(1, reopened.findObservations(first).size());
            assertEquals(1, bucket(new NetworkAnalytics(path).measure(
                    new NetworkAnalytics.Scope(start, end)), "observed-identities", "observed"));
        }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = db.createStatement()) {
            try (var rows = statement.executeQuery("PRAGMA integrity_check")) {
                assertEquals("ok", rows.getString(1));
            }
            try (var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
                assertFalse(rows.next());
            }
        }
    }

    private static DiscoveryObservation observation(NodeIdentity identity, String source,
                                                     String address, Instant at) {
        var family = address.contains(":") ? NodeEndpoint.AddressFamily.IPV6 : NodeEndpoint.AddressFamily.IPV4;
        return new DiscoveryObservation(identity, source, List.of(
                new NodeEndpoint(address, NodeEndpoint.Transport.UDP, 30303, family, NodeEndpoint.Purpose.DISCOVERY),
                new NodeEndpoint(address, NodeEndpoint.Transport.TCP, 30303, family, NodeEndpoint.Purpose.P2P)), at, "freeze-fixture");
    }

    private static long bucket(NetworkAnalytics.Report report, String metric, String label) {
        return report.metrics().stream().filter(value -> value.id().equals(metric)).findFirst().orElseThrow()
                .buckets().stream().filter(value -> value.label().equals(label)).findFirst().orElseThrow().count();
    }

    private static long count(String path, String table, String where) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = db.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + where)) {
            return rows.getLong(1);
        }
    }
}
