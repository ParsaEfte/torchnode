package io.github.gavinruff007.torchnode.enr;

import org.web3j.crypto.*;
import org.web3j.utils.Numeric;
import io.github.gavinruff007.torchnode.model.NodeIdentity;
import java.util.*;

/** Signed discv4 framing used only by ENR acquisition. Existing crawl packet handling is unchanged. */
public final class Discv4Packet {
    public static final int MAX_BYTES = 1280;
    public record Packet(int type, NodeIdentity signer, byte[] hash, CanonicalRlp.Value payload) {
        public Packet { hash = hash.clone(); }
        @Override public byte[] hash() { return hash.clone(); }
    }
    public static byte[] encode(int type, byte[] payload, ECKeyPair key) {
        if (payload.length + 98 > MAX_BYTES) throw new IllegalArgumentException("Discovery packet exceeds 1280 bytes");
        byte[] body = new byte[payload.length + 1]; body[0] = (byte)type;
        System.arraycopy(payload, 0, body, 1, payload.length);
        var signature = Sign.signMessage(Hash.sha3(body), key, false);
        byte[] packet = new byte[98 + payload.length];
        System.arraycopy(signature.getR(), 0, packet, 32, 32);
        System.arraycopy(signature.getS(), 0, packet, 64, 32);
        packet[96] = (byte)(signature.getV()[0] - 27);
        System.arraycopy(body, 0, packet, 97, body.length);
        System.arraycopy(Hash.sha3(Arrays.copyOfRange(packet, 32, packet.length)), 0, packet, 0, 32);
        return packet;
    }
    public static Packet decode(byte[] raw) {
        if (raw.length < 99 || raw.length > MAX_BYTES) throw new IllegalArgumentException("Invalid discovery packet size");
        byte[] hash = Arrays.copyOf(raw, 32);
        if (!java.security.MessageDigest.isEqual(hash, Hash.sha3(Arrays.copyOfRange(raw, 32, raw.length))))
            throw new IllegalArgumentException("Discovery packet hash mismatch");
        int recovery = raw[96] & 255;
        if (recovery > 1) throw new IllegalArgumentException("Invalid signature recovery ID");
        try {
            var signature = new Sign.SignatureData((byte)(recovery + 27), Arrays.copyOfRange(raw, 32, 64), Arrays.copyOfRange(raw, 64, 96));
            var key = Sign.signedMessageHashToKey(Hash.sha3(Arrays.copyOfRange(raw, 97, raw.length)), signature);
            var payload = CanonicalRlp.prefix(Arrays.copyOfRange(raw, 98, raw.length), MAX_BYTES - 98);
            if (!payload.list()) throw new IllegalArgumentException("Discovery payload must be a list");
            return new Packet(raw[97] & 255, new NodeIdentity(Numeric.toHexStringNoPrefixZeroPadded(key, 128)), hash, payload);
        } catch (Exception e) { throw new IllegalArgumentException("Invalid signed discovery packet: " + e.getMessage(), e); }
    }
    private Discv4Packet() {}
}
