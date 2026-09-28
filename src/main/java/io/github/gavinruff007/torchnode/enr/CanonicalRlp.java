package io.github.gavinruff007.torchnode.enr;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.*;

/** Canonical RLP with bounded input, work and nesting, retaining exact encoded unknown values. */
public final class CanonicalRlp {
    public record Value(boolean list, byte[] bytes, List<Value> children, byte[] encoded) {
        public Value { bytes = bytes.clone(); children = List.copyOf(children); encoded = encoded.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
        @Override public byte[] encoded() { return encoded.clone(); }
        public byte[] string() {
            if (list) throw new IllegalArgumentException("Expected RLP byte string");
            return bytes();
        }
        public BigInteger uint(int maxBytes) {
            byte[] b = string();
            if (b.length > maxBytes || (b.length > 0 && b[0] == 0))
                throw new IllegalArgumentException("Non-canonical or overflowing unsigned integer");
            return b.length == 0 ? BigInteger.ZERO : new BigInteger(1, b);
        }
    }
    private final byte[] input;
    private int position;
    private CanonicalRlp(byte[] input, int limit) {
        if (input.length == 0 || input.length > limit) throw new IllegalArgumentException("RLP size limit");
        this.input = input.clone();
    }
    public static Value decode(byte[] bytes, int limit) {
        CanonicalRlp parser = new CanonicalRlp(bytes, limit);
        Value result = parser.value(0, bytes.length);
        if (parser.position != bytes.length) throw new IllegalArgumentException("Trailing RLP data");
        return result;
    }
    /** EIP-8 packet payloads allow data following the first RLP item. ENRs do not. */
    public static Value prefix(byte[] bytes, int limit) {
        CanonicalRlp parser = new CanonicalRlp(bytes, limit);
        return parser.value(0, bytes.length);
    }
    private Value value(int depth, int boundary) {
        if (depth > 32 || position >= boundary) throw new IllegalArgumentException("Truncated or deeply nested RLP");
        int start = position;
        int tag = input[position++] & 255;
        if (tag < 128) return new Value(false, new byte[]{(byte)tag}, List.of(), Arrays.copyOfRange(input, start, position));
        boolean list = tag >= 192;
        int shortBase = list ? 192 : 128;
        int longBase = list ? 247 : 183;
        int length;
        if (tag <= longBase) length = tag - shortBase;
        else {
            int lengthBytes = tag - longBase;
            if (lengthBytes > 4 || position + lengthBytes > boundary || input[position] == 0)
                throw new IllegalArgumentException("Invalid RLP length");
            long n = 0;
            for (int i = 0; i < lengthBytes; i++) n = (n << 8) | (input[position++] & 255);
            if (n < 56 || n > boundary - position) throw new IllegalArgumentException("Non-canonical RLP length");
            length = (int)n;
        }
        if (length > boundary - position) throw new IllegalArgumentException("Truncated RLP payload");
        int end = position + length;
        byte[] bytes = new byte[0];
        List<Value> children = new ArrayList<>();
        if (list) while (position < end) children.add(value(depth + 1, end));
        else {
            if (length == 1 && (input[position] & 255) < 128) throw new IllegalArgumentException("Non-canonical RLP string");
            bytes = Arrays.copyOfRange(input, position, end);
            position = end;
        }
        return new Value(list, bytes, children, Arrays.copyOfRange(input, start, end));
    }
    public static byte[] list(List<byte[]> items) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        items.forEach(payload::writeBytes);
        byte[] b = payload.toByteArray();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        if (b.length < 56) result.write(192 + b.length);
        else {
            byte[] length = BigInteger.valueOf(b.length).toByteArray();
            if (length[0] == 0) length = Arrays.copyOfRange(length, 1, length.length);
            result.write(247 + length.length); result.writeBytes(length);
        }
        result.writeBytes(b);
        return result.toByteArray();
    }
}
