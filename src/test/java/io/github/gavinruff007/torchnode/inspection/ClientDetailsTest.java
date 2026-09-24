package io.github.gavinruff007.torchnode.inspection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientDetailsTest {
    @Test
    void parsesCommonExecutionClientString() {
        ClientDetails details = ClientDetails.parse(
                "Geth/v1.17.2-stable-be4dc0c4/linux-amd64/go1.26.1");

        assertEquals("Geth", details.name());
        assertEquals("1.17.2-stable-be4dc0c4", details.version());
        assertEquals("linux-amd64", details.platform());
        assertEquals("go1.26.1", details.runtime());
    }
}
