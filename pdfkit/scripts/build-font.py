"""
构建 PdfKit 内置中文字体：NotoSansSC-VF → wght=400 静态实例 → 定向子集 → 字形去重。

三个坑，缺一个就会静默产生错误文本：

坑1 CFF 轮廓：PDFBox 的 PDType0Font 只支持 glyf 轮廓。Noto 官方 OTF 是 CFF，
    加载会抛 "True Type fonts using CFF outlines are not supported"，然后静默
    回退系统字体 —— 目标机没装中文字体时就是一片方框。必须用 VF/TTF 源。

坑2 部首区污染：Noto 原字体把部首码位（U+2E80-2FDF）也映射到与常用汉字相同的
    字形。PDFBox 生成 ToUnicode 时按 GID 反查码位，会挑到部首码位 —— 写「页」
    提取出「⻚」。所以子集时必须排除部首区、康熙部首、兼容汉字、竖排标点。

坑3 cmap 多对一（最隐蔽）：即使排除了部首区，剩余区间内仍有多个码位共享同一
    字形（如 ·U+00B7 与 •U+2022、㬵U+3B35 与 胶U+80F6）。PDFBox 的 ToUnicode
    按 GID 反查且只取最小码位，结果写「胶」复制出来是「㬵」，写「•」得到「·」，
    字形正确但文本全错。本脚本对每个冲突码位复制一份独立字形，使 cmap 严格
    一对一，从根上消除歧义。

校验：脚本末尾断言 cmap 无一对多，任何冲突直接抛错，不允许产出坏字体。
"""
import copy
import os
import sys
from collections import defaultdict

from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont
from fontTools import subset

# 用法：python scripts/build-font.py <源可变字体.ttf> <输出.ttf>
# 源字体取 Google Fonts 的 NotoSansSC[wght].ttf（约 17 MB）；
# 输出直接覆盖 src/main/resources/fonts/NotoSansSC-Regular.ttf
HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SRC = os.path.join(HERE, "NotoSansSC-VF.ttf")
DEFAULT_OUT = os.path.join(os.path.dirname(HERE),
                           "src", "main", "resources", "fonts", "NotoSansSC-Regular.ttf")

SRC = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_SRC
OUT = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_OUT

# 保留区间。原则：只留正文排版真正会用到的区块，且不含任何与常用汉字同形的区。
RANGES = [
    (0x0020, 0x007E),   # ASCII
    (0x00A0, 0x00FF),   # Latin-1 补充
    (0x2000, 0x206F),   # 通用标点
    (0x20A0, 0x20BF),   # 货币符号
    (0x2100, 0x214F),   # 字母式符号
    (0x2190, 0x21FF),   # 箭头
    (0x2200, 0x22FF),   # 数学运算符
    (0x25A0, 0x25FF),   # 几何图形
    (0x2600, 0x26FF),   # 杂项符号
    (0x3000, 0x303F),   # CJK 符号与标点
    (0x3400, 0x4DBF),   # CJK 扩展 A
    (0x4E00, 0x9FFF),   # CJK 统一汉字
    (0xFF00, 0xFFEF),   # 全角字符
]
# 明确排除：
#   U+2E80-2FDF 部首 / U+31C0-31EF 笔画 / U+F900-FAFF 兼容汉字 / U+FE30-FE4F 竖排标点
#   以及 emoji 区（PDFBox 无彩色字形支持）


def gb2312_codepoints():
    """GB2312 全部码位：A1-A9 是符号区（标点/序号/全角/假名/希腊/西里尔/
    注音/制表符），B0-F7 是汉字区。中文文档的基本字符集就是它。"""
    out = []
    for hi in range(0xA1, 0xF8):
        for lo in range(0xA1, 0xFF):
            try:
                s = bytes([hi, lo]).decode("gb2312")
            except UnicodeDecodeError:
                continue
            if len(s) == 1:
                out.append(ord(s))
    return out


def target_codepoints():
    cps = {cp for lo, hi in RANGES for cp in range(lo, hi + 1)}
    cps.update(gb2312_codepoints())      # 兜住 GB2312 符号区，缺一项就是缺字
    return sorted(cps)


def split_duplicate_glyphs(font):
    """让 cmap 严格一对一：冲突码位各复制一份独立字形。返回拆分数。"""
    cmap = font.getBestCmap()
    gid2cps = defaultdict(list)
    for cp, gname in cmap.items():
        gid2cps[gname].append(cp)

    glyf = font["glyf"]
    hmtx = font["hmtx"]
    order = list(font.getGlyphOrder())
    splits = []

    for gname, cps in gid2cps.items():
        if len(cps) < 2:
            continue
        cps = sorted(cps)
        # 首个码位保留原字形，其余各复制一份
        for cp in cps[1:]:
            new_name = f"{gname}.u{cp:04X}"
            if new_name in glyf:
                continue
            order.append(new_name)
            font.setGlyphOrder(order)
            glyf[new_name] = copy.deepcopy(glyf[gname])
            hmtx[new_name] = hmtx[gname]
            for t in font["cmap"].tables:
                if t.isUnicode() and cp in t.cmap:
                    t.cmap[cp] = new_name
            splits.append((gname, cp, new_name))
    return splits


def assert_one_to_one(font, where):
    cmap = font.getBestCmap()
    gid2cps = defaultdict(list)
    for cp, gname in cmap.items():
        gid2cps[gname].append(cp)
    bad = {g: c for g, c in gid2cps.items() if len(c) > 1}
    if bad:
        for g, cps in bad.items():
            print(f"  冲突 {g}: " + " ".join(f"U+{c:04X}" for c in sorted(cps)),
                  file=sys.stderr)
        raise SystemExit(f"[{where}] cmap 仍存在 {len(bad)} 个一对多映射，拒绝产出")
    return len(cmap)


def main():
    print("1/5 读取源字体", SRC)
    font = TTFont(SRC)

    print("2/5 实例化 wght=400")
    static = instantiateVariableFont(font, {"wght": 400}, inplace=False, updateFontNames=True)

    print("3/5 子集化（按区块白名单）")
    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.name_legacy = True
    opts.notdef_outline = True
    opts.recalc_bounds = True
    opts.drop_tables += ["DSIG"]
    subsetter = subset.Subsetter(options=opts)
    want = target_codepoints()
    subsetter.populate(unicodes=want)
    subsetter.subset(static)
    n_before = len(static.getBestCmap())
    print(f"    目标码位 {len(want)} 个，实际保留 {n_before} 个")

    # 先落盘，再重新打开做字形拆分。直接在 subsetter 处理过的对象上增删字形，
    # hmtx/glyf 的内部状态与 glyphOrder 不同步，保存时会 KeyError。
    tmp = OUT + ".stage1"
    static.save(tmp)

    print("4/5 拆分同形字形，消除 cmap 多对一")
    work = TTFont(tmp)
    splits = split_duplicate_glyphs(work)
    print(f"    拆分 {len(splits)} 个")
    for gname, cp, _ in splits:
        print(f"      {gname} + U+{cp:04X}({chr(cp)})")
    work.save(OUT)
    work.close()
    import os
    os.remove(tmp)

    print("5/5 校验产出")
    check = TTFont(OUT)
    n_cp = assert_one_to_one(check, "产出校验")
    n_glyph = len(check.getGlyphOrder())
    import os
    print(f"    {OUT}  {os.path.getsize(OUT)/1024/1024:.1f} MB  "
          f"码位 {n_cp}  字形 {n_glyph}")
    print("    cmap 一对一：通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
