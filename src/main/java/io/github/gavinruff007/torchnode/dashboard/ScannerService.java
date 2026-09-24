package io.github.gavinruff007.torchnode.dashboard;

import io.github.gavinruff007.torchnode.daemon.ScanDaemon;
import io.github.gavinruff007.torchnode.discovery.NodeIdentity;

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
        socket = new DatagramSocket(30303);
        daemon = new ScanDaemon(databasePath);
        try {
            daemon.start(new NodeIdentity(), socket, BOOTSTRAP_NODES);
        } catch (Exception e) {
            socket.close();
            socket = null;
            daemon = null;
            throw e;
        }
    }

    public synchronized void stop() {
        if (daemon != null) {
            daemon.stop();
        }
        if (socket != null) {
            socket.close();
        }
        daemon = null;
        socket = null;
    }

    public synchronized boolean isRunning() {
        return daemon != null && daemon.isRunning();
    }
}
