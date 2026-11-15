# PDFPatcher 命令行包装

给上游 PDFPatcher（WinForms 单体程序，无 CLI）加一个命令行外壳，让网关能调用它的 8 项独有能力。

## 现状

`PatcherCli.cs` 已完成：参数解析、JSON 输出契约、错误处理、8 个命令的分发骨架。
**未完成**：每个命令的实际处理调用（代码里是 `throw new NotSupportedException` + `TODO`），
以及**未在本机编译验证**（本机无 .NET Framework 开发工具链）。

编不出、或没接线时，网关会把 `patcher` 通道标记为 `down`，注册表里 8 项能力显示
`available:false` 并给出 Stirling 等价替代。不会阻断其他通道。

## CLI 契约

```bash
PDFPatcher.Cli.exe <command> --in <file> [--extra <file>] --out <dir> [--opt k=v ...] --json
```

| 参数 | 说明 |
|---|---|
| `command` | `auto-bookmark` / `bookmark-export` / `bookmark-import` / `font-report` / `extract-images` / `inspect` / `crop` / `remove-restrictions` |
| `--in` | 主输入文件 |
| `--extra` | 第二输入文件（如书签 XML） |
| `--out` | 输出目录，必须可写 |
| `--opt k=v` | 算法参数，可重复；取值见 `integration/registry/patcher-capabilities.json` 的 `options` |
| `--json` | 输出机器可读结果（网关固定带此参数） |
| `--version` | 打印版本 |

成功时退出码 0，stdout 最后一行是：

```json
{"ok":true,"command":"font-report","outputs":[{"path":"C:\\out\\report.json"}]}
```

失败时退出码非 0，同样输出 `{"ok":false,...,"error":"..."}`；网关会把 `error` 直接写进作业日志。

## 构建

前提：Visual Studio 2019+ 或 Build Tools，含 **.NET Framework 4.0 目标包**。仅有运行时不够。

```powershell
# 1) 编上游本体（产出 bin\Release\PDFPatcher.exe）
msbuild engines\pdfpatcher\PDFPatcher.sln /p:Configuration=Release /p:Platform="Any CPU"

# 2) 编 CLI
msbuild integration\patcher-cli\PatcherCli.csproj /p:Configuration=Release
```

产物 `integration/patcher-cli/bin/PDFPatcher.Cli.exe`，同时会带上 PDFPatcher.exe 与 `App/Lib/*.dll`。

## 接线指引

处理逻辑都在 `engines/pdfpatcher/App/Processor/`，按命令对应：

| 命令 | 参考文件 | 关键类型 |
|---|---|---|
| `auto-bookmark` | `AutoBookmark/AutoBookmarkCreator.cs` + `Processor/AutoBookmarkCreator.cs` | 标题规则、层级判定 |
| `bookmark-export` / `bookmark-import` | `Processor/OutlineManager.cs` | 书签树读写、页码偏移 |
| `font-report` | Stirling 的 `analysis/font-info` 结果结构 | 遍历页面资源字典 |
| `extract-images` | `Processor/ImageExtractor.cs` | 原始编码与 DPI 保留 |
| `inspect` | `Processor/Mupdf/`、`PdfContentStreamParser.cs` | 对象树、内容流 |
| `crop` | `Processor/PdfPageCommandProcessor.cs` | CropBox 与 MediaBox |
| `remove-restrictions` | `Processor/PdfHelper.cs` | 口令与权限位 |

三个坑：

1. **WinForms 依赖**：`Processor` 里 `DocInfoExporter`、`PdfHelper`、`Worker` 等文件直接调用
   `MessageBox` / 进度条。CLI 里要么把 `Main` 标为 `[STAThread]` 并吞掉弹窗，要么把这些文件里的
   UI 调用改成事件回调。先做前者更省事。
2. **闭源依赖**：`App/Lib` 下 MuPDFLib、CL、itextsharp 都是预编译 DLL，必须随产物一起复制，
   否则运行期 `FileNotFoundException`。
3. **路径与编码**：处理中文路径时 PDFPatcher 内部混用 ANSI/UTF-8，建议 CLI 入口统一转绝对路径并
   在 `--out` 目录用 ASCII 临时文件名，最后再改回原名。

## 为什么先不硬做

PDFPatcher 的核心价值是中文书签的启发式生成，规则高度耦合 UI 状态（当前页、选中范围、视图字号）。
把它抽成无状态 CLI 是一次真实的重构，不是套个壳就能完事。相比之下，Stirling 已覆盖其中 6 项
（书签导出、图片提取、裁剪、解密、字体信息），差距只在「自动生成书签」「结构探查」两项
——这两项才是值得投入的接线目标。

建议顺序：先接 `auto-bookmark` 与 `inspect`，其余保持回落。
