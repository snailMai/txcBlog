package com.test.blog.tools.controller;


import com.test.blog.tools.utils.PasswordUtilds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tools/get")
@Configuration
public class GetCommonToolsController {
    private static final Logger log = LoggerFactory.getLogger(GetCommonToolsController.class);
    @RequestMapping(value = "/randompwd", method = RequestMethod.GET)
    public String getRandomPassword(@RequestParam(value = "num", required = false, defaultValue = "0") int num,
                                    @RequestParam(value = "quantity", required = false, defaultValue = "1") int quantity){
        log.info("/randompwd");
        log.info("num is : " + num);
        log.info("quantity is : " + quantity);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < quantity; i++) {
            if (num == 0){
                result.append(PasswordUtilds.randomPassword());
            }else {
                result.append(PasswordUtilds.randomPW(num));
            }
            result.append("\n");
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                log.info(e.toString());
                e.printStackTrace();
            }
        }


        return result.toString();
    }


}
