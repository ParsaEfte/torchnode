package io.github.gavinruff007.torchnode.inspection;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalLifecycleTest {
    @TempDir Path temp;

    private static void finish(InspectionService service,InspectionResult result,long generation) throws Exception {
        Method method=InspectionService.class.getDeclaredMethod("finish",InspectionResult.class,Throwable.class,long.class);
        method.setAccessible(true);method.invoke(service,result,null,generation);
    }
    private static Object persistenceLock(InspectionService service) throws Exception {
        var field=InspectionService.class.getDeclaredField("persistenceLock");field.setAccessible(true);return field.get(service);
    }

    @Test void clearWinsAgainstStalePendingHistoryWriteAndRestartPersists() throws Exception {
        String path=temp.resolve("clear-race.db").toString();var node=new NodeRecord("192.0.2.1",30303,30303,"ab".repeat(64));
        try(var store=new SqliteNodeStore(path)){store.save(node);}
        try(var service=new InspectionService(path)) {
            var pending=new InspectionResult("old-run",node);
            var ready=new CountDownLatch(1);var done=new CountDownLatch(1);
            var error=new AtomicReference<Throwable>();
            Object lock=persistenceLock(service);
            synchronized(lock) {
                var thread=new Thread(()->{
                    ready.countDown();
                    try{finish(service,pending,0);}catch(Throwable e){error.set(e);}finally{done.countDown();}
                },"blocked-history-write");
                thread.start();assertTrue(ready.await(5,TimeUnit.SECONDS));
                try(var store=new SqliteNodeStore(path)){service.clearCollectedData(store);}
            }
            assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(error.get());
            try(var store=new SqliteNodeStore(path)){
                assertTrue(store.inspectionHistory(node.identity(),10,null).isEmpty());
                assertEquals(0,store.count());
            }
        }
        try(var service=new InspectionService(path);var store=new SqliteNodeStore(path)) {
            store.save(node);
            var fresh=new InspectionResult("new-run",node);
            for(var name:java.util.List.of("P2P TCP","RLPx Auth","RLPx Hello","ETH Status","JSON-RPC","Beacon API","ENR"))
                fresh.diagnostic(name,InspectionResult.State.NOT_TESTED,null,"Synthetic lifecycle fixture","Test");
            finish(service,fresh,0);
            assertEquals("new-run",store.inspectionHistory(node.identity(),10,null).get(0).id());
        }
    }

    @Test void closePreventsPendingGenerationFromOpeningStore() throws Exception {
        String path=temp.resolve("close-race.db").toString();var node=new NodeRecord("192.0.2.1",30303,30303,"ab".repeat(64));
        try(var store=new SqliteNodeStore(path)){store.save(node);}
        var service=new InspectionService(path);
        try {
            var pending=new InspectionResult("stale-after-close",node);
            var ready=new CountDownLatch(1);var done=new CountDownLatch(1);
            var error=new AtomicReference<Throwable>();
            synchronized(persistenceLock(service)){
                new Thread(()->{ready.countDown();try{finish(service,pending,0);}catch(Throwable e){error.set(e);}finally{done.countDown();}},
                        "pending-history-at-close").start();
                assertTrue(ready.await(5,TimeUnit.SECONDS));service.close();
            }
            assertTrue(done.await(5,TimeUnit.SECONDS));assertNull(error.get());
            try(var store=new SqliteNodeStore(path)){assertTrue(store.inspectionHistory(node.identity(),10,null).isEmpty());}
        }finally{service.close();}
    }
}
