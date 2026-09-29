package io.github.gavinruff007.torchnode.enrichment;

import io.github.gavinruff007.torchnode.model.EndpointAddress;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import java.util.*;
import java.util.concurrent.*;
import static io.github.gavinruff007.torchnode.enrichment.NetworkEnrichment.*;

/** One owned local worker, bounded pending work/LRU, persisted dataset-aware address deduplication. */
public final class NetworkEnrichmentService implements AutoCloseable {
    private final String databasePath;
    private final NetworkEnrichmentProvider provider;
    private volatile Thread worker;
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(128),
            task->{var thread=new Thread(task,"network-enrichment");thread.setDaemon(true);worker=thread;return thread;},new ThreadPoolExecutor.AbortPolicy());
    private final Map<String,CompletableFuture<NetworkEnrichment>> cache=new LinkedHashMap<>(16,.75f,true);
    private boolean closed;
    private long generation;
    private long lookups,reuses,rejections;
    public NetworkEnrichmentService(String path) { this(path,OfflineGeoIpProvider.configured()); }
    public NetworkEnrichmentService(String path,NetworkEnrichmentProvider provider){databasePath=path;this.provider=provider;}
    public synchronized CompletableFuture<NetworkEnrichment> request(String literal) {
        String address=EndpointAddress.parse(literal).getHostAddress();
        if(closed)return CompletableFuture.failedFuture(new CancellationException("Enrichment stopped"));
        var existing=cache.get(address);if(existing!=null){reuses++;return existing;}
        var future=new CompletableFuture<NetworkEnrichment>();
        try {
            long epoch=generation;executor.execute(()->run(address,future,epoch));cache.put(address,future);
            if(cache.size()>1024){var it=cache.entrySet().iterator();while(it.hasNext())if(it.next().getValue().isDone()){it.remove();break;}}
        } catch(RejectedExecutionException e){rejections++;future.completeExceptionally(new RejectedExecutionException("Bounded enrichment queue full"));}
        return future;
    }
    // Store construction can migrate/backfill rows: it too must precede the clear/stop barrier.
    private synchronized SqliteNodeStore openStore(long epoch,CompletableFuture<NetworkEnrichment> future) throws java.sql.SQLException {
        if(closed || generation!=epoch || future.isCancelled())return null;
        return new SqliteNodeStore(databasePath);
    }
    private void run(String address,CompletableFuture<NetworkEnrichment> future,long epoch) {
        try(var store=openStore(epoch,future)) {
            if(store==null)return;
            String key=provider.datasetKey();var saved=store.findNetworkEnrichment(address,key);
            if(saved.isPresent()){synchronized(this){if(closed || generation!=epoch)return;reuses++;}future.complete(saved.get());return;}
            String reason=PublicAddress.exclusion(address);NetworkEnrichment value;
            if(reason!=null)value=NetworkEnrichment.state(address,key,Status.NOT_APPLICABLE,reason);
            else try {
                synchronized(this){if(closed || generation!=epoch || future.isCancelled())return;lookups++;}
                value=provider.lookup(address);
                if(!value.address().equals(address) || !value.datasetKey().equals(key))throw new IllegalArgumentException("Provider returned different address/version");
            } catch(Exception e){value=NetworkEnrichment.state(address,key,Status.LOOKUP_FAILED,"Offline provider failed: "+e.getClass().getSimpleName());}
            synchronized(this){if(closed || generation!=epoch || future.isCancelled())return;store.saveNetworkEnrichment(value);}
            // Completion can run consumer callbacks; never hold our lock while calling consumers.
            future.complete(value);
        } catch(Exception e){synchronized(this){if(closed || generation!=epoch)return;}future.complete(NetworkEnrichment.state(address,provider.datasetKey(),Status.LOOKUP_FAILED,"Enrichment storage unavailable"));}
    }
    public synchronized Map<String,Object> metrics(){return Map.of("lookups",lookups,"reuses",reuses,"rejections",rejections,"cacheSize",cache.size(),"queued",executor.getQueue().size(),"active",executor.getActiveCount(),"workers",executor.getPoolSize(),"terminated",executor.isTerminated());}
    public synchronized void clear() { generation++;cache.values().forEach(f->f.cancel(true));cache.clear();executor.getQueue().clear();if(worker!=null && executor.getActiveCount()>0)worker.interrupt(); }
    @Override public void close() {
        boolean first;
        synchronized(this){first=!closed;closed=true;generation++;cache.values().forEach(f->f.cancel(true));}
        if(first){executor.shutdownNow();try{provider.close();}catch(Exception ignored){}}
        boolean interrupted=false;
        while(!executor.isTerminated())try{executor.awaitTermination(100,TimeUnit.MILLISECONDS);}catch(InterruptedException e){interrupted=true;}
        synchronized(this){cache.clear();}if(interrupted)Thread.currentThread().interrupt();
    }
}
