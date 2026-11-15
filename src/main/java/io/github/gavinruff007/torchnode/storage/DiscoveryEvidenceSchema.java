package io.github.gavinruff007.torchnode.storage;

import java.sql.*;

/** Additive v6 only; no historical backfill or legacy-table writes. */
final class DiscoveryEvidenceSchema {
    private static final String ID="length(%s)=128 AND %s NOT GLOB '*[^0-9a-f]*'";
    private static String identity(String name){return "("+name+" IS NULL OR ("+ID.formatted(name,name)+"))";}
    static void migrate(Connection db)throws SQLException {
        try(var s=db.createStatement();var r=s.executeQuery("SELECT 1 FROM schema_migrations WHERE version=6")){if(r.next())return;}
        db.setAutoCommit(false);
        try(var s=db.createStatement()) {
            s.execute("CREATE TABLE observer_installations(id TEXT PRIMARY KEY CHECK(length(id)=36))");
            s.execute("CREATE TABLE discovery_runs(id TEXT PRIMARY KEY, observer_id TEXT NOT NULL REFERENCES observer_installations(id), started_at TEXT NOT NULL, local_identity TEXT CHECK("+identity("local_identity")+"), key_lifecycle TEXT NOT NULL, UNIQUE(id,observer_id))");
            s.execute("""
                CREATE TABLE discovery_exchange_evidence(
                  id TEXT PRIMARY KEY CHECK(length(id)=64), receipt_id TEXT NOT NULL,
                  run_id TEXT REFERENCES discovery_runs(id), protocol TEXT NOT NULL CHECK(protocol IN ('discv4','discv5')),
                  kind TEXT NOT NULL CHECK(kind IN ('TRACE','REQUEST','RESPONSE','HEALTH')), contract_version INTEGER NOT NULL,
                  observed_at TEXT, raw_json TEXT NOT NULL CHECK(length(CAST(raw_json AS BLOB))<=60000),
                  raw_hash TEXT NOT NULL CHECK(length(raw_hash)=64), exchange_key TEXT,
                  classification TEXT NOT NULL, authentication TEXT NOT NULL,
                  ingested_at TEXT NOT NULL)
                """);
            s.execute("CREATE TABLE discovery_exchange_claims(exchange_key TEXT PRIMARY KEY, evidence_id TEXT NOT NULL REFERENCES discovery_exchange_evidence(id), UNIQUE(exchange_key,evidence_id))");
            s.execute("""
                CREATE TABLE discovery_record_evidence(
                  evidence_id TEXT NOT NULL REFERENCES discovery_exchange_evidence(id), packet_index INTEGER NOT NULL,
                  record_index INTEGER NOT NULL, source_identity TEXT, target_identity TEXT,
                  authentication TEXT NOT NULL, enr_signature TEXT NOT NULL, identity_comparison TEXT NOT NULL,
                  correlation TEXT NOT NULL, validation_json TEXT CHECK(length(CAST(validation_json AS BLOB))<=16384),
                  PRIMARY KEY(evidence_id,packet_index,record_index))
                """.replace("source_identity TEXT, target_identity TEXT,","source_identity TEXT CHECK("+identity("source_identity")+"), target_identity TEXT CHECK("+identity("target_identity")+"),"));
            s.execute("""
                CREATE TABLE discovery_relationship_observations(
                  seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE CHECK(length(id)=64),
                  evidence_id TEXT NOT NULL REFERENCES discovery_exchange_evidence(id),
                  run_id TEXT NOT NULL, observer_id TEXT NOT NULL,
                  protocol TEXT NOT NULL CHECK(protocol='discv5'), source_identity TEXT NOT NULL, target_identity TEXT NOT NULL,
                  relation_type TEXT NOT NULL CHECK(relation_type='REPORTED_NEIGHBOR'), observed_at TEXT NOT NULL,
                  packet_index INTEGER NOT NULL, record_index INTEGER NOT NULL, correlation_ref TEXT NOT NULL,
                  authentication TEXT NOT NULL CHECK(authentication='HELPER_ATTESTED_AUTHENTICATED'),
                  enr_signature TEXT NOT NULL CHECK(enr_signature='VALID'), identity_comparison TEXT NOT NULL CHECK(identity_comparison='MATCH'),
                  contract_version INTEGER NOT NULL CHECK(contract_version=2),
                  CHECK(source_identity<>target_identity), UNIQUE(evidence_id,packet_index,record_index),
                  FOREIGN KEY(run_id,observer_id) REFERENCES discovery_runs(id,observer_id),
                  FOREIGN KEY(evidence_id,packet_index,record_index) REFERENCES discovery_record_evidence(evidence_id,packet_index,record_index),
                  FOREIGN KEY(correlation_ref,evidence_id) REFERENCES discovery_exchange_claims(exchange_key,evidence_id))
                """.replace("CHECK(source_identity<>target_identity)","CHECK(source_identity<>target_identity), CHECK("+ID.formatted("source_identity","source_identity")+"), CHECK("+ID.formatted("target_identity","target_identity")+")"));
            s.execute("""
                CREATE TABLE discovery_candidates(
                  id TEXT PRIMARY KEY, evidence_id TEXT NOT NULL REFERENCES discovery_exchange_evidence(id),
                  observed_at TEXT, packet_index INTEGER NOT NULL, record_index INTEGER NOT NULL,
                  reason TEXT NOT NULL CHECK(length(reason) BETWEEN 1 AND 128), source_identity TEXT, target_identity TEXT)
                """.replace("source_identity TEXT, target_identity TEXT","source_identity TEXT CHECK("+identity("source_identity")+"), target_identity TEXT CHECK("+identity("target_identity")+")"));
            s.execute("CREATE TABLE discovery_evidence_rejections(evidence_id TEXT NOT NULL REFERENCES discovery_exchange_evidence(id), reason TEXT NOT NULL, PRIMARY KEY(evidence_id,reason))");
            s.execute("CREATE TABLE discovery_capture_health(id TEXT PRIMARY KEY REFERENCES discovery_exchange_evidence(id), run_id TEXT REFERENCES discovery_runs(id), stream_id TEXT NOT NULL, protocol TEXT NOT NULL, recorded_at TEXT NOT NULL, health_json TEXT NOT NULL CHECK(length(CAST(health_json AS BLOB))<=16384))");
            for(String column:new String[]{"observed_at","observer_id,observed_at","protocol,observed_at","source_identity,observed_at","target_identity,observed_at"}) {
                String suffix=column.replace(",","_");s.execute("CREATE INDEX idx_relationship_"+suffix+" ON discovery_relationship_observations("+column+",id,seq)");
            }
            s.execute("CREATE INDEX idx_discovery_runs_observer ON discovery_runs(observer_id,id)");
            s.execute("CREATE INDEX idx_discovery_evidence_time ON discovery_exchange_evidence(observed_at,id)");
            s.execute("CREATE INDEX idx_discovery_evidence_run ON discovery_exchange_evidence(run_id,protocol,observed_at,id)");
            s.execute("CREATE INDEX idx_discovery_candidates_time ON discovery_candidates(observed_at,id)");
            s.execute("CREATE INDEX idx_discovery_candidates_source ON discovery_candidates(evidence_id,reason)");
            s.execute("CREATE INDEX idx_discovery_health_run ON discovery_capture_health(run_id,protocol,recorded_at,id)");
            s.execute("CREATE INDEX idx_discovery_record_source ON discovery_record_evidence(source_identity,evidence_id)");
            s.execute("CREATE INDEX idx_discovery_record_target ON discovery_record_evidence(target_identity,evidence_id)");
            s.execute("CREATE TRIGGER validate_attributable_relationship BEFORE INSERT ON discovery_relationship_observations WHEN NOT EXISTS(SELECT 1 FROM discovery_exchange_evidence e JOIN discovery_record_evidence v ON v.evidence_id=e.id WHERE e.id=NEW.evidence_id AND e.run_id=NEW.run_id AND e.protocol='discv5' AND e.contract_version=2 AND e.classification='ATTRIBUTABLE_EXCHANGE' AND e.authentication='HELPER_ATTESTED_AUTHENTICATED' AND v.packet_index=NEW.packet_index AND v.record_index=NEW.record_index AND v.source_identity=NEW.source_identity AND v.target_identity=NEW.target_identity AND v.authentication='HELPER_ATTESTED_AUTHENTICATED' AND v.enr_signature='VALID' AND v.identity_comparison='MATCH' AND v.correlation='AUTHENTICATED_REQUEST_MATCH') OR EXISTS(SELECT 1 FROM discovery_evidence_rejections r WHERE r.evidence_id=NEW.evidence_id) BEGIN SELECT RAISE(ABORT,'Attributable relationship validation required'); END");
            s.execute("CREATE VIEW discovery_attributable_relationships AS SELECT o.* FROM discovery_relationship_observations o WHERE NOT EXISTS(SELECT 1 FROM discovery_evidence_rejections r WHERE r.evidence_id=o.evidence_id)");
            for(String table:new String[]{"observer_installations","discovery_runs","discovery_exchange_evidence","discovery_exchange_claims","discovery_record_evidence","discovery_relationship_observations","discovery_candidates","discovery_evidence_rejections","discovery_capture_health"})for(String operation:new String[]{"UPDATE","DELETE"})
                s.execute("CREATE TRIGGER immutable_"+table+"_"+operation.toLowerCase()+" BEFORE "+operation+" ON "+table+" BEGIN SELECT RAISE(ABORT,'Discovery evidence is append-only'); END");
            s.execute("INSERT INTO schema_migrations(version) VALUES(6)");db.commit();
        }catch(SQLException|RuntimeException e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
    }
    private DiscoveryEvidenceSchema(){}
}
