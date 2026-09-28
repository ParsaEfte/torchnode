package io.github.gavinruff007.torchnode.enr;

import io.github.gavinruff007.torchnode.model.*;
import org.junit.jupiter.api.Test;
import org.web3j.rlp.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EnrDecoderTest {
    private final EnrDecoder decoder = new EnrDecoder();
    private EnrEvidence decode(byte[] bytes) { return decoder.decode(bytes, EnrFixtures.ID, Instant.EPOCH, "fixture"); }
    @Test void authoritativeEip778VectorVerifiesAndDerivesCorrectNodeAddress() {
        String text = "enr:-IS4QHCYrYZbAKWCBRlAy5zzaDZXJBGkcnh4MHcBFZntXNFrdvJjX04jRzjzCBOonrkTfj499SZuOh8R33Ls8RRcy5wBgmlkgnY0gmlwhH8AAAGJc2VjcDI1NmsxoQPKY0yuDUmstAHYpMa2_oxVtw0RW_QAdpzBQA8yWM0xOIN1ZHCCdl8";
        byte[] bytes = Base64.getUrlDecoder().decode(text.substring(4));
        var evidence = decoder.decode(bytes, new NodeIdentity(""), Instant.EPOCH, "EIP-778 example");
        assertTrue(evidence.decoded()); assertTrue(evidence.structurallyValid());
        assertEquals(EnrEvidence.Signature.VALID, evidence.signature());
        assertEquals(EnrEvidence.IdentityComparison.NOT_AVAILABLE, evidence.identityComparison());
        assertEquals("0xa448f24c6d18e575453db13171562b71999873db5b286df957af199ec94617f7", evidence.record().nodeAddress());
        assertEquals("127.0.0.1", evidence.record().fields().ip()); assertEquals(30303, evidence.record().fields().udp());
        assertNull(evidence.record().fields().tcp()); assertEquals(text, evidence.record().text());
    }
    @Test void validatesSignatureIdentityEndpointsEthAndUnknownEntries() throws Exception {
        byte[] raw = EnrFixtures.complete(42, 30303);
        var e = decode(raw);
        assertTrue(e.usable()); assertEquals("VALID", e.outcome());
        assertEquals("42", e.record().sequence());
        assertEquals(EnrFixtures.ID, e.record().identity());
        assertEquals(EnrEvidence.IdentityComparison.MATCH, e.identityComparison());
        assertEquals(HexFormat.of().formatHex(raw), e.rawRlpHex());
        assertArrayEquals(raw, Base64.getUrlDecoder().decode(e.record().text().substring(4)));
        assertEquals(30303, e.record().fields().tcp()); assertEquals(30305, e.record().fields().udp());
        assertEquals(30306, e.record().fields().tcp6()); assertEquals(30307, e.record().fields().udp6());
        assertTrue(e.record().fields().ip6().contains("2001:db8"));
        assertEquals(new EnrRecord.ForkId("0x01020304", "1234"), e.record().fields().eth());
        assertEquals(4, e.observation().orElseThrow().endpoints().size());
        var unknown = e.record().entries().stream().filter(entry -> !entry.known()).findFirst().orElseThrow();
        assertEquals("vendor", unknown.keyText()); assertTrue(unknown.valueRlpHex().startsWith("c"));
        assertEquals(Instant.EPOCH, e.observation().orElseThrow().observedAt());
    }
    @Test void invalidSignatureIsDecodableButCannotProduceEndpointEvidence() throws Exception {
        byte[] raw = EnrFixtures.complete(1, 30303);
        raw[5] ^= 1;
        var e = decode(raw);
        assertTrue(e.decoded()); assertTrue(e.structurallyValid());
        assertEquals(EnrEvidence.Signature.INVALID, e.signature()); assertEquals("INVALID_SIGNATURE", e.outcome());
        assertTrue(e.observation().isEmpty()); assertNotNull(e.record());
    }
    @Test void validMismatchedRecordDoesNotReplaceAssociatedDiscoveryIdentity() throws Exception {
        var expected = new NodeIdentity("cd".repeat(64));
        var e = decoder.decode(EnrFixtures.complete(1, 30303), expected, Instant.EPOCH, "fixture");
        assertEquals(EnrEvidence.Signature.VALID, e.signature());
        assertEquals(EnrEvidence.IdentityComparison.MISMATCH, e.identityComparison());
        assertEquals(expected, e.associatedIdentity()); assertEquals(EnrFixtures.ID, e.record().identity());
        assertEquals("IDENTITY_MISMATCH", e.outcome()); assertTrue(e.observation().isEmpty());
    }
    @Test void supportsMissingOptionalFieldsAndFullUint64Sequence() {
        var seq = RlpString.create(new byte[]{(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255,(byte)255});
        var e = decode(EnrFixtures.signed(seq, EnrFixtures.fields()));
        assertTrue(e.usable()); assertEquals("18446744073709551615", e.record().sequence());
        assertTrue(e.record().endpoints().isEmpty()); assertNull(e.record().fields().ip());
    }
    @Test void ipv6PortFallbackFollowsEip778WithoutCreatingIpv4Endpoint() throws Exception {
        var fields = EnrFixtures.fields();
        fields.put("ip6", RlpString.create(java.net.InetAddress.getByName("2001:db8::1").getAddress()));
        fields.put("tcp", RlpString.create(30303));
        var e = decode(EnrFixtures.signed(1, fields));
        assertTrue(e.usable()); assertNull(e.record().fields().tcp6());
        assertEquals(1, e.record().endpoints().size());
        assertEquals(NodeEndpoint.AddressFamily.IPV6, e.record().endpoints().get(0).addressFamily());
        assertEquals(30303, e.record().endpoints().get(0).port());
    }
    @Test void rejectsMalformedTruncatedTrailingNoncanonicalAndOversizedRlp() throws Exception {
        byte[] valid = EnrFixtures.complete(1, 30303);
        for (byte[] raw : List.of(new byte[]{(byte)0xff}, Arrays.copyOf(valid, valid.length - 1),
                new byte[]{(byte)0x81, 1}, new byte[]{(byte)0xf8, 1, (byte)0x80}, Arrays.copyOf(valid, valid.length + 1))) {
            var e = decode(raw); assertFalse(e.usable()); assertEquals("INVALID_RLP", e.outcome());
        }
        assertEquals("OVERSIZED_RECORD", decode(new byte[301]).outcome());
        assertNull(decode(new byte[5000]).rawRlpHex());
    }
    @Test void rejectsInvalidIntegerSequenceEncodings() {
        for (byte[] seq : List.of(new byte[]{0}, new byte[]{0,1}, new byte[9]))
            assertEquals("INVALID_SEQUENCE", decode(EnrFixtures.signed(RlpString.create(seq), EnrFixtures.fields())).outcome());
    }
    @Test void rejectsDuplicateAndUnorderedKeys() {
        var fields = EnrFixtures.fields();
        List<RlpType> content = new ArrayList<>(); content.add(RlpString.create(1));
        fields.forEach((k,v) -> {content.add(RlpString.create(k));content.add(v);});
        content.add(RlpString.create("secp256k1")); content.add(fields.get("secp256k1"));
        assertEquals("DUPLICATE_KEY", decode(EnrFixtures.signedContent(content)).outcome());
        content.set(2, fields.get("secp256k1")); content.set(1, RlpString.create("secp256k1"));
        content.set(3, RlpString.create("id")); content.set(4, RlpString.create("v4"));
        assertEquals("UNORDERED_KEYS", decode(EnrFixtures.signedContent(content)).outcome());
    }
    @Test void unsupportedSchemeRemainsDecodedButNotCryptographicallyTested() {
        var fields = EnrFixtures.fields(); fields.put("id", RlpString.create("other"));
        var e = decode(EnrFixtures.signed(1, fields));
        assertEquals("UNSUPPORTED_IDENTITY_SCHEME", e.outcome()); assertTrue(e.structurallyValid());
        assertEquals(EnrEvidence.Signature.NOT_TESTED, e.signature()); assertTrue(e.observation().isEmpty());
    }
    @Test void rejectsBadPublicKeysAddressesPortsAndEthEntry() {
        for (byte[] key : List.of(new byte[33], new byte[]{2}, new byte[32])) {
            var fields = EnrFixtures.fields(); fields.put("secp256k1", RlpString.create(key));
            assertEquals("INVALID_PUBLIC_KEY", decode(EnrFixtures.signed(1, fields)).outcome());
        }
        for (String name : List.of("ip", "ip6", "tcp", "udp6")) {
            var fields = EnrFixtures.fields(); fields.put(name, RlpString.create(new byte[]{1,2,3}));
            assertEquals("INVALID_FIELD", decode(EnrFixtures.signed(1, fields)).outcome());
        }
        var fields = EnrFixtures.fields(); fields.put("eth", new RlpList(new RlpList(RlpString.create(new byte[3]), RlpString.create(1))));
        assertEquals("INVALID_ETH_ENTRY", decode(EnrFixtures.signed(1, fields)).outcome());
    }
    @Test void binaryUnknownKeysAndNestedValuesArePreserved() {
        var fields = EnrFixtures.fields();
        List<RlpType> content = new ArrayList<>(); content.add(RlpString.create(1));
        content.add(RlpString.create(new byte[]{0})); content.add(new RlpList(RlpString.create(new byte[]{(byte)255})));
        fields.forEach((k,v) -> {content.add(RlpString.create(k)); content.add(v);});
        var e = decode(EnrFixtures.signedContent(content));
        assertTrue(e.usable()); assertEquals("00", e.record().entries().get(0).keyHex());
        assertNull(e.record().entries().get(0).keyText()); assertEquals("c281ff", e.record().entries().get(0).valueRlpHex());
    }
    @Test void hostileInputCannotEscapeDecoderOrExhaustStack() {
        Random random = new Random(17);
        for (int i = 0; i < 1000; i++) { byte[] raw = new byte[random.nextInt(600)]; random.nextBytes(raw); assertDoesNotThrow(() -> decode(raw)); }
        byte[] nested = new byte[]{(byte)128};
        for (int i = 0; i < 40; i++) nested = CanonicalRlp.list(List.of(nested));
        assertEquals("INVALID_RLP", decode(nested).outcome());
    }
    @Test void rejectsHighSAndOffCurveKeys() {
        byte[] raw=EnrFixtures.signed(1,EnrFixtures.fields());
        var values=CanonicalRlp.decode(raw,300).children();
        byte[] signature=values.get(0).string();
        var high=org.web3j.crypto.Sign.CURVE_PARAMS.getN().subtract(new java.math.BigInteger(1,Arrays.copyOfRange(signature,32,64)));
        System.arraycopy(org.web3j.utils.Numeric.toBytesPadded(high,32),0,signature,32,32);
        var encoded=new ArrayList<byte[]>();encoded.add(RlpEncoder.encode(RlpString.create(signature)));
        values.subList(1,values.size()).forEach(v->encoded.add(v.encoded()));
        assertEquals("INVALID_SIGNATURE",decode(CanonicalRlp.list(encoded)).outcome());
        var fields=EnrFixtures.fields();byte[] bad=new byte[33];Arrays.fill(bad,(byte)255);bad[0]=2;
        fields.put("secp256k1",RlpString.create(bad));
        assertEquals("INVALID_PUBLIC_KEY",decode(EnrFixtures.signed(1,fields)).outcome());
    }
}
