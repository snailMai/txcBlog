# 工具站集成部署

本项目复用 `https://tools.20242233.xyz/Transfer`，无需独立域名。Vue 站点嵌入 `/transfer/?embedded=1`，Caddy 把 `/transfer/*` 原样转发到 `127.0.0.1:8765`；传输服务使用 `--prefix /transfer`。PWA 分享及资源作用域均为 `/transfer/`，不占用工具站根路径。

首次于 2026-10-28 随工具站 v0.7.0 部署，当前传输组件为 v3.2.4，包含紧凑发送按钮、自动伸缩输入区、紧凑记录卡片、顶部操作栏、上传超时／取消及重试修复、批量删除、图片复制和网页改密。实际启动项位于 `/Users/macminimai/Library/LaunchAgents/com.snailmai.transfer.plist`，WorkingDirectory 指向 `/Users/macminimai/ai/project/personal-tools/deploy/releases/transfer-send-20261030-092756/transfer-service/`；使用保留的 `/Users/macminimai/ai/project/snail-transfer/.venv/bin/python`，不从临时测试目录运行。第一次生产密码记录位于专用数据目录的 `access-password.txt`（0600），不会输出到部署日志；后续发布不重新初始化或覆盖密码，网页改密也不会更新首次密码记录。

本次只恢复传输服务，其他组件不变。新包上级目录的 `sha256.json`、`deployment.json` 记录文件哈希和验证结果；旧启动项备份为 `/Users/macminimai/Library/Application Support/personweb/backups/transfer-send-20261030-092756/com.snailmai.transfer.plist`。回退时先 bootout 传输作业，恢复该启动项，再 bootstrap；旧发布目录完整保留，不回滚或删除真实数据。网页已改成 10 位密码后，回退程序仍能用该密码登录；原版本的登录校验不限制最短长度。

## 生产变更范围

部署将新增一个本机传输服务及专用数据目录，发布 Vue 导航入口，并修改 personal-tools 的 Caddy 公开路径清单。已有 Java 工具和 todoist 的端口、待办文件及共享 Cloudflare Tunnel 无需改变。生产部署、首次真实密码初始化、自启动配置须在明确授权后进行，本轮代码接入不等于执行这些操作。

预定数据目录为 `/Users/macminimai/Library/Application Support/personweb/transfer-data`，包含数据库、原文件、缩略图、未完成上传和私有会话信息。先用 README 中的交互 `init` 设置独立传输密码，再运行 HTTPS 模式；不要把密码写到命令参数、Git 或日志。此密码不是电脑登录密码。

## 发布顺序与回退

1. 为正式服务准备项目虚拟环境，确认端口 8765 可用；初始化专用目录和传输密码。
2. 启动挂载模式，验证同源登录、文字发送及文件完整性。`com.snailmai.transfer.plist.example` 仅为本项目的用户 LaunchAgent 模板，不安装到其他项目或全局环境。
3. 备份工具站首页、Caddyfile 和发布指针；先增加代理并验证，再发布前端入口。修改 Caddy 后仅恢复 Caddy，不为此重启 Java 服务。
4. 用独立测试数据验收，真实目录只按用户明确指令操作；完成后进行 Windows／安卓跨网络验证。
5. 回退时恢复原首页和 Caddyfile、停止新增传输服务；保留传输数据，不回滚或删除真实历史，不改 todoist 数据。

Mac mini 关机、断网、休眠或用户 LaunchAgent 未运行时服务不可用。文件经服务端保存，不是设备间直连。共享隧道无需新增主机名或重启。
