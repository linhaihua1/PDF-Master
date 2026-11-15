# PdfKit · 离线 PDF 工具箱

一个**免安装 Java、双击即用**的 Windows PDF 工具箱。所有能力在本机完成，不联网、不上传、不留痕。

安装包 73 MB，内置运行时、中文字体与全部依赖 —— 目标机器不需要装 JDK、不需要装任何运行库、不需要配环境变量。

```
dist-installer\PdfKit-1.1.0.exe   ← 双击安装（可选目录、开始菜单、桌面快捷方式）
dist\PdfKit\PdfKit.exe            ← 免安装绿色版，整个 dist\PdfKit 目录拷走即用
```

---

## 一、能做什么（60 项）

### 页面组织（18）
合并（保留书签）、拆分（按页数/按范围）、按大小拆分、按章节拆分、按文本筛选页面、按尺寸筛选页面、按有无图片筛选、删除空白页、旋转、裁剪、缩放、删除页面、提取页面、重排页面、多页排版 N-up、海报分块打印、小册子拼版、叠加文档

### 内容编辑（15）
添加页码（中文模板）、添加水印（平铺/角度/透明度）、页眉页脚（六位模板）、添加印章（圆形红章）、插入图片、修改元数据、展平表单、反色/夜间模式、做旧扫描件效果、附件管理（列出/添加/导出）、注释导出清单、注释分类清除、添加便签

### 格式转换（7）
PDF 转图片（PNG/JPG，DPI 可调）、图片转 PDF（多图排序/纸张适配）、PDF 转文本、提取内嵌图片、PDF 转多页 TIFF、PDF 转 Markdown、PDF 转 Word

### 表单处理（3）
读取字段（可导出 CSV）、填充字段、锁定/解锁字段

### 书签与目录（3）
**自动生成书签**（按字号+加粗识别标题，可调层级）、导出书签 XML、导入书签 XML

### 安全与合规（8）
添加密码（AES-256）、移除密码、净化 PDF（去 JS/OpenAction/嵌入文件）、**证书签名**（PKCS#7，支持自签名与 p12）、验证签名、移除签名、**自动涂黑**（身份证/手机号/银行卡/邮箱正则识别）、**手动涂黑**

### 读取与分析（3）
文档信息、**字体嵌入体检**（标出未嵌入字体）、**文档结构探查**（对象/xref/损坏定位）

### 优化与修复（3）
压缩（图片重采样+JPG 重编码）、画质增强（去噪/锐化/对比度）、修复（重写结构）

---

## 二、运行方式

### 安装版（推荐给他人使用）
双击 `PdfKit-1.1.0.exe` → 选择目录 → 完成。默认装到 `%LOCALAPPDATA%\Programs\PdfKit`，**不需要管理员权限**。

### 绿色版（U 盘/内网分发）
把 `dist\PdfKit` 整个文件夹复制到目标机器，双击 `PdfKit.exe`。

### 数据目录

| 用途 | 路径 |
|---|---|
| 程序数据 | `%LOCALAPPDATA%\PdfKit\` |
| 日志（按天滚动） | `%LOCALAPPDATA%\PdfKit\logs\` |
| 任务临时文件 | `%LOCALAPPDATA%\PdfKit\jobs\`（任务结束自动清理） |
| 默认输出 | `%USERPROFILE%\Documents\PdfKit\` |

界面右下角「查看日志」直接打开日志目录；任务失败时日志里有完整堆栈与错误码。

---

## 三、构建

### 前置

| 工具 | 版本 | 本机路径 |
|---|---|---|
| JDK | 17（jpackage 自带） | `C:\dev\jdk-17.0.13` |
| Gradle | 8.14 | `C:\dev\gradle-8.14` |
| WiX Toolset | 3.14（仅出 `.exe` 安装包需要） | `C:\dev\wix314` |

### 命令

```bash
# 编译 + 测试（76 个用例，含 S1–S5 五阶段）
export JAVA_HOME="C:/dev/jdk-17.0.13"
/c/dev/gradle-8.14/bin/gradle test

# 生成可运行目录
/c/dev/gradle-8.14/bin/gradle installDist

# 出绿色版
"$JAVA_HOME/bin/jpackage" --type app-image --name PdfKit --app-version 1.2.0 \
  --input build/install/pdfkit/lib --main-jar pdfkit-1.2.0.jar \
  --main-class io.pdfkit.App --dest dist \
  --java-options "-Dfile.encoding=UTF-8" --java-options "-Xmx1024m"

# 出安装包（需 WiX 在 PATH）
export PATH="/c/dev/wix314:$PATH"
"$JAVA_HOME/bin/jpackage" --type exe --name PdfKit --app-version 1.2.0 \
  --input build/install/pdfkit/lib --main-jar pdfkit-1.2.0.jar \
  --main-class io.pdfkit.App --dest dist-installer \
  --win-menu --win-shortcut --win-dir-chooser \
  --java-options "-Dfile.encoding=UTF-8" --java-options "-Xmx1024m"
```

或直接跑 `scripts\build.ps1`。

### 更换内置字体（非日常构建步骤）

内置字体 `src/main/resources/fonts/NotoSansSC-Regular.ttf`（约 9.7 MB）随安装包分发，
目标机不需要装任何中文字体。**换字体前必须读完这三条，否则会静默出错而不是报错：**

| 要求 | 违反后果 |
|---|---|
| 必须是 **glyf 轮廓**的 TrueType，不能是 CFF 轮廓的 OTF | PDFBox 的 `PDType0Font` 直接拒绝加载，代码静默回退系统字体 —— 目标机没装中文字体时全是方框 |
| cmap **不能有一对多**（多个码位映射到同一字形） | PDFBox 生成 ToUnicode 时按 GID 反查且只取最小码位：写「页」提取出「⻚」、写「胶」提取出「㬵」、写「•」提取出「·」，字形对但复制/搜索/转 Word 全错 |
| 必须覆盖 GB2312 全部 7445 个码位 | 带圈序号 ⒈、片假名中点 ・、声调符号 ˉˇ、制表符等直接缺字抛异常 |

用 `scripts/build-font.py` 生成，它把上面三条都固化进去了（子集白名单含 GB2312 全码表、
逐字形拆分同形字、产出前断言 cmap 严格一对一）：

```bash
pip install fonttools
# 源字体：Google Fonts 的 NotoSansSC[wght].ttf（约 17 MB），放在 scripts/ 下
python scripts/build-font.py scripts/NotoSansSC-VF.ttf \
    src/main/resources/fonts/NotoSansSC-Regular.ttf
```

替换后一定要跑字体验证用例，它用 GB2312 全量 + 产品文案 + 20 组同形字对做
「写入 → 提取 → 逐字比对」，不一致就失败：

```bash
/c/dev/gradle-8.14/bin/gradle test --tests "io.pdfkit.FunctionalTest.bundledChineseFontIsUsable"
```

⚠️ 不要用 `PDType0Font.hasGlyph()` 判断这个字体缺不缺字。PDFBox 对 `embedSubset=true`
加载的字体把入参当 CID/GID 而非 Unicode 码位，只有码位数值小于字形总数时才返回 true，
结论完全随机。

---

## 四、代码结构

```
pdfkit/
├── src/main/java/io/pdfkit/
│   ├── App.java              入口（单实例锁、主题、全局异常）
│   ├── core/
│   │   ├── Capability.java   能力声明（id/标题/分类/参数/执行体）
│   │   ├── Param.java        参数声明（驱动 UI 自动生成表单）
│   │   ├── Ctx.java          执行上下文（输入/参数/输出/进度/取消）
│   │   ├── Err.java          带错误码的业务异常
│   │   ├── Job.java          任务状态机
│   │   ├── JobQueue.java     并发队列（默认 2 线程）+ 产物隔离
│   │   └── Registry.java     能力注册表（60 项，新增能力只改这里）
│   ├── ops/                  能力实现（15 个类）
│   │   ├── OrganizeOps       合并/拆分/旋转/裁剪/重排/按章节拆
│   │   ├── LayoutOps         N-up/按大小拆/叠加
│   │   ├── LayoutPlusOps     海报分块/小册子拼版
│   │   ├── EditOps           页码/水印/元数据/展平
│   │   ├── EditPlusOps       印章/插图/页眉页脚/反色/扫描件效果
│   │   ├── AttachOps         附件增删查
│   │   ├── AnnotOps          注释导出/清除/添加
│   │   ├── ConvertOps        PDF↔图片/转文本/提取图片
│   │   ├── ConvertPlusOps    转 Markdown/转 Word/多页 TIFF
│   │   ├── SecureOps         加解密/净化/证书签名/签名验证
│   │   ├── RedactOps         涂黑（栅格化真删除）
│   │   ├── FormOps           表单读/填/锁定
│   │   ├── AnalyzeOps        信息/字体体检/结构探查
│   │   ├── OptimizeOps       压缩/画质增强/修复
│   │   ├── FilterOps         按文本/尺寸/图片筛选、删空白页
│   │   └── BookmarkOps       自动书签/导入导出
│   ├── ui/                   Swing + FlatLaf 界面
│   └── util/                 字体、路径、日志、PDF 通用
├── src/main/resources/fonts/ 内置思源黑体（OFL）+ 许可证
└── src/test/java/            35 个端到端用例
```

### 加一个新功能

1. 在对应 `Ops` 里写一个 `Capability` 常量（声明参数 + 执行体）
2. 在 `Registry.ALL` 里加一行
3. 完成 —— UI 会自动出现菜单项、自动生成表单、自动接入任务队列

不需要改任何 UI 代码，这是 `Capability`/`Param` 声明式设计的收益。

---

## 五、设计要点

| 关注点 | 做法 |
|---|---|
| 绝不覆盖源文件 | 输出路径与任一输入路径相同时自动改名 `原名_后缀.pdf`；写入用 `.part` + `rename` 原子落盘 |
| 真删除而非遮挡 | 涂黑会清空页面内容流后重新栅格化，`pdftotext`/复制/搜索都取不到原文 |
| 取消不是假取消 | 长循环注入 `ctx.checkCancel()`，取消后 3 秒内停止并清理临时产物 |
| 错误可定位 | 统一错误码（`ERR_ENCRYPTED`/`ERR_PAGE_RANGE`/`ERR_NO_CJK_FONT`…），日志带堆栈 |
| 中文不乱码 | 内置思源黑体子集嵌入（每文件约 +40 KB），不依赖系统字体 |
| 单实例 | `%LOCALAPPDATA%\PdfKit\.lock` 文件锁，重复启动提示已运行 |

---

## 六、已知限制

| 限制 | 原因 / 影响 |
|---|---|
| 涂黑、反色、扫描件效果会把目标页转为图片 | 这是"彻底删除文字"的代价；这些页不可再搜索、体积变大 |
| 自动书签对扫描件无效 | 无文本层，需先 OCR（本版未集成 OCR 引擎） |
| 无 Office → PDF | 需要 Word COM 或内置 LibreOffice（+350 MB），本版未提供 |
| PDF → Word 不做像素级还原 | 只保证文字流、标题层级、段落可编辑；保版式属商业库强项 |
| 表格转 Markdown 会丢列关系 | 无表格结构识别引擎，按纯文本行输出 |
| 自签名证书 Adobe 提示「身份未知」 | 自签名不在 CA 信任链里；签名有效，商业场景请用 CA 证书 |
| 签名验证只查结构覆盖 | 不校验证书链与信任根，正式场景请用 Adobe |
| 无 OCR | tess4j + 中文语言包约 +50 MB，与体积目标冲突 |
| 压缩对纯文字 PDF 收益有限 | 压缩主力是图片重采样，文字型文件本身已经很紧凑 |

---

## 七、许可

- 本工程代码：内部使用
- 内置字体 **Noto Sans SC**：SIL Open Font License 1.1，见 `resources/fonts/OFL.txt`
- PDF 解析引擎 **Apache PDFBox 3.0.5**：Apache License 2.0
- UI 主题 **FlatLaf 3.5.4**：Apache License 2.0
- 加密/签名 **BouncyCastle 1.80**：MIT-style

上游三个参考工程（Stirling-PDF / PDFPatcher / ToolKnit）的许可分别为 MIT、AGPL + 附加条款、PolyForm 非商用。**本工程所有功能实现均为独立编写，未复制上述项目的任何源码、UI 资源或文案**；其源码已从仓库移除，仅作为功能行为参考。
