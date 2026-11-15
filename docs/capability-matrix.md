# 能力矩阵

> 自动生成于 2026-11-15 04:58:04，来源 `integration/registry/capabilities.json`。
> 重新生成：`node scripts/gen-matrix.mjs`

合计 **124** 项：Stirling 99 · PDFPatcher 8 · ToolKnit 17

## 页面组织（22）

| 能力 | 引擎 | 入口 | 输出 | 备注 |
|---|---|---|---|---|
| 裁剪页面 | ToolKnit | toolId: pdf-crop | pdf | 离线可用 |
| 合并 PDF | ToolKnit | toolId: pdf-merge | pdf | 离线可用 |
| 旋转页面 | ToolKnit | toolId: pdf-rotate | pdf | 离线可用 |
| 拆分 PDF | ToolKnit | toolId: pdf-split | pdf | 离线可用 |
| 按裁剪框裁剪 | PDFPatcher | cli: crop | pdf | 回落 stirling.crop |
| 裁剪 | Stirling | `POST /api/v1/general/crop` | pdf |  |
| 编辑目录 | Stirling | `POST /api/v1/general/edit-table-of-contents` | pdf |  |
| 导出书签 | Stirling | `POST /api/v1/general/extract-bookmarks` | xml |  |
| 合并 | Stirling | `POST /api/v1/general/merge-pdfs` | pdf |  |
| 多页排版 | Stirling | `POST /api/v1/general/multi-page-layout` | pdf |  |
| 叠加文档 | Stirling | `POST /api/v1/general/overlay-pdfs` | pdf |  |
| 重排页面 | Stirling | `POST /api/v1/general/rearrange-pages` | pdf |  |
| 删除页面 | Stirling | `POST /api/v1/general/remove-pages` | pdf |  |
| 海报分块打印 | Stirling | `POST /api/v1/general/split-for-poster-print` | pdf |  |
| 移除图片 | Stirling | `POST /api/v1/general/remove-image-pdf` | pdf |  |
| 旋转 | Stirling | `POST /api/v1/general/rotate-pdf` | pdf |  |
| 缩放页面 | Stirling | `POST /api/v1/general/scale-pages` | pdf |  |
| 多页拼单页 | Stirling | `POST /api/v1/general/pdf-to-single-page` | pdf |  |
| 按分节符拆分 | Stirling | `POST /api/v1/general/split-pdf-by-sections` | pdf |  |
| 按大小/数量拆分 | Stirling | `POST /api/v1/general/split-by-size-or-count` | zip |  |
| 按章节拆分 | Stirling | `POST /api/v1/general/split-pdf-by-chapters` | zip |  |
| 拆分 | Stirling | `POST /api/v1/general/split-pages` | zip |  |

## 编辑与标注（31）

| 能力 | 引擎 | 入口 | 输出 | 备注 |
|---|---|---|---|---|
| PDF 编辑器 | ToolKnit | toolId: pdf-editor | pdf | 离线可用 |
| 添加页码 | ToolKnit | toolId: pdf-page-number | pdf | 离线可用 |
| 做旧扫描件 | ToolKnit | toolId: pdf-to-scan | pdf | 离线可用 |
| 添加注释 | Stirling | `POST /api/v1/misc/add-comments` | pdf |  |
| 添加图像 | Stirling | `POST /api/v1/misc/add-image` | pdf |  |
| 添加页码 | Stirling | `POST /api/v1/misc/add-page-numbers` | pdf |  |
| 添加附件 | Stirling | `POST /api/v1/misc/add-attachments` | pdf |  |
| 批量处理附件 | Stirling | `POST /api/v1/misc/batch-process-attachments` | pdf |  |
| 删除附件 | Stirling | `POST /api/v1/misc/delete-attachment` | pdf |  |
| 导出附件 | Stirling | `POST /api/v1/misc/extract-attachments` | zip |  |
| 导出单个附件 | Stirling | `POST /api/v1/misc/extract-single-attachment` | pdf |  |
| 列出附件 | Stirling | `POST /api/v1/misc/list-attachments` | json |  |
| 重命名附件 | Stirling | `POST /api/v1/misc/rename-attachment` | pdf |  |
| 智能重命名 | Stirling | `POST /api/v1/misc/auto-rename` | pdf |  |
| 自动纠偏 | Stirling | `POST /api/v1/misc/auto-rotate-pdf` | pdf |  |
| 自动拆分 | Stirling | `POST /api/v1/misc/auto-split-pdf` | pdf |  |
| 删除空白页 | Stirling | `POST /api/v1/misc/remove-blanks` | pdf |  |
| 压缩 | Stirling | `POST /api/v1/misc/compress-pdf` | pdf |  |
| 解压 PDF | Stirling | `POST /api/v1/misc/decompress-pdf` | pdf |  |
| 提取扫描图像 | Stirling | `POST /api/v1/misc/extract-image-scans` | pdf |  |
| 提取图像 | Stirling | `POST /api/v1/misc/extract-images` | zip |  |
| 展平 | Stirling | `POST /api/v1/misc/flatten` | pdf |  |
| 修改元数据 | Stirling | `POST /api/v1/misc/update-metadata` | pdf |  |
| OCR/扫描清理 | Stirling | `POST /api/v1/misc/ocr-pdf` | pdf |  |
| 创建组合文档 | Stirling | `POST /api/v1/misc/create-portfolio` | pdf |  |
| 扁平化组合文档 | Stirling | `POST /api/v1/misc/flatten-portfolio` | pdf |  |
| 修复 | Stirling | `POST /api/v1/misc/repair` | pdf |  |
| 替换/反色 | Stirling | `POST /api/v1/misc/replace-invert-pdf` | pdf |  |
| 扫描件效果 | Stirling | `POST /api/v1/misc/scanner-effect` | pdf |  |
| 添加印章 | Stirling | `POST /api/v1/misc/add-stamp` | pdf |  |
| 移除表单字段只读属性 | Stirling | `POST /api/v1/misc/unlock-pdf-forms` | pdf |  |

## 格式转换（20）

| 能力 | 引擎 | 入口 | 输出 | 备注 |
|---|---|---|---|---|
| Excel 转 PDF | ToolKnit | toolId: excel-to-pdf | pdf | 离线可用 |
| 图片转 PDF | ToolKnit | toolId: image-to-pdf | pdf | 离线可用 |
| AI 增强转 Markdown | ToolKnit | toolId: pdf-ai-markdown | pdf |  |
| PDF 转 Markdown | ToolKnit | toolId: pdf-text-markdown | pdf | 离线可用 |
| PDF 转图片 | ToolKnit | toolId: pdf-to-image | pdf | 离线可用 |
| PDF 转 Word | ToolKnit | toolId: pdf-to-word | pdf |  |
| CBR 转 PDF | Stirling | `POST /api/v1/convert/cbr/pdf` | pdf |  |
| CBZ 转 PDF | Stirling | `POST /api/v1/convert/cbz/pdf` | pdf |  |
| 图片转 PDF | Stirling | `POST /api/v1/convert/img/pdf` | pdf |  |
| PDF 转 CBR | Stirling | `POST /api/v1/convert/pdf/cbr` | pdf |  |
| PDF 转 CBZ | Stirling | `POST /api/v1/convert/pdf/cbz` | pdf |  |
| PDF 转图片 | Stirling | `POST /api/v1/convert/pdf/img` | zip |  |
| PDF 转 EPUB | Stirling | `POST /api/v1/convert/pdf/epub` | epub |  |
| PDF 转 Excel | Stirling | `POST /api/v1/convert/pdf/xlsx` | xlsx |  |
| Office 转 PDF | Stirling | `POST /api/v1/convert/file/pdf` | pdf |  |
| 电子书转 PDF | Stirling | `POST /api/v1/convert/ebook/pdf` | pdf |  |
| PDF 转 CSV | Stirling | `POST /api/v1/convert/pdf/csv` | csv |  |
| PDF 转视频 | Stirling | `POST /api/v1/convert/pdf/video` | pdf |  |
| PDF 转矢量图 | Stirling | `POST /api/v1/convert/pdf/vector` | pdf |  |
| 矢量图转 PDF | Stirling | `POST /api/v1/convert/vector/pdf` | pdf |  |

## 压缩与优化（2）

| 能力 | 引擎 | 入口 | 输出 | 备注 |
|---|---|---|---|---|
| 压缩 PDF | ToolKnit | toolId: pdf-compress | pdf | 离线可用 |
| 画质增强 | ToolKnit | toolId: pdf-enhance | pdf | 离线可用 |

## 安全与签名（17）

| 能力 | 引擎 | 入口 | 输出 | 备注 |
|---|---|---|---|---|
| 解密 PDF | ToolKnit | toolId: pdf-decrypt | pdf | 离线可用 |
| 加密 PDF | ToolKnit | toolId: pdf-encrypt | pdf | 离线可用 |
| 解除权限限制 | PDFPatcher | cli: remove-restrictions | pdf | 回落 stirling.remove-password |
| 添加密码 | Stirling | `POST /api/v1/security/add-password` | pdf |  |
| 准备加密会话 | Stirling | `POST /api/v1/security/prepare-pdf-session` | pdf |  |
| 移除密码 | Stirling | `POST /api/v1/security/remove-password` | pdf |  |
| 恢复加密保护 | Stirling | `POST /api/v1/security/restore-pdf-protection` | pdf |  |
| 自动涂黑 | Stirling | `POST /api/v1/security/auto-redact` | pdf |  |
| 手动涂黑 | Stirling | `POST /api/v1/security/redact` | pdf |  |
| 执行涂黑 | Stirling | `POST /api/v1/security/redact-execute` | pdf |  |
| 移除证书签名 | Stirling | `POST /api/v1/security/remove-cert-sign` | pdf |  |
| 净化 PDF | Stirling | `POST /api/v1/security/sanitize-pdf` | pdf |  |
| 时间戳 | Stirling | `POST /api/v1/security/timestamp-pdf` | pdf |  |
| 合规校验 | Stirling | `POST /api/v1/security/validate-compliance` | pdf |  |
| 验证签名 | Stirling | `POST /api/v1/security/validate-signature` | pdf |  |
| 校验 PDF | Stirling | `POST /api/v1/security/verify-pdf` | pdf |  |
| 添加水印 | Stirling | `POST /api/v1/security/add-watermark` | pdf |  |

## 内容提取（1）

| 能力 | 引擎 | 入口 | 输出 | 备注 |
|---|---|---|---|---|
| 提取原始图片 | PDFPatcher | cli: extract-images | zip | 回落 stirling.extract-images |

## 书签目录（3）

| 能力 | 引擎 | 入口 | 输出 | 备注 |
|---|---|---|---|---|
| 自动生成书签 | PDFPatcher | cli: auto-bookmark | pdf | 独有 |
| 导出书签 | PDFPatcher | cli: bookmark-export | xml | 回落 stirling.extract-bookmarks |
| 导入书签 | PDFPatcher | cli: bookmark-import | pdf | 回落 stirling.edit-table-of-contents |

## 分析与体检（10）

| 能力 | 引擎 | 入口 | 输出 | 备注 |
|---|---|---|---|---|
| 字体嵌入体检 | PDFPatcher | cli: font-report | json | 回落 stirling.analyze-pdf.font-info |
| 文档结构探查 | PDFPatcher | cli: inspect | json | 独有 |
| 注释信息 | Stirling | `POST /api/v1/analysis/annotation-info` | json |  |
| 基础信息 | Stirling | `POST /api/v1/analysis/basic-info` | json |  |
| 文档属性 | Stirling | `POST /api/v1/analysis/document-properties` | json |  |
| 字体信息 | Stirling | `POST /api/v1/analysis/font-info` | json |  |
| 表单字段 | Stirling | `POST /api/v1/analysis/form-fields` | json |  |
| 页数统计 | Stirling | `POST /api/v1/analysis/page-count` | json |  |
| 页面尺寸 | Stirling | `POST /api/v1/analysis/page-dimensions` | json |  |
| 加密信息 | Stirling | `POST /api/v1/analysis/security-info` | json |  |

## 筛选与批处理（6）

| 能力 | 引擎 | 入口 | 输出 | 备注 |
|---|---|---|---|---|
| 按图片筛选 | Stirling | `POST /api/v1/filter/filter-contains-image` | pdf |  |
| 按文本筛选 | Stirling | `POST /api/v1/filter/filter-contains-text` | pdf |  |
| 按文件大小筛选 | Stirling | `POST /api/v1/filter/filter-file-size` | pdf |  |
| 按页数筛选 | Stirling | `POST /api/v1/filter/filter-page-count` | pdf |  |
| 按页面方向筛选 | Stirling | `POST /api/v1/filter/filter-page-rotation` | pdf |  |
| 按页面尺寸筛选 | Stirling | `POST /api/v1/filter/filter-page-size` | pdf |  |

## 自动化管线（1）

| 能力 | 引擎 | 入口 | 输出 | 备注 |
|---|---|---|---|---|
| 流水线 | Stirling | `POST /api/v1/pipeline/handleData` | pdf |  |

## 其他（11）

| 能力 | 引擎 | 入口 | 输出 | 备注 |
|---|---|---|---|---|
| 小册子拼版 | Stirling | `POST /booklet-imposition` | pdf |  |
| 证书签名 | Stirling | `POST /cert-sign` | pdf |  |
| 添加字段 | Stirling | `POST /add-fields` | pdf |  |
| 删除字段 | Stirling | `POST /delete-fields` | pdf |  |
| 编辑字段 | Stirling | `POST /edit-fields` | pdf |  |
| 表单导出 CSV | Stirling | `POST /extract-csv` | pdf |  |
| 表单导出 Excel | Stirling | `POST /extract-xlsx` | pdf |  |
| 读取表单字段 | Stirling | `POST /fields` | pdf |  |
| 带坐标字段 | Stirling | `POST /fields-with-coordinates` | pdf |  |
| 填充表单 | Stirling | `POST /fill` | pdf |  |
| 修改字段 | Stirling | `POST /modify-fields` | pdf |  |

