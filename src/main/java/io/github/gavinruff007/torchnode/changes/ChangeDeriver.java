package io.github.gavinruff007.torchnode.changes;

import io.github.gavinruff007.torchnode.inspection.ClientDetails;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore.InspectionHistory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.time.Instant;

/** Pure comparison inputs extracted only from evidence actually present in a run. */
public final class ChangeDeriver {
    private ChangeDeriver() {}

    public record Fact(String domain, String subject, String type, String reference,
                       String observedAt, String value, String source, String endpoint,
                       String family) {
        public String key() { return domain + "\u0000" + subject + "\u0000" + type; }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value) {
        return value instanceof Map<?,?> m ? (Map<String,Object>)m : Map.of();
    }
    private static List<?> list(Object value) { return value instanceof List<?> l ? l : List.of(); }
    private static String string(Object value) { return value == null ? null : value.toString(); }
    private static boolean real(String state) {
        return "PASS".equals(state) || "FAILED".equals(state) || "TIMEOUT".equals(state);
    }
    private static boolean observerRestricted(Map<String,Object> stage) {
        String text = (string(stage.get("reason")) + " " + string(stage.get("reasonCode"))).toLowerCase();
        return text.contains("operation not permitted") || text.contains("permission denied")
                || text.contains("network is unreachable") || text.contains("no route to host");
    }
    private static void client(List<Fact> facts,String domain,String endpoint,String family,String at,
                               String reference,String source,String raw) {
        ClientDetails details=ClientDetails.parse(raw);
        if(details==null || at==null || endpoint==null)return;
        String subject=source+"|"+endpoint+"|"+family;
        if(details.name()!=null && !details.name().isBlank())facts.add(new Fact(domain,subject,"CLIENT_IMPLEMENTATION_CHANGED",
                reference,at,details.name(),source,endpoint,family));
        if(details.version()!=null && !details.version().isBlank())facts.add(new Fact(domain,subject+"|"+details.name(),"CLIENT_VERSION_CHANGED",
                reference,at,details.version(),source,endpoint,family));
    }

    public static List<Fact> inspectionFacts(InspectionHistory run) {
        var result=new ArrayList<Fact>();
        Map<String,Object> evidence=run.evidence();
        for(Object rawAttempt:list(evidence.get("endpointAttempts"))) {
            var attempt=map(rawAttempt);String endpoint=string(attempt.get("endpoint"));
            String family=string(attempt.get("addressFamily"));String at=string(attempt.get("observedAt"));
            if(endpoint==null || family==null || at==null)continue;
            var states=new LinkedHashMap<String,String>();
            for(Object rawStage:list(attempt.get("diagnostics"))) {
                var stage=map(rawStage);String name=string(stage.get("name"));String state=string(stage.get("state"));
                if(name==null || !real(state) || observerRestricted(stage))continue;
                states.put(name,state);
                String type=switch(name) {
                    case "P2P TCP" -> "TCP_ATTEMPT_OUTCOME_CHANGED";
                    case "RLPx Auth" -> "RLPX_ATTEMPT_OUTCOME_CHANGED";
                    case "RLPx Hello" -> "HELLO_ATTEMPT_OUTCOME_CHANGED";
                    case "ETH Status" -> "ETH_STATUS_ATTEMPT_OUTCOME_CHANGED";
                    default -> null;
                };
                if(type!=null)result.add(new Fact("P2P",name+"|"+endpoint+"|"+family,type,
                        run.id(),at,state,"P2P",endpoint,family));
            }
            if(!"PASS".equals(states.get("RLPx Hello")))continue;
            var hello=map(map(attempt.get("p2p")).get("hello"));
            client(result,"HELLO",endpoint,family,at,run.id(),"RLPx Hello",string(hello.get("clientId")));
            if(hello.get("capabilities") instanceof List<?>) {
                var capabilities=new TreeSet<String>();
                boolean complete=true;
                for(Object item:list(hello.get("capabilities"))) {
                    if(item instanceof String value && !value.isBlank())capabilities.add(value.trim().toLowerCase(java.util.Locale.ROOT));
                    else if(item instanceof Map<?,?> value && value.get("name")!=null && value.get("version")!=null)
                        capabilities.add((value.get("name")+"/"+value.get("version")).toLowerCase(java.util.Locale.ROOT));
                    else complete=false;
                }
                if(complete)result.add(new Fact("HELLO","capabilities|"+endpoint+"|"+family,"CAPABILITIES_CHANGED",
                        run.id(),at,String.join(",",capabilities),"RLPx Hello",endpoint,family));
            }
        }
        apiFacts(result,run,"rpc","rpcAttempts","RPC","RPC_PROBE_OUTCOME_CHANGED","rpcReachable","clientVersion");
        apiFacts(result,run,"beacon","beaconAttempts","BEACON","BEACON_PROBE_OUTCOME_CHANGED","beaconReachable","version");
        return List.copyOf(result);
    }

    private static void apiFacts(List<Fact> facts,InspectionHistory run,String evidenceKey,String attemptsKey,
                                 String domain,String outcomeType,String reachableKey,String clientKey) {
        for(Object raw:list(run.evidence().get(attemptsKey))) {
            var attempt=map(raw);String endpoint=string(attempt.get("endpoint"));String at=string(attempt.get("attemptedAt"));
            if(endpoint==null || at==null || !Boolean.TRUE.equals(attempt.get("tcpOpen"))
                    && !Boolean.FALSE.equals(attempt.get("tcpOpen")))continue;
            String outcome=Boolean.TRUE.equals(attempt.get(reachableKey))?"PASS":
                    Boolean.FALSE.equals(attempt.get("tcpOpen"))?"NO_TCP_CONNECTION":
                    Boolean.FALSE.equals(attempt.get(reachableKey))?"FAILED":null;
            if(outcome==null || observerRestricted(attempt))continue;
            facts.add(new Fact(domain,endpoint,outcomeType,run.id(),at,outcome,domain,endpoint,
                    endpoint.startsWith("http://[")?"IPV6":"IPV4"));
        }
        var response=map(run.evidence().get(evidenceKey));
        String endpoint=string(response.get("endpoint"));String at=string(response.get("observedAt"));
        client(facts,domain,endpoint,endpoint!=null && endpoint.startsWith("http://[")?"IPV6":"IPV4",
                at,run.id(),domain,string(response.get(clientKey)));
    }

    /** A tie is display-orderable but has no proven before/after relation. */
    public static boolean comparable(Fact older,Fact newer) {
        return older!=null && newer!=null && Objects.equals(older.key(),newer.key())
                && older.observedAt()!=null && newer.observedAt()!=null
                && Instant.parse(older.observedAt()).isBefore(Instant.parse(newer.observedAt()));
    }

    public static final Comparator<Fact> NEWEST_FIRST=Comparator.comparing((Fact fact)->Instant.parse(fact.observedAt()),
            Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Fact::reference,Comparator.reverseOrder());
}
