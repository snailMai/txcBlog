package com.test.blog;

import com.test.blog.entity.dto.BlogUserWrap;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

@SpringBootApplication
public class BlogApplication { // extends 是为了打包

	public static void main(String[] args) {
		SpringApplication.run(BlogApplication.class, args);
	}

}
