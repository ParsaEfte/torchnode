package io.github.gavinruff007.torchnode.analysis;

import io.github.gavinruff007.torchnode.enr.EnrEvidence;
import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import java.net.URI;
import java.time.*;
import java.util.*;

/** Pure analysis of recorded evidence. Neither probes nor changes canonical endpoints. */
public final class EndpointAnalysis {
    public enum Outcome { MATCH, MISMATCH, INSUFFICIENT_EVIDENCE, NOT_COMPARABLE, NOT_OBSERVED }
    public enum NatEvidence { NO_NAT_EVIDENCE, POSSIBLE_NAT_OR_ENDPOINT_TRANSLATION, NAT_EVIDENCE_INSUFFICIENT }
    public enum Strength { DISCOVERY_CLAIM, TRUSTED_ENR, TCP_ATTEMPT, TCP_REACHABLE, RLPX_AUTH, HELLO_PORT, RPC_API, BEACON_API }
    public static final Duration MAX_TIME_GAP = Duration.ofMinutes(5);
    public static final int MAX_EVIDENCE = 512, MAX_COMPARISONS = 256;
    public record Evidence(String id, String source, Strength strength, NodeEndpoint endpoint,
                           Integer port, String observedAt, String provenance, String session,
                           String outcome) {}
    public record Comparison(String label, String leftEvidence, String rightEvidence,
                             List<String> fields, Outcome outcome, String reason) {}
    public record Result(String identity, List<Evidence> evidence, List<Comparison> comparisons,
                         NatEvidence natEvidence, String natReason, int excludedEnrs,
                         int malformedAttempts, boolean truncated) {
        public Map<String,Object> toMap() {
            var map = new LinkedHashMap<String,Object>();
            map.put("identity",identity); map.put("evidence",evidence); map.put("comparisons",comparisons);
            map.put("natEvidence",natEvidence.name()); map.put("natReason",natReason);
            map.put("excludedEnrs",excludedEnrs); map.put("malformedAttempts",malformedAttempts);
            map.put("truncated",truncated); map.put("maximumTimeGapSeconds",MAX_TIME_GAP.toSeconds());
            map.put("temporalScope","Recorded observations only; comparisons do not establish current reachability or simultaneous truth.");
            return map;
        }
    }

    public static Result fromStore(SqliteNodeStore store, NodeIdentity identity) {
        var saved = store.findEndpointEvidence(identity);
        return analyze(identity, store.findObservations(identity), store.findEnrEvidence(identity),
                saved.get("attempts"), saved.get("apis"), saved.get("retainedHellos"));
    }

    public static Result analyze(NodeIdentity identity, List<DiscoveryObservation> observations,
            List<EnrEvidence> enrs, List<Map<String,Object>> attempts,
            List<Map<String,Object>> apis, List<Map<String,Object>> retainedHellos) {
        var evidence = new ArrayList<Evidence>(); int excluded = 0, malformed = 0;
        for (var o : observations) {
            if (!identity.available() || !o.identity().equals(identity) || o.source().equals("ENR")) continue;
            for (var endpoint : o.endpoints()) add(evidence,o.source(),Strength.DISCOVERY_CLAIM,
                    endpoint,null,o.observedAt().toString(),o.provenance(),null,"ADVERTISED");
        }
        for (var e : enrs) {
            if (!identity.available() || !e.usable() || e.record() == null || !e.associatedIdentity().equals(identity)
                    || !identity.equals(e.record().identity())) { excluded++; continue; }
            for (var endpoint : e.record().endpoints()) add(evidence,"ENR",Strength.TRUSTED_ENR,
                    endpoint,null,e.observedAt().toString(),e.provenance(),null,"VALID_SIGNATURE_IDENTITY_MATCH");
        }
        for (var a : attempts) {
            try {
                var address = EndpointAddress.parseHostPort(Objects.toString(a.get("endpoint"),""));
                var endpoint = new NodeEndpoint(address.getHostString(),NodeEndpoint.Transport.TCP,address.getPort(),
                        EndpointAddress.family(address.getHostString()),NodeEndpoint.Purpose.P2P);
                if (!endpoint.addressFamily().name().equals(a.get("addressFamily")) || !"TCP".equals(a.get("transport"))
                        || ((Number)a.get("port")).intValue()!=endpoint.port()) throw new IllegalArgumentException("Inconsistent target");
                String at = timestamp(a.get("observedAt")), provenance = Objects.toString(a.get("provenance"),"");
                String session = endpoint.hostPort()+"@"+at;
                boolean tcp = passed(a,"P2P TCP"), auth = tcp && passed(a,"RLPx Auth");
                add(evidence,"Scanner",Strength.TCP_ATTEMPT,endpoint,null,at,provenance,session,reason(a));
                if (tcp) add(evidence,"TCP",Strength.TCP_REACHABLE,endpoint,null,at,provenance,session,"PASS");
                if (auth) add(evidence,"RLPx",Strength.RLPX_AUTH,endpoint,null,at,provenance,session,"PASS");
                if (auth && passed(a,"RLPx Hello") && a.get("p2p") instanceof Map<?,?> p
                        && p.get("hello") instanceof Map<?,?> h && h.get("listenPort") instanceof Number port
                        && port.longValue()>=0 && port.longValue()<=65535)
                    add(evidence,"Hello",Strength.HELLO_PORT,null,port.intValue(),at,provenance,session,"AUTHENTICATED_PORT_CLAIM");
            } catch (RuntimeException e) { malformed++; }
        }
        // Prior Hello fields can outlive the attempt envelope. Never attach them to a newer failed target.
        for (var hello : retainedHellos) {
            if (hello.get("listenPort") instanceof Number port && port.longValue()>=0 && port.longValue()<=65535)
                add(evidence,"Retained Hello",Strength.HELLO_PORT,null,port.intValue(),timestamp(hello.get("observedAt")),
                        "Persisted authenticated Hello; original session target/stages unavailable",null,"SESSION_ASSOCIATION_UNAVAILABLE");
        }
        for (var api : apis) {
            try {
                String source = Objects.toString(api.get("source"),"");
                if (!List.of("RPC","Beacon").contains(source)) throw new IllegalArgumentException("Unknown API source");
                URI uri = URI.create((String)api.get("endpoint"));
                if (!List.of("http","https").contains(uri.getScheme()) || uri.getUserInfo()!=null) throw new IllegalArgumentException("Invalid API authority");
                String host = uri.getHost(); if (host == null) throw new IllegalArgumentException("Missing address");
                if (host.startsWith("[")) host = host.substring(1,host.length()-1);
                var endpoint = new NodeEndpoint(host,NodeEndpoint.Transport.TCP,uri.getPort(),EndpointAddress.family(host),
                        source.equals("RPC")?NodeEndpoint.Purpose.RPC:NodeEndpoint.Purpose.BEACON);
                add(evidence,source,source.equals("RPC")?Strength.RPC_API:Strength.BEACON_API,endpoint,null,
                        timestamp(api.get("observedAt")),Objects.toString(api.get("provenance"),"Independent API probe"),null,"PASS");
            } catch (RuntimeException e) { malformed++; }
        }
        // Stable newest-first ordering bounds work without changing or deleting original evidence.
        evidence.sort(Comparator.comparing((Evidence e)->Objects.toString(e.observedAt(),"")).reversed()
                .thenComparing(e->e.source()+Objects.toString(e.endpoint(),"")+Objects.toString(e.port(),"")));
        boolean truncated = evidence.size()>MAX_EVIDENCE;
        if (truncated) evidence.subList(MAX_EVIDENCE,evidence.size()).clear();
        var comparisons = new ArrayList<Comparison>();
        var trusted = select(evidence,Strength.TRUSTED_ENR);
        for (String source : List.of("discv4","discv5"))
            groups(comparisons,source+" ↔ trusted ENR",evidence.stream().filter(e->e.source().equals(source)).toList(),trusted,false);
        var tcpClaims = evidence.stream().filter(e->(e.strength()==Strength.DISCOVERY_CLAIM || e.strength()==Strength.TRUSTED_ENR)
                && p2p(e.endpoint())).toList();
        var reachable = select(evidence,Strength.TCP_REACHABLE);
        groups(comparisons,"Discovery ↔ reachable TCP",tcpClaims.stream().filter(e->e.strength()==Strength.DISCOVERY_CLAIM).toList(),reachable,false);
        groups(comparisons,"ENR ↔ reachable TCP",trusted.stream().filter(e->p2p(e.endpoint())).toList(),reachable,false);
        groups(comparisons,"ENR ↔ authenticated RLPx",trusted.stream().filter(e->p2p(e.endpoint())).toList(),select(evidence,Strength.RLPX_AUTH),false);
        groups(comparisons,"Advertised TCP port ↔ reachable TCP port",tcpClaims,reachable,true);
        for (var h : select(evidence,Strength.HELLO_PORT)) {
            var session = select(evidence,Strength.RLPX_AUTH).stream().filter(e->h.session()!=null && h.session().equals(e.session())).findFirst();
            if (comparisons.size()>=MAX_COMPARISONS) { truncated=true; break; }
            comparisons.add(session.map(e->compare("Hello listenPort ↔ authenticated TCP port",h,e,true)).orElseGet(()->
                    new Comparison("Hello listenPort ↔ authenticated TCP port",h.id(),null,List.of("port"),Outcome.INSUFFICIENT_EVIDENCE,
                            "Original authenticated session endpoint is unavailable; retained Hello is not assigned to a newer attempt.")));
        }
        if (select(evidence,Strength.HELLO_PORT).isEmpty() && comparisons.size()<MAX_COMPARISONS)
            comparisons.add(new Comparison("Hello listenPort ↔ authenticated TCP port",null,null,List.of("port"),Outcome.NOT_OBSERVED,"No authenticated Hello listenPort observed."));
        if (reachable.isEmpty() && evidence.stream().anyMatch(e->e.strength()==Strength.TCP_ATTEMPT) && comparisons.size()<MAX_COMPARISONS)
            comparisons.add(new Comparison("TCP reachability",null,null,List.of("endpoint"),Outcome.INSUFFICIENT_EVIDENCE,"Only failed or uncompleted TCP attempts exist; failure does not identify a NAT cause."));
        if (comparisons.size()>=MAX_COMPARISONS) truncated=true;
        NatEvidence nat = NatEvidence.NAT_EVIDENCE_INSUFFICIENT;
        String reason = "Recorded evidence cannot determine NAT; endpoint disagreement alone is not proof of translation.";
        boolean translated = comparisons.stream().anyMatch(c->c.label().equals("ENR ↔ authenticated RLPx") && c.outcome()==Outcome.MISMATCH);
        boolean aligned = comparisons.stream().anyMatch(c->c.label().equals("ENR ↔ authenticated RLPx") && c.outcome()==Outcome.MATCH);
        boolean ambiguity = truncated || malformed>0 || comparisons.stream().anyMatch(c->c.outcome()==Outcome.INSUFFICIENT_EVIDENCE)
                || evidence.stream().anyMatch(e->e.strength()==Strength.TCP_ATTEMPT && !"PASS".equals(e.outcome()));
        if (translated && !ambiguity) {
            nat=NatEvidence.POSSIBLE_NAT_OR_ENDPOINT_TRANSLATION;
            reason="Trusted advertisement differs from an authenticated target within the comparison window. Translation is only one possibility; stale records, multi-homing, proxies, migration or configuration can also explain it. NAT is not proven.";
        } else if (aligned && !ambiguity && comparisons.stream().noneMatch(c->c.outcome()==Outcome.MISMATCH)) {
            nat=NatEvidence.NO_NAT_EVIDENCE; reason="Comparable recorded endpoints agree. Agreement does not rule out NAT, and no absence of NAT is proven.";
        }
        return new Result(identity.nodeId(),List.copyOf(evidence),List.copyOf(comparisons),nat,reason,excluded,malformed,truncated);
    }

    private static List<Evidence> select(List<Evidence> values, Strength strength) { return values.stream().filter(e->e.strength()==strength).toList(); }
    private static void add(List<Evidence> values,String source,Strength strength,NodeEndpoint endpoint,Integer port,String at,String provenance,String session,String outcome) {
        values.add(new Evidence("e"+values.size(),source,strength,endpoint,port,at,provenance,session,outcome));
    }
    private static String timestamp(Object value) { try { return Instant.parse(Objects.toString(value,"")).toString(); } catch(RuntimeException e) { return null; } }
    private static boolean p2p(NodeEndpoint e) { return e!=null && e.transport()==NodeEndpoint.Transport.TCP && e.purpose()==NodeEndpoint.Purpose.P2P; }
    private static boolean passed(Map<String,Object> attempt,String name) {
        if (!(attempt.get("diagnostics") instanceof List<?> list)) return false;
        return list.stream().anyMatch(o->o instanceof Map<?,?> d && name.equals(d.get("name")) && "PASS".equals(d.get("state")));
    }
    private static String reason(Map<String,Object> attempt) {
        if (passed(attempt,"P2P TCP")) return "PASS";
        if (attempt.get("diagnostics") instanceof List<?> list) for(var o:list)
            if(o instanceof Map<?,?> d && "P2P TCP".equals(d.get("name"))) return Objects.toString(d.get("reasonCode"),Objects.toString(d.get("state"),"NOT_TESTED"));
        return "NOT_TESTED";
    }
    private static void groups(List<Comparison> out,String label,List<Evidence> left,List<Evidence> right,boolean ports) {
        if(out.size()>=MAX_COMPARISONS) return;
        if(left.isEmpty() || right.isEmpty()) {
            out.add(new Comparison(label,left.isEmpty()?null:left.getFirst().id(),right.isEmpty()?null:right.getFirst().id(),
                    ports?List.of("port"):List.of("address","port"),Outcome.NOT_OBSERVED,"One or both compatible evidence sources have not been observed.")); return;
        }
        for(var a:left) for(var b:right) { if(out.size()>=MAX_COMPARISONS) return; out.add(compare(label,a,b,ports)); }
    }
    public static Comparison compare(String label,Evidence a,Evidence b,boolean ports) {
        boolean hello=a.strength()==Strength.HELLO_PORT;
        var fields=ports || hello?List.of("port"):List.of("address","port");
        Outcome result; String reason;
        if(hello && (a.session()==null || !a.session().equals(b.session()) || b.strength()!=Strength.RLPX_AUTH)) {
            result=Outcome.NOT_COMPARABLE; reason="Hello is a port-only claim associated with its own authenticated session.";
        } else if(!hello && (a.endpoint()==null || b.endpoint()==null || a.endpoint().addressFamily()!=b.endpoint().addressFamily()
                || a.endpoint().transport()!=b.endpoint().transport() || a.endpoint().purpose()!=b.endpoint().purpose()
                || ports && !a.endpoint().address().equals(b.endpoint().address()))) {
            result=Outcome.NOT_COMPARABLE; reason="Address families, transports, purposes or port-only address contexts differ; IPv4 and IPv6 are independent paths.";
        } else if(a.observedAt()==null || b.observedAt()==null || Duration.between(Instant.parse(a.observedAt()),Instant.parse(b.observedAt())).abs().compareTo(MAX_TIME_GAP)>0) {
            result=Outcome.INSUFFICIENT_EVIDENCE; reason="Observation timestamps are missing or more than five minutes apart; simultaneous endpoint truth is unknown.";
        } else if(hello && a.port()==0) {
            result=Outcome.NOT_COMPARABLE; reason="Hello listenPort zero is valid evidence without a positive listening-port claim.";
        } else if(!hello && (a.endpoint().port()==0 || b.endpoint().port()==0)) {
            result=Outcome.INSUFFICIENT_EVIDENCE; reason="A zero endpoint port does not provide a usable advertised port for comparison.";
        } else {
            boolean match=hello?a.port()==b.endpoint().port():a.endpoint().port()==b.endpoint().port() && (ports || a.endpoint().address().equals(b.endpoint().address()));
            result=match?Outcome.MATCH:Outcome.MISMATCH;
            reason=(hello?"Port only: Hello claims "+a.port()+", authenticated TCP port is "+b.endpoint().port():a.source()+" "+a.endpoint().hostPort()+" versus "+b.source()+" "+b.endpoint().hostPort())
                    +". Recorded within five minutes; disagreement does not establish NAT or simultaneous truth.";
        }
        return new Comparison(label,a.id(),b.id(),fields,result,reason);
    }
}
