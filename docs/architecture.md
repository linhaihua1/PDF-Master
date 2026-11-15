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
