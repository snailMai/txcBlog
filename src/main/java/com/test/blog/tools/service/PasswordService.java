package com.test.blog.tools.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Service;

/** 使用 SecureRandom 生成临时密码结果，不存储或记录生成内容。 */
@Service
public class PasswordService {
    // 保留旧字符集和类别选择方式，避免悄然改变调用方依赖的输出格式。
    private static final String LETTERS = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ";
    private static final String DIGITS = "123456789";
    private static final String SYMBOLS = "!@#$%^&*(){}[].?_";
    private static final String[] CHARACTER_GROUPS = {LETTERS, DIGITS, SYMBOLS};
    private final SecureRandom random = new SecureRandom();

    /** length 为 0 时每条随机取 8～10 位；quantity 限为 1～100，结果逐行返回。 */
    public String generate(int length, int quantity) {
        if (length < 0 || length > 128 || quantity < 1 || quantity > 100) {
            throw new IllegalArgumentException("Invalid password parameters");
        }
        // 参数通过检查后才分配结果空间，容量包含每条密码的末尾换行。
        StringBuilder result = new StringBuilder(((length == 0 ? 10 : length) + 1) * quantity);
        for (int i = 0; i < quantity; i++) {
            int passwordLength = length == 0 ? random.nextInt(3) + 8 : length;
            for (int position = 0; position < passwordLength; position++) {
                // 默认模式交替字母／数字；自定义长度按位置随机选类别，不保证三类齐全。
                String alphabet = length == 0
                        ? (position % 2 == 0 ? LETTERS : DIGITS)
                        : CHARACTER_GROUPS[random.nextInt(CHARACTER_GROUPS.length)];
                result.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            // 最后一条也保留 LF，与旧接口及批量复制格式一致。
            result.append('\n');
        }
        return result.toString();
    }
}
