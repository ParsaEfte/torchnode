package io.github.gavinruff007.torchnode.discovery;

import io.github.gavinruff007.torchnode.model.*;
import io.github.gavinruff007.torchnode.model.NodeIdentity;
import org.web3j.utils.Numeric;
import java.net.DatagramSocket;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Owns discv4 bonding, packet handling and crawling; emits neutral evidence at receipt time. */
public final class Discv4DiscoveryProvider implements DiscoveryProvider {
    private final DatagramSocket socket;
    private final LocalNodeIdentity myNode;
    private final String[] bootstrapNodes;
    private final ConcurrentHashMap<String, DiscoveredNode> discoveredNodes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BondState> bondStates = new ConcurrentHashMap<>();
    private final Set<String> queriedNodes = new HashSet<>();
    private final Queue<DiscoveryObservation> observations = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private Thread listener;

    public Discv4DiscoveryProvider(LocalNodeIdentity myNode, DatagramSocket socket, String[] bootstrapNodes) {
        this.myNode = myNode;
        this.socket = socket;
        this.bootstrapNodes = bootstrapNodes.clone();
    }
    @Override public String protocol() { return "discv4"; }
    @Override public void start() throws Exception {
        running = true;
        listener = P2PListener.startListening(socket, myNode, discoveredNodes, bondStates, this::observe);

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

    }
    void observe(DiscoveredNode node, String sender) {
        observations.add(new DiscoveryObservation(
                new NodeIdentity(node.nodeIdHex), protocol(),
                List.of(new NodeEndpoint(node.ip, NodeEndpoint.Transport.UDP, node.udpPort,
                                NodeEndpoint.AddressFamily.IPV4, NodeEndpoint.Purpose.DISCOVERY),
                        new NodeEndpoint(node.ip, NodeEndpoint.Transport.TCP, node.tcpPort,
                                NodeEndpoint.AddressFamily.IPV4, NodeEndpoint.Purpose.P2P)),
                Instant.now(), "NEIGHBORS from " + sender));
    }
    @Override public void discover(Consumer<DiscoveryObservation> observer) throws InterruptedException {
        if (running) {
            crawlRecursively(socket, myNode, 3, 1, observer);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        }
        drainObservations(observer);
    }
    private void drainObservations(Consumer<DiscoveryObservation> observer) {
        DiscoveryObservation observation;
        while ((observation = observations.peek()) != null) {
            observer.accept(observation);
            observations.remove(); // Retain queued evidence if persistence rejects delivery.
        }
    }
    private void crawlRecursively(DatagramSocket socket, LocalNodeIdentity myNode, int maxDepth, int currentDepth, Consumer<DiscoveryObservation> observer) {
        if (currentDepth > maxDepth) {
            return;
        }

        drainObservations(observer);
        List<DiscoveredNode> nodes = new ArrayList<>(discoveredNodes.values());
        System.out.println("[Crawl] Depth " + currentDepth + " - Processing " + nodes.size() + " nodes");

        for (DiscoveredNode node : nodes) {
            drainObservations(observer);
            if (!running) return;
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

                Thread.sleep(100);

            } catch (Exception e) {
                System.err.println("[Crawl] Error processing node: " + e.getMessage());
            }
        }

        if (running && currentDepth < maxDepth) {
            try {
                Thread.sleep(5000);
                crawlRecursively(socket, myNode, maxDepth, currentDepth + 1, observer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }


    @Override public void close() {
        running = false;
        socket.close();
        boolean interrupted = false;
        if (listener != null && listener != Thread.currentThread()) {
            while (listener.isAlive()) {
                try { listener.join(); } catch (InterruptedException e) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
