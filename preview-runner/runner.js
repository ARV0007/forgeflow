// ForgeFlow's in-browser React runner.
//
// The preview server swaps every <script type="module" src="..."> in a React
// project's index.html for an inert <script type="ff-module" data-src="...">
// and loads this file instead. It then does in the browser what Vite's dev
// server does on a machine:
//
//   1. fetch the project's files from the preview (same token, CORS-open)
//   2. compile each one with Sucrase: JSX -> React's automatic runtime,
//      TypeScript stripped, ES imports -> CommonJS require()
//   3. run them with a 40-line module loader, React and ReactDOM coming from
//      the vendored UMD builds, any other package from esm.sh
//
// No npm install, no bundler on the server, nothing to wait for. A compile or
// start-up error goes to console.error - which the preview's bridge forwards
// to the Logs Stream, which the agent sees on its next turn - and onto an
// overlay in the page, which the visual check sees.

import { transform } from 'sucrase';

(function () {
  const script = document.currentScript;
  const base = script.src.slice(0, script.src.lastIndexOf('/') + 1); // .../p/{token}/
  const CODE = /\.(jsx|js|mjs|tsx|ts)$/;
  const TRY = ['', '.jsx', '.js', '.tsx', '.ts', '.mjs', '/index.jsx', '/index.js', '/index.tsx', '/index.ts'];
  const REQUIRE = /\brequire\(\s*(['"])([^'"]+)\1\s*\)/g;
  const ENV = { MODE: 'development', DEV: true, PROD: false, SSR: false, BASE_URL: '/' };

  const domReady = document.readyState === 'loading'
    ? new Promise((r) => document.addEventListener('DOMContentLoaded', r, { once: true }))
    : Promise.resolve();

  window.process = window.process || { env: { NODE_ENV: 'production' } };

  function loadScript(src) {
    return new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = src;
      s.onload = resolve;
      s.onerror = () => reject(new Error('could not load ' + src));
      document.head.appendChild(s);
    });
  }

  function join(from, spec) {
    const dir = from.includes('/') ? from.slice(0, from.lastIndexOf('/') + 1) : '';
    const parts = [];
    for (const seg of (spec.startsWith('/') ? spec.slice(1) : dir + spec).split('/')) {
      if (!seg || seg === '.') continue;
      if (seg === '..') { if (!parts.length) return null; parts.pop(); continue; }
      parts.push(seg);
    }
    return parts.join('/');
  }

  function packageName(spec) {
    const p = spec.split('/');
    return spec.startsWith('@') ? p[0] + '/' + p[1] : p[0];
  }

  class BuildError extends Error {}

  function overlay(title, detail) {
    const box = document.createElement('div');
    box.setAttribute('data-forgeflow-error', '');
    box.style.cssText = 'position:fixed;inset:0;z-index:2147483647;background:rgba(24,20,16,.94);color:#f0ebe2;'
      + 'font:14px/1.5 ui-monospace,Menlo,monospace;padding:28px;overflow:auto;white-space:pre-wrap';
    const h = document.createElement('div');
    h.style.cssText = 'color:#e8763a;font-weight:600;margin-bottom:10px';
    h.textContent = title;
    box.append(h, document.createTextNode(detail));
    (document.body || document.documentElement).appendChild(box);
  }

  function fail(e) {
    const title = e instanceof BuildError ? 'Build error' : 'The app crashed while starting';
    console.error(title + ': ' + (e && e.message ? e.message : e));
    overlay(title, (e && (e instanceof BuildError ? e.message : e.stack || e.message)) || String(e));
  }

  async function run() {
    await domReady;
    const entries = [...document.querySelectorAll('script[type="ff-module"]')]
      .map((s) => join('index.html', s.getAttribute('data-src')))
      .filter(Boolean);
    if (!entries.length) throw new BuildError('index.html loads no module (<script type="module" src="/src/main.jsx">)');

    const [paths, pkg] = await Promise.all([
      fetch(base + '__files.json').then((r) => r.json()),
      fetch(base + 'package.json').then((r) => (r.ok ? r.json() : {})).catch(() => ({})),
    ]);
    const exists = new Set(paths);
    const deps = Object.assign({}, pkg.devDependencies, pkg.dependencies);
    const resolve = (from, spec) => {
      const b = join(from, spec);
      if (b === null) return null;
      for (const t of TRY) if (exists.has(b + t)) return b + t;
      return null;
    };

    // Compile everything reachable from the entries before running anything:
    // require() is synchronous, so every module has to be ready when asked for.
    const compiled = new Map(); // path -> { code } | { css } | { json } | { url }
    const bare = new Set();
    const queue = [...entries];
    while (queue.length) {
      const path = queue.shift();
      if (compiled.has(path)) continue;
      if (!CODE.test(path) && !/\.(css|json)$/.test(path)) {
        compiled.set(path, { url: base + path });
        continue;
      }
      const res = await fetch(base + path);
      if (!res.ok) throw new BuildError(path + ' could not be loaded (' + res.status + ')');
      const text = await res.text();
      if (path.endsWith('.css')) { compiled.set(path, { css: text }); continue; }
      if (path.endsWith('.json')) { compiled.set(path, { json: text }); continue; }

      let code;
      try {
        const isTs = /\.tsx?$/.test(path);
        code = transform(text.replace(/import\.meta\.env/g, '__ff_env').replace(/import\.meta\.url/g, JSON.stringify(base + path)), {
          transforms: isTs ? ['typescript', 'jsx', 'imports'] : ['jsx', 'imports'],
          jsxRuntime: 'automatic',
          production: true,
          filePath: path,
        }).code;
      } catch (e) {
        throw new BuildError(path + ': ' + String(e.message).replace(/^Error transforming [^:]+: /, ''));
      }
      const resolved = {};
      for (const m of code.matchAll(REQUIRE)) {
        const spec = m[2];
        if (spec.startsWith('.') || spec.startsWith('/')) {
          const target = resolve(path, spec);
          if (!target) throw new BuildError(path + " imports '" + spec + "', but no such file exists");
          resolved[spec] = target;
          queue.push(target);
        } else {
          bare.add(spec);
        }
      }
      compiled.set(path, { code, resolved });
    }

    // Packages. React and ReactDOM are the vendored UMD builds, loaded once;
    // anything else comes from esm.sh, told to use OUR React (two copies of
    // React in one page is the classic "invalid hook call").
    await loadScript(base + '__vendor/react.js');
    await loadScript(base + '__vendor/react-dom.js');
    const React = window.React;
    const ReactDOM = window.ReactDOM;
    const jsxRuntime = {
      Fragment: React.Fragment,
      jsx: (type, props, key) => React.createElement(type, key === undefined ? props : Object.assign({}, props, { key })),
    };
    jsxRuntime.jsxs = jsxRuntime.jsx;
    jsxRuntime.jsxDEV = jsxRuntime.jsx;
    const builtins = {
      react: React,
      'react-dom': ReactDOM,
      'react-dom/client': { createRoot: ReactDOM.createRoot, hydrateRoot: ReactDOM.hydrateRoot },
      'react/jsx-runtime': jsxRuntime,
      'react/jsx-dev-runtime': jsxRuntime,
    };
    const external = [...bare].filter((s) => !(s in builtins));
    if (external.length) {
      const shim = (name, obj) => URL.createObjectURL(new Blob(
        ['const m = window.' + name + '; export default m; export const '
          + Object.keys(obj).filter((k) => /^[A-Za-z_$][\w$]*$/.test(k)).map((k) => k + ' = m.' + k).join(', ') + ';'],
        { type: 'text/javascript' }));
      const map = { imports: {
        react: shim('React', React),
        'react-dom': shim('ReactDOM', ReactDOM),
        'react-dom/client': shim('ReactDOM', { createRoot: 1, hydrateRoot: 1 }),
        'react/jsx-runtime': URL.createObjectURL(new Blob(['const R = window.React; export const Fragment = R.Fragment;'
          + ' export function jsx(t, p, k) { return R.createElement(t, k === undefined ? p : Object.assign({}, p, { key: k })); }'
          + ' export const jsxs = jsx;'], { type: 'text/javascript' })),
      } };
      const im = document.createElement('script');
      im.type = 'importmap';
      im.textContent = JSON.stringify(map);
      document.head.appendChild(im);
      for (const spec of external) {
        const name = packageName(spec);
        const version = deps[name] ? '@' + String(deps[name]).replace(/^[\^~>=<\s]+/, '') : '';
        const url = 'https://esm.sh/' + name + version + spec.slice(name.length) + '?external=react,react-dom';
        try {
          const ns = await import(url);
          builtins[spec] = Object.assign({ __esModule: true }, ns);
        } catch (e) {
          throw new BuildError("Couldn't load the package '" + spec + "' from esm.sh (" + e.message + ')');
        }
      }
    }

    // A CommonJS loader: what Node does, minus the file system.
    const cache = new Map();
    function load(path) {
      if (cache.has(path)) return cache.get(path).exports;
      const mod = { exports: {} };
      cache.set(path, mod);
      const c = compiled.get(path);
      if (c.css !== undefined) {
        const style = document.createElement('style');
        style.setAttribute('data-file', path);
        style.textContent = c.css;
        document.head.appendChild(style);
      } else if (c.json !== undefined) {
        mod.exports = JSON.parse(c.json);
      } else if (c.url !== undefined) {
        mod.exports = { __esModule: true, default: c.url };
      } else {
        const require = (spec) => (spec in c.resolved ? load(c.resolved[spec]) : builtins[spec]);
        // sourceURL: stack traces and errors name the project file, not "anonymous".
        const fn = new Function('require', 'module', 'exports', '__ff_env', c.code + '\n//# sourceURL=' + path);
        fn(require, mod, mod.exports, ENV);
      }
      return mod.exports;
    }
    for (const entry of entries) load(entry);
  }

  run().catch(fail);
})();
