package io.github.gavinruff007.torchnode.inspection;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.model.NodeType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;

public class NodeInspector {
    private final RpcProber rpcProber;
    private final BeaconProber beaconProber;
    
    private static final int[] EXECUTION_PORTS = {8545, 8546, 30303};
    private static final int[] BEACON_PORTS = {5052, 5051, 9000};
    private static final int TIMEOUT_MS = 3000;
    
    public NodeInspector() {
        this.rpcProber = new RpcProber();
        this.beaconProber = new BeaconProber();
    }
    
    public CompletableFuture<NodeRecord> inspect(NodeRecord node) {
        return CompletableFuture.supplyAsync(() -> {
            // بررسی Execution Layer (RPC)
            for (int port : EXECUTION_PORTS) {
                if (isPortOpen(node.getIp(), port)) {
                    try {
                        RpcProber.RpcInfo info = rpcProber.probe(node.getIp(), port);
                        if (info != null) {
                            node.setRpcAvailable(true);
                            node.setClientVersion(info.clientVersion);
                            node.setSyncing(info.syncing);
                            node.setBlockNumber(info.blockNumber);
                            node.setPendingTransactions(info.pendingTxCount);
                            break;
                        }
                    } catch (Exception e) {
                        System.err.println("[Inspector] RPC probe failed for " + 
                            node.getIp() + ":" + port + " - " + e.getMessage());
                    }
                }
            }
            
            // بررسی Consensus Layer (Beacon)
            for (int port : BEACON_PORTS) {
                if (isPortOpen(node.getIp(), port)) {
                    try {
                        BeaconProber.BeaconInfo info = beaconProber.probe(node.getIp(), port);
                        if (info != null) {
                            node.setBeaconAvailable(true);
                            if (node.getClientVersion() == null) {
                                node.setClientVersion(info.version);
                            }
                            break;
                        }
                    } catch (Exception e) {
                        System.err.println("[Inspector] Beacon probe failed for " + 
                            node.getIp() + ":" + port + " - " + e.getMessage());
                    }
                }
            }
            
            // تشخیص نوع نود
            node.setNodeType(determineNodeType(node));
            
            return node;
        });
    }
    
    private NodeType determineNodeType(NodeRecord node) {
        boolean hasRpc = node.isRpcAvailable();
        boolean hasBeacon = node.isBeaconAvailable();
        
        if (hasRpc && hasBeacon) {
            return NodeType.FULL_NODE;
        } else if (hasRpc) {
            return NodeType.EXECUTION;
        } else if (hasBeacon) {
            return NodeType.CONSENSUS;
        } else {
            return NodeType.UNKNOWN;
        }
    }
    
    private boolean isPortOpen(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
