package io.github.gavinruff007.torchnode.discovery;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.storage.NodeStore;

import java.net.DatagramSocket;
import java.util.Map;
import java.util.Set;

public class DiscoveryEngine {
    private final NodeStore nodeStore;
    private final DatagramSocket socket;
    private final NodeIdentity localNode;
    
    private final Map<String, DiscoveredNode> discovered;
    private final Set<String> queried;
    
    public DiscoveryEngine(NodeStore nodeStore, DatagramSocket socket, 
                          NodeIdentity localNode,
                          Map<String, DiscoveredNode> discovered,
                          Set<String> queried) {
        this.nodeStore = nodeStore;
        this.socket = socket;
        this.localNode = localNode;
        this.discovered = discovered;
        this.queried = queried;
    }
    
    public void syncToDatabase() {
        for (DiscoveredNode dNode : discovered.values()) {
            NodeRecord record = new NodeRecord(
                dNode.ip,
                dNode.udpPort,
                dNode.tcpPort,
                dNode.nodeIdHex
            );
            
            nodeStore.save(record);
        }
    }
    
    public void startPeriodicSync(long intervalMs) {
        Thread syncThread = new Thread(() -> {
            while (!Thread.interrupted()) {
                try {
                    Thread.sleep(intervalMs);
                    syncToDatabase();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        syncThread.setDaemon(true);
        syncThread.start();
    }
}
