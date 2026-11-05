package dev.pointer.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class Tokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Tokens() {}

    public static String random(int bytes) {
        var buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    public static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static boolean matches(String value, String expectedHash) {
        return expectedHash != null
                && MessageDigest.isEqual(
                        sha256(value).getBytes(StandardCharsets.US_ASCII),
                        expectedHash.getBytes(StandardCharsets.US_ASCII));
    }
}
