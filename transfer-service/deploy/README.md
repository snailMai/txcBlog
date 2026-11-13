# 工具站集成部署

本项目复用 `https://tools.20242233.xyz/Transfer`，无需独立域名。Vue 站点嵌入 `/transfer/?embedded=1`，Caddy 把 `/transfer/*` 原样转发到 `127.0.0.1:8765`；传输服务使用 `--prefix /transfer`。PWA 分享及资源作用域均为 `/transfer/`，不占用工具站根路径。

当前传输组件为 v4.2.0，支持上传前命名与上传后重命名，保留原登录卡、共享会话及公网／LAN 入口。启动项位于 `/Users/macminimai/Library/LaunchAgents/com.snailmai.transfer.plist`，WorkingDirectory 指向 `/Users/macminimai/ai/project/personal-tools/deploy/releases/transfer-file-rename-20261109-011802/transfer-service/`；仍使用原虚拟环境，不初始化密码、数据或 schema。客户端证书及地址见宿主直连说明。

v4.1.3 只切换传输作业，包为上述 transfer-login-header-20261107-130500，备份位于 personweb 运行目录同名 backups/ 下（backups/transfer-login-header-20261107-130500 存有旧启动项）；运行目录 transfer-current-release.json 记录组件来源。回退只 bootout 传输作业、等待退出、恢复备份 plist 并 bootstrap，不回滚数据或共享登录配置，不重启 Vue、Caddy、Java、网关或隧道。

v3.4.0 首次发布时新增的 `com.snailmai.tools-lan` 已迁出并停用；当前 LAN 入口由独立项目 `local-web-gateway` 的用户作业管理，原 CA、8443 地址及传输来源参数保留。传输进程和数据未随网关迁移，本站发布不隐式重启共享入口；当前维护与回退入口见宿主 `deploy/LAN.md`。

## 生产变更范围

2026-11-09 01:18 定点发布传输 v4.2.0，包为上述 transfer-file-rename-20261109-011802，备份位于 personweb 运行目录同名 backups/；来源回执为 transfer-current-release.json。仅切换传输作业，Vue、Caddy、Java、共享网关和隧道未改。失败回退只等待传输作业退出、恢复备份 plist 并重新加载旧包，不回滚数据库或文件；旧程序可读取新名称，后缀及存储格式不变。

共享鉴权基线及首次配套发布备份见宿主 `deploy/README.md` 的 v4.0.0 记录；后续传输定点发布仍须兼容该鉴权链。旧匿名 Caddy 不能单独恢复，故障时保持任务接口关闭，不回滚真实密码、会话、传输或任务数据。局域网代理和 CA 已独立到 `/Users/macminimai/Library/Application Support/local-web-gateway`，它同时服务 learn／kb；本站回退不能停掉共享作业。

v4.1.3 同时发布工具站前端 todoist 登录卡（横排双语头部、与传输同一绿色按钮），前端发布包为宿主 `deploy/releases/todoist-login-unify2-20261107-130900/`；其余组件未动。

v4.1.0 只切换传输作业，包为上述 transfer-image-preview-20261107-110344，备份位于 personweb 运行目录同名 backups/ 下；运行目录 transfer-current-release.json 记录组件来源。回退只 bootout 传输作业、等待退出、恢复备份 plist 并 bootstrap，不回滚数据或共享登录配置，不重启 Vue、Caddy、Java、网关或隧道。

部署将新增一个本机传输服务及专用数据目录，发布 Vue 导航入口，并修改 personal-tools 的 Caddy 公开路径清单。已有 Java 工具和 todoist 的端口、待办文件及共享 Cloudflare Tunnel 无需改变。生产部署、首次真实密码初始化、自启动配置须在明确授权后进行，本轮代码接入不等于执行这些操作。

预定数据目录为 `/Users/macminimai/Library/Application Support/personweb/transfer-data`，包含数据库、原文件、缩略图、未完成上传和私有会话信息。先用 README 中的交互 `init` 设置独立传输密码，再运行 HTTPS 模式；不要把密码写到命令参数、Git 或日志。此密码不是电脑登录密码。

## 发布顺序与回退

1. 为正式服务准备项目虚拟环境，确认端口 8765 可用；初始化专用目录和传输密码。
2. 启动挂载模式，验证同源登录、文字发送及文件完整性。`com.snailmai.transfer.plist.example` 仅为本项目的用户 LaunchAgent 模板，不安装到其他项目或全局环境。
3. 备份工具站首页、Caddyfile 和发布指针；先增加代理并验证，再发布前端入口。修改 Caddy 后仅恢复 Caddy，不为此重启 Java 服务。
4. 用独立测试数据验收，真实目录只按用户明确指令操作；完成后进行 Windows／安卓跨网络验证。
5. 回退时恢复原首页和 Caddyfile、停止新增传输服务；保留传输数据，不回滚或删除真实历史，不改 todoist 数据。

Mac mini 关机、断网、休眠或用户 LaunchAgent 未运行时服务不可用。文件经服务端保存，不是设备间直连。共享隧道无需新增主机名或重启。
