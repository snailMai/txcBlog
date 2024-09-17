package com.test.blog.testfortestuser.controller;


import com.test.blog.testfortestuser.dao.TestUserMapper;
import com.test.blog.testfortestuser.domain.TestUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;


/**
 * @author mai
 */

@RestController
@RequestMapping("/testuser")
@Configuration
public class AddUserController {
    private static final Logger log = LoggerFactory.getLogger(AddUserController.class);
    @Autowired
    TestUserMapper testUserMapper;

    // @RequestBody
    @RequestMapping(value = "addTestUser", method = RequestMethod.POST)
    public String addTestUserResponse(@RequestBody TestUser requestTestUser) {
        Integer testUserNumber = testUserMapper.countTestUser();

        log.info("TestUser number: " + testUserNumber.toString());

        String username = requestTestUser.getUsername();
        int age = requestTestUser.getAge();

        if (testUserNumber > 100){
            log.error("addUser failed: the user reach the limit");
            return null;
        }
        log.info (String.format("username:%s;  age:%d", username, age));
        TestUser testUser = new TestUser();
        try{
            int test = testUserMapper.insertTestUser(username, age);
            if (test == 1){
                log.debug("-----------debug----------");
                log.info("-----------addTestUserResponse----------");
                log.info("add user success, the user is " + username);
                return "add user success, the user is " + username;
            }
            log.error("add User fail");
            return "add User fail";
        }catch (Exception e){
            log.error("call error: " + e);
        }
        return null;
    }

}
