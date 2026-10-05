package io.github.gavinruff007.torchnode.enrichment;

import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkEnrichmentLifecycleTest {
    @TempDir Path temp;
    static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(5,TimeUnit.SECONDS)); }
    static class LateProvider implements NetworkEnrichmentProvider {
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),returned=new CountDownLatch(1);
        final AtomicInteger calls=new AtomicInteger();
        public String datasetKey(){return "fixture-v1";}
        public NetworkEnrichment lookup(String address) {
            int call=calls.incrementAndGet();
            if(call==1){entered.countDown();boolean interrupted=false;for(;;)try{release.await();break;}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
            returned.countDown();return state(address,datasetKey(),Status.FOUND,"fixture result "+call);
        }
    }
    @Test void queuedCancellationLateStopAndCallbackSuppression() throws Exception {
        String path=temp.resolve("stop.db").toString();var provider=new LateProvider();var service=new NetworkEnrichmentService(path,provider);
        var active=service.request("1.2.3.4");await(provider.entered);var queued=service.request("5.6.7.8");var callbacks=new AtomicInteger();active.thenAccept(v->callbacks.incrementAndGet());
        var stopper=Executors.newSingleThreadExecutor();
        try {
            var stopped=stopper.submit(service::close);
            // Each future is cancelled separately; the active cancellation does not
            // establish that close has already reached the queued future.
            assertThrows(CancellationException.class,()->active.get(5,TimeUnit.SECONDS));
            assertThrows(CancellationException.class,()->queued.get(5,TimeUnit.SECONDS));
            assertTrue(queued.isCancelled());provider.release.countDown();stopped.get(5,TimeUnit.SECONDS);
            assertEquals(1,provider.calls.get());assertEquals(0,callbacks.get());assertEquals(0,service.metrics().get("cacheSize"));
            assertEquals(0,service.metrics().get("queued"));assertEquals(0,service.metrics().get("active"));assertEquals(0,service.metrics().get("workers"));assertEquals(true,service.metrics().get("terminated"));
            var counters=service.metrics();service.close();assertEquals(counters,service.metrics());
            try(var store=new SqliteNodeStore(path)){assertTrue(store.findNetworkEnrichment("1.2.3.4",null).isEmpty());assertTrue(store.findNetworkEnrichment("5.6.7.8",null).isEmpty());}
        } finally {provider.release.countDown();service.close();stopper.shutdownNow();}
    }
    @Test void clearRejectsOldGenerationAndNewGenerationPersistsNormally() throws Exception {
        String path=temp.resolve("clear.db").toString();var provider=new LateProvider();
        try(var service=new NetworkEnrichmentService(path,provider)) {
            var old=service.request("1.2.3.4");await(provider.entered);var queued=service.request("5.6.7.8");var callbacks=new AtomicInteger();old.thenAccept(v->callbacks.incrementAndGet());
            service.clear();try(var store=new SqliteNodeStore(path)){store.clearCollectedData();}
            assertTrue(old.isCancelled());assertTrue(queued.isCancelled());assertEquals(0,service.metrics().get("cacheSize"));
            var fresh=service.request("1.2.3.4");provider.release.countDown();var result=fresh.get(5,TimeUnit.SECONDS);
            assertEquals("fixture result 2",result.country().reason());assertEquals(0,callbacks.get());assertEquals(2,provider.calls.get());assertEquals(2L,service.metrics().get("lookups"));
            try(var store=new SqliteNodeStore(path)){assertEquals(result,store.findNetworkEnrichment("1.2.3.4",null).orElseThrow());assertTrue(store.findNetworkEnrichment("5.6.7.8",null).isEmpty());}
        }
        var restarted=new LateProvider();restarted.release.countDown();try(var service=new NetworkEnrichmentService(path,restarted)){assertEquals("fixture result 2",service.request("1.2.3.4").get(5,TimeUnit.SECONDS).country().reason());assertEquals(0,restarted.calls.get());assertEquals(1L,service.metrics().get("reuses"));}
    }
    @Test void clearWithoutNewRequestsCannotResurrectDatabaseOrCache() throws Exception {
        String path=temp.resolve("empty.db").toString();var provider=new LateProvider();var service=new NetworkEnrichmentService(path,provider);
        var old=service.request("1.2.3.4");await(provider.entered);service.clear();try(var store=new SqliteNodeStore(path)){store.clearCollectedData();}
        provider.release.countDown();await(provider.returned);service.close();assertTrue(old.isCancelled());assertEquals(1L,service.metrics().get("lookups"));assertEquals(0,service.metrics().get("cacheSize"));
        try(var store=new SqliteNodeStore(path)){assertTrue(store.findNetworkEnrichment("1.2.3.4",null).isEmpty());assertEquals(0,store.count());}
    }
    @Test void alreadyExecutingCompletionIsDrainedBeforeStopReturns() throws Exception {
        String path=temp.resolve("callback.db").toString();var provider=new LateProvider();var service=new NetworkEnrichmentService(path,provider);
        var value=service.request("1.2.3.4");await(provider.entered);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        value.thenAccept(v->{entered.countDown();boolean interrupted=false;for(;;)try{release.await();break;}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();});
        provider.release.countDown();await(entered);var executor=Executors.newSingleThreadExecutor();
        try {var stopped=executor.submit(service::close);assertEquals(1,service.metrics().get("active"));release.countDown();stopped.get(5,TimeUnit.SECONDS);assertEquals(0,service.metrics().get("active"));assertEquals(true,service.metrics().get("terminated"));}
        finally{release.countDown();service.close();executor.shutdownNow();}
    }
    @Test void actualInspectionCallbackWaitingAtClearCannotPublishOldGeneration() throws Exception {
        String path=temp.resolve("inspection-clear.db").toString();var provider=new LateProvider();
        var id=new io.github.gavinruff007.torchnode.model.NodeIdentity("ab".repeat(64));
        var observation=new io.github.gavinruff007.torchnode.model.DiscoveryObservation(id,"discv5",java.util.List.of(new io.github.gavinruff007.torchnode.model.NodeEndpoint("1.2.3.4",io.github.gavinruff007.torchnode.model.NodeEndpoint.Transport.TCP,30303,io.github.gavinruff007.torchnode.model.NodeEndpoint.AddressFamily.IPV4,io.github.gavinruff007.torchnode.model.NodeEndpoint.Purpose.P2P)),java.time.Instant.now(),"callback fixture");
        var endpoints=new java.util.ArrayList<>(observation.endpoints());endpoints.add(new io.github.gavinruff007.torchnode.model.NodeEndpoint("1.2.3.4",io.github.gavinruff007.torchnode.model.NodeEndpoint.Transport.UDP,30303,io.github.gavinruff007.torchnode.model.NodeEndpoint.AddressFamily.IPV4,io.github.gavinruff007.torchnode.model.NodeEndpoint.Purpose.DISCOVERY));
        observation=new io.github.gavinruff007.torchnode.model.DiscoveryObservation(id,"discv5",endpoints,observation.observedAt(),observation.provenance());
        var node=new io.github.gavinruff007.torchnode.model.NodeRecord(observation);
        try(var store=new SqliteNodeStore(path)){store.saveObservation(observation);}
        try(var inspection=new io.github.gavinruff007.torchnode.inspection.InspectionService(path,provider)) {
            var result=new io.github.gavinruff007.torchnode.inspection.InspectionResult("fixture",node);
            var finish=inspection.getClass().getDeclaredMethod("finish",result.getClass(),Throwable.class,long.class);finish.setAccessible(true);finish.invoke(inspection,result,null,0L);await(provider.entered);
            var enrichmentField=inspection.getClass().getDeclaredField("enrichment");enrichmentField.setAccessible(true);var service=(NetworkEnrichmentService)enrichmentField.get(inspection);
            var lockField=inspection.getClass().getDeclaredField("persistenceLock");lockField.setAccessible(true);var lock=lockField.get(inspection);
            var saved=result.snapshot().get("networkEnrichment");
            var completion=new CountDownLatch(1);service.request("1.2.3.4").thenAccept(v->completion.countDown());
            synchronized(lock) {
                provider.release.countDown();await(completion);
                // The real finish callback is pending on persistenceLock, with its old generation.
                try(var store=new SqliteNodeStore(path)){inspection.clearCollectedData(store);assertEquals(0,store.count());assertTrue(store.findNetworkEnrichment("1.2.3.4",null).isEmpty());}
            }
            service.close();assertEquals(saved,result.snapshot().get("networkEnrichment"));
            try(var store=new SqliteNodeStore(path)){assertEquals(0,store.count());assertTrue(store.findNetworkEnrichment("1.2.3.4",null).isEmpty());}
        } finally {provider.release.countDown();}
    }

    @Test void queueCapacityRejectsExcessAndClearCancelsEveryQueuedLookup() throws Exception {
        String path=temp.resolve("queue.db").toString();var provider=new LateProvider();var service=new NetworkEnrichmentService(path,provider);
        try {
            service.request("1.2.3.4");await(provider.entered);var queued=new java.util.ArrayList<CompletableFuture<NetworkEnrichment>>();
            for(int i=0;i<128;i++)queued.add(service.request("11.0."+i+".1"));
            var excess=service.request("12.0.0.1");assertThrows(ExecutionException.class,()->excess.get(5,TimeUnit.SECONDS));assertEquals(128,service.metrics().get("queued"));assertEquals(1L,service.metrics().get("rejections"));
            service.clear();assertTrue(queued.stream().allMatch(CompletableFuture::isCancelled));assertEquals(0,service.metrics().get("queued"));provider.release.countDown();service.close();assertEquals(1,provider.calls.get());assertEquals(0,service.metrics().get("cacheSize"));assertEquals(0,service.metrics().get("active"));
        } finally {provider.release.countDown();service.close();}
    }

}
