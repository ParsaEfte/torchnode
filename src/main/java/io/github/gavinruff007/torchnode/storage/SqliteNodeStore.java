package io.github.gavinruff007.torchnode.storage;
import io.github.gavinruff007.torchnode.model.EndpointAddress;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.model.NodeType;
import io.github.gavinruff007.torchnode.model.NodeIdentity;
import io.github.gavinruff007.torchnode.enr.EnrEvidence;
import io.github.gavinruff007.torchnode.model.NodeEndpoint;
import io.github.gavinruff007.torchnode.model.DiscoveryObservation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteNodeStore implements NodeStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Connection connection;
    
    public SqliteNodeStore(String dbPath) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        try { initSchema(); }
        catch (SQLException | RuntimeException e) {
            try { connection.close(); } catch (SQLException close) { e.addSuppressed(close); }
            throw e;
        }
    }
    
    private void initSchema() throws SQLException {
        String createTable = """
            CREATE TABLE IF NOT EXISTS nodes (
                key TEXT PRIMARY KEY,
                ip TEXT NOT NULL,
                udp_port INTEGER NOT NULL,
                tcp_port INTEGER NOT NULL,
                node_id TEXT NOT NULL,
                country TEXT,
                latency INTEGER,
                p2p_connect_ms INTEGER,
                node_type TEXT,
                last_seen INTEGER,
                rpc_available INTEGER,
                beacon_available INTEGER,
                client_version TEXT,
                syncing INTEGER,
                block_number INTEGER,
                pending_transactions INTEGER
            )
        """;
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTable);
            boolean hasP2pConnectMs = false;
            try (ResultSet columns = stmt.executeQuery("PRAGMA table_info(nodes)")) {
                while (columns.next()) {
                    if ("p2p_connect_ms".equals(columns.getString("name"))) hasP2pConnectMs = true;
                }
            }
            if (!hasP2pConnectMs) {
                try {
                    stmt.execute("ALTER TABLE nodes ADD COLUMN p2p_connect_ms INTEGER");
                } catch (SQLException e) {
                    if (e.getMessage() == null || !e.getMessage().contains("duplicate column name: p2p_connect_ms")) {
                        throw e;
                    }
                }
            }
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_country ON nodes(country)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_type ON nodes(node_type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_last_seen ON nodes(last_seen)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_ip ON nodes(ip)");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS p2p_observations (
                        node_key TEXT PRIMARY KEY,
                        observed_at INTEGER NOT NULL,
                        hello_json TEXT,
                        status_json TEXT
                    )
                    """);
        }
        migrateDiscoverySchema();
        migrateEnrSchema();
    }

    /** Additive, transactional migration; existing rows and authenticated evidence retain their data. */
    private void migrateDiscoverySchema() throws SQLException {
        connection.setAutoCommit(false);
        try (Statement stmt = connection.createStatement()) {
            boolean hasSource = false;
            try (ResultSet columns = stmt.executeQuery("PRAGMA table_info(nodes)")) {
                while (columns.next()) if ("discovery_source".equals(columns.getString("name"))) hasSource = true;
            }
            if (!hasSource) stmt.execute("ALTER TABLE nodes ADD COLUMN discovery_source TEXT NOT NULL DEFAULT 'discv4'");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS discovery_observations (
                        id INTEGER PRIMARY KEY,
                        node_id TEXT NOT NULL,
                        source TEXT NOT NULL,
                        provenance TEXT NOT NULL,
                        observed_at TEXT NOT NULL,
                        endpoints_json TEXT NOT NULL,
                        UNIQUE(node_id, source, provenance, observed_at, endpoints_json)
                    )
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_discovery_identity ON discovery_observations(node_id)");
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY)");
            boolean migrated;
            try (ResultSet rows = stmt.executeQuery("SELECT 1 FROM schema_migrations WHERE version = 1")) {
                migrated = rows.next();
            }
            if (!migrated) {
                List<String[]> keys = new ArrayList<>();
                try (ResultSet rows = stmt.executeQuery("SELECT * FROM nodes")) {
                    while (rows.next()) {
                        NodeRecord node = new NodeRecord(rows.getString("ip"), rows.getInt("udp_port"),
                                rows.getInt("tcp_port"), rows.getString("node_id"));
                        node.setLastSeen(Instant.ofEpochSecond(rows.getLong("last_seen")));
                        // Preserve raw legacy IDs in nodes; normalized identity is used only for keys/evidence.
                        keys.add(new String[] { rows.getString("key"), node.getKey() });
                        insertObservation(legacyObservation(node));
                    }
                }
                for (String[] key : keys) {
                    try (PreparedStatement update = connection.prepareStatement("UPDATE nodes SET key = ? WHERE key = ?");
                         PreparedStatement p2p = connection.prepareStatement("UPDATE p2p_observations SET node_key = ? WHERE node_key = ?")) {
                        update.setString(1, key[1]); update.setString(2, key[0]); update.executeUpdate();
                        p2p.setString(1, key[1]); p2p.setString(2, key[0]); p2p.executeUpdate();
                    }
                }
                stmt.execute("INSERT INTO schema_migrations(version) VALUES (1)");
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally { connection.setAutoCommit(true); }
    }

    /** Version 2 only adds ENR evidence; existing projections and observation tables are untouched. */
    private void migrateEnrSchema() throws SQLException {
        connection.setAutoCommit(false);
        try (Statement stmt = connection.createStatement()) {
            boolean migrated;
            try (ResultSet rows = stmt.executeQuery("SELECT 1 FROM schema_migrations WHERE version = 2")) { migrated = rows.next(); }
            if (!migrated) {
                stmt.execute("""
                        CREATE TABLE enr_observations (
                            id INTEGER PRIMARY KEY,
                            node_id TEXT NOT NULL,
                            observed_at TEXT NOT NULL,
                            provenance TEXT NOT NULL,
                            outcome TEXT NOT NULL,
                            sequence TEXT,
                            signature_validation TEXT NOT NULL,
                            identity_comparison TEXT NOT NULL,
                            raw_rlp_hex TEXT,
                            evidence_json TEXT NOT NULL,
                            UNIQUE(node_id, observed_at, provenance, evidence_json)
                        )
                        """);
                stmt.execute("CREATE INDEX idx_enr_identity ON enr_observations(node_id)");
                stmt.execute("INSERT INTO schema_migrations(version) VALUES (2)");
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback(); throw e;
        } finally { connection.setAutoCommit(true); }
    }

    public void saveEnrEvidence(EnrEvidence evidence) throws SQLException {
        boolean ownTransaction = connection.getAutoCommit();
        if (ownTransaction) connection.setAutoCommit(false);
        Savepoint point = connection.setSavepoint();
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO enr_observations(node_id, observed_at, provenance, outcome, sequence,
                    signature_validation, identity_comparison, raw_rlp_hex, evidence_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, evidence.associatedIdentity().nodeId());
            insert.setString(2, evidence.observedAt().toString()); insert.setString(3, evidence.provenance());
            insert.setString(4, evidence.outcome()); insert.setString(5, evidence.record() == null ? null : evidence.record().sequence());
            insert.setString(6, evidence.signature().name()); insert.setString(7, evidence.identityComparison().name());
            insert.setString(8, evidence.rawRlpHex());
            try { insert.setString(9, JSON.writeValueAsString(evidence.toMap())); }
            catch (Exception e) { throw new SQLException("Cannot encode ENR evidence", e); }
            insert.executeUpdate();
            if (evidence.observation().isPresent()) insertObservation(evidence.observation().get());
            connection.releaseSavepoint(point);
            if (ownTransaction) connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback(point); connection.releaseSavepoint(point);
            if (ownTransaction) connection.rollback();
            throw e;
        } finally { if (ownTransaction) connection.setAutoCommit(true); }
    }

    public List<EnrEvidence> findEnrEvidence(NodeIdentity identity) {
        List<EnrEvidence> evidence = new ArrayList<>();
        if (!identity.available()) return List.of();
        try (PreparedStatement query = connection.prepareStatement("SELECT evidence_json FROM enr_observations WHERE node_id = ? ORDER BY id")) {
            query.setString(1, identity.nodeId());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) evidence.add(EnrEvidence.fromMap(JSON.readValue(rows.getString(1),
                        new TypeReference<java.util.Map<String, Object>>() {}), JSON));
            }
        } catch (Exception e) { throw new IllegalStateException("Cannot read ENR evidence", e); }
        return List.copyOf(evidence);
    }

    private DiscoveryObservation legacyObservation(NodeRecord node) {
        NodeEndpoint.AddressFamily family = EndpointAddress.family(node.getIp());
        return new DiscoveryObservation(node.identity(), node.getDiscoverySource(), List.of(
                new NodeEndpoint(node.getIp(), NodeEndpoint.Transport.UDP, node.getUdpPort(), family, NodeEndpoint.Purpose.DISCOVERY),
                node.getP2pEndpoint()), node.getLastSeen(), "legacy-node-record");
    }

    private void insertObservation(DiscoveryObservation observation) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT OR IGNORE INTO discovery_observations(node_id, source, provenance, observed_at, endpoints_json)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, observation.identity().nodeId());
            insert.setString(2, observation.source());
            insert.setString(3, observation.provenance());
            insert.setString(4, observation.observedAt().toString());
            try { insert.setString(5, JSON.writeValueAsString(observation.endpoints())); }
            catch (Exception e) { throw new SQLException("Cannot serialize endpoint evidence", e); }
            insert.executeUpdate();
        }
    }

    @Override public void saveObservation(DiscoveryObservation observation) {
        save(new NodeRecord(observation));
    }

    @Override public List<DiscoveryObservation> findObservations(NodeIdentity identity) {
        List<DiscoveryObservation> observations = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT * FROM discovery_observations WHERE node_id = ? ORDER BY observed_at, id")) {
            query.setString(1, identity.nodeId());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    observations.add(new DiscoveryObservation(identity, rows.getString("source"),
                            JSON.readValue(rows.getString("endpoints_json"), new TypeReference<List<NodeEndpoint>>() {}),
                            Instant.parse(rows.getString("observed_at")), rows.getString("provenance")));
                }
            }
        } catch (Exception e) { throw new IllegalStateException("Cannot read discovery evidence", e); }
        observations.sort(java.util.Comparator.comparing(DiscoveryObservation::observedAt));
        return List.copyOf(observations);
    }

    /** Extend the existing JSON slot without erasing previously authenticated Hello/Status on failure. */
    public void saveEndpointInspection(String nodeKey, java.util.Map<String,Object> hello,
            java.util.Map<String,Object> status, List<java.util.Map<String,Object>> attempts) throws Exception {
        java.util.Map<String,Object> envelope = new java.util.LinkedHashMap<>();
        String savedStatus = null;
        try (PreparedStatement query = connection.prepareStatement("SELECT observed_at, hello_json, status_json FROM p2p_observations WHERE node_key = ?")) {
            query.setString(1, nodeKey);
            try (ResultSet row = query.executeQuery()) {
                if (row.next()) {
                    if (row.getString("hello_json") != null) envelope.putAll(JSON.readValue(row.getString("hello_json"), new TypeReference<java.util.Map<String,Object>>() {}));
                    if (envelope.containsKey("clientId")) envelope.putIfAbsent("_helloObservedAt", Instant.ofEpochSecond(row.getLong("observed_at")).toString());
                    savedStatus = row.getString("status_json");
                }
            }
        }
        if (hello != null) { envelope.putAll(hello); envelope.put("_helloObservedAt", Instant.now().toString()); }
        envelope.put("_evidenceType", "endpoint-inspection");
        envelope.put("_endpointAttempts", attempts);
        saveP2pObservation(nodeKey, JSON.writeValueAsString(envelope), status == null ? savedStatus : JSON.writeValueAsString(status));
    }

    /** Last authenticated Hello/ETH Status observation; discovery updates do not overwrite it. */
    public void saveP2pObservation(String nodeKey, String helloJson, String statusJson) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO p2p_observations(node_key, observed_at, hello_json, status_json)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(node_key) DO UPDATE SET
                    observed_at = excluded.observed_at,
                    hello_json = excluded.hello_json,
                    status_json = excluded.status_json
                """)) {
            statement.setString(1, nodeKey);
            statement.setLong(2, Instant.now().getEpochSecond());
            statement.setString(3, helloJson);
            statement.setString(4, statusJson);
            statement.executeUpdate();
        }
    }
    
    @Override
    public void save(NodeRecord node) {
        String sql = """
            INSERT INTO nodes
            (key, ip, udp_port, tcp_port, node_id, country, latency, node_type, 
             last_seen, rpc_available, beacon_available, client_version, syncing, 
             block_number, pending_transactions, p2p_connect_ms, discovery_source)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET
                ip = excluded.ip,
                udp_port = excluded.udp_port,
                tcp_port = excluded.tcp_port,
                node_id = excluded.node_id,
                discovery_source = excluded.discovery_source,
                last_seen = excluded.last_seen
            WHERE (excluded.last_seen >= nodes.last_seen
                AND (nodes.discovery_source != 'discv4' OR excluded.discovery_source != 'discv5'))
                OR (nodes.discovery_source = 'discv5' AND excluded.discovery_source = 'discv4')
        """;
        
        Savepoint savepoint = null;
        boolean ownTransaction = false;
        try {
            ownTransaction = connection.getAutoCommit();
            if (ownTransaction) connection.setAutoCommit(false);
            savepoint = connection.setSavepoint();
            for (DiscoveryObservation observation : node.getObservations().isEmpty()
                    ? List.of(legacyObservation(node)) : node.getObservations()) insertObservation(observation);
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, node.getKey());
                pstmt.setString(2, node.getIp());
                pstmt.setInt(3, node.getUdpPort());
                pstmt.setInt(4, node.getTcpPort());
                pstmt.setString(5, node.getNodeId());
                pstmt.setString(6, node.getCountry());
                pstmt.setObject(7, node.getLatency());
                pstmt.setString(8, node.getNodeType().name());
                pstmt.setLong(9, node.getLastSeen().getEpochSecond());
                pstmt.setInt(10, node.isRpcAvailable() ? 1 : 0);
                pstmt.setInt(11, node.isBeaconAvailable() ? 1 : 0);
                pstmt.setString(12, node.getClientVersion());
                pstmt.setObject(13, node.getSyncing() == null ? null : (node.getSyncing() ? 1 : 0));
                pstmt.setObject(14, node.getBlockNumber());
                pstmt.setObject(15, node.getPendingTransactions());
                pstmt.setObject(16, node.getP2pConnectMs());
                pstmt.setString(17, node.getDiscoverySource());

                pstmt.executeUpdate();
            }
            // A discv5 receipt updates liveness time without replacing a selected discv4 endpoint.
            if (node.getDiscoverySource().equals("discv5")) try (PreparedStatement seen = connection.prepareStatement(
                    "UPDATE nodes SET last_seen = MAX(last_seen, ?) WHERE key = ?")) {
                seen.setLong(1, node.getLastSeen().getEpochSecond()); seen.setString(2, node.getKey()); seen.executeUpdate();
            }
            connection.releaseSavepoint(savepoint);
            if (ownTransaction) connection.commit();
        } catch (SQLException | RuntimeException e) {
            if (savepoint != null) {
                try { connection.rollback(savepoint); connection.releaseSavepoint(savepoint); }
                catch (SQLException rollback) { e.addSuppressed(rollback); }
            }
            if (ownTransaction) {
                try { connection.rollback(); } catch (SQLException rollback) { e.addSuppressed(rollback); }
            }
            throw new IllegalStateException("Failed to save discovery observation", e);
        } finally {
            if (ownTransaction) {
                try { connection.setAutoCommit(true); }
                catch (SQLException e) { throw new IllegalStateException("Cannot restore transaction state", e); }
            }
        }
    }
    
    @Override
    public void saveAll(List<NodeRecord> nodes) {
        try {
            connection.setAutoCommit(false);
            for (NodeRecord node : nodes) {
                save(node);
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            try {
                connection.rollback();
            } catch (SQLException ex) {
                System.err.println("[DB] Rollback failed: " + ex.getMessage());
            }
            throw new IllegalStateException("Batch save failed", e);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException e) {
                System.err.println("[DB] Failed to restore autocommit: " + e.getMessage());
            }
        }
    }
    
    @Override
    public Optional<NodeRecord> findByKey(String key) {
        String sql = "SELECT * FROM nodes WHERE key = ? OR ip || ':' || udp_port = ? ORDER BY last_seen DESC LIMIT 1";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.setString(2, key);
            ResultSet rs = pstmt.executeQuery();
            
            if (rs.next()) {
                return Optional.of(mapResultSetToNode(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Find failed: " + e.getMessage());
        }
        
        return Optional.empty();
    }

    
    @Override
    public List<NodeRecord> findAll() {
        List<NodeRecord> nodes = new ArrayList<>();
        String sql = "SELECT * FROM nodes ORDER BY last_seen DESC";
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                nodes.add(mapResultSetToNode(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DB] FindAll failed: " + e.getMessage());
        }
        
        return nodes;
    }
    
    @Override
    public List<NodeRecord> findByCountry(String country) {
        List<NodeRecord> nodes = new ArrayList<>();
        String sql = "SELECT * FROM nodes WHERE country = ? ORDER BY last_seen DESC";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, country);
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                nodes.add(mapResultSetToNode(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DB] FindByCountry failed: " + e.getMessage());
        }
        
        return nodes;
    }
    
    @Override
    public List<NodeRecord> findByType(NodeType type) {
        List<NodeRecord> nodes = new ArrayList<>();
        String sql = "SELECT * FROM nodes WHERE node_type = ? ORDER BY last_seen DESC";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, type.name());
            ResultSet rs = pstmt.executeQuery();
            
            while (rs.next()) {
                nodes.add(mapResultSetToNode(rs));
            }
        } catch (SQLException e) {
            System.err.println("[DB] FindByType failed: " + e.getMessage());
        }
        
        return nodes;
    }
    
    /** Inspection updates service evidence only; it cannot replace discovery endpoint claims. */
    @Override
    public void update(NodeRecord node) {
        String sql = """
            UPDATE nodes SET country = ?, latency = ?, node_type = ?, rpc_available = ?,
                beacon_available = ?, client_version = ?, syncing = ?, block_number = ?,
                pending_transactions = ?, p2p_connect_ms = ?
            WHERE key = ?
            """;
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, node.getCountry());
            pstmt.setObject(2, node.getLatency());
            pstmt.setString(3, node.getNodeType().name());
            pstmt.setInt(4, node.isRpcAvailable() ? 1 : 0);
            pstmt.setInt(5, node.isBeaconAvailable() ? 1 : 0);
            pstmt.setString(6, node.getClientVersion());
            pstmt.setObject(7, node.getSyncing() == null ? null : (node.getSyncing() ? 1 : 0));
            pstmt.setObject(8, node.getBlockNumber());
            pstmt.setObject(9, node.getPendingTransactions());
            pstmt.setObject(10, node.getP2pConnectMs());
            pstmt.setString(11, node.getKey());
            if (pstmt.executeUpdate() == 0) save(node);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update inspection evidence", e);
        }
    }

    @Override
    public void delete(String key) {
        String sql = "DELETE FROM nodes WHERE key = ?";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.executeUpdate();
            try (PreparedStatement observation = connection.prepareStatement(
                    "DELETE FROM p2p_observations WHERE node_key = ?")) {
                observation.setString(1, key);
                observation.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("[DB] Delete failed: " + e.getMessage());
        }
    }

    /** Clear only collected Observatory data, leaving the schema and settings intact. */
    public void clearCollectedData() throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM enr_observations");
            statement.executeUpdate("DELETE FROM discovery_observations");
            statement.executeUpdate("DELETE FROM p2p_observations");
            statement.executeUpdate("DELETE FROM nodes");
            connection.commit();
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                e.addSuppressed(rollback);
            }
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }
    
    @Override
    public int count() {
        String sql = "SELECT COUNT(*) FROM nodes";
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            if (rs.next()) {
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            System.err.println("[DB] Count failed: " + e.getMessage());
        }
        
        return 0;
    }
    
    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            System.err.println("[DB] Close failed: " + e.getMessage());
        }
    }
    
    private NodeRecord mapResultSetToNode(ResultSet rs) throws SQLException {
        NodeRecord node = new NodeRecord(
            rs.getString("ip"),
            rs.getInt("udp_port"),
            rs.getInt("tcp_port"),
            rs.getString("node_id")
        );
        
        node.setDiscoverySource(rs.getString("discovery_source"));
        node.setObservations(findObservations(node.identity()).stream()
                .filter(o -> node.identity().available() || o.endpoints().stream()
                        .anyMatch(e -> e.address().equals(node.getIp()) && e.port() == node.getUdpPort()))
                .toList());
        node.getObservations().stream().filter(o -> o.source().equals(node.getDiscoverySource()))
                .filter(o -> o.endpoints().stream().anyMatch(e -> e.purpose() == NodeEndpoint.Purpose.DISCOVERY
                        && e.address().equals(node.getIp()) && e.port() == node.getUdpPort()))
                .filter(o -> o.endpoints().stream().anyMatch(e -> e.purpose() == NodeEndpoint.Purpose.P2P
                        && e.port() == node.getTcpPort()))
                .reduce((older, newer) -> newer).ifPresent(node::selectObservation);
        node.setCountry(rs.getString("country"));
        
        long latencyValue = rs.getLong("latency");
        if (!rs.wasNull()) {
            node.setLatency(latencyValue);
        }
        long p2pConnectMs = rs.getLong("p2p_connect_ms");
        if (!rs.wasNull()) node.setP2pConnectMs(p2pConnectMs);
        
        String typeStr = rs.getString("node_type");
        if (typeStr != null) {
            node.setNodeType(NodeType.valueOf(typeStr));
        }
        
        node.setLastSeen(Instant.ofEpochSecond(rs.getLong("last_seen")));
        node.setRpcAvailable(rs.getInt("rpc_available") == 1);
        node.setBeaconAvailable(rs.getInt("beacon_available") == 1);
        node.setClientVersion(rs.getString("client_version"));
        
        int syncingValue = rs.getInt("syncing");
        if (!rs.wasNull()) {
            node.setSyncing(syncingValue == 1);
        }
        
        long blockNum = rs.getLong("block_number");
        if (!rs.wasNull()) {
            node.setBlockNumber(blockNum);
        }
        
        int pendingTx = rs.getInt("pending_transactions");
        if (!rs.wasNull()) {
            node.setPendingTransactions(pendingTx);
        }
        
        return node;
    }

    @Override
    public NodeRecord findByIp(String ip) {
        String sql = "SELECT * FROM nodes WHERE ip = ? LIMIT 1";

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, ip);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                return mapResultSetToNode(rs);
            }
        } catch (SQLException e) {
            System.err.println("[DB] FindByIp failed: " + e.getMessage());
        }

        return null;
    }

}
