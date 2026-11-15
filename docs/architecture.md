# 整合架构

## 为什么不是「合并代码库」

三个项目的技术栈互不兼容，直接合并会得到一个既编不过也跑不起来的东西：

| 项目 | 语言/运行时 | 形态 | 独有资源 |
|---|---|---|---|
| Stirling-PDF | Java 25 / Spring Boot | 服务端 + React 前端 | LibreOffice、Tesseract、PDFBox、管线引擎 |
| PDFPatcher | C# / .NET 4.0 | WinForms 桌面程序 | MuPDF 封装、iText 中文书签逻辑 |
| ToolKnit | JS + Rust (Tauri) | 桌面应用 | pdf-lib 前端处理链、插件式工具框架 |

可行的整合点是**能力**而不是代码：把三者的功能抽成统一编号的能力清单，再由一层薄网关按能力路由到对应引擎。

```
┌─────────────────────────────────────────────┐
│  PDF 工作台（integration/desktop/pdf-hub）   │  统一界面：搜索 / 分类 / 执行 / 下载
└───────────────────┬─────────────────────────┘
                    │ HTTP (127.0.0.1:8931)
┌───────────────────▼─────────────────────────┐
│  能力网关（integration/gateway）             │
│  · capabilities.json → 124 项能力            │
│  · 作业队列（并发 2）· 产物隔离目录           │
│  · 引擎探测与降级判定                         │
└───┬───────────────┬───────────────┬─────────┘
    │ multipart     │ spawn         │ 直接打开
┌───▼──────┐  ┌─────▼──────┐  ┌────▼───────┐
│ Stirling │  │ PDFPatcher │  │ ToolKnit   │
│ :8080    │  │ CLI.exe    │  │ 前端工具页  │
└──────────┘  └────────────┘  └────────────┘
```

## 三个关键设计

### 1. 能力清单是唯一事实来源

`integration/registry/capabilities.json` 由四份输入合成：

```
stirling-capabilities.json   自动生成（源码注解 + zh-CN 语言包）
patcher-capabilities.json    手工维护（8 项独有能力）
native-capabilities.json     手工维护（17 项前端内置）
overrides.json               手工覆盖（多文件字段名、输出类型）
        ↓  node integration/registry/build.mjs
capabilities.json            124 项，前端与网关都只读这一份
```

Stirling 的能力不是手抄的：`scripts/gen-registry.py` 扫描 `@AutoJobPostMapping` / `@PostMapping`，
再用 `@MiscApi` 这类组合注解还原 `/api/v1/<group>` 前缀，最后从 `frontend/public/locales/zh-CN/translation.toml`
取中文标题与描述。上游升级后重跑一次即可，不用担心清单漂移。

代价与取舍：只能拿到「端点级」信息，参数定义仍需 `overrides.json` 手工补。当前 99 项里
只有 20 项声明了 options，其余走 Stirling 的默认值——够用，但不等于完整 API 文档。

### 2. 网关只做路由，不做业务

`integration/gateway` 零依赖（`package.json` 里 `dependencies` 是空的），职责只有四件：
校验入参、排队、调适配器、收产物。

- 输入统一为**本机路径**（`POST /api/run`）或 **multipart 上传**（`POST /api/upload`）
- 每个作业独占 `.work/jobs/<uuid>/`，下载接口只能读取该目录，杜绝任意路径访问
- 并发默认 2：Stirling 的重任务（OCR、转换）吃内存，放开会互相拖死
- CORS 只放行 `file://`、`localhost`、`127.0.0.1`，其他来源 403——否则任意网页都能指挥本机处理文件

### 3. 不可用要说清原因，不能假装成功

每个能力返回 `available` 与 `reason`：

```json
{ "id": "stirling.convert.pdf-word", "available": false,
  "reason": "Stirling-PDF 未就绪（fetch failed）",
  "hint": "运行 npm run start:all 或 node scripts/dev.mjs all 启动本地服务" }
```

PDFPatcher 通道未构建时，8 项能力全部标记不可用并给出 `fallback`（对应的 Stirling 端点），
不会出现「点了没反应」或「返回空文件」。

## 数据流：一次合并任务的完整路径

```
前端选 2 个 PDF
  → POST /api/upload        multipart 落盘到 .work/uploads/<uuid>/
  → POST /api/run           {capabilityId:"stirling.merge.merge-pdfs", files:[路径]}
      · 查 capabilities.json，确认引擎、字段名（fileInput，multiple）
      · probeEngines() 确认 Stirling UP
      · 入队，返回 202 {jobId}
  → 网关后台执行
      · 读文件 → FormData（Blob，按扩展名定 MIME）
      · POST http://127.0.0.1:8080/api/v1/general/merge-pdfs
      · 解析 Content-Disposition 取文件名（含 RFC 5987 UTF-8 处理）
      · 写入 .work/jobs/<jobId>/
  → GET /api/jobs/<id>      轮询到 status=done，返回 outputs[].url
  → GET /api/jobs/<id>/files/<name>   下载
```

## 已知边界

| 限制 | 原因 | 影响 |
|---|---|---|
| Stirling 需 JDK 25 | 上游 `modernJavaVersion = 25` | 本机 JDK 17，需另装；未装时 99 项不可用 |
| PDFPatcher 无 CLI | 上游只有 WinForms 入口 | 8 项独有能力待接线，见 `integration/patcher-cli/README.md` |
| 无 Tauri 二进制打包 | 本机无 Rust 工具链 | 工作台以静态页运行，桌面打包需另配 |
| 参数定义不全 | 端点信息可自动提取，参数不可 | 20/99 项有显式 options，其余用默认 |

这些都写在 `node scripts/dev.mjs --check` 的输出里，不藏在代码里。
