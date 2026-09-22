// ForgeFlow build check. Runs INSIDE the locked-down build container.
//
// For a static site "building" means verifying, not compiling:
//   1. index.html exists
//   2. every .js file parses (compiled with vm.Script, never executed)
//   3. every local src= / href= in the HTML points at a file that exists
//
// Exit 0 = pass. Exit 1 = fail, with one problem per line on stderr, written so
// the agent can read it and fix it on the next round.

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const root = path.resolve(process.argv[2] || '/work/app');
const errors = [];

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e =>
    e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]);
}

const files = fs.existsSync(root) ? walk(root) : [];
const rel = f => path.relative(root, f);

if (!fs.existsSync(path.join(root, 'index.html'))) {
  errors.push('index.html is missing - every project needs an entry point');
}

for (const f of files.filter(f => f.endsWith('.js'))) {
  try {
    // Compiles without running. The generated code never executes here.
    new vm.Script(fs.readFileSync(f, 'utf8'), { filename: rel(f) });
  } catch (e) {
    errors.push(`${rel(f)}: ${e.name}: ${e.message}`);
  }
}

for (const f of files.filter(f => f.endsWith('.html'))) {
  const html = fs.readFileSync(f, 'utf8');
  const re = /(?:src|href)\s*=\s*["']([^"'#?]+)[^"']*["']/gi;
  let m;
  while ((m = re.exec(html)) !== null) {
    const ref = m[1].trim();
    if (!ref || /^([a-z]+:)?\/\//i.test(ref) || /^(data|mailto|tel|javascript):/i.test(ref)) {
      continue;
    }
    const target = path.normalize(path.join(path.dirname(f), ref));
    if (!target.startsWith(root) || !fs.existsSync(target)) {
      errors.push(`${rel(f)} references a file that does not exist: ${ref}`);
    }
  }
}

if (errors.length > 0) {
  console.error(errors.join('\n'));
  process.exit(1);
}
console.log(`OK - ${files.length} file(s) checked`);
