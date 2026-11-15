/**
 * Stirling-PDF 适配器：把本地文件按能力的 multipart 约定上传，落盘返回结果。
 * 端点约定见 engines/stirling-pdf（@AutoJobPostMapping + @ModelAttribute DTO）。
 */
import { readFile, writeFile } from 'node:fs/promises';
import { basename, extname, resolve } from 'node:path';
import { STIRLING_BASE } from '../lib/engines.mjs';

const DEFAULT_TIMEOUT_MS = Number(process.env.PDF_STIRLING_TIMEOUT ?? 600_000);

const MIME = {
  '.pdf': 'application/pdf',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.tif': 'image/tiff',
  '.tiff': 'image/tiff',
  '.gif': 'image/gif',
  '.bmp': 'image/bmp',
  '.svg': 'image/svg+xml',
  '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  '.xlsx': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  '.pptx': 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  '.doc': 'application/msword',
  '.xls': 'application/vnd.ms-excel',
  '.ppt': 'application/vnd.ms-powerpoint',
  '.odt': 'application/vnd.oasis.opendocument.text',
  '.ods': 'application/vnd.oasis.opendocument.spreadsheet',
  '.txt': 'text/plain',
  '.md': 'text/markdown',
  '.html': 'text/html',
  '.epub': 'application/epub+zip',
  '.zip': 'application/zip',
  '.json': 'application/json',
  '.csv': 'text/csv',
};

function mimeOf(path) {
  return MIME[extname(path).toLowerCase()] ?? 'application/octet-stream';
}

/** 组装 multipart：多文件字段铺开，单文件字段只取第一个。 */
async function buildForm(capability, files, options, job, appendLog) {
  const form = new FormData();
  const used = new Set();

  for (const field of capability.files ?? []) {
    const picked = field.multiple ? files : files.slice(0, 1);
    if (picked.length === 0) {
      if (field.required) throw new Error(`缺少必需文件字段 ${field.field}`);
      continue;
    }
    if (!field.multiple && files.length > 1) {
      appendLog(`${field.field} 只接受一个文件，已取 ${basename(files[0])}`);
    }
    for (const f of picked) {
      const buf = await readFile(f);
      form.append(field.field, new Blob([buf], { type: mimeOf(f) }), basename(f));
      used.add(f);
    }
  }

  const unused = files.filter(f => !used.has(f));
  if (unused.length > 0) {
    appendLog(`忽略未声明的输入文件：${unused.map(basename).join(', ')}`);
  }

  for (const [key, value] of Object.entries(options ?? {})) {
    if (value === undefined || value === null) continue;
    if (Array.isArray(value)) {
      for (const v of value) form.append(key, String(v));
    } else if (typeof value === 'boolean') {
      form.append(key, value ? 'true' : 'false');
    } else if (typeof value === 'object') {
      form.append(key, JSON.stringify(value));
    } else {
      form.append(key, String(value));
    }
  }
  return form;
}

function decodeFilename(disposition, fallback) {
  if (!disposition) return fallback;
  const star = /filename\*=UTF-8''([^;]+)/i.exec(disposition);
  if (star) {
    try {
      return decodeURIComponent(star[1]);
    } catch {
      /* 继续尝试普通 filename */
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(disposition);
  if (plain) {
    // 部分响应把中文按 latin1 编码
    try {
      const decoded = Buffer.from(plain[1], 'latin1').toString('utf8');
      return /\uFFFD/.test(decoded) ? plain[1] : decoded;
    } catch {
      return plain[1];
    }
  }
  return fallback;
}

const EXT_BY_OUTPUT = {
  pdf: '.pdf', docx: '.docx', xlsx: '.xlsx', csv: '.csv', txt: '.txt',
  zip: '.zip', png: '.png', jpg: '.jpg', json: '.json', epub: '.epub', xml: '.xml',
};

export async function run(capability, { files, options, job, appendLog }) {
  const url = `${STIRLING_BASE}${capability.endpoint}`;
  appendLog(`POST ${url}`);
  const form = await buildForm(capability, files, options, job, appendLog);

  const res = await fetch(url, {
    method: 'POST',
    body: form,
    signal: AbortSignal.timeout(DEFAULT_TIMEOUT_MS),
  });

  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`Stirling 返回 HTTP ${res.status}${text ? `：${text.slice(0, 400)}` : ''}`);
  }

  const ctype = res.headers.get('content-type') ?? '';
  const buf = Buffer.from(await res.arrayBuffer());

  if (ctype.includes('application/json')) {
    const name = `${capability.id.replace(/[./]/g, '-')}.json`;
    const out = resolve(job.dir, name);
    await writeFile(out, JSON.stringify(JSON.parse(buf.toString('utf8')), null, 2), 'utf8');
    appendLog(`JSON 结果 ${name}（${buf.length} B）`);
    return;
  }

  const suggested = decodeFilename(res.headers.get('content-disposition'), null);
  const ext = extname(suggested ?? '') || EXT_BY_OUTPUT[capability.output] || '.bin';
  const name = suggested && extname(suggested)
    ? suggested
    : `${capability.id.replace(/[./]/g, '-')}${ext}`;
  const out = resolve(job.dir, name);
  await writeFile(out, buf);
  appendLog(`输出 ${name}（${(buf.length / 1024).toFixed(1)} KB）`);
}
