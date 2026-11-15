# PDF 三引擎整合工程

把三个上游项目的 PDF 能力收进一个仓库、一个入口、一套能力清单。

| 引擎 | 角色 | 技术栈 | 本机状态 |
|---|---|---|---|
| [Stirling-PDF](https://github.com/linhaihua1/Stirling-PDF) | 功能主力：转换、OCR、压缩、签名、涂黑、管线自动化 | Java 17+/25 + Spring Boot 3.3.0 | 源码就绪，**需 JDK 25 才能构建** |
| [PDFPatcher](https://github.com/linhaihua1/PDFPatcher) | 中文场景补位：自动生成书签、字体体检、结构探查 | C# / .NET Framework 4.0 WinForms | 源码就绪，**CLI 未构建** |
| [ToolKnit Desktop](https://github.com/linhaihua1/toolknit-desktop) | 桌面壳与 17 个离线 PDF 工具 | Tauri 2 + Vite + pdf-lib | 源码就绪 |

整合层（本仓库自己写的部分）不依赖任何 npm/Java 包：

```
integration/
├── registry/      能力注册表：124 项能力，三引擎统一编号
├── gateway/       零依赖 Node 网关（默认 127.0.0.1:8931）
├── desktop/       PDF 工作台单页 + Tauri 接入说明
└── patcher-cli/   PDFPatcher 命令行包装（C# 骨架，待接线）
```

## 快速开始

```bash
# 1) 体检：看清哪条通道能用、缺什么
node scripts/dev.mjs --check

# 2) 起网关（native 17 项 + 任一已就绪引擎立即可用）
node scripts/dev.mjs gateway

# 3) 打开工作台
#    双击 integration/desktop/pdf-hub/index.html
#    或 http://127.0.0.1:8931/api/capabilities 直接看 JSON
```

工作台默认连 `127.0.0.1:8931`，端口不同时用 `?gateway=http://127.0.0.1:端口` 打开。

## 能力分布

| 引擎 | 数量 | 说明 |
|---|---|---|
| Stirling | 99 | 从源码注解 + 中文语言包自动提取，见 `scripts/gen-registry.py` |
| PDFPatcher | 8 | 独有能力：自动书签、字体体检、结构探查 |
| ToolKnit 内置 | 17 | 离线可用，纯前端 pdf-lib |

按分类：页面组织 22、编辑 31、转换 20、安全 17、分析 10、筛选 6、书签 3、压缩 2、提取 1、自动化 1、其他 11。

## 三条通道怎么选

```
要格式转换 / OCR / 签名 / 批量管线   → Stirling
要中文书签自动生成 / 字体排错        → PDFPatcher（构建 CLI 后）
要离线、免上传、体积小               → ToolKnit 内置
```

同一能力多引擎覆盖时，注册表里 `fallback` 字段写明替代路径；Stirling 不可用时前端会显示原因与该替代项，而不是静默失败。

## 校验

```bash
node tests/e2e.mjs        # 14 项断言，用 mock Stirling 跑通全链路
node scripts/dev.mjs --check
```

`tests/e2e.mjs` 覆盖：引擎探测 → 能力清单 → 多文件上传 → 任务排队 → 落盘 → 下载 → JSON 响应 → 非法入参拒绝。

## 目录

```
PDF/
├── engines/          三个上游仓库（已剔除各自 .git）
├── integration/      整合层（见上）
├── scripts/          gen-registry.py · dev.mjs
├── tests/            e2e.mjs · mock-stirling.mjs
├── docs/             architecture.md · capability-matrix.md · build.md
└── .work/            运行时产物（jobs / uploads），已忽略
```

## 上游版本

| 仓库 | 提交 | 获取方式 |
|---|---|---|
| Stirling-PDF | `ad38e38` (main, 3.3.0) | tarball 快照 |
| PDFPatcher | `e5f89d9` (v1.2) | git clone 快照 |
| ToolKnit Desktop | `9a181d5` (main, 3.2.0) | tarball 快照 |

三个引擎均为快照副本，不含上游提交历史，这样整合仓库的 git 记录只记录整合本身。更新上游时重新拉取覆盖 `engines/<name>/` 即可，整合层不需要改动（`scripts/gen-registry.py` 会重新提取能力清单）。

## 许可

三个上游各自保留 LICENSE（`engines/*/LICENSE` 与 `NOTICE`），商用前请分别确认：Stirling-PDF 有企业版功能边界，PDFPatcher 为自定义授权，ToolKnit 对 PDF 转 Word 有独立授权条款。
