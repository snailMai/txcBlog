package com.test.blog;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BlogApplication extends ServletInitializer{ // extends 是为了打包

	public static void main(String[] args) {
		SpringApplication.run(BlogApplication.class, args);
	}

}
