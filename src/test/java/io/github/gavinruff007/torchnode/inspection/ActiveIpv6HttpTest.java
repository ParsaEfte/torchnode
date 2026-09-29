package io.github.gavinruff007.torchnode.inspection;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class ActiveIpv6HttpTest {
    @Test void actualRpcAndBeaconRequestsUseBothLiteralFamilies() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "::1"}) {
            var server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(ip), 0), 8);
            server.createContext("/", exchange -> {
                exchange.getRequestBody().readAllBytes();
                String json = exchange.getRequestMethod().equals("POST")
                    ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"0x1\"}"
                    : "{\"data\":{\"version\":\"IPv6 fixture\"}}";
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start();
            try {
                int port = server.getAddress().getPort();
                try (var rpcProber = new RpcProber(); var beaconProber = new BeaconProber()) {
                    var rpc = rpcProber.probeDetailed(ip, port);
                    assertTrue(rpc.reachable, rpc.error); assertEquals(1L, rpc.chainId);
                    var beacon = beaconProber.probeDetailed(ip, port);
                    assertTrue(beacon.reachable, beacon.error); assertEquals("IPv6 fixture", beacon.version);
                }
            } finally { server.stop(0); }
        }
    }
    @Test void ipv6HttpCancellationClosesPendingSocketPromptly() throws Exception {
        try (var listener = new java.net.ServerSocket(0, 5, InetAddress.getByName("::1")); var prober = new RpcProber()) {
            var result = new java.util.concurrent.atomic.AtomicReference<RpcProber.RpcInfo>();
            Thread worker = new Thread(() -> result.set(prober.probeDetailed("::1", listener.getLocalPort())), "cancel-rpc-fixture");
            worker.start(); listener.setSoTimeout(2000);
            try (var connection = listener.accept()) {
                connection.setSoTimeout(2000);
                assertTrue(connection.getInputStream().read() >= 0);
                long start = System.nanoTime(); prober.close(); worker.join(1000);
                assertFalse(worker.isAlive()); assertTrue(System.nanoTime() - start < 1_000_000_000L);
                assertFalse(result.get().reachable);
            }
        }
    }
}
