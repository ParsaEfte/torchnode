package io.github.gavinruff007.torchnode.enr;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Four workers, bounded queue, and a small ten-minute identity/UDP endpoint cache. */
public final class EnrAcquirer implements AutoCloseable {
    private static final Semaphore NETWORK_SLOTS = new Semaphore(4, true);
    private static final long CACHE_NANOS = Duration.ofMinutes(10).toNanos();
    private final Discv4EnrClient client;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), task -> { Thread t = new Thread(task, "enr-acquisition"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private record Cached(long started, io.github.gavinruff007.torchnode.model.NodeIdentity identity, CompletableFuture<EnrEvidence> result) {}
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private boolean closed;
    public EnrAcquirer() { this(new Discv4EnrClient()); }
    public EnrAcquirer(Discv4EnrClient client) { this.client = client; }
    public CompletableFuture<EnrEvidence> acquire(NodeRecord node) { return acquire(node, null); }
    /** The optional consumer runs once for a newly scheduled request, never on repeated cached observations. */
    public synchronized CompletableFuture<EnrEvidence> acquire(NodeRecord node, java.util.function.Consumer<EnrEvidence> onFirstResult) {
        String key = node.getKey();
        if (closed) return CompletableFuture.completedFuture(unavailable(node, "CANCELLED"));
        long now = System.nanoTime();
        Cached existing = cache.get(key);
        if (existing != null && (!existing.result().isDone() || now - existing.started() < CACHE_NANOS)) return existing.result();
        CompletableFuture<EnrEvidence> future = new CompletableFuture<>();
        if (onFirstResult != null) future.thenAccept(onFirstResult);
        future.thenAccept(evidence -> {
            if (evidence.outcome().equals("BUSY")) synchronized (this) {
                Cached cached = cache.get(key);
                if (cached != null && cached.result() == future) cache.remove(key);
            }
        });
        try {
            executor.execute(() -> {
                boolean slot = false;
                try {
                    slot = NETWORK_SLOTS.tryAcquire(1, TimeUnit.SECONDS);
                    future.complete(slot ? client.fetch(node, future::isDone) : unavailable(node, "BUSY"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); future.complete(unavailable(node, "CANCELLED"));
                } catch (RuntimeException e) {
                    future.complete(EnrEvidence.unavailable(node.identity(), "discv4 ENR acquisition", "ACQUISITION_ERROR", e.getMessage()));
                } finally { if (slot) NETWORK_SLOTS.release(); }
            });
            cache.put(key, new Cached(now, node.identity(), future));
            if (cache.size() > 4096) {
                var entries = cache.entrySet().iterator();
                while (entries.hasNext()) if (entries.next().getValue().result().isDone()) { entries.remove(); break; }
            }
        } catch (RejectedExecutionException e) { future.complete(unavailable(node, "BUSY")); }
        return future;
    }
    private static EnrEvidence unavailable(NodeRecord node, String outcome) {
        return EnrEvidence.unavailable(node.identity(), "discv4 ENR acquisition", outcome,
                outcome.equals("BUSY") ? "Bounded ENR acquisition capacity reached" : "ENR acquisition cancelled");
    }
    public synchronized void clear() {
        client.cancelPending(); executor.getQueue().clear();
        cache.values().forEach(c -> c.result().complete(EnrEvidence.unavailable(
                c.identity(), "discv4 ENR acquisition", "CANCELLED", "ENR acquisition cancelled")));
        cache.clear();
    }
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        client.close(); executor.shutdownNow();
        boolean interrupted = false;
        try {
            while (!executor.isTerminated()) {
                try { executor.awaitTermination(100, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            synchronized (this) {
                cache.values().forEach(c -> c.result().complete(EnrEvidence.unavailable(
                        c.identity(), "discv4 ENR acquisition", "CANCELLED", "ENR acquisition cancelled")));
                cache.clear();
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
