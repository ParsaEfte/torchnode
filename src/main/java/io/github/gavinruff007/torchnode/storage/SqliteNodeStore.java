package io.github.gavinruff007.torchnode.storage;
import io.github.gavinruff007.torchnode.model.EndpointAddress;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.model.NodeType;
import io.github.gavinruff007.torchnode.model.NodeIdentity;
import io.github.gavinruff007.torchnode.enr.EnrEvidence;
import io.github.gavinruff007.torchnode.model.NodeEndpoint;
import io.github.gavinruff007.torchnode.model.DiscoveryObservation;
import io.github.gavinruff007.torchnode.changes.ChangeDeriver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.sql.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SqliteNodeStore implements NodeStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CHANGE_RULE_VERSION = 1;
    private Connection connection;
    private boolean historicalSchemaReady;
    private boolean changeSchemaReady;
    
    public SqliteNodeStore(String dbPath) throws SQLException {
        this(dbPath, false);
    }

    /** Open an existing frozen schema without running migrations or allowing writes. */
    public SqliteNodeStore(String dbPath, boolean readOnly) throws SQLException {
        connection = DriverManager.getConnection(readOnly
                ? "jdbc:sqlite:" + java.nio.file.Path.of(dbPath).toAbsolutePath().toUri() + "?mode=ro"
                : "jdbc:sqlite:" + dbPath);
        try { try(var statement=connection.createStatement()){statement.execute("PRAGMA foreign_keys=ON");}
            if (!readOnly) initSchema();
            else { historicalSchemaReady=true; changeSchemaReady=true; }
        }
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
        migrateNetworkEnrichmentSchema();
        migrateHistoricalSchema();
        historicalSchemaReady = true;
        migrateChangeSchema();
        changeSchemaReady = true;
    }

    /** Version 5 stores only reproducible derived comparisons, never wire evidence. */
    private void migrateChangeSchema() throws SQLException {
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT 1 FROM schema_migrations WHERE version=5")) {
            if(rows.next())return;
        }
        connection.setAutoCommit(false);
        try(var statement=connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE change_events(
                      id TEXT PRIMARY KEY, node_id TEXT NOT NULL, domain TEXT NOT NULL,
                      change_type TEXT NOT NULL, subject TEXT NOT NULL, observation_kind TEXT NOT NULL,
                      previous_observation_id TEXT, current_observation_id TEXT NOT NULL,
                      previous_observed_at TEXT, current_observed_at TEXT NOT NULL,
                      previous_value TEXT, current_value TEXT NOT NULL,
                      source TEXT NOT NULL, endpoint TEXT, address_family TEXT,
                      derivation_version INTEGER NOT NULL)
                    """);
            statement.execute("CREATE INDEX idx_change_identity_time ON change_events(node_id,("+
                    sortableTime("current_observed_at")+") DESC,id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_change_inspection_order ON inspection_runs(node_id,("+
                    sortableTime("started_at")+") DESC,id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_change_enr_order ON enr_observations(node_id,("+
                    sortableTime("observed_at")+") DESC,id DESC)");
            statement.execute("INSERT INTO schema_migrations(version) VALUES(5)");
            connection.commit();
        }catch(SQLException | RuntimeException e){connection.rollback();throw e;}
        finally{connection.setAutoCommit(true);}
    }

    public record ChangeEvent(String id,String nodeId,String domain,String changeType,String subject,
                              String observationKind,String previousObservationId,String currentObservationId,
                              String previousObservedAt,String currentObservedAt,String previousValue,
                              String currentValue,String source,String endpoint,String addressFamily,
                              int derivationVersion) {}

    /** Cursor is the last returned event ID; this query never loads source payloads. */
    public List<ChangeEvent> changeHistory(NodeIdentity identity,int limit,String beforeId) {
        if(!identity.available() || limit<1 || limit>100)throw new IllegalArgumentException("Change limit must be 1..100 and identity available");
        var result=new ArrayList<ChangeEvent>();
        String at=sortableTime("current_observed_at");
        String cursorAt="(SELECT "+at+" FROM change_events WHERE id=?)";
        String sql="SELECT * FROM change_events WHERE node_id=? AND (? IS NULL OR "+
                at+"<"+cursorAt+" OR ("+at+"="+cursorAt+" AND id<?)) "+
                "ORDER BY "+at+" DESC,id DESC LIMIT ?";
        try(var query=connection.prepareStatement(sql)){
            query.setString(1,identity.nodeId());query.setString(2,beforeId);query.setString(3,beforeId);
            query.setString(4,beforeId);query.setString(5,beforeId);query.setInt(6,limit);
            try(var rows=query.executeQuery()){while(rows.next())result.add(new ChangeEvent(rows.getString("id"),rows.getString("node_id"),
                    rows.getString("domain"),rows.getString("change_type"),rows.getString("subject"),
                    rows.getString("observation_kind"),rows.getString("previous_observation_id"),
                    rows.getString("current_observation_id"),rows.getString("previous_observed_at"),
                    rows.getString("current_observed_at"),rows.getString("previous_value"),rows.getString("current_value"),
                    rows.getString("source"),rows.getString("endpoint"),rows.getString("address_family"),
                    rows.getInt("derivation_version")));}
        }catch(SQLException e){throw new IllegalStateException("Cannot load change history",e);}
        return List.copyOf(result);
    }

    /** Version 4 records each completed inspection while sharing identical evidence payloads. */
    private void migrateHistoricalSchema() throws SQLException {
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT 1 FROM schema_migrations WHERE version=4")) {
            if (rows.next()) return;
        }
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE inspection_evidence(hash TEXT PRIMARY KEY, evidence_json TEXT NOT NULL)");
            statement.execute("""
                    CREATE TABLE inspection_runs(
                      id TEXT PRIMARY KEY, node_id TEXT NOT NULL, node_key TEXT NOT NULL,
                      started_at TEXT, completed_at TEXT, trigger TEXT NOT NULL,
                      discovery_source TEXT NOT NULL,
                      evidence_hash TEXT NOT NULL REFERENCES inspection_evidence(hash),
                      timing_json TEXT NOT NULL)
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_inspection_identity_time ON inspection_runs(node_id, started_at DESC, id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_inspection_time ON inspection_runs(started_at DESC, id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_discovery_identity_time ON discovery_observations(node_id, observed_at DESC, id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_discovery_source_time ON discovery_observations(source, observed_at DESC, id DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_enr_identity_time ON enr_observations(node_id, observed_at DESC, id DESC)");
            statement.execute("""
                    CREATE TABLE discovery_endpoint_index(
                      observation_id INTEGER NOT NULL REFERENCES discovery_observations(id) ON DELETE CASCADE,
                      node_id TEXT NOT NULL, address TEXT NOT NULL, address_family TEXT NOT NULL,
                      transport TEXT NOT NULL, purpose TEXT NOT NULL, port INTEGER NOT NULL,
                      observed_at TEXT NOT NULL,
                      PRIMARY KEY(observation_id,address,transport,purpose,port))
                    """);
            statement.execute("CREATE INDEX idx_discovery_endpoint_time ON discovery_endpoint_index(address,observed_at DESC,observation_id DESC)");
            try(var rows=statement.executeQuery("SELECT id,node_id,observed_at,endpoints_json FROM discovery_observations")) {
                while(rows.next()) {
                    try {
                        var endpoints=JSON.readValue(rows.getString(4),new TypeReference<List<NodeEndpoint>>() {});
                        indexEndpoints(rows.getLong(1),rows.getString(2),rows.getString(3),endpoints);
                    }catch(java.io.IOException e){throw new SQLException("Cannot index persisted discovery endpoints",e);}
                }
            }
            statement.execute("""
                    CREATE TABLE network_enrichment_lookups(
                      lookup_id TEXT PRIMARY KEY, address TEXT NOT NULL,
                      dataset_key TEXT NOT NULL, looked_up_at TEXT NOT NULL,
                      evidence_json TEXT NOT NULL)
                    """);
            try(var rows=statement.executeQuery("SELECT address,dataset_key,looked_up_at,evidence_json FROM network_enrichment")){
                while(rows.next())try(var insert=connection.prepareStatement("INSERT OR IGNORE INTO network_enrichment_lookups VALUES(?,?,?,?,?)")){
                    String json=rows.getString(4);insert.setString(1,sha256(json));
                    insert.setString(2,rows.getString(1));insert.setString(3,rows.getString(2));
                    insert.setString(4,rows.getString(3));insert.setString(5,json);insert.executeUpdate();
                }
            }
            statement.execute("CREATE INDEX idx_enrichment_address_time ON network_enrichment_lookups(address,looked_up_at DESC)");
            statement.execute("""
                    CREATE TABLE inspection_enrichment_context(
                      run_id TEXT NOT NULL REFERENCES inspection_runs(id),
                      lookup_id TEXT NOT NULL REFERENCES network_enrichment_lookups(lookup_id),
                      PRIMARY KEY(run_id,lookup_id))
                    """);
            statement.execute("INSERT INTO schema_migrations(version) VALUES(4)");
            connection.commit();
        } catch (SQLException | RuntimeException e) { connection.rollback(); throw e; }
        finally { connection.setAutoCommit(true); }
    }

    public record InspectionHistory(String id, String nodeId, String nodeKey, String startedAt,
                                    String completedAt, String trigger, String discoverySource,
                                    java.util.Map<String,Object> evidence) {}
    public record EndpointHistory(long observationId,String nodeId,String address,String addressFamily,
                                  String transport,String purpose,int port,String observedAt,String source,String provenance) {}
    public record DiscoveryHistory(long id,DiscoveryObservation observation) {}
    public record EnrHistory(long id,EnrEvidence evidence) {}
    public record EnrichmentHistory(String lookupId,String address,String datasetKey,String lookedUpAt,
                                    io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment evidence) {}

    /** Address/dataset lookup occurrences associated with any discovery endpoint for this identity. */
    public List<EnrichmentHistory> enrichmentHistory(NodeIdentity identity,int limit,String beforeId) {
        if(!identity.available() || limit<1 || limit>100)
            throw new IllegalArgumentException("History limit must be 1..100 and identity available");
        String at=sortableTime("l.looked_up_at");
        String cursorAt="(SELECT "+sortableTime("looked_up_at")+" FROM network_enrichment_lookups WHERE lookup_id=?)";
        String sql="SELECT l.* FROM network_enrichment_lookups l WHERE l.address IN "+
                "(SELECT DISTINCT address FROM discovery_endpoint_index WHERE node_id=?) AND "+
                "(? IS NULL OR "+at+"<"+cursorAt+" OR ("+at+"="+cursorAt+" AND l.lookup_id<?)) "+
                "ORDER BY "+at+" DESC,l.lookup_id DESC LIMIT ?";
        var result=new ArrayList<EnrichmentHistory>();
        try(var query=connection.prepareStatement(sql)) {
            query.setString(1,identity.nodeId());query.setString(2,beforeId);
            query.setString(3,beforeId);query.setString(4,beforeId);query.setString(5,beforeId);
            query.setInt(6,limit);
            try(var rows=query.executeQuery()) {
                while(rows.next())result.add(new EnrichmentHistory(
                        rows.getString("lookup_id"),rows.getString("address"),rows.getString("dataset_key"),
                        rows.getString("looked_up_at"),JSON.readValue(rows.getString("evidence_json"),
                        io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment.class)));
            }
        } catch(Exception e) {throw new IllegalStateException("Cannot load enrichment history",e);}
        return List.copyOf(result);
    }

    public List<DiscoveryHistory> discoveryHistory(NodeIdentity identity,int limit,long beforeId) {
        if(!identity.available() || limit<1 || limit>100)throw new IllegalArgumentException("History limit must be 1..100 and identity available");
        var result=new ArrayList<DiscoveryHistory>();
        String at=sortableTime("d.observed_at"),cursorAt="(SELECT "+sortableTime("observed_at")+
                " FROM discovery_observations WHERE id=?)";
        String sql="""
                SELECT d.* FROM discovery_observations d WHERE d.node_id=? AND
                  (?=0 OR %s<%s OR (%s=%s AND d.id<?))
                ORDER BY %s DESC,d.id DESC LIMIT ?
                """.formatted(at,cursorAt,at,cursorAt,at);
        try(var query=connection.prepareStatement(sql)){
            query.setString(1,identity.nodeId());query.setLong(2,beforeId);query.setLong(3,beforeId);
            query.setLong(4,beforeId);query.setLong(5,beforeId);query.setInt(6,limit);
            try(var rows=query.executeQuery()){while(rows.next())result.add(new DiscoveryHistory(rows.getLong("id"),
                    new DiscoveryObservation(identity,rows.getString("source"),JSON.readValue(rows.getString("endpoints_json"),
                            new TypeReference<List<NodeEndpoint>>() {}),Instant.parse(rows.getString("observed_at")),
                            rows.getString("provenance"))));}
        }catch(Exception e){throw new IllegalStateException("Cannot load discovery history",e);}
        return List.copyOf(result);
    }

    public List<EnrHistory> enrHistory(NodeIdentity identity,int limit,long beforeId) {
        if(!identity.available() || limit<1 || limit>100)throw new IllegalArgumentException("History limit must be 1..100 and identity available");
        var result=new ArrayList<EnrHistory>();
        String at=sortableTime("e.observed_at"),cursorAt="(SELECT "+sortableTime("observed_at")+
                " FROM enr_observations WHERE id=?)";
        String sql="""
                SELECT e.* FROM enr_observations e WHERE e.node_id=? AND
                  (?=0 OR %s<%s OR (%s=%s AND e.id<?))
                ORDER BY %s DESC,e.id DESC LIMIT ?
                """.formatted(at,cursorAt,at,cursorAt,at);
        try(var query=connection.prepareStatement(sql)){
            query.setString(1,identity.nodeId());query.setLong(2,beforeId);query.setLong(3,beforeId);
            query.setLong(4,beforeId);query.setLong(5,beforeId);query.setInt(6,limit);
            try(var rows=query.executeQuery()){while(rows.next())result.add(new EnrHistory(rows.getLong("id"),
                    EnrEvidence.fromMap(JSON.readValue(rows.getString("evidence_json"),
                            new TypeReference<java.util.Map<String,Object>>() {}),JSON)));}
        }catch(Exception e){throw new IllegalStateException("Cannot load ENR history",e);}
        return List.copyOf(result);
    }

    /** Address-indexed factual endpoint occurrences; no disappearance inference is made. */
    public List<EndpointHistory> endpointHistory(String address,int limit,long beforeObservationId) {
        if(limit<1 || limit>100)throw new IllegalArgumentException("History limit must be 1..100");
        String normalized=EndpointAddress.parse(address).getHostAddress();
        String sql="""
                SELECT e.*,d.source,d.provenance FROM discovery_endpoint_index e
                JOIN discovery_observations d ON d.id=e.observation_id
                WHERE e.address=? AND (?=0 OR e.observed_at<(SELECT observed_at FROM discovery_observations WHERE id=?)
                  OR (e.observed_at=(SELECT observed_at FROM discovery_observations WHERE id=?) AND e.observation_id<?))
                ORDER BY e.observed_at DESC,e.observation_id DESC LIMIT ?
                """;
        var result=new ArrayList<EndpointHistory>();
        try(var query=connection.prepareStatement(sql)){
            query.setString(1,normalized);query.setLong(2,beforeObservationId);query.setLong(3,beforeObservationId);
            query.setLong(4,beforeObservationId);query.setLong(5,beforeObservationId);query.setInt(6,limit);
            try(var rows=query.executeQuery()){while(rows.next())result.add(new EndpointHistory(rows.getLong("observation_id"),
                    rows.getString("node_id"),rows.getString("address"),rows.getString("address_family"),
                    rows.getString("transport"),rows.getString("purpose"),rows.getInt("port"),
                    rows.getString("observed_at"),rows.getString("source"),rows.getString("provenance")));}
        }catch(SQLException e){throw new IllegalStateException("Cannot load endpoint history",e);}
        return List.copyOf(result);
    }

    private static String sha256(String value) throws SQLException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) { throw new SQLException("SHA-256 unavailable", e); }
    }

    private void removeChanges(String nodeId,String kind) throws SQLException {
        try(var delete=connection.prepareStatement("DELETE FROM change_events WHERE node_id=? AND observation_kind=?")){
            delete.setString(1,nodeId);delete.setString(2,kind);delete.executeUpdate();
        }
    }

    private void insertChange(String nodeId,String kind,String domain,String type,String subject,String previousId,
                              String currentId,String previousAt,String currentAt,String previousValue,
                              String currentValue,String source,String endpoint,String family) throws SQLException {
        if(currentAt==null || currentValue==null)return;
        String id=sha256(String.join("\u0000",nodeId,kind,type,subject,
                previousId==null?"":previousId,currentId));
        try(var insert=connection.prepareStatement("""
                INSERT OR IGNORE INTO change_events VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)){
            insert.setString(1,id);insert.setString(2,nodeId);insert.setString(3,domain);
            insert.setString(4,type);insert.setString(5,subject);insert.setString(6,kind);
            insert.setString(7,previousId);insert.setString(8,currentId);
            insert.setString(9,previousAt);insert.setString(10,currentAt);
            insert.setString(11,previousValue);insert.setString(12,currentValue);
            insert.setString(13,source);insert.setString(14,endpoint);insert.setString(15,family);
            insert.setInt(16,CHANGE_RULE_VERSION);
            insert.executeUpdate();
        }
    }

    private record FactGroup(ChangeDeriver.Fact fact,boolean ambiguous) {}
    private record FactPair(FactGroup newer,FactGroup current) {}

    /** Instant.toString uses 0/3/6/9 fractional digits; pad them for exact SQL ordering. */
    private static String sortableTime(String column) {
        return "substr("+column+",1,19) || CASE WHEN substr("+column+",20,1)='.' THEN " +
                "substr(substr("+column+",21,instr(substr("+column+",21),'Z')-1) || '000000000',1,9) " +
                "ELSE '000000000' END";
    }

    private List<InspectionHistory> changeOrderedInspections(NodeIdentity identity,int offset) throws Exception {
        String sql="SELECT r.*,e.evidence_json FROM inspection_runs r JOIN inspection_evidence e ON e.hash=r.evidence_hash "+
                "WHERE r.node_id=? ORDER BY "+sortableTime("r.started_at")+" DESC,r.id DESC LIMIT 100 OFFSET ?";
        try(var query=connection.prepareStatement(sql)){
            query.setString(1,identity.nodeId());query.setInt(2,offset);return readInspectionHistory(query);
        }
    }

    private List<EnrHistory> changeOrderedEnrs(NodeIdentity identity,int offset) throws Exception {
        var result=new ArrayList<EnrHistory>();
        String sql="SELECT id,evidence_json FROM enr_observations WHERE node_id=? ORDER BY "+
                sortableTime("observed_at")+" DESC,id DESC LIMIT 100 OFFSET ?";
        try(var query=connection.prepareStatement(sql)){
            query.setString(1,identity.nodeId());query.setInt(2,offset);
            try(var rows=query.executeQuery()){while(rows.next())result.add(new EnrHistory(rows.getLong(1),
                    EnrEvidence.fromMap(JSON.readValue(rows.getString(2),
                            new TypeReference<java.util.Map<String,Object>>() {}),JSON)));}
        }
        return result;
    }

    private void emitFactTransition(String nodeId,FactGroup older,FactGroup newer) throws SQLException {
        if(older==null || newer==null || older.ambiguous() || newer.ambiguous())return;
        var a=older.fact();var b=newer.fact();
        if(ChangeDeriver.comparable(a,b) && !a.value().equals(b.value()))
            insertChange(nodeId,"INSPECTION",a.domain(),a.type(),a.subject(),
                    a.reference(),b.reference(),a.observedAt(),b.observedAt(),
                    a.value(),b.value(),b.source(),b.endpoint(),b.family());
    }

    /** Rebuild one identity's inspection comparisons in the writer's transaction. */
    private void rebuildInspectionChanges(NodeIdentity identity) throws SQLException {
        if(!changeSchemaReady || !identity.available())return;
        removeChanges(identity.nodeId(),"INSPECTION");
        var groups=new java.util.HashMap<String,FactPair>();
        int offset=0;
        try {
            while(true) {
                var page=changeOrderedInspections(identity,offset);
                if(page.isEmpty())break;
                for(var run:page) {
                    var facts=new ArrayList<>(ChangeDeriver.inspectionFacts(run));
                    facts.sort(ChangeDeriver.NEWEST_FIRST);
                    for(var older:facts) {
                        String key=older.key();var pair=groups.get(key);
                        if(pair==null){groups.put(key,new FactPair(null,new FactGroup(older,false)));continue;}
                        var current=pair.current().fact();
                        int order=Instant.parse(older.observedAt()).compareTo(Instant.parse(current.observedAt()));
                        if(order==0){
                            groups.put(key,new FactPair(pair.newer(),new FactGroup(current,
                                    pair.current().ambiguous() || !older.value().equals(current.value()))));
                        }else if(order<0){
                            emitFactTransition(identity.nodeId(),pair.current(),pair.newer());
                            groups.put(key,new FactPair(pair.current(),new FactGroup(older,false)));
                        }else groups.put(key,new FactPair(null,new FactGroup(older,true)));
                    }
                }
                if(page.size()<100)break;
                offset+=page.size();
            }
            for(var pair:groups.values())emitFactTransition(identity.nodeId(),pair.current(),pair.newer());
        }catch(Exception e){throw e instanceof SQLException sql?sql:new SQLException("Cannot derive inspection comparisons",e);}
    }

    private record EnrGroup(EnrHistory evidence,boolean ambiguous) {}

    private void rebuildEnrChanges(NodeIdentity identity) throws SQLException {
        if(!changeSchemaReady || !identity.available())return;
        removeChanges(identity.nodeId(),"ENR");
        EnrGroup newer=null,current=null;int offset=0;
        try {
            while(true){
                var page=changeOrderedEnrs(identity,offset);
                if(page.isEmpty())break;
                for(var older:page) {
                    if(!older.evidence().usable())continue;
                    if(current==null){current=new EnrGroup(older,false);continue;}
                    int order=older.evidence().observedAt().compareTo(current.evidence().evidence().observedAt());
                    if(order==0){
                        current=new EnrGroup(current.evidence(),current.ambiguous() ||
                                !older.evidence().record().sequence().equals(current.evidence().evidence().record().sequence()));
                    }else if(order<0){
                        emitEnrTransition(identity.nodeId(),current,newer);
                        newer=current;current=new EnrGroup(older,false);
                    }else{newer=null;current=new EnrGroup(older,true);}
                }
                if(page.size()<100)break;offset+=page.size();
            }
            emitEnrTransition(identity.nodeId(),current,newer);
        }catch(Exception e){throw e instanceof SQLException sql?sql:new SQLException("Cannot derive ENR comparisons",e);}
    }

    private void emitEnrTransition(String nodeId,EnrGroup older,EnrGroup newer) throws SQLException {
        if(older==null || newer==null || older.ambiguous() || newer.ambiguous() ||
                !older.evidence().evidence().observedAt().isBefore(newer.evidence().evidence().observedAt()))return;
        var oldSeq=new java.math.BigInteger(older.evidence().evidence().record().sequence());
        var newSeq=new java.math.BigInteger(newer.evidence().evidence().record().sequence());
        if(newSeq.compareTo(oldSeq)>0)insertChange(nodeId,"ENR","ENR","ENR_SEQUENCE_ADVANCED","trusted-sequence",
                Long.toString(older.evidence().id()),Long.toString(newer.evidence().id()),older.evidence().evidence().observedAt().toString(),
                newer.evidence().evidence().observedAt().toString(),oldSeq.toString(),newSeq.toString(),"ENR",null,null);
    }

    private void rebuildDiscoveryChanges(NodeIdentity identity) throws SQLException {
        if(!changeSchemaReady || !identity.available())return;
        removeChanges(identity.nodeId(),"DISCOVERY");
        var first=new java.util.HashMap<String,EndpointHistory>();
        EndpointHistory firstIpv6=null;
        try(var query=connection.prepareStatement("""
                SELECT e.*,d.source,d.provenance FROM discovery_endpoint_index e
                JOIN discovery_observations d ON d.id=e.observation_id
                WHERE e.node_id=?
                """)){
            query.setString(1,identity.nodeId());
            try(var rows=query.executeQuery()){while(rows.next()){
                var endpoint=new EndpointHistory(rows.getLong("observation_id"),rows.getString("node_id"),
                        rows.getString("address"),rows.getString("address_family"),rows.getString("transport"),
                        rows.getString("purpose"),rows.getInt("port"),rows.getString("observed_at"),
                        rows.getString("source"),rows.getString("provenance"));
                String key=endpoint.source()+"|"+endpoint.addressFamily()+"|"+endpoint.transport()+"|"+
                        endpoint.purpose()+"|"+endpoint.address()+"|"+endpoint.port();
                first.merge(key,endpoint,(a,b)->earlierEndpoint(a,b)?a:b);
                if("IPV6".equals(endpoint.addressFamily()) &&
                        (firstIpv6==null || !earlierEndpoint(firstIpv6,endpoint)))firstIpv6=endpoint;
            }}
        }
        for(var entry:first.entrySet()){
            var endpoint=entry.getValue();
            insertChange(identity.nodeId(),"DISCOVERY","DISCOVERY","ENDPOINT_FIRST_OBSERVED",entry.getKey(),null,
                    Long.toString(endpoint.observationId()),null,endpoint.observedAt(),null,
                    endpoint.address()+":"+endpoint.port(),endpoint.source(),endpoint.address(),endpoint.addressFamily());
        }
        if(firstIpv6!=null)insertChange(identity.nodeId(),"DISCOVERY","DISCOVERY","IPV6_FIRST_OBSERVED","identity-ipv6",null,
                Long.toString(firstIpv6.observationId()),null,firstIpv6.observedAt(),null,
                firstIpv6.address(),firstIpv6.source(),firstIpv6.address(),firstIpv6.addressFamily());
    }

    private static boolean earlierEndpoint(EndpointHistory a,EndpointHistory b) {
        int order=Instant.parse(a.observedAt()).compareTo(Instant.parse(b.observedAt()));
        return order<0 || (order==0 && a.observationId()<b.observationId());
    }

    /** Deterministic correction/rebuild path; original observations are never changed. */
    public void rebuildChanges(NodeIdentity identity) throws SQLException {
        if(!identity.available())throw new IllegalArgumentException("Canonical identity required");
        if(!connection.getAutoCommit())throw new IllegalStateException("Change rebuild transaction already active");
        connection.setAutoCommit(false);
        try{
            rebuildDiscoveryChanges(identity);
            rebuildEnrChanges(identity);
            rebuildInspectionChanges(identity);
            connection.commit();
        }catch(SQLException | RuntimeException e){connection.rollback();throw e;}
        finally{connection.setAutoCommit(true);}
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String,Object> separateTiming(java.util.Map<String,Object> evidence,
                                                                 java.util.Map<String,Object> timing) {
        var facts=JSON.convertValue(evidence,new TypeReference<java.util.Map<String,Object>>() {});
        var attempts=(List<java.util.Map<String,Object>>)facts.get("endpointAttempts");
        if(attempts!=null)timing.put("endpointAttemptTimes",attempts.stream().map(a->a.remove("observedAt")).toList());
        for(String source:List.of("rpcAttempts","beaconAttempts")) {
            var values=(List<java.util.Map<String,Object>>)facts.get(source);
            if(values!=null)timing.put(source+"Times",values.stream().map(a->a.remove("attemptedAt")).toList());
        }
        for(String source:List.of("rpc","beacon")) {
            var value=(java.util.Map<String,Object>)facts.get(source);
            if(value!=null)timing.put(source+"ObservedAt",value.remove("observedAt"));
        }
        return facts;
    }

    @SuppressWarnings("unchecked")
    private static void restoreTiming(java.util.Map<String,Object> evidence,java.util.Map<String,Object> timing) {
        var attempts=(List<java.util.Map<String,Object>>)evidence.get("endpointAttempts");
        var times=(List<Object>)timing.get("endpointAttemptTimes");
        if(attempts!=null && times!=null)for(int i=0;i<Math.min(attempts.size(),times.size());i++)attempts.get(i).put("observedAt",times.get(i));
        for(String source:List.of("rpcAttempts","beaconAttempts")) {
            var values=(List<java.util.Map<String,Object>>)evidence.get(source);
            var stamps=(List<Object>)timing.get(source+"Times");
            if(values!=null && stamps!=null)for(int i=0;i<Math.min(values.size(),stamps.size());i++)values.get(i).put("attemptedAt",stamps.get(i));
        }
        for(String source:List.of("rpc","beacon")) {
            var value=(java.util.Map<String,Object>)evidence.get(source);
            if(value!=null && timing.containsKey(source+"ObservedAt"))value.put("observedAt",timing.get(source+"ObservedAt"));
        }
    }

    /** One transaction covers the run, its evidence and the latest inspection projection. */
    public void saveInspectionRun(NodeRecord node, String id, String startedAt, String completedAt,
                                  java.util.Map<String,Object> evidence, EnrEvidence enr,
                                  java.util.Map<String,Object> hello, java.util.Map<String,Object> status,
                                  List<java.util.Map<String,Object>> attempts,
                                  List<java.util.Map<String,Object>> apis) throws Exception {
        saveInspectionRun(node,id,startedAt,completedAt,"deep-inspection",evidence,enr,hello,status,attempts,apis);
    }

    public void saveInspectionRun(NodeRecord node, String id, String startedAt, String completedAt,
                                  String trigger,java.util.Map<String,Object> evidence, EnrEvidence enr,
                                  java.util.Map<String,Object> hello, java.util.Map<String,Object> status,
                                  List<java.util.Map<String,Object>> attempts,
                                  List<java.util.Map<String,Object>> apis) throws Exception {
        if (!connection.getAutoCommit()) throw new IllegalStateException("Inspection transaction already active");
        var timing=new java.util.LinkedHashMap<String,Object>();
        String json = JSON.writeValueAsString(separateTiming(evidence,timing));
        String hash = sha256(json);
        connection.setAutoCommit(false);
        try {
            try (var payload = connection.prepareStatement("INSERT OR IGNORE INTO inspection_evidence(hash,evidence_json) VALUES(?,?)")) {
                payload.setString(1, hash); payload.setString(2, json); payload.executeUpdate();
            }
            try (var run = connection.prepareStatement("INSERT INTO inspection_runs(id,node_id,node_key,started_at,completed_at,trigger,discovery_source,evidence_hash,timing_json) VALUES(?,?,?,?,?,?,?,?,?)")) {
                run.setString(1,id); run.setString(2,node.identity().nodeId()); run.setString(3,node.getKey());
                run.setString(4,startedAt); run.setString(5,completedAt); run.setString(6,trigger);
                run.setString(7,node.getDiscoverySource());run.setString(8,hash);
                run.setString(9,JSON.writeValueAsString(timing)); run.executeUpdate();
            }
            update(node);
            if (enr != null) saveEnrEvidence(enr);
            if (!attempts.isEmpty() || !apis.isEmpty()) saveEndpointInspection(node.getKey(),hello,status,attempts,apis);
            rebuildInspectionChanges(node.identity());
            connection.commit();
        } catch (Exception e) { connection.rollback(); throw e; }
        finally { connection.setAutoCommit(true); }
    }

    /** Cursor is the last returned run ID; ordering is timestamp then ID, with NULL last. */
    public List<InspectionHistory> inspectionHistory(NodeIdentity identity, int limit, String beforeId) {
        if (!identity.available() || limit < 1 || limit > 100) throw new IllegalArgumentException("History limit must be 1..100 and identity available");
        String at="COALESCE("+sortableTime("r.started_at")+",'')";
        String cursorAt="COALESCE((SELECT "+sortableTime("started_at")+" FROM inspection_runs WHERE id=?),'')";
        String sql = """
                SELECT r.*, e.evidence_json FROM inspection_runs r JOIN inspection_evidence e ON e.hash=r.evidence_hash
                WHERE r.node_id=? AND (? IS NULL OR
                  (%s < %s OR (%s = %s AND r.id < ?)))
                ORDER BY %s DESC, r.id DESC LIMIT ?
                """.formatted(at,cursorAt,at,cursorAt,at);
        try (var query = connection.prepareStatement(sql)) {
            query.setString(1,identity.nodeId()); query.setString(2,beforeId); query.setString(3,beforeId);
            query.setString(4,beforeId); query.setString(5,beforeId); query.setInt(6,limit);
            return readInspectionHistory(query);
        } catch (Exception e) { throw new IllegalStateException("Cannot load inspection history",e); }
    }

    public List<InspectionHistory> recentInspectionHistory(int limit) {
        if(limit<1 || limit>100)throw new IllegalArgumentException("History limit must be 1..100");
        try(var query=connection.prepareStatement("""
                SELECT r.*,e.evidence_json FROM inspection_runs r JOIN inspection_evidence e ON e.hash=r.evidence_hash
                ORDER BY r.started_at DESC,r.id DESC LIMIT ?
                """)){
            query.setInt(1,limit);return readInspectionHistory(query);
        }catch(Exception e){throw new IllegalStateException("Cannot load recent inspections",e);}
    }

    public Optional<InspectionHistory> findInspectionRun(String id) {
        if(id==null || id.isBlank())return Optional.empty();
        try(var query=connection.prepareStatement("""
                SELECT r.*,e.evidence_json FROM inspection_runs r JOIN inspection_evidence e ON e.hash=r.evidence_hash
                WHERE r.id=? LIMIT 1
                """)){
            query.setString(1,id);
            var rows=readInspectionHistory(query);return rows.isEmpty()?Optional.empty():Optional.of(rows.get(0));
        }catch(Exception e){throw new IllegalStateException("Cannot load inspection run",e);}
    }

    public List<InspectionHistory> inspectionHistoryBetween(NodeIdentity identity,Instant fromInclusive,
                                                             Instant beforeExclusive,int limit) {
        if(!identity.available() || limit<1 || limit>100 || !fromInclusive.isBefore(beforeExclusive))
            throw new IllegalArgumentException("Invalid history range or limit");
        try(var query=connection.prepareStatement("""
                SELECT r.*,e.evidence_json FROM inspection_runs r JOIN inspection_evidence e ON e.hash=r.evidence_hash
                WHERE r.node_id=? AND r.started_at>=? AND r.started_at<?
                ORDER BY r.started_at DESC,r.id DESC LIMIT ?
                """)){
            query.setString(1,identity.nodeId());query.setString(2,fromInclusive.toString());
            query.setString(3,beforeExclusive.toString());query.setInt(4,limit);
            return readInspectionHistory(query);
        }catch(Exception e){throw new IllegalStateException("Cannot load ranged inspections",e);}
    }

    private static List<InspectionHistory> readInspectionHistory(PreparedStatement query) throws Exception {
        var result=new ArrayList<InspectionHistory>();
        try(var rows=query.executeQuery()){while(rows.next()){
            var evidence=JSON.readValue(rows.getString("evidence_json"),new TypeReference<java.util.Map<String,Object>>() {});
            var timing=JSON.readValue(rows.getString("timing_json"),new TypeReference<java.util.Map<String,Object>>() {});
            restoreTiming(evidence,timing);
            result.add(new InspectionHistory(rows.getString("id"),rows.getString("node_id"),rows.getString("node_key"),
                    rows.getString("started_at"),rows.getString("completed_at"),rows.getString("trigger"),
                    rows.getString("discovery_source"),evidence));
        }}
        return List.copyOf(result);
    }

    /** Version 3 adds reusable address/dataset evidence without changing endpoint/identity rows. */
    private void migrateNetworkEnrichmentSchema() throws SQLException {
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT 1 FROM schema_migrations WHERE version=3")){if(rows.next())return;}
        connection.setAutoCommit(false);
        try(var statement=connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS network_enrichment(address TEXT NOT NULL, address_family TEXT NOT NULL, dataset_key TEXT NOT NULL, looked_up_at TEXT NOT NULL, evidence_json TEXT NOT NULL, PRIMARY KEY(address,dataset_key))");
            statement.execute("INSERT OR IGNORE INTO schema_migrations(version) VALUES(3)");
            connection.commit();
        } catch(SQLException | RuntimeException e) { connection.rollback();throw e; }
        finally { connection.setAutoCommit(true); }
    }

    public void saveNetworkEnrichment(io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment evidence) throws SQLException {
        String time=evidence.country().lookedUpAt().compareTo(evidence.asn().lookedUpAt())>=0?evidence.country().lookedUpAt():evidence.asn().lookedUpAt();
        String json;
        try{json=JSON.writeValueAsString(evidence);}catch(Exception e){throw new SQLException("Cannot encode enrichment",e);}
        boolean own=connection.getAutoCommit();if(own)connection.setAutoCommit(false);
        try {
            try(var statement=connection.prepareStatement("INSERT OR IGNORE INTO network_enrichment_lookups(lookup_id,address,dataset_key,looked_up_at,evidence_json) VALUES(?,?,?,?,?)")) {
                statement.setString(1,sha256(json));statement.setString(2,evidence.address());
                statement.setString(3,evidence.datasetKey());statement.setString(4,time);
                statement.setString(5,json);statement.executeUpdate();
            }
            try(var statement=connection.prepareStatement("INSERT INTO network_enrichment VALUES(?,?,?,?,?) ON CONFLICT(address,dataset_key) DO UPDATE SET address_family=excluded.address_family, looked_up_at=excluded.looked_up_at, evidence_json=excluded.evidence_json")) {
                statement.setString(1,evidence.address());statement.setString(2,evidence.addressFamily().name());statement.setString(3,evidence.datasetKey());
                statement.setString(4,time);statement.setString(5,json);statement.executeUpdate();
            }
            if(own)connection.commit();
        }catch(SQLException e){if(own)connection.rollback();throw e;}
        finally{if(own)connection.setAutoCommit(true);}
    }

    /** Attach the exact cached lookup used by an inspection, without changing its observation time. */
    public void linkInspectionEnrichment(String runId,io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment evidence) throws SQLException {
        String json;
        try{json=JSON.writeValueAsString(evidence);}catch(Exception e){throw new SQLException("Cannot encode enrichment",e);}
        try(var statement=connection.prepareStatement("INSERT OR IGNORE INTO inspection_enrichment_context(run_id,lookup_id) VALUES(?,?)")){
            statement.setString(1,runId);statement.setString(2,sha256(json));statement.executeUpdate();
        }
    }

    public List<io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment> inspectionEnrichmentContext(String runId) {
        var result=new ArrayList<io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment>();
        try(var query=connection.prepareStatement("""
                SELECT l.evidence_json FROM inspection_enrichment_context c
                JOIN network_enrichment_lookups l ON l.lookup_id=c.lookup_id
                WHERE c.run_id=? ORDER BY l.address,l.dataset_key,l.looked_up_at,l.lookup_id
                """)){
            query.setString(1,runId);
            try(var rows=query.executeQuery()){while(rows.next())result.add(JSON.readValue(rows.getString(1),
                    io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment.class));}
        }catch(Exception e){throw new IllegalStateException("Cannot load inspection enrichment context",e);}
        return List.copyOf(result);
    }

    public Optional<io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment> findNetworkEnrichment(String address,String datasetKey) {
        String query=datasetKey==null?"SELECT evidence_json FROM network_enrichment WHERE address=? ORDER BY looked_up_at DESC,dataset_key LIMIT 1":"SELECT evidence_json FROM network_enrichment WHERE address=? AND dataset_key=?";
        try(var statement=connection.prepareStatement(query)) {
            statement.setString(1,EndpointAddress.parse(address).getHostAddress());if(datasetKey!=null)statement.setString(2,datasetKey);
            try(var rows=statement.executeQuery()){return rows.next()?Optional.of(JSON.readValue(rows.getString(1),io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment.class)):Optional.empty();}
        } catch(Exception e){throw new IllegalStateException("Cannot load enrichment",e);}
    }

    /** Read-only view joining address enrichment to original endpoint provenance; never initiates a lookup. */
    public List<java.util.Map<String,Object>> networkEnrichmentView(NodeIdentity identity) {
        var analysis=io.github.gavinruff007.torchnode.analysis.EndpointAnalysis.fromStore(this,identity);
        return analysis.evidence().stream().filter(e->e.endpoint()!=null).map(e->{
            var view=new java.util.LinkedHashMap<String,Object>();view.put("endpoint",e.endpoint());view.put("source",e.source());
            view.put("observedAt",e.observedAt());view.put("provenance",e.provenance());
            view.put("enrichment",findNetworkEnrichment(e.endpoint().address(),null).orElse(null));return (java.util.Map<String,Object>)view;
        }).toList();
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
                        long legacyLastSeen=rows.getLong("last_seen");
                        boolean hasObservedTime=!rows.wasNull() && legacyLastSeen>0;
                        if(hasObservedTime)node.setLastSeen(Instant.ofEpochSecond(legacyLastSeen));
                        // Preserve raw legacy IDs in nodes; normalized identity is used only for keys/evidence.
                        keys.add(new String[] { rows.getString("key"), node.getKey() });
                        if(hasObservedTime)insertObservation(legacyObservation(node));
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
            int inserted=insert.executeUpdate();
            if (evidence.observation().isPresent()) insertObservation(evidence.observation().get());
            if(inserted>0)rebuildEnrChanges(evidence.associatedIdentity());
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
        int inserted;
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
            inserted=insert.executeUpdate();
        }
        if(historicalSchemaReady)try(var query=connection.prepareStatement("SELECT id FROM discovery_observations WHERE node_id=? AND source=? AND provenance=? AND observed_at=? AND endpoints_json=?")) {
            query.setString(1,observation.identity().nodeId());query.setString(2,observation.source());query.setString(3,observation.provenance());
            query.setString(4,observation.observedAt().toString());
            try{query.setString(5,JSON.writeValueAsString(observation.endpoints()));}catch(Exception e){throw new SQLException("Cannot serialize endpoint evidence",e);}
            try(var rows=query.executeQuery()){if(rows.next())indexEndpoints(rows.getLong(1),observation.identity().nodeId(),observation.observedAt().toString(),observation.endpoints());}
        }
        if(inserted>0)rebuildDiscoveryChanges(observation.identity());
    }

    private void indexEndpoints(long observationId,String nodeId,String observedAt,List<NodeEndpoint> endpoints) throws SQLException {
        try(var insert=connection.prepareStatement("INSERT OR IGNORE INTO discovery_endpoint_index VALUES(?,?,?,?,?,?,?,?)")){
            for(var endpoint:endpoints){
                insert.setLong(1,observationId);insert.setString(2,nodeId);insert.setString(3,endpoint.address());
                insert.setString(4,endpoint.addressFamily().name());insert.setString(5,endpoint.transport().name());
                insert.setString(6,endpoint.purpose().name());insert.setInt(7,endpoint.port());insert.setString(8,observedAt);
                insert.addBatch();
            }
            insert.executeBatch();
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
        saveEndpointInspection(nodeKey, hello, status, attempts, List.of());
    }

    public void saveEndpointInspection(String nodeKey, java.util.Map<String,Object> hello,
            java.util.Map<String,Object> status, List<java.util.Map<String,Object>> attempts,
            List<java.util.Map<String,Object>> apis) throws Exception {
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
        if (!apis.isEmpty()) envelope.put("_apiEndpointEvidence", apis);
        saveP2pObservation(nodeKey, JSON.writeValueAsString(envelope), status == null ? savedStatus : JSON.writeValueAsString(status));
    }

    /** Identity-scoped original evidence, not persisted derived conclusions. */
    public java.util.Map<String,List<java.util.Map<String,Object>>> findEndpointEvidence(NodeIdentity identity) {
        var result = new java.util.LinkedHashMap<String,List<java.util.Map<String,Object>>>();
        for (String key : List.of("attempts", "apis", "retainedHellos")) result.put(key,new java.util.ArrayList<>());
        if (!identity.available()) return result;
        try (var query = connection.prepareStatement("SELECT p.hello_json,p.node_key FROM p2p_observations p JOIN nodes n ON p.node_key=n.key WHERE n.node_id=? ORDER BY p.node_key")) {
            query.setString(1, identity.nodeId());
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    String raw = rows.getString(1); if (raw == null) continue;
                    try {
                        var json = JSON.readTree(raw);
                        for (var pair : java.util.Map.of("_endpointAttempts","attempts","_apiEndpointEvidence","apis").entrySet()) {
                            var values=json.path(pair.getKey());
                            if (values.isArray()) for(var value:values) {
                                var observation=JSON.convertValue(value,new TypeReference<java.util.Map<String,Object>>() {});
                                observation.put("_nodeKey",rows.getString(2)); result.get(pair.getValue()).add(observation);
                            }
                        }
                        if (json.has("clientId") && json.has("listenPort")) {
                            var hello = new java.util.LinkedHashMap<String,Object>();
                            hello.put("listenPort",JSON.convertValue(json.get("listenPort"),Object.class));
                            hello.put("observedAt",json.path("_helloObservedAt").asText(null));
                            hello.put("_nodeKey",rows.getString(2));
                            // Only expose a detached legacy claim when its successful session is no longer retained.
                            var attempts=json.path("_endpointAttempts");
                            boolean associated=false;
                            if(attempts.isArray()) for(var attempt:attempts)
                                for(var stage:attempt.path("diagnostics")) if(stage.path("name").asText().equals("RLPx Hello") && stage.path("state").asText().equals("PASS")) associated=true;
                            if(!associated) result.get("retainedHellos").add(hello);
                        }
                    } catch (RuntimeException | java.io.IOException malformed) { result.get("attempts").add(java.util.Map.of("malformed","Stored endpoint envelope cannot be decoded")); }
                }
            }
        } catch(SQLException e) { throw new IllegalStateException("Cannot load endpoint evidence",e); }
        return result;
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
            WHERE ((excluded.last_seen > COALESCE(nodes.last_seen,-1)
                OR (excluded.last_seen = nodes.last_seen AND
                    (excluded.tcp_port > nodes.tcp_port OR
                     (excluded.tcp_port = nodes.tcp_port AND excluded.node_id > nodes.node_id))))
                AND (nodes.discovery_source != 'discv4' OR excluded.discovery_source != 'discv5'))
                OR (nodes.discovery_source = 'discv5' AND excluded.discovery_source = 'discv4'
                    AND excluded.last_seen IS NOT NULL)
        """;
        
        Savepoint savepoint = null;
        boolean ownTransaction = false;
        try {
            ownTransaction = connection.getAutoCommit();
            if (ownTransaction) connection.setAutoCommit(false);
            savepoint = connection.setSavepoint();
            for (DiscoveryObservation observation : node.getObservations().isEmpty()
                    ? node.getLastSeen()==null?List.<DiscoveryObservation>of():List.of(legacyObservation(node))
                    : node.getObservations()) insertObservation(observation);
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, node.getKey());
                pstmt.setString(2, node.getIp());
                pstmt.setInt(3, node.getUdpPort());
                pstmt.setInt(4, node.getTcpPort());
                pstmt.setString(5, node.getNodeId());
                pstmt.setString(6, node.getCountry());
                pstmt.setObject(7, node.getLatency());
                pstmt.setString(8, node.getNodeType().name());
                pstmt.setObject(9, node.getLastSeen()==null?null:node.getLastSeen().getEpochSecond());
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
            if (node.getDiscoverySource().equals("discv5") && node.getLastSeen()!=null) try (PreparedStatement seen = connection.prepareStatement(
                    "UPDATE nodes SET last_seen = MAX(COALESCE(last_seen,-1), ?) WHERE key = ?")) {
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
        String sql = "SELECT * FROM nodes WHERE key = ? OR ip || ':' || udp_port = ? ORDER BY (key = ?) DESC,last_seen DESC,key DESC LIMIT 1";
        
        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, key);
            pstmt.setString(2, key);
            pstmt.setString(3, key);
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
        String sql = "SELECT * FROM nodes ORDER BY last_seen DESC,key DESC";
        
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
            statement.executeUpdate("DELETE FROM discovery_endpoint_index");
            statement.executeUpdate("DELETE FROM inspection_enrichment_context");
            statement.executeUpdate("DELETE FROM change_events");
            statement.executeUpdate("DELETE FROM inspection_runs");
            statement.executeUpdate("DELETE FROM inspection_evidence");
            statement.executeUpdate("DELETE FROM network_enrichment_lookups");
            statement.executeUpdate("DELETE FROM discovery_observations");
            statement.executeUpdate("DELETE FROM p2p_observations");
            statement.executeUpdate("DELETE FROM nodes");
            statement.executeUpdate("DELETE FROM network_enrichment");
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
        
        long lastSeen=rs.getLong("last_seen");node.setLastSeen(rs.wasNull() || lastSeen<=0?null:Instant.ofEpochSecond(lastSeen));
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
