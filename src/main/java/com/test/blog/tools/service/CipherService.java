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
 * 保留旧 Java 8 在 UTF-8 环境下的密文格式，不提供消息认证或完整的篡改检测。
 * 格式为五位数字前缀与原文经 AES-128/ECB/PKCS5Padding 加密，再按 76 列 Base64 折行。
 * 密钥、输入及输出仅用于计算，禁止添加包含这些内容的日志。
 */
@Service
public class CipherService {
    // 上限按 UTF-8 字节计算；密文额度同时容纳加密膨胀和旧格式换行。
    public static final int MAX_KEY_BYTES = 1024;
    public static final int MAX_PLAINTEXT_BYTES = 64 * 1024;
    public static final int MAX_CIPHERTEXT_BYTES = 128 * 1024;
    private final SecureRandom random = new SecureRandom();

    /** 支持空明文，保留空白；随机前缀沿用旧格式，不是认证标签。 */
    public String encrypt(String key, String plaintext) {
        validateInput(key, plaintext, MAX_PLAINTEXT_BYTES);
        Cipher cipher = createCipher(Cipher.ENCRYPT_MODE, key);
        byte[] payload = (Integer.toString(random.nextInt(90000) + 10000) + plaintext)
                .getBytes(StandardCharsets.UTF_8);
        try {
            byte[] encrypted = cipher.doFinal(payload);
            String encoded = Base64.getMimeEncoder(76, new byte[]{'\n'}).encodeToString(encrypted);
            // 57 字节恰好编码成 76 列；旧编码器在最后一行满行时还会追加 LF。
            return encrypted.length % 57 == 0 ? encoded + "\n" : encoded;
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Legacy encryption unavailable", exception);
        }
    }

    /** 兼容 LF／CRLF 折行，解密失败统一抛出不含敏感上下文的业务异常。 */
    public String decrypt(String key, String ciphertext) {
        validateInput(key, ciphertext, MAX_CIPHERTEXT_BYTES);
        Cipher cipher = createCipher(Cipher.DECRYPT_MODE, key);
        try {
            // 只移除旧编码器允许的换行，其他非法 Base64 字符仍须拒绝。
            byte[] encrypted = Base64.getDecoder().decode(ciphertext.replace("\r", "").replace("\n", ""));
            byte[] decrypted = cipher.doFinal(encrypted);
            // 严格解码，不把错误字节替换成占位字符后冒充成功结果。
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

    /** 固定算法、提供者和编码以保持旧密钥派生结果；每次调用创建独立 Cipher。 */
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

    /** 先区分缺失输入，再检查字节额度；不通过截断密钥或正文来满足上限。 */
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
