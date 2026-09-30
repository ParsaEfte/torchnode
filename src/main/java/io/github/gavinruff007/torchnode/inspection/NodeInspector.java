package io.github.gavinruff007.torchnode.inspection;

import io.github.gavinruff007.torchnode.model.EndpointAddress;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import io.github.gavinruff007.torchnode.model.NodeType;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public class NodeInspector implements AutoCloseable {
    public record Measurement(NodeRecord node,String startedAt,String completedAt,Map<String,Object> evidence) {}
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
    
    public NodeRecord inspect(NodeRecord node, long deadline) { return inspectMeasurement(node,deadline).node(); }

    public Measurement inspectMeasurement(NodeRecord node, long deadline) {
            String startedAt=Instant.now().toString();
            var rpcAttempts=new ArrayList<Map<String,Object>>();
            var beaconAttempts=new ArrayList<Map<String,Object>>();
            Map<String,Object> rpcEvidence=null,beaconEvidence=null;
            // بررسی Execution Layer (RPC)
            for (int port : EXECUTION_PORTS) {
                if (closed || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) break;
                String endpoint=EndpointAddress.http(node.getIp(),port);
                String attemptedAt=Instant.now().toString();
                Boolean open=isPortOpen(node.getIp(), port, deadline);if(open==null)break;
                var attempt=new LinkedHashMap<String,Object>();attempt.put("endpoint",endpoint);attempt.put("attemptedAt",attemptedAt);attempt.put("tcpOpen",open);
                rpcAttempts.add(attempt);
                if (open) {
                    try {
                        RpcProber.RpcInfo info = rpcProber.probeDetailed(node.getIp(), port, deadline);
                        attempt.put("rpcReachable",info.reachable);attempt.put("responseMs",info.responseMs);attempt.put("error",info.error);
                        if (info.reachable) {
                            node.setRpcAvailable(true);
                            node.setClientVersion(info.clientVersion);
                            node.setSyncing(info.syncing);
                            node.setBlockNumber(info.blockNumber);
                            node.setPendingTransactions(info.pendingTxCount);
                            rpcEvidence=new LinkedHashMap<>();rpcEvidence.put("endpoint",endpoint);rpcEvidence.put("observedAt",Instant.now().toString());
                            rpcEvidence.put("responseMs",info.responseMs);rpcEvidence.put("clientVersion",info.clientVersion);
                            rpcEvidence.put("chainId",info.chainId);rpcEvidence.put("networkId",info.networkId);
                            rpcEvidence.put("blockNumber",info.blockNumber);rpcEvidence.put("peerCount",info.peerCount);
                            rpcEvidence.put("syncing",info.syncing);rpcEvidence.put("methodStatus",info.methodStatus);
                            break;
                        }
                    } catch (Exception e) {
                        attempt.put("error",e.getClass().getSimpleName());
                        System.err.println("[Inspector] RPC probe failed for " + 
                            EndpointAddress.hostPort(node.getIp(), port) + " - " + e.getMessage());
                    }
                }
            }
            
            // بررسی Consensus Layer (Beacon)
            for (int port : BEACON_PORTS) {
                if (closed || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) break;
                String endpoint=EndpointAddress.http(node.getIp(),port);
                String attemptedAt=Instant.now().toString();
                Boolean open=isPortOpen(node.getIp(), port, deadline);if(open==null)break;
                var attempt=new LinkedHashMap<String,Object>();attempt.put("endpoint",endpoint);attempt.put("attemptedAt",attemptedAt);attempt.put("tcpOpen",open);
                beaconAttempts.add(attempt);
                if (open) {
                    try {
                        BeaconProber.BeaconInfo info = beaconProber.probeDetailed(node.getIp(), port, deadline);
                        attempt.put("beaconReachable",info.reachable);attempt.put("responseMs",info.responseMs);attempt.put("error",info.error);
                        if (info.reachable) {
                            node.setBeaconAvailable(true);
                            if (node.getClientVersion() == null) {
                                node.setClientVersion(info.version);
                            }
                            beaconEvidence=new LinkedHashMap<>();beaconEvidence.put("endpoint",endpoint);beaconEvidence.put("observedAt",Instant.now().toString());
                            beaconEvidence.put("responseMs",info.responseMs);beaconEvidence.put("version",info.version);
                            beaconEvidence.put("headSlot",info.slot);beaconEvidence.put("syncing",info.syncing);
                            beaconEvidence.put("syncDistance",info.syncDistance);beaconEvidence.put("optimistic",info.optimistic);
                            beaconEvidence.put("executionOffline",info.executionOffline);beaconEvidence.put("genesisTime",info.genesisTime);
                            beaconEvidence.put("genesisValidatorsRoot",info.genesisValidatorsRoot);
                            break;
                        }
                    } catch (Exception e) {
                        attempt.put("error",e.getClass().getSimpleName());
                        System.err.println("[Inspector] Beacon probe failed for " + 
                            EndpointAddress.hostPort(node.getIp(), port) + " - " + e.getMessage());
                    }
                }
            }
            
            // تشخیص نوع نود
            node.setNodeType(determineNodeType(node));
            var evidence=new LinkedHashMap<String,Object>();
            evidence.put("endpointAttempts",List.of());evidence.put("p2p",null);evidence.put("rpc",rpcEvidence);evidence.put("beacon",beaconEvidence);
            evidence.put("rpcAttempts",rpcAttempts);evidence.put("beaconAttempts",beaconAttempts);
            evidence.put("rpcProbeEndpoints",rpcAttempts.stream().map(a->(String)a.get("endpoint")).toList());
            evidence.put("beaconProbeEndpoints",beaconAttempts.stream().map(a->(String)a.get("endpoint")).toList());
            var diagnostics=new ArrayList<Map<String,Object>>();
            for(String name:List.of("P2P TCP","RLPx Auth","RLPx Hello","ETH Status"))diagnostics.add(Map.of("name",name,"state","NOT_TESTED","source","Background scanner"));
            diagnostics.add(Map.of("name","JSON-RPC","state",rpcEvidence!=null?"PASS":rpcAttempts.isEmpty()?"NOT_TESTED":"UNAVAILABLE","source","Background scanner"));
            diagnostics.add(Map.of("name","Beacon API","state",beaconEvidence!=null?"PASS":beaconAttempts.isEmpty()?"NOT_TESTED":"UNAVAILABLE","source","Background scanner"));
            evidence.put("diagnostics",diagnostics);
            return new Measurement(node,startedAt,Instant.now().toString(),evidence);
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
    
    private Boolean isPortOpen(String ip, int port, long deadline) {
        Socket socket = new Socket(); active.add(socket);
        try (socket) {
            if (closed || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline || !EndpointAddress.activeTarget(ip)) return null;
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
