package com.test.blog.testfortestuser.dao;

import com.test.blog.testfortestuser.domain.TestUser;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;

@Mapper
@Repository
public interface TestUserMapper {
    TestUser insertTestUser(String username, Integer age);
    TestUser deleteTestUser(String username);
    TestUser selectUserByName(String username);
    ArrayList<TestUser> findAllTestUser();
    ArrayList<TestUser> findAllTestUserByNameAge(String sqlData);
    Integer countTestUser();
//    TestUser
}
