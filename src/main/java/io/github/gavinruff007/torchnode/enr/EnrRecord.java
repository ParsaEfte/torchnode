package io.github.gavinruff007.torchnode.enr;

import io.github.gavinruff007.torchnode.model.NodeIdentity;
import io.github.gavinruff007.torchnode.model.NodeEndpoint;
import java.util.List;

/** Exact record bytes plus decoded advertised values; sequence/forkNext use unsigned decimal strings. */
public record EnrRecord(String text, String sequence, String identityScheme, NodeIdentity identity,
                        String compressedPublicKey, String nodeAddress, Fields fields,
                        List<Entry> entries, List<NodeEndpoint> endpoints) {
    public record ForkId(String forkHash, String forkNext) {}
    public record Fields(String ip, Integer tcp, Integer udp, String ip6, Integer tcp6, Integer udp6, ForkId eth) {}
    /** Values are their exact RLP encoding, including unknown lists. Binary keys remain lossless. */
    public record Entry(String keyHex, String keyText, String valueRlpHex, boolean known) {}
    public EnrRecord { entries = List.copyOf(entries); endpoints = List.copyOf(endpoints); }
}
