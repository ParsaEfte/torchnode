package io.github.gavinruff007.torchnode.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ObserverInstanceTest {
    @TempDir Path temporary;
    @Test void stableAcrossReopenDistinctAcrossInstallationsAndRuns() throws Exception {
        var file = temporary.resolve("one/observer.json");
        var first = ObserverInstance.open(file);
        var identity = new LocalNodeIdentity();
        var run = first.beginRun(identity.getNodeId());
        var reopened = ObserverInstance.open(file);
        var next = reopened.beginRun(identity.getNodeId());
        assertEquals(first.id(), reopened.id());
        assertNotEquals(first.id(), ObserverInstance.open(temporary.resolve("two/observer.json")).id());
        assertNotEquals(run.discoveryRunId(), next.discoveryRunId());
        assertEquals(ObserverInstance.KeyLifecycle.EPHEMERAL_FIRST_SEEN, run.keyLifecycle());
        assertEquals(ObserverInstance.KeyLifecycle.UNCHANGED, next.keyLifecycle());
        assertEquals(ObserverInstance.KeyLifecycle.EPHEMERAL_ROTATED,
                reopened.beginRun(new LocalNodeIdentity().getNodeId()).keyLifecycle());
        assertFalse(Files.readString(file).contains("privateKey"));
        if (Files.getFileStore(file).supportsFileAttributeView("posix"))
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
    @Test void missingKeyIsExplicitAndDoesNotOverwriteLastPublicKey() throws Exception {
        var observer = ObserverInstance.open(temporary.resolve("observer.json"));
        var key = new LocalNodeIdentity().getNodeId();
        observer.beginRun(key);
        var missing = observer.beginRun(null);
        assertNull(missing.localDiscoveryIdentity());
        assertEquals(ObserverInstance.KeyLifecycle.MISSING, missing.keyLifecycle());
        assertEquals(ObserverInstance.KeyLifecycle.UNCHANGED, observer.beginRun(key).keyLifecycle());
        assertThrows(IllegalArgumentException.class, () -> observer.beginRun("127.0.0.1"));
    }
    @Test void concurrentInitializersShareOneInstallation() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new java.util.ArrayList<Future<java.util.UUID>>();
            for (int i = 0; i < 8; i++) tasks.add(pool.submit(() -> ObserverInstance.open(temporary.resolve("shared.json")).id()));
            var id = tasks.getFirst().get();
            for (var task : tasks) assertEquals(id, task.get());
        }
    }
    @Test void corruptMissingReplacedAndSymlinkedMetadataFailClosed() throws Exception {
        var file = temporary.resolve("observer.json");
        var observer = ObserverInstance.open(file);
        Files.writeString(file, "{}");
        assertThrows(java.io.IOException.class, () -> ObserverInstance.open(file));
        assertEquals("{}", Files.readString(file));
        Files.delete(file);
        assertThrows(java.io.IOException.class, () -> observer.beginRun(null));
        assertThrows(java.io.IOException.class, () -> ObserverInstance.open(file));
        Files.createSymbolicLink(file, temporary.resolve("elsewhere"));
        assertThrows(java.io.IOException.class, () -> ObserverInstance.open(file));
    }
}
