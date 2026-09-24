package io.github.gavinruff007.torchnode.discovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class P2PListenerTest {
    @Test
    void decodesDistinctUdpAndAdvertisedTcpPorts() {
        byte[] udpField = {(byte) 0x76, (byte) 0x5f}; // 30303
        byte[] tcpField = {(byte) 0x27, (byte) 0x0f}; // 9999
        assertEquals(30303, P2PListener.discoveryPort(udpField));
        assertEquals(9999, P2PListener.discoveryPort(tcpField));
    }

    @Test
    void rejectsOversizedPortField() {
        assertThrows(IllegalArgumentException.class,
                () -> P2PListener.discoveryPort(new byte[] {1, 2, 3}));
    }
}
