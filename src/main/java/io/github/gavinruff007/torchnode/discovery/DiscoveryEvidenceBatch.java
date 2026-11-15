package io.github.gavinruff007.torchnode.discovery;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Trusted collector handoff; not an HTTP ingestion contract. No private-key fields exist here. */
public record DiscoveryEvidenceBatch(ObserverInstance.Run run,String protocol,String streamId,List<Receipt> receipts,Health health) {
    public static final int MAX_BATCH=16,MAX_RAW=60000;
    public static final ObjectMapper JSON=new ObjectMapper();
    static {
        var module=new SimpleModule();
        module.addSerializer(Instant.class,new JsonSerializer<Instant>(){public void serialize(Instant value,JsonGenerator out,SerializerProvider p)throws IOException{out.writeString(value.toString());}});
        module.addDeserializer(Instant.class,new JsonDeserializer<Instant>(){public Instant deserialize(JsonParser in,DeserializationContext c)throws IOException{return Instant.parse(in.getValueAsString());}});
        JSON.registerModule(module);JSON.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    public record Receipt(String kind,String receiptId,String rawJson,boolean attestedContext,boolean attestedEligible,String captureOutcome) {
        public Receipt {
            if(!Set.of("TRACE","REQUEST","RESPONSE").contains(kind) || receiptId==null || !receiptId.matches("[A-Za-z0-9_-]{1,128}")
                    || rawJson==null || rawJson.getBytes(StandardCharsets.UTF_8).length>MAX_RAW || captureOutcome==null || !captureOutcome.matches("[A-Z0-9_]{1,128}"))
                throw new IllegalArgumentException("Malformed or oversized discovery receipt");
        }
    }
    public record Health(Map<String,Long> counters,long evictedRaw,long evictedRequests,long evictedReports,Long helperDropHighWaterMark) {
        public Health {
            counters=Map.copyOf(counters);
            if(counters.size()>96 || evictedRaw<0 || evictedRequests<0 || evictedReports<0 || helperDropHighWaterMark!=null && helperDropHighWaterMark<0)throw new IllegalArgumentException("Invalid capture counters");
            counters.forEach((key,value)->{if(!key.matches("[A-Z0-9_]{1,96}") || value<0)throw new IllegalArgumentException("Invalid diagnostic counter");});
        }
        public boolean reportsDropsOrEvictions(){return evictedRaw>0 || evictedRequests>0 || evictedReports>0 || helperDropHighWaterMark!=null && helperDropHighWaterMark>0;}
    }
    public DiscoveryEvidenceBatch {
        streamId=UUID.fromString(streamId).toString();
        if(!Set.of("discv4","discv5").contains(protocol))throw new IllegalArgumentException("Invalid discovery protocol");
        receipts=List.copyOf(receipts);if(receipts.size()>MAX_BATCH)throw new IllegalArgumentException("Evidence batch exceeds 16 receipts");
    }
    public static String json(Object value){try{return JSON.writeValueAsString(value);}catch(IOException e){throw new IllegalArgumentException("Cannot encode evidence",e);}}
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    public static String canonicalJson(JsonNode value){
        if(value.isObject()){var sorted=new TreeMap<String,JsonNode>();value.fields().forEachRemaining(e->sorted.put(e.getKey(),e.getValue()));var node=JSON.createObjectNode();sorted.forEach((k,v)->node.set(k,parseCanonical(v)));return node.toString();}
        if(value.isArray()){var node=JSON.createArrayNode();value.forEach(v->node.add(parseCanonical(v)));return node.toString();}return value.toString();
    }
    private static JsonNode parseCanonical(JsonNode node){try{return JSON.readTree(canonicalJson(node));}catch(IOException e){throw new IllegalArgumentException(e);}}
    public String fingerprint(){return hash(json(this));}
    private static List<DiscoveryEvidenceBatch> split(ObserverInstance.Run run,String protocol,String stream,List<Receipt> receipts,Health health){
        var result=new ArrayList<DiscoveryEvidenceBatch>();
        for(int i=0;i<receipts.size();i+=MAX_BATCH)result.add(new DiscoveryEvidenceBatch(run,protocol,stream,receipts.subList(i,Math.min(i+MAX_BATCH,receipts.size())),i==0?health:null));
        if(result.isEmpty())result.add(new DiscoveryEvidenceBatch(run,protocol,stream,List.of(),health));return List.copyOf(result);
    }
    public static List<DiscoveryEvidenceBatch> v5(ObserverInstance.Run run,String stream,Discv5EvidenceCapture.Snapshot snapshot){
        var receipts=snapshot.traces().stream().map(t->new Receipt("TRACE",t.id(),t.rawJson(),t.eligibleContext() || t.records().stream().anyMatch(Discv5EvidenceCapture.RecordEvidence::correlated),t.eligibleContext(),t.outcome())).toList();
        return split(run,"discv5",stream,receipts,new Health(snapshot.counters(),snapshot.evictedTraces(),0,snapshot.evictedReports(),snapshot.helperDropHighWaterMark()));
    }
    public static List<DiscoveryEvidenceBatch> v4(ObserverInstance.Run run,String stream,Discv4EvidenceCapture.Snapshot snapshot){
        var receipts=new ArrayList<Receipt>();
        snapshot.requests().forEach(r->receipts.add(new Receipt("REQUEST",r.id(),json(r),false,false,"V4_REQUEST_CONTEXT")));
        snapshot.responses().forEach(r->receipts.add(new Receipt("RESPONSE",r.id(),json(r),false,false,r.validation())));
        return split(run,"discv4",stream,receipts,new Health(snapshot.diagnosticCounts(),snapshot.evictedResponses(),snapshot.evictedRequests(),snapshot.evictedReports(),null));
    }
}
