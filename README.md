# PdfKit · 离线 PDF 工具箱

Windows 桌面版 PDF 处理工具。**双击安装即用，不需要装 Java、不需要联网、不需要 Office。**
能力清单 60 项，全部用纯 Java（PDFBox + Swing）实现，目标机零环境依赖。

## 交付物

| 产物 | 路径 | 规格 |
|---|---|---|
| 安装包 | `pdfkit/dist-installer/PdfKit-1.2.1.exe` | 72 MB，含精简 JRE，双击安装 |
| 绿色版 | `pdfkit/dist/PdfKit/` | 159 MB，整个目录拷走即用，免安装 |
| 源码 | `pdfkit/src/main/java/` | 32 个 Java 文件，60 项能力 |
| 测试 | `pdfkit/src/test/java/io/pdfkit/` | 76 个用例，S1 接口 / S2 UI / S3 安全 / S4 功能 / S5 性能 |
| 测试报告 | `pdfkit/TEST-REPORT.md` | 缺陷清单、性能实测、门禁判定 |

实测（打包产物 `PdfKit.exe`）：启动到主窗口可见 **1.15 秒**，空闲内存 **43 MB**，60 项能力全部注册。

## 为什么是「重写」而不是「合并」

原始需求是「用 Java 重写三个工程的代码，整合成一个新项目」。落地时做了三个判断：

| 判断 | 依据 |
|---|---|
| 三个工程的代码一行都不复用 | Stirling 用 JDK 25 + Spring Boot（启动 8–15 s、体积 320 MB）；PDFPatcher 是 .NET WinForms，**AGPL 传染**；ToolKnit 是 **PolyForm 非商用**许可 |
| 只保留能力，不保留实现 | 去重后真实能力 107 项，其中 90 项 Java 生态已有成熟实现（PDFBox 覆盖绝大多数） |
| 首发做 60 项而不是 107 项 | 剩下 47 项全部依赖外部大件（LibreOffice 350 MB、OCR 语言包 50 MB、矢量引擎），与「零环境依赖」冲突 |

关键取舍：**体积和启动速度优先于功能数量**。内嵌 Stirling 的路线能 3 周出包，但要 350 MB 体积、8–15 秒冷启动、还要用户装 Office 才能转 PDF —— 与本项目的核心承诺冲突，因此放弃。

## 60 项能力

| 分类 | 项数 | 代表能力 |
|---|---|---|
| 页面组织 | 18 | 合并（保留书签）、拆分、按章节拆、按大小拆、N-up、海报分块、小册子拼版、叠加、重排、裁剪、旋转、缩放 |
| 内容编辑 | 15 | 页码、水印、页眉页脚、印章、插图、元数据、展平、反色、扫描件效果、附件增删查、注释导出/清除/添加 |
| 格式转换 | 7 | PDF↔图片、转文本、提取内嵌图片、多页 TIFF、转 Markdown、转 Word |
| 表单处理 | 3 | 读取字段（导出 CSV）、填充、锁定/解锁 |
| 书签与目录 | 3 | 自动生成（字号聚类）、导入、导出 |
| 安全与合规 | 8 | AES-256 加解密、净化、证书签名、签名验证、移除签名、自动涂黑、区域涂黑 |
| 读取与分析 | 3 | 文档信息、字体嵌入体检、结构探查 |
| 优化与修复 | 3 | 压缩、画质增强、修复 |

完整矩阵（含参数与验收依据）见 [docs/capability-matrix.md](docs/capability-matrix.md)。

## 目录结构

```
PDF-Master/
├── pdfkit/                     # 唯一的功能代码
│   ├── src/main/java/io/pdfkit/
│   │   ├── App.java            # 入口
│   │   ├── core/               # 能力注册表、任务队列、上下文、错误模型
│   │   ├── ops/                # 16 个能力类，60 项能力
│   │   ├── ui/                 # Swing + FlatLaf 界面（表单由参数定义自动生成）
│   │   └── util/               # PDF 加载/原子保存、字体、日志、系统探测
│   ├── src/main/resources/fonts/  # 内置思源黑体 TTF（OFL 许可，子集嵌入）
│   ├── src/test/java/io/pdfkit/   # 76 个用例，6 个测试类 + 1 个启动夹具
│   ├── scripts/
│   │   ├── build.ps1           # 一键构建 + 打包
│   │   └── build-font.py       # 重建内置字体（仅换字体时需要）
│   ├── TEST-REPORT.md          # 五阶段测试报告
│   ├── dist/                   # 绿色版产物（已 gitignore）
│   └── dist-installer/         # 安装包产物（已 gitignore）
├── docs/
│   ├── user-guide.md           # 用户操作手册（60 项功能逐项说明 + 按需求索引）
│   ├── requirements/           # 需求分析 + 验收 Checklist
│   ├── architecture.md         # 架构设计 + 新增能力步骤 + 维护约定
│   ├── build.md                # 构建、打包、重建字体
│   └── capability-matrix.md    # 60 项能力矩阵
└── .gitignore
```

## 快速开始

```bash
# 构建 + 打包（需要 JDK 17 与 WiX 3.14，路径见 docs/build.md）
cd pdfkit
powershell -File scripts/build.ps1

# 只跑测试（需 JDK 17 与 Gradle 8.x，均需自行安装）
cd pdfkit
gradle test
```

## 文档导航

| 文档 | 给谁看 | 内容 |
|---|---|---|
| [docs/user-guide.md](docs/user-guide.md) | **最终用户** | 安装卸载、界面导览、按需求找功能、60 项功能逐项说明、常见问题、故障排查 |
| [docs/architecture.md](docs/architecture.md) | 开发者 | 分层设计、能力注册表原理、任务执行流程、错误模型、技术选型理由、**新增一项能力的完整步骤**、代码维护约定 |
| [docs/build.md](docs/build.md) | 开发者 | 环境要求、构建与打包命令、依赖清单、测试组织、**重建内置字体** |
| [docs/capability-matrix.md](docs/capability-matrix.md) | 验收 | 60 项能力的参数与验收依据 |
| [pdfkit/TEST-REPORT.md](pdfkit/TEST-REPORT.md) | 质量 | 五阶段测试结果、10 个缺陷明细、性能实测数据 |

## 设计要点

**能力注册表驱动 UI**。每项能力声明自己的参数（名称、类型、默认值、可选值、帮助文本），界面据此自动生成表单。新增一项能力只需写一个 `Capability` 常量和一行注册，不碰任何界面代码。

**任务队列 + 产物隔离**。处理在后台线程执行（并发 2），支持取消；所有产物先写 `.part` 再原子移动，中途失败不留半个文件；输出文件名自动避让已存在文件与源文件。

**中文不出方框**。内置思源黑体（Noto Sans SC）子集，随安装包分发，不依赖系统字体，覆盖 GB2312 全部 7445 个码位。每个 PDF 只增加约 40 KB。

字体有两条硬约束，换字体时必须同时满足，否则测试全绿但用户拿到的是方框或乱码：

1. **必须是 glyf 轮廓的 TrueType**。PDFBox 的 `PDType0Font` 不支持 CFF 轮廓的 OTF，加载直接抛异常；代码 catch 后回退系统字体，于是「零依赖」静默失效。
2. **cmap 不能多对一**。PDFBox 生成 ToUnicode 时按 GID 反查字形，同一字形只保留最小码位。若字体把部首码位（如 U+2EDA「⻚」）映射到与常用汉字相同的字形，写「页」提取出来就是「⻚」——字形正确，但复制、搜索、文本提取、转 Word 全部出错。

`scripts/build-font.py` 把这两条固化进构建流程：子集白名单含 GB2312 全码表，对同形字逐字形拆成独立副本，产出前断言 cmap 严格一对一，不满足则拒绝写出文件。

**涂黑是真删除**。涂黑、反色、扫描件效果会把目标页整页栅格化后替换内容流，原文字在文件里不存在，复制和搜索都取不到。代价是该页不再可检索。

## 许可

本项目代码原创。内置字体思源黑体（Noto Sans SC）采用 SIL Open Font License 1.1，许可证全文见 `pdfkit/src/main/resources/fonts/OFL.txt`。
上游三个工程（Stirling-PDF / PDFPatcher / ToolKnit）仅作为功能行为参考，其源代码已从工作区移除，未复制任何代码、UI 资源或文案。
移除前的快照保留在 git 历史中（提交 `b6c5804` 之前），需要时可从历史检出。
