# Java 工具后端

保留加解密与随机密码生成接口，不依赖数据库。使用 JDK 21 和 Spring Boot 4.1.1，通过 `bash ./mvnw clean verify` 验证并生成 `target/myBlog.war`，通过 `java -jar target/myBlog.war` 使用自带容器启动。终端的 `JAVA_HOME` 和 `java` 应指向 JDK 21。

HTTP 与业务逻辑分别位于 `tools/controller/`、`tools/service/`；成功返回原始 UTF-8 文本，失败返回安全的 `{code, message}` 错误体。旧密文兼容基于 UTF-8 密钥环境，旧 AES 格式不具备消息认证能力。

服务默认只监听 `127.0.0.1:8081`。旧用户模块、数据库配置和 SQL 已退役，不参与构建，也不应重新提交到仓库。

仓库根目录的 `Jenkinsfile` 每 5 分钟检查一次 `master` 分支。发现新提交后，Jenkins 使用 Maven Wrapper 运行全部测试并构建 WAR，归档 `target/myBlog.war` 和测试报告；流水线不执行线上部署。
