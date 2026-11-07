# 蜗牛传送

当前源码版本为 `v4.1.0`，新增图片弹窗预览及缩放，复用已有鉴权原图接口；保留 v4.0.0 的 todoist 共享登录和全部传输功能，单实例数据不变。部署和实机状态见 ROADMAP。

新增参数示例：`--origin https://tools.20242233.xyz --additional-origin https://192.168.3.195:8443 --additional-origin https://192.168.3.159:8443`。每个入口独立登录，Cookie 保持 Secure／HttpOnly／SameSite，禁止跨入口混用 Origin。主入口为 HTTP 时不能配置额外入口，不接受通配符或任意客户端来源。局域网证书安装和运行入口见宿主 [直连说明](/Users/macminimai/ai/project/personal-tools/deploy/LAN.md) 。

Windows／安卓共用的私人传输空间，支持文字、图片、文件和历史记录。数据保存在当前 Mac mini；两端通过工具站入口访问，原文件按需下载。文件下载后的本地修改不会回传。

## 运行

正式源码位于 txcBlog 仓库的 `transfer-service/`，独立运行，不参与 Maven 构建。使用 Python 3.11 及以上版本，依赖锁定于 `requirements.txt`。新环境从本目录运行：

```bash
cd /Users/macminimai/ai/project/personal-tools/back-end/blog/transfer-service
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m transfer run --data data --port 8765
```

当前 Mac mini 的原虚拟环境保留在 `/Users/macminimai/ai/project/snail-transfer/.venv/`，生产仍使用其中的 Python；可以从新源码目录直接用该 Python 替代上面命令中的 `.venv/bin/python`，无需移动环境。使用启动脚本时可显式指定：

```bash
TRANSFER_PYTHON=/Users/macminimai/ai/project/snail-transfer/.venv/bin/python sh scripts/run.sh --data data --port 18765
```

独立本机入口为 `http://127.0.0.1:8765/`，首次可在本机设置传输密码。对外 HTTPS 模式先通过 `init` 交互设置密码，仅保存哈希；不使用或索取 macOS 登录密码。

工具站集成以同源 `/transfer/` 承载应用，Vue 页面为 `/Transfer`：

```bash
.venv/bin/python -m transfer init --data '/Users/macminimai/Library/Application Support/personweb/transfer-data'
.venv/bin/python -m transfer run --origin https://tools.20242233.xyz --prefix /transfer --port 8765 --data '/Users/macminimai/Library/Application Support/personweb/transfer-data'
```

传输服务已部署 v4.1.0，新增图片弹窗预览并保留 todoist 共享登录；入口为 `https://tools.20242233.xyz/Transfer`。项目专用用户 LaunchAgent 使用独立发布包，登录后启动并自动恢复；数据目录为上述 `transfer-data`。access-password.txt（0600）仅记录首次密码，网页改密后不会更新，不作为当前密码依据；不重新初始化已有数据或凭据。入口及自启动见 [部署说明](/Users/macminimai/ai/project/personal-tools/back-end/blog/transfer-service/deploy/README.md)，验收状态见 ROADMAP。

## 使用和数据

- 两端用同一个传输密码登录，发送文字、选择文件；Windows 还可拖放或粘贴截图。时间线支持搜索、类型筛选、复制、原图及文件下载、手动删除。
- 输入框随文字、换行及窗口宽度自动增高或缩回，空白从一行开始，手机占位文字按宽度自然换行；最多约 8 行，超出后在框内滚动。删除文字、发送成功或分享草稿填入后同步高度，发送未确认时保留原内容；粘贴到末尾时保持末尾可见。底部常驻提示栏已移除，容量达到 90% 时仍显示警告。
- 发送按钮采用紧凑尺寸，发送中、取消及发送完成后保持相同高度和文字／箭头结构；手机端发送与取消可换行显示，不挤压到屏幕外。
- 记录顶部集中显示来源、状态和时间，右侧操作依次为：文字「复制文字 → 删除记录」、图片「复制图片 → 下载 → 删除记录」、文件「下载 → 删除记录」。混合记录保留文字复制及各附件操作，多附件显示对应文件名，删除记录始终最后；手机端允许操作区在正文上方换行。点击图片缩略图在当前同步区域弹窗查看原图，不新开页签；初始适应窗口，支持放大／缩小、100%、滚轮或双指缩放、放大后拖动。关闭按钮、Esc 或桌面遮罩可关闭，恢复原列表位置；加载失败可重试，关闭取消请求并释放资源。下载仍保存原文件。
- 传输记录默认每页 5 条，底部可选 5／10／20 条，通过「上一页／下一页」浏览；翻页替换列表，显示当前页及匹配总数。搜索、类型或条数改变、手动刷新及发送成功回到第一页。浏览旧页或已有勾选时，新记录只触发更新提示，点击提示回首页刷新；删除导致末页为空时回到上一有效页。旧接口不传 `page_size` 时仍返回最多 30 条。
- 批量删除：每条记录左侧有勾选框，上方提供「全选本页」、已选数量和「删除所选」。翻页、搜索、切换类型或每页条数、刷新时清空勾选，不能跨页累计；确认后移除原文及服务器附件，无法撤销，已下载副本不变。原来的逐条删除仍可使用；服务端批量接口保留最多 1000 条的既有边界。
- 修改密码：登录后在页面下方点击「修改密码」，填写旧密码、新密码及确认密码，再点击「保存新密码」。新密码允许 10～256 个字符，不强制大小写、数字或符号组合，空格按原样保留。成功后所有设备退出登录，需使用新密码登录；文字、图片和文件不变。旧密码校验沿用 15 分钟最多 10 次的登录限流，失败不会改变密码或会话；改密时请先等待当前发送完成。
- 每张历史图片提供「复制图片」按钮，将原图内容写入当前设备剪贴板，可粘贴到支持图片的聊天框或编辑器。PNG 直接复制，JPEG／WebP／BMP 等转为原始分辨率 PNG，透明背景保留，GIF 复制首帧；服务端原文件不变。需要 HTTPS（或本机回环地址）、浏览器支持及剪贴板写入权限；超时、权限拒绝或图片损坏会显示失败提示。为控制内存，超过 50 MiB 或 3000 万像素的图片请下载原图后使用。安卓实际复制和目标 App 粘贴仍需实机验收。
- 文件上限 500 MB，新上传默认每片 1 MiB；已有 8 MiB 任务按首片校验值识别并继续，不迁移数据库或修改原文件。新页面依照服务返回的 chunk_size 传输；连接旧服务时保留 8 MiB 兼容。刷新后重新选择相同原文件可续传，上传需保持页面打开。
- 上传提供阶段、进度和取消：读取／校验及普通请求超时 30 秒，上传连续 60 秒没有新增字节进度时超时，正常传输不因累计超过 60 秒中断；每片累计上限 5 分钟，服务端完整校验 90 秒。取消／超时恢复操作并保留确认片，重试先查状态；记录提交未知沿用原标识，保存后的刷新不阻塞按钮。v3.3.1 改善慢速链路，安卓实机结果仍需核对。
- 页面每 5 秒检查新记录；发送成功表示服务端已保存，不等于对方已下载。原文、原文件长期保留，未提交上传有过期清理，满额拒绝新增。
- SQLite 保存记录，附件保存在独立磁盘目录；默认容量 20 GB。此数据与 todoist 两份 Markdown 无关。`data/`、真实凭据和备份均不提交 Git。
- 单账号登录和 CSRF 校验保留；文件、缩略图和备份均须授权。工具站挂载模式仅允许同源嵌入，资源及 Service Worker 不越出 `/transfer/`；共享 Cookie 使用 `/`，保持 host-only、Secure／HttpOnly／SameSite，在会话检查时兼容迁移旧 `/transfer/` Cookie。PWA 安装及安卓系统分享仍使用 `/transfer/` 独立页面。
- `/transfer/api/todoist-authorize` 供本站 Caddy 的 `forward_auth` 调用，只做鉴权，不接触任务文件。Caddy 覆写原始方法头；GET 校验会话，POST 额外校验精确 Origin 与 CSRF，其他方法拒绝。退出、会话过期、改密撤销同时作用于 todoist；认证服务不可用时代理拒绝放行。单独发布 Java 或登录界面不能提供此权限。
- 运行中从页面导出完整备份；离线可用 `backup --data … --output …`。恢复使用 `restore <备份路径> --data <不存在的新目录>`，核对清单及文件哈希，不覆盖运行数据。

## 验证

```bash
.venv/bin/python -m unittest discover -s tests -v
node --check transfer/static/app.js
node --check transfer/static/transfer-io.mjs
node --check transfer/static/image-clipboard.mjs
node --check transfer/static/image-preview.mjs
node --check transfer/static/sw.js
node --test tests/*.test.*
```

测试使用项目 `.tmp/tests/` 下的独立数据。需要实际 500 MB 测试时设置 `TRANSFER_LARGE_TEST=1`，会产生对应临时磁盘占用。代码、部署模板与真实运行配置分别管理，不随修改源码自动部署。
