package com.test.blog.entity.dto;

import lombok.Data;

import java.util.Date;

@Data

public class BlogUserAuthToken {
    private Long id;
    private String user_name;
    private String auth_token;
    private Date createTime;
}
