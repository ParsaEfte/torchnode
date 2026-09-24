package io.github.gavinruff007.torchnode.discovery;

public class DiscoveredNode {
    public final String ip;

    public byte[] getNodeId() {
        return nodeId;
    }

    public int getTcpPort() {
        return tcpPort;
    }

    public int getUdpPort() {
        return udpPort;
    }

    public String getIp() {
        return ip;
    }

    public final int    udpPort;
    public final int    tcpPort;
    public final byte[] nodeId;
    public final String nodeIdHex;

    public DiscoveredNode(String ip, int udpPort, int tcpPort,
                          byte[] nodeId, String nodeIdHex) {
        this.ip        = ip;
        this.udpPort   = udpPort;
        this.tcpPort   = tcpPort;
        this.nodeId    = nodeId;
        this.nodeIdHex = nodeIdHex;
    }

    public String getKey() {
        return ip + ":" + udpPort;
    }

    @Override
    public String toString() {
        return String.format("Node{ip=%s, udp=%d, tcp=%d, nodeId=%s...}",
                ip, udpPort, tcpPort,
                nodeIdHex.length() >= 16 ? nodeIdHex.substring(0, 16) : nodeIdHex);
    }
}
