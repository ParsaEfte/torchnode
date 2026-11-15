package io.github.gavinruff007.torchnode;

import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;

/** Bounded loopback-only image healthcheck; no shell or downloader dependency. */
public final class HealthCheck {
    private HealthCheck() {}
    public static void main(String[] args) {
        int result = 1;
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("TORCHNODE_PORT", "8080"));
            if (port < 1024 || port > 65535) throw new IllegalArgumentException("Invalid port");
            var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/healthz").toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(false);
            result = connection.getResponseCode() == 200 ? 0 : 1;
            connection.disconnect();
        } catch (Exception ignored) {
            // Report only process status; configuration/response bodies are not logged.
        }
        System.exit(result);
    }
}
