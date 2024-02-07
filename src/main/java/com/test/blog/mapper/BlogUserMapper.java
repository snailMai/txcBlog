package com.test.blog.mapper;

import com.test.blog.entity.dto.ShowBlogUserModel;
import org.apache.ibatis.annotations.*;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;

@Mapper
@Repository
public interface BlogUserMapper {

    // password是明文存储,以后有时间再调试
    Integer insertBlogUser(String blog_user, String password, String phone_number, String user_id);
    Integer deleteBlogUser(String user_id);
    Integer updateBlogUser(String user_id);
    ShowBlogUserModel selectShowBlogUser(String user_id);
    ArrayList<ShowBlogUserModel> selectListBlogUser(ShowBlogUserModel showBlogUserModel);




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
