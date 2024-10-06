package com.test.blog.tools.controller;

import com.test.blog.tools.entity.VO.CryptionModel;
import com.test.blog.tools.utils.Cryption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tools")
@Configuration
public class CommonToolsController {

    private static final Logger log = LoggerFactory.getLogger(CommonToolsController.class);

    @RequestMapping(value = "encryption", method = RequestMethod.POST)
    public String encryption(@RequestBody CryptionModel cryptionModel) {
        String result = "";
        try{
            result = Cryption.AESEncode_key(cryptionModel.getKey(), cryptionModel.getPwd());
        }catch (Exception e){
            log.error(e.toString());
            return "error";
        }
        log.info("encryption success");
        return result;
    }

    @RequestMapping(value = "/decryption", method = RequestMethod.POST)
    public String decryption(@RequestBody CryptionModel cryptionModel){

        String result = "";
        try{
            result = Cryption.AESDecode_key(cryptionModel.getKey(), cryptionModel.getPwd());
        }catch (Exception e){
            log.error(e.toString());
            return "error";
        }
        log.info("decryption success");
        return result;
    }
}
