package io.github.gavinruff007.torchnode.inspection;

import io.github.gavinruff007.torchnode.model.EndpointAddress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RpcProber implements AutoCloseable {
    private volatile boolean closed;
    private final java.util.Set<Call> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final OkHttpClient client;
    private final ObjectMapper mapper;
    
    public RpcProber() {
        this.client = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build();
        this.mapper = new ObjectMapper();
    }
    
    public static class RpcInfo {
        public int port;
        public boolean reachable;
        public String clientVersion;
        public Boolean syncing;
        public Long blockNumber;
        public Integer pendingTxCount;
        public Long chainId;
        public String networkId;
        public Long peerCount;
        public final Map<String, String> methodStatus = new LinkedHashMap<>();
        public String error;
        public Long responseMs;
    }
    
    public RpcInfo probe(String ip, int port) {
        RpcInfo info = probeDetailed(ip, port);
        return info.reachable ? info : null;
    }

    public RpcInfo probeDetailed(String ip, int port) {
        return probeDetailed(ip, port, Long.MAX_VALUE);
    }

    public RpcInfo probeDetailed(String ip, int port, long deadline) {
        RpcInfo info = new RpcInfo();
        info.port = port;
        info.clientVersion = textCall(ip, port, "web3_clientVersion", info, deadline);
        String chainId = textCall(ip, port, "eth_chainId", info, deadline);
        String networkId = textCall(ip, port, "net_version", info, deadline);
        String block = textCall(ip, port, "eth_blockNumber", info, deadline);
        String peers = textCall(ip, port, "net_peerCount", info, deadline);
        String syncing = textCall(ip, port, "eth_syncing", info, deadline);

        info.chainId = parseQuantity(chainId);
        info.networkId = networkId;
        info.blockNumber = parseQuantity(block);
        info.peerCount = parseQuantity(peers);
        info.syncing = syncing == null ? null : !"false".equals(syncing);
        info.reachable = info.methodStatus.containsValue("PASS");
        if (!info.reachable && info.error == null) info.error = "No supported JSON-RPC method responded";
        return info;
    }

    private String textCall(String ip, int port, String method, RpcInfo info, long deadline) {
        long started = System.nanoTime();
        try {
            String result = callRpc(ip, port, method, deadline);
            if (result == null) {
                info.methodStatus.put(method, "UNAVAILABLE");
                return null;
            }
            info.methodStatus.put(method, "PASS");
            if (info.responseMs == null) info.responseMs = (System.nanoTime() - started) / 1_000_000;
            return result;
        } catch (Exception e) {
            info.methodStatus.put(method, "FAILED");
            info.error = concise(e);
            return null;
        }
    }

    private Long parseQuantity(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return value.startsWith("0x") ? Long.parseUnsignedLong(value.substring(2), 16)
                    : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String concise(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private String callRpc(String ip, int port, String method, long deadline) throws IOException {
        String url = EndpointAddress.http(ip, port);
        
        String jsonBody = String.format(
            "{\"jsonrpc\":\"2.0\",\"method\":\"%s\",\"params\":[],\"id\":1}",
            method
        );
        
        RequestBody body = RequestBody.create(
            jsonBody,
            MediaType.get("application/json")
        );
        
        Request request = new Request.Builder()
            .url(url)
            .post(body)
            .build();
        
        Call call = client.newCall(request);
        if (deadline != Long.MAX_VALUE) call.timeout().deadlineNanoTime(deadline);
        active.add(call);
        if (closed || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) call.cancel();
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            
            String responseBody = response.body().string();
            JsonNode node = mapper.readTree(responseBody);
            
            if (node.has("result")) {
                JsonNode result = node.get("result");
                return result.isTextual() ? result.asText() : result.toString();
            }
            if (node.has("error")) {
                throw new IOException(node.path("error").path("message").asText("RPC error"));
            }
        } finally { active.remove(call); }
        throw new IOException("Missing JSON-RPC result");
    }
    @Override public void close() {
        closed = true;
        active.forEach(Call::cancel);
        client.connectionPool().evictAll();
    }
}
