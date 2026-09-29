package io.github.gavinruff007.torchnode.daemon;

import io.github.gavinruff007.torchnode.discovery.DiscoveryProvider;
import io.github.gavinruff007.torchnode.enr.EnrAcquirer;
import io.github.gavinruff007.torchnode.inspection.NodeInspector;
import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.storage.NodeStore;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;

import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

public class ScanDaemon {
    private static final int INSPECT_BATCH_SIZE = 50;
    private static final int INSPECT_TIMEOUT_SECONDS = 10;
    private NodeInspector nodeInspector;
    private final String databasePath;


    private volatile boolean running = false;
    private Thread scanThread;
    private Thread inspectionThread;
    private final Set<String> inspectedNodes = ConcurrentHashMap.newKeySet();
    private DiscoveryProvider provider;
    private EnrAcquirer enrAcquirer;
    private io.github.gavinruff007.torchnode.enrichment.NetworkEnrichmentService enrichment;

    public ScanDaemon(String databasePath) {
        this.databasePath = databasePath;
    }

    public void start(DiscoveryProvider provider) throws SQLException {
        if (running) {
            System.out.println("[ScanDaemon] Already running");
            return;
        }

        NodeStore nodeStore = new SqliteNodeStore(databasePath);
        this.provider = provider;
        this.nodeInspector = new NodeInspector();
        this.enrAcquirer = new EnrAcquirer();
        this.enrichment = new io.github.gavinruff007.torchnode.enrichment.NetworkEnrichmentService(databasePath);
        running = true;

        scanThread = new Thread(() -> {
            try {
                System.out.println("[ScanDaemon] Starting discovery...");

                provider.start();

                while (running) {
                    provider.discover(observation -> {
                        nodeStore.saveObservation(observation);
                        observation.endpoints().forEach(endpoint->enrichment.request(endpoint.address()));
                        if (observation.source().equals("discv4")) enrAcquirer.acquire(new NodeRecord(observation), evidence -> {
                            if (evidence.outcome().equals("BUSY")) return;
                            try (SqliteNodeStore enrStore = new SqliteNodeStore(databasePath)) { enrStore.saveEnrEvidence(evidence); if(evidence.usable())evidence.record().endpoints().forEach(endpoint->enrichment.request(endpoint.address())); }
                            catch (Exception e) { System.err.println("[ENR] Evidence persistence failed: " + e.getMessage()); }
                        });
                    });
                    provider.drainEnrEvidence(evidence -> {
                        try { ((SqliteNodeStore)nodeStore).saveEnrEvidence(evidence); if(evidence.usable())evidence.record().endpoints().forEach(endpoint->enrichment.request(endpoint.address())); }
                        catch (SQLException e) { throw new IllegalStateException("Cannot persist provider ENR", e); }
                    });
                    if (!running) break;
                    if (inspectionThread == null || !inspectionThread.isAlive()) {
                        inspectionThread = new Thread(() -> {
                            try (NodeStore inspectionStore = new SqliteNodeStore(databasePath)) { inspectNewNodes(inspectionStore); }
                            catch (Exception e) { System.err.println("[Inspect] " + e.getMessage()); }
                        }, "scanner-api-inspection");
                        inspectionThread.setDaemon(true); inspectionThread.start();
                    }
                    Thread.sleep(1000);
                }

            } catch (InterruptedException e) {
                System.out.println("[ScanDaemon] Interrupted");
            } catch (Exception e) {
                System.err.println("[ScanDaemon] Error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                running = false;
                provider.close();
                nodeInspector.close();
                enrichment.close();
                enrAcquirer.close();
                boolean interrupted = false;
                if (inspectionThread != null) {
                    inspectionThread.interrupt();
                    while (inspectionThread.isAlive()) try { inspectionThread.join(100); }
                    catch (InterruptedException e) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
                try {
                    provider.discover(nodeStore::saveObservation);
                    provider.drainEnrEvidence(evidence -> {
                        try { ((SqliteNodeStore)nodeStore).saveEnrEvidence(evidence); }
                        catch (SQLException e) { throw new IllegalStateException(e); }
                    });
                } catch (Exception e) {
                    System.err.println("[ScanDaemon] Failed to persist final discovery evidence: " + e.getMessage());
                } finally { nodeStore.close(); }
            }
        });

        scanThread.setDaemon(true);
        scanThread.start();
        System.out.println("[ScanDaemon] Started");
    }

    private void inspectNewNodes(NodeStore nodeStore) {
        try {
            List<NodeRecord> allNodes = nodeStore.findAll();
            List<NodeRecord> toInspect = new ArrayList<>();

            for (NodeRecord node : allNodes) {
                String key = node.getKey() + ":tcp=" + node.getP2pEndpoint().port();
                if (!inspectedNodes.contains(key)) {
                    toInspect.add(node);
                    if (toInspect.size() >= INSPECT_BATCH_SIZE) {
                        break;
                    }
                }
            }

            if (toInspect.isEmpty()) {
                System.out.println("[Inspect] No new nodes to inspect");
                return;
            }

            System.out.println("[Inspect] Inspecting " + toInspect.size() + " nodes");

            ExecutorService executor = Executors.newFixedThreadPool(10);
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (NodeRecord node : toInspect) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    String key = node.getKey() + ":tcp=" + node.getP2pEndpoint().port();
                    try {
                        NodeRecord inspected = nodeInspector.inspect(node, System.nanoTime() + TimeUnit.SECONDS.toNanos(INSPECT_TIMEOUT_SECONDS));
                        nodeStore.update(inspected);
                        inspectedNodes.add(key);
                        System.out.println("[Inspect] Success: " + key);

                    } catch (Exception e) {
                        System.err.println("[Inspect] Failed: " + key + " - " + e.getMessage());
                        inspectedNodes.add(key);
                    }
                }, executor);

                futures.add(future);
            }

            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .get(120, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                System.err.println("[Inspect] Batch timeout");
            } finally {
                executor.shutdownNow();
                boolean interrupted = false;
                boolean terminated = false;
                while (!terminated) {
                    try {
                        terminated = executor.awaitTermination(1, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }

        } catch (Exception e) {
            System.err.println("[Inspect] Error: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        if (nodeInspector != null) nodeInspector.close();
        if (enrichment != null) enrichment.close();
        if (provider != null) provider.close();
        if (enrAcquirer != null) enrAcquirer.close();
        if (scanThread != null) {
            scanThread.interrupt();
        }
        System.out.println("[ScanDaemon] Stopped");
    }

    public void awaitStopped() throws InterruptedException {
        Thread thread = scanThread;
        if (thread != null && thread != Thread.currentThread()) thread.join();
    }

    public boolean isRunning() {
        return running && scanThread != null && scanThread.isAlive();
    }
    public java.util.Map<String,Object> enrichmentMetrics() { return enrichment==null?java.util.Map.of():enrichment.metrics(); }
}
