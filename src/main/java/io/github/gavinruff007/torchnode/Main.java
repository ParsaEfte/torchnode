
package io.github.gavinruff007.torchnode;

import io.github.gavinruff007.torchnode.dashboard.DashboardServer;
import io.github.gavinruff007.torchnode.storage.SqliteNodeStore;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("TORCHNODE_PORT", "8080"));
        String database = System.getenv().getOrDefault("TORCHNODE_DB", "torchnode.db");
        String bind = System.getenv().getOrDefault("TORCHNODE_BIND", "127.0.0.1");

        boolean provision=args.length==1 && "--provision-admin".equals(args[0]);
        boolean reset=args.length==2 && "--reset-admin-password".equals(args[0]);
        if(args.length>0 && !provision && !reset)
            throw new IllegalArgumentException("Usage: [--provision-admin | --reset-admin-password USERNAME]");
        System.out.println(io.github.gavinruff007.torchnode.security.LegalNotices.consoleSummary());
        try (SqliteNodeStore ignored = new SqliteNodeStore(database)) {
            // Initialize or validate storage before reporting HTTP readiness.
        }
        if (provision || reset) {
            var console=System.console();
            if(console==null)throw new IllegalStateException("Administrator credential operations require an interactive terminal");
            String username=provision?console.readLine("Administrator username/email: "):args[1];
            char[] password=console.readPassword("Password (12–256 characters): ");
            char[] confirm=console.readPassword("Confirm password: ");
            try {
                if(!java.util.Arrays.equals(password,confirm))throw new IllegalArgumentException("Passwords do not match");
                var authentication=new io.github.gavinruff007.torchnode.security.AuthStore(database);
                if(provision)authentication.provision(username,new String(password));
                else authentication.resetAdministrator(username,new String(password));
            } finally {java.util.Arrays.fill(password,'\0');java.util.Arrays.fill(confirm,'\0');}
            console.printf(provision?"Administrator provisioned.%n":"Administrator password reset; existing sessions revoked.%n");return;
        }
        DashboardServer server = new DashboardServer(port, database, bind);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("TorchNode dashboard listening on " + bind + ":" + port);
        server.await();
    }
}
