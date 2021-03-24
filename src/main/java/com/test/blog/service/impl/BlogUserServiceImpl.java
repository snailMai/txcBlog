package com.test.blog.service.impl;

import com.test.blog.entity.dto.BlogUserWrap;
import com.test.blog.mapper.BlogUserMapper;
import org.springframework.beans.factory.annotation.Autowired;

public class BlogUserServiceImpl {
    @Autowired
    private BlogUserMapper blogUserMapper;

    public BlogUserWrap saveBlogUserWrap(String user_name, String user_password, String phone_number){
        BlogUserWrap blogUserWrap = new BlogUserWrap();
        return blogUserWrap;
    }

}
