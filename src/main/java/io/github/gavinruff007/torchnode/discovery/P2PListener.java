package io.github.gavinruff007.torchnode.discovery;

import org.bouncycastle.util.encoders.Hex;
import io.github.gavinruff007.torchnode.model.BondState;
import org.web3j.rlp.RlpDecoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;


public class P2PListener {
    


    public static void startListening(DatagramSocket socket, NodeIdentity myNode,
                                      ConcurrentHashMap<String, DiscoveredNode> discoveredNodesMap,
                                      ConcurrentHashMap<String, BondState> bondStates) {
        Thread listenerThread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            System.out.println("Listener started. Waiting for Ethereum nodes to talk back...");

            while (!socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    byte[] receivedData = Arrays.copyOf(packet.getData(), packet.getLength());

                    if (receivedData.length < 98) {
                        continue;
                    }

                    byte[] hash = Arrays.copyOfRange(receivedData, 0, 32);
                    byte packetType = receivedData[97];
                    byte[] rlpData = Arrays.copyOfRange(receivedData, 98, receivedData.length);

                    String sender = packet.getAddress().getHostAddress() + ":" + packet.getPort();

                    // در حلقه receive، قبل از شرط packetType:
                    System.out.printf("[RAW] From %s | len=%d | type=0x%02x%n",
                            sender, receivedData.length, packetType);


                    if (packetType == 0x01) {
                        System.out.println("[←] PING from " + sender);
                        handlePing(socket, packet, hash, myNode, bondStates);

                    } else if (packetType == 0x02) {
                        System.out.println("[←] PONG from " + sender);
                        updateBondState(sender, bondStates, false, true, false);

                    } else if (packetType == 0x04) {
                        System.out.println("[←] NEIGHBORS from " + sender);
                        handleNeighbors(rlpData,discoveredNodesMap);

                    } else {
                        System.out.printf("[WARN] Unknown packet type: 0x%02x from %s%n", packetType, sender);
                    }

                } catch (Exception e) {
                    if (socket.isClosed()) {
                        break;
                    }
                    System.err.println("[ERROR] Listener: " + e.getMessage());
                }
            }
        });

        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private static void handlePing(DatagramSocket socket, DatagramPacket packet,
                                   byte[] pingHash, NodeIdentity myNode,
                                   ConcurrentHashMap<String, BondState> bondStates) {
        try {
            InetAddress senderIP = packet.getAddress();
            int senderPort = packet.getPort();
            String sender = senderIP.getHostAddress() + ":" + senderPort;

            P2PSender.sendPong(myNode, senderIP.getHostAddress(), senderPort, socket, pingHash);
            System.out.println("[→] PONG sent to " + sender);

            updateBondState(sender, bondStates, true, false, true);

        } catch (Exception e) {
            System.err.println("[ERROR] Failed to send Pong: " + e.getMessage());
        }
    }

    private static void updateBondState(String sender,
                                        ConcurrentHashMap<String, BondState> bondStates,
                                        boolean receivedPing, boolean receivedPong, boolean sentPong) {
        BondState state = bondStates.get(sender);
        if (state != null) {
            if (receivedPing) state.receivedPing = true;
            if (receivedPong) state.receivedPong = true;
            if (sentPong) state.sentPong = true;
        }
    }

    private static void handleNeighbors(byte[] rlpData,ConcurrentHashMap<String, DiscoveredNode> discoveredNodesMap) {
        try {
            RlpList outerDecoded = RlpDecoder.decode(rlpData);
            if (outerDecoded.getValues().isEmpty()) {
                System.err.println("[WARN] Empty RLP in Neighbors packet");
                return;
            }

            RlpList decoded = (RlpList) outerDecoded.getValues().get(0);
            if (decoded.getValues().size() < 2) {
                System.err.println("[WARN] Malformed Neighbors: expected [nodes, expiration]");
                return;
            }

            RlpList nodesList = (RlpList) decoded.getValues().get(0);
            byte[] expirationBytes = ((RlpString) decoded.getValues().get(1)).getBytes();
            long expirationTime = bytesToLong(expirationBytes);

            System.out.println("\n╔════════════════════════════════════════════╗");
            System.out.println("║        NEIGHBORS RESPONSE RECEIVED         ║");
            System.out.println("╚════════════════════════════════════════════╝");
            System.out.println("Number of neighbors: " + nodesList.getValues().size());
            System.out.println("Expiration: " + expirationTime);
            System.out.println();

            List<?> neighbors = nodesList.getValues();
            for (int i = 0; i < neighbors.size(); i++) {
                RlpList neighborData = (RlpList) neighbors.get(i);
                if (neighborData.getValues().size() < 4) {
                    System.err.println("[WARN] Skipping malformed neighbor entry");
                    continue;
                }

                byte[] ipBytes = ((RlpString) neighborData.getValues().get(0)).getBytes();
                if (ipBytes.length != 4) {
                    System.err.println("[WARN] Invalid IP length, skipping");
                    continue;
                }

                String ip = (ipBytes[0] & 0xFF) + "." + (ipBytes[1] & 0xFF) + "."
                        + (ipBytes[2] & 0xFF) + "." + (ipBytes[3] & 0xFF);

                // discv4 NEIGHBORS entry: [ip, udp-port, tcp-port, node-id].
                // TCP is advertised in field 2; it must never be inferred from field 1 (UDP).
                int udpPort = discoveryPort(((RlpString) neighborData.getValues().get(1)).getBytes());
                int tcpPort = discoveryPort(((RlpString) neighborData.getValues().get(2)).getBytes());

                byte[] nodeId = ((RlpString) neighborData.getValues().get(3)).getBytes();
                String nodeIdHex = Hex.toHexString(nodeId);

                System.out.printf("  Node #%d%n", i + 1);
                System.out.printf("    IP:      %s%n", ip);
                System.out.printf("    UDP:     %d%n", udpPort);
                System.out.printf("    TCP:     %d%n", tcpPort);
                System.out.printf("    NodeID:  %s...%n",
                        nodeIdHex.length() >= 16 ? nodeIdHex.substring(0, 16) : nodeIdHex);
                System.out.println();

                // ── ذخیره در discoveredNodes ────────────────────────────────
                if (nodeId.length == 64) {
                    DiscoveredNode node = new DiscoveredNode(ip, udpPort, tcpPort, nodeId, nodeIdHex);
                    String key = node.getKey();
                    discoveredNodesMap.putIfAbsent(key, node);
                } else {
                    System.err.printf("[WARN] NodeID length %d (expected 64), skipping storage%n",
                            nodeId.length);
                }
            }

            System.out.printf("[Crawl] discoveredNodes total: %d%n", discoveredNodesMap.size());

        } catch (Exception e) {
            System.err.println("[ERROR] Failed to decode Neighbors: " + e.getMessage());
            e.printStackTrace();
        }
    }

    static int discoveryPort(byte[] encoded) {
        if (encoded.length == 0) return 0;
        if (encoded.length > 2) throw new IllegalArgumentException("Invalid discovery port length");
        int port = 0;
        for (byte b : encoded) port = (port << 8) | (b & 0xff);
        return port;
    }


    private static long bytesToLong(byte[] bytes) {
        long result = 0;
        for (byte b : bytes) {
            result = (result << 8) | (b & 0xFF);
        }
        return result;
    }
}
