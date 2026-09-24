package io.github.gavinruff007.torchnode.inspection;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gavinruff007.torchnode.model.NodeRecord;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Bounded one-shot process boundary; no protocol code runs in scanner threads. */
public final class GoEthereumP2pInspector {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Semaphore SLOTS = new Semaphore(4);
    private static final int MAX_OUTPUT_BYTES = 16_384;
    private static final String BINARY_NAME = "torchnode-p2p-helper";
    private final Path helper;

    public GoEthereumP2pInspector() {
        this(System.getenv("TORCHNODE_P2P_HELPER"), applicationDirectory(), Path.of("").toAbsolutePath());
    }

    GoEthereumP2pInspector(String configured, Path applicationDirectory, Path workingDirectory) {
        helper = resolveHelper(configured, applicationDirectory, workingDirectory);
    }

    private static Path applicationDirectory() {
        try {
            Path location = Path.of(GoEthereumP2pInspector.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI()).toAbsolutePath();
            return location.getParent(); // target/classes or the packaged jar -> target
        } catch (Exception e) {
            return null;
        }
    }

    static Path resolveHelper(String configured, Path applicationDirectory, Path workingDirectory) {
        if (configured != null && !configured.isBlank()) {
            try {
                // An explicit setting is authoritative. Never fall back if it is wrong.
                return workingDirectory.resolve(Path.of(configured)).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                return null;
            }
        }
        List<Path> candidates = List.of(
                applicationDirectory == null ? workingDirectory.resolve("target").resolve(BINARY_NAME)
                        : applicationDirectory.resolve(BINARY_NAME),
                workingDirectory.resolve("target").resolve(BINARY_NAME),
                workingDirectory.resolve("p2p-helper").resolve(BINARY_NAME));
        return candidates.stream().map(path -> path.toAbsolutePath().normalize())
                .filter(path -> Files.isRegularFile(path) && Files.isExecutable(path))
                .findFirst().orElse(null);
    }

    Path helperPath() { return helper; }

    public String unavailableReason() {
        if (helper == null) return "No RLPx helper binary found beside TorchNode or in the project build paths";
        if (!Files.isRegularFile(helper)) return "Configured RLPx helper is not a regular file";
        if (!Files.isExecutable(helper)) return "Configured RLPx helper is not executable";
        return null;
    }

    public boolean available() {
        return unavailableReason() == null;
    }

    public Optional<P2pInspectionResult> inspect(NodeRecord node) throws Exception {
        if (!available()) return Optional.empty();
        if (!SLOTS.tryAcquire(1, TimeUnit.SECONDS))
            throw new IllegalStateException("P2P_INSPECTOR_BUSY");
        Process process = null;
        try {
            process = new ProcessBuilder(helper.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            byte[] request = JSON.writeValueAsBytes(Map.of("ip", node.getIp(), "tcpPort", node.getTcpPort(),
                    "nodeId", node.getNodeId() == null ? "" : node.getNodeId()));
            try (var stdin = process.getOutputStream()) { stdin.write(request); }
            if (!process.waitFor(15, TimeUnit.SECONDS))
                throw new IllegalStateException("P2P_HELPER_TIMEOUT");
            byte[] output = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
            if (output.length > MAX_OUTPUT_BYTES || process.exitValue() != 0)
                throw new IllegalStateException("P2P_HELPER_INVALID_RESPONSE");
            return Optional.of(P2pInspectionResult.decode(output));
        } finally {
            if (process != null) {
                process.destroyForcibly();
                process.getInputStream().close();
                process.getErrorStream().close();
            }
            SLOTS.release();
        }
    }
}
