package com.test.blog.testfortestuser.controller;


import com.test.blog.testfortestuser.dao.TestUserMapper;
import com.test.blog.testfortestuser.domain.TestUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.*;


/**
 * @author mai
 */

@RestController
@RequestMapping("/testuser")
@Configuration
public class UpdateUserController {
    private static final Logger log = LoggerFactory.getLogger(UpdateUserController.class);
    @Autowired
    TestUserMapper testUserMapper;

    // 还是觉得使用{username}比较好,要设计成username唯一
    @RequestMapping(value = "updateTestUser/{id}", method = RequestMethod.PUT)
    public String updateTestUserResponse(@RequestBody TestUser requestTestUser, @PathVariable int id) {
        String username = requestTestUser.getUsername();
        int age = requestTestUser.getAge();
        log.info (String.format("id:%d;  username:%s;  age:%d", id, username, age));
        TestUser testUser = new TestUser();
        TestUser testUserById =  testUserMapper.selectUserById(id);
        if (username.equals(testUserById.getUsername()) && age == testUserById.getAge()){
            log.error("username and age is already exist");
            return  "username and age is already exist";
        }
        try{
            int test = testUserMapper.updateTestUser(id, username, age);
            if (test == 1){
                log.debug("-----------debug----------");
                log.info("-----------updateTestUserResponse----------");
                log.info("update user success, the new username is " + username + "; age is" + age);
                return "update user success, the user is " + username;
            }
            log.error("update User fail");
            return "update User fail";
        }catch (Exception e){
            log.error("call error: " + e);
        }
        return null;
    }
}
