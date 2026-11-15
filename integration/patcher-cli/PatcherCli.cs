// PDFPatcher 命令行包装（骨架）
//
// 目标：把 PDFPatcher 的处理引擎暴露成网关可调用的 CLI。
//
//   PDFPatcher.Cli.exe <command> --in <file> [--extra <file>] --out <dir> [--opt k=v ...] --json
//
// 现状说明（重要）：
//   1. 上游 PDFPatcher 是 WinForms 单体程序，没有命令行入口；处理逻辑在 App/Processor 下，
//      部分文件直接调用 System.Windows.Forms（弹窗/进度条）。
//   2. 本文件只实现参数解析、输出契约与错误处理骨架；各 command 的实际处理需要在
//      Execute() 中调用 PDFPatcher.Processor.* 的具体类型补全（见 TODO 标记）。
//   3. 本文件未在本机编译验证 —— 本机没有 .NET Framework 开发工具链（见 docs/build.md）。
//      编不出来时，网关会把 patcher 通道标记为 down，所有能力自动回落到 Stirling 等价端点。

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;

namespace PdfPatcherCli
{
    internal static class Program
    {
        private const string Version = "0.1.0";

        private static int Main(string[] args)
        {
            if (args.Length == 0 || args.Contains("--help") || args.Contains("-h"))
            {
                Console.WriteLine("用法: PDFPatcher.Cli.exe <command> --in <file> [--extra <file>] --out <dir> [--opt k=v ...] --json");
                Console.WriteLine("命令: auto-bookmark | bookmark-export | bookmark-import | font-report | extract-images | inspect | crop | remove-restrictions");
                return args.Length == 0 ? 2 : 0;
            }

            if (args[0] == "--version")
            {
                Console.WriteLine(Version);
                return 0;
            }

            var cli = CliArgs.Parse(args);
            try
            {
                var outputs = Execute(cli);
                EmitJson(new { ok = true, command = cli.Command, outputs = outputs.Select(p => new { path = p }) });
                return 0;
            }
            catch (Exception ex)
            {
                EmitJson(new { ok = false, command = cli.Command, error = ex.Message, type = ex.GetType().Name });
                return 1;
            }
        }

        private static IReadOnlyList<string> Execute(CliArgs cli)
        {
            if (string.IsNullOrEmpty(cli.Input) || !File.Exists(cli.Input))
                throw new FileNotFoundException("--in 指定的文件不存在", cli.Input);

            Directory.CreateDirectory(cli.OutputDir);
            var stem = Path.GetFileNameWithoutExtension(cli.Input);

            switch (cli.Command)
            {
                case "auto-bookmark":
                    // TODO: 参照 App/Processor/AutoBookmarkCreator.cs
                    //   var ctx = new DocumentProcessorContext(cli.Input, target, processors, null, null, true);
                    //   ctx.ProcessDocument();
                    //   规则来自 cli.Option("levelRule") / cli.Option("titlePattern")
                    throw new NotSupportedException("auto-bookmark 尚未接线：需在 PDFPatcher.Processor.AutoBookmarkCreator 上补齐调用");

                case "bookmark-export":
                    // TODO: OutlineManager.ExportBookmark(target, cli.Option("format", "xml"))
                    throw new NotSupportedException("bookmark-export 尚未接线：见 Processor/OutlineManager.cs");

                case "bookmark-import":
                    // TODO: OutlineManager.ImportBookmark(target, cli.Extra, offset)
                    throw new NotSupportedException("bookmark-import 尚未接线：见 Processor/OutlineManager.cs");

                case "font-report":
                    // TODO: AnalysisController 同款字体遍历 -> JSON 输出
                    throw new NotSupportedException("font-report 尚未接线：可参考 Stirling /api/v1/analysis/font-info 的结果结构");

                case "extract-images":
                    // TODO: ImageExtractor.Extract(cli.Input, cli.OutputDir, minWidth, minHeight)
                    throw new NotSupportedException("extract-images 尚未接线：见 Processor/ImageExtractor.cs");

                case "inspect":
                    // TODO: MuPDFLib 打开文档后遍历对象树，输出 JSON
                    throw new NotSupportedException("inspect 尚未接线");

                case "crop":
                    // TODO: PdfPageCommandProcessor + CropBox 设置
                    throw new NotSupportedException("crop 尚未接线");

                case "remove-restrictions":
                    // TODO: PdfHelper 解密后另存
                    throw new NotSupportedException("remove-restrictions 尚未接线");

                default:
                    throw new ArgumentException($"未知命令: {cli.Command}");
            }
        }

        private static void EmitJson(object payload)
        {
            var json = JsonSerializer.Serialize(payload, new JsonSerializerOptions { WriteIndented = false });
            Console.WriteLine(json);
        }
    }

    internal sealed class CliArgs
    {
        public string Command { get; private set; } = "";
        public string Input { get; private set; } = "";
        public string Extra { get; private set; } = "";
        public string OutputDir { get; private set; } = ".";
        private readonly Dictionary<string, string> _options = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        public string Option(string key, string fallback = null)
        {
            return _options.TryGetValue(key, out var v) ? v : fallback;
        }

        public static CliArgs Parse(string[] args)
        {
            var cli = new CliArgs { Command = args[0] };
            for (var i = 1; i < args.Length; i++)
            {
                var a = args[i];
                switch (a)
                {
                    case "--in":
                        cli.Input = Next(args, ref i);
                        break;
                    case "--extra":
                        cli.Extra = Next(args, ref i);
                        break;
                    case "--out":
                        cli.OutputDir = Next(args, ref i);
                        break;
                    case "--opt":
                        var kv = Next(args, ref i);
                        var idx = kv.IndexOf('=');
                        if (idx > 0) cli._options[kv.Substring(0, idx)] = kv.Substring(idx + 1);
                        break;
                    case "--json":
                        break;
                    default:
                        throw new ArgumentException($"无法识别的参数: {a}");
                }
            }
            return cli;
        }

        private static string Next(string[] args, ref int i)
        {
            if (i + 1 >= args.Length) throw new ArgumentException($"{args[i]} 缺少取值");
            return args[++i];
        }
    }
}
