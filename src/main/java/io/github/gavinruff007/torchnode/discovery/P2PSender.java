package io.github.gavinruff007.torchnode.discovery;

import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public class P2PSender {

    private static String cachedPublicIP = null;

    public static void sendPing(NodeIdentity myNode, String targetIP, int targetPort, DatagramSocket socket) throws Exception {
        InetAddress targetAddr = InetAddress.getByName(targetIP);

        // Get public IP
        String publicIP = getPublicIP();
        byte[] fromIPBytes = InetAddress.getByName(publicIP).getAddress();

        // From endpoint (with public IP)
        List<RlpType> fromEndpoint = new ArrayList<>();
        fromEndpoint.add(RlpString.create(fromIPBytes));
        fromEndpoint.add(RlpString.create(socket.getLocalPort()));
        fromEndpoint.add(RlpString.create(socket.getLocalPort()));

        // To endpoint
        List<RlpType> toEndpoint = new ArrayList<>();
        toEndpoint.add(RlpString.create(targetAddr.getAddress()));
        toEndpoint.add(RlpString.create(targetPort));
        toEndpoint.add(RlpString.create(targetPort));

        long expirationTime = (System.currentTimeMillis() / 1000) + 60;

        List<RlpType> pingPayload = new ArrayList<>();
        pingPayload.add(RlpString.create(4));
        pingPayload.add(new RlpList(fromEndpoint));
        pingPayload.add(new RlpList(toEndpoint));
        pingPayload.add(RlpString.create(expirationTime));

        byte[] payload = RlpEncoder.encode(new RlpList(pingPayload));
        byte[] packet = buildPacket((byte) 0x01, payload, myNode.getKeyPair());

        DatagramPacket udpPacket = new DatagramPacket(packet, packet.length, targetAddr, targetPort);
        socket.send(udpPacket);

        System.out.println("Sending Ping to " + targetIP + ":" + targetPort + " (our public IP: " + publicIP + ")");
    }

    public static void sendPong(NodeIdentity myNode, String targetIP, int targetPort,
                                DatagramSocket socket, byte[] pingHash) throws Exception {
        InetAddress targetAddr = InetAddress.getByName(targetIP);

        // To endpoint
        List<RlpType> toEndpoint = new ArrayList<>();
        toEndpoint.add(RlpString.create(targetAddr.getAddress()));
        toEndpoint.add(RlpString.create(targetPort));
        toEndpoint.add(RlpString.create(targetPort));

        long expirationTime = (System.currentTimeMillis() / 1000) + 60;

        List<RlpType> pongPayload = new ArrayList<>();
        pongPayload.add(new RlpList(toEndpoint));
        pongPayload.add(RlpString.create(pingHash));
        pongPayload.add(RlpString.create(expirationTime));

        byte[] payload = RlpEncoder.encode(new RlpList(pongPayload));
        byte[] packet = buildPacket((byte) 0x02, payload, myNode.getKeyPair());

        DatagramPacket udpPacket = new DatagramPacket(packet, packet.length, targetAddr, targetPort);
        socket.send(udpPacket);

        System.out.println("✅ Pong sent to " + targetIP + ":" + targetPort);
    }

    private static byte[] buildPacket(byte packetType, byte[] payload, ECKeyPair keyPair) throws Exception {
        // Step 1: Type + Payload
        byte[] typeAndPayload = new byte[payload.length + 1];
        typeAndPayload[0] = packetType;
        System.arraycopy(payload, 0, typeAndPayload, 1, payload.length);

        // Step 2: Sign(Keccak256(Type + Payload))
        Keccak.Digest256 keccak = new Keccak.Digest256();
        byte[] toSign = keccak.digest(typeAndPayload);

        Sign.SignatureData sig = Sign.signMessage(toSign, keyPair, false);

        byte[] signatureBytes = new byte[65];
        System.arraycopy(sig.getR(), 0, signatureBytes, 0, 32);
        System.arraycopy(sig.getS(), 0, signatureBytes, 32, 32);
        signatureBytes[64] = (byte) (sig.getV()[0] - 27);

        // Step 3: Hash = Keccak256(Signature + Type + Payload)
        byte[] sigAndTypeAndPayload = new byte[65 + typeAndPayload.length];
        System.arraycopy(signatureBytes, 0, sigAndTypeAndPayload, 0, 65);
        System.arraycopy(typeAndPayload, 0, sigAndTypeAndPayload, 65, typeAndPayload.length);

        Keccak.Digest256 keccak2 = new Keccak.Digest256();
        byte[] hash = keccak2.digest(sigAndTypeAndPayload);

        // Step 4: Final packet = Hash + Signature + Type + Payload
        byte[] fullPacket = new byte[32 + 65 + typeAndPayload.length];
        System.arraycopy(hash, 0, fullPacket, 0, 32);
        System.arraycopy(signatureBytes, 0, fullPacket, 32, 65);
        System.arraycopy(typeAndPayload, 0, fullPacket, 97, typeAndPayload.length);

        return fullPacket;
    }

    private static String getPublicIP() throws Exception {
        if (cachedPublicIP != null) {
            return cachedPublicIP;
        }

        try {
            URL whatismyip = new URL("https://checkip.amazonaws.com");
            BufferedReader in = new BufferedReader(new InputStreamReader(whatismyip.openStream()));
            cachedPublicIP = in.readLine().trim();
            in.close();
            return cachedPublicIP;
        } catch (Exception e) {
            System.err.println("⚠️ Could not get public IP, using localhost: " + e.getMessage());
            cachedPublicIP = "127.0.0.1";
            return cachedPublicIP;
        }
    }
}
