package io.github.gavinruff007.torchnode.discovery;

import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.utils.Numeric;

import java.math.BigInteger;

public class NodeIdentity {
    private ECKeyPair keyPair;
    private String nodeId;

    public NodeIdentity() throws Exception {
        this.keyPair = Keys.createEcKeyPair();
        BigInteger publicKey = keyPair.getPublicKey();

        byte[] pubKeyBytes = Numeric.toBytesPadded(publicKey, 64);

        if (pubKeyBytes.length == 65 && pubKeyBytes[0] == 0) {
            byte[] temp = new byte[64];
            System.arraycopy(pubKeyBytes, 1, temp, 0, 64);
            pubKeyBytes = temp;
        }

        this.nodeId = Numeric.toHexStringNoPrefix(pubKeyBytes);

        System.out.println("NodeID: " + nodeId.substring(0, 20) + "...");
    }

    public ECKeyPair getKeyPair() {
        return keyPair;
    }

    public String getNodeId() {
        return nodeId;
    }

    public byte[] getPublicKey() {
        BigInteger publicKey = keyPair.getPublicKey();
        byte[] pubKeyBytes = Numeric.toBytesPadded(publicKey, 64);

        if (pubKeyBytes.length == 65 && pubKeyBytes[0] == 0) {
            byte[] temp = new byte[64];
            System.arraycopy(pubKeyBytes, 1, temp, 0, 64);
            return temp;
        }

        return pubKeyBytes;
    }

}
