# 蜗牛传送

当前源码及传输组件版本为 `3.2.4`，属于 personal-tools 的 V3 电脑手机同步阶段，发送按钮采用紧凑样式，输入区随内容自动伸缩，采用紧凑记录卡片及顶部操作栏，保留上传超时／取消与重试、批量删除、图片复制和网页改密。

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

传输服务已部署 v3.2.4，包含紧凑发送按钮、自动伸缩输入区、紧凑记录卡片、顶部操作栏、上传超时／取消与重试修复、记录批量删除、图片复制及网页改密；外层工具站及 Java 组件保持原部署。入口为 `https://tools.20242233.xyz/Transfer`。生产采用独立发布包中的代码，项目专用用户 LaunchAgent `com.snailmai.transfer` 在登录后启动并自动恢复；数据目录为上述 `transfer-data`，首次访问密码记录位于其中的 `access-password.txt`（0600），不写入日志或网页目录。该文件仅记录首次密码，网页改密后不会更新，不能作为当前密码依据。该密码不是电脑登录密码，无需重复初始化已有数据。入口及自启动步骤见 [部署说明](/Users/macminimai/ai/project/personal-tools/back-end/blog/transfer-service/deploy/README.md)；验收状态见 [ROADMAP.md](/Users/macminimai/ai/project/personal-tools/back-end/blog/transfer-service/ROADMAP.md) 。

## 使用和数据

- 两端用同一个传输密码登录，发送文字、选择文件；Windows 还可拖放或粘贴截图。时间线支持搜索、类型筛选、复制、原图及文件下载、手动删除。
- 输入框随文字、换行及窗口宽度自动增高或缩回，空白从一行开始，手机占位文字按宽度自然换行；最多约 8 行，超出后在框内滚动。删除文字、发送成功或分享草稿填入后同步高度，发送未确认时保留原内容；粘贴到末尾时保持末尾可见。底部常驻提示栏已移除，容量达到 90% 时仍显示警告。
- 发送按钮采用紧凑尺寸，发送中、取消及发送完成后保持相同高度和文字／箭头结构；手机端发送与取消可换行显示，不挤压到屏幕外。
- 记录顶部集中显示来源、状态和时间，右侧操作依次为：文字「复制文字 → 删除记录」、图片「复制图片 → 下载 → 删除记录」、文件「下载 → 删除记录」。混合记录保留文字复制及各附件操作，多附件显示对应文件名，删除记录始终最后；手机端允许操作区在正文上方换行。图片使用小缩略图，点击仍可查看原图，下载按钮保存原文件。
- 批量删除：每条记录左侧有勾选框，上方提供「全选已加载记录」、已选数量和「删除所选」。一次最多 1000 条，确认后移除原文及服务器附件，无法撤销；已下载副本不变。全选只覆盖当前已加载列表，继续加载的记录不会自动勾选；搜索或切换类型清空勾选。原来的逐条删除仍可使用。该功能已随 v3.2.0 上线。
- 修改密码：登录后在页面下方点击「修改密码」，填写旧密码、新密码及确认密码，再点击「保存新密码」。新密码允许 10～256 个字符，不强制大小写、数字或符号组合，空格按原样保留。成功后所有设备退出登录，需使用新密码登录；文字、图片和文件不变。旧密码校验沿用 15 分钟最多 10 次的登录限流，失败不会改变密码或会话；改密时请先等待当前发送完成。
- 每张历史图片提供「复制图片」按钮，将原图内容写入当前设备剪贴板，可粘贴到支持图片的聊天框或编辑器。PNG 直接复制，JPEG／WebP／BMP 等转为原始分辨率 PNG，透明背景保留，GIF 复制首帧；服务端原文件不变。需要 HTTPS（或本机回环地址）、浏览器支持及剪贴板写入权限；超时、权限拒绝或图片损坏会显示失败提示。为控制内存，超过 50 MiB 或 3000 万像素的图片请下载原图后使用。安卓实际复制和目标 App 粘贴仍需实机验收。
- 文件上限 500 MB，分片 8 MiB；刷新后重新选择相同原文件可续传。上传需保持页面打开，跨网络和安卓后台行为需真机验证。
- 上传提供阶段提示、进度和「取消发送」：文件读取／校验及普通请求最多等待 30 秒，单片上传 60 秒，服务端完整校验 90 秒；超时恢复操作，保留已确认分片。取消后可再次发送，刷新后的未完成分片需重新选择原文件。分片未获确认时先查询服务器再决定是否重传；记录提交结果未知时，保持页面和原内容，再次发送沿用原标识核对，避免重复记录。保存完成后的历史刷新不再阻塞发送按钮。该修复已随 v3.2.1 上线。
- 页面每 5 秒检查新记录；发送成功表示服务端已保存，不等于对方已下载。原文、原文件长期保留，未提交上传有过期清理，满额拒绝新增。
- SQLite 保存记录，附件保存在独立磁盘目录；默认容量 20 GB。此数据与 todoist 两份 Markdown 无关。`data/`、真实凭据和备份均不提交 Git。
- 单账号登录和 CSRF 校验保留；文件、缩略图和备份均须授权。工具站挂载模式仅允许同源嵌入，Cookie／Service Worker 不越出 `/transfer/`。PWA 安装及安卓系统分享使用 `/transfer/` 独立页面，不能以桌面测试代表安卓实测。
- 运行中从页面导出完整备份；离线可用 `backup --data … --output …`。恢复使用 `restore <备份路径> --data <不存在的新目录>`，核对清单及文件哈希，不覆盖运行数据。

## 验证

```bash
.venv/bin/python -m unittest discover -s tests -v
node --check transfer/static/app.js
node --check transfer/static/transfer-io.mjs
node --check transfer/static/image-clipboard.mjs
node --check transfer/static/sw.js
node --test tests/*.test.*
```

测试使用项目 `.tmp/tests/` 下的独立数据。需要实际 500 MB 测试时设置 `TRANSFER_LARGE_TEST=1`，会产生对应临时磁盘占用。代码、部署模板与真实运行配置分别管理，不随修改源码自动部署。
