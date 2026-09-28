package io.github.gavinruff007.torchnode.discovery;
import io.github.gavinruff007.torchnode.model.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
class CompositeDiscoveryProviderTest {
 static class Fixture implements DiscoveryProvider {
  final boolean fail;volatile boolean closed;Fixture(boolean fail){this.fail=fail;}
  public String protocol(){return fail?"discv5":"discv4";}
  public void start(){if(fail)throw new IllegalStateException("helper crashed");}
  public void discover(Consumer<DiscoveryObservation> observer){if(!closed)observer.accept(new DiscoveryObservation(new NodeIdentity("ab".repeat(64)),protocol(),List.of(new NodeEndpoint("127.0.0.1",NodeEndpoint.Transport.UDP,30301,NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.DISCOVERY)),Instant.now(),"fixture"));}
  public void close(){closed=true;}
 }
 @Test void failedProviderDoesNotStopSiblingAndRepeatedLifecyclesLeaveNoWorkers() throws Exception {
  for(int cycle=0;cycle<2;cycle++) {
   var healthy=new Fixture(false);var failed=new Fixture(true);var composite=new CompositeDiscoveryProvider(List.of(healthy,failed));composite.start();
   long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);List<DiscoveryObservation> received=List.of();
   while(received.isEmpty()&&System.nanoTime()<until){received=composite.discover();Thread.sleep(10);}
   assertFalse(received.isEmpty());assertEquals("discv4",received.get(0).source());composite.close();assertTrue(healthy.closed);assertTrue(failed.closed);
   assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(t->t.isAlive()&&t.getName().equals("discovery-discv5")));
  }
 }
 @Test void saturatedQueueCanBeStoppedWithoutLeakingThreads() throws Exception {
  DiscoveryProvider flood=new Fixture(false){public void discover(Consumer<DiscoveryObservation> observer){for(int i=0;i<3000;i++)super.discover(observer);}};
  var composite=new CompositeDiscoveryProvider(List.of(flood));composite.start();Thread.sleep(100);
  long start=System.nanoTime();composite.close();assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(2));
  assertTrue(composite.discover().size()<=1024);
 }
}
