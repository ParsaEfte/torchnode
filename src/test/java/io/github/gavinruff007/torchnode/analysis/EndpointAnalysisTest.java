package io.github.gavinruff007.torchnode.analysis;

import io.github.gavinruff007.torchnode.enr.*;
import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.web3j.rlp.RlpString;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static io.github.gavinruff007.torchnode.analysis.EndpointAnalysis.*;
import static org.junit.jupiter.api.Assertions.*;

class EndpointAnalysisTest {
    @TempDir Path temp;
    static final NodeIdentity ID=EnrFixtures.ID;
    static final Instant AT=Instant.parse("2026-09-29T00:00:00Z");
    static NodeEndpoint endpoint(String ip,NodeEndpoint.Transport transport,int port,NodeEndpoint.Purpose purpose) {
        return new NodeEndpoint(ip,transport,port,EndpointAddress.family(ip),purpose);
    }
    static NodeEndpoint tcp(String ip,int port) { return endpoint(ip,NodeEndpoint.Transport.TCP,port,NodeEndpoint.Purpose.P2P); }
    static DiscoveryObservation discovery(String source,String ip,int port,Instant at) {
        return new DiscoveryObservation(ID,source,List.of(tcp(ip,port),endpoint(ip,NodeEndpoint.Transport.UDP,30301,NodeEndpoint.Purpose.DISCOVERY)),at,"fixture "+source+" "+ip);
    }
    static EnrEvidence enr(String ip,int port,Instant at) throws Exception {
        var fields=EnrFixtures.fields();String key=EndpointAddress.family(ip)==NodeEndpoint.AddressFamily.IPV6?"ip6":"ip";
        fields.put(key,RlpString.create(InetAddress.getByName(ip).getAddress()));
        fields.put(key.equals("ip6")?"tcp6":"tcp",RlpString.create(port));
        fields.put(key.equals("ip6")?"udp6":"udp",RlpString.create(30301));
        return new EnrDecoder().decode(EnrFixtures.signed(1,fields),ID,at,"signed fixture");
    }
    static Map<String,Object> attempt(String ip,int port,Instant at,boolean tcp,boolean auth,Integer helloPort) {
        var value=new LinkedHashMap<String,Object>();var e=tcp(ip,port);
        value.put("endpoint",e.hostPort());value.put("addressFamily",e.addressFamily().name());value.put("transport","TCP");value.put("port",port);
        value.put("observedAt",at.toString());value.put("provenance",List.of("fixture attempt"));
        value.put("diagnostics",List.of(Map.of("name","P2P TCP","state",tcp?"PASS":"FAILED","reasonCode",tcp?"TCP_CONNECTED":"TCP_NO_ROUTE_TO_HOST"),
                Map.of("name","RLPx Auth","state",auth?"PASS":"NOT_TESTED"),Map.of("name","RLPx Hello","state",auth && helloPort!=null?"PASS":"NOT_TESTED")));
        if(helloPort!=null)value.put("p2p",Map.of("hello",Map.of("listenPort",helloPort,"clientId","fixture")));
        return value;
    }
    static Result analyze(List<DiscoveryObservation> observations,List<EnrEvidence> enrs,List<Map<String,Object>> attempts) {
        return EndpointAnalysis.analyze(ID,observations,enrs,attempts,List.of(),List.of());
    }
    static boolean has(Result result,String label,Outcome outcome) { return result.comparisons().stream().anyMatch(c->c.label().equals(label) && c.outcome()==outcome); }

    @Test void caseAAgreementAndCaseBTrustedAddressDisagreement() throws Exception {
        var claims=List.of(discovery("discv4","1.2.3.4",30303,AT),discovery("discv5","1.2.3.4",30303,AT));
        var attempts=List.of(attempt("1.2.3.4",30303,AT,true,true,30303));
        var agreed=analyze(claims,List.of(enr("1.2.3.4",30303,AT)),attempts);
        assertEquals(NatEvidence.NO_NAT_EVIDENCE,agreed.natEvidence());
        assertTrue(agreed.comparisons().stream().allMatch(c->c.outcome()==Outcome.MATCH || c.outcome()==Outcome.NOT_COMPARABLE));
        var different=analyze(claims,List.of(enr("5.6.7.8",30303,AT)),attempts);
        assertTrue(has(different,"ENR ↔ authenticated RLPx",Outcome.MISMATCH));
        assertEquals(NatEvidence.POSSIBLE_NAT_OR_ENDPOINT_TRANSLATION,different.natEvidence());
        assertTrue(different.natReason().contains("NAT is not proven"));
    }
    @Test void caseCHelloPortOnlyMismatchZeroAndPrerequisites() throws Exception {
        var mismatch=analyze(List.of(),List.of(enr("1.2.3.4",30303,AT)),List.of(attempt("1.2.3.4",30303,AT,true,true,30304)));
        assertTrue(has(mismatch,"Hello listenPort ↔ authenticated TCP port",Outcome.MISMATCH));
        assertEquals(NatEvidence.NAT_EVIDENCE_INSUFFICIENT,mismatch.natEvidence());
        var hello=mismatch.evidence().stream().filter(e->e.strength()==Strength.HELLO_PORT).findFirst().orElseThrow();assertNull(hello.endpoint());
        var c=mismatch.comparisons().stream().filter(x->x.label().startsWith("Hello")).findFirst().orElseThrow();assertEquals(List.of("port"),c.fields());
        assertTrue(has(analyze(List.of(),List.of(),List.of(attempt("1.2.3.4",30303,AT,true,true,0))),"Hello listenPort ↔ authenticated TCP port",Outcome.NOT_COMPARABLE));
        var unauthenticated=analyze(List.of(),List.of(),List.of(attempt("1.2.3.4",30303,AT,true,false,30303)));
        assertTrue(unauthenticated.evidence().stream().noneMatch(e->e.strength()==Strength.RLPX_AUTH || e.strength()==Strength.HELLO_PORT));
    }
    @Test void casesDEFamilyPathsAndIndependentFailure() throws Exception {
        var enrs=List.of(enr("1.2.3.4",30303,AT),enr("2001:db8::1",30303,AT));
        var both=analyze(List.of(),enrs,List.of(attempt("1.2.3.4",30303,AT,true,true,30303),attempt("2001:db8::1",30303,AT,true,true,30303)));
        assertTrue(both.comparisons().stream().noneMatch(c->c.outcome()==Outcome.MISMATCH));
        assertTrue(has(both,"ENR ↔ authenticated RLPx",Outcome.NOT_COMPARABLE));
        assertTrue(has(both,"ENR ↔ authenticated RLPx",Outcome.MATCH));
        var oneFailed=analyze(List.of(),enrs,List.of(attempt("1.2.3.4",30303,AT,true,true,30303),attempt("2001:db8::1",30303,AT,false,false,null)));
        assertTrue(has(oneFailed,"ENR ↔ authenticated RLPx",Outcome.MATCH));
        assertEquals(NatEvidence.NAT_EVIDENCE_INSUFFICIENT,oneFailed.natEvidence());
        assertTrue(oneFailed.evidence().stream().anyMatch(e->e.outcome().equals("TCP_NO_ROUTE_TO_HOST")));
    }
    @Test void caseFRealInvalidSignatureAndIdentityMismatchExcluded() throws Exception {
        var valid=enr("2001:db8::1",30303,AT);byte[] bytes=org.web3j.utils.Numeric.hexStringToByteArray(valid.rawRlpHex());bytes[8]^=1;
        var invalid=new EnrDecoder().decode(bytes,ID,AT,"tampered");assertFalse(invalid.usable());
        var wrong=new EnrDecoder().decode(org.web3j.utils.Numeric.hexStringToByteArray(valid.rawRlpHex()),new NodeIdentity("ab".repeat(64)),AT,"wrong identity");assertFalse(wrong.usable());
        var r=analyze(List.of(valid.observation().orElseThrow()),List.of(invalid,wrong),List.of(attempt("1.2.3.4",30303,AT,true,true,30303)));
        assertEquals(2,r.excludedEnrs());assertTrue(r.evidence().stream().noneMatch(e->e.strength()==Strength.TRUSTED_ENR));
        assertEquals(NatEvidence.NAT_EVIDENCE_INSUFFICIENT,r.natEvidence());
    }
    @Test void caseGTimeGapMissingTimestampAndNoObservation() throws Exception {
        var r=analyze(List.of(discovery("discv4","1.2.3.4",30303,AT)),List.of(enr("5.6.7.8",30303,AT.plusSeconds(301))),List.of(attempt("1.2.3.4",30303,AT,true,true,30303)));
        assertTrue(has(r,"ENR ↔ authenticated RLPx",Outcome.INSUFFICIENT_EVIDENCE));assertEquals(NatEvidence.NAT_EVIDENCE_INSUFFICIENT,r.natEvidence());
        var missing=new LinkedHashMap<>(attempt("1.2.3.4",30303,AT,true,true,30303));missing.remove("observedAt");
        assertTrue(has(analyze(List.of(),List.of(enr("1.2.3.4",30303,AT)),List.of(missing)),"ENR ↔ authenticated RLPx",Outcome.INSUFFICIENT_EVIDENCE));
        var empty=analyze(List.of(),List.of(),List.of());assertTrue(empty.comparisons().stream().allMatch(c->c.outcome()==Outcome.NOT_OBSERVED));
    }
    @Test void incompatibleProtocolsPurposesAndHelloSessions() {
        var udp=new Evidence("a","discv4",Strength.DISCOVERY_CLAIM,endpoint("1.2.3.4",NodeEndpoint.Transport.UDP,30303,NodeEndpoint.Purpose.DISCOVERY),null,AT.toString(),"fixture",null,"ADVERTISED");
        var tcp=new Evidence("b","RLPx",Strength.RLPX_AUTH,tcp("1.2.3.4",30303),null,AT.toString(),"fixture","session","PASS");
        assertEquals(Outcome.NOT_COMPARABLE,compare("protocols",udp,tcp,false).outcome());
        var api=new Evidence("c","RPC",Strength.RPC_API,endpoint("1.2.3.4",NodeEndpoint.Transport.TCP,30303,NodeEndpoint.Purpose.RPC),null,AT.toString(),"fixture",null,"PASS");
        assertEquals(Outcome.NOT_COMPARABLE,compare("purposes",api,tcp,false).outcome());
        var hello=new Evidence("h","Hello",Strength.HELLO_PORT,null,30303,AT.toString(),"fixture","different session","CLAIM");
        assertEquals(Outcome.NOT_COMPARABLE,compare("Hello",hello,tcp,true).outcome());
        assertEquals(List.of("port"),compare("Hello",hello,tcp,false).fields());
    }
    @Test void casesHICanonicalIdentityAndReopenDerivedEquality() throws Exception {
        String path=temp.resolve("analysis.db").toString();var v4=discovery("discv4","1.2.3.4",30303,AT);var v6=discovery("discv5","2001:db8::1",30303,AT);
        Result before;
        try(var store=new SqliteNodeStore(path)) {
            store.saveObservation(v4);store.saveObservation(v6);store.saveObservation(v6);store.saveEnrEvidence(enr("1.2.3.4",30303,AT));
            assertEquals(1,CanonicalNodes.views(store.findAll()).size());
            var other=new NodeIdentity("ab".repeat(64));store.saveObservation(new DiscoveryObservation(other,"discv4",v4.endpoints(),AT,"other identity"));
            assertEquals(2,CanonicalNodes.views(store.findAll()).size());
            var api=Map.<String,Object>of("source","RPC","endpoint","http://[2001:db8::1]:8545","observedAt",AT.toString(),"provenance","independent fixture");
            store.saveEndpointInspection(new NodeRecord(v4).getKey(),Map.of("clientId","fixture","listenPort",30303),Map.of("networkId",1),List.of(attempt("1.2.3.4",30303,AT,true,true,30303)),List.of(api));
            before=EndpointAnalysis.fromStore(store,ID);assertTrue(before.evidence().stream().anyMatch(e->e.strength()==Strength.RPC_API));
            assertTrue(before.evidence().stream().noneMatch(e->e.provenance().equals("other identity")));
        }
        try(var store=new SqliteNodeStore(path)) {
            assertEquals(before,EndpointAnalysis.fromStore(store,ID));
            store.saveEndpointInspection(new NodeRecord(v4).getKey(),null,null,List.of(attempt("2001:db8::1",30303,AT.plusSeconds(30),false,false,null)));
            var later=EndpointAnalysis.fromStore(store,ID);assertTrue(later.evidence().stream().anyMatch(e->e.source().equals("Retained Hello")));
            assertTrue(has(later,"Hello listenPort ↔ authenticated TCP port",Outcome.INSUFFICIENT_EVIDENCE));
            try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+path);var s=c.createStatement()) {
                try(var rows=s.executeQuery("select hello_json,status_json from p2p_observations")){assertTrue(rows.next());assertTrue(rows.getString(1).contains("fixture"));assertTrue(rows.getString(2).contains("networkId"));}
                try(var rows=s.executeQuery("pragma integrity_check")){assertTrue(rows.next());assertEquals("ok",rows.getString(1));}
                try(var rows=s.executeQuery("pragma foreign_key_check")){assertFalse(rows.next());}
            }
        }
    }
    @Test void comparisonBoundsMalformedInputAndUnspecifiedIdentityAreConservative() throws Exception {
        var many=new ArrayList<DiscoveryObservation>();for(int i=0;i<600;i++)many.add(discovery("discv4","1.2.3.4",30303,AT.plusSeconds(i)));
        var r=analyze(many,List.of(enr("1.2.3.4",30303,AT)),List.of(attempt("1.2.3.4",30303,AT,true,true,30303)));
        assertTrue(r.truncated());assertTrue(r.evidence().size()<=MAX_EVIDENCE);assertTrue(r.comparisons().size()<=MAX_COMPARISONS);assertEquals(NatEvidence.NAT_EVIDENCE_INSUFFICIENT,r.natEvidence());
        assertEquals(1,analyze(List.of(),List.of(),List.of(Map.of("endpoint","bad"))).malformedAttempts());
        var noId=EndpointAnalysis.analyze(new NodeIdentity(""),List.of(discovery("discv4","1.2.3.4",30303,AT)),List.of(enr("1.2.3.4",30303,AT)),List.of(),List.of(),List.of());assertTrue(noId.evidence().isEmpty());
    }
}
