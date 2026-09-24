
package io.github.gavinruff007.torchnode;

import io.github.gavinruff007.torchnode.dashboard.DashboardServer;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("TORCHNODE_PORT", "8080"));
        String database = System.getenv().getOrDefault("TORCHNODE_DB", "torchnode.db");

        DashboardServer server = new DashboardServer(port, database);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("TorchNode dashboard: http://localhost:" + port);
        server.await();
    }
}
