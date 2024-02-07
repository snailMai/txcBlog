package com.test.blog.service.impl;

import com.test.blog.entity.dto.AddBlogUserModel;
import com.test.blog.entity.dto.ShowBlogUserModel;
import com.test.blog.mapper.BlogUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class BlogUserServiceImpl {
    private static final Logger log = LoggerFactory.getLogger(BlogUserServiceImpl.class);

    @Autowired
    private AddBlogUserModel addBlogUserModel;

    @Autowired
    private BlogUserMapper blogUserMapper;

    public ShowBlogUserModel saveBlogUserWrap(String user_name, String user_password, String phone_number){
        ShowBlogUserModel showBlogUserModel = new ShowBlogUserModel();
        return showBlogUserModel;
    }

    public int addBlogUser(String user_name, String password, String phone_number, String user_id){
        int addSuccess = 0;
        try{
            addSuccess = blogUserMapper.insertBlogUser(user_name, password, phone_number, user_id);
        }catch (Exception e){
            log.error("insert blog_user failed");
            log.error("call error: " + e);
        }
        return addSuccess;
    }

}
