# 三条通道的构建

按需构建，不必三条全通。网关会按实际状态自动判定可用性。

## 0. 先体检

```bash
node scripts/dev.mjs --check
```

输出会明确告诉你缺什么，例如：

```
ok   Node 22.22.2
warn JDK 8 低于要求的 25，Stirling 通道不可运行
FAIL Stirling 构建产物 — 未构建，先看 docs/build.md
FAIL PDFPatcher CLI — ...\integration\patcher-cli\bin\PDFPatcher.Cli.exe
```

---

## 1. Stirling-PDF（主力，99 项能力）

**前提：JDK 25**。上游 `build.gradle` 写死 `modernJavaVersion = 25`，用 17 或 21 编不过。

```bash
# 装 JDK 25 后（Adoptium Temurin / Liberica 均可），确认：
java -version          # 应显示 25.x

cd engines/stirling-pdf

# Windows 用 gradlew.bat，Linux/macOS 用 ./gradlew
./gradlew.bat :app:core:bootJar

# 产物默认在 app/core/build/libs/，启动：
java -jar app/core/build/libs/*.jar
# 访问 http://127.0.0.1:8080
```

首次构建要点：

| 事项 | 说明 |
|---|---|
| Gradle 9.7.1 | wrapper 会自动下载（约 150 MB），国内网络建议配镜像 |
| 依赖体积 | Spring Boot + PDFBox + LibreOffice 桥接，约 600 MB 缓存 |
| 前端 | `frontend/` 是独立 Vite 工程，`bootJar` 会带上构建好的静态资源；只调 API 不必单独构建 |
| 转换类功能 | `pdf/word`、`file/pdf` 依赖内置 LibreOffice，Windows 上需确认 soffice 可用 |

不想编译的话，官方 Docker 镜像与本机 jar 都可用，只要 `http://127.0.0.1:8080/api/v1/info/status` 能返回即可；网关不关心它怎么起来的。

---

## 2. PDFPatcher（中文书签补位，8 项能力）

**前提：.NET Framework 开发工具链**（Visual Studio 2019+ 或 Build Tools，含 .NET Framework 4.0 目标包）。
仅有运行时不够——`App/Lib` 里的 MuPDFLib / itextsharp 是编译期引用。

```powershell
# 1) 先编上游本体，产出 PDFPatcher.exe
msbuild engines\pdfpatcher\PDFPatcher.sln /p:Configuration=Release /p:Platform="Any CPU"

# 2) 再编 CLI 包装（引用上一步的 exe）
msbuild integration\patcher-cli\PatcherCli.csproj /p:Configuration=Release
# 产物：integration\patcher-cli\bin\PDFPatcher.Cli.exe
```

**当前状态：未接线。** `PatcherCli.cs` 里的 8 个命令都是 `throw new NotSupportedException`，
需要按 `TODO` 注释接上 `PDFPatcher.Processor.*` 的真实调用。原因见 `integration/patcher-cli/README.md`。

未接线前，这 8 项能力在注册表里标记 `available:false`，并给出 Stirling 的等价替代——不会阻断工作台。

---

## 3. ToolKnit Desktop（桌面壳，17 项内置能力）

内置工具是纯前端 pdf-lib 实现，**不需要构建**即可通过工作台调用其能力定义；但要跑它的完整界面需要：

```bash
cd engines/toolknit-desktop/toolknit-desktop
npm install            # 36 个依赖，含 @napi-rs/canvas 原生模块
npm run dev            # Vite 开发服务器
npm run tauri:dev      # 桌面窗口（需 Rust 工具链）
```

接入方式（把 PDF 工作台挂成 ToolKnit 里的一个工具页）见 `integration/desktop/README.md`。

---

## 4. 验证整合层

```bash
node tests/e2e.mjs
```

不需要 Java、不需要 .NET：mock Stirling 实现了状态、合并、页数分析三个端点，
用来验证网关的上传、排队、落盘、下载、错误处理全链路。14 项断言。

真机联调时把网关指向真实 Stirling 即可：

```bash
PDF_STIRLING_BASE=http://127.0.0.1:8080 node integration/gateway/server.mjs
```

## 环境变量

| 变量 | 默认 | 用途 |
|---|---|---|
| `PDF_GATEWAY_PORT` | 8931 | 网关端口 |
| `PDF_GATEWAY_HOST` | 127.0.0.1 | 监听地址，勿改成 0.0.0.0 |
| `PDF_STIRLING_BASE` | http://127.0.0.1:8080 | Stirling 地址 |
| `PDF_STIRLING_TIMEOUT` | 600000 | 单次调用超时（ms） |
| `PDF_PATCHER_CLI` | integration/patcher-cli/bin/PDFPatcher.Cli.exe | CLI 路径 |
| `PDF_PATCHER_TIMEOUT` | 300000 | CLI 超时（ms） |
| `PDF_GATEWAY_PARALLEL` | 2 | 并发作业数 |
| `PDF_MAX_UPLOAD` | 536870912 | 单次上传上限（512 MB） |
| `PDF_JOBS_ROOT` | .work/jobs | 作业产物目录 |
