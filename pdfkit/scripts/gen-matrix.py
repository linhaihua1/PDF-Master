#!/usr/bin/env python3
"""从 Java 源码提取能力清单，生成 docs/capability-matrix.md。

用法：python pdfkit/scripts/gen-matrix.py
每次改动 Registry 后重跑，保证文档与代码一致。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OPS = ROOT / "pdfkit" / "src" / "main" / "java" / "io" / "pdfkit" / "ops"
REG = ROOT / "pdfkit" / "src" / "main" / "java" / "io" / "pdfkit" / "core" / "Registry.java"
OUT = ROOT / "docs" / "capability-matrix.md"

CAP_RE = re.compile(
    r'new Capability\(\s*"([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*((?:"(?:[^"\\]|\\.)*"\s*\+?\s*)+)',
    re.S,
)
PARAM_RE = re.compile(r'Param\.(text|num|bool|choice|password)\(\s*"([^"]+)",\s*"([^"]+)"(?:,\s*"([^"]*)")?')

CATEGORY_ORDER = ["页面组织", "内容编辑", "格式转换", "表单处理", "书签与目录", "安全与合规", "读取与分析", "优化与修复"]


def load_registered() -> list[str]:
    """按 Registry 里注册的先后顺序取能力常量名，决定文档排序。"""
    text = REG.read_text(encoding="utf-8")
    body = text.split("List.of(", 1)[1].split(");", 1)[0]
    return re.findall(r"(\w+Ops\.\w+)", body)


def unquote(expr: str) -> str:
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', expr)
    return "".join(parts).replace('\\"', '"').replace("\\n", " ").strip()


def scan() -> dict[str, dict]:
    caps: dict[str, dict] = {}
    order: list[str] = []
    for f in sorted(OPS.glob("*.java")):
        src = f.read_text(encoding="utf-8")
        # 逐个能力切块，块内找 Param 声明
        starts = [m.start() for m in re.finditer(r"new Capability\(", src)]
        for i, s in enumerate(starts):
            end = starts[i + 1] if i + 1 < len(starts) else len(src)
            chunk = src[s:end]
            m = CAP_RE.match(chunk)
            if not m:
                continue
            cid, title, category, desc_expr = m.group(1), m.group(2), m.group(3), m.group(4)
            const = f"{f.stem}.{find_const(src, s)}"
            params = [
                {"kind": k, "key": key, "label": label, "def": default}
                for k, key, label, default in PARAM_RE.findall(chunk[:chunk.find("ctx ->") if "ctx ->" in chunk else len(chunk)])
            ]
            caps[const] = {
                "id": cid,
                "title": title,
                "category": category,
                "desc": unquote(desc_expr),
                "params": params,
                "file": f.name,
            }
            order.append(const)
    return caps, order


def find_const(src: str, pos: int) -> str:
    """向上找 public static final Capability XXX = 里的常量名。"""
    head = src[:pos]
    names = re.findall(r"Capability\s+(\w+)\s*=", head)
    return names[-1] if names else "UNKNOWN"


def main() -> int:
    caps, order = scan()
    registered = load_registered()
    missing = [r for r in registered if r not in caps]
    if missing:
        print(f"警告：Registry 注册了但源码未解析到：{missing}", file=sys.stderr)

    lines: list[str] = []
    lines.append("# 能力矩阵（60 项）")
    lines.append("")
    lines.append("> 本文件由 `pdfkit/scripts/gen-matrix.py` 从源码自动生成，请勿手工编辑。")
    lines.append("> 生成依据：`Registry.java` 的注册顺序 + 各 `*Ops.java` 的 `Capability` 声明。")
    lines.append("")
    lines.append(f"总计 **{len(registered)} 项**，分布在 {len(set(caps[r]['category'] for r in registered if r in caps))} 个分类。")
    lines.append("")

    # 分类汇总
    lines.append("## 汇总")
    lines.append("")
    lines.append("| 分类 | 项数 |")
    lines.append("|---|---|")
    counts: dict[str, int] = {}
    for r in registered:
        if r in caps:
            counts[caps[r]["category"]] = counts.get(caps[r]["category"], 0) + 1
    for cat in CATEGORY_ORDER:
        if cat in counts:
            lines.append(f"| {cat} | {counts[cat]} |")
    for cat, n in counts.items():
        if cat not in CATEGORY_ORDER:
            lines.append(f"| {cat} | {n} |")
    lines.append(f"| **合计** | **{len(registered)}** |")
    lines.append("")

    # 分分类明细
    for cat in CATEGORY_ORDER + [c for c in counts if c not in CATEGORY_ORDER]:
        group = [r for r in registered if r in caps and caps[r]["category"] == cat]
        if not group:
            continue
        lines.append(f"## {cat}（{len(group)} 项）")
        lines.append("")
        lines.append("| # | 能力 | 能力 ID | 说明 |")
        lines.append("|---|---|---|---|")
        for i, r in enumerate(group, 1):
            c = caps[r]
            lines.append(f"| {i} | {c['title']} | `{c['id']}` | {c['desc']} |")
        lines.append("")
        # 参数表
        detail = [r for r in group if caps[r]["params"]]
        if detail:
            lines.append("<details><summary>参数明细</summary>")
            lines.append("")
            for r in detail:
                c = caps[r]
                ps = "；".join(
                    f"{p['label']}（{p['kind']}"
                    + (f"，默认 {p['def']}" if p["def"] else "")
                    + "）"
                    for p in c["params"]
                )
                lines.append(f"- **{c['title']}**：{ps}")
            lines.append("")
            lines.append("</details>")
            lines.append("")

    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"已生成 {OUT.relative_to(ROOT)}：{len(registered)} 项")
    return 0


if __name__ == "__main__":
    sys.exit(main())
