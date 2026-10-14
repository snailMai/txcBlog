package com.test.blog.tools.dto;

/**
 * 沿用旧接口字段：key 是密钥，pwd 在加密时为明文、解密时为密文，不裁剪空白。
 * 对象包含敏感数据，禁止打印对象本身、自动生成的 toString 或任一字段。
 */
public record CipherRequest(String key, String pwd) {
}
