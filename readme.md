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
java -jar todoist/target/todoist.jar
```

默认监听 `127.0.0.1:8091`，不改变工具 WAR。通过本人桌面应用已有的 `~/.codex/ipc/ipc.sock` 发现固定原 AI Todoist 会话持有者，再经该持有者发送前端原文，不启动独立 Codex 进程、不调用 thread/resume、不创建替代会话。需要自定义路径时使用 `--todoist.desktop-socket=/absolute/path/to/ipc.sock`；旧 codex-path／workspace 参数不再使用，不要求修改现有启动项。模块不解析或直接写入待办 Markdown，不追加 prompt，也不覆盖会话目录、权限、审批或模型设置。

前端见 [snailMai/vueDemo](https://github.com/snailMai/vueDemo) 。同源代理须将 `/api/todoist/` 转发到本模块并去掉 `/api` 前缀，其余 `/api/` 转发到原工具接口。接口如下：

- `POST /todoist/commands`：JSON `{input, requestId}`，UUID 标识同一请求，异步返回状态。
- `GET /todoist/commands?requestId=…`：查询状态；完成时返回 `{requestId, status: "completed", output}`。
- `GET /todoist/health`：仅本机检查配置，不触发模型，不公开代理。

页面组合添加、完成、修改、删除任务命令；输出全部待办只发送该命令文本。后端只校验传输格式，不实现业务命令限制、网站登录、每日限额或跨客户端协调。对公网开放后其他人也可能调用，不应宣称具备访问控制。

请求状态仅保存在内存，完成结果一小时后可清理。未知结果不应自动重发；服务重启或结果过期后需在原会话核对。任务输入、输出、工具正文及凭证不得写入运行日志，原会话正常历史仍会保存请求。单元测试使用模拟协议和合成数据，不操作真实待办。

转发每次连接、订阅目标会话并提交一次原文，通过返回的轮次 ID 匹配最终结果。只有轮次已完成才返回文本，随后取消本次订阅并关闭 socket，不中断桌面会话或其他连接；不会因为已出现部分文字就提前结束。初始化与持有者发现失败是明确未发送，提交后异常仍返回 `RESULT_UNKNOWN`，不自动重发。所有错误仅使用安全固定码，不回显协议原文。

IPC 是当前桌面版本的内部协议，桌面应用须运行且原会话已有可发现的持有者，升级后可能需要适配；版本／状态序列不匹配时明确失败，不自动切换到抢占连接、改全局配置或创建新会话。测试在独立合成 Unix socket 上模拟五种命令、状态更新、失败及连接释放，绝不向真实 AI Todoist 发测试消息。网站不管理会话规则、不检查任务文件或同步状态，只负责转发和显示。

## 持续集成

`Jenkinsfile` 仅手工触发，当前执行原工具 WAR 的测试、构建及产物归档，不自动构建 todoist 模块，不执行生产部署。todoist 需按上述独立命令验证和打包。
