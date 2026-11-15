/**
 * 作业存储：内存表 + 磁盘产物。
 * 每个作业独占一个目录，产物只能通过本网关读取，避免任意路径访问。
 */
import { mkdir, readdir, rm, stat } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { PROJECT_ROOT } from './registry.mjs';

export const JOBS_ROOT = process.env.PDF_JOBS_ROOT ?? resolve(PROJECT_ROOT, '.work', 'jobs');
const MAX_JOBS = 200;

const jobs = new Map();

export async function createJob({ capability, files, options }) {
  const id = randomUUID();
  const dir = resolve(JOBS_ROOT, id);
  await mkdir(dir, { recursive: true });
  const job = {
    id,
    capabilityId: capability.id,
    engine: capability.engine,
    status: 'queued',       // queued | running | done | failed | cancelled
    createdAt: new Date().toISOString(),
    startedAt: null,
    finishedAt: null,
    inputFiles: files,
    options,
    dir,
    outputs: [],
    error: null,
    log: [],
  };
  jobs.set(id, job);
  trim();
  return job;
}

export function getJob(id) {
  return jobs.get(id) ?? null;
}

export function listJobs() {
  return [...jobs.values()].sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
}

export function appendLog(job, line) {
  job.log.push({ at: new Date().toISOString(), line: String(line).slice(0, 2000) });
  if (job.log.length > 200) job.log.shift();
}

export async function collectOutputs(job) {
  try {
    const names = await readdir(job.dir);
    const outputs = [];
    for (const name of names) {
      if (name.startsWith('input-')) continue;
      const p = resolve(job.dir, name);
      const s = await stat(p);
      if (s.isFile()) {
        outputs.push({ name, bytes: s.size, path: p });
      }
    }
    job.outputs = outputs;
  } catch {
    job.outputs = [];
  }
  return job.outputs;
}

function trim() {
  if (jobs.size <= MAX_JOBS) return;
  const sorted = [...jobs.values()].sort((a, b) => (a.createdAt < b.createdAt ? -1 : 1));
  for (const old of sorted.slice(0, jobs.size - MAX_JOBS)) {
    jobs.delete(old.id);
    rm(old.dir, { recursive: true, force: true }).catch(() => {});
  }
}

/** 对外可见的作业视图，去掉磁盘绝对路径。 */
export function publicJob(job) {
  if (!job) return null;
  return {
    id: job.id,
    capabilityId: job.capabilityId,
    engine: job.engine,
    status: job.status,
    createdAt: job.createdAt,
    startedAt: job.startedAt,
    finishedAt: job.finishedAt,
    outputs: job.outputs.map(o => ({ name: o.name, bytes: o.bytes, url: `/api/jobs/${job.id}/files/${encodeURIComponent(o.name)}` })),
    error: job.error,
    log: job.log.slice(-30),
  };
}
