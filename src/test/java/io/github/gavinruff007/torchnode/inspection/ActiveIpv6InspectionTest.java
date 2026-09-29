package io.github.gavinruff007.torchnode.inspection;
import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.storage.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ActiveIpv6InspectionTest {
 @TempDir Path temp;
 @ParameterizedTest @ValueSource(booleans={true,false})
 void activeTcpAttemptsBothFamiliesAndPersistsIndependentResults(boolean ipv6Reachable) throws Exception {
  String path=temp.resolve("inspection.db").toString();
  try(var peer=new ServerSocket(0,5,InetAddress.getByName(ipv6Reachable ? "::1" : "127.0.0.1"))) {
   var acceptor=new Thread(()->{try{while(!peer.isClosed()){peer.accept().close();}}catch(Exception ignored){}},"ipv6-tcp-fixture");acceptor.start();
   var identity=new NodeIdentity("ab".repeat(64));
   var observation=new DiscoveryObservation(identity,"discv5",List.of(
    new NodeEndpoint("127.0.0.1",NodeEndpoint.Transport.UDP,30301,NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.DISCOVERY),
    new NodeEndpoint("127.0.0.1",NodeEndpoint.Transport.TCP,ipv6Reachable ? 1 : peer.getLocalPort(),NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.P2P),
    new NodeEndpoint("::1",NodeEndpoint.Transport.TCP,ipv6Reachable ? peer.getLocalPort() : 1,NodeEndpoint.AddressFamily.IPV6,NodeEndpoint.Purpose.P2P)),Instant.now(),"dual-stack fixture");
   try(var store=new SqliteNodeStore(path)){store.saveObservation(observation);}
   try(var service=new InspectionService(path)) {
    String id=service.inspect(new NodeRecord(observation));var snapshot=service.snapshot(id).orElseThrow();
    long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(12);
    while(!Boolean.TRUE.equals(snapshot.get("complete"))&&System.nanoTime()<deadline){Thread.sleep(20);snapshot=service.snapshot(id).orElseThrow();}
    assertEquals(true,snapshot.get("complete"));
    var attempts=(List<Map<String,Object>>)snapshot.get("endpointAttempts");assertEquals(2,attempts.size());
    assertEquals("IPV4",attempts.get(0).get("addressFamily"));assertEquals("IPV6",attempts.get(1).get("addressFamily"));
    assertTrue(attempts.get(1).get("endpoint").toString().startsWith("["));
    var stages=(List<Map<String,Object>>)attempts.get(ipv6Reachable ? 1 : 0).get("diagnostics");assertEquals("PASS",stages.get(0).get("state"));
    var failed=(List<Map<String,Object>>)attempts.get(ipv6Reachable ? 0 : 1).get("diagnostics");assertEquals("FAILED",failed.get(0).get("state"));
    var summary=(List<Map<String,Object>>)snapshot.get("diagnostics");assertEquals("PASS",summary.get(0).get("state"));
    assertNotEquals("PASS",stages.get(1).get("state"));
    try(var c=DriverManager.getConnection("jdbc:sqlite:"+path);var stmt=c.createStatement();var rows=stmt.executeQuery("SELECT hello_json FROM p2p_observations")) {
     assertTrue(rows.next());assertTrue(rows.getString(1).contains("_endpointAttempts"));assertTrue(rows.getString(1).contains("IPV6"));assertFalse(rows.next());
    }
   } finally {peer.close();acceptor.join(1000);assertFalse(acceptor.isAlive());}
  }
 }
}
