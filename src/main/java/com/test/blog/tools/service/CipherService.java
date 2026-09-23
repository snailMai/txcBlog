package com.test.blog.tools.service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;

import org.springframework.stereotype.Service;

/**
 * Preserves the existing UTF-8 Java 8 wire format; this is not a new authenticated cipher protocol.
 * Format: AES-128/ECB/PKCS5Padding over a five-digit prefix and text, then 76-column Base64.
 */
@Service
public class CipherService {
    public static final int MAX_KEY_BYTES = 1024;
    public static final int MAX_PLAINTEXT_BYTES = 64 * 1024;
    public static final int MAX_CIPHERTEXT_BYTES = 128 * 1024;
    private final SecureRandom random = new SecureRandom();

    public String encrypt(String key, String plaintext) {
        validateInput(key, plaintext, MAX_PLAINTEXT_BYTES);
        Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, key);
        byte[] payload = (Integer.toString(random.nextInt(90000) + 10000) + plaintext)
                .getBytes(StandardCharsets.UTF_8);
        try {
            byte[] encrypted = cipher.doFinal(payload);
            String encoded = Base64.getMimeEncoder(76, new byte[]{'\n'}).encodeToString(encrypted);
            // The old BASE64Encoder also terminated a final full 57-byte input line.
            return encrypted.length % 57 == 0 ? encoded + "\n" : encoded;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Legacy encryption unavailable", exception);
        }
    }

    public String decrypt(String key, String ciphertext) {
        validateInput(key, ciphertext, MAX_CIPHERTEXT_BYTES);
        Cipher cipher = createCipher(Cipher.DECRYPT_MODE, key);
        try {
            // Only permit the line separators emitted by the legacy encoder, not arbitrary garbage.
            byte[] encrypted = Base64.getDecoder().decode(ciphertext.replace("\r", "").replace("\n", ""));
            byte[] decrypted = cipher.doFinal(encrypted);
            String payload = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(decrypted)).toString();
            if (payload.length() < 5 || !payload.substring(0, 5).matches("[1-9][0-9]{4}")) {
                throw new DecryptionException();
            }
            return payload.substring(5);
        } catch (IllegalArgumentException | BadPaddingException | IllegalBlockSizeException
                 | CharacterCodingException exception) {
            throw new DecryptionException();
        }
    }

    private Cipher createCipher(int mode, String key) {
        try {
            SecureRandom seeded = SecureRandom.getInstance("SHA1PRNG", "SUN");
            seeded.setSeed(key.getBytes(StandardCharsets.UTF_8));
            KeyGenerator generator = KeyGenerator.getInstance("AES", "SunJCE");
            generator.init(128, seeded);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding", "SunJCE");
            cipher.init(mode, generator.generateKey());
            return cipher;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Legacy cipher unavailable", exception);
        }
    }

    private void validateInput(String key, String text, int textLimit) {
        if (key == null || key.isEmpty() || text == null) {
            throw new IllegalArgumentException("Invalid cipher input");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES
                || text.getBytes(StandardCharsets.UTF_8).length > textLimit) {
            throw new InputTooLargeException();
        }
    }
}
