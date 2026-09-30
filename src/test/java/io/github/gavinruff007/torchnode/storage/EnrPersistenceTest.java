package io.github.gavinruff007.torchnode.storage;

import io.github.gavinruff007.torchnode.enr.*;
import io.github.gavinruff007.torchnode.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EnrPersistenceTest {
    @TempDir Path temp;
    private EnrEvidence evidence(long seq, int tcp) throws Exception {
        return new EnrDecoder().decode(EnrFixtures.complete(seq, tcp), EnrFixtures.ID, Instant.ofEpochSecond(seq), "discv4 ENRResponse fixture");
    }
    @Test void roundTripKeepsRawTypedUnknownIpv6SequenceValidationAndConflictingDiscovery() throws Exception {
        String path = temp.resolve("enr.db").toString();
        var node = new NodeRecord("127.0.0.1", 30305, 30305, EnrFixtures.ID.nodeId());
        var older = evidence(1, 30303); var newer = evidence(2, 30304);
        byte[] raw = EnrFixtures.complete(3, 30301); raw[5] ^= 1;
        var invalid = new EnrDecoder().decode(raw, node.identity(), Instant.ofEpochSecond(3), "fixture invalid signature");
        var mismatched = new EnrDecoder().decode(EnrFixtures.complete(4, 30302), new NodeIdentity("cd".repeat(64)), Instant.ofEpochSecond(4), "fixture mismatch");
        try (var store = new SqliteNodeStore(path)) {
            store.save(node); store.saveP2pObservation(node.getKey(), "{\"listenPort\":0}", "{}");
            store.saveEnrEvidence(newer); store.saveEnrEvidence(older); store.saveEnrEvidence(invalid); store.saveEnrEvidence(mismatched);
            store.saveEnrEvidence(newer); // Exact cached evidence is idempotent.
        }
        try (var store = new SqliteNodeStore(path)) {
            assertEquals(List.of(newer, older, invalid), store.findEnrEvidence(node.identity()));
            var recent=store.enrHistory(node.identity(),1,0);
            assertEquals(invalid,recent.get(0).evidence());
            assertEquals(newer,store.enrHistory(node.identity(),1,recent.get(0).id()).get(0).evidence());
            assertEquals(List.of(mismatched), store.findEnrEvidence(mismatched.associatedIdentity()));
            assertEquals(30305, store.findByKey(node.getKey()).orElseThrow().getP2pEndpoint().port());
            var discovery = store.findObservations(node.identity());
            assertEquals(3, discovery.size());
            assertEquals(2, discovery.stream().filter(o -> o.source().equals("ENR")).count());
            assertEquals(4, newer.record().endpoints().size());
            assertEquals(NodeEndpoint.AddressFamily.IPV6, newer.record().endpoints().get(2).addressFamily());
            assertEquals("vendor", newer.record().entries().stream().filter(e -> !e.known()).findFirst().orElseThrow().keyText());
            store.clearCollectedData();
            assertTrue(store.findEnrEvidence(node.identity()).isEmpty()); assertTrue(store.findObservations(node.identity()).isEmpty());
            store.save(node); store.saveEnrEvidence(older);
            assertEquals(List.of(older), store.findEnrEvidence(node.identity()));
        }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = db.createStatement(); var rows = sql.executeQuery("PRAGMA integrity_check")) {
            assertTrue(rows.next()); assertEquals("ok", rows.getString(1));
        }
    }
    private String versionOneDatabase(String file) throws Exception {
        String path = temp.resolve(file).toString();
        var node = new NodeRecord("127.0.0.1", 30305, 30305, EnrFixtures.ID.nodeId());
        try (var store = new SqliteNodeStore(path)) { store.save(node); store.saveP2pObservation(node.getKey(), "{\"listenPort\":0}", "{}"); }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = db.createStatement()) {
            sql.execute("DROP TABLE enr_observations"); sql.execute("DROP TABLE network_enrichment"); sql.execute("DELETE FROM schema_migrations WHERE version IN (2,3)");
        }
        return path;
    }
    @Test void additiveVersionTwoMigrationPreservesLegacyDataAndReopens() throws Exception {
        String path = versionOneDatabase("migration.db");
        for (int i = 0; i < 2; i++) try (var store = new SqliteNodeStore(path)) {
            assertEquals(1, store.count()); assertEquals(1, store.findObservations(EnrFixtures.ID).size());
            assertTrue(store.findEnrEvidence(EnrFixtures.ID).isEmpty());
        }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = db.createStatement()) {
            try (var rows = sql.executeQuery("SELECT hello_json FROM p2p_observations")) { assertTrue(rows.next()); assertEquals("{\"listenPort\":0}", rows.getString(1)); }
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM schema_migrations")) { assertTrue(rows.next()); assertEquals(5, rows.getInt(1)); }
            try (var rows = sql.executeQuery("PRAGMA integrity_check")) { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
        }
    }
    @Test void migrationFailureRollsBackTableAndVersionWithoutDiscardingData() throws Exception {
        String path = versionOneDatabase("rollback.db");
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = db.createStatement()) {
            sql.execute("CREATE TRIGGER block_v2 BEFORE INSERT ON schema_migrations WHEN NEW.version = 2 BEGIN SELECT RAISE(ABORT, 'blocked'); END");
        }
        assertThrows(java.sql.SQLException.class, () -> new SqliteNodeStore(path));
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = db.createStatement()) {
            for (String table : List.of("nodes", "discovery_observations", "p2p_observations")) {
                try (var rows = sql.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); }
            }
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'enr_observations'")) { assertTrue(rows.next()); assertEquals(0, rows.getInt(1)); }
            sql.execute("DROP TRIGGER block_v2");
        }
        try (var store = new SqliteNodeStore(path)) { assertEquals(1, store.count()); }
    }
    @Test void enrSaveIsAtomicWithNeutralObservationInsertion() throws Exception {
        String path = temp.resolve("atomic.db").toString();
        try (var store = new SqliteNodeStore(path);
             var db = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = db.createStatement()) {
            sql.execute("CREATE TRIGGER reject_evidence BEFORE INSERT ON discovery_observations BEGIN SELECT RAISE(ABORT, 'blocked'); END");
            assertThrows(java.sql.SQLException.class, () -> store.saveEnrEvidence(evidence(1,30303)));
            assertTrue(store.findEnrEvidence(EnrFixtures.ID).isEmpty());
            sql.execute("DROP TRIGGER reject_evidence");
            store.saveEnrEvidence(evidence(1,30303)); assertEquals(1, store.findEnrEvidence(EnrFixtures.ID).size());
        }
    }
    @Test void clearFailureRollsBackEnrAndDerivedDiscoveryEvidence() throws Exception {
        String path = temp.resolve("clear-rollback.db").toString();
        try (var store = new SqliteNodeStore(path);
             var db = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = db.createStatement()) {
            store.save(new NodeRecord("127.0.0.1",30305,30305,EnrFixtures.ID.nodeId())); store.saveEnrEvidence(evidence(1,30303));
            sql.execute("CREATE TRIGGER block_clear BEFORE DELETE ON nodes BEGIN SELECT RAISE(ABORT, 'blocked'); END");
            assertThrows(java.sql.SQLException.class, store::clearCollectedData);
            assertEquals(1, store.findEnrEvidence(EnrFixtures.ID).size()); assertEquals(2, store.findObservations(EnrFixtures.ID).size());
        }
    }
}
