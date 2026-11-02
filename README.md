# CUHKCampus

本机运行的课程、资料、邮件、任务、日历与 AI 工作空间。产品规格见 [cuhkcampus.md](cuhkcampus.md)。

## 环境

- Java 21 或更高版本（源码以 Java 21 编译）。
- Node.js 22 或更高版本、npm。
- Docker Desktop，用于 PostgreSQL 17。
- LibreOffice，`soffice` 在 PATH 中，用于 Word、PowerPoint 和 Excel 原排版预览；也可设置 `CAMPUS_SOFFICE_BINARY`。

## Windows

在 Windows 10/11 的项目根目录运行以下命令。setup 会检查运行环境，通过 WinGet 自动下载安装缺失的 JDK 21、Node.js LTS（含 npm）、Google Chrome、LibreOffice、curl 和 Docker Desktop（含 Compose），随后下载 PostgreSQL 镜像及项目依赖。每项下载前都会在终端提示软件名称和安装过程。已有满足版本要求的软件不会重复安装。

安装时会弹出 Windows 管理员授权；若系统没有 WinGet，脚本会先从微软安装 WinGet。首次启用 WSL 2 可能需要重启，脚本会提示重启后再次运行 setup，不会自动重启电脑。Docker 首次启动的协议或系统虚拟化要求需在其窗口完成。

```bat
scripts\setup.cmd
scripts\start.cmd
```

PowerShell 也可以运行以上 `.cmd` 文件，或直接运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\start.ps1
```

安装脚本生成本地 `.env`、数据库密码和加密密钥，自动启动 Docker 并等待 PostgreSQL 就绪后构建前后端；已有 `.env` 保留。启动后访问 `http://localhost:8080`，在终端按 Ctrl+C 停止应用。脚本可以从其他目录执行，也支持路径含空格。

Chrome 与 LibreOffice 会从 Windows 常见安装目录查找。自定义安装位置可在 `.env` 中配置，路径使用正斜杠、不加引号：

```properties
CAMPUS_CHROME_BINARY=C:/Program Files/Google/Chrome/Application/chrome.exe
CAMPUS_SOFFICE_BINARY=C:/Program Files/LibreOffice/program/soffice.exe
```

使用本机或远程 PostgreSQL 时，先复制 `.env.example` 为 `.env` 并配置数据库连接、密码与 `CAMPUS_ENCRYPTION_KEY`（32 字节随机值的 Base64），然后执行 `scripts\setup.cmd -SkipDocker` 和 `scripts\start.cmd -SkipDocker`。此模式要求数据库已创建并启动。安装脚本需要 Node.js，日常启动只需要 Java 及相关运行依赖。

## 启动

```sh
./scripts/setup.sh
./scripts/start.sh
```

打开 [http://localhost:8080](http://localhost:8080)，创建本地账号。首次安装会生成 `.env` 中的数据库密码和凭据加密密钥，构建前后端。数据库绑定本机 `5438` 端口，数据保存在 Docker 持久卷；原文件和预览保存在 `data/`。

开发时运行 `./scripts/dev.sh`，前端地址为 [http://localhost:5173](http://localhost:5173)。前端通过 Vite 代理调用本机后端。

## 模型与连接

在设置 → AI 模型中分别保存 Agent 与文档翻译配置。

服务地址填写完整 API 基址（例如 `https://api.openai.com/v1`）。服务地址、模型和 API Key 按账号保存，密钥加密存储；可直接获取服务端模型列表并自动补全模型名称。翻译使用独立配置，不创建聊天历史。

ChatGPT 订阅模式使用官方 [Sign in with ChatGPT](https://developers.openai.com/siwc/token-sharing-open-source)。连接后选择实际模型并完成计划权限授权。订阅用量计入原计划，API Key 模式分别配置、独立选择。该接入按个人本地应用实现；商业或远程托管场景需遵循官方接入范围。

Blackboard 使用一次性 Chrome 登录窗口。在设置 → 数据连接点击登录后会打开 [Blackboard](https://bb.cuhk.edu.cn)，完成登录后自动读取站点会话 token（Cookie）、加密保存到当前账号并关闭窗口。后端每次通过保存的会话直接请求 BB API，不保留后台浏览器，也不运行全量同步任务。

页面和 Copilot 共用按需访问逻辑：课程列表只请求课程元数据；打开课程只请求该课程；展开目录只读取下一层；打开内容才读取正文和作业截止时间；附件列表与文件下载分别请求。公告按课程读取，首页选择课程后加载公告。本地保留已访问资源的 ID、个人备注、已读状态和附件元数据，以便笔记、任务和文档链接保持关联。PPT/PPTX 预览由 LibreOffice UNO 加载一次文档，后台连续逐页导出，每页完成后通过 SSE 通知前端立即显示，转换不由翻页触发。页面与缩略图按文件缓存；当前页图片用于翻译和 Copilot。已有整份 PDF 预览会直接拆页复用。文档中心和文件搜索展示已浏览的附件及本地资料；未浏览的 BB 资料从课程目录进入。用户打开、预览、读取正文或下载附件时，才拉取对应文件并持久化。会话过期后重新登录；API 返回失败，不使用旧数据代替本次请求。系统需要 `curl`，可通过 `CAMPUS_CURL_BINARY` 指定路径。

BB 访问使用现有应用 API（均需当前用户登录，后端代为携带该用户的 BB token）：

| 请求 | 访问范围 |
| --- | --- |
| `GET /api/v1/courses` | 课程列表 |
| `GET /api/v1/courses/{id}` | 一门课程的基本信息、教师 |
| `GET /api/v1/courses/{id}/content?parentId=...` | 根目录或指定目录的下一层 |
| `GET /api/v1/content/{id}` | 一项内容的正文、作业截止时间 |
| `GET /api/v1/content/{id}/documents` | 一项内容的附件元数据 |
| `GET /api/v1/announcements?courseId=...` | 一门课程的公告 |
| `GET /api/v1/announcements/{id}` | 一条公告 |
| `GET /api/v1/documents/{id}/prepare` | 按需准备文件，通过 SSE 返回已下载字节数和总大小 |
| `GET /api/v1/documents/{id}/file` | 下载并返回文件 |

文件预览区显示 BB 下载、预览加载和保存原文件的实际传输进度与百分比；远端未提供总大小时显示已接收字节数。所有 API 请求显示顶部加载动画。课程页的文档与公告区域限制高度，超出后在区域内滚动。

以上路径中的 ID 为应用返回的资源 UUID。`POST /api/v1/connections/blackboard/sync` 已移除。

Outlook 保留 Graph 登录；开发者在服务端 `.env` 设置 `CAMPUS_OUTLOOK_CLIENT_ID` 和 `CAMPUS_OUTLOOK_REDIRECT_URI`，用户界面不展示应用配置。应用回调注册为“移动和桌面应用程序”：`http://localhost:8080/api/v1/connections/outlook/callback`，使用 PKCE，无需 Client Secret。学校租户可能要求管理员同意。


Blackboard 可保存学校账号密码并无头登录；课程、公告、文件请求遇到会话过期时自动重新登录并重试一次。密码在数据库中通过 `CAMPUS_ENCRYPTION_KEY` 加密，不返回客户端；断开 BB 时同时删除保存的账号密码。BB 仅使用无头自动登录；遇到验证码或 MFA 会明确报错，不再提供窗口登录。浏览器运行在后端所在电脑，需安装 Chrome，可用 `CAMPUS_CHROME_BINARY` 配置路径。


授权后勾选邮件文件夹并同步；邮件中心“搜索 Outlook”直接检索云端邮件，日历中的“添加到 Outlook”将选中的日程写入本人日历。ChatGPT 回调使用 `http://127.0.0.1:8080/api/v1/connections/chatgpt/callback`。

## 验证

```sh
cd frontend
npm run build
cd ../backend
./mvnw -B -ntp -Dmaven.repo.local=../.tools/m2 test
```

真实 ChatGPT 订阅与 Outlook 的验收需要对应账号的授权与实际服务调用。Blackboard 可使用 `scripts/verify_blackboard.py --user-id <账号 UUID> --storage-root data/files` 对照远端 API 核验已访问资源及已下载文件哈希。默认只检查课程列表；指定 `--course-id <BB 课程 ID>` 检查一层内容目录，`--parent-id <BB 目录 ID>` 检查其下一层，`--content-id <BB 内容 ID>` 检查其附件，`--announcements` 检查所选课程公告。验证不会递归遍历目录，未下载的文件不要求存在。协议测试与本地业务测试分别验证代码行为，不代替真实账号验收。

端到端验证脚本为 `scripts/verify.py`，使用独立的 `8081` 测试进程及明确的本地模型协议测试服务。验证结果保存到 `.run/verification-result.json`；测试不会修改 `.env`。更新构建前先停止正在运行的后端，再重新启动。
