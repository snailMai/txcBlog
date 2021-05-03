package com.test.blog.testfortestuser.dao;

import com.test.blog.testfortestuser.domain.TestUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;

@Mapper
@Repository
public interface TestUserMapper {
    // 实际返回的testUser是要在service层实现,这里直接查询sql返回,一般mapper返回参数是int,来确定sql语句有没有成功
    Integer insertTestUser(@Param("username")String username, @Param("age")int age);
    int deleteTestUser(String username);
    TestUser selectUserByName(String username);
    TestUser selectUserById(int id);
    ArrayList<TestUser> findAllTestUser();
    ArrayList<TestUser> findAllTestUserByPage(@Param("startIndex")int startIndex, @Param("pageSize")int pageSize);
    ArrayList<TestUser> findAllTestUserByNameAge(String sqlData);
    Integer countTestUser();
    int updateTestUser(@Param("id")int id, @Param("username")String username, @Param("age")int age);
//    TestUser
}
