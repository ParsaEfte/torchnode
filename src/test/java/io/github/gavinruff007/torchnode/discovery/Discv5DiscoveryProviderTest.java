package io.github.gavinruff007.torchnode.discovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gavinruff007.torchnode.enr.*;
import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class Discv5DiscoveryProviderTest {
 @TempDir Path temp;
 private Discv5DiscoveryProvider provider() throws Exception {return new Discv5DiscoveryProvider(new LocalNodeIdentity(),temp.resolve("missing-helper"),List.of("unused"));}
 private com.fasterxml.jackson.databind.JsonNode event(byte[] raw,String id,String time) {
  return new ObjectMapper().valueToTree(Map.of("type","node","rlp",HexFormat.of().formatHex(raw),"nodeId",id,"at",time,"authenticated",false,"provenance","discv5 NODES fixture"));
 }
 @Test void rawEnrIsRevalidatedAndProvenanceSurvivesPersistenceWithBothFamilies() throws Exception {
  var provider=provider();String at="2026-09-28T00:00:00.123456789Z";byte[] raw=EnrFixtures.complete(7,30303);
  provider.accept(event(raw,EnrFixtures.ID.nodeId(),at));var observations=provider.discover();assertEquals(1,observations.size());
  var observation=observations.get(0);assertEquals("discv5",observation.source());assertEquals(Instant.parse(at),observation.observedAt());
  assertEquals(Set.of(NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.AddressFamily.IPV6), observation.endpoints().stream().map(NodeEndpoint::addressFamily).collect(java.util.stream.Collectors.toSet()));assertEquals(30303,new NodeRecord(observation).getP2pEndpoint().port());
  assertTrue(observation.provenance().contains("acquired via DISCV5"));
  List<EnrEvidence> evidence=new ArrayList<>();provider.drainEnrEvidence(evidence::add);var enr=evidence.get(0);
  assertTrue(enr.usable());assertEquals(HexFormat.of().formatHex(raw),enr.rawRlpHex());assertNotNull(enr.record().fields().ip6());
  try(var store=new SqliteNodeStore(temp.resolve("roundtrip.db").toString())) {store.saveObservation(observation);store.saveEnrEvidence(enr);}
  try(var store=new SqliteNodeStore(temp.resolve("roundtrip.db").toString())) {
   assertEquals(observation,store.findObservations(EnrFixtures.ID).stream().filter(o->o.source().equals("discv5")).findFirst().orElseThrow());
   assertEquals(enr,store.findEnrEvidence(EnrFixtures.ID).get(0));store.clearCollectedData();assertEquals(0,store.count());assertTrue(store.findObservations(EnrFixtures.ID).isEmpty());
  }
 }
 @Test void helperCannotOverrideInvalidSignatureOrIdentityMismatch() throws Exception {
  var provider=provider();byte[] invalid=EnrFixtures.complete(7,30303);invalid[5]^=1;
  provider.accept(event(invalid,EnrFixtures.ID.nodeId(),Instant.now().toString()));
  provider.accept(event(EnrFixtures.complete(8,30303),"ab".repeat(64),Instant.now().toString()));
  assertTrue(provider.discover().isEmpty());List<EnrEvidence> evidence=new ArrayList<>();provider.drainEnrEvidence(evidence::add);
  assertEquals(2,evidence.size());assertTrue(evidence.stream().noneMatch(EnrEvidence::usable));
  assertTrue(evidence.stream().anyMatch(e->e.outcome().equals("INVALID_SIGNATURE")));assertTrue(evidence.stream().anyMatch(e->e.outcome().equals("IDENTITY_MISMATCH")));
 }
 @Test void udpOnlyRecordDoesNotInventP2pPortAndMalformedHelperInputIsBounded() throws Exception {
  var fields=EnrFixtures.fields();fields.put("ip",org.web3j.rlp.RlpString.create(new byte[]{127,0,0,1}));fields.put("udp",org.web3j.rlp.RlpString.create(9000));
  var provider=provider();provider.accept(event(EnrFixtures.signed(1,fields),EnrFixtures.ID.nodeId(),Instant.now().toString()));
  assertEquals(0,new NodeRecord(provider.discover().get(0)).getP2pEndpoint().port());
  for(int i=0;i<100;i++)provider.accept(event(new byte[301],EnrFixtures.ID.nodeId(),"bad timestamp"));
  assertTrue(provider.diagnostics().size()<=64);assertTrue(provider.discover().isEmpty());
 }
 @Test void readyIdentityMustMatchSharedJavaIdentityAndMissingHelperIsExplicit() throws Exception {
  var identity=new LocalNodeIdentity();var provider=new Discv5DiscoveryProvider(identity,temp.resolve("missing"),List.of("unused"));var json=new ObjectMapper();
  provider.accept(json.valueToTree(Map.of("type","ready","nodeId",identity.getNodeId())));
  assertThrows(IllegalStateException.class,()->provider.accept(json.valueToTree(Map.of("type","ready","nodeId",EnrFixtures.ID.nodeId()))));
  assertThrows(java.io.IOException.class,provider::start);provider.close();
 }
 @Test void inspectionLoadsProviderEnrWithoutSendingDiscv4AcquisitionToV5Endpoint() throws Exception {
  var provider=provider();provider.accept(event(EnrFixtures.complete(9,0),EnrFixtures.ID.nodeId(),Instant.now().toString()));
  var observation=provider.discover().get(0);var node=new NodeRecord(observation);String path=temp.resolve("inspection.db").toString();
  try(var store=new SqliteNodeStore(path)){store.saveObservation(observation);provider.drainEnrEvidence(e->{try{store.saveEnrEvidence(e);}catch(Exception failure){throw new IllegalStateException(failure);}});}
  try(var inspection=new io.github.gavinruff007.torchnode.inspection.InspectionService(path)) {
   String id=inspection.inspect(node);long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
   Map<String,Object> result=inspection.snapshot(id).orElseThrow();
   while(!Boolean.TRUE.equals(result.get("complete"))&&System.nanoTime()<deadline){Thread.sleep(10);result=inspection.snapshot(id).orElseThrow();}
   assertEquals(true,result.get("complete"));var enr=(Map<?,?>)result.get("enr");assertEquals("VALID",enr.get("signature"));
   assertEquals("MATCH",enr.get("identityComparison"));assertFalse(((List<?>)result.get("discv5")).isEmpty());
   assertNull(result.get("client"));
  }
 }
}
