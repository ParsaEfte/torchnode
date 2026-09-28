package io.github.gavinruff007.torchnode.discovery;

import io.github.gavinruff007.torchnode.model.DiscoveryObservation;
import io.github.gavinruff007.torchnode.enr.EnrEvidence;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Independent provider workers; a slow or failed provider cannot block its sibling. */
public final class CompositeDiscoveryProvider implements DiscoveryProvider {
    private final List<DiscoveryProvider> providers;
    private final ArrayBlockingQueue<DiscoveryObservation> observations = new ArrayBlockingQueue<>(1024);
    private final ArrayBlockingQueue<EnrEvidence> enrs = new ArrayBlockingQueue<>(1024);
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;
    public CompositeDiscoveryProvider(List<DiscoveryProvider> providers) {
        if (providers.isEmpty() || providers.size() > 2) throw new IllegalArgumentException("Require 1..2 providers");
        this.providers = List.copyOf(providers);
    }
    @Override public String protocol() { return "discv4+discv5"; }
    @Override public synchronized void start() {
        if (running) return; running = true;
        for (var provider : providers) {
            Thread worker = new Thread(() -> {
                try {
                    provider.start();
                    while (running) {
                        provider.discover(this::enqueue); provider.drainEnrEvidence(this::enqueueEnr);
                        Thread.sleep(provider.protocol().equals("discv4") ? 10000 : 100);
                    }
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception e) { System.err.println("[Discovery] " + provider.protocol() + " unavailable: " + e.getMessage()); }
                finally { provider.close(); }
            }, "discovery-" + provider.protocol());
            worker.setDaemon(true); workers.add(worker); worker.start();
        }
    }
    private void enqueue(DiscoveryObservation value) { put(observations, value); }
    private void enqueueEnr(EnrEvidence value) { put(enrs, value); }
    private <T> void put(BlockingQueue<T> queue, T value) {
        while (running) try { if (queue.offer(value, 100, TimeUnit.MILLISECONDS)) return; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        throw new CancellationException();
    }
    @Override public void discover(Consumer<DiscoveryObservation> observer) {
        DiscoveryObservation value; while ((value = observations.peek()) != null) { observer.accept(value); observations.remove(); }
        if (!running) for (var provider : providers) {
            try { provider.discover(observer); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
    @Override public void drainEnrEvidence(Consumer<EnrEvidence> observer) {
        EnrEvidence value; while ((value = enrs.peek()) != null) { observer.accept(value); enrs.remove(); }
        if (!running) for (var provider : providers) provider.drainEnrEvidence(observer);
    }
    @Override public void close() {
        running = false; workers.forEach(Thread::interrupt); providers.forEach(DiscoveryProvider::close);
        boolean interrupted = false;
        for (Thread worker : workers) while (worker.isAlive()) try { worker.join(100); } catch (InterruptedException e) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
