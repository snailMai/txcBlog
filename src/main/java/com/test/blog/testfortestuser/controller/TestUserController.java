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
public class TestUserController {
    private static final Logger log = LoggerFactory.getLogger(TestUserController.class);
    @Autowired
    TestUserMapper testUserMapper;

    @RequestMapping(value = "pic")
    public TestUser pic() throws Exception{
        log.debug("-----------debug----------");
        log.info("-----------pic----------");
        try {
            // 调用dao层
            TestUser testUser = testUserMapper.selectUserByName("wx");
            return testUser;
        }catch (Exception e){
            System.out.println("call method failed,error: " + e);
        }


        return null;
    }

    @RequestMapping(value = "", method = RequestMethod.POST, produces="test/html;charset=UTF-8")
    public TestUser addTestUser(@PathVariable String username, @PathVariable Integer age) throws Exception{
        TestUser testUser = new TestUser();
        try{
            testUser = testUserMapper.insertTestUser(username, age);
            return testUser;
        }catch (Exception e){
            System.out.println("call method failed,error: " + e);
        }
        return null;
    }

//    @RequestMapping(value = "{username}", method = RequestMethod.GET, produces = "test/html;charset=UTF-8")
    @RequestMapping(value = "{username}", method = RequestMethod.GET)
    public TestUser getTestUserByName(@PathVariable String username ) throws  Exception{
        TestUser testUser = new TestUser();
        try{
            testUser = testUserMapper.selectUserByName(username);
            return testUser;
        }catch (Exception e){
            System.out.println("call method failed,error: " + e);
        }
        return null;
    }

//    @RequestMapping(value = "{username}", method = RequestMethod.GET, produces = "test/html;charset=UTF-8")
//    public TestUser getAllTestUser() throws  Exception{
//        TestUser testUser = new TestUser();
//        try{
//            testUser = testUserMapper.selectUserByName(username);
//            return testUser;
//        }catch (Exception e){
//            System.out.println("call method failed,error: " + e);
//        }
//        return null;
//    }

}
