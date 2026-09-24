package io.github.gavinruff007.torchnode.inspection;

import io.github.gavinruff007.torchnode.model.NodeRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoEthereumP2pInspectorTest {
    @TempDir Path temp;

    @Test
    void discoversExecutableBesideApplicationAndExecutesIt() throws Exception {
        Path app = Files.createDirectory(temp.resolve("app"));
        Path working = Files.createDirectory(temp.resolve("work"));
        Path helper = app.resolve("torchnode-p2p-helper");
        String response = """
                {"tcp":{"state":"NOT_TESTED","reasonCode":"INVALID_REQUEST"},
                 "auth":{"state":"NOT_TESTED"},"hello":{"state":"NOT_TESTED"},
                 "status":{"state":"NOT_TESTED"}}
                """.trim();
        Files.writeString(helper, "#!/bin/sh\ncat >/dev/null\nprintf '%s' '" + response + "'\n");
        assertTrue(helper.toFile().setExecutable(true));

        GoEthereumP2pInspector inspector = new GoEthereumP2pInspector(null, app, working);
        assertEquals(helper, inspector.helperPath());
        assertTrue(inspector.available());
        P2pInspectionResult result = inspector.inspect(
                new NodeRecord("127.0.0.1", 30303, 30303, "ab".repeat(64))).orElseThrow();
        assertEquals(InspectionResult.State.NOT_TESTED, result.auth().state());
        assertEquals("INVALID_REQUEST", result.tcp().reasonCode());
    }

    @Test
    void explicitPathWinsAndInvalidExplicitPathDoesNotSilentlyFallBack() throws Exception {
        Path app = Files.createDirectory(temp.resolve("app"));
        Path working = Files.createDirectory(temp.resolve("work"));
        Path fallback = app.resolve("torchnode-p2p-helper");
        Files.writeString(fallback, "fixture");
        assertTrue(fallback.toFile().setExecutable(true));

        GoEthereumP2pInspector missing = new GoEthereumP2pInspector("missing-helper", app, working);
        assertEquals(working.resolve("missing-helper"), missing.helperPath());
        assertFalse(missing.available());
        assertEquals("Configured RLPx helper is not a regular file", missing.unavailableReason());

        GoEthereumP2pInspector explicit = new GoEthereumP2pInspector(fallback.toString(), app, working);
        assertEquals(fallback, explicit.helperPath());
        assertTrue(explicit.available());
    }

    @Test
    void findsProjectBuildPathWithoutEnvironmentVariable() throws Exception {
        Path app = Files.createDirectory(temp.resolve("app"));
        Path working = Files.createDirectory(temp.resolve("work"));
        Path target = Files.createDirectory(working.resolve("target"));
        Path helper = target.resolve("torchnode-p2p-helper");
        Files.writeString(helper, "fixture");
        assertTrue(helper.toFile().setExecutable(true));
        assertEquals(helper, GoEthereumP2pInspector.resolveHelper(null, app, working));
    }
}
