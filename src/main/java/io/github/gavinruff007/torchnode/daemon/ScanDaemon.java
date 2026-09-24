package io.github.gavinruff007.torchnode.daemon;

import io.github.gavinruff007.torchnode.discovery.*;
import io.github.gavinruff007.torchnode.inspection.NodeInspector;
import io.github.gavinruff007.torchnode.model.BondState;
import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.storage.NodeStore;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;
import org.web3j.utils.Numeric;

import java.net.DatagramSocket;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

public class ScanDaemon {
    private static final int INSPECT_BATCH_SIZE = 50;
    private static final int INSPECT_TIMEOUT_SECONDS = 10;
    private final NodeInspector nodeInspector = new NodeInspector();
    private final String databasePath;


    private volatile boolean running = false;
    private Thread scanThread;
    public ConcurrentHashMap<String, DiscoveredNode> discoveredNodes = new ConcurrentHashMap<>();
    private Set<String> queriedNodes = new HashSet<>();
    private Set<String> inspectedNodes = ConcurrentHashMap.newKeySet();
    private ConcurrentHashMap<String, BondState> bondStates = new ConcurrentHashMap<>();

    public ScanDaemon(String databasePath) {
        this.databasePath = databasePath;
    }

    public void start(NodeIdentity myNode, DatagramSocket socket, String[] bootstrapNodes) throws SQLException {
        if (running) {
            System.out.println("[ScanDaemon] Already running");
            return;
        }

        running = true;
        NodeStore nodeStore = new SqliteNodeStore(databasePath);

        scanThread = new Thread(() -> {
            try {
                System.out.println("[ScanDaemon] Starting discovery...");

                P2PListener.startListening(socket, myNode, discoveredNodes,bondStates);

                for (String bootstrap : bootstrapNodes) {
                    String[] parts = bootstrap.split(":");
                    String ip = parts[0];
                    int port = Integer.parseInt(parts[1]);

                    System.out.println("[ScanDaemon] Pinging bootstrap: " + bootstrap);
                    P2PSender.sendPing(myNode, ip, port, socket);

                    String key = ip + ":" + port;
                    bondStates.put(key, new BondState(true, false, false, false));
                }

                Thread.sleep(2000);

                for (String bootstrap : bootstrapNodes) {
                    String[] parts = bootstrap.split(":");
                    String ip = parts[0];
                    int port = Integer.parseInt(parts[1]);
                    String key = ip + ":" + port;

                    BondState state = bondStates.get(key);
                    if (state != null && state.isFullyBonded()) {
                        System.out.println("[ScanDaemon] Sending FIND_NODE to bootstrap: " + bootstrap);
                        byte[] publicKey = Numeric.hexStringToByteArray(myNode.getNodeId());
                        FindNodeSender.sendFindNode(myNode, ip, port, socket, publicKey);
                    }
                }

                while (running) {
                    crawlRecursively(socket, myNode, nodeStore, 3, 1);
                    inspectNewNodes(nodeStore);
                    Thread.sleep(60000);
                }

            } catch (InterruptedException e) {
                System.out.println("[ScanDaemon] Interrupted");
            } catch (Exception e) {
                System.err.println("[ScanDaemon] Error: " + e.getMessage());
                e.printStackTrace();
            } finally {
                running = false;
                nodeStore.close();
            }
        });

        scanThread.setDaemon(true);
        scanThread.start();
        System.out.println("[ScanDaemon] Started");
    }

    private void crawlRecursively(DatagramSocket socket, NodeIdentity myNode, NodeStore nodeStore, int maxDepth, int currentDepth) {
        if (currentDepth > maxDepth) {
            return;
        }

        List<DiscoveredNode> nodes = new ArrayList<>(discoveredNodes.values());
        System.out.println("[Crawl] Depth " + currentDepth + " - Processing " + nodes.size() + " nodes");

        for (DiscoveredNode node : nodes) {
            try {
                String key = node.getIp() + ":" + node.getUdpPort();
                BondState state = bondStates.get(key);

                if (state == null || !state.isFullyBonded()) {
                    P2PSender.sendPing(myNode, node.getIp(), node.getUdpPort(), socket);
                    bondStates.put(key, new BondState(true, false, false, false));
                }

                state = bondStates.get(key);
                if (state != null && state.isFullyBonded() && !queriedNodes.contains(key)) {
                    System.out.println("[Crawl] Sending FIND_NODE to: " + key);
                    byte[] publicKey = Numeric.hexStringToByteArray(myNode.getNodeId());
                    FindNodeSender.sendFindNode(myNode, node.getIp(), node.getUdpPort(), socket, publicKey);
                    queriedNodes.add(key);
                }

                NodeRecord record = new NodeRecord(
                        node.getIp(),
                        node.getUdpPort(),
                        node.getTcpPort(),
                        node.nodeIdHex
                );

                nodeStore.save(record);
                Thread.sleep(100);

            } catch (Exception e) {
                System.err.println("[Crawl] Error processing node: " + e.getMessage());
            }
        }

        if (currentDepth < maxDepth) {
            try {
                Thread.sleep(5000);
                crawlRecursively(socket, myNode, nodeStore, maxDepth, currentDepth + 1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }


    private void inspectNewNodes(NodeStore nodeStore) {
        try {
            List<NodeRecord> allNodes = nodeStore.findAll();
            List<NodeRecord> toInspect = new ArrayList<>();

            for (NodeRecord node : allNodes) {
                String key = node.getIp() + ":" + node.getUdpPort();
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
                    String key = node.getIp() + ":" + node.getUdpPort();
                    try {
                        NodeRecord inspected = nodeInspector.inspect(node).get(INSPECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        nodeStore.update(inspected);
                        inspectedNodes.add(key);
                        System.out.println("[Inspect] Success: " + key);
                    } catch (TimeoutException e) {
                        System.out.println("[Inspect] Timeout: " + key);
                        inspectedNodes.add(key);
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
            }

            executor.shutdown();

        } catch (Exception e) {
            System.err.println("[Inspect] Error: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        if (scanThread != null) {
            scanThread.interrupt();
        }
        System.out.println("[ScanDaemon] Stopped");
    }

    public boolean isRunning() {
        return running && scanThread != null && scanThread.isAlive();
    }
}
