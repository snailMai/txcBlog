package com.test.blog.tools.controller;

import com.test.blog.tools.dto.CipherRequest;
import com.test.blog.tools.service.CipherService;
import com.test.blog.tools.service.PasswordService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仅负责 HTTP 参数绑定和服务调用；成功响应保留原始文本，不包装成 JSON。 */
@RestController
@RequestMapping("/tools")
public class ToolsController {
    private static final String TEXT_UTF8 = "text/plain;charset=UTF-8";
    private final CipherService cipherService;
    private final PasswordService passwordService;

    public ToolsController(CipherService cipherService, PasswordService passwordService) {
        this.cipherService = cipherService;
        this.passwordService = passwordService;
    }

    /** 密钥和明文从 JSON 正文读取，不放入 URL，也不记录请求或结果。 */
    @PostMapping(value = "/encryption", consumes = MediaType.APPLICATION_JSON_VALUE, produces = TEXT_UTF8)
    public String encrypt(@RequestBody CipherRequest request) {
        return cipherService.encrypt(request.key(), request.pwd());
    }

    /** 保持旧接口名称及字段，校验与解密失败交给统一异常处理。 */
    @PostMapping(value = "/decryption", consumes = MediaType.APPLICATION_JSON_VALUE, produces = TEXT_UTF8)
    public String decrypt(@RequestBody CipherRequest request) {
        return cipherService.decrypt(request.key(), request.pwd());
    }

    /** 查询参数只包含长度与数量；缺省值沿用旧协议，范围由服务层校验。 */
    @GetMapping(value = "/get/randompwd", produces = TEXT_UTF8)
    public String randomPassword(@RequestParam(name = "num", defaultValue = "0") int num,
                                 @RequestParam(name = "quantity", defaultValue = "1") int quantity) {
        return passwordService.generate(num, quantity);
    }
}
