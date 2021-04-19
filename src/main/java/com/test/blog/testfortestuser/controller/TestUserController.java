package com.test.blog.testfortestuser.controller;


import com.test.blog.testfortestuser.dao.TestUserMapper;
import com.test.blog.testfortestuser.domain.TestUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.util.ArrayList;


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
    public TestUser pic() {

        try {
            // 调用dao层
            TestUser testUser = testUserMapper.selectUserByName("wx");
            log.debug("-----------debug----------");
            log.info("-----------pic----------");
            return testUser;
        }catch (Exception e){
            log.error("call error: " + e);
        }

        return null;
    }

    // vue
    @RequestMapping(value = "/allTestUserVue", method = RequestMethod.GET)
    public ArrayList<TestUser> getAllTestUserVue() throws  Exception{
        ArrayList<TestUser> listTestUser;
        try{
            listTestUser = testUserMapper.findAllTestUser();
            log.debug("-----------debug----------");
            log.info("----------getAllTestUser-----------");
            return listTestUser;
        }catch (Exception e){
            log.error("call method failed,error: " + e);
        }
        return null;
    }

    @RequestMapping(value = "{username}_{age}", method = RequestMethod.POST)
    public TestUser addTestUser(@PathVariable String username, @PathVariable Integer age) {
        Integer testUserNumber = testUserMapper.countTestUser();

        if (testUserNumber > 100){
            log.error("addUser failed: the user reach the limit");
            return null;
        }
        TestUser testUser = new TestUser();
        try{
            testUser = testUserMapper.insertTestUser(username, age);
            log.debug("-----------debug----------");
            log.info("-----------addTestUser----------");
            log.info("add user success, the user is " + username);
            return testUser;
        }catch (Exception e){
            log.error("call error: " + e);
        }
        return null;
    }

    @RequestMapping(value = "{username}", method = RequestMethod.GET)
    public TestUser getTestUserByName(@PathVariable String username ) {
        TestUser testUser = new TestUser();
        try{
            testUser = testUserMapper.selectUserByName(username);
            log.debug("-----------debug----------");
            log.info("----------getTestUserByName-----------");
            return testUser;
        }catch (Exception e){
            System.out.println("call method failed,error: " + e);
            log.error("call error: " + e);
        }
        return null;
    }

    @RequestMapping(value = "{username}", method = RequestMethod.DELETE)
    public TestUser deleteTestUserByName(@PathVariable String username ) {
        TestUser testUser = new TestUser();
        try{
            testUser = testUserMapper.deleteTestUser(username);
            log.debug("-----------debug----------");
            log.info("----------deleteTestUserByName-----------");
            log.info("delete user success, the user is " + username);
            return testUser;
        }catch (Exception e){
            System.out.println("call method failed,error: " + e);
            log.error("call error: " + e);
        }
        return null;
    }

    @RequestMapping(value = "/allTestUser", method = RequestMethod.GET)
    public ArrayList<TestUser> getAllTestUser() throws  Exception{
        ArrayList<TestUser> listTestUser;
        try{
            listTestUser = testUserMapper.findAllTestUser();
            log.debug("-----------debug----------");
            log.info("----------getAllTestUser-----------");
            return listTestUser;
        }catch (Exception e){
            log.error("call method failed,error: " + e);
        }
        return null;
    }

    @RequestMapping(value = "/number", method = RequestMethod.GET)
    public Integer getTestUserNumber() throws  Exception{

        try{
            Integer testUserNumber = testUserMapper.countTestUser();
            log.debug("-----------debug----------");
            log.info("----------getTestUserNumber-----------");
            return testUserNumber;
        }catch (Exception e){
            log.error("call error: " + e);
        }
        return null;
    }


    // @RequestParam

    @RequestMapping(value = "/filterTestUser", method = RequestMethod.GET)
    public ArrayList<TestUser> getAllTestUserByNameOrAge(@RequestParam(value = "age", required = false) Integer age,
                                                         @RequestParam(value = "username", required = false, defaultValue = "") String username)  throws  Exception{
        ArrayList<TestUser> listTestUser;
        log.info("requestBody age:" + age);
        log.info("requestBody username" + username);

        //当前方式不知道有没有 sql注入风险
        String sqlData = "WHERE";
        if (age != null){
            sqlData = sqlData + " age = " + age;
        }
        if (username.equals("")){

        }else if(age != null && !username.equals("")){
            sqlData = sqlData + " AND username = " + "\'" + username + "\'";
        }else {
            sqlData = sqlData + " username = " + "\'" + username + "\'";
        }

        try{
            if (sqlData.equals("WHERE")){
                listTestUser = testUserMapper.findAllTestUser();
            }else {
                listTestUser = testUserMapper.findAllTestUserByNameAge(sqlData);
            }
            log.debug("-----------debug----------");
            log.info("----------getAllTestUserByNameOrAge success-----------");
            return listTestUser;
        }catch (Exception e){
            log.error("call method failed,error: " + e);
        }
        return null;
    }

    // @RequestBody
    @RequestMapping(value = "addTestUser", method = RequestMethod.POST)
    public TestUser addTestUserResponse(@RequestBody TestUser requestTestUser) {
        Integer testUserNumber = testUserMapper.countTestUser();

        String username = requestTestUser.getUsername();
        int age = requestTestUser.getAge();

        if (testUserNumber > 100){
            log.error("addUser failed: the user reach the limit");
            return null;
        }
        TestUser testUser = new TestUser();
        try{
            testUser = testUserMapper.insertTestUser(username, age);
            log.debug("-----------debug----------");
            log.info("-----------addTestUserResponse----------");
            log.info("add user success, the user is " + username);
            return testUser;
        }catch (Exception e){
            log.error("call error: " + e);
        }
        return null;
    }


    // all interface
    @RequestMapping(value = "/all", method = RequestMethod.GET)
    public String getAllInterface(){
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("get pic,test \n");
        stringBuilder.append("post {username}_{age},{username}_{age} \n");
        stringBuilder.append("get {username},username \n");
        stringBuilder.append("delete {username},username \n");
        stringBuilder.append("get allTestUser,null \n");
        stringBuilder.append("get number,null \n");
        stringBuilder.append("get filterTestUser,requestParam \n");
        stringBuilder.append("post addTestUser,requestBody");
        log.info(stringBuilder.toString());
        return stringBuilder.toString();
    }
}
