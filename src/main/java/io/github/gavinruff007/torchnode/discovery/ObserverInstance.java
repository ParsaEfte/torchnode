package io.github.gavinruff007.torchnode.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.gavinruff007.torchnode.model.NodeIds;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.UUID;

/** Installation metadata only. Discovery private keys are never persisted here. */
public final class ObserverInstance {
    private static final ObjectMapper JSON = new ObjectMapper();
    public enum KeyLifecycle { EPHEMERAL_FIRST_SEEN, EPHEMERAL_ROTATED, UNCHANGED, MISSING }
    public record Run(UUID observerInstanceId, UUID discoveryRunId, Instant startedAt,
                      String localDiscoveryIdentity, KeyLifecycle keyLifecycle) {
        public Run {
            if (observerInstanceId == null || discoveryRunId == null || startedAt == null || keyLifecycle == null)
                throw new IllegalArgumentException("Complete observer/run context is required");
            if (localDiscoveryIdentity != null) {
                localDiscoveryIdentity = NodeIds.normalize(localDiscoveryIdentity);
                if (localDiscoveryIdentity.isEmpty() || keyLifecycle == KeyLifecycle.MISSING)
                    throw new IllegalArgumentException("Invalid local discovery identity/lifecycle");
            } else if (keyLifecycle != KeyLifecycle.MISSING) throw new IllegalArgumentException("Missing local identity must be explicit");
        }
    }
    public record Metadata(int version, String observerInstanceId, String createdAt, String lastLocalDiscoveryIdentity) {}
    private final Path path;
    private final UUID id;
    private ObserverInstance(Path path, UUID id) { this.path = path; this.id = id; }
    public UUID id() { return id; }

    public static Path configuredPath(String databasePath) {
        String override = System.getenv("TORCHNODE_OBSERVER_FILE");
        return Path.of(override == null ? databasePath + ".observer.json" : override).toAbsolutePath().normalize();
    }
    public static synchronized ObserverInstance open(Path file) throws IOException {
        Path path = file.toAbsolutePath().normalize();
        Files.createDirectories(path.getParent());
        boolean previouslyInitialized = Files.exists(path.resolveSibling(path.getFileName() + ".lock"), LinkOption.NOFOLLOW_LINKS);
        try (var channel = lockChannel(path); var lock = channel.lock()) {
            Metadata value = read(path);
            if (value == null) {
                if (previouslyInitialized) throw new IOException("Observer metadata missing; restore installation metadata instead of silently rotating its identity");
                value = new Metadata(1, UUID.randomUUID().toString(), Instant.now().toString(), null);
                write(path, value);
            }
            return new ObserverInstance(path, UUID.fromString(value.observerInstanceId()));
        }
    }
    public synchronized Run beginRun(String localPublicIdentity) throws IOException {
        // JVM-wide lock prevents OverlappingFileLockException between instances in one process.
        synchronized (ObserverInstance.class) {
            String local = localPublicIdentity == null ? null : NodeIds.normalize(localPublicIdentity);
            if (local != null && local.isEmpty()) throw new IllegalArgumentException("Invalid local public identity");
            try (var channel = lockChannel(path); var lock = channel.lock()) {
                Metadata value = read(path);
                if (value == null || !id.toString().equals(value.observerInstanceId()))
                    throw new IOException("Observer metadata missing or replaced; explicit operator recovery required");
                KeyLifecycle lifecycle = local == null ? KeyLifecycle.MISSING
                        : value.lastLocalDiscoveryIdentity() == null ? KeyLifecycle.EPHEMERAL_FIRST_SEEN
                        : local.equals(value.lastLocalDiscoveryIdentity()) ? KeyLifecycle.UNCHANGED : KeyLifecycle.EPHEMERAL_ROTATED;
                if (local != null && !local.equals(value.lastLocalDiscoveryIdentity()))
                    write(path, new Metadata(1, value.observerInstanceId(), value.createdAt(), local));
                return new Run(id, UUID.randomUUID(), Instant.now(), local, lifecycle);
            }
        }
    }
    private static FileChannel lockChannel(Path path) throws IOException {
        Path lock = path.resolveSibling(path.getFileName() + ".lock");
        return FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
    private static Metadata read(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Observer metadata must be a regular file");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(4097);
            if (bytes.length > 4096) throw new IOException("Oversized observer metadata");
            Metadata value = JSON.readValue(bytes, Metadata.class);
            if (value.version() != 1 || !UUID.fromString(value.observerInstanceId()).toString().equals(value.observerInstanceId()))
                throw new IOException("Unsupported observer metadata");
            Instant.parse(value.createdAt());
            if (value.lastLocalDiscoveryIdentity() != null &&
                    (value.lastLocalDiscoveryIdentity().isEmpty() || !value.lastLocalDiscoveryIdentity().equals(NodeIds.normalize(value.lastLocalDiscoveryIdentity()))))
                throw new IOException("Invalid observer public identity metadata");
            return value;
        } catch (RuntimeException e) { throw new IOException("Invalid observer metadata; refusing to regenerate identity", e); }
    }
    private static void write(Path path, Metadata metadata) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".observer-", ".tmp");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            byte[] bytes = JSON.writeValueAsBytes(metadata);
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            // Fail closed on filesystems without atomic replacement; never leave a truncated identity file.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
