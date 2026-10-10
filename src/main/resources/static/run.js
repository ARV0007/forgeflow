// "Run with Node": the project, booted under real Node.js in this tab.
//
// The workbench preview compiles a React project in the browser - instant,
// but it is not Node: no npm install, no Vite. This page is the other half of
// the diagram's "Code Execution Service: WebContainer": StackBlitz's
// WebContainer runs Node compiled to WebAssembly, so the exact files ForgeFlow
// generated get `npm install` and `npm run dev`, and the app is served by Vite
// from inside the browser tab. ForgeFlow's server only hands over the files.
//
// Needs cross-origin isolation (SharedArrayBuffer): this page alone is served
// with COOP/COEP headers - see CrossOriginIsolation.java.

import { WebContainer } from '/vendor/webcontainer-api.js';

const $ = (id) => document.getElementById(id);
const term = $('run-term');
const ANSI = /\x1b\[[0-9;?]*[A-Za-z]|\x1b\][^\x07]*\x07/g;

function step(name, state, note) {
  const li = document.querySelector(`[data-step="${name}"]`);
  li.className = 'is-' + state;
  if (note !== undefined) li.dataset.note = note;
}

let lastLine = '';
function write(chunk) {
  // npm redraws its spinner with \r; keep the terminal to finished lines.
  const text = String(chunk).replace(ANSI, '');
  for (const part of text.split(/(\r?\n|\r)/)) {
    if (part === '\n' || part === '\r\n') { term.textContent += lastLine + '\n'; lastLine = ''; }
    else if (part === '\r') { lastLine = ''; }
    else lastLine += part;
  }
  const atBottom = term.scrollHeight - term.scrollTop - term.clientHeight < 40;
  term.dataset.live = lastLine;
  if (atBottom) term.scrollTop = term.scrollHeight;
}

function stop(name, message) {
  step(name, 'failed');
  write('\n' + message + '\n');
  $('run-wait').innerHTML = '';
  const p = document.createElement('p');
  p.className = 'run-error';
  p.textContent = message;
  $('run-wait').appendChild(p);
}

async function api(path) {
  const token = localStorage.getItem('ff_token');
  const res = await fetch(path, { headers: token ? { Authorization: 'Bearer ' + token } : {} });
  if (!res.ok) throw new Error(`${path} answered ${res.status}`);
  return res.json();
}

/** ForgeFlow's flat path -> contents map, as the nested tree WebContainer.mount wants. */
function tree(files) {
  const root = {};
  for (const [path, contents] of Object.entries(files)) {
    const parts = path.split('/');
    let dir = root;
    for (const part of parts.slice(0, -1)) {
      dir[part] = dir[part] || { directory: {} };
      dir = dir[part].directory;
    }
    dir[parts[parts.length - 1]] = { file: { contents } };
  }
  return root;
}

async function run() {
  const projectId = Number(new URLSearchParams(location.hash.slice(1)).get('project'));
  if (!projectId) return stop('isolate', 'No project given. Open this page from the workbench: Preview → Run with Node.');
  if (!localStorage.getItem('ff_token')) return stop('isolate', 'Sign in on the workbench first, then come back.');

  step('isolate', 'active');
  if (!window.crossOriginIsolated) {
    return stop('isolate', 'This browser did not grant cross-origin isolation, which Node-in-the-browser needs '
      + '(SharedArrayBuffer). Use a recent Chrome, Edge or Firefox.');
  }
  step('isolate', 'done');

  step('files', 'active');
  let project;
  let files = {};
  try {
    project = await api(`/api/v1/projects/${projectId}`);
    $('run-title').textContent = project.name;
    document.title = `${project.name} · Run with Node · ForgeFlow`;
    const list = await api(`/api/v1/projects/${projectId}/files`);
    for (const f of list) {
      const file = await api(`/api/v1/projects/${projectId}/files/content?path=${encodeURIComponent(f.path)}`);
      files[f.path] = file.content;
    }
  } catch (e) {
    return stop('files', 'Could not load the project: ' + e.message);
  }
  if (!files['package.json']) {
    return stop('files', 'This project has no package.json, so there is nothing for Node to run. '
      + 'Run with Node is for React projects; plain HTML projects use the normal preview.');
  }
  step('files', 'done', `${Object.keys(files).length} files`);

  step('boot', 'active');
  let wc;
  try {
    // Booting fetches the runtime from StackBlitz; a network that blocks it
    // can leave boot() waiting forever, so give up with a reason instead.
    wc = await Promise.race([
      WebContainer.boot({ workdirName: 'app' }),
      new Promise((_, reject) => setTimeout(() => reject(new Error('timed out after 45 s')), 45000)),
    ]);
  } catch (e) {
    return stop('boot', 'The WebContainer could not start: ' + (e.message || e)
      + '. It loads its runtime from StackBlitz, so a network that blocks stackblitz.com blocks it too.');
  }
  step('boot', 'done');

  await wc.mount(tree(files));

  step('install', 'active');
  write('$ npm install\n');
  const install = await wc.spawn('npm', ['install', '--no-audit', '--no-fund']);
  install.output.pipeTo(new WritableStream({ write }));
  const code = await install.exit;
  if (code !== 0) return stop('install', `npm install exited with code ${code}.`);
  step('install', 'done');

  step('dev', 'active');
  write('\n$ npm run dev\n');
  wc.on('server-ready', (port, url) => {
    step('dev', 'done', `port ${port}`);
    $('run-wait').hidden = true;
    $('run-frame').hidden = false;
    $('run-frame').src = url;
    $('run-open').href = url;
    $('run-open').hidden = false;
  });
  wc.on('error', (e) => write('\n[webcontainer] ' + (e.message || e) + '\n'));
  const dev = await wc.spawn('npm', ['run', 'dev']);
  dev.output.pipeTo(new WritableStream({ write }));
  dev.exit.then((c) => stop('dev', `The dev server stopped (exit code ${c}).`));
}

run().catch((e) => stop('boot', 'Something went wrong: ' + (e.message || e)));
