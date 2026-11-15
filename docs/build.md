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
| 安装包 | `dist-installer\PdfKit-1.1.0.exe` | 双击安装，含开始菜单与卸载项 |

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
"$JAVA_HOME/bin/jpackage" --type app-image --name PdfKit --app-version 1.1.0 \
  --input build/install/pdfkit/lib --main-jar pdfkit-1.1.0.jar --main-class io.pdfkit.App \
  --dest dist --java-options "-Dfile.encoding=UTF-8" --java-options "-Xmx1024m"

# 安装包（需要 WiX 在 PATH）
export PATH="/c/dev/wix314:$PATH"
"$JAVA_HOME/bin/jpackage" --type exe --name PdfKit --app-version 1.1.0 \
  --input build/install/pdfkit/lib --main-jar pdfkit-1.1.0.jar --main-class io.pdfkit.App \
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
JAVA_HOME=C:/dev/jdk-17.0.13 /c/dev/gradle-8.14/bin/gradle test
```

35 个端到端用例，覆盖 60 项能力中的 44 项。测试不启界面，直接构造 `Ctx` 调 `cap.op().run(ctx)` —— 这也是「能力层不依赖 UI」这一架构约束的验证手段。

未覆盖的 16 项主要是分析类（输出为文字报告，验证价值低）、以及需要特殊样本的能力（表单、加密、损坏文件）。

报告：`build/reports/tests/test/index.html`

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
