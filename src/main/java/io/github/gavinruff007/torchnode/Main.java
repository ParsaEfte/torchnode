
package io.github.gavinruff007.torchnode;

import io.github.gavinruff007.torchnode.dashboard.DashboardServer;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("TORCHNODE_PORT", "8080"));
        String database = System.getenv().getOrDefault("TORCHNODE_DB", "torchnode.db");
        String bind = System.getenv().getOrDefault("TORCHNODE_BIND", "127.0.0.1");

        try (SqliteNodeStore ignored = new SqliteNodeStore(database)) {
            // Initialize or validate storage before reporting HTTP readiness.
        }
        DashboardServer server = new DashboardServer(port, database, bind);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("TorchNode dashboard listening on " + bind + ":" + port);
        server.await();
    }
}
