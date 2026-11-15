#!/usr/bin/env node
/**
 * 端到端链路测试（不需要 Java）：
 *   mock Stirling → gateway → 提交任务 → 轮询 → 下载产物 → 断言
 *
 * 运行: node tests/e2e.mjs
 */
import { spawn } from 'node:child_process';
import { mkdir, rm, writeFile, readFile, stat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const TMP = resolve(ROOT, '.work', 'e2e');
const MOCK_PORT = 18080;
const GW_PORT = 18931;
const BASE = `http://127.0.0.1:${GW_PORT}`;

const procs = [];
const sleep = ms => new Promise(r => setTimeout(r, ms));

function launch(name, script, env) {
  const child = spawn(process.execPath, [script], {
    cwd: ROOT,
    env: { ...process.env, ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  child.stdout.on('data', d => process.stdout.write(`[${name}] ${d}`));
  child.stderr.on('data', d => process.stderr.write(`[${name}!] ${d}`));
  procs.push(child);
  return child;
}

async function waitHttp(url, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(1500) });
      if (res.status < 500) return true;
    } catch {
      /* 尚未就绪 */
    }
    await sleep(250);
  }
  return false;
}

let failures = 0;
function check(label, cond, detail = '') {
  if (cond) {
    console.log(`  ok   ${label}`);
  } else {
    failures += 1;
    console.log(`  FAIL ${label}${detail ? ` — ${detail}` : ''}`);
  }
}

async function main() {
  await rm(TMP, { recursive: true, force: true });
  await mkdir(TMP, { recursive: true });

  console.log('1) 启动 mock Stirling 与网关');
  launch('mock', resolve(HERE, 'mock-stirling.mjs'), { MOCK_PORT: String(MOCK_PORT) });
  launch('gw', resolve(ROOT, 'integration/gateway/server.mjs'), {
    PDF_GATEWAY_PORT: String(GW_PORT),
    PDF_STIRLING_BASE: `http://127.0.0.1:${MOCK_PORT}`,
    PDF_JOBS_ROOT: resolve(TMP, 'jobs'),
  });

  if (!(await waitHttp(`http://127.0.0.1:${MOCK_PORT}/api/v1/info/status`))) throw new Error('mock 未就绪');
  if (!(await waitHttp(`${BASE}/healthz`))) throw new Error('网关未就绪');

  console.log('2) 健康检查应判定 stirling=up');
  const health = await (await fetch(`${BASE}/healthz`)).json();
  check('healthz.ok === true', health.ok === true, JSON.stringify(health.engines?.stirling));
  check('stirling 版本可读', health.engines?.stirling?.version === 'mock-3.3.0');

  console.log('3) 能力清单应包含 124 项且 stirling 可用');
  const caps = await (await fetch(`${BASE}/api/capabilities`)).json();
  check('能力总数 124', caps.total === 124, String(caps.total));
  check('可用能力 > 100', caps.available > 100, String(caps.available));

  console.log('4) 构造输入文件');
  const inputA = join(TMP, 'input-a.pdf');
  const inputB = join(TMP, 'input-b.pdf');
  const stub = Buffer.from('%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n', 'latin1');
  await writeFile(inputA, stub);
  await writeFile(inputB, stub);

  console.log('5) 提交合并任务（多文件字段 fileInput）');
  const runRes = await fetch(`${BASE}/api/run`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({
      capabilityId: 'stirling.merge.merge-pdfs',
      files: [inputA, inputB],
      options: { sortType: 'orderProvided' },
    }),
  });
  check('提交返回 202', runRes.status === 202, `HTTP ${runRes.status}`);
  const { jobId } = await runRes.json();
  check('返回 jobId', typeof jobId === 'string' && jobId.length > 10);

  console.log('6) 轮询任务直到完成');
  let job = null;
  for (let i = 0; i < 40; i += 1) {
    job = await (await fetch(`${BASE}/api/jobs/${jobId}`)).json();
    if (job.status === 'done' || job.status === 'failed') break;
    await sleep(250);
  }
  check('任务完成', job?.status === 'done', `${job?.status} ${job?.error ?? ''}`);
  check('有产物', (job?.outputs?.length ?? 0) >= 1);

  console.log('7) 下载产物并校验');
  if (job?.outputs?.length) {
    const out = job.outputs[0];
    const fileRes = await fetch(`${BASE}${out.url}`);
    const buf = Buffer.from(await fileRes.arrayBuffer());
    check('下载 200', fileRes.status === 200);
    check('产物是 PDF（%PDF 头）', buf.subarray(0, 5).toString('latin1') === '%PDF-');
    check('产物大小与描述一致', buf.length === out.bytes, `${buf.length} vs ${out.bytes}`);
  }

  console.log('8) 提交分析任务（JSON 响应）');
  const anaRes = await fetch(`${BASE}/api/run`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ capabilityId: 'stirling.analyze-pdf.page-count', files: [inputA] }),
  });
  const { jobId: anaId } = await anaRes.json();
  let ana = null;
  for (let i = 0; i < 40; i += 1) {
    ana = await (await fetch(`${BASE}/api/jobs/${anaId}`)).json();
    if (ana.status === 'done' || ana.status === 'failed') break;
    await sleep(250);
  }
  check('分析任务完成', ana?.status === 'done', ana?.error ?? '');
  if (ana?.outputs?.length) {
    const json = JSON.parse(await readFile(resolve(TMP, 'jobs', anaId, ana.outputs[0].name), 'utf8'));
    check('JSON 产物含 pageCount', json.pageCount === 1, JSON.stringify(json));
  }

  console.log('9) 非法入参应被拒绝');
  const bad = await fetch(`${BASE}/api/run`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ capabilityId: 'stirling.merge.merge-pdfs', files: ['C:/definitely/missing.pdf'] }),
  });
  check('不存在的文件返回 400', bad.status === 400, String(bad.status));

  const unknown = await fetch(`${BASE}/api/run`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ capabilityId: 'nope.nope', files: [inputA] }),
  });
  check('未知能力返回 404', unknown.status === 404, String(unknown.status));

  console.log(`\n${failures === 0 ? '全部通过' : `失败 ${failures} 项`}`);
  return failures;
}

main()
  .then(code => {
    procs.forEach(p => p.kill());
    process.exit(code === 0 ? 0 : 1);
  })
  .catch(err => {
    console.error('[fatal]', err);
    procs.forEach(p => p.kill());
    process.exit(1);
  });
