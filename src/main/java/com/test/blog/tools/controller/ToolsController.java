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

    @PostMapping(value = "/encryption", consumes = MediaType.APPLICATION_JSON_VALUE, produces = TEXT_UTF8)
    public String encrypt(@RequestBody CipherRequest request) {
        return cipherService.encrypt(request.key(), request.pwd());
    }

    @PostMapping(value = "/decryption", consumes = MediaType.APPLICATION_JSON_VALUE, produces = TEXT_UTF8)
    public String decrypt(@RequestBody CipherRequest request) {
        return cipherService.decrypt(request.key(), request.pwd());
    }

    @GetMapping(value = "/get/randompwd", produces = TEXT_UTF8)
    public String randomPassword(@RequestParam(name = "num", defaultValue = "0") int num,
                                 @RequestParam(name = "quantity", defaultValue = "1") int quantity) {
        return passwordService.generate(num, quantity);
    }
}
