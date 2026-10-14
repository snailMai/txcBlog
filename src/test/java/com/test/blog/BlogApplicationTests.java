package com.test.blog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** 最小启动回归：应用上下文应在没有数据库环境的情况下完成装配。 */
@SpringBootTest
class BlogApplicationTests {

	@Test
	void contextLoads() {
		// 上下文创建失败会由 SpringBootTest 直接使测试失败，无需重复断言。
	}

}
