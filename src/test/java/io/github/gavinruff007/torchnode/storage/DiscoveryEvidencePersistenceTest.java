package io.github.gavinruff007.torchnode.storage;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gavinruff007.torchnode.discovery.*;
import io.github.gavinruff007.torchnode.enr.*;
import io.github.gavinruff007.torchnode.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DiscoveryEvidencePersistenceTest {
    @TempDir Path temporary;
    private static final String STREAM="00000000-0000-0000-0000-000000000003";
    private JsonNode fixture(String mode)throws Exception {try(var in=getClass().getResourceAsStream("/discv5-trace-v2-"+mode+".json")){assertNotNull(in);return DiscoveryEvidenceBatch.JSON.readTree(in).path("trace");}}
    private ObserverInstance.Run run()throws Exception {var raw=fixture("valid");return new ObserverInstance.Run(UUID.fromString(raw.path("observer").path("instanceId").asText()),UUID.fromString(raw.path("observer").path("runId").asText()),Instant.parse(raw.path("startedAt").asText()).minusSeconds(1),raw.path("localIdentity").asText(),ObserverInstance.KeyLifecycle.EPHEMERAL_FIRST_SEEN);}
    private DiscoveryEvidenceBatch batch(JsonNode raw,ObserverInstance.Run context)throws Exception {
        var capture=new Discv5EvidenceCapture(context,raw.path("localIdentity").asText());capture.ready(2,raw.path("localIdentity").asText());capture.accept(raw);
        return DiscoveryEvidenceBatch.v5(context,STREAM,capture.snapshot()).getFirst();
    }
    private DiscoveryEvidenceBatch batch(String mode)throws Exception{return batch(fixture(mode),run());}
    private DiscoveryEvidenceRepository.Query query()throws Exception {Instant at=Instant.parse(fixture("valid").path("startedAt").asText());return new DiscoveryEvidenceRepository.Query(at.minusSeconds(60),at.plusSeconds(86400),null,"all",null,200);}
    private String path(String file){return temporary.resolve(file).toString();}
    private long count(String path,String table)throws Exception {try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){assertTrue(r.next());return r.getLong(1);}}
    private void sql(String path,String sql)throws Exception {try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement()){s.execute(sql);}}
    @Test void attributableFixtureSurvivesReopenWithCanonicalMappingAndIdempotentRetry()throws Exception {
        String path=path("persist.db");var batch=batch("valid");
        try(var store=new DiscoveryEvidenceStore(path)){assertEquals(1,store.ingest(batch).relationshipsInserted());assertEquals(0,store.ingest(batch).relationshipsInserted());}
        try(var store=new DiscoveryEvidenceStore(path)){assertEquals(0,store.ingest(batch).relationshipsInserted());}
        var repo=new DiscoveryEvidenceRepository(path);var page=repo.relationships(query(),null);assertEquals(1,page.observations().size());
        var row=page.observations().getFirst();var raw=fixture("valid");
        assertEquals(raw.path("responderIdentity").asText(),row.get("source_identity"));assertEquals(raw.path("packets").get(0).path("records").get(0).path("identity").asText(),row.get("target_identity"));
        assertEquals(run().observerInstanceId().toString(),row.get("observer_id"));assertEquals("REPORTED_NEIGHBOR",row.get("relation_type"));
        var detail=repo.evidence((String)row.get("evidence_id")).orElseThrow();assertEquals(raw,DiscoveryEvidenceBatch.JSON.readTree((String)detail.evidence().get("raw_json")));
        assertTrue(detail.validations().stream().anyMatch(v->v.get("enr_signature").equals("VALID")));assertTrue(page.coverage().relationshipMeasurementAvailable());assertFalse(page.coverage().collectionComplete());
        assertEquals(1,count(path,"discovery_relationship_observations"));assertEquals(1,count(path,"observer_installations"));assertEquals(1,count(path,"discovery_runs"));
        assertEquals(0,count(path,"discovery_observations"));assertEquals(0,count(path,"nodes"));
    }
    @Test void repeatedRelationshipsAndEndpointSharingPreserveDistinctEvidence()throws Exception {
        String path=path("reobserved.db");try(var store=new DiscoveryEvidenceStore(path)){store.ingest(batch("valid"));store.ingest(batch("multi"));}
        var rows=new DiscoveryEvidenceRepository(path).relationships(query(),null).observations();assertEquals(3,rows.size());
        assertEquals(2,rows.stream().map(r->r.get("target_identity")).distinct().count());assertEquals(3,rows.stream().map(r->r.get("id")).distinct().count());
        assertEquals(1,rows.stream().map(r->r.get("source_identity")).distinct().count());
    }
    @Test void failedSqlTransactionLeavesNoPartialEligibleRowsAndRetrySucceeds()throws Exception {
        String path=path("rollback.db");try(var schema=new SqliteNodeStore(path)){}
        sql(path,"CREATE TRIGGER reject_candidate BEFORE INSERT ON discovery_candidates BEGIN SELECT RAISE(ABORT,'fixture rollback'); END");
        try(var store=new DiscoveryEvidenceStore(path)){assertThrows(SQLException.class,()->store.ingest(batch("duplicate")));}
        for(String table:List.of("discovery_exchange_evidence","discovery_relationship_observations","discovery_record_evidence","observer_installations","discovery_runs"))assertEquals(0,count(path,table),table);
        sql(path,"DROP TRIGGER reject_candidate");try(var store=new DiscoveryEvidenceStore(path)){assertEquals(2,store.ingest(batch("duplicate")).relationshipsInserted());}
    }
    @Test void immutableReceiptContradictionRollsBackTheWholeBatch()throws Exception {
        String path=path("contradiction.db");var initial=batch("valid");
        try(var store=new DiscoveryEvidenceStore(path)){store.ingest(initial);}
        ObjectNode altered=fixture("valid").deepCopy();altered.put("endpoint","127.0.0.1:39999");
        var other=batch("multi").receipts().getFirst();var conflicting=batch(altered,run()).receipts().getFirst();
        var combined=new DiscoveryEvidenceBatch(run(),"discv5",STREAM,List.of(other,conflicting),null);
        try(var store=new DiscoveryEvidenceStore(path)){assertThrows(SQLException.class,()->store.ingest(combined));}
        assertEquals(1,count(path,"discovery_relationship_observations"));assertEquals(2,count(path,"discovery_exchange_evidence"));
    }
    @Test void eligibleFlagsCannotOverrideInvalidEvidenceAndSecretsAreRejected()throws Exception {
        String path=path("invalid.db");ObjectNode raw=fixture("valid").deepCopy();raw.put("responderIdentity","ab".repeat(64));
        var forged=new DiscoveryEvidenceBatch.Receipt("TRACE",raw.path("traceId").asText(),raw.toString(),true,true,"COMPLETE_ATTRIBUTABLE");
        try(var store=new DiscoveryEvidenceStore(path)) {
            assertThrows(SQLException.class,()->store.ingest(new DiscoveryEvidenceBatch(run(),"discv5",STREAM,List.of(forged),null)));
            ObjectNode secret=fixture("valid").deepCopy();secret.put("privateKey","fixture-must-not-persist");
            var rejected=new DiscoveryEvidenceBatch.Receipt("TRACE",secret.path("traceId").asText(),secret.toString(),false,false,"MALFORMED_TRACE");
            assertThrows(SQLException.class,()->store.ingest(new DiscoveryEvidenceBatch(run(),"discv5",STREAM,List.of(rejected),null)));
        }
        assertEquals(0,count(path,"discovery_exchange_evidence"));assertEquals(0,count(path,"discovery_relationship_observations"));
    }
    @Test void invalidPartialMissingObserverAndDuplicateCandidatesPersistExplicitly()throws Exception {
        for(String mode:List.of("invalid-enr","distance-mismatch","partial","wrong-request","inconsistent","wrong-key","malformed","delayed")) {
            String path=path(mode+".db");try(var store=new DiscoveryEvidenceStore(path)){store.ingest(batch(mode));}
            assertEquals(0,count(path,"discovery_relationship_observations"),mode);assertTrue(count(path,"discovery_candidates")>0,mode);
        }
        String path=path("missing-observer.db");try(var store=new DiscoveryEvidenceStore(path)){store.ingest(batch(fixture("valid"),null));}
        assertEquals(0,count(path,"discovery_runs"));assertEquals(0,count(path,"discovery_relationship_observations"));
        var counts=new DiscoveryEvidenceRepository(path).candidateCounts(query());assertTrue(counts.reasons().containsKey("MISSING_OR_INVALID_AUTHENTICATED_CONTEXT"));
    }
    @Test void duplicatesSelfAndMeasuredEmptyDoNotInventRelationships()throws Exception {
        for(String mode:List.of("self","empty","repeated-enr","duplicate","replay")) {
            String path=path(mode+".db");var batch=batch(mode);try(var store=new DiscoveryEvidenceStore(path)){store.ingest(batch);store.ingest(batch);}
            long expected=Set.of("self","empty").contains(mode)?0:2;assertEquals(expected,count(path,"discovery_relationship_observations"),mode);
            assertTrue(new DiscoveryEvidenceRepository(path).relationships(query(),null).coverage().relationshipMeasurementAvailable(),mode);
        }
    }
    @Test void truncatedExchangeAndLossCountersRemainDiagnostic()throws Exception {
        String path=path("truncated.db");ObjectNode raw=fixture("valid").deepCopy();raw.put("omittedPackets",1);
        var normal=batch(raw,run());var health=new DiscoveryEvidenceBatch.Health(Map.of("TRACE_EVICTED",3L),3,0,2,4L);
        var batch=new DiscoveryEvidenceBatch(run(),"discv5",STREAM,normal.receipts(),health);
        try(var store=new DiscoveryEvidenceStore(path)){store.ingest(batch);store.ingest(batch);}
        assertEquals(0,count(path,"discovery_relationship_observations"));assertEquals(1,count(path,"discovery_capture_health"));
        assertTrue(new DiscoveryEvidenceRepository(path).candidateCounts(query()).reasons().containsKey("INCOMPLETE_OR_UNATTRIBUTABLE"));
    }
    @Test void legacyV4RequestsAndSignedReportsNeverPromote()throws Exception {
        String path=path("v4.db");var capture=new Discv4EvidenceCapture(run());
        byte[] key=org.web3j.utils.Numeric.toBytesPadded(org.web3j.crypto.ECKeyPair.create(java.math.BigInteger.valueOf(3)).getPublicKey(),64);
        var descriptor=new org.web3j.rlp.RlpList(org.web3j.rlp.RlpString.create(new byte[]{127,0,0,1}),org.web3j.rlp.RlpString.create(30303),org.web3j.rlp.RlpString.create(30305),org.web3j.rlp.RlpString.create(key));
        byte[] raw=Discv4Packet.encode(4,org.web3j.rlp.RlpEncoder.encode(new org.web3j.rlp.RlpList(new org.web3j.rlp.RlpList(descriptor),org.web3j.rlp.RlpString.create(Instant.now().plusSeconds(60).getEpochSecond()))),EnrFixtures.KEY);
        var remote=new java.net.InetSocketAddress("127.0.0.1",30303);
        byte[] request=Discv4Packet.encode(3,org.web3j.rlp.RlpEncoder.encode(new org.web3j.rlp.RlpList(org.web3j.rlp.RlpString.create(HexFormat.of().parseHex(run().localDiscoveryIdentity())),org.web3j.rlp.RlpString.create(Instant.now().plusSeconds(60).getEpochSecond()))),EnrFixtures.KEY);
        capture.sendFindNode(request,remote,EnrFixtures.ID.nodeId(),HexFormat.of().parseHex(run().localDiscoveryIdentity()),()->{});
        capture.sendFindNode(request,remote,EnrFixtures.ID.nodeId(),HexFormat.of().parseHex(run().localDiscoveryIdentity()),()->{});
        capture.receive(raw,remote);
        try(var store=new DiscoveryEvidenceStore(path)){for(var b:DiscoveryEvidenceBatch.v4(run(),STREAM,capture.snapshot()))store.ingest(b);}
        assertEquals(0,count(path,"discovery_relationship_observations"));assertTrue(count(path,"discovery_candidates")>=1);
        assertEquals(4,count(path,"discovery_exchange_evidence")); // Two requests, response, capture health.
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM discovery_candidates WHERE reason='AMBIGUOUS_LOCAL_REQUESTS'")){r.next();assertEquals(1,r.getLong(1));}

        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM discovery_candidates WHERE reason='DISCV4_REQUEST_UNBOUND'")){r.next();assertEquals(1,r.getLong(1));}
    }
    @Test void freshAndRepresentativeV5MigrationsPreserveHistoricalRowsAndRollBackDdl()throws Exception {
        String path=path("v5.db");var at=Instant.parse("2026-01-01T00:00:00.123456789Z");
        var observation=DiscoveryPersistenceTest.observation("ab".repeat(64),"192.0.2.1",30303,30305,"discv4",at);
        try(var old=new SqliteNodeStore(path,false,5)){old.saveObservation(observation);old.saveP2pObservation(new NodeRecord(observation).getKey(),"{}","{}");}
        assertEquals(5,count(path,"schema_migrations"));
        sql(path,"CREATE TRIGGER reject_v6 BEFORE INSERT ON schema_migrations WHEN NEW.version=6 BEGIN SELECT RAISE(ABORT,'migration rollback'); END");
        assertThrows(SQLException.class,()->new SqliteNodeStore(path));
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='discovery_exchange_evidence'")){r.next();assertEquals(0,r.getInt(1));}
        assertEquals(5,count(path,"schema_migrations"));sql(path,"DROP TRIGGER reject_v6");
        for(int i=0;i<2;i++)try(var migrated=new SqliteNodeStore(path)){assertEquals(List.of(observation),migrated.findObservations(observation.identity()));assertEquals(1,migrated.count());}
        assertEquals(6,count(path,"schema_migrations"));assertEquals(1,count(path,"p2p_observations"));assertEquals(0,count(path,"discovery_relationship_observations"));
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement();var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}
    }
    @Test void emptyWindowsUnknownCountersAndReadOnlyGetDoNotWrite()throws Exception {
        String path=path("empty.db");try(var store=new DiscoveryEvidenceStore(path)){}
        var repo=new DiscoveryEvidenceRepository(path);var result=repo.relationships(query(),null);assertTrue(result.observations().isEmpty());assertFalse(result.coverage().relationshipMeasurementAvailable());
        assertNull(repo.candidateCounts(query()).total());assertTrue(repo.evidence("a".repeat(64)).isEmpty());assertEquals(0,count(path,"discovery_exchange_evidence"));
        assertThrows(IllegalArgumentException.class,()->repo.evidence("127.0.0.1"));
        assertThrows(SQLException.class,()->new DiscoveryEvidenceRepository(path,Duration.ofNanos(1)).relationships(query(),null));
    }
    @Test void stablePaginationExcludesConcurrentInsertionsAndHonorsExactTimeBounds()throws Exception {
        String path=path("pages.db");JsonNode original=fixture("valid");
        try(var store=new DiscoveryEvidenceStore(path)){for(int i=0;i<5;i++){ObjectNode raw=original.deepCopy();raw.put("traceId",String.format("%032x",i+100));raw.put("codecContext",String.format("%032x",i+100));store.ingest(batch(raw,run()));}}
        var base=query();var small=new DiscoveryEvidenceRepository.Query(base.start(),base.end(),run().observerInstanceId().toString(),"discv5",null,2);
        var repo=new DiscoveryEvidenceRepository(path);var first=repo.relationships(small,null);assertEquals(2,first.observations().size());assertNotNull(first.nextCursor());
        try(var concurrent=new DiscoveryEvidenceStore(path)){ObjectNode raw=original.deepCopy();raw.put("traceId","f".repeat(32));raw.put("codecContext","f".repeat(32));concurrent.ingest(batch(raw,run()));}
        Set<Object> ids=new HashSet<>();first.observations().forEach(r->assertTrue(ids.add(r.get("id"))));var cursor=first.nextCursor();
        while(cursor!=null){var page=repo.relationships(small,cursor);page.observations().forEach(r->assertTrue(ids.add(r.get("id"))));cursor=page.nextCursor();}
        assertEquals(5,ids.size());assertEquals(6,repo.relationships(base,null).observations().size());
        Instant at=Instant.parse(original.path("packets").get(0).path("at").asText());
        var exact=new DiscoveryEvidenceRepository.Query(at,at.plusNanos(1),null,"discv5",null,200);assertEquals(6,repo.relationships(exact,null).observations().size());
        var before=new DiscoveryEvidenceRepository.Query(at.minusSeconds(1),at,null,"discv5",null,200);assertTrue(repo.relationships(before,null).observations().isEmpty());
        var target=original.path("packets").get(0).path("records").get(0).path("identity").asText();
        assertEquals(6,repo.relationships(new DiscoveryEvidenceRepository.Query(base.start(),base.end(),null,"all",target,200),null).observations().size());
        assertTrue(repo.relationships(new DiscoveryEvidenceRepository.Query(base.start(),base.end(),null,"discv4",null,200),null).observations().isEmpty());
        assertThrows(IllegalArgumentException.class,()->repo.relationships(before,first.nextCursor()));
    }
    @Test void durableRequestDedupAndAppendOnlyRejectionPreserveRawEvidence()throws Exception {
        String path=path("rejection.db");JsonNode raw=fixture("valid");
        try(var store=new DiscoveryEvidenceStore(path)){store.ingest(batch(raw,run()));ObjectNode replay=raw.deepCopy();replay.put("traceId","e".repeat(32));store.ingest(batch(replay,run()));}
        assertEquals(1,count(path,"discovery_relationship_observations"));
        var capture=new Discv5EvidenceCapture(run(),raw.path("localIdentity").asText());capture.ready(2,raw.path("localIdentity").asText());capture.accept(raw);
        ObjectNode conflicting=raw.deepCopy();conflicting.put("endpoint","127.0.0.1:39998");capture.accept(conflicting);
        try(var store=new DiscoveryEvidenceStore(path)){for(var b:DiscoveryEvidenceBatch.v5(run(),STREAM,capture.snapshot()))store.ingest(b);}
        var repo=new DiscoveryEvidenceRepository(path);assertTrue(repo.relationships(query(),null).observations().isEmpty());assertEquals(1,count(path,"discovery_relationship_observations"));
        assertEquals(0,count(path,"discovery_attributable_relationships"));
        String id=DiscoveryEvidenceStore.evidenceId(run(),"discv5","TRACE",raw.path("traceId").asText());
        var details=repo.evidence(id).orElseThrow();assertEquals(raw,DiscoveryEvidenceBatch.JSON.readTree((String)details.evidence().get("raw_json")));assertFalse(details.rejections().isEmpty());
        assertThrows(SQLException.class,()->sql(path,"UPDATE discovery_exchange_evidence SET raw_json='{}'"));
        assertThrows(SQLException.class,()->sql(path,"DELETE FROM discovery_relationship_observations"));
    }
    @Test void exactCandidateCapAndNativeExecutionDeadlineAreEnforced()throws Exception {
        String path=path("cap.db");try(var store=new DiscoveryEvidenceStore(path)){store.ingest(batch("empty"));}
        String id=DiscoveryEvidenceStore.evidenceId(run(),"discv5","TRACE",fixture("empty").path("traceId").asText());
        String at=DiscoveryEvidenceStore.clock(Instant.parse(fixture("valid").path("startedAt").asText()));
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var q=db.prepareStatement("WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x<49999) INSERT INTO discovery_candidates SELECT printf('%064x',x),?,?,0,x,'LIMIT_FIXTURE',NULL,NULL FROM n")){q.setString(1,id);q.setString(2,at);q.executeUpdate();}
        var repo=new DiscoveryEvidenceRepository(path);var exact=repo.candidateCounts(query());assertEquals(Long.valueOf(50000),exact.total());assertTrue(exact.queryComplete());assertFalse(exact.aggregateTruncated());
        sql(path,"INSERT INTO discovery_candidates VALUES('"+"f".repeat(64)+"','"+id+"','"+at+"',0,50000,'LIMIT_FIXTURE',NULL,NULL)");
        var limit=assertThrows(DiscoveryEvidenceRepository.ScopeLimit.class,()->repo.candidateCounts(query()));assertFalse(limit.aggregateReturned);assertFalse(limit.queryComplete);assertEquals(50000,limit.safetyLimit);
        long began=System.nanoTime();SQLException timeout=assertThrows(SQLException.class,()->new DiscoveryEvidenceRepository(path,Duration.ofNanos(1)).candidateCounts(query()));
        assertTrue(System.nanoTime()-began<java.util.concurrent.TimeUnit.SECONDS.toNanos(2));assertInstanceOf(org.sqlite.SQLiteException.class,timeout);assertEquals(org.sqlite.SQLiteErrorCode.SQLITE_INTERRUPT,((org.sqlite.SQLiteException)timeout).getResultCode());
        assertEquals(50001,count(path,"discovery_candidates"));
    }
    @Test void isolatedV5CopyPreservesEveryHistoricalTableContent()throws Exception {
        String source=path("representative-v5.db"),copy=path("isolated-v6-copy.db");Instant at=Instant.parse("2026-01-01T00:00:00.123456789Z");
        try(var old=new SqliteNodeStore(source,false,5)) {
            var observation=DiscoveryPersistenceTest.observation("ab".repeat(64),"192.0.2.1",30303,30305,"discv4",at);old.saveObservation(observation);
            old.saveObservation(DiscoveryPersistenceTest.observation("cd".repeat(64),"192.0.2.1",30303,30305,"discv5",at.plusNanos(1)));
            var node=new NodeRecord(observation);old.saveP2pObservation(node.getKey(),"{\"clientId\":\"Geth/fixture\"}","{}");
            old.saveEnrEvidence(new EnrDecoder().decode(EnrFixtures.complete(1,30303),EnrFixtures.ID,at,"B4 fixture"));
            old.saveInspectionRun(node,"fixture-inspection",at.toString(),at.plusSeconds(1).toString(),Map.of("diagnostics",List.of(Map.of("name","P2P TCP","state","NOT_TESTED","source","fixture"))),null,null,null,List.of(),List.of());
        }
        var before=legacyDigests(source);
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+source);var q=db.prepareStatement("VACUUM INTO ?")){q.setString(1,copy);q.execute();}
        try(var migrated=new SqliteNodeStore(copy)){}var after=legacyDigests(copy);after.remove("schema_migrations");before.remove("schema_migrations");assertEquals(before,after);
        assertEquals(5,count(source,"schema_migrations"));assertEquals(6,count(copy,"schema_migrations"));assertEquals(0,count(copy,"discovery_relationship_observations"));
    }
    private Map<String,String> legacyDigests(String path)throws Exception {
        Map<String,String> result=new TreeMap<>();
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement();var names=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('observer_installations','discovery_runs','discovery_exchange_evidence','discovery_exchange_claims','discovery_record_evidence','discovery_relationship_observations','discovery_candidates','discovery_evidence_rejections','discovery_capture_health') ORDER BY name")) {
            while(names.next()){String table=names.getString(1);var values=new ArrayList<String>();try(var q=db.createStatement();var rows=q.executeQuery("SELECT * FROM \""+table+"\"")){while(rows.next()){var row=new ArrayList<Object>();for(int i=1;i<=rows.getMetaData().getColumnCount();i++)row.add(rows.getObject(i));values.add(DiscoveryEvidenceBatch.json(row));}}Collections.sort(values);result.put(table,DiscoveryEvidenceBatch.hash(DiscoveryEvidenceBatch.json(values)));}
        }return result;
    }
    @Test void twoAuthenticatedResponderIdentitiesAtOneEndpointRemainDistinctAfterPersistence()throws Exception {
        String path=path("source-reuse.db");var raws=new ArrayList<JsonNode>();
        for(int i:new int[]{2,4})try(var in=getClass().getResourceAsStream("/discv5-b4-responder-"+i+".json")){assertNotNull(in);raws.add(DiscoveryEvidenceBatch.JSON.readTree(in).path("trace"));}
        assertEquals(raws.get(0).path("endpoint"),raws.get(1).path("endpoint"));
        try(var store=new DiscoveryEvidenceStore(path)){for(var raw:raws)store.ingest(batch(raw,run()));}
        Instant at=Instant.parse(raws.getFirst().path("startedAt").asText());var q=new DiscoveryEvidenceRepository.Query(at.minusSeconds(5),at.plusSeconds(60),null,"discv5",null,200);
        var rows=new DiscoveryEvidenceRepository(path).relationships(q,null).observations();assertEquals(2,rows.size());assertEquals(2,rows.stream().map(r->r.get("source_identity")).distinct().count());assertEquals(1,rows.stream().map(r->r.get("target_identity")).distinct().count());
    }
    @Test void backgroundWriterRetriesWithoutNetworkActivityOrCallerThreadWrites()throws Exception {
        String path=path("worker.db");try(var store=new DiscoveryEvidenceStore(path)){}
        sql(path,"CREATE TRIGGER temporarily_reject BEFORE INSERT ON discovery_relationship_observations BEGIN SELECT RAISE(ABORT,'retry fixture'); END");
        var batch=batch("valid");var threadName=new java.util.concurrent.atomic.AtomicReference<String>();
        var provider=new DiscoveryProvider(){public String protocol(){return "fixture";}public void start(){}public void discover(java.util.function.Consumer<DiscoveryObservation> c){fail("no discovery from persistence");}public void close(){}public void evidenceBatches(java.util.function.Consumer<DiscoveryEvidenceBatch> c){threadName.set(Thread.currentThread().getName());c.accept(batch);}};
        try(var worker=new DiscoveryEvidenceWorker(path,provider)) {
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);while(worker.status().failedBatches()==0 && System.nanoTime()<until)Thread.sleep(10);assertTrue(worker.status().failedBatches()>0);assertEquals(0,count(path,"discovery_relationship_observations"));
            sql(path,"DROP TRIGGER temporarily_reject");until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);while(count(path,"discovery_relationship_observations")==0 && System.nanoTime()<until)Thread.sleep(20);
            assertEquals(1,count(path,"discovery_relationship_observations"));assertEquals("discovery-evidence-writer",threadName.get());
        }
        assertEquals(1,count(path,"discovery_relationship_observations"));
    }
    @Test void rotatedRunContextPersistsAndMissingKeyAttestationIsRejected()throws Exception {
        String path=path("rotation.db");ObjectNode raw=fixture("valid").deepCopy();var original=run();
        var rotated=new ObserverInstance.Run(original.observerInstanceId(),UUID.randomUUID(),original.startedAt(),original.localDiscoveryIdentity(),ObserverInstance.KeyLifecycle.EPHEMERAL_ROTATED);
        ((ObjectNode)raw.path("observer")).put("runId",rotated.discoveryRunId().toString()).put("keyLifecycle","EPHEMERAL_ROTATED");
        try(var store=new DiscoveryEvidenceStore(path)){store.ingest(batch("valid"));store.ingest(batch(raw,rotated));}
        assertEquals(2,count(path,"discovery_runs"));assertEquals(2,count(path,"discovery_relationship_observations"));assertEquals(1,count(path,"observer_installations"));
        var missing=new ObserverInstance.Run(original.observerInstanceId(),UUID.randomUUID(),original.startedAt(),null,ObserverInstance.KeyLifecycle.MISSING);
        var forged=new DiscoveryEvidenceBatch(missing,"discv5",STREAM,batch("valid").receipts(),null);
        try(var store=new DiscoveryEvidenceStore(path)){assertThrows(SQLException.class,()->store.ingest(forged));}
        assertEquals(2,count(path,"discovery_relationship_observations"));
    }
    @Test void explicitPartialOrOmittedRecordMetadataCannotBeOverriddenByEligibilityFlags()throws Exception {
        String path=path("partial-metadata.db");
        try(var store=new DiscoveryEvidenceStore(path)) {
            for(String mode:List.of("partial","omittedRecords","missingCompleteness")) {
                ObjectNode raw=fixture("valid").deepCopy();
                if(mode.equals("partial"))raw.put("partial",true);
                else if(mode.equals("omittedRecords"))((ObjectNode)raw.path("packets").get(0)).put("omittedRecords",1);
                else raw.remove("partial");
                var receipt=new DiscoveryEvidenceBatch.Receipt("TRACE",raw.path("traceId").asText(),raw.toString(),true,true,"COMPLETE_ATTRIBUTABLE");
                assertThrows(SQLException.class,()->store.ingest(new DiscoveryEvidenceBatch(run(),"discv5",STREAM,List.of(receipt),null)),mode);
            }
        }
        assertEquals(0,count(path,"discovery_exchange_evidence"));assertEquals(0,count(path,"discovery_relationship_observations"));
    }
    @Test void foreignKeyIntegrityAndCanonicalIdentityChecksRejectOrphans()throws Exception {
        String path=path("foreign-keys.db");try(var store=new DiscoveryEvidenceStore(path)){store.ingest(batch("valid"));}
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+path);var s=db.createStatement()) {
            s.execute("PRAGMA foreign_keys=ON");
            assertThrows(SQLException.class,()->s.execute("INSERT INTO discovery_candidates VALUES('orphan','"+"f".repeat(64)+"',NULL,0,0,'ORPHAN',NULL,NULL)"));
            assertThrows(SQLException.class,()->s.execute("INSERT INTO discovery_record_evidence VALUES('"+"e".repeat(64)+"',0,0,'127.0.0.1',NULL,'UNVERIFIED','NOT_TESTED','NOT_AVAILABLE','UNBOUND',NULL)"));
            try(var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}
        }
    }
    @Test void legacyCollectionAndEvidenceWriterCoexistWithoutLosingObservations()throws Exception {
        String path=path("concurrent-legacy.db");var exchanges=new ArrayList<DiscoveryEvidenceBatch.Receipt>();var context=run();
        for(int i=0;i<64;i++){ObjectNode raw=fixture("valid").deepCopy();raw.put("traceId",String.format("%032x",i+200));raw.put("codecContext",String.format("%032x",i+200));exchanges.add(batch(raw,context).receipts().getFirst());}
        var batches=new ArrayList<DiscoveryEvidenceBatch>();for(int i=0;i<64;i+=16)batches.add(new DiscoveryEvidenceBatch(context,"discv5",STREAM,exchanges.subList(i,i+16),null));
        var provider=new DiscoveryProvider(){public String protocol(){return "fixture";}public void start(){}public void discover(java.util.function.Consumer<DiscoveryObservation> c){fail("no network activity");}public void close(){}public void evidenceBatches(java.util.function.Consumer<DiscoveryEvidenceBatch> c){batches.forEach(c);}};
        try(var legacy=new SqliteNodeStore(path);var writer=new DiscoveryEvidenceWorker(path,provider)) {
            Instant at=Instant.now();for(int i=0;i<50;i++)legacy.saveObservation(DiscoveryPersistenceTest.observation("ab".repeat(64),"192.0.2.1",30303,30305,"discv4",at.plusNanos(i)));
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(6);while(count(path,"discovery_relationship_observations")<64 && System.nanoTime()<end)Thread.sleep(20);
            assertEquals(64,count(path,"discovery_relationship_observations"));assertEquals(50,count(path,"discovery_observations"));assertEquals(1,legacy.count());
        }
    }
}
