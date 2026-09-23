package com.test.blog.tools.service;

public class DecryptionException extends RuntimeException {
    public DecryptionException() {
        super("Decryption failed");
    }
}
