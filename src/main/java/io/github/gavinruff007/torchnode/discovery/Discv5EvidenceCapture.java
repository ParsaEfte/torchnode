package io.github.gavinruff007.torchnode.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gavinruff007.torchnode.enr.*;
import io.github.gavinruff007.torchnode.model.*;
import org.web3j.crypto.Hash;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Non-durable B3 capture. Authentication is attested by the trusted local helper, not re-decrypted in Java. */
public final class Discv5EvidenceCapture {
    public static final int MAX_TRACES=128, MAX_REPORTS=1024, MAX_EVENT_BYTES=60000;
    public record RecordEvidence(int packetIndex,int recordIndex,String helperValidation,EnrEvidence enr,boolean correlated) {}
    public record Trace(String id,String rawJson,String digest,String outcome,List<RecordEvidence> records,boolean eligibleContext,String exchangeKey,EnrEvidence responderEvidence) {
        public Trace { records=List.copyOf(records); }
    }
    public record Report(String sourceEvidenceId,int packetIndex,int recordIndex,ObserverInstance.Run observer,
                         String codecContext,String requestId,String sourceResponderIdentity,String targetIdentity,
                         Instant observedAt,boolean qualifiesForGraphPromotion,String reason) {
        public String protocol(){return "discv5";}
        public String relationType(){return "REPORTED_NEIGHBOR";}
    }
    public record Snapshot(List<Trace> traces,List<Report> reports,Map<String,Long> counters,long evictedTraces,
                           long evictedReports,Long helperDropHighWaterMark,boolean durable) {}
    private final ObserverInstance.Run observer;
    private final String localIdentity;
    private final LinkedHashMap<String,Trace> traces=new LinkedHashMap<>();
    private final ArrayDeque<Report> reports=new ArrayDeque<>();
    private final Map<String,Long> counters=new TreeMap<>();
    private boolean authenticatedReady;
    private long evictedTraces,evictedReports;
    private Long helperDrops;
    public Discv5EvidenceCapture(ObserverInstance.Run observer,String localIdentity) {
        this.observer=observer;this.localIdentity=canonical(localIdentity);
        if(observer!=null && !this.localIdentity.equals(observer.localDiscoveryIdentity()))
            throw new IllegalArgumentException("Observer run does not match actual local discovery key");
    }
    public synchronized void ready(int version,String identity) {
        authenticatedReady=false;
        if(!localIdentity.equals(identity))throw new IllegalStateException("Helper local discovery identity mismatch");
        authenticatedReady=version==2;
        if(!authenticatedReady)count("LEGACY_HELPER_NO_ATTRIBUTION");
    }
    public synchronized void helperUnavailable(){authenticatedReady=false;}
    public synchronized void helperDrops(long value){if(value>=0)helperDrops=helperDrops==null?value:Math.max(helperDrops,value);}
    public synchronized void diagnose(String code){count(code);}
    private void count(String code){counters.merge(code,1L,Long::sum);}
    private static String canonical(String value){if(value==null || !value.matches("[0-9a-f]{128}"))throw new IllegalArgumentException("Canonical public identity required");return value;}
    private static byte[] hex(JsonNode node,String field,int max) {
        String text=node.path(field).asText();
        if(text.length()>max*2 || (text.length()&1)!=0 || !text.matches("[0-9a-f]*"))throw new IllegalArgumentException("Invalid bounded hex field");
        return HexFormat.of().parseHex(text);
    }
    private static String token(JsonNode node,String name){String value=node.path(name).asText();if(!value.matches("[0-9a-f]{32}"))throw new IllegalArgumentException("Invalid local correlation token");return value;}
    private static String routing(String identity){return HexFormat.of().formatHex(Hash.sha3(HexFormat.of().parseHex(identity)));}
    private static int distance(String a,String b){byte[] x=HexFormat.of().parseHex(a),y=HexFormat.of().parseHex(b);for(int i=0;i<32;i++){int v=(x[i]^y[i])&255;if(v!=0)return (32-i)*8-Integer.numberOfLeadingZeros(v)+24;}return 0;}
    public synchronized void accept(JsonNode event) {
        String raw=event.toString();
        if(raw.getBytes(StandardCharsets.UTF_8).length>MAX_EVENT_BYTES){count("OVERSIZED_TRACE");return;}
        String id;
        try{id=token(event,"traceId");}catch(RuntimeException e){count("MALFORMED_TRACE");return;}
        String digest=HexFormat.of().formatHex(Hash.sha3(raw.getBytes(StandardCharsets.UTF_8)));
        Trace previous=traces.get(id);
        if(previous!=null){
            if(previous.digest().equals(digest)){count("DUPLICATE_TRACE");return;}
            reports.removeIf(r->r.sourceEvidenceId().equals(id));
            traces.put(id,new Trace(id,previous.rawJson(),previous.digest(),"CONTRADICTORY_TRACE",previous.records(),false,previous.exchangeKey(),previous.responderEvidence()));
            store(new Trace(UUID.randomUUID().toString().replace("-",""),raw,digest,"CONFLICTING_RECEIPT",List.of(),false,previous.exchangeKey(),previous.responderEvidence()));
            count("CONTRADICTORY_TRACE");return;
        }
        var records=new ArrayList<RecordEvidence>();var pending=new ArrayList<Report>();
        EnrEvidence responderEvidence=null;
        boolean eligible=false;String outcome="MALFORMED_TRACE",exchangeKey="";
        try {
            if(!event.path("version").isIntegralNumber() || event.path("version").asInt()!=2)throw new IllegalArgumentException("Unknown contract");
            token(event,"codecContext");
            if(event.path("outcome").asText().equals("COMPLETE") && !event.path("failureCode").asText().isEmpty())throw new IllegalArgumentException("Contradictory completion");
            if(!event.path("omittedPackets").isIntegralNumber() || event.path("omittedPackets").asLong(-1)<0)throw new IllegalArgumentException("Missing truncation metadata");
            String source=canonical(event.path("responderIdentity").asText());
            Instant started=Instant.parse(event.path("startedAt").asText()), completed=Instant.parse(event.path("completedAt").asText());
            if(completed.isBefore(started))throw new IllegalArgumentException("Invalid trace clocks");
            var sourceEnr=new EnrDecoder().decode(hex(event,"responderEnr",300),new NodeIdentity(source),started,"discv5 responder context");
            responderEvidence=sourceEnr;
            if(!sourceEnr.usable())count("RESPONDER_"+sourceEnr.outcome());
            boolean sourceValid=sourceEnr.usable() && routing(source).equals(event.path("responderRoutingId").asText());
            var request=CanonicalRlp.decode(hex(event,"requestPayload",1280),1280);
            if(!request.list() || request.children().size()!=2 || !request.children().get(1).list())throw new IllegalArgumentException("Invalid FINDNODE");
            byte[] requestId=hex(event,"requestId",8);
            if(requestId.length==0 || !Arrays.equals(requestId,request.children().getFirst().string()))throw new IllegalArgumentException("Request ID contradiction");
            exchangeKey=event.path("codecContext").asText()+"/"+source+"/"+event.path("requestId").asText();
            String exchange=exchangeKey;
            boolean duplicateExchange=traces.values().stream().anyMatch(t->t.exchangeKey().equals(exchange));
            if(duplicateExchange)count("DUPLICATE_REQUEST_CONTEXT");
            var distances=new HashSet<Integer>();
            for(var d:request.children().get(1).children()) {int value=d.uint(2).intValueExact();if(value>256)throw new IllegalArgumentException("Distance overflow");distances.add(value);}
            if(distances.isEmpty() || distances.size()>3)throw new IllegalArgumentException("Unbounded FINDNODE distances");
            var declared=new HashSet<Integer>();
            if(!event.path("distances").isArray() || event.path("distances").size()>3)throw new IllegalArgumentException("Missing distances");
            for(var d:event.path("distances")){if(!d.isIntegralNumber())throw new IllegalArgumentException();declared.add(d.intValue());}
            if(!declared.equals(distances))throw new IllegalArgumentException("Distance contradiction");
            var ctx=event.path("observer");
            boolean observerMatches=observer!=null && observer.observerInstanceId().toString().equals(ctx.path("instanceId").asText())
                    && observer.discoveryRunId().toString().equals(ctx.path("runId").asText())
                    && observer.keyLifecycle().name().equals(ctx.path("keyLifecycle").asText())
                    && localIdentity.equals(event.path("localIdentity").asText());
            boolean contextValid=authenticatedReady && observerMatches && sourceValid;
            if(!contextValid)count("MISSING_OR_INVALID_AUTHENTICATED_CONTEXT");
            var packets=event.path("packets");
            if(!packets.isNull() && (!packets.isArray() || packets.size()>8))throw new IllegalArgumentException("Packet bound");
            var seen=new HashSet<String>();var seenTargets=new HashSet<String>();int matched=0,total=-1,recordCount=0;boolean contradictory=false,invalidEnr=false;
            for(int p=0;p<packets.size();p++) {
                var packet=packets.get(p);if(!packet.path("authenticated").isBoolean())throw new IllegalArgumentException("Invalid authentication state");if(hex(packet,"wire",1280).length<63)throw new IllegalArgumentException("Missing wire evidence");
                Instant at=Instant.parse(packet.path("at").asText());
                if(at.isBefore(started) || at.isAfter(completed))throw new IllegalArgumentException("Packet outside request lifetime");
                if(packet.path("payload").asText().isEmpty())continue;
                byte[] payload=hex(packet,"payload",1280);
                var nodes=CanonicalRlp.decode(payload,1280);
                if(!nodes.list() || nodes.children().size()!=3 || !nodes.children().get(2).list())throw new IllegalArgumentException("Malformed NODES");
                int advertised=nodes.children().get(1).uint(1).intValueExact();
                boolean correlated=contextValid && packet.path("authenticated").asBoolean()
                        && event.path("responderRoutingId").asText().equals(packet.path("sourceRoutingId").asText())
                        && Arrays.equals(requestId,nodes.children().getFirst().string()) && packet.path("status").asText().equals("MATCHED_NODES");
                boolean duplicate=!seen.add(packet.path("payload").asText());
                if(duplicate){count("DUPLICATE_NODES");correlated=false;}
                if(correlated){matched++;if(total<0)total=advertised;if(total!=advertised || advertised<1 || advertised>5)contradictory=true;}
                var values=nodes.children().get(2).children();var helper=packet.path("records");
                if(values.size()>16 || !helper.isArray() || helper.size()!=values.size())throw new IllegalArgumentException("ENR count contradiction");
                for(int r=0;r<values.size();r++) {
                    if(++recordCount>128)throw new IllegalArgumentException("Record bound");
                    byte[] rawEnr=values.get(r).encoded();var claim=helper.get(r);
                    if(claim.path("index").asInt(-1)!=r || !Arrays.equals(rawEnr,hex(claim,"rlp",300)))throw new IllegalArgumentException("ENR payload contradiction");
                    String target=claim.path("identity").asText();
                    if(!target.isEmpty())canonical(target);
                    var enr=new EnrDecoder().decode(rawEnr,new NodeIdentity(target),at,"discv5 NODES trace="+id+" packet="+p+" record="+r);
                    boolean valid=enr.usable() && distances.contains(distance(routing(source),routing(target))) && claim.path("validation").asText().equals("VALID");
                    records.add(new RecordEvidence(p,r,claim.path("validation").asText(),enr,correlated));
                    if(!valid){invalidEnr=true;count("INVALID_OR_MISMATCHED_ENR");}
                    boolean duplicateTarget=valid && correlated && !seenTargets.add(target);
                    if(duplicateTarget)count("DUPLICATE_ENR");
                    String reason=duplicateTarget?"DUPLICATE_ENR":!valid?"INVALID_OR_MISMATCHED_ENR":duplicate?"DUPLICATE_NODES":!correlated?"UNATTRIBUTED_RESPONSE":source.equals(target)?"SELF_RECORD":"PENDING_COMPLETENESS";
                    if(!target.isEmpty())pending.add(new Report(id,p,r,observer,event.path("codecContext").asText(),event.path("requestId").asText(),source,target,at,false,reason));
                }
            }
            eligible=!duplicateExchange && contextValid && event.path("outcome").asText().equals("COMPLETE") && event.path("omittedPackets").asLong(-1)==0
                    && matched>=1 && matched==total && !contradictory && !invalidEnr && recordCount<=16;
            outcome=eligible?"COMPLETE_ATTRIBUTABLE": "INCOMPLETE_OR_UNATTRIBUTABLE";
            if(!eligible)count(outcome);
        } catch(RuntimeException e){count("MALFORMED_TRACE");eligible=false;}
        store(new Trace(id,raw,digest,outcome,records,eligible,exchangeKey,responderEvidence));
        for(var r:pending) {
            boolean promote=eligible && r.reason().equals("PENDING_COMPLETENESS");
            if(reports.size()==MAX_REPORTS){reports.removeFirst();evictedReports++;count("REPORT_EVICTED");}
            reports.addLast(new Report(r.sourceEvidenceId(),r.packetIndex(),r.recordIndex(),r.observer(),r.codecContext(),r.requestId(),r.sourceResponderIdentity(),r.targetIdentity(),r.observedAt(),promote,
                    promote?"AUTHENTICATED_CORRELATED_REPORTED_NEIGHBOR":r.reason().equals("PENDING_COMPLETENESS")?"INCOMPLETE_OR_UNATTRIBUTABLE":r.reason()));
        }
        count("TRACE_RECEIVED");
    }
    private void store(Trace value) {
        if(traces.size()==MAX_TRACES) {
            String oldest=traces.keySet().iterator().next();traces.remove(oldest);evictedTraces++;count("TRACE_EVICTED");
            for(var it=reports.iterator();it.hasNext();)if(it.next().sourceEvidenceId().equals(oldest)){it.remove();evictedReports++;count("REPORT_SOURCE_EVICTED");}
        }
        traces.put(value.id(),value);
    }
    public synchronized Snapshot snapshot(){return new Snapshot(List.copyOf(traces.values()),List.copyOf(reports),Map.copyOf(counters),evictedTraces,evictedReports,helperDrops,false);}
}
