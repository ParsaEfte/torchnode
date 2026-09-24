package io.github.gavinruff007.torchnode.storage;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.model.NodeType;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteNodeStore implements NodeStore {
    private Connection connection;
    
    public SqliteNodeStore(String dbPath) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        initSchema();
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
             block_number, pending_transactions, p2p_connect_ms)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET
                ip = excluded.ip,
                udp_port = excluded.udp_port,
                tcp_port = excluded.tcp_port,
                node_id = excluded.node_id,
                last_seen = excluded.last_seen
        """;
        
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
            
            pstmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[DB] Failed to save node: " + e.getMessage());
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
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ex) {
                System.err.println("[DB] Rollback failed: " + ex.getMessage());
            }
            System.err.println("[DB] Batch save failed: " + e.getMessage());
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
        String sql = "SELECT * FROM nodes WHERE key = ?";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, key);
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
    
    @Override
    public void update(NodeRecord node) {
        String sql = """
            UPDATE nodes SET ip = ?, udp_port = ?, tcp_port = ?, node_id = ?, country = ?,
                latency = ?, node_type = ?, last_seen = ?, rpc_available = ?,
                beacon_available = ?, client_version = ?, syncing = ?, block_number = ?,
                pending_transactions = ?, p2p_connect_ms = ?
            WHERE key = ?
        """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, node.getIp());
            pstmt.setInt(2, node.getUdpPort());
            pstmt.setInt(3, node.getTcpPort());
            pstmt.setString(4, node.getNodeId());
            pstmt.setString(5, node.getCountry());
            pstmt.setObject(6, node.getLatency());
            pstmt.setString(7, node.getNodeType().name());
            pstmt.setLong(8, node.getLastSeen().getEpochSecond());
            pstmt.setInt(9, node.isRpcAvailable() ? 1 : 0);
            pstmt.setInt(10, node.isBeaconAvailable() ? 1 : 0);
            pstmt.setString(11, node.getClientVersion());
            pstmt.setObject(12, node.getSyncing() == null ? null : (node.getSyncing() ? 1 : 0));
            pstmt.setObject(13, node.getBlockNumber());
            pstmt.setObject(14, node.getPendingTransactions());
            pstmt.setObject(15, node.getP2pConnectMs());
            pstmt.setString(16, node.getKey());

            if (pstmt.executeUpdate() == 0) {
                save(node);
            }
        } catch (SQLException e) {
            System.err.println("[DB] Update failed: " + e.getMessage());
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
