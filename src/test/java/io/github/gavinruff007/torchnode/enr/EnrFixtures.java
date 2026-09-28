package io.github.gavinruff007.torchnode.enr;

import org.web3j.crypto.*;
import org.web3j.rlp.*;
import io.github.gavinruff007.torchnode.model.NodeIdentity;
import java.math.BigInteger;
import java.net.InetAddress;
import java.util.*;

public final class EnrFixtures {
    public static final ECKeyPair KEY = ECKeyPair.create(BigInteger.ONE);
    public static final NodeIdentity ID = new NodeIdentity(org.web3j.utils.Numeric.toHexStringNoPrefixZeroPadded(KEY.getPublicKey(), 128));
    public static TreeMap<String, RlpType> fields() {
        TreeMap<String, RlpType> fields = new TreeMap<>();
        fields.put("id", RlpString.create("v4"));
        fields.put("secp256k1", RlpString.create(Sign.publicPointFromPrivate(KEY.getPrivateKey()).getEncoded(true)));
        return fields;
    }
    public static byte[] signed(long seq, Map<String, RlpType> fields) { return signed(RlpString.create(seq), fields); }
    public static byte[] signed(RlpType seq, Map<String, RlpType> fields) {
        List<RlpType> content = new ArrayList<>(); content.add(seq);
        fields.forEach((key, value) -> { content.add(RlpString.create(key)); content.add(value); });
        return signedContent(content);
    }
    public static byte[] signedContent(List<RlpType> content) {
        var sig = Sign.signMessage(Hash.sha3(RlpEncoder.encode(new RlpList(content))), KEY, false);
        byte[] bytes = new byte[64]; System.arraycopy(sig.getR(), 0, bytes, 0, 32); System.arraycopy(sig.getS(), 0, bytes, 32, 32);
        List<RlpType> record = new ArrayList<>(); record.add(RlpString.create(bytes)); record.addAll(content);
        return RlpEncoder.encode(new RlpList(record));
    }
    public static byte[] complete(long seq, int tcp) throws Exception {
        var fields = fields();
        fields.put("ip", RlpString.create(new byte[]{127,0,0,1}));
        fields.put("tcp", RlpString.create(tcp)); fields.put("udp", RlpString.create(30305));
        fields.put("ip6", RlpString.create(InetAddress.getByName("2001:db8::1").getAddress()));
        fields.put("tcp6", RlpString.create(30306)); fields.put("udp6", RlpString.create(30307));
        fields.put("eth", new RlpList(new RlpList(RlpString.create(new byte[]{1,2,3,4}), RlpString.create(1234)), RlpString.create("extension")));
        fields.put("vendor", new RlpList(RlpString.create(new byte[]{0,(byte)255}), RlpString.create("opaque")));
        return signed(seq, fields);
    }
}
