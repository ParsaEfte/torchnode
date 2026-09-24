package io.github.gavinruff007.torchnode.storage;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.model.NodeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteNodeStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void rediscoveryDoesNotEraseInspectionData() throws Exception {
        try (NodeStore store = new SqliteNodeStore(tempDir.resolve("nodes.db").toString())) {
            NodeRecord inspected = new NodeRecord("127.0.0.1", 30303, 30303, "ab".repeat(64));
            store.save(inspected);
            inspected.setNodeType(NodeType.EXECUTION);
            inspected.setRpcAvailable(true);
            inspected.setClientVersion("Geth/v1.14");
            inspected.setLatency(42L);
            store.update(inspected);

            store.save(new NodeRecord("127.0.0.1", 30303, 30304, "cd".repeat(64)));

            NodeRecord stored = store.findByKey("127.0.0.1:30303").orElseThrow();
            assertTrue(stored.isRpcAvailable());
            assertEquals(NodeType.EXECUTION, stored.getNodeType());
            assertEquals("Geth/v1.14", stored.getClientVersion());
            assertEquals(42L, stored.getLatency());
            assertEquals(30304, stored.getTcpPort());
            assertEquals("cd".repeat(64), stored.getNodeId());
        }
    }

    @Test
    void authenticatedP2pObservationSurvivesRediscoveryAndIsRemovedWithNode() throws Exception {
        String path = tempDir.resolve("p2p.db").toString();
        NodeRecord node = new NodeRecord("127.0.0.1", 30303, 9999, "ab".repeat(64));
        try (SqliteNodeStore store = new SqliteNodeStore(path)) {
            store.save(node);
            store.saveP2pObservation(node.getKey(), "{\"clientId\":\"Geth/test\"}",
                    "{\"networkId\":1}");
            store.save(new NodeRecord("127.0.0.1", 30303, 9999, "ab".repeat(64)));
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
                 var query = connection.prepareStatement("SELECT hello_json, status_json FROM p2p_observations WHERE node_key = ?")) {
                query.setString(1, node.getKey());
                try (var rows = query.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("{\"clientId\":\"Geth/test\"}", rows.getString(1));
                    assertEquals("{\"networkId\":1}", rows.getString(2));
                }
            }
            store.delete(node.getKey());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var query = connection.prepareStatement("SELECT COUNT(*) FROM p2p_observations")) {
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
        }
    }
}
