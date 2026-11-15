# 构建与打包

## 1. 环境要求

| 组件 | 版本 | 用途 | 本机路径 |
|---|---|---|---|
| JDK | 17（LTS） | 编译 + `jpackage` | `C:\dev\jdk-17.0.13` |
| Gradle | 8.14 | 构建 | `C:\dev\gradle-8.14` |
| WiX Toolset | 3.14 | `jpackage --type exe` 生成安装包 | `C:\dev\wix314`（需在 PATH 里） |

> JDK 选 17 而不是 25：JDK 17 是长期支持版、`jpackage` 功能完整，且不引入新版语法导致维护门槛。Stirling 需要的 JDK 25 在本项目中已不适用。

WiX 只需在**打安装包**时用；只出绿色版可以不装。

## 2. 一键构建

```powershell
cd <仓库根>\pdfkit

# 编译 + 测试
.\scripts\build.ps1

# 编译 + 测试 + 绿色版 + 安装包
.\scripts\build.ps1 -Package

# 跳过测试（仅调试用）
.\scripts\build.ps1 -SkipTest
```

产物位置：

| 产物 | 路径 | 说明 |
|---|---|---|
| 绿色版 | `dist\PdfKit\PdfKit.exe` | 整个 `PdfKit` 目录可直接拷走 |
| 安装包 | `dist-installer\PdfKit-1.2.1.exe` | 双击安装，含开始菜单与卸载项 |

## 3. 手工构建（等价命令）

```bash
export JAVA_HOME="C:/dev/jdk-17.0.13"

# 编译
/c/dev/gradle-8.14/bin/gradle compileJava

# 测试
/c/dev/gradle-8.14/bin/gradle test

# 生成可分发的 lib 目录
/c/dev/gradle-8.14/bin/gradle installDist

# 绿色版
"$JAVA_HOME/bin/jpackage" --type app-image --name PdfKit --app-version 1.2.1 \
  --input build/install/pdfkit/lib --main-jar pdfkit-1.2.1.jar --main-class io.pdfkit.App \
  --dest dist --java-options "-Dfile.encoding=UTF-8" --java-options "-Xmx1024m"

# 安装包（需要 WiX 在 PATH）
export PATH="/c/dev/wix314:$PATH"
"$JAVA_HOME/bin/jpackage" --type exe --name PdfKit --app-version 1.2.1 \
  --input build/install/pdfkit/lib --main-jar pdfkit-1.2.1.jar --main-class io.pdfkit.App \
  --dest dist-installer --win-menu --win-shortcut --win-dir-chooser \
  --java-options "-Dfile.encoding=UTF-8" --java-options "-Xmx1024m"
```

## 4. 依赖

全部来自 Maven，走阿里云镜像（`build.gradle` 已配）。首次构建需联网下载约 40 MB，之后离线可重复构建。

| 依赖 | 版本 | 许可 | 用途 |
|---|---|---|---|
| pdfbox / pdfbox-tools / xmpbox / fontbox | 3.0.5 | Apache 2.0 | PDF 读写、渲染、字体、XMP 元数据 |
| bcprov-jdk18on / bcpkix-jdk18on | 1.80 | BouncyCastle | AES-256 加密、CMS 签名、X.509 证书 |
| flatlaf / flatlaf-extras | 3.5.4 | Apache 2.0 | 现代化 Swing 外观 |
| junit-jupiter | 5.11.4 | EPL 2.0 | 测试（不进入产物） |

## 5. 测试

```bash
export JAVA_HOME="C:/dev/jdk-17.0.13"
/c/dev/gradle-8.14/bin/gradle test
```

76 个用例，按五阶段串行门禁组织（编排见 `test-execution-pipeline`）：

| 阶段 | 测试类 | 用例 | 关注点 |
|---|---|---|---|
| S1 接口 | `ContractTest` | 10 | 60 项能力的元数据、参数声明、错误码契约、错误信息不泄露内部实现 |
| S2 UI | `UiTest` | 6 | 控件树遍历 + 反射读私有字段，含两档窗口尺寸的布局回归断言 |
| S3 安全 | `SecurityTest` | 11 | XXE、深层嵌套炸弹、附件名路径穿越、AES-256 强度、密码不落日志 |
| S4 功能 | `FunctionalTest` | 8 | 逐项断言产物内容（页数、提取文本、书签层级、加密状态、docx 条目） |
| S5 性能 | `PerformanceTest` | 6 | 启动耗时、合并与压缩吞吐、空闲与峰值内存、双任务并发 |

另有 `SmokeTest`（35 个历史端到端用例）与 `StartupProbe`（启动夹具：在独立 JVM 里复现 App 初始化，
用于测启动耗时与空闲内存，避免测试框架自身的堆占用污染数据）。

测试不启界面，直接构造 `Ctx` 调 `cap.op().run(ctx)` —— 这也是「能力层不依赖 UI」这一架构约束的验证手段。

报告：`build/reports/tests/test/index.html`。缺陷清单与性能实测见 `pdfkit/TEST-REPORT.md`。

## 6. 能力文档同步

改动 `Registry.java` 或任一 `*Ops.java` 后，务必重跑：

```bash
python pdfkit/scripts/gen-matrix.py
```

它会重新读取源码生成 `docs/capability-matrix.md`，保证文档里的能力数与代码一致。若 Registry 注册了但源码里解析不到，脚本会打印警告。

## 7. 版本号变更

版本号出现在三处，必须同步改：

1. `pdfkit/build.gradle` → `version`
2. `pdfkit/scripts/build.ps1` → `--app-version` 与 `--main-jar`
3. `README.md` 与本文档里的产物文件名

## 8. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `jpackage` 报 `WiX not found` | WiX 不在 PATH | 把 `C:\dev\wix314` 加进 PATH，或改用 `--type app-image` 只出绿色版 |
| 构建报「找不到 JDK 17」 | `JAVA_HOME` 未设 | `build.ps1` 里已写死路径，改那一行即可 |
| 运行 exe 闪退 | 看日志 | `%LOCALAPPDATA%\PdfKit\logs\` 下按天滚动 |
| 中文变方框 | 字体资源未打进 jar | 检查 `src/main/resources/fonts/NotoSansSC-Regular.ttf` 存在且被 `processResources` 收进产物 |
| 安装包体积暴涨 | 误把 `dist/` 打进安装包 | `jpackage --input` 只指向 `build/install/pdfkit/lib` |

## 9. 重建内置字体

> 只有**更换字体**时才需要做这一步。字体文件已在仓库里（9.75 MB），日常构建不涉及。

### 为什么不能直接扔一个 ttf 进 `fonts/`

PDFBox 有两个隐性要求，不满足时不报错，只是效果不对：

**约束一：必须是 glyf 轮廓的 TrueType。**
CFF 轮廓的 OTF（多数 `.otf` 都是）会让 `PDType0Font.load` 抛
`True Type fonts using CFF outlines are not supported`。`Fonts.chinese()` 捕获异常后
回退系统字体——测试全绿，但在没装中文字体的机器上全是方框。

**约束二：cmap 不能多对一。**
PDFBox 生成 ToUnicode 时按 GID 反查字形，同一字形有多个码位映射时**只取最小的那个**。
Noto 原始字体把「页」的部首形（U+2EDA）和「页」（U+9875）映射到同一字形，
于是写「页」提取出来是「⻚」。字形看着对，但复制、搜索、文本提取、转 Word 全错。

**第三条注意事项：`hasGlyph()` 不能用来验缺字。**
对 `embedSubset=true` 加载的字体，它把入参当 CID/GID 而非 Unicode 码位，
只有码位数值小于字形总数才返回 true，结论等于随机。唯一可信的验法是
写入后用 `PDFTextStripper` 提取比对。

### 操作步骤

**① 准备源字体**

下载 `NotoSansSC-VF.ttf`（思源黑体可变字体，glyf 轮廓，SIL OFL 许可），放到脚本同级目录。

**② 跑构建脚本**

```bash
cd <仓库根>/pdfkit/scripts
python build-font.py
```

需要 `fonttools`：`pip install fonttools`

脚本做四件事：

1. 把可变字体实例化成 `wght=400`（Regular）静态字体；
2. 定向子集——白名单为「常用区间 ∪ GB2312 全码表」，产出约 28700 码位；
3. **逐字形拆分同形字**：给每个冲突码位复制一份独立的字形（glyf + hmtx），
   让 cmap 严格一对一。这是解决约束二的关键，不是「二选一丢字符」；
4. 产出前断言 cmap 无多对一，不满足则拒绝写文件。

成功时输出类似：

```
GB2312 码位 7445，产出字体缺 0 个
拆分同形字 19 个
cmap 一对一校验：通过
```

**③ 部署并验证**

```bash
cp NotoSansSC-builtin.ttf ../src/main/resources/fonts/NotoSansSC-Regular.ttf
```

然后跑字体相关的测试：

```bash
export JAVA_HOME="C:/dev/jdk-17.0.13"
/c/dev/gradle-8.14/bin/gradle test --tests "io.pdfkit.FunctionalTest.bundledChineseFontIsUsable"
```

这个用例会扫 GB2312 全部 7445 个码位，逐个写入 PDF 再提取比对。
它能抓住 cmap 多对一的问题；**`hasGlyph` 抓不到**，所以这个用例必须过。

**④ 确认打进产物**

```bash
unzip -l build/install/pdfkit/lib/pdfkit-1.2.1.jar | grep fonts/NotoSans
```

应能看到约 9.75 MB 的 `NotoSansSC-Regular.ttf`。

### 换字体时容易忽略的一点

字体变了，`.docx` 导出的字体设置（`ConvertPlusOps` 里写死的「Microsoft YaHei」）
不会跟着变。那是 Word 端的字体，与 PDF 内的嵌入字体是两回事，不需要同步修改。

