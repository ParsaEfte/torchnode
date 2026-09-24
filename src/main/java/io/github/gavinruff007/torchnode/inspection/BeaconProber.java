package io.github.gavinruff007.torchnode.inspection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class BeaconProber {
    private final OkHttpClient client;
    private final ObjectMapper mapper;
    
    public BeaconProber() {
        this.client = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build();
        this.mapper = new ObjectMapper();
    }
    
    public static class BeaconInfo {
        public int port;
        public boolean reachable;
        public String version;
        public Long slot;
        public Boolean syncing;
        public Long syncDistance;
        public Boolean optimistic;
        public Boolean executionOffline;
        public Long genesisTime;
        public String genesisValidatorsRoot;
        public String error;
        public Long responseMs;
    }
    
    public BeaconInfo probe(String ip, int port) {
        BeaconInfo info = probeDetailed(ip, port);
        return info.reachable ? info : null;
    }

    public BeaconInfo probeDetailed(String ip, int port) {
        BeaconInfo info = new BeaconInfo();
        info.port = port;
        long started = System.nanoTime();
        try {
            String versionJson = callBeaconApi(ip, port, "/eth/v1/node/version");
            JsonNode node = mapper.readTree(versionJson);
            info.version = text(node.at("/data/version"));
            if (info.version != null && !info.version.isBlank()) {
                info.reachable = true;
                info.responseMs = (System.nanoTime() - started) / 1_000_000;
            }
        } catch (Exception e) { info.error = concise(e); }
        started = System.nanoTime();
        try {
            String headJson = callBeaconApi(ip, port, "/eth/v1/beacon/headers/head");
            JsonNode node = mapper.readTree(headJson);
            info.slot = longValue(node.at("/data/header/message/slot"));
            if (info.slot != null) {
                info.reachable = true;
                if (info.responseMs == null) info.responseMs = (System.nanoTime() - started) / 1_000_000;
            }
        } catch (Exception e) { if (info.error == null) info.error = concise(e); }
        started = System.nanoTime();
        try {
            String syncJson = callBeaconApi(ip, port, "/eth/v1/node/syncing");
            JsonNode data = mapper.readTree(syncJson).path("data");
            info.syncing = booleanValue(data.get("is_syncing"));
            info.syncDistance = longValue(data.get("sync_distance"));
            info.optimistic = booleanValue(data.get("is_optimistic"));
            info.executionOffline = booleanValue(data.get("el_offline"));
            if (info.syncing != null || info.syncDistance != null || info.optimistic != null
                    || info.executionOffline != null) {
                info.reachable = true;
                if (info.responseMs == null) info.responseMs = (System.nanoTime() - started) / 1_000_000;
            }
        } catch (Exception e) { if (info.error == null) info.error = concise(e); }
        started = System.nanoTime();
        try {
            String genesisJson = callBeaconApi(ip, port, "/eth/v1/beacon/genesis");
            JsonNode data = mapper.readTree(genesisJson).path("data");
            info.genesisTime = longValue(data.get("genesis_time"));
            info.genesisValidatorsRoot = text(data.get("genesis_validators_root"));
            if (info.genesisTime != null || info.genesisValidatorsRoot != null) {
                info.reachable = true;
                if (info.responseMs == null) info.responseMs = (System.nanoTime() - started) / 1_000_000;
            }
        } catch (Exception e) { if (info.error == null) info.error = concise(e); }
        return info;
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private Long longValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        try { return Long.parseLong(node.asText()); } catch (NumberFormatException e) { return null; }
    }

    private Boolean booleanValue(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asBoolean();
    }

    private String concise(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
    
    private String callBeaconApi(String ip, int port, String endpoint) throws IOException {
        String url = "http://" + ip + ":" + port + endpoint;
        
        Request request = new Request.Builder()
            .url(url)
            .get()
            .build();
        
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            
            return response.body().string();
        }
    }
}
