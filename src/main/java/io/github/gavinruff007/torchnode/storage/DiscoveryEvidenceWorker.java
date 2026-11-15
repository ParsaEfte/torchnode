package io.github.gavinruff007.torchnode.storage;

import io.github.gavinruff007.torchnode.discovery.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** One polling writer, no ingestion queue. Capture rings and retry fingerprints are bounded. */
public final class DiscoveryEvidenceWorker implements AutoCloseable {
    private final String path;private final DiscoveryProvider provider;
    private final LinkedHashMap<String,Boolean> committed=new LinkedHashMap<>();
    private final Map<String,DiscoveryEvidenceBatch> failureHealth=new LinkedHashMap<>();
    private final AtomicLong failures=new AtomicLong(),commits=new AtomicLong(),deferred=new AtomicLong();
    private volatile boolean running=true;private final Thread thread;
    public record Status(boolean running,long committedBatches,long failedBatches,long deferredBatches) {}
    public DiscoveryEvidenceWorker(String path,DiscoveryProvider provider){this.path=path;this.provider=provider;thread=new Thread(this::run,"discovery-evidence-writer");thread.setDaemon(true);thread.start();}
    private void pass(DiscoveryEvidenceStore store) {
        long passDeadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        int[] attempted={0};
        provider.evidenceBatches(batch->{
            if(attempted[0]++>=32 || System.nanoTime()-passDeadline>=0){deferred.incrementAndGet();return;}
            if(Thread.currentThread().isInterrupted())return;
            String key=batch.fingerprint();if(committed.containsKey(key))return;
            try {
                store.ingest(batch);commits.incrementAndGet();
                if(committed.size()==512)committed.remove(committed.keySet().iterator().next());committed.put(key,true);
            }catch(SQLException|RuntimeException e){
                long failed=failures.incrementAndGet();
                // No raw payloads or exception messages are logged or copied into failure telemetry.
                if(failureHealth.size()<2 || failureHealth.containsKey(batch.streamId()))failureHealth.put(batch.streamId(),new DiscoveryEvidenceBatch(batch.run(),batch.protocol(),batch.streamId(),List.of(),new DiscoveryEvidenceBatch.Health(Map.of("WRITER_BATCH_FAILURES",failed),0,0,0,null)));
                if(failed==1)System.err.println("[Discovery evidence] Batch rejected or unavailable; bounded retries active");
            }
        });
        for(var iterator=failureHealth.values().iterator();iterator.hasNext() && System.nanoTime()-passDeadline<0;)try{store.ingest(iterator.next());iterator.remove();}catch(SQLException ignored){}
    }
    private void run(){
        while(running) {
            try(var store=new DiscoveryEvidenceStore(path)) {
                try {while(running){try{pass(store);}catch(RuntimeException e){failures.incrementAndGet();}Thread.sleep(1000);}}
                catch(InterruptedException e){Thread.currentThread().interrupt();}
                finally {Thread.interrupted();try{pass(store);}catch(RuntimeException e){failures.incrementAndGet();}}
                return;
            }catch(SQLException e){failures.incrementAndGet();try{Thread.sleep(1000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}}
        }
    }
    public Status status(){return new Status(thread.isAlive()&&running,commits.get(),failures.get(),deferred.get());}
    @Override public void close(){running=false;thread.interrupt();boolean interrupted=false;while(thread.isAlive())try{thread.join(1000);}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
}
