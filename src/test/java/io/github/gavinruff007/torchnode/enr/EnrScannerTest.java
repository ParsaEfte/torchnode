package io.github.gavinruff007.torchnode.enr;

import io.github.gavinruff007.torchnode.daemon.ScanDaemon;
import io.github.gavinruff007.torchnode.discovery.DiscoveryProvider;
import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class EnrScannerTest {
    @TempDir Path temp;
    @Test void scannerPreservesDiscoveryAndSurvivesHostileEnrWithoutChangingTcp() throws Exception {
        String path=temp.resolve("scanner.db").toString();
        try(var peer=new LoopbackEnrPeer(LoopbackEnrPeer.Mode.BAD_SIGNATURE)) {
            var node=peer.node();
            var observation=new DiscoveryObservation(node.identity(),"discv4",List.of(
                    new NodeEndpoint(node.getIp(),NodeEndpoint.Transport.UDP,node.getUdpPort(),NodeEndpoint.AddressFamily.IPV4,NodeEndpoint.Purpose.DISCOVERY),
                    node.getP2pEndpoint()),Instant.now(),"loopback discovery fixture");
            AtomicBoolean emitted=new AtomicBoolean();
            DiscoveryProvider provider=new DiscoveryProvider() {
                public String protocol(){return "discv4";}
                public void start(){}
                public void discover(Consumer<DiscoveryObservation> consumer){if(emitted.compareAndSet(false,true))consumer.accept(observation);}
                public void close(){}
            };
            var scanner=new ScanDaemon(path);scanner.start(provider);
            try {
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
                List<EnrEvidence> evidence=List.of();
                while(System.nanoTime()<deadline) {
                    try(var store=new SqliteNodeStore(path)){evidence=store.findEnrEvidence(node.identity());}
                    if(!evidence.isEmpty())break;Thread.sleep(20);
                }
                assertFalse(evidence.isEmpty());assertEquals("INVALID_SIGNATURE",evidence.get(0).outcome());
                assertTrue(scanner.isRunning());
                try(var store=new SqliteNodeStore(path)) {
                    assertEquals(1,store.count());assertEquals(1,store.findObservations(node.identity()).size());
                    assertEquals(30305,store.findByKey(node.getKey()).orElseThrow().getTcpPort());
                }
            } finally {scanner.stop();scanner.awaitStopped();}
            assertFalse(scanner.isRunning());
        }
    }
}
