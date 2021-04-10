package com.test.blog.testfortestuser.dao;

import com.test.blog.testfortestuser.domain.TestUser;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;

@Mapper
@Repository
public interface TestUserMapper {
    // 实际返回的testUser是要在service层实现,这里直接查询sql返回,一般mapper返回参数是int,来确定sql语句有没有成功
    TestUser insertTestUser(String username, Integer age);
    TestUser deleteTestUser(String username);
    TestUser selectUserByName(String username);
    ArrayList<TestUser> findAllTestUser();
    ArrayList<TestUser> findAllTestUserByNameAge(String sqlData);
    Integer countTestUser();
//    TestUser
}
