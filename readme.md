# 工具后端

当前工具后端与 todoist 项目版本统一为 `3.1.0`，随产品 V3 电脑手机同步阶段管理；本次仅对齐产品元数据，提交前已验证构建，未发布 Java，原工具接口、任务文件和固定产物名不变。

Java 21／Spring Boot 工具接口与独立 todoist 本地任务模块，不依赖数据库。使用仓库 Maven Wrapper（Maven 3.9.9），运行前让 `JAVA_HOME` 指向 JDK 21。v0.4.0 本地执行版已于 2026-10-20 部署，原工具 WAR 不变。

## 工具接口

```bash
bash ./mvnw verify
java -jar target/myBlog.war
```

默认监听 `127.0.0.1:8081`。保留 `POST /tools/encryption`、`POST /tools/decryption`（JSON：`key`、`pwd`）及 `GET /tools/get/randompwd`（`num`、`quantity`）接口。成功响应为 UTF-8 纯文本且禁止缓存，错误响应不回显输入。用户管理接口已退役，`legacy/` 不参与构建或部署。

密钥、明文、密文、解密结果及生成密码不得写入日志。旧密文格式仅保持既有 UTF-8 兼容，不提供消息认证。

## todoist 本地执行

```bash
bash ./mvnw -f todoist/pom.xml verify
java -jar todoist/target/todoist.jar
```

默认监听 `127.0.0.1:8091`，不改变工具 WAR。Java 直接读写两份 Markdown，不调用 AI、桌面 IPC、会话或外部命令，不修改原会话及周期脚本。运行用户需有两个文件的读写权限；不放宽整个知识库权限。配置只接受服务端固定绝对路径，浏览器不能指定：

- `todoist.task-file`：默认 `/Users/macminimai/Library/CloudStorage/坚果云-15507814601@163.com/snailmaiNotes/journal/aiTodoist.md`。
- `todoist.archive-file`：默认 `/Users/macminimai/Library/CloudStorage/坚果云-15507814601@163.com/snailmaiNotes/journal/history/aiTodoistArchive.md`。
- `todoist.state-dir`：默认运行用户的 `Library/Application Support/personweb/todoist-state`；0700 私有目录，0600 回执，不进入静态目录或 Git。

直接运行上面的启动命令会使用真实默认路径；测试必须显式覆盖上述三项为独立合成文件和目录，不用真实待办试写。旧 desktop-socket／codex-path／workspace 参数不再使用。

前端见 [snailMai/vueDemo](https://github.com/snailMai/vueDemo) 。同源代理须将 `/api/todoist/` 转发到本模块并去掉 `/api` 前缀，其余 `/api/` 转发到原工具接口。接口如下：

- `POST /todoist/commands`：JSON `{requestId, operation, fields?, targets?, confirmation?}`。UUID 标识请求，FIFO 一次执行一条；旧 `{input, requestId}` 返回 `CLIENT_UPDATE_REQUIRED`，不能回退会话转发。
- `GET /todoist/commands?requestId=…`：查询状态；排队中／执行中返回 `queued`／`running`，成功返回 `{requestId, status: "completed", output}`，失败返回 `failed` + 固定错误码，结果可能已执行但无法确认时返回 `unknown` + 错误码。
- `GET /todoist/tasks`：只读解析任务文件，返回 `{tasks: [{id, content, priority, due, overdue, key, version, note}]}`；每次重新读取，不写文件。与写入共用 `TaskDocument` 字段解析，备注续行中的状态／期限不覆盖真实字段；保留历史缺字段的读取默认值，不调用写入校验收紧列表兼容性。
- `GET /todoist/health`：仅本机检查文件可访问性，返回 `{status: "ready", mode: "local"}`，不写文件、不公开代理。
- `GET /todoist/recurring/preview`：仅本机只读预览缺失的三类周期，返回 `{timezone, missingCount, tasks}`；不投递队列、不创建回执。Caddy 不公开该路径。

operation 为 `add`／`edit`／`complete`／`remove`。新增 fields 至少含 name，可选 priority、due、goal、criteria、dependency、note；缺省 P2，目标「完成〈名称〉。」，标准「完成任务所述事项，并核对结果。」，依赖／备注「无」，截止「未设置」，结束留空。模板不具备 AI 语义理解。编辑 fields 仅允许 name、priority、note；非新增必须带 `targets: [{key, version}]`，不接受编号定位。时间明确使用 Asia/Shanghai。

每次完整读取两文件，校验格式和版本；保留未知字段、BOM／换行及其他任务原文。兼容三个空格缩进和历史无缩进字段，编辑保留原字段缩进；历史归档「已放弃」原样保留并参与查重，新归档仍只写「已完成」／「已移除」。完成／删除先归档并验证，保留创建时间、填结束时间，再移除待办并重编号。支持旧结束时间 `—` 和空值，不批量改旧文档。

去重检查待办和归档：名称及 NFKC／空格／大小写归一名称相同，或「测试任务＋编号」与「测试＋同编号」的限定别名，返回 `DUPLICATE_TASK`；其他编辑距离相似候选返回 `SIMILAR_TASK`，附 duplicates 和 confirmation，尚未写入。明确确认时用新 UUID 和当前 token 重提；候选文件变化则重新确认，不全局删除“任务”，不同周期编号不合并。

私有回执记录幂等摘要、结果及未完成事务的前后字节快照，成功后删去快照。服务重启可查询／恢复原请求；同 UUID 改输入返回 `REQUEST_MISMATCH`。归档中断后只有文件仍匹配记录才继续，外部变化返回 `RECOVERY_REQUIRED` 并阻止新写入，不覆盖数据。两文件不是原子事务，部分写入或断电可能需人工核对。正文不进入运行日志，错误只返回安全码。

本机文件锁不保证与 PowerShell、编辑器、会话、坚果云及其他设备互斥；跨 Java／PowerShell 锁尚未实测。使用前确认同步完成，避免同时编辑两个文件，不扫描其他知识库文件。按用户选择，没有网站登录、每日限额或跨客户端协调，不宣称访问控制。测试仅使用合成文件，覆盖格式、去重、旧版本、重复请求、锁、批量及归档中断恢复。

## 固定周期生成

`RecurringTasks` 移植既有脚本的三个固定业务模板：工资转移每月 10 日 10:00（起始 2026-10），半月写作每月 1／15 日 10:00（起始 2026-10-15），工资调整每月 15 日 10:00（起始 2026-09）。全部使用 Asia/Shanghai 和 P1，保留专用目标／标准、备注周期标识与计划生成时间；新任务结束时间留空。创建时间取实际执行时间，不当作周期身份。

`todoist.recurring.enabled` 默认 false；显式设 true 后，ApplicationReadyEvent 启动服务内 `RecurringScheduler`，立即及每分钟向原 FIFO 投递私有检查操作。公开 TaskOperation 契约仍只有四种操作，HTTP 不能提交 recurring。关闭浏览器不影响检查，停止服务则停止，恢复时补建漏跑周期，不创建 launchd／系统任务，不调用脚本或模型。

在同一锁和事务内读取待办／归档，以周期标题或备注标识查重，同一期多份记录停止；已完成／移除／已放弃不再生成，上期未完成不影响下一期。编辑带标识任务的名称／备注保留原机器标识，不允许替换到其他周期；没有标识的历史记录仅按原标题匹配，不为统一格式改写旧任务。空检查不持久化空回执，有新增仍走原准备／验证／恢复流程，未确认结果不自动再次投递。

生产启用前先通过本机 preview 核对历史补建清单，再明确启用；测试始终覆盖 task-file、archive-file、state-dir 为合成绝对路径。新功能源码的实现／发布状态见 personWeb 根 ROADMAP，不能把本地构建当作线上启用。原 PowerShell 生成器、邮件提醒和外部调度均未改，也不提供任意周期配置页。错误日志仅输出固定码，不输出名称、字段、正文或异常消息。

## 持续集成

`Jenkinsfile` 仅手工触发，当前执行原工具 WAR 的测试、构建及产物归档，不自动构建 todoist 模块，不执行生产部署。todoist 需按上述独立命令验证和打包。
