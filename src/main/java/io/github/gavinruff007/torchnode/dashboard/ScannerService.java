package io.github.gavinruff007.torchnode.dashboard;

import io.github.gavinruff007.torchnode.daemon.ScanDaemon;
import io.github.gavinruff007.torchnode.discovery.LocalNodeIdentity;
import io.github.gavinruff007.torchnode.discovery.*;
import java.util.List;

import java.net.DatagramSocket;

public class ScannerService {
    private static final String[] BOOTSTRAP_NODES = {
            "18.138.108.67:30303",
            "3.209.45.79:30303"
    };

    private final String databasePath;
    private volatile ScanDaemon daemon;
    private DatagramSocket socket;
    private ObserverInstance.Run discoveryRun;
    private Discv4DiscoveryProvider discv4Provider;
    private Discv5DiscoveryProvider discv5Provider;

    public ScannerService(String databasePath) {
        this.databasePath = databasePath;
    }

    public synchronized void start() throws Exception {
        if ("true".equals(System.getenv("TORCHNODE_COLLECTION_DISABLED")))
            throw new IllegalStateException("Collection disabled by operator configuration");
        if (isRunning()) {
            return;
        }
        discv4Provider = null; discv5Provider=null;
        LocalNodeIdentity identity = new LocalNodeIdentity();
        discoveryRun = ObserverInstance.open(ObserverInstance.configuredPath(databasePath)).beginRun(identity.getNodeId());
        java.util.List<DiscoveryProvider> providers = new java.util.ArrayList<>();
        try {
            socket = new DatagramSocket(30303);
            discv4Provider = new Discv4DiscoveryProvider(identity, socket, BOOTSTRAP_NODES, discoveryRun);
            providers.add(discv4Provider);
        } catch (java.net.SocketException e) {
            System.err.println("[Discovery] discv4 unavailable: " + e.getMessage()); socket = null;
        }
        try { discv5Provider=new Discv5DiscoveryProvider(identity,discoveryRun); providers.add(discv5Provider); }
        catch (java.io.IOException e) { System.err.println("[Discovery] discv5 configuration unavailable: " + e.getMessage()); }
        if (providers.isEmpty()) throw new java.io.IOException("No discovery provider could be configured");
        daemon = new ScanDaemon(databasePath);
        try { daemon.start(new CompositeDiscoveryProvider(providers)); }
        catch (Exception e) {
            providers.forEach(DiscoveryProvider::close); socket = null; daemon = null; throw e;
        }
    }

    public synchronized void stop() {
        if (daemon != null) {
            daemon.stop();
        }
        if (socket != null) {
            socket.close();
        }
        boolean interrupted = false;
        if (daemon != null) {
            boolean stopped = false;
            while (!stopped) {
                try {
                    daemon.awaitStopped();
                    stopped = true;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        daemon = null;
        socket = null;
        if (interrupted) Thread.currentThread().interrupt();
    }

    public boolean isRunning() {
        ScanDaemon current=daemon;return current != null && current.isRunning();
    }

    /** Public installation/run context, never discovery private-key material. */
    public synchronized ObserverInstance.Run discoveryRun() { return discoveryRun; }
    public synchronized Discv5EvidenceCapture.Snapshot discv5Evidence() {return discv5Provider==null?null:discv5Provider.relationshipEvidence();}
    public synchronized Discv4EvidenceCapture.Snapshot discv4Evidence() {
        return discv4Provider == null ? null : discv4Provider.relationshipEvidence();
    }
}
