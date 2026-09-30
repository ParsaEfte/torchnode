package io.github.gavinruff007.torchnode.storage;

import io.github.gavinruff007.torchnode.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryPersistenceTest {
    @TempDir Path temp;
    static DiscoveryObservation observation(String id, String address, int udp, int tcp, String source, Instant time) {
        return new DiscoveryObservation(new NodeIdentity(id), source, List.of(
                new NodeEndpoint(address, NodeEndpoint.Transport.UDP, udp, NodeEndpoint.AddressFamily.IPV4, NodeEndpoint.Purpose.DISCOVERY),
                new NodeEndpoint(address, NodeEndpoint.Transport.TCP, tcp, NodeEndpoint.AddressFamily.IPV4, NodeEndpoint.Purpose.P2P)),
                time, "NEIGHBORS from 192.0.2.9:30303");
    }
    @Test void roundTripPreservesConflictsIdentityProvenanceAndExactTimes() throws Exception {
        String path = temp.resolve("evidence.db").toString();
        String id = "ab".repeat(64);
        Instant time = Instant.parse("2026-09-28T01:02:03.123456789Z");
        var first = observation(id, "192.0.2.1", 30301, 30305, "discv4", time);
        var second = observation(id, "192.0.2.1", 30301, 30303, "independent-source", time.plusSeconds(1));
        var moved = observation(id, "192.0.2.2", 30302, 9999, "discv4", time.plusSeconds(2));
        try (var store = new SqliteNodeStore(path)) {
            store.saveObservation(first); store.saveObservation(second); store.saveObservation(moved);
            store.saveObservation(first); // Exact re-delivery is idempotent, distinct receipt times are retained.
        }
        try (var store = new SqliteNodeStore(path)) {
            assertEquals(List.of(first, second, moved), store.findObservations(first.identity()));
            assertEquals(2, store.count()); // Endpoint projections, one cryptographic identity.
            var selected = store.findByKey(new NodeRecord(second).getKey()).orElseThrow();
            assertEquals(first.identity(), selected.identity());
            assertEquals(30301, selected.getUdpPort());
            assertEquals(30303, selected.getP2pEndpoint().port());
            assertEquals("independent-source", selected.getDiscoverySource());
            assertEquals(3, selected.getObservations().size());
            store.clearCollectedData();
            assertTrue(store.findObservations(first.identity()).isEmpty());
            store.saveObservation(first);
            assertEquals(List.of(first), store.findObservations(first.identity()));
        }
    }
    @Test void identitiesSharingEndpointDoNotShareServiceEvidence() throws Exception {
        try (var store = new SqliteNodeStore(temp.resolve("identities.db").toString())) {
            var first = observation("ab".repeat(64), "192.0.2.1", 30303, 30305, "discv4", Instant.now());
            var second = observation("cd".repeat(64), "192.0.2.1", 30303, 30303, "discv4", Instant.now());
            store.saveObservation(first);
            var inspected = new NodeRecord(first); inspected.setRpcAvailable(true); store.update(inspected);
            store.saveObservation(second);
            assertEquals(2, store.count());
            assertTrue(store.findByKey(inspected.getKey()).orElseThrow().isRpcAvailable());
            assertFalse(store.findByKey(new NodeRecord(second).getKey()).orElseThrow().isRpcAvailable());
        }
    }
    @Test void delayedInspectionCannotRestoreOldDiscoveryEndpoint() throws Exception {
        try (var store = new SqliteNodeStore(temp.resolve("race.db").toString())) {
            Instant time = Instant.now();
            var old = observation("ab".repeat(64), "192.0.2.1", 30301, 30305, "discv4", time);
            var newer = observation("ab".repeat(64), "192.0.2.1", 30301, 9999, "discv4", time.plusSeconds(2));
            store.saveObservation(old);
            var inspecting = store.findByKey(new NodeRecord(old).getKey()).orElseThrow();
            store.saveObservation(newer);
            inspecting.setBeaconAvailable(true); store.update(inspecting);
            store.saveObservation(old);
            var stored = store.findByKey(inspecting.getKey()).orElseThrow();
            assertEquals(9999, stored.getTcpPort());
            assertTrue(stored.isBeaconAvailable());
            assertEquals(2, stored.getObservations().size());
        }
    }
    @Test void migratesRepresentativeLegacyDatabaseWithoutLosingRowsOrP2pEvidence() throws Exception {
        String path = temp.resolve("legacy.db").toString();
        String id = "ab".repeat(64);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement()) {
            sql.execute("""
                CREATE TABLE nodes (key TEXT PRIMARY KEY, ip TEXT NOT NULL, udp_port INTEGER NOT NULL,
                    tcp_port INTEGER NOT NULL, node_id TEXT NOT NULL, country TEXT, latency INTEGER,
                    node_type TEXT, last_seen INTEGER, rpc_available INTEGER, beacon_available INTEGER,
                    client_version TEXT, syncing INTEGER, block_number INTEGER, pending_transactions INTEGER)
                """);
            sql.execute("INSERT INTO nodes(key,ip,udp_port,tcp_port,node_id,last_seen,rpc_available,beacon_available,client_version) " +
                    "VALUES ('192.0.2.1:30301','192.0.2.1',30301,30305,'0x" + id + "',1234567890,1,1,'Geth/test')");
            sql.execute("CREATE TABLE p2p_observations(node_key TEXT PRIMARY KEY, observed_at INTEGER NOT NULL, hello_json TEXT, status_json TEXT)");
            sql.execute("INSERT INTO p2p_observations VALUES ('192.0.2.1:30301',1234567891,'{\"listenPort\":0}', '{}')");
        }
        for (int reopen = 0; reopen < 2; reopen++) {
            try (var store = new SqliteNodeStore(path)) {
                var node = store.findByKey("192.0.2.1:30301").orElseThrow();
                assertEquals(id + "@192.0.2.1:30301", node.getKey());
                assertEquals(id, node.getNodeId());
                assertEquals(30305, node.getTcpPort());
                assertEquals(Instant.ofEpochSecond(1234567890), node.getLastSeen());
                assertTrue(node.isRpcAvailable()); assertTrue(node.isBeaconAvailable());
                assertEquals("Geth/test", node.getClientVersion());
                var evidence = store.findObservations(node.identity());
                assertEquals(1, evidence.size());
                assertEquals("legacy-node-record", evidence.get(0).provenance());
                assertEquals(node.getLastSeen(), evidence.get(0).observedAt());
            }
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT node_key,hello_json,observed_at FROM p2p_observations")) {
                assertTrue(rows.next()); assertEquals(id + "@192.0.2.1:30301", rows.getString(1));
                assertEquals("{\"listenPort\":0}", rows.getString(2)); assertEquals(1234567891, rows.getLong(3));
            }
            try (var rows = sql.executeQuery("SELECT node_id FROM nodes")) {
                assertTrue(rows.next()); assertEquals("0x" + id, rows.getString(1)); // Raw legacy field preserved.
            }
        }
    }
    @Test void legacyMissingTimestampDoesNotBecomeEpochObservation() throws Exception {
        String path=temp.resolve("legacy-no-time.db").toString();
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement()){
            sql.execute("""
                    CREATE TABLE nodes(key TEXT PRIMARY KEY,ip TEXT NOT NULL,udp_port INTEGER NOT NULL,
                    tcp_port INTEGER NOT NULL,node_id TEXT NOT NULL,country TEXT,latency INTEGER,
                    node_type TEXT,last_seen INTEGER,rpc_available INTEGER,beacon_available INTEGER,
                    client_version TEXT,syncing INTEGER,block_number INTEGER,pending_transactions INTEGER)
                    """);
            sql.execute("INSERT INTO nodes(key,ip,udp_port,tcp_port,node_id,last_seen) VALUES('192.0.2.1:30303','192.0.2.1',30303,30303,'"+"ab".repeat(64)+"',NULL)");
        }
        try(var store=new SqliteNodeStore(path)){
            var identity=new NodeIdentity("ab".repeat(64));
            assertTrue(store.findObservations(identity).isEmpty());
            assertTrue(store.inspectionHistory(identity,10,null).isEmpty());
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var sql=db.createStatement();var rows=sql.executeQuery("SELECT last_seen FROM nodes")){
                assertTrue(rows.next());assertEquals(null,rows.getObject(1));
            }
        }
    }
    @Test void failedMigrationRollsBackEvidenceAndKeyChanges() throws Exception {
        String path = temp.resolve("migration-rollback.db").toString();
        NodeRecord node = new NodeRecord("192.0.2.1", 30301, 30305, "ab".repeat(64));
        try (var store = new SqliteNodeStore(path)) {
            store.save(node); store.saveP2pObservation(node.getKey(), "{}", "{}");
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement()) {
            sql.execute("DELETE FROM schema_migrations WHERE version = 1");
            sql.execute("DELETE FROM discovery_observations");
            sql.execute("CREATE TRIGGER block_migration BEFORE UPDATE OF key ON nodes BEGIN SELECT RAISE(ABORT, 'blocked'); END");
        }
        assertThrows(java.sql.SQLException.class, () -> new SqliteNodeStore(path));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement()) {
            for (String table : List.of("discovery_observations", "schema_migrations")) {
                try (var rows = sql.executeQuery("SELECT COUNT(*) FROM " + table + (table.equals("schema_migrations") ? " WHERE version = 1" : ""))) {
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                }
            }
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM p2p_observations")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
            sql.execute("DROP TRIGGER block_migration");
        }
        try (var store = new SqliteNodeStore(path)) { assertEquals(1, store.count()); }
    }
    @Test void rejectedProjectionDoesNotLeaveAnOrphanObservation() throws Exception {
        String path = temp.resolve("save-rollback.db").toString();
        try (var store = new SqliteNodeStore(path);
             var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var sql = connection.createStatement()) {
            sql.execute("CREATE TRIGGER reject_node BEFORE INSERT ON nodes BEGIN SELECT RAISE(ABORT, 'blocked'); END");
            var evidence = observation("ab".repeat(64), "192.0.2.1", 30301, 30305, "discv4", Instant.now());
            assertThrows(IllegalStateException.class, () -> store.saveObservation(evidence));
            assertEquals(0, store.count());
            assertTrue(store.findObservations(evidence.identity()).isEmpty());
            sql.execute("DROP TRIGGER reject_node");
            store.saveObservation(evidence);
            assertEquals(List.of(evidence), store.findObservations(evidence.identity()));
        }
    }
}
