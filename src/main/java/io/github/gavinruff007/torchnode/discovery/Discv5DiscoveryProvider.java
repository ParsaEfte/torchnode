package io.github.gavinruff007.torchnode.discovery;

import com.fasterxml.jackson.databind.*;
import io.github.gavinruff007.torchnode.enr.*;
import io.github.gavinruff007.torchnode.model.*;
import org.web3j.utils.Numeric;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Lifecycle adapter for the pinned geth discv5 engine. Every emitted ENR is revalidated in Java. */
public final class Discv5DiscoveryProvider implements DiscoveryProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Received(DiscoveryObservation observation, EnrEvidence enr) {}
    private final LocalNodeIdentity identity;
    private final Path executable;
    private final List<String> bootstraps;
    private final ArrayBlockingQueue<Received> observations = new ArrayBlockingQueue<>(512);
    private final ArrayBlockingQueue<EnrEvidence> evidence = new ArrayBlockingQueue<>(1024);
    private final ArrayDeque<String> diagnostics = new ArrayDeque<>();
    private final Map<String,Long> transportCounts = new LinkedHashMap<>();
    private volatile boolean running;
    private volatile long lastEvent;
    private Process process;
    private Thread reader;
    public Discv5DiscoveryProvider(LocalNodeIdentity identity) throws IOException {
        this(identity, Path.of(System.getenv().getOrDefault("TORCHNODE_DISCV5_HELPER", "target/torchnode-discovery-helper")), loadBootstraps());
    }
    public Discv5DiscoveryProvider(LocalNodeIdentity identity, Path executable, List<String> bootstraps) {
        this.identity = identity; this.executable = executable; this.bootstraps = List.copyOf(bootstraps);
    }
    private static List<String> loadBootstraps() throws IOException {
        String override = System.getenv("TORCHNODE_DISCV5_BOOTSTRAPS");
        if (override != null) return Files.readAllLines(Path.of(override)).stream().map(String::trim).filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
        try (var stream = Discv5DiscoveryProvider.class.getResourceAsStream("/discv5-bootnodes.txt")) {
            if (stream == null) throw new IOException("Missing discv5 bootstrap configuration");
            return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).lines().map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#")).toList();
        }
    }
    @Override public String protocol() { return "discv5"; }
    @Override public synchronized void start() throws Exception {
        if (running) return;
        if (!Files.isExecutable(executable)) throw new IOException("Discovery helper unavailable: " + executable);
        if (bootstraps.isEmpty() || bootstraps.size() > 32) throw new IOException("Require 1..32 discv5 bootstrap ENRs");
        // Configuration, including the shared identity secret, travels only over stdin, never argv/logs.
        process = new ProcessBuilder(executable.toAbsolutePath().toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var input = process.getOutputStream()) {
            JSON.writeValue(input, Map.of("privateKey", Numeric.toHexStringNoPrefixZeroPadded(identity.getKeyPair().getPrivateKey(), 64), "bootstraps", bootstraps));
        } catch (Exception e) { process.destroyForcibly(); throw e; }
        running = true; lastEvent = System.nanoTime();
        reader = new Thread(this::read, "discv5-events"); reader.setDaemon(true); reader.start();
    }
    private void read() {
        try (var input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder(); boolean oversized = false; int c;
            while ((c = input.read()) != -1) {
                if (c == '\n') {
                    if (!oversized && !line.isEmpty()) accept(JSON.readTree(line.toString()));
                    else if (oversized) diagnose("OVERSIZED_HELPER_EVENT");
                    line.setLength(0); oversized = false;
                } else if (line.length() < 8192) line.append((char)c); else oversized = true;
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (Exception e) { diagnose("HELPER_FAILURE: " + e.getClass().getSimpleName()); }
        finally { if (running) diagnose("HELPER_EXIT"); running = false; if (process != null && process.isAlive()) process.destroy(); }
    }
    void accept(JsonNode event) throws InterruptedException {
        lastEvent = System.nanoTime();
        String code = event.path("code").asText();
        if (Set.of("ENDPOINT_ATTEMPT", "ENDPOINT_SUCCESS", "ENDPOINT_FAILURE").contains(code)) {
            String family = event.path("addressFamily").asText();
            if (Set.of("IPV4", "IPV6").contains(family)) synchronized (this) {
                transportCounts.merge(family + "_" + code, 1L, Long::sum);
            }
        }
        if (event.path("type").asText().equals("ready") && !event.path("nodeId").asText().equals(identity.getNodeId()))
            throw new IllegalStateException("Helper local identity differs from shared discovery identity");
        if (!event.path("type").asText().equals("node")) {
            diagnose(event.path("code").asText(event.path("type").asText()) + ": " + event.path("detail").asText()); return;
        }
        try {
            NodeIdentity expected = new NodeIdentity(event.path("nodeId").asText());
            if (expected.nodeId().length() != 128) { diagnose("INVALID_HELPER_IDENTITY"); return; }
            String raw = event.path("rlp").asText();
            if (raw.length() > 600) { diagnose("OVERSIZED_ENR"); return; }
            String provenance = event.path("provenance").asText() + "; acquired via DISCV5; authenticated returned-node session=" + event.path("authenticated").asBoolean();
            EnrEvidence decoded = new EnrDecoder().decode(HexFormat.of().parseHex(raw), expected,
                    Instant.parse(event.path("at").asText()), provenance);
            if (!decoded.usable()) { evidence.put(decoded); diagnose(decoded.outcome()); return; }
            // Only Java-validated ENR endpoint claims enter the scanner projection, in both families.
            var endpoints = decoded.record().endpoints();
            if (endpoints.stream().noneMatch(e -> e.purpose() == NodeEndpoint.Purpose.DISCOVERY && e.port() > 0)) {
                evidence.put(decoded); diagnose("NO_DISCOVERY_ENDPOINT"); return;
            }
            observations.put(new Received(new DiscoveryObservation(expected, protocol(), endpoints, decoded.observedAt(), provenance), decoded));
        } catch (IllegalArgumentException e) { diagnose("MALFORMED_HELPER_EVENT"); }
    }
    private synchronized void diagnose(String text) { if (diagnostics.size() == 64) diagnostics.removeFirst(); diagnostics.addLast(text); }
    public synchronized List<String> diagnostics() { return List.copyOf(diagnostics); }
    public synchronized Map<String,Long> transportCounts() { return Map.copyOf(transportCounts); }
    @Override public void discover(Consumer<DiscoveryObservation> observer) {
        if (running && System.nanoTime() - lastEvent > TimeUnit.MINUTES.toNanos(2)) {
            diagnose("HELPER_IDLE_TIMEOUT"); close();
        }
        Received item;
        while ((item = observations.peek()) != null) {
            // Keep both until the consumer accepts the discovery projection; failed delivery is retryable.
            if (evidence.remainingCapacity() == 0) break;
            observer.accept(item.observation()); evidence.add(item.enr()); observations.remove();
        }
    }
    @Override public void drainEnrEvidence(Consumer<EnrEvidence> observer) {
        EnrEvidence item; while ((item = evidence.peek()) != null) { observer.accept(item); evidence.remove(); }
    }
    @Override public void close() {
        Process active; Thread events;
        synchronized (this) { running = false; active = process; events = reader; }
        if (active != null) {
            active.destroy();
            try { if (!active.waitFor(3, TimeUnit.SECONDS)) { active.destroyForcibly(); active.waitFor(3, TimeUnit.SECONDS); } }
            catch (InterruptedException e) { active.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
        if (events != null) {
            events.interrupt();
            boolean interrupted = false;
            while (events.isAlive()) try { events.join(100); } catch (InterruptedException e) { interrupted = true; }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
}
