package com.test.blog.entity.dto;

import lombok.Data;

import java.util.Date;

@Data


// 作为redis
public class TempBlogUserAuthTokenModel {
    private Long id;
    private String user_name;
    private String blog_token;
    private Long startTimestamp;
    private Long endTimestamp;
}
