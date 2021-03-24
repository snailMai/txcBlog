package com.test.blog.testfortestuser.controller;


import com.test.blog.testfortestuser.dao.TestUserMapper;
import com.test.blog.testfortestuser.domain.TestUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/testuser")
public class TestUserController {
    @Autowired
    TestUserMapper testUserMapper;

    @RequestMapping(value = "pic")
    public TestUser pic() throws Exception{
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
