#!/usr/bin/env node
/**
 * 本地开发编排：
 *   node scripts/dev.mjs --check     环境体检（不启动任何进程）
 *   node scripts/dev.mjs gateway     只起网关
 *   node scripts/dev.mjs all         起 Stirling（若可运行）+ 网关
 *
 * Stirling-PDF 3.3.0 要求 JDK 25；检测到低版本时明确报错，不静默降级。
 */
import { spawn, spawnSync } from 'node:child_process';
import { access, readdir } from 'node:fs/promises';
import { constants } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '..');
const STIRLING = resolve(ROOT, 'engines', 'stirling-pdf');
const REQUIRED_JAVA = 25;
const GW_PORT = process.env.PDF_GATEWAY_PORT ?? '8931';

const procs = [];
const sleep = ms => new Promise(r => setTimeout(r, ms));

function javaVersion() {
  const r = spawnSync('java', ['-version'], { encoding: 'utf8' });
  const text = `${r.stderr ?? ''}${r.stdout ?? ''}`;
  const m = /version "(\d+)(?:\.(\d+))?/.exec(text);
  if (!m) return null;
  const major = Number(m[1]) === 1 ? Number(m[2]) : Number(m[1]);
  return { major, raw: text.split('\n')[0].trim() };
}

async function exists(p) {
  try {
    await access(p, constants.F_OK);
    return true;
  } catch {
    return false;
  }
}

async function findStirlingJar() {
  const dirs = [
    resolve(STIRLING, 'app', 'core', 'build', 'libs'),
    resolve(STIRLING, 'app', 'build', 'libs'),
  ];
  for (const dir of dirs) {
    if (!(await exists(dir))) continue;
    const files = await readdir(dir);
    const jar = files.find(f => f.endsWith('.jar') && !f.includes('sources') && !f.includes('plain'));
    if (jar) return resolve(dir, jar);
  }
  return null;
}

async function check() {
  let bad = 0;
  const line = (label, ok, detail) => {
    console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${label}${detail ? ` — ${detail}` : ''}`);
    if (!ok) bad += 1;
  };

  console.log('运行环境');
  const nodeMajor = Number(process.versions.node.split('.')[0]);
  line(`Node ${process.versions.node}`, nodeMajor >= 18, nodeMajor >= 18 ? '' : '需要 >= 18.17');

  const jv = javaVersion();
  line('Java', Boolean(jv), jv?.raw ?? '未找到 java');
  if (jv) {
    const ok = jv.major >= REQUIRED_JAVA;
    console.log(`  ${ok ? 'ok  ' : 'warn'} JDK ${jv.major} ${ok ? '满足 Stirling 3.3.0' : `低于要求的 ${REQUIRED_JAVA}，Stirling 通道不可运行`}`);
  }

  console.log('\n源码与产物');
  line('engines/stirling-pdf', await exists(resolve(STIRLING, 'build.gradle')));
  line('engines/pdfpatcher', await exists(resolve(STIRLING, '..', 'pdfpatcher', 'PDFPatcher.sln')));
  line('engines/toolknit-desktop', await exists(resolve(STIRLING, '..', 'toolknit-desktop', 'README.md')));
  line('capabilities.json', await exists(resolve(ROOT, 'integration', 'registry', 'capabilities.json')));
  line('gateway 依赖为零', true, 'package.json 无 dependencies');

  const jar = await findStirlingJar();
  line('Stirling 构建产物', Boolean(jar), jar ?? '未构建，先看 docs/build.md');

  const cli = resolve(ROOT, 'integration', 'patcher-cli', 'bin', 'PDFPatcher.Cli.exe');
  line('PDFPatcher CLI', await exists(cli), cli);

  console.log('\n结论');
  if (jv && jv.major < REQUIRED_JAVA) {
    console.log('  Stirling 需要 JDK 25：安装后设置 JAVA_HOME 再运行 npm run start:all');
    console.log('  仅验证网关：node scripts/dev.mjs gateway（native 17 项能力不受影响）');
  } else if (!jar) {
    console.log('  先构建 Stirling：cd engines/stirling-pdf && ./gradlew :app:core:bootJar');
  } else {
    console.log('  可运行：node scripts/dev.mjs all');
  }
  return bad;
}

function run(name, cmd, args, opts = {}) {
  const child = spawn(cmd, args, { stdio: 'inherit', ...opts });
  child.on('exit', code => console.log(`[${name}] 退出 ${code}`));
  procs.push(child);
  return child;
}

async function waitPort(url, timeoutMs = 180000, label = url) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const res = await fetch(url, { signal: AbortSignal.timeout(2000) });
      if (res.status < 500) return true;
    } catch {
      /* 还没起来 */
    }
    await sleep(1500);
  }
  console.log(`[timeout] ${label}`);
  return false;
}

async function main() {
  const mode = process.argv[2] ?? 'gateway';

  if (mode === '--check' || mode === 'check') {
    const bad = await check();
    process.exit(bad > 0 ? 1 : 0);
  }

  if (mode === 'gateway') {
    run('gateway', process.execPath, [resolve(ROOT, 'integration', 'gateway', 'server.mjs')]);
    console.log(`网关 http://127.0.0.1:${GW_PORT}`);
    return;
  }

  if (mode === 'all') {
    const jv = javaVersion();
    if (!jv || jv.major < REQUIRED_JAVA) {
      console.error(`Stirling 需要 JDK ${REQUIRED_JAVA}，当前 ${jv ? jv.raw : '无 Java'}`);
      console.error('仅启动网关：node scripts/dev.mjs gateway');
      process.exit(2);
    }
    const jar = await findStirlingJar();
    if (!jar) {
      console.error('未找到 Stirling 构建产物，先执行：cd engines/stirling-pdf && ./gradlew :app:core:bootJar');
      process.exit(2);
    }
    run('stirling', 'java', ['-jar', jar], { cwd: STIRLING });
    console.log('等待 Stirling 就绪（首次启动约 30–60 秒）…');
    const up = await waitPort('http://127.0.0.1:8080/api/v1/info/status');
    if (!up) console.log('Stirling 未在超时内就绪，网关仍会启动并在 /healthz 报告 down');
    run('gateway', process.execPath, [resolve(ROOT, 'integration', 'gateway', 'server.mjs')]);
    console.log(`网关 http://127.0.0.1:${GW_PORT}`);
    return;
  }

  console.error('用法: node scripts/dev.mjs [--check|gateway|all]');
  process.exit(1);
}

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    procs.forEach(p => p.kill());
    process.exit(0);
  });
}

main().catch(err => {
  console.error(err);
  procs.forEach(p => p.kill());
  process.exit(1);
});
