package com.test.blog.testfortestuser.dao;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;


@SpringBootTest
class TestUserMapperTest {

    @Autowired
    private TestUserMapper testUserMapper;

    @Test
    void findAllTestUser(){
        System.out.println("------------ findAllTestUser start ------------ ");
        System.out.println(testUserMapper.findAllTestUser());
    }

    @Test
    void insertTestUser(){
        testUserMapper.insertTestUser("springTest", 18);
        System.out.println("ok");

    }

//    public static void main(String[] args) {
//        new TestUserMapperTest().insertTestUser();
//    }
}