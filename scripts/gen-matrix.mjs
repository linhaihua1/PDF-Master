#!/usr/bin/env node
/**
 * 从 integration/registry/capabilities.json 生成 docs/capability-matrix.md。
 * 上游更新后重跑，矩阵随之刷新。
 */
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const reg = JSON.parse(await readFile(resolve(ROOT, 'integration/registry/capabilities.json'), 'utf8'));

const CAT_LABEL = {
  organize: '页面组织', edit: '编辑与标注', convert: '格式转换', optimize: '压缩与优化',
  security: '安全与签名', extract: '内容提取', bookmark: '书签目录', analyze: '分析与体检',
  filter: '筛选与批处理', forms: '表单', automation: '自动化管线', other: '其他',
};
const ENGINE_LABEL = { stirling: 'Stirling', patcher: 'PDFPatcher', native: 'ToolKnit' };
const ORDER = ['organize', 'edit', 'convert', 'optimize', 'security', 'extract', 'bookmark', 'analyze', 'filter', 'forms', 'automation', 'other'];

const lines = [];
lines.push('# 能力矩阵');
lines.push('');
lines.push(`> 自动生成于 ${new Date().toISOString().slice(0, 19).replace('T', ' ')}，来源 \`integration/registry/capabilities.json\`。`);
lines.push(`> 重新生成：\`node scripts/gen-matrix.mjs\``);
lines.push('');
lines.push(`合计 **${reg.stats.total}** 项：` +
  Object.entries(reg.stats.byEngine).map(([k, v]) => `${ENGINE_LABEL[k] ?? k} ${v}`).join(' · '));
lines.push('');

for (const cat of ORDER) {
  const rows = reg.capabilities.filter(c => c.category === cat);
  if (rows.length === 0) continue;
  lines.push(`## ${CAT_LABEL[cat] ?? cat}（${rows.length}）`);
  lines.push('');
  lines.push('| 能力 | 引擎 | 入口 | 输出 | 备注 |');
  lines.push('|---|---|---|---|---|');
  for (const c of rows.sort((a, b) => a.id.localeCompare(b.id))) {
    const entry = c.engine === 'native'
      ? `toolId: ${c.toolId}`
      : c.engine === 'patcher'
        ? `cli: ${c.command}`
        : `\`${c.method} ${c.endpoint}\``;
    const note = c.unique ? '独有' : (c.fallback ? `回落 ${c.fallback}` : (c.offline ? '离线可用' : ''));
    lines.push(`| ${c.title} | ${ENGINE_LABEL[c.engine] ?? c.engine} | ${entry} | ${c.output ?? '-'} | ${note} |`);
  }
  lines.push('');
}

await writeFile(resolve(ROOT, 'docs/capability-matrix.md'), `${lines.join('\n')}\n`, 'utf8');
console.log(`[ok] docs/capability-matrix.md  ${reg.stats.total} 项`);
