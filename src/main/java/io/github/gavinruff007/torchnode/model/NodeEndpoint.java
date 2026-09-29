package io.github.gavinruff007.torchnode.model;

import java.util.Objects;

/** An endpoint claim; reachability and advertised Hello ports remain separate evidence. */
public record NodeEndpoint(String address, Transport transport, int port,
                           AddressFamily addressFamily, Purpose purpose) {
    public enum Transport { UDP, TCP }
    public enum AddressFamily { IPV4, IPV6 }
    public enum Purpose { DISCOVERY, P2P }
    public NodeEndpoint {
        Objects.requireNonNull(address);
        Objects.requireNonNull(transport);
        Objects.requireNonNull(addressFamily);
        Objects.requireNonNull(purpose);
        address = EndpointAddress.parse(address).getHostAddress();
        if (EndpointAddress.family(address) != addressFamily) throw new IllegalArgumentException("Address family mismatch");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid endpoint port");
    }
    public String hostPort() { return EndpointAddress.hostPort(address, port); }
}
