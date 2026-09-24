package io.github.gavinruff007.torchnode.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeIdsTest {
    @Test
    void convertsLegacySignedByteArrayToHex() {
        String signed = "[" + "37, -96, 109, 0, ".repeat(15) + "37, -96, 109, 0]";
        assertEquals("25a06d00".repeat(16), NodeIds.normalize(signed));
    }

    @Test
    void hidesInvalidNodeIds() {
        assertEquals("", NodeIds.normalize("[37, 35, -96]"));
        assertEquals("", NodeIds.normalize("not-a-public-key"));
    }
}
