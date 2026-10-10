#!/bin/sh
# Rebuild the vendored preview files. Run from this folder: npm ci && npm run build
set -e
OUT=../src/main/resources/preview
STATIC=../src/main/resources/static/vendor
mkdir -p "$OUT" "$STATIC"

# 1. The in-browser module runner: Sucrase + a small CommonJS loader, one IIFE.
npx esbuild runner.js --bundle --minify --format=iife --target=es2019 \
  --legal-comments=none --banner:js="/*! ForgeFlow preview runner | includes Sucrase 3.35.0 (MIT) */" \
  --outfile="$OUT/module-runner.js"

# 2. React 18 UMD builds (React 19 ships no UMD); the runner exposes them as 'react' / 'react-dom'.
cp node_modules/react/umd/react.production.min.js "$OUT/react.production.min.js"
cp node_modules/react-dom/umd/react-dom.production.min.js "$OUT/react-dom.production.min.js"

# 3. The WebContainer client for /run.html, as a single ES module.
npx esbuild node_modules/@webcontainer/api/dist/index.js --bundle --minify --format=esm \
  --banner:js="/*! @webcontainer/api 1.6.4 (MIT) - StackBlitz */" --outfile="$STATIC/webcontainer-api.js"

ls -la "$OUT" "$STATIC"
