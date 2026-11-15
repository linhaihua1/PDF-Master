/**
 * PDFPatcher 适配器：通过 integration/patcher-cli 的可执行文件调用。
 *
 * CLI 契约（见 integration/patcher-cli/README.md）：
 *   PDFPatcher.Cli.exe <command> --in <file> [--extra <file>] --out <dir> [--opt key=value ...] --json
 * 退出码 0 且 stdout 最后一行是 JSON：{"ok":true,"outputs":[{"path":"..."}]}
 */
import { spawn } from 'node:child_process';
import { copyFile, mkdir, stat } from 'node:fs/promises';
import { basename, resolve } from 'node:path';
import { PATCHER_CLI } from '../lib/engines.mjs';

const TIMEOUT_MS = Number(process.env.PDF_PATCHER_TIMEOUT ?? 300_000);

function runProcess(cmd, args, { timeout = TIMEOUT_MS } = {}) {
  return new Promise((done, fail) => {
    const child = spawn(cmd, args, { windowsHide: true });
    let stdout = '';
    let stderr = '';
    const timer = setTimeout(() => {
      child.kill();
      fail(new Error(`PDFPatcher CLI 超时（${timeout} ms）`));
    }, timeout);
    child.stdout.on('data', d => { stdout += d.toString(); });
    child.stderr.on('data', d => { stderr += d.toString(); });
    child.on('error', err => { clearTimeout(timer); fail(err); });
    child.on('close', code => {
      clearTimeout(timer);
      done({ code, stdout, stderr });
    });
  });
}

export async function run(capability, { files, options, job, appendLog }) {
  try {
    await stat(PATCHER_CLI);
  } catch {
    throw new Error('PDFPatcher CLI 未构建，见 docs/build.md 的「PDFPatcher 通道」章节');
  }

  const args = [capability.command];
  if (files.length > 0) args.push('--in', files[0]);
  if (files.length > 1) args.push('--extra', files[1]);
  args.push('--out', job.dir);
  for (const [k, v] of Object.entries(options ?? {})) {
    if (v === undefined || v === null) continue;
    args.push('--opt', `${k}=${v}`);
  }
  args.push('--json');

  appendLog(`exec ${basename(PATCHER_CLI)} ${args.join(' ')}`);
  const { code, stdout, stderr } = await runProcess(PATCHER_CLI, args);
  if (stderr.trim()) appendLog(stderr.trim().slice(0, 1500));
  if (code !== 0) {
    throw new Error(`PDFPatcher 退出码 ${code}${stderr ? `：${stderr.slice(0, 300)}` : ''}`);
  }

  let parsed = null;
  const lines = stdout.trim().split(/\r?\n/).filter(Boolean);
  for (let i = lines.length - 1; i >= 0; i -= 1) {
    try {
      parsed = JSON.parse(lines[i]);
      break;
    } catch {
      /* 继续向上找 */
    }
  }
  if (!parsed?.ok) throw new Error('PDFPatcher 未返回成功结果');

  for (const out of parsed.outputs ?? []) {
    const src = resolve(out.path);
    const dst = resolve(job.dir, basename(src));
    if (src !== dst) await copyFile(src, dst);
    appendLog(`输出 ${basename(dst)}`);
  }
}

/** 供 doctor 使用：打印 CLI 版本信息。 */
export async function version() {
  const { code, stdout } = await runProcess(PATCHER_CLI, ['--version'], { timeout: 15_000 });
  return code === 0 ? stdout.trim() : null;
}

export async function ensureDir(dir) {
  await mkdir(dir, { recursive: true });
}
