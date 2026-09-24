package io.github.gavinruff007.torchnode.model;

import java.util.regex.Pattern;

/** Normalizes discv4 64-byte public keys, including rows written by the old scanner. */
public final class NodeIds {
    private static final Pattern HEX = Pattern.compile("(?i)[0-9a-f]{128}");

    private NodeIds() { }

    public static String normalize(String value) {
        if (value == null) return "";
        String input = value.trim();
        if (input.startsWith("0x") || input.startsWith("0X")) input = input.substring(2);
        if (HEX.matcher(input).matches()) return input.toLowerCase();
        if (!input.startsWith("[") || !input.endsWith("]")) return "";

        String[] bytes = input.substring(1, input.length() - 1).split(",");
        if (bytes.length != 64) return "";
        StringBuilder hex = new StringBuilder(128);
        for (String part : bytes) {
            try {
                int signed = Integer.parseInt(part.trim());
                if (signed < -128 || signed > 127) return "";
                hex.append(Character.forDigit((signed & 0xff) >>> 4, 16));
                hex.append(Character.forDigit(signed & 0xf, 16));
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return hex.toString();
    }
}
