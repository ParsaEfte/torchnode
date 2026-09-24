package io.github.gavinruff007.torchnode.model;

import java.time.Instant;

public class NodeRecord {
    private String ip;
    private int udpPort;
    private int tcpPort;
    private String nodeId;
    private String country;
    private Long latency;
    private Long p2pConnectMs;
    private NodeType nodeType;
    private Instant lastSeen;
    
    // RPC/Beacon Status
    private boolean rpcAvailable;
    private boolean beaconAvailable;
    private String clientVersion;
    private Boolean syncing;
    private Long blockNumber;
    private Integer pendingTransactions;
    
    public NodeRecord(String ip, int udpPort, int tcpPort, String nodeId) {
        this.ip = ip;
        this.udpPort = udpPort;
        this.tcpPort = tcpPort;
        this.nodeId = NodeIds.normalize(nodeId);
        this.lastSeen = Instant.now();
        this.nodeType = NodeType.UNKNOWN;
    }
    
    public String getKey() {
        return ip + ":" + udpPort;
    }
    
    public void updateLastSeen() {
        this.lastSeen = Instant.now();
    }
    
    // Getters & Setters
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    
    public int getUdpPort() { return udpPort; }
    public void setUdpPort(int udpPort) { this.udpPort = udpPort; }
    
    public int getTcpPort() { return tcpPort; }
    public void setTcpPort(int tcpPort) { this.tcpPort = tcpPort; }
    
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = NodeIds.normalize(nodeId); }
    
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    
    public Long getLatency() { return latency; }
    public void setLatency(Long latency) { this.latency = latency; }

    public Long getP2pConnectMs() { return p2pConnectMs; }
    public void setP2pConnectMs(Long p2pConnectMs) { this.p2pConnectMs = p2pConnectMs; }
    
    public NodeType getNodeType() { return nodeType; }
    public void setNodeType(NodeType nodeType) { this.nodeType = nodeType; }
    
    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant lastSeen) { this.lastSeen = lastSeen; }
    
    public boolean isRpcAvailable() { return rpcAvailable; }
    public void setRpcAvailable(boolean rpcAvailable) { this.rpcAvailable = rpcAvailable; }
    
    public boolean isBeaconAvailable() { return beaconAvailable; }
    public void setBeaconAvailable(boolean beaconAvailable) { this.beaconAvailable = beaconAvailable; }
    
    public String getClientVersion() { return clientVersion; }
    public void setClientVersion(String clientVersion) { this.clientVersion = clientVersion; }
    
    public Boolean getSyncing() { return syncing; }
    public void setSyncing(Boolean syncing) { this.syncing = syncing; }
    
    public Long getBlockNumber() { return blockNumber; }
    public void setBlockNumber(Long blockNumber) { this.blockNumber = blockNumber; }
    
    public Integer getPendingTransactions() { return pendingTransactions; }
    public void setPendingTransactions(Integer pendingTransactions) { 
        this.pendingTransactions = pendingTransactions; 
    }
    
    @Override
    public String toString() {
        return String.format("Node{ip=%s, udp=%d, type=%s, rpc=%s, beacon=%s}",
            ip, udpPort, nodeType, rpcAvailable, beaconAvailable);
    }
}
