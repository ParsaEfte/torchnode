package io.github.gavinruff007.torchnode.security;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** Argon2id v19, 19 MiB, two passes, one lane; unique 128-bit salt. */
public final class Passwords {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Passwords() {}
    public static String hash(String password) {
        if (password == null || password.length() < 12 || password.length() > 256)
            throw new IllegalArgumentException("Password must contain 12–256 characters");
        byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
        return "$argon2id$v=19$m=19456,t=2,p=1$" + encode(salt) + "$" + encode(derive(password, salt));
    }
    public static boolean verify(String password, String encoded) {
        if (password == null || password.length() > 256) return false;
        try {
            String[] parts = encoded.split("\\$");
            if (parts.length != 6 || !encoded.startsWith("$argon2id$v=19$m=19456,t=2,p=1$")) return false;
            byte[] salt = Base64.getDecoder().decode(parts[4]), expected = Base64.getDecoder().decode(parts[5]);
            return salt.length == 16 && expected.length == 32 && MessageDigest.isEqual(expected, derive(password, salt));
        } catch (RuntimeException e) { return false; }
    }
    private static String encode(byte[] bytes) { return Base64.getEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] derive(String password, byte[] salt) {
        byte[] input = password.getBytes(StandardCharsets.UTF_8), result = new byte[32];
        var generator = new Argon2BytesGenerator();
        generator.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(19)
                .withMemoryAsKB(19456).withIterations(2).withParallelism(1).withSalt(salt).build());
        try { generator.generateBytes(input, result); return result; }
        finally { Arrays.fill(input, (byte) 0); }
    }
}
