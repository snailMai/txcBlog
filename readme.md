# 工具后端

Java 21／Spring Boot 工具接口与独立 todoist 会话转发模块，不依赖数据库。使用仓库 Maven Wrapper（Maven 3.9.9），运行前让 `JAVA_HOME` 指向 JDK 21。

## 工具接口

```bash
bash ./mvnw verify
java -jar target/myBlog.war
```

默认监听 `127.0.0.1:8081`。保留 `POST /tools/encryption`、`POST /tools/decryption`（JSON：`key`、`pwd`）及 `GET /tools/get/randompwd`（`num`、`quantity`）接口。成功响应为 UTF-8 纯文本且禁止缓存，错误响应不回显输入。用户管理接口已退役，`legacy/` 不参与构建或部署。

密钥、明文、密文、解密结果及生成密码不得写入日志。旧密文格式仅保持既有 UTF-8 兼容，不提供消息认证。

## todoist 会话转发

```bash
bash ./mvnw -f todoist/pom.xml verify
java -jar todoist/target/todoist.jar --todoist.codex-path=/absolute/path/to/codex --todoist.workspace=/absolute/path/to/workspace
```

默认监听 `127.0.0.1:8091`，不改变工具 WAR。两个路径须为本机实际绝对路径，使用已有 Codex 登录；缺省留空，不启动模型或接触任务文件。该模块恢复源码中固定的原 AI Todoist 会话，不创建替代会话，不解析或直接写入待办 Markdown，也不追加 prompt。恢复使用 `workspace-write`／`never` 参数，可能影响原会话后续默认值，不修改全局 Codex 配置。

前端见 [snailMai/vueDemo](https://github.com/snailMai/vueDemo) 。同源代理须将 `/api/todoist/` 转发到本模块并去掉 `/api` 前缀，其余 `/api/` 转发到原工具接口。接口如下：

- `POST /todoist/commands`：JSON `{input, requestId}`，UUID 标识同一请求，异步返回状态。
- `GET /todoist/commands?requestId=…`：查询状态；完成时返回 `{requestId, status: "completed", output}`。
- `GET /todoist/health`：仅本机检查配置，不触发模型，不公开代理。

页面组合添加、完成、修改、删除任务命令；输出全部待办只发送该命令文本。后端只校验传输格式，不实现业务命令限制、网站登录、每日限额或跨客户端协调。对公网开放后其他人也可能调用，不应宣称具备访问控制。

请求状态仅保存在内存，完成结果一小时后可清理。未知结果不应自动重发；服务重启或结果过期后需在原会话核对。任务输入、输出、工具正文及凭证不得写入运行日志，原会话正常历史仍会保存请求。单元测试使用模拟协议和合成数据，不操作真实待办。

## 持续集成

`Jenkinsfile` 仅手工触发，当前执行原工具 WAR 的测试、构建及产物归档，不自动构建 todoist 模块，不执行生产部署。todoist 需按上述独立命令验证和打包。
