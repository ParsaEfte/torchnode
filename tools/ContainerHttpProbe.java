import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Test-only HTTP client, mounted read-only; no HTTP client package in the runtime. */
public final class ContainerHttpProbe {
    static String read(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 65536) throw new IOException("Request limit");
        byte[] bytes = in.readNBytes(n);
        if (bytes.length != n) throw new EOFException();
        return new String(bytes, StandardCharsets.UTF_8);
    }
    public static void main(String[] ignored) throws Exception {
        var in = new DataInputStream(System.in);
        String path = read(in), method = read(in), body = read(in), cookie = read(in);
        if (!path.startsWith("/") || path.startsWith("//")) throw new IOException("Local path required");
        int port = Integer.parseInt(System.getenv().getOrDefault("TORCHNODE_PORT", "8080"));
        if (port < 1024 || port > 65535) throw new IOException("Invalid port");
        var c = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection(Proxy.NO_PROXY);
        c.setConnectTimeout(2000); c.setReadTimeout(10000); c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        if (!cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        if (!body.isEmpty()) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (var out = c.getOutputStream()) { out.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int status = c.getResponseCode();
        StringBuilder headers = new StringBuilder();
        c.getHeaderFields().forEach((k, values) -> { if (k != null) for (String v : values) headers.append(k).append(": ").append(v).append("\n"); });
        byte[] response;
        var stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (stream == null) response = new byte[0];
        else try (stream) { response = stream.readNBytes(2 * 1024 * 1024 + 1); }
        if (response.length > 2 * 1024 * 1024) throw new IOException("Response limit");
        var b64 = Base64.getEncoder();
        System.out.println(status);
        System.out.println(b64.encodeToString(headers.toString().getBytes(StandardCharsets.UTF_8)));
        System.out.println(b64.encodeToString(response)); c.disconnect();
    }
}
