/**
 * PDF 工作台前端：消费本机网关（integration/gateway）。
 * 无构建、无依赖，直接静态打开即可（网关已放行 localhost 与 file:// 的跨源请求）。
 */
const params = new URLSearchParams(location.search);
const API = (params.get('gateway') || 'http://127.0.0.1:8931').replace(/\/$/, '');

const el = id => document.getElementById(id);
const ui = {
  engines: el('engines'),
  search: el('search'),
  stats: el('stats'),
  cats: el('cats'),
  grid: el('grid'),
  drawer: el('drawer'),
  scrim: el('scrim'),
  title: el('drawerTitle'),
  desc: el('drawerDesc'),
  files: el('files'),
  filesHint: el('filesHint'),
  fileList: el('fileList'),
  options: el('options'),
  runBtn: el('runBtn'),
  resetBtn: el('resetBtn'),
  job: el('job'),
  jobState: el('jobState'),
  jobId: el('jobId'),
  logs: el('logs'),
  outputs: el('outputs'),
};

const state = {
  categories: {},
  activeCat: 'all',
  query: '',
  current: null,
  picked: [],
  polling: null,
};

const ENGINE_LABEL = { stirling: 'Stirling', patcher: 'PDFPatcher', native: '内置' };

/* ---------------------------------- 数据 ---------------------------------- */

async function getJSON(path, init) {
  const res = await fetch(`${API}${path}`, init);
  const text = await res.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = { error: text.slice(0, 300) };
  }
  if (!res.ok) {
    const err = new Error(body?.error || `HTTP ${res.status}`);
    err.hint = body?.hint;
    err.status = res.status;
    throw err;
  }
  return body;
}

async function load() {
  try {
    const data = await getJSON('/api/capabilities');
    state.categories = data.categories;
    renderEngines(data.engines);
    renderStats(data);
    renderCats(data);
    renderGrid();
  } catch (err) {
    ui.stats.textContent = `无法连接网关：${err.message}`;
    ui.grid.replaceChildren(emptyBlock(
      `网关未启动或端口不是 8931。先运行 node scripts/dev.mjs gateway，再用 ?gateway=http://127.0.0.1:端口 打开本页。`
    ));
  }
}

/* --------------------------------- 渲染 --------------------------------- */

function renderEngines(engines) {
  ui.engines.replaceChildren();
  for (const [id, info] of Object.entries(engines ?? {})) {
    const wrap = document.createElement('div');
    wrap.className = 'engine';
    const dot = document.createElement('span');
    dot.className = `dot ${info.state}`;
    const label = document.createElement('span');
    label.textContent = ENGINE_LABEL[id] ?? id;
    const hint = document.createElement('span');
    hint.className = 'muted';
    hint.textContent = info.state === 'up'
      ? (info.version ? `v${info.version}` : '就绪')
      : (info.state === 'down' ? '未就绪' : info.state);
    if (info.detail) wrap.title = info.detail;
    wrap.append(dot, label, hint);
    ui.engines.append(wrap);
  }
}

function renderStats(data) {
  ui.stats.textContent = `共 ${data.total} 项能力 · 当前可用 ${data.available} 项`;
}

function allItems() {
  return Object.entries(state.categories).flatMap(([cat, group]) =>
    group.items.map(item => ({ ...item, category: cat, categoryLabel: group.label }))
  );
}

function renderCats(data) {
  const items = allItems();
  const catRows = [['all', '全部', items.length]];
  for (const [key, group] of Object.entries(data.categories)) {
    catRows.push([key, group.label, group.items.length]);
  }

  ui.cats.replaceChildren();
  for (const [key, label, count] of catRows) {
    const btn = document.createElement('button');
    btn.className = `cat${state.activeCat === key ? ' active' : ''}`;
    btn.type = 'button';
    const name = document.createElement('span');
    name.textContent = label;
    const num = document.createElement('span');
    num.className = 'count';
    num.textContent = String(count);
    btn.append(name, num);
    btn.addEventListener('click', () => {
      state.activeCat = key;
      renderCats(data);
      renderGrid();
    });
    ui.cats.append(btn);
  }
}

function matches(item, q) {
  if (!q) return true;
  const hay = `${item.title} ${item.desc ?? ''} ${item.id} ${item.categoryLabel ?? ''}`.toLowerCase();
  return q.split(/\s+/).filter(Boolean).every(tok => hay.includes(tok));
}

function renderGrid() {
  const q = state.query.trim().toLowerCase();
  const rows = allItems()
    .filter(item => state.activeCat === 'all' || item.category === state.activeCat)
    .filter(item => matches(item, q));

  ui.grid.replaceChildren();
  if (rows.length === 0) {
    ui.grid.append(emptyBlock('没有匹配的能力，换个关键词试试'));
    return;
  }
  for (const item of rows) ui.grid.append(card(item));
}

function card(item) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'card';
  btn.dataset.disabled = String(!item.available);

  const h = document.createElement('h3');
  h.textContent = item.title || item.id;

  const p = document.createElement('p');
  p.textContent = item.desc || (item.available ? '' : item.reason || '当前不可用');

  const tags = document.createElement('div');
  tags.className = 'tags';
  tags.append(tag(ENGINE_LABEL[item.engine] ?? item.engine, item.engine));
  if (item.output) tags.append(tag(item.output.toUpperCase(), ''));
  if (item.unique) tags.append(tag('独有', ''));
  if (!item.available) tags.append(tag('不可用', 'off'));

  btn.append(h, p, tags);
  btn.addEventListener('click', () => openDrawer(item));
  return btn;
}

function tag(text, cls) {
  const s = document.createElement('span');
  s.className = `tag ${cls}`.trim();
  s.textContent = text;
  return s;
}

function emptyBlock(text) {
  const div = document.createElement('div');
  div.className = 'empty';
  div.textContent = text;
  return div;
}

/* --------------------------------- 抽屉 --------------------------------- */

function openDrawer(item) {
  state.current = item;
  state.picked = [];
  ui.title.textContent = item.title;
  ui.desc.textContent = item.desc || item.id;
  ui.files.value = '';
  ui.fileList.replaceChildren();
  ui.logs.replaceChildren();
  ui.outputs.replaceChildren();
  ui.job.hidden = true;
  ui.runBtn.disabled = false;
  ui.runBtn.textContent = item.engine === 'native' ? '在桌面端打开' : '开始处理';

  const required = (item.files ?? []).filter(f => f.required);
  const fieldHint = required.length
    ? `需要上传：${required.map(f => f.field + (f.multiple ? '（可多选）' : '（单个）')).join('、')}`
    : '该能力由客户端本地处理，无需上传';
  ui.filesHint.textContent = fieldHint;
  ui.files.disabled = !item.available || item.engine === 'native';

  renderOptions(item);
  ui.drawer.classList.add('open');
  ui.drawer.setAttribute('aria-hidden', 'false');
  ui.scrim.hidden = false;

  if (!item.available) {
    ui.job.hidden = false;
    ui.jobState.textContent = '不可用';
    ui.jobState.className = 'badge failed';
    ui.jobId.textContent = '';
    addLog(item.reason ?? '');
    if (item.fallback) addLog(`建议改用：${item.fallback}`);
    if (item.engine === 'patcher') addLog('构建方式见 docs/build.md 的「PDFPatcher 通道」');
  }
}

function closeDrawer() {
  ui.drawer.classList.remove('open');
  ui.drawer.setAttribute('aria-hidden', 'true');
  ui.scrim.hidden = true;
  if (state.polling) {
    clearTimeout(state.polling);
    state.polling = null;
  }
}

function renderOptions(item) {
  ui.options.replaceChildren();
  for (const opt of item.options ?? []) {
    const wrap = document.createElement('div');
    wrap.className = 'opt';
    const label = document.createElement('label');
    label.textContent = opt.desc ? `${opt.name}（${opt.desc}）` : opt.name;
    const id = `opt-${opt.name}`;
    label.htmlFor = id;

    let input;
    if (opt.type === 'enum' && Array.isArray(opt.values)) {
      input = document.createElement('select');
      for (const v of opt.values) {
        const o = document.createElement('option');
        o.value = String(v);
        o.textContent = String(v);
        input.append(o);
      }
      if (opt.default !== undefined) input.value = String(opt.default);
    } else if (opt.type === 'bool') {
      input = document.createElement('select');
      for (const v of ['true', 'false']) {
        const o = document.createElement('option');
        o.value = v;
        o.textContent = v === 'true' ? '是' : '否';
        input.append(o);
      }
      input.value = String(opt.default ?? false);
    } else {
      input = document.createElement('input');
      input.type = opt.type === 'int' ? 'number' : opt.type === 'password' ? 'password' : 'text';
      if (opt.default !== undefined) input.value = String(opt.default);
    }

    input.id = id;
    input.dataset.optName = opt.name;
    input.dataset.optType = opt.type ?? 'string';
    input.className = 'opt-input';
    wrap.append(label, input);
    ui.options.append(wrap);
  }
}

function collectOptions() {
  const out = {};
  for (const input of ui.options.querySelectorAll('.opt-input')) {
    const name = input.dataset.optName;
    const type = input.dataset.optType;
    const raw = input.value;
    if (raw === '') continue;
    out[name] = type === 'int' ? Number(raw) : type === 'bool' ? raw === 'true' : raw;
  }
  return out;
}

/* --------------------------------- 执行 --------------------------------- */

ui.files.addEventListener('change', () => {
  state.picked = [...ui.files.files];
  ui.fileList.replaceChildren();
  for (const f of state.picked) {
    const li = document.createElement('li');
    const n = document.createElement('span');
    n.textContent = f.name;
    const s = document.createElement('span');
    s.className = 'muted';
    s.textContent = `${(f.size / 1024).toFixed(0)} KB`;
    li.append(n, s);
    ui.fileList.append(li);
  }
});

ui.runBtn.addEventListener('click', async () => {
  const item = state.current;
  if (!item) return;

  if (item.engine === 'native') {
    if (window.__TAURI__) {
      addLog(`调用桌面端工具 ${item.toolId}`);
      await window.__TAURI__?.core?.invoke('open_toolknit_tool', { toolId: item.toolId }).catch(e => addLog(String(e)));
    } else {
      ui.job.hidden = false;
      ui.jobState.textContent = '提示';
      ui.jobState.className = 'badge';
      addLog(`该能力是 ToolKnit 内置页面（toolId=${item.toolId}），在桌面端启动后会自动打开。`);
      addLog('当前是浏览器环境，请改用 Stirling 通道的等价能力，或直接启动桌面端。');
    }
    return;
  }

  if (!state.picked.length) {
    ui.job.hidden = false;
    ui.jobState.textContent = '缺少文件';
    ui.jobState.className = 'badge failed';
    addLog('先选择输入文件');
    return;
  }

  ui.runBtn.disabled = true;
  ui.runBtn.textContent = '上传中…';
  ui.job.hidden = false;
  ui.logs.replaceChildren();
  ui.outputs.replaceChildren();
  setJobState('running', '上传');

  try {
    const form = new FormData();
    for (const f of state.picked) form.append('file', f, f.name);
    const up = await getJSON('/api/upload', { method: 'POST', body: form });
    addLog(`已上传 ${up.files.length} 个文件`);
    ui.runBtn.textContent = '处理中…';

    const run = await getJSON('/api/run', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        capabilityId: item.id,
        files: up.files.map(f => f.path),
        options: collectOptions(),
      }),
    });
    ui.jobId.textContent = run.jobId;
    addLog(`任务已提交 ${run.jobId}`);
    poll(run.jobId);
  } catch (err) {
    setJobState('failed', '失败');
    addLog(err.message);
    if (err.hint) addLog(err.hint);
    ui.runBtn.disabled = false;
    ui.runBtn.textContent = '开始处理';
  }
});

ui.resetBtn.addEventListener('click', () => {
  state.picked = [];
  ui.files.value = '';
  ui.fileList.replaceChildren();
  ui.logs.replaceChildren();
  ui.outputs.replaceChildren();
  ui.job.hidden = true;
  ui.runBtn.disabled = false;
  ui.runBtn.textContent = state.current?.engine === 'native' ? '在桌面端打开' : '开始处理';
  if (state.polling) {
    clearTimeout(state.polling);
    state.polling = null;
  }
  for (const input of ui.options.querySelectorAll('.opt-input')) {
    if (input.tagName === 'SELECT') input.selectedIndex = 0;
    else input.value = '';
  }
});

function setJobState(cls, text) {
  ui.jobState.className = `badge ${cls}`;
  ui.jobState.textContent = text;
}

function addLog(line) {
  const li = document.createElement('li');
  li.textContent = line;
  ui.logs.append(li);
  ui.logs.scrollTop = ui.logs.scrollHeight;
}

async function poll(jobId) {
  try {
    const job = await getJSON(`/api/jobs/${jobId}`);
    const seen = new Set([...ui.logs.children].map(n => n.textContent));
    for (const entry of job.log ?? []) {
      if (!seen.has(entry.line)) addLog(entry.line);
    }
    setJobState(job.status, { queued: '排队', running: '处理中', done: '完成', failed: '失败', cancelled: '已取消' }[job.status] ?? job.status);

    if (job.status === 'done') {
      ui.outputs.replaceChildren();
      for (const out of job.outputs ?? []) {
        const a = document.createElement('a');
        a.href = `${API}${out.url}`;
        a.download = out.name;
        const n = document.createElement('span');
        n.textContent = out.name;
        const s = document.createElement('span');
        s.textContent = `${(out.bytes / 1024).toFixed(0)} KB`;
        a.append(n, s);
        ui.outputs.append(a);
      }
      ui.runBtn.disabled = false;
      ui.runBtn.textContent = '再处理一次';
      state.polling = null;
      return;
    }
    if (job.status === 'failed' || job.status === 'cancelled') {
      if (job.error) addLog(`错误：${job.error}`);
      ui.runBtn.disabled = false;
      ui.runBtn.textContent = '重试';
      state.polling = null;
      return;
    }
    state.polling = setTimeout(() => poll(jobId), 700);
  } catch (err) {
    addLog(`轮询失败：${err.message}`);
    ui.runBtn.disabled = false;
    ui.runBtn.textContent = '重试';
    state.polling = null;
  }
}

/* --------------------------------- 交互 --------------------------------- */

ui.search.addEventListener('input', () => {
  state.query = ui.search.value;
  renderGrid();
});

el('drawerClose').addEventListener('click', closeDrawer);
ui.scrim.addEventListener('click', closeDrawer);
document.addEventListener('keydown', e => {
  if (e.key === 'Escape') closeDrawer();
});

load();
