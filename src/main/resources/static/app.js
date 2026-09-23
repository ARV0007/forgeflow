/* ForgeFlow workbench.
   Plain JS on purpose: ForgeFlow generates static sites, and its own UI is one.
   No build step, no bundler, one jar that serves both the API and this page. */

const API = '';
let token = localStorage.getItem('ff_token') || null;
let email = localStorage.getItem('ff_email') || null;
let projectId = null;
let busy = false;

const $ = (id) => document.getElementById(id);

// ── http ────────────────────────────────────────────────

async function api(path, { method = 'GET', body } = {}) {
  const res = await fetch(API + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : {})
    },
    body: body ? JSON.stringify(body) : undefined
  });

  if (res.status === 401) { signOut(); throw new Error('Session expired. Sign in again.'); }

  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(data?.detail || data?.message || `Request failed (${res.status})`);
  return data;
}

// ── sign in ─────────────────────────────────────────────

// Plain click handlers. No <form>, so nothing can submit the page out from
// under an in-flight fetch - which is exactly what used to happen.
$('btn-login').addEventListener('click', () => authenticate('/api/v1/auth/login'));
$('btn-signup').addEventListener('click', () => authenticate('/api/v1/auth/signup'));

async function authenticate(path) {
  const msg = $('gate-msg');
  msg.className = 'msg';

  const mail = $('email').value.trim();
  const pass = $('password').value;

  // Say what is wrong HERE rather than letting the browser silently refuse.
  if (!mail || !pass) {
    msg.textContent = 'Enter an email and a password.';
    return;
  }

  msg.textContent = 'Signing in\u2026';
  try {
    const out = await api(path, { method: 'POST', body: { email: mail, password: pass } });
    token = out.token;
    email = out.email;
    localStorage.setItem('ff_token', token);
    localStorage.setItem('ff_email', email);
    await openWorkbench();
  } catch (err) {
    msg.textContent = err.message;
  }
}

function signOut() {
  token = email = projectId = null;
  localStorage.removeItem('ff_token');
  localStorage.removeItem('ff_email');
  $('shell').hidden = true;
  $('gate').hidden = false;
}
$('btn-signout').addEventListener('click', signOut);

// ── projects ────────────────────────────────────────────

async function openWorkbench() {
  document.getElementById('gate').style.display = 'none';
  $('gate').hidden = true;
  $('shell').hidden = false;
  $('whoami').textContent = email || '';
  await loadProjects();
}

async function loadProjects() {
  const projects = await api('/api/v1/projects');
  const select = $('project-select');
  select.innerHTML = '';

  if (projects.length === 0) {
    await createProject('My first app');
    return;
  }
  for (const p of projects) {
    const opt = document.createElement('option');
    opt.value = p.id;
    opt.textContent = p.name;
    select.appendChild(opt);
  }
  projectId = projects[0].id;
  select.value = projectId;
  await loadFiles();
}

$('project-select').addEventListener('change', async (e) => {
  projectId = Number(e.target.value);
  resetRecord();
  await loadFiles();
});

$('btn-new-project').addEventListener('click', async () => {
  const name = prompt('Name this project');
  if (name) await createProject(name);
});

async function createProject(name) {
  const p = await api('/api/v1/projects', { method: 'POST', body: { name } });
  projectId = p.id;
  const select = $('project-select');
  const opt = document.createElement('option');
  opt.value = p.id;
  opt.textContent = p.name;
  select.appendChild(opt);
  select.value = p.id;
  resetRecord();
  await loadFiles();
}

// ── files ───────────────────────────────────────────────

async function loadFiles() {
  const files = await api(`/api/v1/projects/${projectId}/files`);
  const list = $('file-list');
  list.innerHTML = '';

  if (files.length === 0) {
    list.innerHTML = '<li class="empty" style="cursor:default"><span>No files yet.</span></li>';
    return;
  }
  for (const f of files) {
    const li = document.createElement('li');
    li.innerHTML = `<span class="fname"></span>
                    <span class="fsize"></span>
                    <span class="fver"></span>`;
    li.querySelector('.fname').textContent = f.path;
    li.querySelector('.fsize').textContent = f.sizeBytes + ' B';
    li.querySelector('.fver').textContent = 'v' + f.version;
    li.addEventListener('click', () => showCode(f.path));
    list.appendChild(li);
  }
}

async function showCode(path) {
  const file = await api(`/api/v1/projects/${projectId}/files/content?path=${encodeURIComponent(path)}`);
  $('code-name').textContent = file.path;
  $('code-body').textContent = file.content;
  switchTab('code');
}

// ── tabs ────────────────────────────────────────────────

document.querySelectorAll('.tab').forEach((tab) =>
  tab.addEventListener('click', () => switchTab(tab.dataset.tab)));

function switchTab(name) {
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('is-on', t.dataset.tab === name));
  document.querySelectorAll('.tabpanel').forEach((p) => p.classList.toggle('is-on', p.id === 'tab-' + name));
}

// ── the run record ──────────────────────────────────────

const record = $('record');

function resetRecord() {
  record.innerHTML = '';
  $('run-state').textContent = '';
}

function line(cls, html) {
  const div = document.createElement('div');
  div.className = cls;
  div.innerHTML = html;
  record.appendChild(div);
  record.scrollTop = record.scrollHeight;
  return div;
}

function esc(s) {
  return String(s ?? '').replace(/[&<>"]/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function render(ev) {
  switch (ev.type) {
    case 'status':
      if (ev.message === 'Building') line('round', 'inspection&hellip;');
      break;

    case 'thinking':
      line('round', esc(ev.message.toLowerCase()));
      break;

    case 'tool':
      line('step', `<span class="what">${esc(ev.message)}</span>` +
                   (ev.path ? ` <span class="path">${esc(ev.path)}</span>` : ''));
      break;

    case 'file':
      line('step', `<span class="what">wrote</span> <span class="path">${esc(ev.path)}</span>` +
                   ` <span class="size">${esc(ev.data)} B</span>`);
      break;

    case 'tool_failed':
      line('step is-bad', `<span class="what">rejected</span> <span class="path">${esc(ev.message)}</span>`);
      break;

    case 'build': {
      const passed = ev.message === 'Build passed';
      const el = line('verdict ' + (passed ? 'passed' : 'failed'),
        `<div class="verdict-head">${passed ? 'Build passed' : 'Build failed'}</div>` +
        `<div class="verdict-body">${esc(ev.data)}</div>`);
      el.scrollIntoView({ block: 'nearest' });
      break;
    }

    case 'repair':
      line('repair', esc(ev.message.toLowerCase()) + ' &mdash; the agent has to fix this before it can finish');
      break;

    case 'error':
      line('verdict failed', `<div class="verdict-head">Run failed</div>` +
                             `<div class="verdict-body">${esc(ev.message)}</div>`);
      break;

    case 'done':
      summarise(ev.data);
      break;
  }
}

function summarise(r) {
  const el = document.createElement('div');
  el.className = 'summary';
  el.innerHTML =
    (r.summary ? `<div class="summary-say">${esc(r.summary)}</div>` : '') +
    `<span><b>${esc(r.status)}</b> ${esc(r.stopReason)}</span>` +
    `<span>${esc(r.filesWritten.length)} file(s)</span>` +
    `<span>${esc(r.toolCalls)} tool calls</span>` +
    `<span>${esc(r.repairRounds)} repair round(s)</span>` +
    `<span>${esc(r.totalTokens)} tokens</span>` +
    `<span>${(r.durationMs / 1000).toFixed(1)}s</span>`;
  record.appendChild(el);
  record.scrollTop = record.scrollHeight;
}

// ── generate (SSE over fetch) ───────────────────────────
// EventSource cannot send an Authorization header, so the stream is read
// straight off the response body and the SSE frames parsed by hand.

$('btn-send').addEventListener('click', () => generate());

document.querySelectorAll('.chip').forEach((chip) =>
  chip.addEventListener('click', () => { $('prompt').value = chip.textContent; generate(); }));

async function generate() {
  const prompt = $('prompt').value.trim();
  if (!prompt || busy) return;

  setBusy(true);
  if (record.querySelector('.empty')) record.innerHTML = '';
  line('round', `&gt; ${esc(prompt)}`);
  $('prompt').value = '';

  try {
    const res = await fetch(`/api/v1/projects/${projectId}/generate/stream`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
      body: JSON.stringify({ prompt })
    });
    if (!res.ok) throw new Error(`The server refused the run (${res.status})`);

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });

      const frames = buffer.split('\n\n');
      buffer = frames.pop();
      for (const frame of frames) {
        const dataLine = frame.split('\n').find((l) => l.startsWith('data:'));
        if (!dataLine) continue;
        try { render(JSON.parse(dataLine.slice(5))); } catch { /* partial frame */ }
      }
    }
    await loadFiles();
  } catch (err) {
    // The server closing a finished stream is not a failure. If the run
    // already reported 'done', there is nothing to warn about.
    if (!record.querySelector('.summary')) {
      line('verdict failed', `<div class="verdict-head">Run failed</div>` +
                             `<div class="verdict-body">${esc(err.message)}</div>`);
    }
  } finally {
    setBusy(false);
  }
}

function setBusy(on) {
  busy = on;
  $('btn-send').disabled = on;
  $('btn-send').textContent = on ? 'Building' : 'Build';
  $('run-state').textContent = on ? 'working' : '';
  $('run-state').classList.toggle('working', on);
}

// ── build and preview ───────────────────────────────────

$('btn-build').addEventListener('click', async () => {
  try {
    const r = await api(`/api/v1/projects/${projectId}/build`, { method: 'POST' });
    line('verdict ' + (r.passed ? 'passed' : 'failed'),
      `<div class="verdict-head">${r.passed ? 'Build passed' : 'Build failed'}</div>` +
      `<div class="verdict-body">${esc(r.output)}</div>`);
  } catch (err) {
    line('verdict failed', `<div class="verdict-head">Build failed</div>` +
                           `<div class="verdict-body">${esc(err.message)}</div>`);
  }
});

$('btn-preview').addEventListener('click', async () => {
  switchTab('preview');
  try {
    const p = await api(`/api/v1/projects/${projectId}/preview`, { method: 'POST' });
    const frame = $('preview-frame');
    frame.src = p.url;
    frame.hidden = false;
    $('preview-empty').hidden = true;
  } catch (err) {
    $('preview-empty').hidden = false;
    $('preview-empty').innerHTML =
      `<p>Preview did not start.</p><p class="empty-sub">${esc(err.message)}</p>` +
      `<p class="empty-sub">If the project has no files yet, build something first.</p>`;
  }
});

// ── boot ────────────────────────────────────────────────

if (token) {
  openWorkbench().catch(signOut);
}

// The gate is absolutely positioned over the shell; hidden alone is not enough.
const _ffHide = () => { const g = document.getElementById("gate"); if (g && !document.getElementById("shell").hidden) g.style.display = "none"; };
setInterval(_ffHide, 200);
