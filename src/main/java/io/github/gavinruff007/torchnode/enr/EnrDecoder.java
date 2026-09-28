package io.github.gavinruff007.torchnode.enr;

import io.github.gavinruff007.torchnode.model.*;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Sign;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** EIP-778 v4 record validation. No DNS, network access or endpoint selection occurs here. */
public final class EnrDecoder {
    public static final int MAX_RECORD_BYTES = 300;
    private static final Set<String> KNOWN = Set.of("id", "secp256k1", "ip", "tcp", "udp", "ip6", "tcp6", "udp6", "eth");
    private static final ECDomainParameters CURVE = new ECDomainParameters(Sign.CURVE_PARAMS.getCurve(),
            Sign.CURVE_PARAMS.getG(), Sign.CURVE_PARAMS.getN(), Sign.CURVE_PARAMS.getH());
    private static final HexFormat HEX = HexFormat.of();
    public EnrEvidence decode(byte[] raw, NodeIdentity expected, Instant observedAt, String provenance) {
        boolean decoded = false;
        String outcome = "INVALID_RLP";
        EnrRecord record = null;
        try {
            if (raw.length > MAX_RECORD_BYTES) throw new Bad("OVERSIZED_RECORD", "ENR exceeds 300 bytes");
            var root = CanonicalRlp.decode(raw, MAX_RECORD_BYTES);
            decoded = true;
            if (!root.list() || root.children().size() < 4 || root.children().size() % 2 != 0)
                throw new Bad("INVALID_STRUCTURE", "Expected [signature, seq, key, value, ...]");
            List<CanonicalRlp.Value> values = root.children();
            byte[] signature = values.get(0).string();
            String sequence;
            try { sequence = values.get(1).uint(8).toString(); }
            catch (IllegalArgumentException e) { throw new Bad("INVALID_SEQUENCE", e.getMessage()); }
            Map<String, CanonicalRlp.Value> fields = new LinkedHashMap<>();
            List<EnrRecord.Entry> entries = new ArrayList<>();
            byte[] previous = null;
            for (int i = 2; i < values.size(); i += 2) {
                byte[] key = values.get(i).string();
                if (previous != null && Arrays.compareUnsigned(previous, key) >= 0)
                    throw new Bad(Arrays.equals(previous, key) ? "DUPLICATE_KEY" : "UNORDERED_KEYS", "ENR keys must be unique and sorted");
                previous = key;
                String text = printable(key);
                boolean known = text != null && KNOWN.contains(text);
                entries.add(new EnrRecord.Entry(HEX.formatHex(key), text, HEX.formatHex(values.get(i + 1).encoded()), known));
                if (known) fields.put(text, values.get(i + 1));
            }
            if (!fields.containsKey("id")) throw new Bad("INVALID_STRUCTURE", "Missing identity scheme");
            String scheme = printable(fields.get("id").string());
            if (scheme == null) scheme = "0x" + HEX.formatHex(fields.get("id").string());
            outcome = "INVALID_FIELD";
            String ip = ip(fields, "ip", 4), ip6 = ip(fields, "ip6", 16);
            Integer tcp = port(fields, "tcp"), udp = port(fields, "udp");
            Integer tcp6 = port(fields, "tcp6"), udp6 = port(fields, "udp6");
            EnrRecord.ForkId eth = null;
            if (fields.containsKey("eth")) {
                var entry = fields.get("eth");
                if (!entry.list() || entry.children().isEmpty()) throw new Bad("INVALID_ETH_ENTRY", "eth must contain a fork ID list");
                var fork = entry.children().get(0);
                if (!fork.list() || fork.children().size() != 2 || fork.children().get(0).string().length != 4)
                    throw new Bad("INVALID_ETH_ENTRY", "Fork ID must contain a 4-byte hash and uint64 next");
                try { eth = new EnrRecord.ForkId("0x" + HEX.formatHex(fork.children().get(0).string()), fork.children().get(1).uint(8).toString()); }
                catch (IllegalArgumentException e) { throw new Bad("INVALID_ETH_ENTRY", e.getMessage()); }
                // Additional eth entry elements are retained verbatim and ignored per the entry specification.
            }
            List<NodeEndpoint> endpoints = new ArrayList<>();
            endpoints(endpoints, ip, tcp, udp, NodeEndpoint.AddressFamily.IPV4);
            // EIP-778 explicitly makes tcp/udp apply to IPv6 when tcp6/udp6 are absent.
            endpoints(endpoints, ip6, tcp6 != null ? tcp6 : tcp, udp6 != null ? udp6 : udp, NodeEndpoint.AddressFamily.IPV6);
            NodeIdentity identity = new NodeIdentity("");
            String publicKey = null, nodeAddress = null;
            org.bouncycastle.math.ec.ECPoint point = null;
            if ("v4".equals(scheme)) {
                if (signature.length != 64) throw new Bad("INVALID_SIGNATURE_LENGTH", "v4 signature must contain 64 bytes");
                if (!fields.containsKey("secp256k1")) throw new Bad("INVALID_PUBLIC_KEY", "Missing secp256k1 key");
                byte[] key = fields.get("secp256k1").string();
                if (key.length != 33 || (key[0] != 2 && key[0] != 3)) throw new Bad("INVALID_PUBLIC_KEY", "Expected compressed 33-byte secp256k1 key");
                try {
                    point = CURVE.getCurve().decodePoint(key).normalize();
                    if (point.isInfinity() || !point.isValid()) throw new IllegalArgumentException("Invalid curve point");
                } catch (IllegalArgumentException e) { throw new Bad("INVALID_PUBLIC_KEY", e.getMessage()); }
                byte[] uncompressed = Arrays.copyOfRange(point.getEncoded(false), 1, 65);
                identity = new NodeIdentity(HEX.formatHex(uncompressed));
                publicKey = "0x" + HEX.formatHex(key);
                nodeAddress = "0x" + HEX.formatHex(Hash.sha3(uncompressed));
            }
            record = new EnrRecord("enr:" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw), sequence,
                    scheme, identity, publicKey, nodeAddress, new EnrRecord.Fields(ip, tcp, udp, ip6, tcp6, udp6, eth), entries, endpoints);
            if (!"v4".equals(scheme)) return evidence(expected, observedAt, provenance, "UNSUPPORTED_IDENTITY_SCHEME", "Only the v4 identity scheme is supported",
                    decoded, true, EnrEvidence.Signature.NOT_TESTED, EnrEvidence.IdentityComparison.NOT_AVAILABLE, raw, record);
            byte[] content = CanonicalRlp.list(values.subList(1, values.size()).stream().map(CanonicalRlp.Value::encoded).toList());
            BigInteger r = new BigInteger(1, Arrays.copyOfRange(signature, 0, 32));
            BigInteger s = new BigInteger(1, Arrays.copyOfRange(signature, 32, 64));
            ECDSASigner verifier = new ECDSASigner();
            verifier.init(false, new ECPublicKeyParameters(point, CURVE));
            boolean valid = r.signum() > 0 && r.compareTo(CURVE.getN()) < 0 && s.signum() > 0
                    && s.compareTo(CURVE.getN().shiftRight(1)) <= 0 && verifier.verifySignature(Hash.sha3(content), r, s);
            EnrEvidence.IdentityComparison comparison = !expected.available() ? EnrEvidence.IdentityComparison.NOT_AVAILABLE
                    : expected.equals(identity) ? EnrEvidence.IdentityComparison.MATCH : EnrEvidence.IdentityComparison.MISMATCH;
            return evidence(expected, observedAt, provenance, !valid ? "INVALID_SIGNATURE" : comparison == EnrEvidence.IdentityComparison.MISMATCH ? "IDENTITY_MISMATCH" : "VALID",
                    !valid ? "ENR signature verification failed" : comparison == EnrEvidence.IdentityComparison.MISMATCH ? "ENR public key differs from discovered identity" : null,
                    true, true, valid ? EnrEvidence.Signature.VALID : EnrEvidence.Signature.INVALID, comparison, raw, record);
        } catch (Bad e) { outcome = e.code; return evidence(expected, observedAt, provenance, outcome, e.getMessage(), decoded, false,
                EnrEvidence.Signature.NOT_TESTED, EnrEvidence.IdentityComparison.NOT_AVAILABLE, raw, record); }
        catch (RuntimeException e) { return evidence(expected, observedAt, provenance, outcome, e.getMessage(), decoded, false,
                EnrEvidence.Signature.NOT_TESTED, EnrEvidence.IdentityComparison.NOT_AVAILABLE, raw, record); }
    }
    private static EnrEvidence evidence(NodeIdentity expected, Instant time, String provenance, String outcome, String detail,
                                        boolean decoded, boolean structure, EnrEvidence.Signature signature,
                                        EnrEvidence.IdentityComparison comparison, byte[] raw, EnrRecord record) {
        return new EnrEvidence(expected, time, provenance, outcome, detail, true, decoded, structure, signature, comparison,
                raw.length <= Discv4Packet.MAX_BYTES ? HEX.formatHex(raw) : null, record);
    }
    private static String printable(byte[] bytes) {
        for (byte b : bytes) if ((b & 255) < 32 || (b & 255) > 126) return null;
        return new String(bytes, StandardCharsets.US_ASCII);
    }
    private static String ip(Map<String, CanonicalRlp.Value> fields, String key, int length) throws Bad {
        if (!fields.containsKey(key)) return null;
        byte[] value = fields.get(key).string();
        if (value.length != length) throw new Bad("INVALID_FIELD", key + " has invalid address length");
        try { return (length == 16 ? java.net.Inet6Address.getByAddress(null, value, -1) : InetAddress.getByAddress(value)).getHostAddress(); }
        catch (Exception e) { throw new Bad("INVALID_FIELD", e.getMessage()); }
    }
    private static Integer port(Map<String, CanonicalRlp.Value> fields, String key) {
        return fields.containsKey(key) ? fields.get(key).uint(2).intValueExact() : null;
    }
    private static void endpoints(List<NodeEndpoint> list, String ip, Integer tcp, Integer udp, NodeEndpoint.AddressFamily family) {
        if (ip == null) return;
        if (tcp != null) list.add(new NodeEndpoint(ip, NodeEndpoint.Transport.TCP, tcp, family, NodeEndpoint.Purpose.P2P));
        if (udp != null) list.add(new NodeEndpoint(ip, NodeEndpoint.Transport.UDP, udp, family, NodeEndpoint.Purpose.DISCOVERY));
    }
    private static final class Bad extends Exception {
        final String code;
        Bad(String code, String message) { super(message); this.code = code; }
    }
}
