# PdfKit · 离线 PDF 工具箱

一个**免安装 Java、双击即用**的 Windows PDF 工具箱。所有能力在本机完成，不联网、不上传、不留痕。

安装包 73 MB，内置运行时、中文字体与全部依赖 —— 目标机器不需要装 JDK、不需要装任何运行库、不需要配环境变量。

```
dist-installer\PdfKit-1.0.0.exe   ← 双击安装（可选目录、开始菜单、桌面快捷方式）
dist\PdfKit\PdfKit.exe            ← 免安装绿色版，整个 dist\PdfKit 目录拷走即用
```

---

## 一、能做什么（46 项）

### 页面组织
合并（保留书签）、拆分（按页数/按范围）、按大小拆分、按章节拆分、按文本筛选页面、按尺寸筛选页面、删除空白页、旋转、裁剪、缩放、删除页面、提取页面、重排页面、多页排版 N-up、叠加文档

### 内容编辑
添加页码（中文模板）、添加水印（平铺/角度/透明度）、添加印章（圆形红章）、插入图片、修改元数据、展平表单、反色/夜间模式、做旧扫描件效果、附件管理（列出/添加/导出）

### 格式转换
PDF 转图片（PNG/JPG，DPI 可调）、图片转 PDF（多图排序/纸张适配）、PDF 转文本

### 书签与目录
**自动生成书签**（按字号+加粗识别标题，可调层级）、导出书签 XML、导入书签 XML

### 安全与合规
添加密码（AES-256）、移除密码、净化 PDF（去 JS/OpenAction/嵌入文件）、**自动涂黑**（身份证/手机号/银行卡/邮箱正则识别）、**手动涂黑**、验证签名、移除签名

### 读取与分析
文档信息、**字体嵌入体检**（标出未嵌入字体）、**文档结构探查**（对象/xref/损坏定位）

### 优化与修复
压缩（图片重采样+JPG 重编码）、修复（重写结构）

---

## 二、运行方式

### 安装版（推荐给他人使用）
双击 `PdfKit-1.0.0.exe` → 选择目录 → 完成。默认装到 `%LOCALAPPDATA%\Programs\PdfKit`，**不需要管理员权限**。

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
# 编译 + 测试（25 个用例）
export JAVA_HOME="C:/dev/jdk-17.0.13"
/c/dev/gradle-8.14/bin/gradle test

# 生成可运行目录
/c/dev/gradle-8.14/bin/gradle installDist

# 出绿色版
"$JAVA_HOME/bin/jpackage" --type app-image --name PdfKit --app-version 1.0.0 \
  --input build/install/pdfkit/lib --main-jar pdfkit-1.0.0.jar \
  --main-class io.pdfkit.App --dest dist \
  --java-options "-Dfile.encoding=UTF-8" --java-options "-Xmx1024m"

# 出安装包（需 WiX 在 PATH）
export PATH="/c/dev/wix314:$PATH"
"$JAVA_HOME/bin/jpackage" --type exe --name PdfKit --app-version 1.0.0 \
  --input build/install/pdfkit/lib --main-jar pdfkit-1.0.0.jar \
  --main-class io.pdfkit.App --dest dist-installer \
  --win-menu --win-shortcut --win-dir-chooser \
  --java-options "-Dfile.encoding=UTF-8" --java-options "-Xmx1024m"
```

或直接跑 `scripts\build.ps1`。

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
│   │   └── Registry.java     能力注册表（46 项，新增能力只改这里）
│   ├── ops/                  能力实现（按域分文件）
│   │   ├── OrganizeOps       合并/拆分/旋转/裁剪/重排/按章节拆
│   │   ├── LayoutOps         N-up/按大小拆/叠加
│   │   ├── EditOps           页码/水印/元数据/展平
│   │   ├── EditPlusOps       印章/插图/反色/扫描件效果
│   │   ├── AttachOps         附件增删查
│   │   ├── ConvertOps        PDF↔图片/转文本
│   │   ├── SecureOps         加解密/净化/签名验证
│   │   ├── RedactOps         涂黑（栅格化真删除）
│   │   ├── FormOps           表单读/填
│   │   ├── AnalyzeOps        信息/字体体检/结构探查
│   │   ├── OptimizeOps       压缩/修复
│   │   ├── FilterOps         按文本/尺寸筛选、删空白页
│   │   └── BookmarkOps       自动书签/导入导出
│   ├── ui/                   Swing + FlatLaf 界面
│   └── util/                 字体、路径、日志、PDF 通用
├── src/main/resources/fonts/ 内置思源黑体（OFL）+ 许可证
└── src/test/java/            25 个端到端用例
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
| 无 PDF → Word/Excel | 保版式还原属于商业库强项，本版只提供 PDF→文本 |
| 签名验证只查结构覆盖 | 不校验证书链与信任根，正式场景请用 Adobe |
| 压缩对纯文字 PDF 收益有限 | 压缩主力是图片重采样，文字型文件本身已经很紧凑 |

---

## 七、许可

- 本工程代码：内部使用
- 内置字体 **Noto Sans SC**：SIL Open Font License 1.1，见 `resources/fonts/OFL.txt`
- PDF 解析引擎 **Apache PDFBox 3.0.5**：Apache License 2.0
- UI 主题 **FlatLaf 3.5.4**：Apache License 2.0
- 加密/签名 **BouncyCastle 1.80**：MIT-style

上游参考项目（`../engines/`）的许可与边界见 `../README.md`：Stirling-PDF 为 MIT，ToolKnit 为 PolyForm 非商用，PDFPatcher 为 AGPL + 附加条款。**本工程所有功能实现均为独立编写，未复制上述项目源码。**
