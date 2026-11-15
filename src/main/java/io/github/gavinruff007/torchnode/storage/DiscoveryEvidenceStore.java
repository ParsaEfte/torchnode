package io.github.gavinruff007.torchnode.storage;

import com.fasterxml.jackson.databind.*;
import io.github.gavinruff007.torchnode.discovery.*;
import io.github.gavinruff007.torchnode.enr.*;
import io.github.gavinruff007.torchnode.model.NodeIds;
import io.github.gavinruff007.torchnode.topology.SqliteReadDeadline;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static io.github.gavinruff007.torchnode.discovery.DiscoveryEvidenceBatch.*;

/** Single-writer repository. Only trusted collector handoffs may supply helper attestations. */
public final class DiscoveryEvidenceStore implements AutoCloseable {
    public static final int CONTRACT=2,MAX_RECORDS=512;
    private static final Set<String> TRACE_FIELDS=Set.of("version","traceId","observer","localIdentity","codecContext","requestId","requestPayload","distances","responderIdentity","responderEnr","responderRoutingId","endpoint","startedAt","completedAt","outcome","failureCode","partial","packets","counts","omittedPackets","guardCounts","helperDrops");
    private static final String AUTH="HELPER_ATTESTED_AUTHENTICATED";
    private final Connection db;
    private long writeDeadline;
    public record Result(int evidenceInserted,int relationshipsInserted,int candidatesInserted) {}
    private record Record(int packet,int index,String source,String target,String auth,String signature,String comparison,String correlation,String validation,String reason,Instant at,boolean eligible) {}
    private record Prepared(String id,Receipt receipt,String rawHash,Instant at,String exchangeKey,String classification,String authentication,int version,List<Record> records,boolean reject) {}
    public DiscoveryEvidenceStore(String path)throws SQLException {
        try(var schema=new SqliteNodeStore(path)){}
        db=DriverManager.getConnection("jdbc:sqlite:"+path);
        try(var s=db.createStatement()){s.execute("PRAGMA foreign_keys=ON");s.execute("PRAGMA busy_timeout=500");}catch(SQLException e){try{db.close();}catch(SQLException closing){e.addSuppressed(closing);}throw e;}
    }
    public static String clock(Instant at){return at==null?null:DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'").withZone(ZoneOffset.UTC).format(at);}
    private static Instant instant(JsonNode node,String field){try{return Instant.parse(node.path(field).asText());}catch(RuntimeException e){return null;}}
    private static String identity(String value){String n=NodeIds.normalize(value);return n.isEmpty()?null:n;}
    public static String evidenceId(ObserverInstance.Run run,String protocol,String kind,String receipt){return hash(json(Arrays.asList("discovery-v6",run==null?null:run.discoveryRunId().toString(),protocol,kind,receipt)));}
    private static void fields(JsonNode node,Set<String> allowed){if(!node.isObject())throw new IllegalArgumentException("Structured evidence object required");node.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw new IllegalArgumentException("Unexpected evidence field; rejected without logging payload");});}
    private static void validateTraceFields(JsonNode root){
        fields(root,TRACE_FIELDS);
        for(String field:List.of("counts","guardCounts")) {
            var counters=root.path(field);
            if(counters.isObject()) {if(counters.size()>96)throw new IllegalArgumentException("Counter bound");counters.fields().forEachRemaining(e->{if(!e.getKey().matches("[A-Z0-9_]{1,96}") || !e.getValue().isIntegralNumber() || e.getValue().asLong(-1)<0)throw new IllegalArgumentException("Invalid counter metadata");});}
            else if(!counters.isNull() && !counters.isMissingNode())throw new IllegalArgumentException("Invalid counter metadata");
        }
        if(!root.path("observer").isNull() && !root.path("observer").isMissingNode())fields(root.path("observer"),Set.of("instanceId","runId","keyLifecycle"));
        for(var p:root.path("packets")){fields(p,Set.of("at","wire","payload","sourceRoutingId","authenticated","status","records","omittedRecords"));for(var r:p.path("records"))fields(r,Set.of("index","rlp","identity","validation"));}
    }
    private static java.net.InetSocketAddress numericEndpoint(String endpoint)throws Exception {
        if(endpoint==null || endpoint.length()>80)throw new IllegalArgumentException("Invalid numeric UDP endpoint");
        String host,port;
        if(endpoint.startsWith("[")) {int split=endpoint.indexOf("]:");if(split<0)throw new IllegalArgumentException();host=endpoint.substring(1,split);port=endpoint.substring(split+2);if(!host.contains(":") || !host.matches("[0-9a-fA-F:]+(?:%[0-9A-Za-z_.-]{1,32})?"))throw new IllegalArgumentException();}
        else {int split=endpoint.lastIndexOf(':');if(split<0)throw new IllegalArgumentException();host=endpoint.substring(0,split);port=endpoint.substring(split+1);if(!host.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}"))throw new IllegalArgumentException();}
        int number=Integer.parseInt(port);if(number<1 || number>65535)throw new IllegalArgumentException();
        java.net.InetAddress address;
        if(host.contains(":"))address=java.net.InetAddress.getByName(host.split("%",2)[0]); // Unambiguous IPv6 literal only.
        else {String[] parts=host.split("\\.");byte[] bytes=new byte[4];for(int i=0;i<4;i++){int part=Integer.parseInt(parts[i]);if(part>255)throw new IllegalArgumentException();bytes[i]=(byte)part;}address=java.net.InetAddress.getByAddress(bytes);}
        return new java.net.InetSocketAddress(address,number);
    }
    private Prepared prepare(DiscoveryEvidenceBatch batch,Receipt receipt)throws Exception {
        JsonNode raw=JSON.readTree(receipt.rawJson());
        String id=evidenceId(batch.run(),batch.protocol(),receipt.kind(),receipt.receiptId());
        String digest=hash(canonicalJson(raw));var records=new ArrayList<Record>();
        if(batch.protocol().equals("discv5")) {
            if(!receipt.kind().equals("TRACE"))throw new IllegalArgumentException("V5 trace required");
            validateTraceFields(raw);
            String local=raw.path("localIdentity").asText();
            if(!local.matches("[0-9a-f]{128}")) {
                if(receipt.attestedEligible() || receipt.attestedContext())throw new IllegalArgumentException("Contradictory local attestation");
                return new Prepared(id,receipt,digest,instant(raw,"startedAt"),null,"CANDIDATE","UNAVAILABLE",raw.path("version").asInt(),List.of(),false);
            }
            boolean matchedRun=batch.run()!=null && local.equals(batch.run().localDiscoveryIdentity());
            if(receipt.attestedContext() && !matchedRun)throw new IllegalArgumentException("Authenticated run/key mismatch");
            var verify=new Discv5EvidenceCapture(matchedRun?batch.run():null,local);
            if(receipt.attestedContext())verify.ready(2,local);
            verify.accept(raw);
            var snapshot=verify.snapshot();
            if(snapshot.traces().size()!=1)throw new IllegalArgumentException("Malformed trace envelope");
            var checked=snapshot.traces().getFirst();
            boolean completeMetadata=raw.path("partial").isBoolean() && !raw.path("partial").asBoolean();
            for(var packet:raw.path("packets"))if(packet.path("omittedRecords").asLong(0)!=0)completeMetadata=false;
            boolean eligible=receipt.attestedEligible() && checked.eligibleContext() && completeMetadata;
            if(receipt.attestedEligible() && (!eligible || !receipt.captureOutcome().equals("COMPLETE_ATTRIBUTABLE") || !receipt.receiptId().equals(raw.path("traceId").asText())))
                throw new IllegalArgumentException("Claimed eligible exchange failed independent validation");
            String source=identity(raw.path("responderIdentity").asText());
            if(checked.responderEvidence()!=null) {
                var sourceEvidence=checked.responderEvidence();
                records.add(new Record(-1,-1,source,null,receipt.attestedContext()?AUTH:"UNVERIFIED",sourceEvidence.signature().name(),sourceEvidence.identityComparison().name(),"RESPONDER_CONTEXT",json(sourceEvidence.toMap()),sourceEvidence.usable()?null:"RESPONDER_"+sourceEvidence.outcome(),sourceEvidence.observedAt(),false));
            }
            int diagnostic=0;
            for(var entry:snapshot.counters().entrySet())if(!entry.getKey().equals("TRACE_RECEIVED"))records.add(new Record(-2,diagnostic++,source,null,"UNVERIFIED","NOT_TESTED","NOT_AVAILABLE","DIAGNOSTIC",json(Map.of("count",entry.getValue())),entry.getKey(),instant(raw,"startedAt"),false));
            for(String counterField:List.of("counts","guardCounts"))for(var it=raw.path(counterField).fields();it.hasNext();) {
                var entry=it.next();if(entry.getValue().asLong()>0 && !entry.getKey().equals("MATCHED_NODES"))records.add(new Record(-3,diagnostic++,source,null,"UNVERIFIED","NOT_TESTED","NOT_AVAILABLE","DIAGNOSTIC",json(Map.of("count",entry.getValue().asLong())),"TRANSPORT_"+entry.getKey(),instant(raw,"startedAt"),false));
            }
            for(var record:checked.records()) {
                var enr=record.enr();var report=snapshot.reports().stream().filter(r->r.packetIndex()==record.packetIndex() && r.recordIndex()==record.recordIndex()).findFirst();
                boolean promote=eligible && report.isPresent() && report.get().qualifiesForGraphPromotion();
                String reason=report.map(Discv5EvidenceCapture.Report::reason).orElse("NO_VALIDATED_TARGET_ATTRIBUTION");
                if(!promote && reason.equals("AUTHENTICATED_CORRELATED_REPORTED_NEIGHBOR"))reason=receipt.captureOutcome();
                records.add(new Record(record.packetIndex(),record.recordIndex(),source,identity(enr.record()==null?null:enr.record().identity().nodeId()),
                        record.correlated()?AUTH:"UNVERIFIED",enr.signature().name(),enr.identityComparison().name(),record.correlated()?"AUTHENTICATED_REQUEST_MATCH":"UNATTRIBUTED",
                        json(enr.toMap()),promote?null:reason,enr.observedAt(),promote));
            }
            boolean rejected=Set.of("CONTRADICTORY_TRACE","CONFLICTING_RECEIPT").contains(receipt.captureOutcome());
            String key=checked.exchangeKey().isEmpty()?null:hash(json(Arrays.asList(batch.run()==null?null:batch.run().discoveryRunId().toString(),"discv5",checked.exchangeKey())));
            return new Prepared(id,receipt,digest,instant(raw,"startedAt"),key,eligible?"ATTRIBUTABLE_EXCHANGE":"CANDIDATE",eligible?AUTH:"UNVERIFIED",2,records,rejected);
        }
        if(receipt.attestedEligible() || receipt.attestedContext())throw new IllegalArgumentException("Discv4 cannot supply authenticated request attribution");
        if(receipt.kind().equals("REQUEST")) {
            var request=JSON.readValue(receipt.rawJson(),Discv4EvidenceCapture.Request.class);
            if(!request.id().equals(receipt.receiptId()))throw new IllegalArgumentException("Request reference mismatch");
            var packet=Discv4Packet.decode(HexFormat.of().parseHex(request.rawPacketHex()));
            if(packet.type()!=3 || !HexFormat.of().formatHex(packet.hash()).equals(request.packetHash()) || !HexFormat.of().formatHex(packet.payload().children().getFirst().string()).equals(request.lookupTargetIdentity()) || request.observer()!=null && !packet.signer().nodeId().equals(request.observer().localDiscoveryIdentity()))throw new IllegalArgumentException("V4 request metadata contradiction");
            numericEndpoint(request.endpoint());
            return new Prepared(id,receipt,digest,request.sentAt(),null,"CANDIDATE","LOCAL_REQUEST_ONLY",1,List.of(),false);
        }
        if(!receipt.kind().equals("RESPONSE"))throw new IllegalArgumentException("V4 response required");
        var response=JSON.readValue(receipt.rawJson(),Discv4EvidenceCapture.Response.class);
        if(!response.id().equals(receipt.receiptId()) || !response.protocol().equals("discv4"))throw new IllegalArgumentException("Response reference mismatch");
        if(!Objects.equals(response.packetDigestScope(),response.rawTruncated()?"RETAINED_PREFIX_ONLY":"COMPLETE_DATAGRAM"))throw new IllegalArgumentException("Raw evidence scope contradiction");
        byte[] packet=HexFormat.of().parseHex(response.rawPacketHex());
        if(packet.length>1280)throw new IllegalArgumentException("Oversized raw packet");
        String signer=null;
        if(!response.rawTruncated())try{signer=Discv4Packet.decode(packet).signer().nodeId();}catch(IllegalArgumentException ignored){}
        if(response.responderIdentity()!=null && !response.responderIdentity().equals(signer))throw new IllegalArgumentException("V4 signer metadata contradiction");
        if(!HexFormat.of().formatHex(org.web3j.crypto.Hash.sha3(packet)).equals(response.packetDigest()))throw new IllegalArgumentException("V4 raw digest contradiction");
        var endpoint=numericEndpoint(response.endpoint());
        if(!response.rawTruncated()) {
            var verifier=new Discv4EvidenceCapture(batch.run(),Clock.fixed(response.observedAt(),ZoneOffset.UTC));verifier.receive(packet,endpoint);
            var checked=verifier.snapshot().responses();
            if(checked.isEmpty() || !checked.getFirst().descriptors().equals(response.descriptors()))throw new IllegalArgumentException("V4 descriptor metadata contradiction");
        } else if(!response.descriptors().isEmpty())throw new IllegalArgumentException("Truncated packet cannot supply complete descriptors");

        int diagnosticIndex=0;
        for(String diagnostic:response.diagnostics())records.add(new Record(-1,diagnosticIndex++,signer,null,signer==null?"UNVERIFIED":"SIGNATURE_VERIFIED","NOT_TESTED","NOT_AVAILABLE",response.correlation().name(),json(Map.of("code",diagnostic)),diagnostic,response.observedAt(),false));
        for(var d:response.descriptors())records.add(new Record(0,d.index(),signer,identity(d.targetIdentity()),signer==null?"UNVERIFIED":"SIGNATURE_VERIFIED","NOT_TESTED","NOT_AVAILABLE","UNBOUND_DISCV4",
                json(d),d.validation().equals("VALID_CURVE_POINT_UNVERIFIED_CLAIM")?"DISCV4_REQUEST_UNBOUND":d.validation(),response.observedAt(),false));
        return new Prepared(id,receipt,digest,response.observedAt(),null,"CANDIDATE",signer==null?"UNVERIFIED":"SIGNATURE_VERIFIED",1,records,false);
    }
    private void writeCheck()throws SQLException {if(Thread.currentThread().isInterrupted() || System.nanoTime()-writeDeadline>=0)throw new SQLTimeoutException("Discovery evidence write deadline exceeded");}
    private int execute(String sql,Object... values)throws SQLException {
        writeCheck();
        try(var q=db.prepareStatement(sql)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);int changed=q.executeUpdate();writeCheck();return changed;}
    }
    private String text(String sql,Object... values)throws SQLException {
        writeCheck();
        try(var q=db.prepareStatement(sql)){for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);try(var r=q.executeQuery()){return r.next()?r.getString(1):null;}}
    }
    private void run(ObserverInstance.Run run)throws SQLException {
        if(run==null)return;
        execute("INSERT INTO observer_installations(id) VALUES(?) ON CONFLICT(id) DO NOTHING",run.observerInstanceId().toString());
        String existing=text("SELECT observer_id||'|'||started_at||'|'||COALESCE(local_identity,'')||'|'||key_lifecycle FROM discovery_runs WHERE id=?",run.discoveryRunId().toString());
        String expected=run.observerInstanceId()+"|"+clock(run.startedAt())+"|"+Objects.toString(run.localDiscoveryIdentity(),"")+"|"+run.keyLifecycle().name();
        if(existing!=null && !existing.equals(expected))throw new SQLException("Contradictory immutable discovery run");
        execute("INSERT INTO discovery_runs VALUES(?,?,?,?,?) ON CONFLICT(id) DO NOTHING",run.discoveryRunId().toString(),run.observerInstanceId().toString(),clock(run.startedAt()),run.localDiscoveryIdentity(),run.keyLifecycle().name());
    }
    public synchronized Result ingest(DiscoveryEvidenceBatch batch)throws SQLException {
        var prepared=new ArrayList<Prepared>();
        try {for(var receipt:batch.receipts())prepared.add(prepare(batch,receipt));}
        catch(Exception e){throw new SQLException("Rejected discovery evidence batch: invalid or contradictory contract",e);}
        if(prepared.stream().mapToInt(p->p.records().size()).sum()>MAX_RECORDS)throw new SQLException("Batch normalized-record budget exceeded");
        int inserted=0,relations=0,candidates=0;String now=clock(Instant.now());
        db.setAutoCommit(false);writeDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        try(var deadline=new SqliteReadDeadline(db,Duration.ofSeconds(1))) {
            run(batch.run());
            for(var p:prepared) {
                String existing=text("SELECT raw_hash FROM discovery_exchange_evidence WHERE id=?",p.id());
                if(existing!=null && !existing.equals(p.rawHash()))throw new SQLException("Contradictory immutable evidence ID");
                String owner=p.exchangeKey()==null?null:text("SELECT evidence_id FROM discovery_exchange_claims WHERE exchange_key=?",p.exchangeKey());
                boolean duplicate=owner!=null && !owner.equals(p.id());
                String classification=duplicate?"CANDIDATE":p.classification();
                String previous=text("SELECT classification FROM discovery_exchange_evidence WHERE id=?",p.id());
                if(previous!=null && !previous.equals(classification) && !p.reject())throw new SQLException("Evidence cannot be reclassified on retry");
                inserted+=execute("INSERT INTO discovery_exchange_evidence VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",p.id(),p.receipt().receiptId(),batch.run()==null?null:batch.run().discoveryRunId().toString(),batch.protocol(),p.receipt().kind(),p.version(),clock(p.at()),p.receipt().rawJson(),p.rawHash(),p.exchangeKey(),classification,p.authentication(),now);
                if(p.exchangeKey()!=null)execute("INSERT INTO discovery_exchange_claims VALUES(?,?) ON CONFLICT(exchange_key) DO NOTHING",p.exchangeKey(),p.id());
                if(p.reject())execute("INSERT INTO discovery_evidence_rejections VALUES(?,?) ON CONFLICT(evidence_id,reason) DO NOTHING",p.id(),p.receipt().captureOutcome());
                for(var r:p.records()) {
                    if(r.validation().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw new SQLException("Record payload budget exceeded");
                    execute("INSERT INTO discovery_record_evidence VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(evidence_id,packet_index,record_index) DO NOTHING",p.id(),r.packet(),r.index(),r.source(),r.target(),r.auth(),r.signature(),r.comparison(),r.correlation(),r.validation());
                    if(r.packet()<0 && r.reason()==null)continue;
                    if(r.eligible() && !duplicate && !p.reject()) {
                        if(batch.run()==null)throw new SQLException("Eligible relationship requires observer/run context");
                        relations+=execute("INSERT INTO discovery_relationship_observations(id,evidence_id,run_id,observer_id,protocol,source_identity,target_identity,relation_type,observed_at,packet_index,record_index,correlation_ref,authentication,enr_signature,identity_comparison,contract_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
                                hash(json(List.of(p.id(),r.packet(),r.index()))),p.id(),batch.run().discoveryRunId().toString(),batch.run().observerInstanceId().toString(),batch.protocol(),r.source(),r.target(),"REPORTED_NEIGHBOR",clock(r.at()),r.packet(),r.index(),p.exchangeKey(),AUTH,"VALID","MATCH",2);
                    } else candidates+=candidate(p.id(),r.packet(),r.index(),clock(r.at()),p.reject()?p.receipt().captureOutcome():duplicate?"DUPLICATE_REQUEST_CONTEXT":r.reason(),r.source(),r.target());
                }
                if(p.records().stream().noneMatch(r->r.packet()>=0) || duplicate || p.reject() || p.classification().equals("CANDIDATE"))
                    candidates+=candidate(p.id(),-1,-1,clock(p.at()),p.reject()?p.receipt().captureOutcome():duplicate?"DUPLICATE_REQUEST_CONTEXT":p.classification().equals("ATTRIBUTABLE_EXCHANGE")?"MEASURED_EMPTY_OR_SELF_RESPONSE":p.receipt().captureOutcome(),null,null);
            }
            if(batch.health()!=null) {
                String raw=json(batch.health()),canonical;
                try{canonical=canonicalJson(JSON.readTree(raw));}catch(java.io.IOException e){throw new SQLException("Invalid health metadata",e);}
                String id=hash(json(Arrays.asList("capture-health",batch.run()==null?null:batch.run().discoveryRunId().toString(),batch.streamId(),batch.protocol(),canonical)));
                inserted+=execute("INSERT INTO discovery_exchange_evidence VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",id,id,batch.run()==null?null:batch.run().discoveryRunId().toString(),batch.protocol(),"HEALTH",1,now,raw,hash(canonical),null,"CAPTURE_HEALTH","LOCAL_COUNTERS",now);
                execute("INSERT INTO discovery_capture_health VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",id,batch.run()==null?null:batch.run().discoveryRunId().toString(),batch.streamId(),batch.protocol(),now,raw);
                if(batch.health().reportsDropsOrEvictions())candidates+=candidate(id,-1,-1,now,"CAPTURE_DROP_OR_EVICTION_REPORTED",null,null);
            }
            writeCheck();db.commit();return new Result(inserted,relations,candidates);
        }catch(SQLException|RuntimeException e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
    }
    private int candidate(String evidence,int packet,int index,String at,String reason,String source,String target)throws SQLException {
        if(reason==null || !reason.matches("[A-Z0-9_]{1,128}"))throw new SQLException("Invalid non-promotion reason");
        return execute("INSERT INTO discovery_candidates VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING",hash(json(List.of(evidence,packet,index,reason))),evidence,at,packet,index,reason,source,target);
    }
    @Override public void close()throws SQLException{db.close();}
}
