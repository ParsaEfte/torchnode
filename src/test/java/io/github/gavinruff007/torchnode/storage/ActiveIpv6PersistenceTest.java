package io.github.gavinruff007.torchnode.storage;
import io.github.gavinruff007.torchnode.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
class ActiveIpv6PersistenceTest {
 @TempDir Path temp;
 static DiscoveryObservation observation(String id,String source,String ip) {
  var family=EndpointAddress.family(ip);
  return new DiscoveryObservation(new NodeIdentity(id),source,List.of(
   new NodeEndpoint(ip,NodeEndpoint.Transport.UDP,30301,family,NodeEndpoint.Purpose.DISCOVERY),
   new NodeEndpoint(ip,NodeEndpoint.Transport.TCP,30305,family,NodeEndpoint.Purpose.P2P)),Instant.parse("2026-09-29T00:00:00Z"),source+" at "+EndpointAddress.hostPort(ip,30301));
 }
 @Test void dualStackIdentityAndSourceRoundTripReopenAndIntegrity() throws Exception {
  String path=temp.resolve("dual.db").toString(), a="ab".repeat(64), b="cd".repeat(64);
  var v4=observation(a,"discv4","192.0.2.1");var v6=observation(a,"discv5","2001:db8::10");
  try(var store=new SqliteNodeStore(path)) {
   store.saveObservation(v4);assertEquals(1,CanonicalNodes.views(store.findAll()).size());
   store.saveObservation(v6);store.saveObservation(v6);
   assertEquals(1,CanonicalNodes.views(store.findAll()).size());
   store.saveObservation(observation(a,"discv4","2001:db8::10"));
   store.saveObservation(observation(b,"discv5","2001:db8::10"));
   store.saveObservation(observation(b,"discv4","192.0.2.1"));
   assertEquals(2,CanonicalNodes.views(store.findAll()).size());
  }
  try(var store=new SqliteNodeStore(path);var connection=DriverManager.getConnection("jdbc:sqlite:"+path);var stmt=connection.createStatement()) {
   assertEquals(2,CanonicalNodes.views(store.findAll()).size());
   var observations=store.findObservations(new NodeIdentity(a));assertEquals(3,observations.size());
   assertTrue(observations.contains(v4));assertTrue(observations.contains(v6));
   assertEquals(2,observations.stream().flatMap(o->o.endpoints().stream()).map(NodeEndpoint::addressFamily).distinct().count());
   try(var rs=stmt.executeQuery("PRAGMA integrity_check")){assertTrue(rs.next());assertEquals("ok",rs.getString(1));}
   try(var rs=stmt.executeQuery("PRAGMA foreign_key_check")){assertFalse(rs.next());}
  }
 }
 @Test void failedIpv6AttemptDoesNotErasePreviouslyAuthenticatedEvidence() throws Exception {
  String path=temp.resolve("attempts.db").toString();
  var node=new NodeRecord(observation("ab".repeat(64),"discv4","192.0.2.1"));
  try(var store=new SqliteNodeStore(path)) {
   store.save(node);store.saveP2pObservation(node.getKey(),"{\"clientId\":\"prior authenticated peer\"}","{\"networkId\":1}");
   store.saveEndpointInspection(node.getKey(),null,null,List.of(Map.of("endpoint","[2001:db8::1]:30303","addressFamily","IPV6","state","FAILED")));
  }
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var stmt=c.createStatement();var rs=stmt.executeQuery("SELECT hello_json,status_json FROM p2p_observations")) {
   assertTrue(rs.next());var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(rs.getString(1));
   assertEquals("prior authenticated peer",json.path("clientId").asText());assertTrue(json.has("_helloObservedAt"));
   assertEquals("FAILED",json.at("/_endpointAttempts/0/state").asText());assertEquals("{\"networkId\":1}",rs.getString(2));
  }
 }
}
