# 工具站集成部署

本项目复用 `https://tools.20242233.xyz/Transfer`，无需独立域名。Vue 站点嵌入 `/transfer/?embedded=1`，Caddy 把 `/transfer/*` 原样转发到 `127.0.0.1:8765`；传输服务使用 `--prefix /transfer`。PWA 分享及资源作用域均为 `/transfer/`，不占用工具站根路径。

首次于 2026-10-28 随工具站 v0.7.0 部署，当前传输组件为 v3.3.2（界面优化：嵌入设置条、删除按钮禁用态、状态徽章条件渲染、跨年时间、缩略图限高），上一版 v3.3.1 改为 1 MiB 新分片及按进度计时的超时，保留分页和原有功能。启动项位于 `/Users/macminimai/Library/LaunchAgents/com.snailmai.transfer.plist`，WorkingDirectory 指向 `/Users/macminimai/ai/project/personal-tools/deploy/releases/transfer-ui-polish-20261106-165020/transfer-service/`；仍使用原虚拟环境，不依赖临时目录，不重新初始化密码或数据。

本次只恢复传输服务，其他组件不变。新包的 `sha256.json`、`deployment.json` 记录哈希及切换核对；旧启动项备份为 `/Users/macminimai/Library/Application Support/personweb/backups/transfer-mobile-upload-20261105-230802/com.snailmai.transfer.plist`。切换须等待旧进程和作业完全退出后再 bootstrap；恢复旧程序前先核对新分片未完成任务，不能回滚或删除真实数据。

## 生产变更范围

当前 v3.3.2 包为 `/Users/macminimai/ai/project/personal-tools/deploy/releases/transfer-ui-polish-20261106-165020/transfer-service/`，启动项备份位于运行目录 `backups/transfer-ui-polish-20261106-165020/`。仅前端界面优化；其他服务及产物、数据与会话核对未变，不迁移 schema。1 MiB 新分片与 8 MiB 续传兼容沿用，v3.3.1 同样支持该协议；回退到更早的 v3.3.0 时须先核对新 1 MiB 未完成任务，不能直接回滚数据或覆盖上传文件。

部署将新增一个本机传输服务及专用数据目录，发布 Vue 导航入口，并修改 personal-tools 的 Caddy 公开路径清单。已有 Java 工具和 todoist 的端口、待办文件及共享 Cloudflare Tunnel 无需改变。生产部署、首次真实密码初始化、自启动配置须在明确授权后进行，本轮代码接入不等于执行这些操作。

预定数据目录为 `/Users/macminimai/Library/Application Support/personweb/transfer-data`，包含数据库、原文件、缩略图、未完成上传和私有会话信息。先用 README 中的交互 `init` 设置独立传输密码，再运行 HTTPS 模式；不要把密码写到命令参数、Git 或日志。此密码不是电脑登录密码。

## 发布顺序与回退

1. 为正式服务准备项目虚拟环境，确认端口 8765 可用；初始化专用目录和传输密码。
2. 启动挂载模式，验证同源登录、文字发送及文件完整性。`com.snailmai.transfer.plist.example` 仅为本项目的用户 LaunchAgent 模板，不安装到其他项目或全局环境。
3. 备份工具站首页、Caddyfile 和发布指针；先增加代理并验证，再发布前端入口。修改 Caddy 后仅恢复 Caddy，不为此重启 Java 服务。
4. 用独立测试数据验收，真实目录只按用户明确指令操作；完成后进行 Windows／安卓跨网络验证。
5. 回退时恢复原首页和 Caddyfile、停止新增传输服务；保留传输数据，不回滚或删除真实历史，不改 todoist 数据。

Mac mini 关机、断网、休眠或用户 LaunchAgent 未运行时服务不可用。文件经服务端保存，不是设备间直连。共享隧道无需新增主机名或重启。
