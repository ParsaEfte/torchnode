package io.github.gavinruff007.torchnode.inspection;

import io.github.gavinruff007.torchnode.enr.*;
import io.github.gavinruff007.torchnode.model.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EnrInspectionTest {
    private EnrEvidence evidence(long seq, int tcp) throws Exception {
        return new EnrDecoder().decode(EnrFixtures.complete(seq,tcp),EnrFixtures.ID,Instant.ofEpochSecond(seq),"fixture");
    }
    @Test void latestValidatedSequenceWinsWithoutReplacingDiscoveryHelloOrStatusEvidence() throws Exception {
        var node = new NodeRecord("127.0.0.1",30305,30305,EnrFixtures.ID.nodeId());
        var result = new InspectionResult("test",node);
        result.loadEnrEvidence(List.of(evidence(2,30303),evidence(1,30301)));
        result.setEnrEvidence(EnrEvidence.unavailable(node.identity(),"fixture","REQUEST_TIMEOUT","timeout"));
        result.setP2p(Map.of("hello",Map.of("listenPort",0),"status",Map.of("forkHash","0x01020304","forkNext",1234)));
        var snapshot = result.snapshot();
        var enr = (Map<?,?>)snapshot.get("enr");
        assertEquals("2",((EnrRecord)enr.get("record")).sequence());
        assertEquals("REQUEST_TIMEOUT",((Map<?,?>)snapshot.get("enrAttempt")).get("outcome"));
        assertEquals(30305,node.getP2pEndpoint().port());
        var comparisons=(Map<?,?>)snapshot.get("enrComparisons");
        assertEquals("MISMATCH",comparisons.get("TCP vs discovery"));
        assertEquals("MATCH",comparisons.get("UDP vs discovery"));
        assertEquals("MATCH",comparisons.get("Fork hash vs ETH Status"));
        assertEquals("MATCH",comparisons.get("Fork next vs ETH Status"));
        assertFalse(comparisons.containsValue("VERIFIED"));
        result.setP2p(Map.of("status",Map.of("forkHash","0x05060708","forkNext",1235)));
        comparisons=(Map<?,?>)result.snapshot().get("enrComparisons");
        assertEquals("MISMATCH",comparisons.get("Fork hash vs ETH Status"));
    }
    @Test void invalidHighSequenceCannotSupersedeValidLowerSequenceAndSameSequenceConflictIsExplicit() throws Exception {
        var result = new InspectionResult("test",new NodeRecord("127.0.0.1",30305,30305,EnrFixtures.ID.nodeId()));
        result.loadEnrEvidence(List.of(evidence(1,30303),evidence(1,30304)));
        byte[] invalid = EnrFixtures.complete(100,30301); invalid[5]^=1;
        result.setEnrEvidence(new EnrDecoder().decode(invalid,EnrFixtures.ID,Instant.now(),"fixture invalid"));
        var snapshot=result.snapshot();
        assertEquals("1",((EnrRecord)((Map<?,?>)snapshot.get("enr")).get("record")).sequence());
        assertEquals("CONFLICTING_RECORDS",((Map<?,?>)snapshot.get("enrComparisons")).get("Sequence"));
    }
}
