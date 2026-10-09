#!/bin/bash
# ForgeFlow MCP verification. Brings the local stack up, compiles, boots the
# app, and drives a complete MCP session over curl. Everything lands in
# .verify/ so the whole run can be read back afterwards.
#
#   bash scripts/verify-mcp.sh
#
# Leaves the app RUNNING on 8081 when it succeeds.

set -u
cd "$(dirname "$0")/.." || exit 1
OUT=.verify/mcp-verify.log
APPLOG=.verify/app.log
mkdir -p .verify
: > "$OUT"

say() { echo "$@" | tee -a "$OUT"; }
run() { echo "\$ $*" >> "$OUT"; "$@" >> "$OUT" 2>&1; }

say "=== ForgeFlow MCP verification  $(date '+%Y-%m-%d %H:%M:%S %z')"

# ---------- 1. Docker ----------
if docker info >/dev/null 2>&1; then
  say "[1/7] docker: up"
else
  say "[1/7] docker: NOT RUNNING - start Docker Desktop and re-run. Stopping."
  exit 1
fi

# ---------- 2. Postgres ----------
say "[2/7] bringing up compose services"
run docker compose up -d
for i in $(seq 1 30); do
  if nc -z localhost 5433 2>/dev/null; then break; fi
  sleep 1
done
if nc -z localhost 5433 2>/dev/null; then
  say "      postgres: reachable on 5433"
else
  say "      postgres: NOT reachable on 5433 after 30s. Stopping."
  exit 1
fi

# ---------- 3. API key ----------
if [ -f .env ]; then
  export GOOGLE_API_KEY="$(grep '^GOOGLE_API_KEY=' .env | cut -d= -f2-)"
fi
if [ -n "${GOOGLE_API_KEY:-}" ]; then
  say "[3/7] GOOGLE_API_KEY: loaded (${GOOGLE_API_KEY:0:6}...)"
else
  say "[3/7] GOOGLE_API_KEY: MISSING - generate_app will fail with a 403"
fi

# ---------- 4. free the port ----------
PIDS=$(lsof -ti :8081 2>/dev/null)
if [ -n "$PIDS" ]; then
  say "[4/7] killing existing listener(s) on 8081: $PIDS"
  kill -9 $PIDS 2>/dev/null
  sleep 2
else
  say "[4/7] port 8081 free"
fi

# ---------- 5. compile ----------
say "[5/7] compiling"
if ./mvnw -q compile >> "$OUT" 2>&1; then
  say "      compile: OK"
else
  say "      compile: FAILED - see $OUT"
  exit 1
fi

# ---------- 6. boot ----------
say "[6/7] starting app (log: $APPLOG)"
: > "$APPLOG"
nohup ./mvnw spring-boot:run > "$APPLOG" 2>&1 &
for i in $(seq 1 90); do
  if curl -s -m 2 http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
    break
  fi
  sleep 1
done
if curl -s -m 2 http://localhost:8081/actuator/health 2>/dev/null | grep -q '"status":"UP"'; then
  say "      app: UP (pid $(lsof -ti :8081 2>/dev/null | tr '\n' ' '))"
else
  say "      app: did NOT come up in 90s. Tail of $APPLOG:"
  tail -40 "$APPLOG" | tee -a "$OUT"
  exit 1
fi

# ---------- 7. the MCP session ----------
MCP=http://localhost:8081/mcp
# If the server requires a key (FORGEFLOW_MCP_API_KEY), send the same one.
AUTH=()
if [ -n "${FORGEFLOW_MCP_API_KEY:-}" ]; then AUTH=(-H "Authorization: Bearer $FORGEFLOW_MCP_API_KEY"); fi
rpc() {   # rpc <label> <json>
  echo "" >> "$OUT"
  echo "----- $1" >> "$OUT"
  echo "--> $2" >> "$OUT"
  printf '<-- ' >> "$OUT"
  curl -s -m 180 -X POST "$MCP" "${AUTH[@]}" -H "Content-Type: application/json" -d "$2" >> "$OUT" 2>&1
  echo "" >> "$OUT"
}

say "[7/7] driving an MCP session"

rpc "initialize" '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"verify-script","version":"1"}}}'

echo "" >> "$OUT"; echo "----- notifications/initialized (expect empty body, HTTP 202)" >> "$OUT"
curl -s -m 10 -o /dev/null -w "HTTP %{http_code}\n" -X POST "$MCP" "${AUTH[@]}" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' >> "$OUT" 2>&1

rpc "tools/list" '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

rpc "tools/call create_project" '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"create_project","arguments":{"name":"mcp-verify","description":"created through the MCP server"}}}'

# pull the new project id out of the create_project text
PID_NEW=$(grep -o 'Created project [0-9]*' "$OUT" | tail -1 | grep -o '[0-9]*')
say "      new project id: ${PID_NEW:-<none>}"

if [ -n "${PID_NEW:-}" ]; then
  rpc "tools/call list_project_files (empty project)" \
    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"list_project_files\",\"arguments\":{\"project_id\":${PID_NEW}}}}"

  rpc "tools/call generate_app" \
    "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"generate_app\",\"arguments\":{\"project_id\":${PID_NEW},\"prompt\":\"a one-page site with a heading, a short paragraph and a button that shows an alert\"}}}"

  rpc "tools/call list_project_files (after generate)" \
    "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"list_project_files\",\"arguments\":{\"project_id\":${PID_NEW}}}}"
fi

# --- negative cases: these must come back as TOOL errors, not JSON-RPC errors
rpc "tools/call unknown tool (expect isError:true)" '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"no_such_tool","arguments":{}}}'

rpc "tools/call bad project_id (expect isError:true)" '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"list_project_files","arguments":{"project_id":99999}}}'

rpc "unknown method (expect JSON-RPC -32601)" '{"jsonrpc":"2.0","id":9,"method":"nonsense"}'

say ""
say "=== done. Full transcript in $OUT"
say "=== app left running on 8081"
