package com.test.blog.tools.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Service;

@Service
public class PasswordService {
    private static final String LETTERS = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ";
    private static final String DIGITS = "123456789";
    private static final String SYMBOLS = "!@#$%^&*(){}[].?_";
    private static final String[] CHARACTER_GROUPS = {LETTERS, DIGITS, SYMBOLS};
    private final SecureRandom random = new SecureRandom();

    public String generate(int length, int quantity) {
        if (length < 0 || length > 128 || quantity < 1 || quantity > 100) {
            throw new IllegalArgumentException("Invalid password parameters");
        }
        StringBuilder result = new StringBuilder(((length == 0 ? 10 : length) + 1) * quantity);
        for (int i = 0; i < quantity; i++) {
            int passwordLength = length == 0 ? random.nextInt(3) + 8 : length;
            for (int position = 0; position < passwordLength; position++) {
                String alphabet = length == 0
                        ? (position % 2 == 0 ? LETTERS : DIGITS)
                        : CHARACTER_GROUPS[random.nextInt(CHARACTER_GROUPS.length)];
                result.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            result.append('\n');
        }
        return result.toString();
    }
}
