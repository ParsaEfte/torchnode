package io.github.gavinruff007.torchnode.discovery;

import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.bouncycastle.util.encoders.Hex;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class FindNodeSender {

    public static void sendFindNode(NodeIdentity myNode, String targetIP, int targetPort,
                                    DatagramSocket socket, byte[] targetNodeId) throws Exception {
        InetAddress targetAddr = InetAddress.getByName(targetIP);

        if (targetNodeId.length != 64) {
            throw new IllegalArgumentException("Target NodeID must be exactly 64 bytes, got: " + targetNodeId.length);
        }

        long expirationTime = (System.currentTimeMillis() / 1000) + 60;

        List<RlpType> findNodePayload = new ArrayList<>();
        findNodePayload.add(RlpString.create(targetNodeId));
        findNodePayload.add(RlpString.create(expirationTime));

        byte[] payload = RlpEncoder.encode(new RlpList(findNodePayload));
        byte[] packet = buildPacket((byte) 0x03, payload, myNode.getKeyPair());

        DatagramPacket udpPacket = new DatagramPacket(packet, packet.length, targetAddr, targetPort);
        socket.send(udpPacket);

        System.out.println("📤 FindNode sent to " + targetIP + ":" + targetPort);
        System.out.println("   Target: " + Hex.toHexString(targetNodeId));
        System.out.println("[DEBUG] FindNode packet hex: " + Hex.toHexString(packet));
    }

    private static byte[] buildPacket(byte packetType, byte[] payload, ECKeyPair keyPair) throws Exception {
        byte[] typeAndPayload = new byte[payload.length + 1];
        typeAndPayload[0] = packetType;
        System.arraycopy(payload, 0, typeAndPayload, 1, payload.length);

        Keccak.Digest256 keccak = new Keccak.Digest256();
        byte[] toSign = keccak.digest(typeAndPayload);

        Sign.SignatureData sig = Sign.signMessage(toSign, keyPair, false);

        byte[] signatureBytes = new byte[65];
        System.arraycopy(sig.getR(), 0, signatureBytes, 0, 32);
        System.arraycopy(sig.getS(), 0, signatureBytes, 32, 32);
        signatureBytes[64] = (byte) (sig.getV()[0] - 27);

        byte[] sigAndTypeAndPayload = new byte[65 + typeAndPayload.length];
        System.arraycopy(signatureBytes, 0, sigAndTypeAndPayload, 0, 65);
        System.arraycopy(typeAndPayload, 0, sigAndTypeAndPayload, 65, typeAndPayload.length);

        Keccak.Digest256 keccak2 = new Keccak.Digest256();
        byte[] hash = keccak2.digest(sigAndTypeAndPayload);

        byte[] fullPacket = new byte[32 + 65 + typeAndPayload.length];
        System.arraycopy(hash, 0, fullPacket, 0, 32);
        System.arraycopy(signatureBytes, 0, fullPacket, 32, 65);
        System.arraycopy(typeAndPayload, 0, fullPacket, 97, typeAndPayload.length);

        return fullPacket;
    }
}
