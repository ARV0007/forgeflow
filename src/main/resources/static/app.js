/* ForgeFlow workbench.
   Plain JS on purpose: ForgeFlow generates static sites, and its own UI is one.
   No build step, no bundler, one jar that serves both the API and this page.

   Layout: chats on the left (sessions + the conversation), the artifact on
   the right (preview, code, live logs, search). Everything here is a client
   of the documented API - /docs.html lists every call this file makes. */

const state = {
  token: localStorage.getItem('ff_token'),
  email: localStorage.getItem('ff_email'),
  projectId: null,
  role: null,           // OWNER | EDITOR | VIEWER | PUBLIC
  sessionId: null,
  busy: false,
  logs: null,           // AbortController for the live log stream
  lastLogSeq: 0,
};

const $ = (id) => document.getElementById(id);
const canWrite = () => state.role === 'OWNER' || state.role === 'EDITOR';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// ── http ────────────────────────────────────────────────

class ApiError extends Error {
  constructor(status, body) {
    super(body?.detail || body?.message || `Request failed (${status})`);
    this.status = status;
    this.body = body || {};
  }
}

async function api(path, { method = 'GET', body } = {}) {
  const res = await fetch(path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      ...(state.token ? { Authorization: 'Bearer ' + state.token } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  adoptRenewedToken(res);
  if (res.status === 401 && state.token) {
    signOut();
    throw new ApiError(401, { detail: 'Your session expired. Sign in again.' });
  }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = { detail: text }; }
  if (!res.ok) throw explain(new ApiError(res.status, data));
  return data;
}

/** Turn the two "it's not you, it's your limits" statuses into something actionable. */
function explain(err) {
  if (err.status === 402) {
    err.message = (err.body.detail || 'Plan limit reached.');
    err.upgrade = true;
  } else if (err.status === 429) {
    const s = err.body.retryAfterSeconds;
    err.message = `Slow down a little${s ? ` - try again in ${s}s` : ''}.`;
  }
  return err;
}

/**
 * Read a Server-Sent Events response off fetch(). EventSource can't send an
 * Authorization header, so frames are parsed by hand: "event:", "id:" and
 * "data:" lines, separated by a blank line.
 */
async function sse(path, { method = 'POST', body, signal, headers = {}, onOpen }, onEvent) {
  const res = await fetch(path, {
    method,
    signal,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      Authorization: 'Bearer ' + state.token,
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  adoptRenewedToken(res);
  if (!res.ok) {
    let data = null;
    try { data = await res.json(); } catch { /* not JSON */ }
    throw explain(new ApiError(res.status, data));
  }
  if (onOpen) onOpen();
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const frames = buffer.split('\n\n');
    buffer = frames.pop();
    for (const frame of frames) {
      let event = 'message', id = null, data = '';
      for (const l of frame.split('\n')) {
        if (l.startsWith('event:')) event = l.slice(6).trim();
        else if (l.startsWith('id:')) id = l.slice(3).trim();
        else if (l.startsWith('data:')) data += l.slice(5);
      }
      if (!data) continue;
      try { onEvent(event, JSON.parse(data), id); } catch (e) { console.warn('bad SSE frame', e); }
    }
  }
}

/** Sliding session: the server hands back a fresh token as ours ages. */
function adoptRenewedToken(res) {
  const fresh = res.headers.get('X-Auth-Token');
  if (fresh && state.token) {
    state.token = fresh;
    localStorage.setItem('ff_token', fresh);
  }
}

function toast(text, { upgrade = false } = {}) {
  const t = $('toast');
  t.innerHTML = esc(text) + (upgrade ? ' <button class="linklike" id="toast-upgrade">See plans</button>' : '');
  t.hidden = false;
  if (upgrade) $('toast-upgrade').onclick = () => { t.hidden = true; openPlans(); };
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.hidden = true; }, upgrade ? 9000 : 4500);
}

// ── sign in ─────────────────────────────────────────────

$('btn-login').addEventListener('click', () => authenticate('/api/v1/auth/login'));
$('btn-signup').addEventListener('click', () => authenticate('/api/v1/auth/signup'));
$('password').addEventListener('keydown', (e) => { if (e.key === 'Enter') authenticate('/api/v1/auth/login'); });

async function authenticate(path) {
  const msg = $('gate-msg');
  const mail = $('email').value.trim();
  const pass = $('password').value;
  if (!mail || !pass) { msg.textContent = 'Enter an email and a password.'; return; }
  msg.textContent = 'Signing in…';
  try {
    const out = await api(path, { method: 'POST', body: { email: mail, password: pass } });
    state.token = out.token;
    state.email = out.email;
    localStorage.setItem('ff_token', state.token);
    localStorage.setItem('ff_email', state.email);
    msg.textContent = '';
    await openWorkbench();
  } catch (err) {
    msg.textContent = err.message;
  }
}

function signOut() {
  stopLogs();
  Object.assign(state, { token: null, email: null, projectId: null, sessionId: null, role: null });
  localStorage.removeItem('ff_token');
  localStorage.removeItem('ff_email');
  $('shell').hidden = true;
  $('gate').hidden = false;
}
$('btn-signout').addEventListener('click', signOut);

async function openWorkbench() {
  $('gate').hidden = true;
  $('shell').hidden = false;
  $('whoami').textContent = state.email || '';
  handleBillingReturn();
  refreshPlanPill();
  await loadProjects();
}

// ── projects ────────────────────────────────────────────

async function loadProjects(selectId) {
  const projects = await api('/api/v1/projects');
  const select = $('project-select');
  select.innerHTML = '';
  if (projects.length === 0) {
    try { await createProject('My first app'); } catch (err) { toast(err.message, { upgrade: err.upgrade }); }
    return;
  }
  for (const p of projects) {
    const opt = document.createElement('option');
    opt.value = p.id;
    opt.textContent = p.name + (p.role !== 'OWNER' ? `  (${p.role.toLowerCase()})` : '');
    opt.dataset.role = p.role;
    select.appendChild(opt);
  }
  const pick = projects.find((p) => p.id === selectId) || projects.find((p) => p.id === state.projectId) || projects[0];
  select.value = pick.id;
  await openProject(pick.id, pick.role);
}

$('project-select').addEventListener('change', (e) => {
  const opt = e.target.selectedOptions[0];
  openProject(Number(opt.value), opt.dataset.role);
});

async function openProject(id, role) {
  state.projectId = id;
  state.role = role;
  state.sessionId = null;
  $('role-badge').textContent = role ? role.toLowerCase() : '';
  $('role-badge').hidden = role === 'OWNER';
  applyRole();
  resetThread();
  $('code-name').textContent = 'Pick a file';
  $('code-body').textContent = '';
  $('search-results').innerHTML = '';
  startLogs();
  await Promise.all([loadSessions(), loadFiles(), loadPreview()]);
}

function applyRole() {
  const w = canWrite();
  $('prompt').disabled = !w;
  $('prompt').placeholder = w ? 'Describe what to build or change…' : 'You can view this project but not change it.';
  $('btn-send').disabled = !w || state.busy;
  $('btn-new-chat').disabled = !w;
  $('btn-build').disabled = !w;
  $('btn-preview').disabled = !w;
  $('btn-preview-stop').disabled = !w;
}

$('btn-new-project').addEventListener('click', () => {
  $('new-name').value = '';
  $('new-msg').textContent = '';
  $('dlg-new').showModal();
  $('new-name').focus();
});
$('btn-create').addEventListener('click', async () => {
  const name = $('new-name').value.trim();
  if (!name) { $('new-msg').textContent = 'Give it a name.'; return; }
  try {
    await createProject(name);
    $('dlg-new').close();
  } catch (err) {
    $('new-msg').innerHTML = esc(err.message) +
      (err.upgrade ? ' <button class="linklike" onclick="openPlans()">See plans</button>' : '');
  }
});
$('new-name').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('btn-create').click(); });

async function createProject(name) {
  const p = await api('/api/v1/projects', { method: 'POST', body: { name } });
  refreshPlanPill();
  await loadProjects(p.id);
}

// ── chat sessions ───────────────────────────────────────

async function loadSessions(selectId) {
  const sessions = await api(`/api/v1/projects/${state.projectId}/chat/sessions`);
  const list = $('session-list');
  list.innerHTML = '';
  if (sessions.length === 0) {
    list.innerHTML = '<li class="session-empty">No chats yet</li>';
    state.sessionId = null;
    return;
  }
  for (const s of sessions) {
    const li = document.createElement('li');
    li.className = 'session';
    li.dataset.id = s.id;
    li.innerHTML = `<span class="session-title"></span>`;
    li.querySelector('.session-title').textContent = s.title || 'New chat';
    li.addEventListener('click', () => openSession(s.id));
    list.appendChild(li);
  }
  const target = selectId || state.sessionId || sessions[0].id;
  await openSession(target);
}

function markActiveSession() {
  document.querySelectorAll('.session').forEach((li) =>
    li.classList.toggle('is-on', Number(li.dataset.id) === state.sessionId));
}

async function openSession(id) {
  state.sessionId = id;
  markActiveSession();
  const messages = await api(`/api/v1/projects/${state.projectId}/chat/sessions/${id}/messages`);
  resetThread(messages.length > 0);
  for (const m of messages) appendMessage(m);
  updateRetry(messages);
}

$('btn-new-chat').addEventListener('click', async () => {
  try {
    const s = await api(`/api/v1/projects/${state.projectId}/chat/sessions`, { method: 'POST', body: {} });
    await loadSessions(s.id);
    $('prompt').focus();
  } catch (err) { toast(err.message); }
});

// ── the thread ──────────────────────────────────────────

const thread = $('thread');

function resetThread(hasMessages = false) {
  if (hasMessages) { thread.innerHTML = ''; return; }
  thread.innerHTML = `
    <div class="empty">
      <p>${canWrite() ? 'What should we build?' : 'Nothing in this chat yet.'}</p>
      <p class="empty-sub">Describe it in a sentence. The agent writes the files, runs a build,
         and fixes what fails before it says it's done.</p>
      ${canWrite() ? `<div class="chips">
        <button class="chip" type="button">A recipe page with three cards</button>
        <button class="chip" type="button">A countdown timer to new year</button>
        <button class="chip" type="button">A tip calculator</button></div>` : ''}
    </div>`;
  thread.querySelectorAll('.chip').forEach((chip) =>
    chip.addEventListener('click', () => { $('prompt').value = chip.textContent; send(); }));
  $('btn-retry').hidden = true;
}

function scrollThread() { thread.scrollTop = thread.scrollHeight; }

function appendMessage(m) {
  if (thread.querySelector('.empty')) thread.innerHTML = '';
  const el = document.createElement('div');
  if (m.role === 'user') {
    el.className = 'msg-user';
    el.textContent = m.content;
  } else {
    const failed = m.status && m.status !== 'SUCCEEDED';
    el.className = 'msg-bot' + (failed ? ' is-failed' : '');
    el.innerHTML = `<div class="bot-text"></div>` +
      (m.toolCalls?.length
        ? `<div class="bot-files">${m.toolCalls.map((t) =>
            `<button class="file-chip" data-path="${esc(t.path)}">${esc(t.path)}</button>`).join('')}</div>` : '') +
      `<div class="bot-meta">${failed ? '<b>failed</b> · ' : ''}${esc(m.tokensUsed || 0)} tokens</div>`;
    el.querySelector('.bot-text').textContent = m.content;
    el.querySelectorAll('.file-chip').forEach((b) => b.addEventListener('click', () => showCode(b.dataset.path)));
  }
  thread.appendChild(el);
  scrollThread();
  return el;
}

/** The reply being built right now: a live list of what the agent is doing. */
function pendingReply() {
  const el = document.createElement('div');
  el.className = 'msg-bot is-working';
  el.innerHTML = `<div class="work-head"><span class="spinner"></span><span class="work-now">Planning</span></div>
                  <ol class="work-steps"></ol>`;
  thread.appendChild(el);
  scrollThread();
  const steps = el.querySelector('.work-steps');
  const step = (cls, html) => {
    const li = document.createElement('li');
    li.className = cls;
    li.innerHTML = html;
    steps.appendChild(li);
    scrollThread();
  };
  return {
    el,
    event(ev) {
      switch (ev.type) {
        case 'status': el.querySelector('.work-now').textContent = ev.message; break;
        case 'thinking': el.querySelector('.work-now').textContent = 'Thinking'; break;
        case 'tool':
          if (ev.message !== 'write_file') step('st', `${esc(ev.message.replace('_', ' '))} <code>${esc(ev.path || '')}</code>`);
          break;
        case 'file': step('st st-file', `${String(ev.message).startsWith('Edited') ? 'edited' : 'wrote'} <code>${esc(ev.path)}</code>`); break;
        case 'tool_failed': step('st st-bad', `rejected: ${esc(ev.message)}`); break;
        case 'build':
          step(ev.message === 'Build passed' ? 'st st-pass' : 'st st-bad',
            ev.message === 'Build passed' ? 'build passed' : `build failed &mdash; <span class="why">${esc(String(ev.data || '').split('\n')[0])}</span>`);
          break;
        case 'repair': step('st st-repair', `${esc(ev.message.toLowerCase())} &mdash; fixing before it can finish`); break;
        case 'error': step('st st-bad', esc(ev.message)); break;
      }
    },
  };
}

function updateRetry(messages) {
  const last = messages[messages.length - 1];
  $('btn-retry').hidden = !(canWrite() && last && last.role === 'assistant' && last.status !== 'SUCCEEDED');
}

// ── sending ─────────────────────────────────────────────

$('btn-send').addEventListener('click', () => send());
$('prompt').addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); }
});
$('btn-retry').addEventListener('click', () => send({ retry: true }));

async function send({ retry = false } = {}) {
  const content = $('prompt').value.trim();
  if (state.busy || !canWrite() || (!retry && !content)) return;
  setBusy(true);

  try {
    if (!state.sessionId) {
      const s = await api(`/api/v1/projects/${state.projectId}/chat/sessions`, { method: 'POST', body: {} });
      state.sessionId = s.id;
    }
    const base = `/api/v1/projects/${state.projectId}/chat/sessions/${state.sessionId}`;
    if (!retry) {
      appendMessage({ role: 'user', content });
      $('prompt').value = '';
    }
    $('btn-retry').hidden = true;
    const pending = pendingReply();
    let turn = null;
    try {
      await sse(retry ? `${base}/retry/stream` : `${base}/messages/stream`,
        { body: retry ? undefined : { content } },
        (event, data) => { if (event === 'message') turn = data; else pending.event(data); });
    } finally {
      pending.el.remove();
    }
    if (turn) {
      appendMessage(turn.assistantMessage);
      updateRetry([turn.assistantMessage]);
    }
    await Promise.all([loadSessions(state.sessionId), loadFiles(), refreshPlanPill()]);
    reloadPreviewFrame();
  } catch (err) {
    // A refusal before the stream opened (402, 409, 429): the server saved
    // nothing, so put the text back where the user typed it.
    const bubbles = thread.querySelectorAll('.msg-user');
    if (!retry && err.status && bubbles.length) {
      bubbles[bubbles.length - 1].remove();
      $('prompt').value = content;
    }
    toast(err.message, { upgrade: err.upgrade });
    // A stream that broke mid-way (network, proxy) may still have finished
    // on the server: show what was actually saved rather than a gap.
    if (!err.status && state.sessionId) {
      try { await Promise.all([loadSessions(state.sessionId), loadFiles()]); } catch { /* keep the toast */ }
    }
  } finally {
    setBusy(false);
  }
}

function setBusy(on) {
  state.busy = on;
  $('btn-send').disabled = on || !canWrite();
  $('btn-send').textContent = on ? 'Working…' : 'Send';
}

// ── files ───────────────────────────────────────────────

async function loadFiles() {
  const files = await api(`/api/v1/projects/${state.projectId}/files`);
  const list = $('file-list');
  list.innerHTML = '';
  $('btn-download').disabled = files.length === 0;
  if (files.length === 0) {
    list.innerHTML = '<li class="file-empty">No files yet</li>';
    return;
  }
  for (const f of files) {
    const li = document.createElement('li');
    li.className = 'file';
    li.dataset.path = f.path;
    li.innerHTML = `<span class="fname"></span><span class="fmeta"></span>`;
    li.querySelector('.fname').textContent = f.path;
    li.querySelector('.fmeta').textContent = `${f.sizeBytes} B · v${f.version}`;
    li.addEventListener('click', () => showCode(f.path));
    list.appendChild(li);
  }
}

async function showCode(path, line) {
  switchTab('code');
  document.querySelectorAll('.file').forEach((li) => li.classList.toggle('is-on', li.dataset.path === path));
  try {
    const file = await api(`/api/v1/projects/${state.projectId}/files/content?path=${encodeURIComponent(path)}`);
    $('code-name').textContent = file.path;
    const body = $('code-body');
    body.innerHTML = file.content.split('\n')
      .map((l, i) => `<span class="ln${line && i + 1 >= line[0] && i + 1 <= line[1] ? ' hl' : ''}">${esc(l) || ' '}</span>`)
      .join('');      // each line is display:block - a newline between them would double-space
    const first = body.querySelector('.hl');
    if (first) first.scrollIntoView({ block: 'center' });
  } catch (err) {
    toast(err.message);
  }
}

$('btn-download').addEventListener('click', async () => {
  // A plain link can't carry the Authorization header, so fetch the zip and
  // hand the browser a blob URL to save.
  try {
    const res = await fetch(`/api/v1/projects/${state.projectId}/files/download`,
      { headers: { Authorization: 'Bearer ' + state.token } });
    if (!res.ok) throw new Error(`Download failed (${res.status})`);
    const name = /filename="?([^";]+)"?/.exec(res.headers.get('Content-Disposition') || '')?.[1] || 'project.zip';
    const url = URL.createObjectURL(await res.blob());
    const a = Object.assign(document.createElement('a'), { href: url, download: name });
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  } catch (err) { toast(err.message); }
});

// ── tabs ────────────────────────────────────────────────

document.querySelectorAll('.tab').forEach((tab) =>
  tab.addEventListener('click', () => switchTab(tab.dataset.tab)));

function switchTab(name) {
  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('is-on', t.dataset.tab === name));
  document.querySelectorAll('.tabpanel').forEach((p) => p.classList.toggle('is-on', p.id === 'tab-' + name));
  if (name === 'logs') $('log-dot').hidden = true;
  if (name === 'search') $('search-q').focus();
}

// ── build and preview ───────────────────────────────────

$('btn-build').addEventListener('click', async () => {
  $('btn-build').disabled = true;
  try {
    const r = await api(`/api/v1/projects/${state.projectId}/build`, { method: 'POST' });
    toast(r.passed ? 'Build passed.' : 'Build failed - details in Logs.');
    if (!r.passed) switchTab('logs');
  } catch (err) {
    toast(err.message, { upgrade: err.upgrade });
  } finally {
    $('btn-build').disabled = !canWrite();
  }
});

async function loadPreview() {
  try {
    showPreview(await api(`/api/v1/projects/${state.projectId}/preview`));
  } catch (err) {
    if (err.status === 404) showPreview(null); else toast(err.message);
  }
}

function showPreview(p) {
  const frame = $('preview-frame');
  if (p) {
    if (frame.getAttribute('src') !== p.url) frame.src = p.url;
    frame.hidden = false;
    $('preview-empty').hidden = true;
    const mins = Math.max(0, Math.round((new Date(p.expiresAt) - Date.now()) / 60000));
    $('preview-status').textContent = `Live · expires in ${mins} min`;
    $('preview-open').href = p.url;
    $('preview-open').hidden = false;
    $('btn-preview-stop').hidden = false;
    $('btn-preview').textContent = 'Restart';
  } else {
    frame.removeAttribute('src');
    frame.hidden = true;
    $('preview-empty').hidden = false;
    $('preview-status').textContent = 'No preview running';
    $('preview-open').hidden = true;
    $('btn-preview-stop').hidden = true;
    $('btn-preview').textContent = 'Start preview';
  }
}

function reloadPreviewFrame() {
  const frame = $('preview-frame');
  if (!frame.hidden && frame.src) frame.src = frame.src;   // pick up new files
}

$('btn-preview').addEventListener('click', async () => {
  switchTab('preview');
  try {
    showPreview(await api(`/api/v1/projects/${state.projectId}/preview`, { method: 'POST' }));
    refreshPlanPill();
  } catch (err) {
    toast(err.message, { upgrade: err.upgrade });
  }
});

$('btn-preview-stop').addEventListener('click', async () => {
  try {
    await api(`/api/v1/projects/${state.projectId}/preview`, { method: 'DELETE' });
    showPreview(null);
    refreshPlanPill();
  } catch (err) { toast(err.message); }
});

// ── live logs ───────────────────────────────────────────

function stopLogs() {
  if (state.logs) state.logs.abort();
  state.logs = null;
}

function startLogs() {
  stopLogs();
  $('log-list').innerHTML = '';
  runtimeErrors.clear();
  state.runtimeDismissed = 0;
  $('runtime-bar').hidden = true;
  state.lastLogSeq = 0;
  const controller = new AbortController();
  state.logs = controller;
  const project = state.projectId;
  let delay = 1000;

  const connect = async () => {
    $('log-conn').textContent = 'connecting…';
    try {
      // Last-Event-ID: after a drop, resume where we were instead of replaying the buffer.
      await sse(`/api/v1/projects/${project}/preview/logs/stream`, {
        method: 'GET', signal: controller.signal,
        headers: state.lastLogSeq ? { 'Last-Event-ID': String(state.lastLogSeq) } : {},
        // Connected is "live", whether or not anything has happened yet.
        onOpen: () => { $('log-conn').textContent = 'live'; delay = 1000; },
      }, (event, line) => addLogLine(line));
    } catch (err) {
      if (controller.signal.aborted) return;
      if (err.status === 401 || err.status === 404) { $('log-conn').textContent = 'unavailable'; return; }
    }
    if (controller.signal.aborted) return;
    $('log-conn').textContent = 'reconnecting…';
    setTimeout(connect, delay);
    delay = Math.min(delay * 2, 15000);
  };
  connect();
}

// Errors the generated app threw in the preview since the last build. The
// server attaches the same list to the next request, so "fix it" needs no
// copy-pasting - the agent already sees them.
const runtimeErrors = new Set();

function trackRuntime(l) {
  if (l.source === 'build' && l.message.startsWith('Build started')) {
    runtimeErrors.clear();
  } else if (l.source === 'console' && l.level === 'error') {
    runtimeErrors.add(l.message);
  } else {
    return;
  }
  showRuntimeBar();
}

function showRuntimeBar() {
  const n = runtimeErrors.size;
  if (n === 0 || state.runtimeDismissed === n) { $('runtime-bar').hidden = true; return; }
  const first = [...runtimeErrors][0];
  $('runtime-text').innerHTML = `<b>${n === 1 ? 'Preview error' : `${n} preview errors`}</b><code></code>`;
  $('runtime-text').querySelector('code').textContent = first;
  $('btn-fix').hidden = !canWrite();
  $('runtime-bar').hidden = false;
}

$('btn-fix').addEventListener('click', () => {
  $('runtime-bar').hidden = true;
  $('prompt').value = runtimeErrors.size === 1
    ? 'Fix the error the preview reported.'
    : 'Fix the errors the preview reported.';
  send();
});
$('btn-runtime-dismiss').addEventListener('click', () => {
  state.runtimeDismissed = runtimeErrors.size;
  $('runtime-bar').hidden = true;
});

function addLogLine(l) {
  state.lastLogSeq = Math.max(state.lastLogSeq, l.seq);
  trackRuntime(l);
  const li = document.createElement('li');
  li.className = `log log-${l.level}`;
  const t = new Date(l.at);
  li.innerHTML = `<time>${t.toLocaleTimeString([], { hour12: false })}</time><span class="src src-${esc(l.source)}">${esc(l.source)}</span><span class="txt"></span>`;
  li.querySelector('.txt').textContent = l.message;
  const list = $('log-list');
  const atBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 40;
  list.appendChild(li);
  while (list.children.length > 600) list.firstChild.remove();
  if (atBottom) list.scrollTop = list.scrollHeight;
  if (!$('tab-logs').classList.contains('is-on') && l.level === 'error') $('log-dot').hidden = false;
}

$('btn-log-clear').addEventListener('click', () => { $('log-list').innerHTML = ''; });

// ── search ──────────────────────────────────────────────

$('btn-search').addEventListener('click', search);
$('search-q').addEventListener('keydown', (e) => { if (e.key === 'Enter') search(); });

async function search() {
  const q = $('search-q').value.trim();
  const out = $('search-results');
  if (!q) return;
  out.innerHTML = '<li class="search-none">Searching…</li>';
  try {
    const hits = await api(`/api/v1/projects/${state.projectId}/search?q=${encodeURIComponent(q)}&k=10`);
    out.innerHTML = hits.length ? '' : '<li class="search-none">No matches.</li>';
    for (const h of hits) {
      const li = document.createElement('li');
      li.className = 'hit';
      li.innerHTML = `<div class="hit-head"><code></code><span class="hit-lines"></span></div><pre class="hit-body"></pre>`;
      li.querySelector('code').textContent = h.path;
      li.querySelector('.hit-lines').textContent = `lines ${h.startLine}–${h.endLine}`;
      li.querySelector('.hit-body').textContent = h.content.split('\n').slice(0, 8).join('\n');
      li.addEventListener('click', () => showCode(h.path, [h.startLine, h.endLine]));
      out.appendChild(li);
    }
  } catch (err) {
    out.innerHTML = `<li class="search-none">${esc(err.message)}</li>`;
  }
}

// ── sharing ─────────────────────────────────────────────

document.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', () => b.closest('dialog').close()));

$('btn-share').addEventListener('click', openShare);

async function openShare() {
  $('share-msg').textContent = '';
  $('invite-row').hidden = state.role !== 'OWNER';
  $('dlg-share').showModal();
  await loadMembers();
}

async function loadMembers() {
  const list = $('member-list');
  list.innerHTML = '';
  try {
    const members = await api(`/api/v1/projects/${state.projectId}/members`);
    for (const m of members) {
      const li = document.createElement('li');
      li.className = 'member';
      li.innerHTML = `<span class="m-who"><b></b><small></small></span><span class="m-role"></span>`;
      li.querySelector('b').textContent = m.name || m.email;
      li.querySelector('small').textContent = m.email;
      const role = li.querySelector('.m-role');
      if (m.role === 'OWNER' || state.role !== 'OWNER') {
        role.textContent = m.role.toLowerCase();
      } else {
        role.innerHTML = `<select class="select select-sm">
            <option value="EDITOR"${m.role === 'EDITOR' ? ' selected' : ''}>editor</option>
            <option value="VIEWER"${m.role === 'VIEWER' ? ' selected' : ''}>viewer</option></select>
          <button class="btn btn-quiet btn-xs">Remove</button>`;
        role.querySelector('select').addEventListener('change', async (e) => {
          try {
            await api(`/api/v1/projects/${state.projectId}/members/${m.userId}`, { method: 'PATCH', body: { role: e.target.value } });
            $('share-msg').textContent = 'Role updated.';
          } catch (err) { $('share-msg').textContent = err.message; }
        });
        role.querySelector('button').addEventListener('click', async () => {
          try {
            await api(`/api/v1/projects/${state.projectId}/members/${m.userId}`, { method: 'DELETE' });
            await loadMembers();
          } catch (err) { $('share-msg').textContent = err.message; }
        });
      }
      list.appendChild(li);
    }
  } catch (err) { $('share-msg').textContent = err.message; }
}

$('btn-invite').addEventListener('click', async () => {
  const mail = $('invite-email').value.trim();
  if (!mail) return;
  try {
    await api(`/api/v1/projects/${state.projectId}/members`, { method: 'POST', body: { email: mail, role: $('invite-role').value } });
    $('invite-email').value = '';
    $('share-msg').textContent = `Added ${mail}.`;
    await loadMembers();
  } catch (err) { $('share-msg').textContent = err.message; }
});

// ── plan and billing ────────────────────────────────────

const QUOTA_NAMES = { PROJECTS: 'Projects', PREVIEWS: 'Live previews', AI_TOKENS_PER_DAY: 'AI tokens today' };

async function refreshPlanPill() {
  try {
    const me = await api('/api/v1/billing/me');
    const tokens = me.usage.find((u) => u.quota === 'AI_TOKENS_PER_DAY');
    const pct = tokens && tokens.limit > 0 ? Math.min(100, Math.round(100 * tokens.used / tokens.limit)) : 0;
    $('btn-plan').innerHTML = `<b>${esc(me.plan.name)}</b><span class="pill-bar"><i style="width:${pct}%"></i></span>`;
    $('btn-plan').hidden = false;
    $('btn-plan').title = tokens ? `${tokens.used.toLocaleString()} of ${tokens.limit.toLocaleString()} AI tokens used today` : '';
    return me;
  } catch { return null; }
}

$('btn-plan').addEventListener('click', openPlans);

async function openPlans() {
  $('plan-msg').textContent = '';
  $('dlg-plan').showModal();
  const [me, plans] = await Promise.all([api('/api/v1/billing/me'), api('/api/v1/billing/plans')]);

  $('usage').innerHTML = me.usage.map((u) => {
    const pct = u.limit > 0 ? Math.min(100, Math.round(100 * u.used / u.limit)) : 0;
    return `<div class="usage-row"><span>${esc(QUOTA_NAMES[u.quota] || u.quota)}</span>
      <span class="bar${pct >= 90 ? ' is-hot' : ''}"><i style="width:${pct}%"></i></span>
      <span class="num">${u.used.toLocaleString()} / ${u.limit < 0 ? '∞' : u.limit.toLocaleString()}</span></div>`;
  }).join('') + (me.subscriptionStatus
    ? `<p class="dlg-sub">${esc(me.plan.name)} · ${esc(me.subscriptionStatus.toLowerCase().replace('_', ' '))}` +
      (me.currentPeriodEnd ? ` · ${me.cancelAtPeriodEnd ? 'ends' : 'renews'} ${new Date(me.currentPeriodEnd).toLocaleDateString()}` : '') + '</p>'
    : '');

  $('plans').innerHTML = plans.map((p) => `
    <div class="plan${p.code === me.plan.code ? ' is-current' : ''}">
      <h3>${esc(p.name)}</h3>
      <div class="price">${p.priceCents ? '$' + (p.priceCents / 100).toFixed(0) + '<small>/mo</small>' : 'Free'}</div>
      <ul>${(p.features?.highlights || []).map((h) => `<li>${esc(h)}</li>`).join('')}</ul>
      ${p.code === me.plan.code ? '<span class="current">Current plan</span>'
        : p.priceCents ? `<button class="btn btn-primary btn-sm" data-plan="${esc(p.code)}">Upgrade</button>` : ''}
    </div>`).join('');
  $('plans').querySelectorAll('[data-plan]').forEach((b) => b.addEventListener('click', () => checkout(b.dataset.plan)));

  $('btn-cancel-plan').hidden = !(me.subscriptionStatus && !me.cancelAtPeriodEnd && me.plan.code !== 'FREE');
  if (me.paymentProvider === 'none') {
    $('plan-msg').textContent = 'Payments are not switched on for this server.';
  }
}

async function checkout(plan) {
  $('plan-msg').textContent = 'Opening checkout…';
  try {
    const c = await api('/api/v1/billing/checkout', { method: 'POST', body: { plan } });
    window.location.href = c.url;
  } catch (err) { $('plan-msg').textContent = err.message; }
}

$('btn-cancel-plan').addEventListener('click', async () => {
  try {
    await api('/api/v1/billing/cancel', { method: 'POST' });
    await openPlans();
    $('plan-msg').textContent = 'Cancelled. You keep the plan until the end of the period you paid for.';
  } catch (err) { $('plan-msg').textContent = err.message; }
});

/** Back from checkout: ?billing=success|cancelled. The webhook, not this, grants the plan. */
function handleBillingReturn() {
  const p = new URLSearchParams(location.search).get('billing');
  if (!p) return;
  history.replaceState(null, '', location.pathname);
  toast(p === 'success' ? 'Payment received - your plan updates in a moment.' : 'Checkout cancelled. Nothing was charged.');
  if (p === 'success') setTimeout(refreshPlanPill, 2500);
}

// ── boot ────────────────────────────────────────────────

if (state.token) {
  openWorkbench().catch(() => signOut());
}
