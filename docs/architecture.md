# 架构设计

## 1. 设计目标与约束

| 目标 | 约束来源 | 落地方式 |
|---|---|---|
| 目标机零环境依赖 | 用户硬要求「组件全部内置」 | 精简 JRE 随包分发；UI 用 JDK 自带的 Swing；字体内置；不调 COM、不调外部进程 |
| 离线可用 | 需求 NFR-05 | 全程无网络调用，连 LLM 类能力也不做 |
| 双击即用 | 交付形态要求 exe | `jpackage --type exe` 生成安装包 |
| 体积可控 | 需求 NFR-04 | 放弃内嵌 Stirling（320 MB）与 LibreOffice（+350 MB），最终 72 MB |
| 启动 ≤5 s | 需求 NFR-01 | 无 Spring、无 WebView、无本地服务端口，实测 1.15 s |

## 2. 分层

```
┌───────────────────────────────────────────────┐
│ ui/          Swing + FlatLaf                  │
│   MainFrame  能力列表 / 表单 / 任务列表 / 日志  │
│   FormPanel  由 Param 列表自动生成控件         │
└───────────────────┬───────────────────────────┘
                    │ 只依赖 Capability 抽象，不认识任何具体能力
┌───────────────────▼───────────────────────────┐
│ core/        能力内核（不依赖 Swing）          │
│   Capability  能力声明（id/title/category/参数/执行体）│
│   Registry    60 项能力的唯一注册点            │
│   Ctx         单次执行的上下文（输入/参数/进度/取消）│
│   JobQueue    固定线程池（并发 2）+ 任务状态机  │
│   Err         错误码 + 人话消息                │
└───────────────────┬───────────────────────────┘
                    │ 只用 PDFBox / BouncyCastle，不用任何框架
┌───────────────────▼───────────────────────────┐
│ ops/         15 个能力类，60 项能力            │
│   OrganizeOps  LayoutOps  LayoutPlusOps        │
│   FilterOps    EditOps    EditPlusOps          │
│   ConvertOps   ConvertPlusOps                  │
│   SecureOps    RedactOps  FormOps              │
│   BookmarkOps  AttachOps  AnnotOps  AnalyzeOps │
└───────────────────┬───────────────────────────┘
                    │
┌───────────────────▼───────────────────────────┐
│ util/        PdfUtil（加载/原子保存/页范围解析）│
│              Fonts（内置字体）Log（滚动日志）   │
└───────────────────────────────────────────────┘
```

依赖方向严格单向：`ui → core → ops → util`。`ops` 不 import 任何 `ui` 包，因此 60 项能力全部可以不启界面直接跑 —— 76 个用例（S1 接口 / S2 UI / S3 安全 / S4 功能 / S5 性能）就是这样验证的。

## 3. 能力注册表：为什么 UI 里没有「一个功能一个面板」

传统写法是「每个功能画一个面板」，60 项能力就是 60 个界面文件，改一个参数要动界面代码。

本项目把参数也变成数据：

```java
public record Param(String key, String label, Type type, String def,
                    List<String> choices, String help) {
    public enum Type { TEXT, NUMBER, CHOICE, BOOL, PASSWORD }
}
```

一个能力的完整声明如下（以「旋转页面」为例）：

```java
public static final Capability ROTATE = new Capability(
        "organize.rotate", "旋转页面", "页面组织",
        "顺时针旋转全部或指定页面的显示方向。",
        List.of(
                Param.choice("angle", "旋转角度", "90", "90", "180", "270"),
                Param.text("pages", "作用页", "", "留空=全部页，或 1-3,5")
        ),
        ctx -> {
            int angle = ctx.intVal("angle", 90);
            /* ... 实际处理，约 20 行 ... */
        });
```

`FormPanel` 拿到 `params` 后按 `Type` 生成下拉框 / 数字框 / 勾选框 / 密码框，`help` 变成输入框下方的灰色提示。**新增一项能力 = 写一个常量 + 在 Registry 加一行**，界面零改动。

`Ctx` 统一做参数解析与校验，越界的数字在这里就抛 `ERR_PARAM_RANGE`，不会走到业务代码里产生半个损坏文件：

```java
int dpi = ctx.intVal("dpi", 200, 96, 600);  // 不在 96–600 直接报错并说明当前值
```

## 4. 任务执行

```
用户点「执行」
   │
   ├─ JobQueue.submit() 建 Job，状态 PENDING，立刻返回
   │
   └─ 固定线程池（并发 2）
        ├─ 建独立工作目录 work/<jobId>/
        ├─ 建 Ctx（输入快照、参数快照、进度回调、取消令牌）
        ├─ cap.op().run(ctx)
        │    ├─ ctx.report(pct, msg)   → UI 进度条 + 状态文字
        │    └─ ctx.checkCancel()      → 每页/每文件检查一次
        └─ 结束
             ├─ 成功：产物列表写回 Job，工作目录删除
             ├─ 失败：保留错误码 + 消息 + 堆栈到日志，工作目录删除
             └─ 取消：清理已生成的半成品
```

三个刻意的设计：

**产物原子化**。`PdfUtil.save()` 先写 `<name>.pdf.part` 再 `move` 覆盖。断电或崩溃时磁盘上要么是旧文件，要么是新文件，不会出现 0 字节的 PDF。

**输出名自动避让**。`ctx.outDerived("_合并", ".pdf")` 会在 `a.pdf` 存在时自动变成 `a_合并.pdf`，再冲突变 `a_合并_1.pdf`。同时会主动避开输入文件 —— 避免「输出覆盖源文件」这类不可逆事故。

**取消是协作式的**。`ctx.checkCancel()` 在每页循环里抛 `ERR_CANCELED`，队列捕获后清理工作目录。不用 `Thread.stop()`，因为它会把文件句柄留在半开状态。

## 5. 错误模型

错误不是异常堆栈，是给人看的一句话 + 一个可检索的码。

```java
public final class Err extends RuntimeException {
    private final String code;      // ERR_ENCRYPTED / ERR_NO_FORM / ERR_PAGE_RANGE ...
}
```

| 码 | 触发场景 | 用户看到的话 |
|---|---|---|
| `ERR_ENCRYPTED` | 打开加密 PDF 未给密码 | PDF 已加密，请提供打开密码：x.pdf |
| `ERR_BAD_PDF` | 文件损坏或不是 PDF | 无法解析 PDF（文件损坏或不是 PDF）：x.pdf |
| `ERR_PAGE_RANGE` | 页码范围越界 | 页码范围 99 超出总页数 3 |
| `ERR_NO_FORM` | 对无表单文件做表单操作 | 这个 PDF 没有表单字段 |
| `ERR_NO_TEXT` | 对扫描件做文本提取 | 这个 PDF 没有可提取的文字（可能是扫描件） |

原则：**能说清「为什么」和「怎么办」的才叫错误消息**，`NullPointerException` 直接给用户看是不合格的。

## 6. 为什么是这些技术选型

| 选择 | 备选 | 否决理由 |
|---|---|---|
| PDFBox 3.0.5 | iText 7 / Aspose | iText 是 AGPL；Aspose 是商业授权。PDFBox 是 Apache 2.0，功能覆盖本项目 60 项中的 58 项 |
| Swing + FlatLaf | JavaFX / WebView2 / 内嵌 Spring | JavaFX 需额外 60 MB 运行时；WebView2 在部分 Win10 需单独装运行时；Spring 让冷启动变成 8–15 s |
| 内置思源黑体 | 用系统字体 / 用 Standard14 | 系统字体在精简版 Windows 上可能缺失，出方框；Standard14 没有中文 |
| BouncyCastle | 系统 KeyStore 默认算法 | 签名需要完整 CMS 支持，JDK 自带的不够 |
| `jpackage` | InstallShield / NSIS | jpackage 是 JDK 自带，能一并打包 JRE，输出标准 MSI/EXE |

## 7. 已知限制

| 限制 | 原因 | 影响 |
|---|---|---|
| 涂黑/反色/扫描件效果后该页不可搜索 | 为实现「真删除底层文字」，把整页栅格化替换内容流 | 这是安全红线的必要代价，已在界面提示 |
| PDF → Word 不做像素级还原 | 保版式是商业库（Aspose/iText）的强项，开源方案还原度差 | 只保证文字流、标题层级、段落可编辑 |
| 表格转 Markdown 只做纯文本 | 无表格结构识别引擎 | 复杂表格会丢列关系 |
| 自签名证书 Adobe 提示「身份未知」 | 自签名不在任何 CA 信任链里 | 签名本身有效，商业场景请用 CA 证书 |
| 无 OCR | tess4j + 中文语言包 +50 MB，与体积目标冲突 | 扫描件只能做图像处理，不能提取文字 |
| 无 Office → PDF | 依赖系统 Office（违背零依赖）或 LibreOffice（+350 MB） | 该能力置于范围外 |
| 无可视化编辑器与流水线 | 各约 10 人日，排后续版本 | 当前只能单步操作 |

## 8. 新增一项能力：完整步骤

界面上不会出现任何「为某个能力写的代码」，但代码仓库里有四处需要同步。

### 步骤

**① 在 `ops` 包对应类里声明能力**

按功能归档：页面操作放 `OrganizeOps`，文字/图形叠加放 `EditOps` / `EditPlusOps`，
格式转换放 `ConvertOps` / `ConvertPlusOps`，安全相关放 `SecureOps` / `RedactOps`。

```java
public static final Capability FOO = new Capability(
        "category.foo",            // id：分类.动作，发布后不可改
        "功能名称",                 // 界面显示名
        "页面组织",                 // 分类，必须是 8 个分类之一
        "一句话说明这个功能做什么、产出什么。",
        List.of(
                Param.text("pages", "作用页", "", "留空=全部页，或 1-3,5"),
                Param.num("size", "字号", "12", "6-36")
        ),
        ctx -> {
            // 第一行先取输入：让「没选文件」的提示先于参数错误出现
            Path in = ctx.input();
            int size = ctx.intVal("size", 12, 6, 36);
            try (PDDocument doc = PdfUtil.load(in, ctx.str("password"))) {
                // ... 业务逻辑
                Path out = ctx.outDerived("_后缀", ".pdf");
                PdfUtil.save(doc, out);       // 必须走原子保存
                ctx.addOutput(out);           // 不登记则用户找不到产物
                ctx.report(100, "完成");
            }
        });
```

**② 在 `Registry.ALL` 里登记**

放在希望它显示的位置（`ALL` 的顺序就是界面顺序）。

**③ 补测试**

`FunctionalTest.everyCapabilityIsCoveredBySomeAssertion` 拿硬编码的 60 个 id 与注册表比对。
新增能力不补测试会直接失败——这是刻意设的门禁，防止「加了功能没测」。

新能力的断言要写「产物里能看到什么」：页数、提取出的文本、书签层级、加密状态等，
而不是只断言「没抛异常」。

**④ 更新文档**

- `docs/capability-matrix.md`：能力矩阵
- `docs/user-guide.md`：用户手册里对应分类的功能详解
- `README.md`：分类项数

### 检查清单

| 项 | 要求 |
|---|---|
| 错误码 | 用已有码（见 `Err` 类的分类表），不要造同义词 |
| 空输入 | 第一行 `ctx.input()`，抛 `ERR_NO_INPUT` |
| 参数校验 | 用 `ctx.intVal(key, def, min, max)`，越界抛 `ERR_PARAM_RANGE` |
| 产物登记 | 每个写出的文件都 `ctx.addOutput(...)` |
| 结果文本 | 分析类用 `ctx.setResult(...)`，可选同时落盘 |
| 取消检查 | 长循环里 `ctx.report(...)` 或 `ctx.checkCancel()` |
| 临时文件 | 写 `ctx.work(...)`，不要污染输出目录 |
| 保存 | 走 `PdfUtil.save()`，不要直接 `doc.save()` |
| 单文件失败 | 批量处理时跳过并记日志，不要中断整批 |
| 错误消息 | 说清「哪里不对」+「怎么办」，不泄露类名与堆栈 |

## 9. 代码阅读与维护约定

### 注释写什么

本项目的注释密度约 30%（9059 行 Java 里约 2733 行是注释），集中在三类信息上，
不写「这行干了什么」这种复述代码的话：

1. **为什么这样设计**（而不是那样）—— 例如为什么用 Form XObject 而不是复制页面、
   为什么取消不用 `Thread.cancel`、为什么任务队列并发上限是 4。
2. **踩过的坑** —— 例如 PDFBox 的 `getStringWidth` 单位是 1/1000 em、
   `hasGlyph` 对子集字体不可信、cmap 多对一会导致文本提取出错。
   这类注释最值钱，删掉等于让后来者再踩一遍。
3. **不可逆操作与边界** —— 例如栅格化会丢文字层、`form.flatten()` 后不能编辑、
   哪些操作会破坏数字签名。

不写的一类：`// 遍历页面`、`// 返回结果` 这种跟着代码念一遍的注释。

### 五个高危改动点

改这些地方前请先读原注释，它们各自对应一个已修复的真实缺陷：

| 位置 | 风险 |
|---|---|
| `Fonts` 的内置字体 | 换错字体类型（CFF 轮廓）会让 PDFBox **静默回退**系统字体，测试全绿但目标机全是方框 |
| `AttachOps.sanitizeName` | 附件名来自 PDF 内部，不清洗可导致路径穿越写出目录，或 `CON` 之类保留名崩溃 |
| `BookmarkOps.newSafeBuilder` | 书签 XML 是外部输入，不加固可被 XXE 读取本机任意文件 |
| `RedactOps.rasterizeAndRedact` | 少了清空内容流那一步，涂黑只是「盖住」，原文仍可提取 |
| `MainFrame` 构造里的 `JobQueue` 回调 | 去掉 `SwingUtilities.invokeLater` 包装会从工作线程改 Swing 组件，偶发错乱且极难复现 |

### 加注释时的两个坑

- **Javadoc 里的 `%` 要小心**：`String.format` 的文案里出现字面 `%` 必须写 `%%`。
  踩过：参数说明写「（如 100%）」，`%）` 被当成转换符，调用即崩。
- **注释里别写 `*/` 或 `out-*` 这类片段**：`out-*/wk-*` 会提前闭合块注释，导致编译失败。

### 测试在哪

`pdfkit/src/test/java/io/pdfkit/`，按测试阶段分文件：

| 文件 | 覆盖 |
|---|---|
| `ContractTest` | 60 项能力的对外契约（元数据、参数、错误码、产物） |
| `UiTest` | 控件树、表单生成、布局尺寸回归 |
| `SecurityTest` | XXE、路径穿越、加密强度、密码不落日志、临时文件残留 |
| `FunctionalTest` | 60 项能力逐个断言产物内容 |
| `PerformanceTest` | 启动、合并、压缩、内存、并发 |
| `SmokeTest` | 冒烟 |

跑全量 `gradle test` 约 3 分钟。测试不启界面（除了 `UiTest` 会建一个屏外窗口），
能力层可以脱离 UI 直接调 —— 这也是「核心层不依赖界面」这一约束的验证手段。

## 10. 已知待改进项

以下问题是补注释时逐行核对代码发现的，都是**真实存在但未修复**的行为差异。
记在这里而不是只写在注释里，是为了让后来者能一次性看到全貌。

| 位置 | 现象 | 影响 | 修法 |
|---|---|---|---|
| `EditPlusOps.INVERT` 的「增强对比度」 | `clamp()` 只做 0–255 钳制，输入本就在范围内，该模式**等价于原样输出** | 用户选了这个模式会觉得「点了没反应」 | 改成 `(v-128)*k+128` 之类的拉伸 |
| `ConvertPlusOps.writeDocx` | styles.xml 只定义了 Heading1/2/3，但参数允许 `maxLevel=4` | 第 4 级标题在 Word 里退化为正文外观（内容不丢） | 补一段 `w:styleId="Heading4"` 的定义 |
| `AttachOps.allAttachments` | 只查 `Names/EmbeddedFiles`，不查 Catalog 的 `/AF`（PDF 2.0 的关联文件） | 某些文档「列出 0 个附件」但其实有附件 | 追加对 `/AF` 字典的遍历 |
| `ConvertOps.EXTRACT_IMAGES` | 只遍历页面资源里的 XObject | 内联图像（inline image）提不到 | 需解析内容流中的 `BI…ID…EI` 段 |
| `OrganizeOps.DELETE_PAGES` | 用 `List.contains` 判断待删页 | 复杂度 O(n²)，万页级文档会慢 | 换成 `HashSet` |
| `OptimizeOps.median` | `r`/`g`/`b` 三个数组已无引用 | 死代码，干扰阅读 | 直接删三个声明 |
| `RedactOps.RedactStripper.writeString` | `pageHeight`、`baseY` 声明后未使用 | 死代码 | 直接删两个声明 |
| `BookmarkOps.pageOf` | 第一个 `instanceof PDPageFitDestination` 分支被第二个完全覆盖 | 冗余 | 删第一个分支 |

### 已修的（补注释期间顺手修的，需要有回归测试覆盖）

| 位置 | 原来 | 现在 |
|---|---|---|
| `ConvertOps.TO_IMAGE` 单页分支 | 登记产物时传的是**目录**，导致界面双击任务打开输出目录的上一级 | 传具体文件路径 |
| `MainFrame.jobRows` | 缓存「任务 id → 行号」，但新任务插到第 0 行后旧行号未平移，导致连续提交两个任务后状态刷到错误的行 | 去掉缓存，改按 id 线性查找 |
| `ConvertPlusOps.headingSizes` | 「相邻字号差 < 3% 视为同一级」的判断拿层级号（1、2）当字号比，条件永不成立 | 单独维护每级代表字号 |

