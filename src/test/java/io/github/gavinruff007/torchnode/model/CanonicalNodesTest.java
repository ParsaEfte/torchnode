package io.github.gavinruff007.torchnode.model;
import org.junit.jupiter.api.Test;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CanonicalNodesTest {
 @TempDir Path temp;
 private DiscoveryObservation observation(String id,String source,int udp) {
  return new DiscoveryObservation(new NodeIdentity(id),source,List.of(
   new NodeEndpoint("192.0.2.1",NodeEndpoint.Transport.UDP,udp,NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.DISCOVERY),
   new NodeEndpoint("192.0.2.1",NodeEndpoint.Transport.TCP,30305,NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.P2P)),Instant.now(),source+" fixture");
 }
 @Test void canonicalCountDeduplicatesProvidersAndRepeatedEvidenceWithoutDiscardingSources() throws Exception {
  try(var store=new SqliteNodeStore(temp.resolve("counts.db").toString())) {
   var id=io.github.gavinruff007.torchnode.enr.EnrFixtures.ID.nodeId();var v4=observation(id,"discv4",30301);var v5=observation(id,"discv5",9000);
   store.saveObservation(v4);store.saveObservation(v5);store.saveObservation(observation(id,"discv5",9000));
   assertEquals(2,store.findAll().size());assertEquals(1,CanonicalNodes.views(store.findAll()).size());
   assertEquals("discv4",CanonicalNodes.views(store.findAll()).get(0).getDiscoverySource());
   assertEquals(Set.of("discv4","discv5"),store.findObservations(v4.identity()).stream().map(DiscoveryObservation::source).collect(java.util.stream.Collectors.toSet()));
   store.saveEnrEvidence(new io.github.gavinruff007.torchnode.enr.EnrDecoder().decode(
    io.github.gavinruff007.torchnode.enr.EnrFixtures.complete(7,30303),io.github.gavinruff007.torchnode.enr.EnrFixtures.ID,Instant.now(),"discv5 fixture"));
   store.saveEnrEvidence(new io.github.gavinruff007.torchnode.enr.EnrDecoder().decode(
    io.github.gavinruff007.torchnode.enr.EnrFixtures.complete(8,30304),io.github.gavinruff007.torchnode.enr.EnrFixtures.ID,Instant.now(),"discv4 fixture"));
   assertEquals(1,CanonicalNodes.views(store.findAll()).size());
   assertEquals(2,store.findEnrEvidence(v4.identity()).size());
   assertEquals(Set.of("discv4","discv5","ENR"),store.findObservations(v4.identity()).stream().map(DiscoveryObservation::source).collect(java.util.stream.Collectors.toSet()));
  }
 }
 @Test void differentIdentitiesAtOneIpCountSeparatelyAndApiCountersAreIndependent() {
  var v4=new NodeRecord(observation("ab".repeat(64),"discv4",30301));var v5=new NodeRecord(observation("ab".repeat(64),"discv5",9000));
  var other=new NodeRecord(observation("cd".repeat(64),"discv5",9000));v5.setRpcAvailable(true);other.setBeaconAvailable(true);
  var rows=List.of(v4,v5,other);assertEquals(2,CanonicalNodes.views(rows).size());
  assertEquals(1,CanonicalNodes.count(rows,NodeRecord::isRpcAvailable));assertEquals(1,CanonicalNodes.count(rows,NodeRecord::isBeaconAvailable));
 }
 @Test void sameEndpointDiscv5CannotOverwriteDiscv4TcpEvenWhenNewer() throws Exception {
  try(var store=new SqliteNodeStore(temp.resolve("priority.db").toString())) {
   var v4=observation("ab".repeat(64),"discv4",30301);store.saveObservation(v4);
   var v5=new DiscoveryObservation(v4.identity(),"discv5",List.of(v4.endpoints().get(0)),v4.observedAt().plusSeconds(10),"ENR UDP only");
   store.saveObservation(v5);var node=store.findAll().get(0);
   assertEquals(v5.observedAt().getEpochSecond(),node.getLastSeen().getEpochSecond());assertEquals(30305,node.getP2pEndpoint().port());assertEquals("discv4",node.getDiscoverySource());assertEquals(2,store.findObservations(v4.identity()).size());
  }
 }
}
