/**
 * 引擎探测：判断三条通道当前是否可用。
 * 结果缓存 5 秒，避免前端轮询把探测放大。
 */
import { access } from 'node:fs/promises';
import { constants } from 'node:fs';
import { resolve } from 'node:path';
import { PROJECT_ROOT } from './registry.mjs';

const CACHE_MS = 5000;
let cache = { at: 0, data: null };

export const STIRLING_BASE = process.env.PDF_STIRLING_BASE ?? 'http://127.0.0.1:8080';
export const PATCHER_CLI = process.env.PDF_PATCHER_CLI
  ?? resolve(PROJECT_ROOT, 'integration', 'patcher-cli', 'bin', 'PDFPatcher.Cli.exe');

async function probeStirling(timeoutMs = 2500) {
  const url = `${STIRLING_BASE}/api/v1/info/status`;
  const started = Date.now();
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(timeoutMs) });
    const ms = Date.now() - started;
    // 401/403 也说明服务在跑（只是开了安全模式）
    if (res.ok || res.status === 401 || res.status === 403) {
      let version = null;
      try {
        const body = await res.json();
        version = body?.version ?? body?.appVersion ?? null;
      } catch {
        /* 非 JSON 响应同样视为存活 */
      }
      return { state: 'up', baseUrl: STIRLING_BASE, version, latencyMs: ms };
    }
    return { state: 'degraded', baseUrl: STIRLING_BASE, detail: `HTTP ${res.status}`, latencyMs: ms };
  } catch (err) {
    return {
      state: 'down',
      baseUrl: STIRLING_BASE,
      detail: err.name === 'TimeoutError' ? '探测超时' : String(err.message ?? err),
    };
  }
}

async function probePatcher() {
  try {
    await access(PATCHER_CLI, constants.X_OK);
    return { state: 'up', entry: PATCHER_CLI };
  } catch {
    return {
      state: 'down',
      entry: PATCHER_CLI,
      detail: '未找到 CLI 可执行文件，需先构建 integration/patcher-cli（见 docs/build.md）',
    };
  }
}

export async function probeEngines({ force = false } = {}) {
  const now = Date.now();
  if (!force && cache.data && now - cache.at < CACHE_MS) return cache.data;

  const [stirling, patcher] = await Promise.all([probeStirling(), probePatcher()]);
  const data = {
    stirling,
    patcher,
    native: { state: 'up', detail: '前端内置，无需外部进程' },
  };
  cache = { at: now, data };
  return data;
}

/** 能力是否可执行：按引擎状态判定，patcher 未构建则自动降级。 */
export function capabilityAvailability(cap, engines) {
  if (cap.engine === 'native') return { available: true, reason: null };
  if (cap.engine === 'stirling') {
    if (engines.stirling.state === 'up') return { available: true, reason: null };
    return {
      available: false,
      reason: `Stirling-PDF 未就绪（${engines.stirling.detail ?? engines.stirling.state}）`,
      hint: '运行 npm run start:all 或 node scripts/dev.mjs all 启动本地服务',
    };
  }
  if (cap.engine === 'patcher') {
    if (engines.patcher.state === 'up') return { available: true, reason: null };
    return {
      available: false,
      reason: 'PDFPatcher 命令行未构建',
      hint: cap.fallback ? `可改用 ${cap.fallback}` : '见 docs/build.md',
    };
  }
  return { available: false, reason: '未知引擎' };
}
