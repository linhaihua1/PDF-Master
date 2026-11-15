/**
 * 能力注册表访问层：加载 capabilities.json，并给出每个能力的可用性判定。
 */
import { readFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
export const PROJECT_ROOT = resolve(HERE, '..', '..', '..');
const REGISTRY = resolve(PROJECT_ROOT, 'integration', 'registry', 'capabilities.json');

let cache = null;

export async function loadRegistry() {
  if (cache) return cache;
  const raw = JSON.parse(await readFile(REGISTRY, 'utf8'));
  const map = new Map();
  for (const cap of raw.capabilities) map.set(cap.id, cap);
  cache = { ...raw, map };
  return cache;
}

export async function getCapability(id) {
  const reg = await loadRegistry();
  return reg.map.get(id) ?? null;
}

/** 注册表里出现的分类，按固定顺序返回，供前端分组渲染。 */
export const CATEGORY_ORDER = [
  'organize', 'edit', 'convert', 'optimize', 'security',
  'extract', 'bookmark', 'analyze', 'filter', 'forms', 'automation', 'other',
];

export const CATEGORY_LABELS = {
  organize: '页面组织',
  edit: '编辑与标注',
  convert: '格式转换',
  optimize: '压缩与优化',
  security: '安全与签名',
  extract: '内容提取',
  bookmark: '书签目录',
  analyze: '分析与体检',
  filter: '筛选与批处理',
  forms: '表单',
  automation: '自动化管线',
  other: '其他',
};
