package io.github.gavinruff007.torchnode.inspection;

import io.github.gavinruff007.torchnode.model.EndpointAddress;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.model.NodeType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;


public class NodeInspector implements AutoCloseable {
    private volatile boolean closed;
    private final java.util.Set<Socket> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final RpcProber rpcProber;
    private final BeaconProber beaconProber;
    
    private static final int[] EXECUTION_PORTS = {8545, 8546, 30303};
    private static final int[] BEACON_PORTS = {5052, 5051, 9000};
    private static final int TIMEOUT_MS = 3000;
    
    public NodeInspector() {
        this.rpcProber = new RpcProber();
        this.beaconProber = new BeaconProber();
    }
    
    public NodeRecord inspect(NodeRecord node, long deadline) {
            // بررسی Execution Layer (RPC)
            for (int port : EXECUTION_PORTS) {
                if (closed || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) break;
                if (isPortOpen(node.getIp(), port, deadline)) {
                    try {
                        RpcProber.RpcInfo info = rpcProber.probeDetailed(node.getIp(), port, deadline);
                        if (info.reachable) {
                            node.setRpcAvailable(true);
                            node.setClientVersion(info.clientVersion);
                            node.setSyncing(info.syncing);
                            node.setBlockNumber(info.blockNumber);
                            node.setPendingTransactions(info.pendingTxCount);
                            break;
                        }
                    } catch (Exception e) {
                        System.err.println("[Inspector] RPC probe failed for " + 
                            EndpointAddress.hostPort(node.getIp(), port) + " - " + e.getMessage());
                    }
                }
            }
            
            // بررسی Consensus Layer (Beacon)
            for (int port : BEACON_PORTS) {
                if (closed || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) break;
                if (isPortOpen(node.getIp(), port, deadline)) {
                    try {
                        BeaconProber.BeaconInfo info = beaconProber.probeDetailed(node.getIp(), port, deadline);
                        if (info.reachable) {
                            node.setBeaconAvailable(true);
                            if (node.getClientVersion() == null) {
                                node.setClientVersion(info.version);
                            }
                            break;
                        }
                    } catch (Exception e) {
                        System.err.println("[Inspector] Beacon probe failed for " + 
                            EndpointAddress.hostPort(node.getIp(), port) + " - " + e.getMessage());
                    }
                }
            }
            
            // تشخیص نوع نود
            node.setNodeType(determineNodeType(node));
            
            return node;
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
    
    private boolean isPortOpen(String ip, int port, long deadline) {
        Socket socket = new Socket(); active.add(socket);
        try (socket) {
            if (closed || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline || !EndpointAddress.activeTarget(ip)) return false;
            int remaining = (int)Math.max(1, Math.min(TIMEOUT_MS, (deadline - System.nanoTime()) / 1_000_000));
            socket.connect(EndpointAddress.socket(ip, port), remaining);
            return true;
        } catch (IOException e) { return false; }
        finally { active.remove(socket); }
    }
    @Override public void close() {
        closed = true;
        active.forEach(socket -> { try { socket.close(); } catch (IOException ignored) {} });
        rpcProber.close(); beaconProber.close();
    }
}
