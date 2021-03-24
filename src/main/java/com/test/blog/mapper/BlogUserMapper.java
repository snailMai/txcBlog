package com.test.blog.mapper;

import com.test.blog.entity.dto.BlogUserResponseWrap;
import com.test.blog.entity.dto.BlogUserWrap;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;

@Mapper
@Repository
public class BlogUserMapper {

//    boolean insert(BlogUserWrap blogUserWrap);

    // lombok
//    @Insert("insert into blog_user(user_name, user_password, phone_number)"
//            + "values (#{user_name}, #{user_password}, #{phone_number}, now(), now())")
//    int save(BlogUserWrap blogUserWrap);
//
//    @Select("select * from blog_user where id = #{user_name}")
//    BlogUserWrap findById(@Param("user_name") String user_name);
//
//    @Select("select * from blog_user")
//    BlogUserWrap findAll();
}
