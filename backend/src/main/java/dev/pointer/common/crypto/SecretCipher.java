package dev.pointer.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    SecretCipher(@Value("${pointer.encryption-key}") String base64Key) {
        var bytes = Base64.getDecoder().decode(base64Key);
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException("pointer.encryption-key must be 32 bytes encoded as base64");
        }
        this.key = new SecretKeySpec(bytes, "AES");
    }

    public byte[] encrypt(String plaintext) {
        try {
            var nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(NONCE_BYTES + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Encryption failed", exception);
        }
    }

    public String decrypt(byte[] payload) {
        try {
            var buffer = ByteBuffer.wrap(payload);
            var nonce = new byte[NONCE_BYTES];
            buffer.get(nonce);
            var ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            var cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Decryption failed", exception);
        }
    }
}
