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
    private ScanDaemon daemon;
    private DatagramSocket socket;

    public ScannerService(String databasePath) {
        this.databasePath = databasePath;
    }

    public synchronized void start() throws Exception {
        if (isRunning()) {
            return;
        }
        LocalNodeIdentity identity = new LocalNodeIdentity();
        java.util.List<DiscoveryProvider> providers = new java.util.ArrayList<>();
        try {
            socket = new DatagramSocket(30303);
            providers.add(new Discv4DiscoveryProvider(identity, socket, BOOTSTRAP_NODES));
        } catch (java.net.SocketException e) {
            System.err.println("[Discovery] discv4 unavailable: " + e.getMessage()); socket = null;
        }
        try { providers.add(new Discv5DiscoveryProvider(identity)); }
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

    public synchronized boolean isRunning() {
        return daemon != null && daemon.isRunning();
    }
}
