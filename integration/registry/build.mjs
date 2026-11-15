#!/usr/bin/env node
/**
 * 合并三个引擎的能力清单，产出 integration/registry/capabilities.json。
 * 输入：
 *   stirling-capabilities.json  (由 scripts/gen-registry.py 生成)
 *   patcher-capabilities.json   (手工维护)
 *   native-capabilities.json    (手工维护)
 *   overrides.json              (手工覆盖：多文件字段名、输出类型)
 */
import { readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const read = async name => JSON.parse(await readFile(join(HERE, name), 'utf8'));

const [stirling, patcher, native, overrides] = await Promise.all([
  read('stirling-capabilities.json'),
  read('patcher-capabilities.json'),
  read('native-capabilities.json'),
  read('overrides.json'),
]);

const byEndpoint = overrides.byEndpoint ?? {};
const capabilities = [];
const seen = new Set();

function push(cap, engineName) {
  if (seen.has(cap.id)) {
    console.warn(`[warn] 重复 id: ${cap.id}（${engineName}），已跳过`);
    return;
  }
  seen.add(cap.id);
  capabilities.push({ engine: engineName, ...cap });
}

// 1) Stirling：统一的 REST 端点能力
for (const c of stirling.capabilities) {
  const ov = byEndpoint[c.endpoint] ?? {};
  push({
    id: c.id,
    title: c.title,
    desc: ov.note ? `${c.desc || ov.note}` : c.desc || '',
    category: c.category,
    method: c.method,
    endpoint: c.endpoint,
    files: ov.files ?? [{ field: overrides.notes?.defaultFilesField ?? 'fileInput', multiple: true, required: true }],
    options: ov.options ?? [],
    output: ov.output ?? overrides.notes?.defaultOutput ?? 'pdf',
    ...(ov.note ? { note: ov.note } : {}),
  }, 'stirling');
}

// 2) PDFPatcher：本地命令行能力
for (const c of patcher.capabilities) {
  push({
    id: c.id,
    title: c.title,
    desc: c.desc,
    category: c.category,
    command: c.command,
    files: (c.inputs ?? []).map(i => ({ field: i.name, multiple: false, required: i.required !== false })),
    options: c.options ?? [],
    output: c.output ?? 'pdf',
    fallback: c.fallback,
    unique: c.unique === true,
  }, 'patcher');
}

// 3) ToolKnit：前端原生页面
for (const c of native.capabilities) {
  push({
    id: c.id,
    title: c.title,
    desc: c.desc,
    category: c.category,
    toolId: c.toolId,
    offline: c.offline !== false,
    output: 'pdf',
  }, 'native');
}

const engines = [
  {
    id: 'stirling',
    name: 'Stirling-PDF',
    runtime: 'Java 17+ / Spring Boot',
    upstream: 'https://github.com/linhaihua1/Stirling-PDF',
    baseUrl: 'http://127.0.0.1:8080',
    probe: { type: 'http', path: '/api/v1/info/status' },
    note: '功能主力：转换、OCR、压缩、签名、涂黑、管线自动化',
  },
  patcher.engine,
  native.engine,
];
const byEngine = {};
const byCategory = {};
for (const c of capabilities) {
  byEngine[c.engine] = (byEngine[c.engine] ?? 0) + 1;
  byCategory[c.category] = (byCategory[c.category] ?? 0) + 1;
}

const out = {
  version: '1.0.0',
  generatedAt: new Date().toISOString(),
  sources: {
    stirling: 'engines/stirling-pdf (Spring Boot, 99 个 REST 端点)',
    patcher: 'engines/pdfpatcher (C#/.NET, 经 patcher-cli 包装)',
    native: 'engines/toolknit-desktop (Tauri 前端内置工具)',
  },
  engines,
  stats: { total: capabilities.length, byEngine, byCategory },
  capabilities,
};

await writeFile(join(HERE, 'capabilities.json'), JSON.stringify(out, null, 2) + '\n', 'utf8');
console.log(`[ok] capabilities.json  ${capabilities.length} 项`);
console.log(`     ${Object.entries(byEngine).map(([k, v]) => `${k}=${v}`).join('  ')}`);
