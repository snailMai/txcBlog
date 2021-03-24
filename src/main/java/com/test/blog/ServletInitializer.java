package com.test.blog;


import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;


public class ServletInitializer extends SpringBootServletInitializer {

	// 打包需要增加的内容
	@Override
	protected SpringApplicationBuilder configure(SpringApplicationBuilder application) {
		return application.sources(BlogApplication.class);
	}

//	public static void main(String[] args){
//		SpringApplication.run(BlogApplication.class, args);
//	}

}
