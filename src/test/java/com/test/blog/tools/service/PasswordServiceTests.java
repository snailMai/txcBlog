package com.test.blog.tools.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证输出格式与边界，不依赖随机结果恰好出现某个字符，以免测试不稳定。 */
class PasswordServiceTests {
    private final PasswordService service = new PasswordService();

    @Test
    void defaultPasswordsKeepAlternatingCharactersAndFinalNewline() {
        // 保留 split 的末尾空项，才能断言最后一条密码仍以 LF 结束。
        String[] lines = service.generate(0, 100).split("\n", -1);
        assertEquals(101, lines.length);
        assertEquals("", lines[100]);
        for (int i = 0; i < 100; i++) {
            assertTrue(lines[i].length() >= 8 && lines[i].length() <= 10);
            for (int j = 0; j < lines[i].length(); j++) {
                char ch = lines[i].charAt(j);
                assertTrue((j % 2 == 0 ? "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ" : "123456789")
                        .indexOf(ch) >= 0);
            }
        }
    }

    @Test
    void rejectsOutOfRangeArgumentsWithoutAllocatingResults() {
        for (int length : new int[]{-1, 129, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> service.generate(length, 1));
        }
        for (int quantity : new int[]{0, -1, 101, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> service.generate(10, quantity));
        }
    }
}
