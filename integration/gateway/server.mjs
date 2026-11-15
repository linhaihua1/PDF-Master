#!/usr/bin/env node
/**
 * PDF 统一能力网关
 *
 *   GET  /healthz                      健康检查 + 三引擎状态
 *   GET  /api/engines                  引擎明细
 *   GET  /api/capabilities             能力清单（含 available 判定）
 *   POST /api/upload                   上传本地文件（multipart，返回落盘路径）
 *   POST /api/run                      提交任务 {capabilityId, files[], options{}}
 *   GET  /api/jobs                     最近任务
 *   GET  /api/jobs/:id                 任务详情（含输出下载地址）
 *   GET  /api/jobs/:id/files/:name     下载产物
 *   POST /api/jobs/:id/cancel          取消排队中的任务
 *
 * 只监听回环地址，路径参数只接受本机已存在的文件。
 */
import { createServer } from 'node:http';
import { access, stat, mkdir, writeFile } from 'node:fs/promises';
import { constants, createReadStream } from 'node:fs';
import { Readable } from 'node:stream';
import { randomUUID } from 'node:crypto';
import { basename, resolve, sep } from 'node:path';
import { loadRegistry, CATEGORY_ORDER, CATEGORY_LABELS } from './lib/registry.mjs';
import { probeEngines, capabilityAvailability } from './lib/engines.mjs';
import { createJob, getJob, listJobs, appendLog, collectOutputs, publicJob, JOBS_ROOT } from './lib/jobs.mjs';
import * as stirlingAdapter from './adapters/stirling.mjs';
import * as patcherAdapter from './adapters/patcher.mjs';

const PORT = Number(process.env.PDF_GATEWAY_PORT ?? 8931);
const HOST = process.env.PDF_GATEWAY_HOST ?? '127.0.0.1';
const MAX_BODY = 1 << 20; // 1 MB，/api/run 只传路径不传文件内容
const MAX_UPLOAD = Number(process.env.PDF_MAX_UPLOAD ?? 512 * 1024 * 1024);
const MAX_PARALLEL = Number(process.env.PDF_GATEWAY_PARALLEL ?? 2);
const UPLOADS_ROOT = resolve(JOBS_ROOT, '..', 'uploads');

const ADAPTERS = { stirling: stirlingAdapter, patcher: patcherAdapter };

let running = 0;
const queue = [];

function send(res, code, body, headers = {}) {
  const payload = typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body);
  res.writeHead(code, {
    'content-type': typeof body === 'string' ? 'text/plain; charset=utf-8' : 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    ...headers,
  });
  res.end(payload);
}

function readJson(req) {
  return new Promise((done, fail) => {
    let size = 0;
    const chunks = [];
    req.on('data', c => {
      size += c.length;
      if (size > MAX_BODY) {
        fail(Object.assign(new Error('请求体过大'), { status: 413 }));
        req.destroy();
        return;
      }
      chunks.push(c);
    });
    req.on('end', () => {
      if (chunks.length === 0) return done({});
      try {
        done(JSON.parse(Buffer.concat(chunks).toString('utf8')));
      } catch {
        fail(Object.assign(new Error('请求体不是合法 JSON'), { status: 400 }));
      }
    });
    req.on('error', fail);
  });
}

async function validateFiles(files) {
  if (!Array.isArray(files) || files.length === 0) {
    throw Object.assign(new Error('files 不能为空，需要本机绝对路径'), { status: 400 });
  }
  const checked = [];
  for (const f of files) {
    if (typeof f !== 'string' || !f.trim()) {
      throw Object.assign(new Error('files 元素必须是路径字符串'), { status: 400 });
    }
    const abs = resolve(f);
    try {
      await access(abs, constants.R_OK);
      const s = await stat(abs);
      if (!s.isFile()) throw new Error('不是文件');
      checked.push(abs);
    } catch {
      throw Object.assign(new Error(`文件不可读：${abs}`), { status: 400 });
    }
  }
  return checked;
}

function pump() {
  while (running < MAX_PARALLEL && queue.length > 0) {
    const task = queue.shift();
    running += 1;
    execute(task)
      .catch(err => {
        task.job.status = 'failed';
        task.job.error = String(err.message ?? err);
        appendLog(task.job, `[error] ${task.job.error}`);
      })
      .finally(() => {
        task.job.finishedAt = new Date().toISOString();
        running -= 1;
        pump();
      });
  }
}

async function execute({ job, capability, files, options }) {
  const adapter = ADAPTERS[capability.engine];
  if (!adapter) throw new Error(`引擎 ${capability.engine} 不支持直接执行（native 能力由桌面端处理）`);

  job.status = 'running';
  job.startedAt = new Date().toISOString();
  const log = line => appendLog(job, line);
  log(`执行 ${capability.id}（${capability.engine}）`);
  await adapter.run(capability, { files, options, job, appendLog: log });
  await collectOutputs(job);
  job.status = 'done';
  log(`完成，产出 ${job.outputs.length} 个文件`);
}

/**
 * multipart 上传：为浏览器端（拿不到本地绝对路径）准备。
 * 用 undici 的 Request.formData() 解析，避免引入第三方依赖。
 */
async function handleUpload(req, res) {
  const ctype = req.headers['content-type'] ?? '';
  if (!ctype.startsWith('multipart/form-data')) {
    throw Object.assign(new Error('需要 multipart/form-data'), { status: 415 });
  }
  const declared = Number(req.headers['content-length'] ?? 0);
  if (declared > MAX_UPLOAD) {
    throw Object.assign(new Error(`上传超过上限 ${(MAX_UPLOAD / 1048576).toFixed(0)} MB`), { status: 413 });
  }

  const request = new Request('http://127.0.0.1/upload', {
    method: 'POST',
    headers: { 'content-type': ctype },
    body: Readable.toWeb(req),
    duplex: 'half',
  });
  const form = await request.formData();

  const dir = resolve(UPLOADS_ROOT, randomUUID());
  await mkdir(dir, { recursive: true });

  const saved = [];
  for (const [, value] of form.entries()) {
    if (typeof value === 'string') continue;
    const rawName = basename(value.name || 'upload.bin');
    const safeName = rawName.replace(/[\\/:*?"<>|]/g, '_');
    const buf = Buffer.from(await value.arrayBuffer());
    if (buf.length === 0) continue;
    const target = resolve(dir, safeName);
    if (!target.startsWith(dir + sep)) continue;
    await writeFile(target, buf);
    saved.push({ name: safeName, path: target, bytes: buf.length });
  }
  if (saved.length === 0) {
    throw Object.assign(new Error('未收到任何文件'), { status: 400 });
  }
  send(res, 200, { dir, files: saved });
}

const ROUTES = [
  ['POST', /^\/api\/upload$/, handleUpload],

  ['GET', /^\/healthz$/, async (req, res) => {
    const engines = await probeEngines({ force: true });
    const ok = engines.stirling.state === 'up';
    send(res, ok ? 200 : 503, {
      ok,
      service: 'pdf-gateway',
      version: '1.0.0',
      engines,
      jobs: { total: listJobs().length, running, queued: queue.length },
    });
  }],

  ['GET', /^\/api\/engines$/, async (req, res) => {
    send(res, 200, await probeEngines());
  }],

  ['GET', /^\/api\/capabilities$/, async (req, res) => {
    const [reg, engines] = await Promise.all([loadRegistry(), probeEngines()]);
    const caps = reg.capabilities.map(cap => {
      const avail = capabilityAvailability(cap, engines);
      return {
        id: cap.id,
        engine: cap.engine,
        title: cap.title,
        desc: cap.desc,
        category: cap.category,
        output: cap.output,
        toolId: cap.toolId ?? null,
        endpoint: cap.endpoint ?? null,
        command: cap.command ?? null,
        fallback: cap.fallback ?? null,
        unique: cap.unique === true,
        offline: cap.offline === true,
        files: cap.files ?? [],
        options: cap.options ?? [],
        ...avail,
      };
    });
    const grouped = {};
    for (const cat of CATEGORY_ORDER) {
      const items = caps.filter(c => c.category === cat);
      if (items.length > 0) grouped[cat] = { label: CATEGORY_LABELS[cat] ?? cat, items };
    }
    const rest = caps.filter(c => !CATEGORY_ORDER.includes(c.category));
    if (rest.length > 0) grouped.other = { label: '其他', items: [...(grouped.other?.items ?? []), ...rest] };

    send(res, 200, {
      version: reg.version,
      generatedAt: reg.generatedAt,
      stats: reg.stats,
      engines,
      categories: grouped,
      total: caps.length,
      available: caps.filter(c => c.available).length,
    });
  }],

  ['POST', /^\/api\/run$/, async (req, res) => {    const body = await readJson(req);
    const { capabilityId, files, options } = body ?? {};
    if (!capabilityId) throw Object.assign(new Error('缺少 capabilityId'), { status: 400 });

    const reg = await loadRegistry();
    const capability = reg.map.get(capabilityId);
    if (!capability) throw Object.assign(new Error(`未知能力：${capabilityId}`), { status: 404 });
    if (capability.engine === 'native') {
      throw Object.assign(new Error('native 能力由桌面端直接打开，不经网关执行'), { status: 400 });
    }

    const engines = await probeEngines();
    const avail = capabilityAvailability(capability, engines);
    if (!avail.available) {
      throw Object.assign(new Error(avail.reason), { status: 409, hint: avail.hint });
    }

    const checked = await validateFiles(files);
    const job = await createJob({ capability, files: checked, options: options ?? {} });
    queue.push({ job, capability, files: checked, options: options ?? {} });
    pump();
    send(res, 202, { jobId: job.id, status: job.status, poll: `/api/jobs/${job.id}` });
  }],

  ['GET', /^\/api\/jobs$/, async (req, res) => {
    send(res, 200, { jobs: listJobs().slice(0, 30).map(publicJob) });
  }],

  ['GET', /^\/api\/jobs\/([\w-]+)$/, async (req, res, m) => {
    const job = getJob(m[1]);
    if (!job) throw Object.assign(new Error('任务不存在'), { status: 404 });
    if (job.status === 'done') await collectOutputs(job);
    send(res, 200, publicJob(job));
  }],

  ['GET', /^\/api\/jobs\/([\w-]+)\/files\/([^/]+)$/, async (req, res, m) => {
    const job = getJob(m[1]);
    if (!job) throw Object.assign(new Error('任务不存在'), { status: 404 });
    const name = decodeURIComponent(m[2]);
    const file = resolve(job.dir, name);
    if (!file.startsWith(job.dir + sep)) {
      throw Object.assign(new Error('非法文件名'), { status: 400 });
    }
    try {
      const s = await stat(file);
      if (!s.isFile()) throw new Error('not file');
      res.writeHead(200, {
        'content-type': 'application/octet-stream',
        'content-length': s.size,
        'content-disposition': `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
      });
      createReadStream(file).pipe(res);
    } catch {
      throw Object.assign(new Error('产物不存在或已清理'), { status: 404 });
    }
  }],

  ['POST', /^\/api\/jobs\/([\w-]+)\/cancel$/, async (req, res, m) => {
    const job = getJob(m[1]);
    if (!job) throw Object.assign(new Error('任务不存在'), { status: 404 });
    const idx = queue.findIndex(t => t.job.id === job.id);
    if (idx >= 0) {
      queue.splice(idx, 1);
      job.status = 'cancelled';
      job.finishedAt = new Date().toISOString();
      appendLog(job, '已取消（排队中）');
      return send(res, 200, publicJob(job));
    }
    if (job.status === 'running') {
      return send(res, 409, { error: '任务已开始执行，无法取消' });
    }
    send(res, 200, publicJob(job));
  }],
];

/** 只对本机页面放行跨源：file://（Origin: null）、localhost、127.0.0.1。 */
function corsHeaders(origin) {
  if (!origin) return { 'access-control-allow-origin': '*' }; // 非浏览器调用（curl/后端）
  if (origin === 'null' || /^https?:\/\/(127\.0\.0\.1|localhost|\[::1\])(:\d+)?$/.test(origin)) {
    return {
      'access-control-allow-origin': origin,
      'access-control-allow-methods': 'GET,POST,OPTIONS',
      'access-control-allow-headers': 'content-type',
      'access-control-max-age': '600',
      vary: 'Origin',
    };
  }
  return null;
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${HOST}:${PORT}`);
  const path = url.pathname;

  const cors = corsHeaders(req.headers.origin);
  if (!cors) {
    return send(res, 403, { error: '来源不被允许', origin: req.headers.origin });
  }
  for (const [k, v] of Object.entries(cors)) res.setHeader(k, v);

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    return res.end();
  }

  try {
    for (const [method, pattern, handler] of ROUTES) {
      const m = pattern.exec(path);
      if (m && req.method === method) {
        await handler(req, res, m);
        return;
      }
    }
    if (path === '/' && req.method === 'GET') {
      return send(res, 200, 'PDF 能力网关已就绪。GET /api/capabilities 查看能力清单，GET /healthz 查看引擎状态。');
    }
    send(res, 404, { error: 'not found', path });
  } catch (err) {
    const status = err.status ?? 500;
    if (status >= 500) console.error('[error]', err);
    send(res, status, { error: String(err.message ?? err), ...(err.hint ? { hint: err.hint } : {}) });
  }
});

server.listen(PORT, HOST, () => {
  console.log(`PDF 能力网关 http://${HOST}:${PORT}`);
  console.log(`  产物目录 ${JOBS_ROOT}`);
  console.log(`  并发上限 ${MAX_PARALLEL}，能力清单 GET /api/capabilities`);
});

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    console.log(`\n收到 ${sig}，关闭网关`);
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 3000).unref();
  });
}
