package com.test.blog.testfortestuser.controller;


import com.test.blog.testfortestuser.dao.TestUserMapper;
import com.test.blog.testfortestuser.domain.TestUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;


/**
 * @author mai
 */

@RestController
@RequestMapping("/testuser")
@Configuration
public class GetUserController {
    private static final Logger log = LoggerFactory.getLogger(GetUserController.class);
    @Autowired
    TestUserMapper testUserMapper;

    // all interface
    @RequestMapping(value = "/all", method = RequestMethod.GET)
    public String getAllInterface(){
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("----------------testuser-------------\n");
        stringBuilder.append("get pic,test \n");
        stringBuilder.append("post {username}_{age},{username}_{age} \n");
        stringBuilder.append("get {username},username \n");
        stringBuilder.append("delete {username},username \n");
        stringBuilder.append("get allTestUser,null \n");
        stringBuilder.append("get number,null \n");
        stringBuilder.append("get filterTestUser,requestParam \n");
        stringBuilder.append("post addTestUser,requestBody\n");
        stringBuilder.append("----------------Tools-------------\n");
        stringBuilder.append("-----post-----\n");
        stringBuilder.append("enc/dec, encryption/decryption\n");
        stringBuilder.append("getRandomPwd, tools/get/randompwd\n");
        log.info(stringBuilder.toString());
        return stringBuilder.toString();
    }

    @RequestMapping(value = "/number", method = RequestMethod.GET)
    public Integer getTestUserNumber() {
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

    @RequestMapping(value = "/allTestUser", method = RequestMethod.GET)
    public ArrayList<TestUser> getAllTestUser(){
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

    @RequestMapping(value = "/filterTestUser", method = RequestMethod.GET)
    public ArrayList<TestUser> getAllTestUserByNameOrAge(@RequestParam(value = "age", required = false) Integer age,
                                                         @RequestParam(value = "username", required = false, defaultValue = "") String username) {
        ArrayList<TestUser> listTestUser;
        log.info("requestBody age:" + age);
        log.info("requestBody username" + username);
        TestUser testUser = new TestUser();
        testUser.setUsername(username);
        testUser.setAge(age);


        try{
            listTestUser = testUserMapper.findAllTestUserByNameAge(testUser);
            log.debug("-----------debug----------");
            log.info("----------getAllTestUserByNameOrAge success-----------");
            return listTestUser;
        }catch (Exception e){
            log.error("call method failed,error: " + e);
        }
        return null;
    }


    // vue
    @RequestMapping(value = "/allTestUserVue", method = RequestMethod.GET)
    public ArrayList<TestUser> getAllTestUserVue(){
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

    @RequestMapping(value = "/allTestUserVue/page/{page}", method = RequestMethod.GET)
    public ArrayList<TestUser> getAllTestUserVuePage(@PathVariable Integer page){
        //PageHelper.startPage(page, pageSize);这段代码表示，程序开始分页了，
        //page默认值是1，pageSize默认是10，意思是从第1页开始，每页显示10条记录。
//        PageHelper.startPage(page, 5);
        ArrayList<TestUser> listTestUser;
        try{
            listTestUser = testUserMapper.findAllTestUserByPage((page-1) * 5, 5);
            log.debug("-----------debug----------");
            log.info("----------getAllTestUser-----------");
            return listTestUser;
        }catch (Exception e){
            log.error("call method failed,error: " + e);
        }
        return null;
    }

    @RequestMapping(value = "{username}", method = RequestMethod.GET)
    public TestUser getTestUserByName(@PathVariable String username ) {
        int id = 0;
        TestUser testUser;
        try {
            // parseInt返回int,valueOf返回Integer
            id = Integer.parseInt(username);
        }catch(Exception e){
            log.warn("getTestUserByName is String");
        }
        if (id != 0){
            try{
                testUser = testUserMapper.selectUserById(id);
                log.debug("-----------debug----------");
                log.info("----------getTestUserById-----------");
                return testUser;
            }catch (Exception e){
                System.out.println("call method failed,error: " + e);
                log.error("call error: " + e);
            }
        }else {
            try{
                testUser = testUserMapper.selectUserByName(username);
                log.debug("-----------debug----------");
                log.info("----------getTestUserByName-----------");
                return testUser;
            }catch (Exception e){
                System.out.println("call method failed,error: " + e);
                log.error("call error: " + e);
            }
        }
        return null;
    }
}
