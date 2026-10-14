package com.test.blog.tools.service;

/** 统一表示可检测的解密失败，不暴露密钥、密文或底层失败细节。 */
public class DecryptionException extends RuntimeException {
    public DecryptionException() {
        super("Decryption failed");
    }
}
