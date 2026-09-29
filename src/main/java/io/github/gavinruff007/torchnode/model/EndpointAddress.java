package io.github.gavinruff007.torchnode.model;

import java.net.*;

/** Numeric literals only: validation never performs DNS. Explicit mapped IPv6 remains IPv6; runtime IPv4 literals remain IPv4. */
public final class EndpointAddress {
    public static InetAddress parse(String address) {
        if (address == null || address.isEmpty()) throw new IllegalArgumentException("Missing IP literal");
        try {
            if (address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                String[] parts = address.split("\\.");
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int value = Integer.parseInt(parts[i]);
                    if (value > 255) throw new IllegalArgumentException("Invalid IPv4 literal");
                    bytes[i] = (byte)value;
                }
                return InetAddress.getByAddress(bytes);
            }
            if (address.indexOf(':') >= 0 && address.matches("[0-9a-fA-F:.]+")) {
                InetAddress parsed = InetAddress.getByName(address);
                if (parsed instanceof Inet4Address) {
                    byte[] mapped = new byte[16]; mapped[10] = mapped[11] = (byte)255;
                    System.arraycopy(parsed.getAddress(), 0, mapped, 12, 4);
                    return Inet6Address.getByAddress(null, mapped, -1);
                }
                return parsed;
            }
        } catch (UnknownHostException e) { throw new IllegalArgumentException("Invalid IP literal", e); }
        throw new IllegalArgumentException("Invalid IP literal");
    }
    public static NodeEndpoint.AddressFamily family(String address) {
        return parse(address) instanceof Inet6Address ? NodeEndpoint.AddressFamily.IPV6 : NodeEndpoint.AddressFamily.IPV4;
    }
    public static String hostPort(String address, int port) {
        if (port < 0 || port > 65535) throw new IllegalArgumentException("Invalid port");
        InetAddress parsed = parse(address);
        String host = parsed instanceof Inet6Address ? "[" + address + "]" : parsed.getHostAddress();
        return host + ":" + port;
    }
    public static boolean activeTarget(String address) {
        InetAddress parsed = parse(address);
        return !parsed.isAnyLocalAddress() && !parsed.isMulticastAddress();
    }
    public static String http(String address, int port) { return "http://" + hostPort(address, port); }
    public static InetSocketAddress socket(String address, int port) { return new InetSocketAddress(parse(address), port); }
    public static InetSocketAddress parseHostPort(String text) {
        try {
            URI uri = new URI("udp://" + text);
            if (uri.getHost() == null || uri.getPort() < 1 || uri.getRawUserInfo() != null ||
                    !uri.getRawPath().isEmpty() || uri.getRawQuery() != null || uri.getRawFragment() != null)
                throw new IllegalArgumentException("Invalid endpoint");
            String host = uri.getHost();
            if (host.startsWith("[")) host = host.substring(1, host.length() - 1);
            return socket(host, uri.getPort());
        } catch (URISyntaxException e) { throw new IllegalArgumentException("Invalid endpoint", e); }
    }
    private EndpointAddress() {}
}
