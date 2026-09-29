package io.github.gavinruff007.torchnode.model;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class EndpointAddressTest {
 @Test void literalFormattingParsingAndFamilies() {
  assertEquals("203.0.113.10:30303", EndpointAddress.hostPort("203.0.113.10",30303));
  assertEquals("[2001:db8::10]:30303", EndpointAddress.hostPort("2001:db8::10",30303));
  assertEquals("http://[2001:db8::10]:8545",EndpointAddress.http("2001:db8::10",8545));
  assertEquals("http://203.0.113.10:8545",EndpointAddress.http("203.0.113.10",8545));
  assertEquals(EndpointAddress.parse("2001:db8::10"),EndpointAddress.parseHostPort("[2001:db8:0:0:0:0:0:10]:30303").getAddress());
  assertEquals(NodeEndpoint.AddressFamily.IPV6,EndpointAddress.family("::ffff:192.0.2.1"));
  assertEquals(NodeEndpoint.AddressFamily.IPV4,EndpointAddress.family("192.0.2.1"));
  assertEquals(EndpointAddress.parse("::ffff:192.0.2.1"),EndpointAddress.parse("0:0:0:0:0:ffff:c000:201"));
 }
 @Test void rejectsInvalidOrDnsOrAmbiguousEndpoints() {
  for(String ip : new String[]{"example.org","999.1.1.1","2001:::1","[::1]","::1%en0","", "1234"})
   assertThrows(IllegalArgumentException.class,()->EndpointAddress.parse(ip),ip);
  for(String ep : new String[]{"2001:db8::10:30303","[::1]","[::1]:70000","localhost:30303","127.0.0.1:30303/path"})
   assertThrows(IllegalArgumentException.class,()->EndpointAddress.parseHostPort(ep),ep);
  assertThrows(IllegalArgumentException.class,()->new NodeEndpoint("::1",NodeEndpoint.Transport.TCP,1,NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.P2P));
 }
}
