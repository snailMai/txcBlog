package com.test.blog.testfortestuser.controller;


import com.test.blog.testfortestuser.dao.TestUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;


/**
 * @author mai
 */

@RestController
@RequestMapping("/testuser")
@Configuration
public class DeleteUserController {
    private static final Logger log = LoggerFactory.getLogger(DeleteUserController.class);
    @Autowired
    TestUserMapper testUserMapper;
    @RequestMapping(value = "{username}", method = RequestMethod.DELETE)
    public String deleteTestUserByName(@PathVariable String username ) {
//        TestUser testUser = new TestUser();
        try{
            int testUser = testUserMapper.deleteTestUser(username);
            if (testUser == 1){
                log.debug("-----------debug----------");
                log.info("----------deleteTestUserByName-----------");
                log.info("delete user success, the user is " + username);
                return "delete user success, the user is " + username;
            }
            log.error("delete user fail");
            return "delete user fail";
        }catch (Exception e){
            System.out.println("call method failed,error: " + e);
            log.error("call error: " + e);
        }
        return null;
    }

}
