#!/usr/bin/env python3
"""从 Stirling-PDF 源码 + zh-CN 语言包生成能力清单（供 integration/registry 使用）。

用法: python scripts/gen-registry.py [--repo engines/stirling-pdf] [--out integration/registry]
产出: integration/registry/stirling-capabilities.json
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 端点前缀 -> 功能大类
CATEGORY_BY_GROUP = {
    "General": "organize",
    "Misc": "edit",
    "Convert": "convert",
    "Security": "security",
    "Filter": "filter",
    "Analysis": "analyze",
    "Pipeline": "automation",
    "Form": "forms",
}

# controller 名 -> 语言包段名（不匹配时按规则自动回落）
SEGMENT_HINTS = {
    "Rotation": "rotate",
    "RearrangePagesPDF": "pageOps",
    "SplitPDF": "split",
    "Merge": "merge",
    "PdfOverlay": "overlay",
    "MultiPageLayout": "multiTool",
    "ScalePages": "scale",
    "Crop": "crop",
    "ToSinglePage": "singlePage",
    "BookletImposition": "bookletImposition",
    "PosterPdf": "posterPdf",
    "EditTableOfContents": "editTableOfContents",
    "SplitPdfBySize": "splitBySize",
    "SplitPdfBySections": "splitBySections",
    "SplitPdfByChapters": "splitPdfByChapters",
    "ConvertOffice": "convert",
    "ConvertImgPDF": "convertImg",
    "ConvertPDFToOffice": "convertToOffice",
    "ConvertPDFToPDFA": "convertToPdfa",
    "ConvertPDFToHtml": "convertToHtml",
    "ConvertPDFToExcel": "convertToExcel",
    "ConvertPDFToEpub": "convertToEpub",
    "ConvertPdfToVideo": "pdfToVideo",
    "ConvertHtmlToPDF": "htmlToPdf",
    "ConvertMarkdownToPdf": "markdownToPdf",
    "ConvertEbookToPDF": "ebookToPdf",
    "ConvertWebsiteToPDF": "urlToPdf",
    "ConvertSvgToPDF": "svgToPdf",
    "ConvertEmlToPDF": "emlToPdf",
    "ExtractCSV": "extractCsv",
    "PdfVectorExport": "vectorExport",
    "Compress": "compress",
    "DecompressPdf": "decompress",
    "ExtractImages": "extractImages",
    "ExtractImageScans": "extractImageScans",
    "RemoveImages": "removeImages",
    "Repair": "repair",
    "Flatten": "flatten",
    "RemoveBlanks": "removeBlanks",
    "AutoRename": "autoRename",
    "AutoRotate": "autoRotate",
    "AutoSplitPdf": "autoSplit",
    "PageNumbers": "addPageNumbers",
    "Stamp": "stamp",
    "OverlayImage": "addImage",
    "AddComments": "addComments",
    "UpdateMetadata": "updateMetadata",
    "ScannerEffect": "scannerEffect",
    "ReplaceAndInvertColor": "replaceInvert",
    "UnlockPDFForms": "unlockPDFForms",
    "OCR": "ocr",
    "Password": "password",
    "Watermark": "watermark",
    "Sanitize": "sanitize",
    "Redact": "redact",
    "CertSign": "certSign",
    "ValidateSignature": "validateSignature",
    "VerifyPDF": "verifyPDF",
    "Timestamp": "timestamp",
    "RemoveCertSign": "removeCertSign",
    "GetInfoOnPDF": "getPdfInfo",
    "Attachment": "attachments",
    "Portfolio": "portfolio",
    "PrintFile": "printFile",
    "ShowJavascript": "showJS",
    "Analysis": "analyzePdf",
    "Filter": "filter",
    "Pipeline": "pipeline",
    "FormFill": "formFill",
    "MobileScanner": "scanner",
    "ValidateCompliance": "validateCompliance",
}

# 这些 controller 只提供配置/元数据，不作为用户可执行能力
SKIP_CONTROLLERS = {
    "Config", "Settings", "UIData", "LoginDisclaimer", "AdditionalLanguageJs",
    "HardwareSigning", "ShowJavascript", "PrintFile",
}

VERB_TITLE = {
    "/page-count": "页数统计",
    "/basic-info": "基础信息",
    "/document-properties": "文档属性",
    "/page-dimensions": "页面尺寸",
    "/form-fields": "表单字段",
    "/annotation-info": "注释信息",
    "/font-info": "字体信息",
    "/security-info": "加密信息",
    "/extract-bookmarks": "导出书签",
    "/edit-table-of-contents": "编辑目录",
    "/list-attachments": "列出附件",
    "/extract-attachments": "导出附件",
    "/extract-single-attachment": "导出单个附件",
    "/add-attachments": "添加附件",
    "/rename-attachment": "重命名附件",
    "/delete-attachment": "删除附件",
    "/batch-process-attachments": "批量处理附件",
    "/create-portfolio": "创建组合文档",
    "/flatten-portfolio": "扁平化组合文档",
    "/split-pdf-by-chapters": "按章节拆分",
    "/split-pdf-by-sections": "按分节符拆分",
    "/split-by-size-or-count": "按大小/数量拆分",
    "/remove-pages": "删除页面",
    "/rearrange-pages": "重排页面",
    "/pdf-to-single-page": "多页拼单页",
    "/split-for-poster-print": "海报分块打印",
    "/multi-page-layout": "多页排版",
    "/overlay-pdfs": "叠加文档",
    "/crop": "裁剪",
    "/scale-pages": "缩放页面",
    "/rotate-pdf": "旋转",
    "/merge-pdfs": "合并",
    "/split-pages": "拆分",
    # 转换类：同一 controller 下路径即功能
    "/pdf/img": "PDF 转图片",
    "/img/pdf": "图片转 PDF",
    "/cbz/pdf": "CBZ 转 PDF",
    "/pdf/cbz": "PDF 转 CBZ",
    "/cbr/pdf": "CBR 转 PDF",
    "/pdf/cbr": "PDF 转 CBR",
    "/ebook/pdf": "电子书转 PDF",
    "/file/pdf": "Office 转 PDF",
    "/pdf/epub": "PDF 转 EPUB",
    "/pdf/xlsx": "PDF 转 Excel",
    "/pdf/csv": "PDF 转 CSV",
    "/pdf/vector": "PDF 转矢量图",
    "/vector/pdf": "矢量图转 PDF",
    "/pdf/video": "PDF 转视频",
    "/pdf/html": "PDF 转 HTML",
    "/html/pdf": "HTML 转 PDF",
    "/markdown/pdf": "Markdown 转 PDF",
    "/url/pdf": "网页转 PDF",
    "/svg/pdf": "SVG 转 PDF",
    "/eml/pdf": "邮件转 PDF",
    "/pdf/pdfa": "PDF 转 PDF/A",
    "/pdf/presentation": "PDF 转 PPT",
    "/pdf/text": "PDF 转文本",
    "/pdf/word": "PDF 转 Word",
    "/pdf/xml": "PDF 转 XML",
    # 安全类
    "/add-password": "添加密码",
    "/remove-password": "移除密码",
    "/add-watermark": "添加水印",
    "/sanitize-pdf": "净化 PDF",
    "/auto-redact": "自动涂黑",
    "/redact": "手动涂黑",
    "/redact-execute": "执行涂黑",
    "/cert-sign": "证书签名",
    "/timestamp-pdf": "时间戳",
    "/validate-signature": "验证签名",
    "/verify-pdf": "校验 PDF",
    "/remove-cert-sign": "移除证书签名",
    "/validate-compliance": "合规校验",
    # 表单类
    "/fields": "读取表单字段",
    "/fields-with-coordinates": "带坐标字段",
    "/extract-csv": "表单导出 CSV",
    "/extract-xlsx": "表单导出 Excel",
    "/add-fields": "添加字段",
    "/edit-fields": "编辑字段",
    "/modify-fields": "修改字段",
    "/delete-fields": "删除字段",
    "/fill": "填充表单",
    # 其余单端点控制器
    "/filter-contains-text": "按文本筛选",
    "/filter-contains-image": "按图片筛选",
    "/filter-page-count": "按页数筛选",
    "/filter-page-size": "按页面尺寸筛选",
    "/filter-file-size": "按文件大小筛选",
    "/filter-page-rotation": "按页面方向筛选",
    "/add-comments": "添加注释",
    "/auto-rename": "智能重命名",
    "/auto-rotate-pdf": "自动纠偏",
    "/auto-split-pdf": "自动拆分",
    "/remove-blanks": "删除空白页",
    "/decompress-pdf": "解压 PDF",
    "/extract-image-scans": "提取扫描图像",
    "/update-metadata": "修改元数据",
    "/remove-image-pdf": "移除图片",
    "/replace-invert-pdf": "替换/反色",
    "/scanner-effect": "扫描件效果",
    "/add-stamp": "添加印章",
    "/prepare-pdf-session": "准备加密会话",
    "/restore-pdf-protection": "恢复加密保护",
}


def parse_toml_titles(path: Path) -> dict[str, dict[str, str]]:
    """极简 TOML 段解析：只取 [section] 下的 title/desc/submit。"""
    sections: dict[str, dict[str, str]] = {}
    cur = ""
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        m = re.fullmatch(r"\[([^\]]+)\]", line)
        if m:
            cur = m.group(1)
            continue
        m = re.match(r'^(title|desc|submit)\s*=\s*"(.*)"\s*$', line)
        if m and cur and "." not in cur:
            sections.setdefault(cur, {})[m.group(1)] = m.group(2).replace('\\"', '"')
    return sections


def camel_to_kebab(s: str) -> str:
    s = re.sub(r"(?<=[a-z0-9])([A-Z])", r"-\1", s)
    s = re.sub(r"(?<=[A-Z])([A-Z][a-z])", r"-\1", s)
    return s.lower()


def collect_endpoints(api_root: Path, repo: Path) -> list[dict]:
    ann_root = repo / "app" / "common" / "src" / "main" / "java" / "stirling" / "software" / "common" / "annotations" / "api"
    prefix: dict[str, str] = {}
    for f in ann_root.glob("*Api.java"):
        m = re.search(r'@RequestMapping\("([^"]+)"\)', f.read_text(encoding="utf-8", errors="replace"))
        if m:
            prefix[f.stem[:-3]] = m.group(1)

    rows: list[dict] = []
    for f in sorted(api_root.rglob("*Controller.java")):
        text = f.read_text(encoding="utf-8", errors="replace")
        ctrl = f.stem.replace("Controller", "")
        if ctrl in SKIP_CONTROLLERS:
            continue
        api = re.search(r"@(\w+)Api\b", text)
        base = prefix.get(api.group(1), "") if api else ""
        for m in re.finditer(r"@(AutoJobPostMapping|PostMapping|GetMapping)\((.*?)\)\s*(?:@|public|\w)", text, re.S):
            blk = m.group(2)
            v = re.search(r'value\s*=\s*"([^"]+)"|value\s*=\s*\{([^}]+)\}|^\s*"([^"]+)"', blk)
            if not v:
                continue
            raw = next(g for g in v.groups() if g)
            path = raw.strip().strip('"').split('"')[0]
            full = base + (path if path.startswith("/") else "/" + path)
            if "{" in full:  # 会话类路径不适合一次性调用
                continue
            verb = "GET" if m.group(1) == "GetMapping" else "POST"
            rows.append({"controller": ctrl, "verb": verb, "path": full, "leaf": path, "source": f.name})

    dedup: dict[tuple[str, str], dict] = {}
    for r in rows:
        dedup.setdefault((r["verb"], r["path"]), r)
    return list(dedup.values())


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=str(ROOT / "engines" / "stirling-pdf"))
    ap.add_argument("--out", default=str(ROOT / "integration" / "registry"))
    args = ap.parse_args()

    repo = Path(args.repo)
    api_root = repo / "app" / "core" / "src" / "main" / "java" / "stirling" / "software" / "SPDF" / "controller" / "api"
    toml = repo / "frontend" / "public" / "locales" / "zh-CN" / "translation.toml"
    if not api_root.exists():
        print(f"[fail] 找不到 controller 目录: {api_root}", file=sys.stderr)
        return 2

    titles = parse_toml_titles(toml) if toml.exists() else {}
    rows = collect_endpoints(api_root, repo)

    caps: list[dict] = []
    for r in rows:
        ctrl = r["controller"]
        seg = SEGMENT_HINTS.get(ctrl, ctrl[0].lower() + ctrl[1:])
        meta = titles.get(seg, {})
        group = re.search(r"/api/v1/(\w+)", r["path"])
        category = CATEGORY_BY_GROUP.get(group.group(1).capitalize(), "other") if group else "other"

        multi = sum(1 for x in rows if x["controller"] == ctrl) > 1
        leaf = r.get("leaf") or r["path"]
        if leaf in VERB_TITLE and (multi or leaf != r["path"]):
            title = VERB_TITLE[leaf]
            desc = meta.get("desc", "")
            cid = f"stirling.{camel_to_kebab(seg)}.{leaf.strip('/').replace('/', '-')}"
        else:
            title = VERB_TITLE.get(leaf) or meta.get("title") or camel_to_kebab(ctrl)
            desc = meta.get("desc", "")
            cid = f"stirling.{camel_to_kebab(seg)}"
            if multi:
                cid += "." + leaf.strip("/").replace("/", "-")

        caps.append({
            "id": cid,
            "engine": "stirling",
            "title": title,
            "desc": desc,
            "category": category,
            "method": r["verb"],
            "endpoint": r["path"],
            "controller": ctrl,
        })

    # 同一 id 冲突时追加端点后缀
    used: dict[str, int] = {}
    for c in caps:
        used[c["id"]] = used.get(c["id"], 0) + 1
        if used[c["id"]] > 1:
            c["id"] = f"{c['id']}-{used[c['id']]}"

    named = sum(1 for c in caps if c["title"] != camel_to_kebab(c["controller"]))
    outdir = Path(args.out)
    outdir.mkdir(parents=True, exist_ok=True)
    dst = outdir / "stirling-capabilities.json"
    dst.write_text(json.dumps({
        "_comment": "由 scripts/gen-registry.py 生成，勿手工编辑",
        "source": "engines/stirling-pdf",
        "generatedAt": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "endpointCount": len(caps),
        "capabilities": caps,
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"[ok] {dst}")
    print(f"     端点 {len(caps)} 个 | 中文名 {named} 个 | 语言包段 {len(titles)} 个")
    missing = [c for c in caps if c["title"] == camel_to_kebab(c["controller"])][:8]
    if missing:
        print("     未取到中文名示例: " + ", ".join(f"{c['controller']}({c['endpoint']})" for c in missing))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
