# ForgeFlow — Notes

Concepts, organised by topic, with the *why* behind each. The WORKLOG says what
happened on which day; this file says what it means.

---

## 1. The agent loop

An agent is a loop, not a single prompt:

```
prompt → model → tool calls → results → model → tool calls → ... → finish
```

Each pass round the loop is one **round**. The model looks at everything so far,
decides what to do next, and either calls tools or stops.

**Tools instead of prose.** The typical "AI app builder" clone asks the model for
code, gets back a markdown block, and pulls the code out with a regex. That
breaks the first time the model writes a stray backtick, and it can't edit a
file, only rewrite everything. ForgeFlow's model never writes code in its reply.
Code reaches the project only through `write_file`. The model decides *what* to
do; our Java decides whether it's allowed and actually does it.

**The four tools:** `list_files`, `read_file`, `write_file`, `finish`. There's no
`run_command`. The list of tools is the list of everything the model can do — so
the allowlist is the security boundary.

**A failed tool call is an observation, not a crash.** If the model writes to
`../../etc/passwd`, `AgentTools` returns `ERROR: path may not contain '..'`. The
model reads that on its next round and adjusts. Throwing an exception would end
the run over something the model could have fixed.

---

## 2. Brakes: caps and stop reasons

An agent loop without brakes is a bill. Every run is capped:

| Cap | Value | Stops |
|---|---|---|
| Tool calls | 25 | a model stuck in a loop |
| Input tokens | 120,000 | runaway context growth |
| Wall clock | 240 s | a run that never ends |
| Repair rounds | 3 | endless fix-and-fail |

Every exit records **why** in `stop_reason`:

- `FINISH_TOOL` — model called finish and the build passed
- `NO_TOOL_CALL` — model stopped replying without calling finish
- `MAX_REPAIRS` — the build kept failing after 3 fix attempts
- `MAX_TOOL_CALLS`, `TOKEN_BUDGET`, `TIMEOUT` — a cap tripped
- `ERROR` — something threw

So a run that didn't end cleanly can be diagnosed from the database, without
re-running it.

### Status versus stop reason

These are two different questions, deliberately kept apart:

- **`status` is the outcome:** did the code work? `SUCCEEDED` means the build
  passed, however the loop happened to end.
- **`stop_reason` is the mechanism:** *how* did the loop end?

A run can be `SUCCEEDED` with stop reason `NO_TOOL_CALL` — good code, untidy
sign-off. Collapse the two into one field and you lose exactly the data an eval
needs.

---

## 3. Talking to the model directly

**Why no Spring AI.** Spring AI's stable releases target Spring Boot 3. ForgeFlow
is on Boot 4, which needs a Spring AI 2.x milestone from a separate repository.
Chasing milestone versions on a deadline is how days disappear. Instead:

- `LlmClient` — one method, `chat(systemPrompt, history, tools)`. Provider-neutral.
- `GeminiClient` — the implementation, over the JDK's own `java.net.http.HttpClient`.

Swapping to Claude or OpenAI means writing one more implementation. Nothing in
the agent loop knows which provider it's talking to.

**Echo the model's turn back verbatim.** Gemini 2.5 thinks before it answers, and
attaches a `thoughtSignature` to each part of its reply. Next round, the
conversation history has to include the model's previous turn *exactly as
received*. Rebuild it from your own fields and the signature is lost — the
model forgets its own reasoning. That's why `LlmMessage` carries an opaque
`rawModelParts` blob for model turns.

### Tokens

| Field | Meaning |
|---|---|
| `promptTokenCount` | everything you sent: system prompt, history, tool definitions |
| `candidatesTokenCount` | what the model wrote back |
| `thoughtsTokenCount` | the model's hidden reasoning — billed |
| `totalTokenCount` | the real total |

**`total` ≠ `prompt` + `completion`.** Run 1 was 5,461 + 1,258 = 6,719, but the
total was 7,102 — the missing 383 were thinking tokens. Always use the number the
API reports rather than doing your own addition.

**Context grows every round.** The whole history is re-sent each time. That's
why the token cap is on *input* tokens, and it's what prompt caching (still to
build) is for.

---

## 4. Streaming (Server-Sent Events)

SSE is a normal HTTP response that stays open. The server writes events into it
as they happen:

```
event:file
data:{"type":"file","message":"Wrote index.html","path":"index.html","data":3448}
```

**We stream our own events, not the model's tokens.** A tool call arrives from
Gemini as one complete chunk — it can't be half-emitted, because half a JSON
object means nothing. So in an agent, the meaningful unit of progress is a
completed tool call.

**Three details that matter:**
- **Check ownership before opening the stream.** Once the response has started,
  the status code is already sent. Check inside the background task and an
  unauthorised caller gets `200` plus an error event instead of a clean `404`.
- **Once streaming, errors must be events.** Spring logs *"Cannot render error
  page… response has already been committed"* — it can't change a response
  that's half-sent.
- **Virtual threads.** Each run executes on `newVirtualThreadPerTaskExecutor()`.
  A run is mostly waiting on the network; virtual threads make waiting almost
  free.

---

## 5. Rate limits and retries

Gemini's free tier: **5 requests per minute** per model. One run makes one
request per round. So hitting the limit mid-run is normal operation.

**Which failures to retry:**
- `429 Too Many Requests` — yes, it'll work later
- `5xx` — yes, the server had a bad moment
- any other `4xx` — **no**; a malformed request is just as malformed next time

**How long to wait:**
1. **Honour `retryDelay`** if the server sends one — Google includes it in the 429
   body. The server knows better than you do.
2. Otherwise **exponential backoff**: 2s, 4s, 8s, 16s…
3. Add **jitter** (a random 0–500ms), so several runs that got limited together
   don't all retry at the same instant and get limited together again.

**Cost of the free tier:** rate-limit waits dominate wall-clock time. The Day 6
demo took 114 seconds; on a paid key it's around 20.

---

## 6. The sandbox

Generated code was written by a model responding to whatever a stranger typed.
Treat it as hostile.

### Two containers, two threat models

| | Build container | Preview container |
|---|---|---|
| Job | read and parse generated code | serve files over HTTP |
| Image | `node:20-alpine` | `nginx:alpine` |
| Where generated JS runs | parsed here, never executed | in the viewer's browser |
| Lockdown | full | lighter, bound to localhost |

The build container gets everything, because it's the one handling the code. The
preview container only hands bytes to a browser, and the browser has its own
sandbox for running JavaScript.

### What each build flag stops

| Flag | Stops |
|---|---|
| `--network=none` | exfiltration, phoning home, downloading payloads |
| `--read-only` + `--tmpfs /tmp` | persisting anything |
| `--memory=512m` | eating the host's RAM |
| `--cpus=0.5` | crypto mining on your machine |
| `--pids-limit=128` | fork bombs |
| `--cap-drop=ALL` | every Linux privilege, gone |
| `--security-opt=no-new-privileges` | regaining any of them |
| `--user=1000:1000` | running as root |
| a 30s timeout + `docker rm -f` | infinite loops |

### Compile, don't execute

`check.js` uses Node's `vm.Script`, which *parses* JavaScript without running
it. A syntax error is caught; the code itself never executes. It also checks that
every local `src=` and `href=` in the HTML points at a file that exists.

### Defence in depth on paths

There are **two independent path guards**:
1. `AgentTools.safePath` — protects the database
2. `DockerSandboxProvider.writeFiles` — protects the host filesystem, by
   normalising each path and refusing any that ends up outside the sandbox
   folder

Why both? Because a path reaching the second one is about to become a real file
on a real disk, and one layer of defence is always one bug away from none.

### Running processes from Java: two traps

- **Killing the `docker` CLI doesn't kill the container.** The CLI is just a
  client talking to the Docker daemon. On timeout, remove the container by name
  with `docker rm -f`, or it keeps running.
- **Read a child's output while it runs.** If a process writes more than the pipe
  buffer holds and you only start reading after `waitFor()`, both sides wait on
  each other forever. `DockerSandboxProvider` drains output on a separate thread.

---

## 7. Self-healing: the gate inside `finish`

When the model calls `finish`, ForgeFlow runs the build *before* answering:

- **passes** → `finish` returns "Build passed", the run ends `SUCCEEDED`
- **fails, repairs left** → `finish` returns an ERROR listing each problem; the
  model fixes them and calls `finish` again
- **fails, no repairs left** → the run ends `FAILED` / `MAX_REPAIRS`

**Why inside `finish`, not after the loop?**
- The model **can't skip verification**. The only way out goes through the check.
- A build failure arrives as a **tool result** — exactly what the model is best
  at acting on.
- It avoids **two user turns in a row**. After the loop ends, the last message is
  the user side's tool results; sending another "please fix this" user message
  breaks the alternating conversation Gemini expects.

The error text is written *for the model*: file name first, then the problem
(`broken.js: SyntaxError: Unexpected number`), so it knows which file to open.

---

## 8. Auth patterns

- **401 vs 403.** 401 = "who are you?" 403 = "I know who you are, and no."
  Without `httpBasic()` or `formLogin()`, Spring Security has no entry point that
  issues a challenge and falls back to 403 — so you have to configure one.
- **Identical failures.** Wrong password and unknown email give the same status
  and byte-identical body. Otherwise the login endpoint tells anyone which emails
  are registered.
- **404, not 403, for someone else's resource.** Same message as "doesn't exist".
- **Ownership in the query.** `findByIdAndOwnerIdAndDeletedAtIsNull` — the check
  isn't a line of Java you could forget, it's the query itself.
- **Make the lie impossible.** `ownerId` isn't a field on the request DTO, so a
  forged one has nowhere to land.
- **JWT subject is the user ID.** Emails change; IDs don't.
- **`/error` first and permitted.** Otherwise a real 500 gets re-dispatched to
  `/error`, arrives anonymous, and turns into an empty 403.

---

## 9. Architecture: a modular monolith

One deployable app, split into packages exactly where separate services would
be: `account`, `workspace`, `intelligence`, `execution`, `shared`.

**The rules that keep a later split cheap:**
- Modules talk through **service interfaces**, never another module's repository
- **No cross-module JPA relationships** — `Project.ownerId` is a plain `Long`,
  not `@ManyToOne User`
- Each module owns its tables

**Why not microservices now?** Six services means six deployments, six configs,
service discovery and distributed debugging — before the product even works. The
only boundary that earns a network hop in v1 is the sandbox, because it runs
untrusted code and scales differently from everything else.

*Known exception:* `AgentTools` still reads `ProjectFileRepository` directly.
`ProjectFileService` exists and new code uses it.

---

## 10. Spring and Java gotchas

- **Jackson 3.** Boot 4's Jackson lives in `tools.jackson`, not
  `com.fasterxml.jackson`. `JsonNode.fieldNames()` became `propertyNames()` and
  returns a `Set`. Package and method names change across major versions, not
  just the numbers.
- **The `:` in `@Value`.** `@Value("${key:default}")` splits on the first colon.
  An image tag like `node:20-alpine` contains one, so image defaults live in the
  Java code instead.
- **`@Value` defaults make config optional.** `${forgeflow.llm.max-retries:5}`
  works whether or not `application.yml` has the key.
- **Relaxed binding.** The environment variable `FORGEFLOW_AGENT_TIMEOUTSECONDS`
  overrides `forgeflow.agent.timeout-seconds` — no file edit needed.
- **`ddl-auto: validate`.** Hibernate checks entities against the real tables and
  refuses to boot on a mismatch. With `update`, Hibernate would silently alter
  tables that Flyway owns.
- **YAML indentation is structure.** A key one level too deep isn't an error —
  it's a different key, silently ignored.

---

## 11. Environment and tooling

- **After a reboot:** Docker Desktop → `docker compose up -d` → the app. The app
  dies at startup without Postgres.
- **Docker Desktop on a Mac** can only bind-mount folders it shares with its VM.
  `/Users` is always shared, which is why sandbox work lives under
  `~/.forgeflow`.
- **Heredocs need `EOF` at column 0.** An indented terminator isn't recognised,
  so the heredoc stays open and swallows your next paste.
- **Moving files between chat and terminal:** base64 on a single line (no
  newlines to mangle), chunks under ~4.4KB, and an MD5 per chunk so a bad one is
  caught immediately.
- **`/tmp` is wiped on reboot.**
- **"Expecting value: line 1 column 1"** from `json.load` means the response was
  *empty*, not malformed — usually because the server isn't running.
- **Wrong directory** is the most common failure of all. Glance at the prompt
  before anything starting `./` or `git`. `ff` jumps to the project.

## Fixed: LLM timeout was two timeouts under one name (27 Sep 2026)

`forgeflow.llm.timeout-seconds` was wired to `HttpClient.connectTimeout` — how long to wait for the TCP connection to open. The *request* timeout, which bounds how long to wait for Gemini's response, was hardcoded to 120s and ignored config entirely.

So the property that looked like it controlled generation timeouts controlled the one that almost never matters.

Now split: `connect-timeout-seconds: 10` and `request-timeout-seconds: 60`.

The request timeout must stay meaningfully below `agent.timeout-seconds` (240), which bounds the whole run. At 60 a run survives up to four hung calls before the agent gives up. Set it above 240 and it becomes unreachable — the agent timeout always fires first, and you'd have a dead knob again from the other direction.

Verified by inversion: at 1 second a generation fails in 297ms; at the 60s default the same generation succeeds in 6.1s. A config fix isn't done until the knob is shown to move something.

## Ctrl+C on spring-boot:run can leave an orphaned JVM (27 Sep 2026)

`./mvnw spring-boot:run` forks a child Java process. Interrupting Maven does not reliably kill the child, so the JVM keeps running and holding port 8081. Symptoms: "Port 8081 was already in use" after a Ctrl+C, or — worse — a "restarted" app that still behaves like the old one, because the orphan answered the request.

Diagnose with `lsof -i :8081` and compare the PID against the one printed in the startup line of the tab you think is serving. If they differ, you are testing the wrong process.

Worse still: if the shell that spawned it carries a stale exported variable, killing the process is not enough — the tab can respawn with the same environment. Close the tab and open a fresh one. That was what finally resolved an hour of a config change appearing not to take effect.

## 12. The MCP server

MCP (Model Context Protocol) is how an AI assistant calls out to an external
tool. The assistant knows nothing about ForgeFlow; it knows how to speak MCP.
Expose an MCP server and ForgeFlow becomes something another agent can drive.

### It is JSON-RPC 2.0 and nothing more

Every message in has the same shape:

```json
{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
```

Every message out echoes that `id` with **either** `result` **or** `error`,
never both — which is why `JsonRpc.Response` carries
`@JsonInclude(NON_NULL)`. Without it a success serializes with a dangling
`"error": null` sitting next to the result, and a strict client may reject it.

Three methods are enough for a tools-only server: `initialize`, `tools/list`,
`tools/call`.

### Notifications have no id

A JSON-RPC message with no `id` expects no reply. MCP sends
`notifications/initialized` right after the handshake. Answering it confuses
clients that are not waiting for a response, so the controller returns
`202 Accepted` with an empty body when `req.id()` is null.

### Why the protocol version is pinned

The spec has moved at least five times: `2024-11-05`, `2025-03-26`,
`2025-06-18`, `2025-11-25`, `2026-07-28`. The newest revision is not an
increment — it **removes the `initialize` handshake entirely** and makes MCP
stateless, drops the `Mcp-Session-Id` header, requires `Mcp-Method` and
`Mcp-Name` headers on every POST, and adds required `resultType`, `ttlMs` and
`cacheScope` fields.

Two things make pinning safe rather than lazy:

- Anthropic's clients still perform the `initialize` handshake.
- The newer spec explicitly tells clients to treat a result from an
  earlier-protocol server that omits `resultType` as `"complete"`.

So `McpController` pins `2025-06-18` and says so in a comment. Same reasoning as
pinning `gemini-3.1-flash-lite` over `gemini-flash-lite-latest`: a moving
dependency under a thing you demo is a liability, and "I pinned it deliberately"
is a better answer than "I used latest."

### Tool error versus protocol error

This is the distinction that matters most, and it is easy to get backwards.

- **JSON-RPC `error`** means *the client is wrong*: unknown method, malformed
  JSON, missing required parameter. Code `-32601` is the standard "method not
  found". A client typically surfaces this to the user as "the server is
  broken."
- **A normal `result` with `isError: true`** means *the world is wrong*: no such
  project, the build failed, the project was deleted. The request was
  well-formed and the tool ran.

The second goes back into the calling model's context, where it can read
"project 99999 not found" and call `create_project` instead. That is the
behaviour you want, and it is the same reasoning as the agent's own self-healing
loop: feed the error back to the model and let it recover.

Rule of thumb: **protocol error if the client is wrong, tool error if the world
is wrong.** So `McpToolExecutor.call` wraps everything in a try/catch that
returns a tool error — a bad `project_id` must not fail the JSON-RPC call.

### The tool list is a prompt

`tools/list` returns names, descriptions and JSON Schemas, and the calling model
reads them to decide what to call. They are part of its prompt, so they are
written for a reader who knows nothing about ForgeFlow: `generate_app`'s
description says it needs a `project_id` from `create_project`, that it takes
5–20 seconds, and that calling it again on the same project iterates.

Three tools, not six. `/build` and `/preview` exist in the REST API but are left
out: the agent already runs the build inside `finish`, and a preview URL means
nothing to an agent. More tools with overlapping purposes makes a model *worse*
at choosing.

**Declare integers as integers.** `project_id` was first declared
`"type": "string"` while the services take a `Long`. A model reading that
schema will dutifully send `"43"` with quotes.

### MCP requests carry no JWT

Every service method takes an owner (`projects.create(ownerId, …)`), and an MCP
request has no token to derive one from. ForgeFlow uses a **fixed service
account**: `forgeflow.mcp.owner-id`, default 1. Projects created through MCP
belong to that user, not to whoever is driving the client.

This keeps the multi-tenancy model intact instead of punching a hole in it, and
it is honest about what it is. The alternatives were an API key header (Claude
Desktop's custom-header support is only partial) or making the caller pass a
JWT as a tool argument (awkward — the model would have to carry a token
around).

`listFiles` still performs the ownership check (`projects.getById(id, ownerId)`)
before reading files. That check is what turns a wrong `project_id` into a
readable tool error rather than someone else's file listing.

### Hand-rolled on purpose

Spring AI 2.0 ships `spring-ai-starter-mcp-server-webmvc` with `@McpTool`
annotations, and it would have taken an afternoon. It was not used, for the same
reason `GeminiClient` has no SDK: one fewer dependency to version-match against
Boot 4, and the request/response shape stays visible. The protocol is three
methods over JSON-RPC — small enough that implementing it is cheaper than
owning a milestone-version dependency.

The honest counter-argument: the starter is maintained and handles version
negotiation and edge cases. If ForgeFlow had users rather than an interviewer,
that would be the right call.

### Reaching it from a client

- **Claude Desktop's `claude_desktop_config.json` cannot do HTTP.** Every entry
  there describes a local process to launch — command, args, env. No `url`
  field, no HTTP transport. That file is stdio-only.
- **Remote servers go through Settings → Connectors → Add custom connector.**
  But that runtime connects from Anthropic's infrastructure, not from the local
  machine, so `http://localhost:8081/mcp` is invisible to it — a custom
  connector needs a publicly reachable HTTPS server. The Render deployment is
  already one.
- **Claude Code CLI** supports `claude mcp add --transport http` and runs
  locally, so it can reach `localhost:8081`.

One endpoint serves all three. The transport worry turned out not to fork the
work at all.

---

## 13. Redesign notes (9 Oct 2026)

### Testing on Spring Boot 4

- The test annotations moved. `@AutoConfigureMockMvc` is now
  `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`;
  `@LocalServerPort` is `org.springframework.boot.test.web.server.LocalServerPort`.
  Old imports fail with "cannot find symbol", not a helpful hint.
- Jackson 3 lives in `tools.jackson.databind`, **but the annotations stayed** in
  `com.fasterxml.jackson.annotation` (`@JsonInclude` and friends).
- A base test class with a method called `post(...)` **hides** a static import
  of MockMvc's `post(...)`, even though the parameters differ. Java looks up the
  name in the class first and never reaches the import. Use the qualified
  `MockMvcRequestBuilders.post(...)`.
- Tests run against a real Postgres, in CI too (a `pgvector/pgvector:pg16`
  service container). Every test signs up its own random-email users, so tests
  never depend on a clean database.
- `src/test/resources/application-test.yml` forces
  `forgeflow.sandbox.provider: in-process` — no Docker needed to test.

### The scripted model

`ScriptedLlm` replaces Gemini in tests (`@Primary` in `TestLlmConfig`). You
queue up what the model will "say" — `llm.then(calls(write(...), finish(...)))`
— and it plays them back in order, recording every conversation it was shown
(`llm.seen()`). An empty script throws, like a dead provider. This is how the
self-healing loop is tested: script a broken file, a `finish`, a fix, another
`finish`, and assert the refusal the model saw named the broken file.

### Check that a test can fail

A new test that passes first time proves nothing yet. Break the behaviour on
purpose (comment out the access check, drop a field), run it, see red, put it
back. Done for: 404 vs 403, the MCP access check, chat memory, the build gate,
file authorship, live log delivery.

### SSE with MockMvc

An `SseEmitter` endpoint returns with `request.isAsyncStarted() == true`, and
whatever the emitter has sent so far is already in
`response.getContentAsString()`. So a test can open the stream, trigger
something, and read the response again to see the new event — no real server
needed.

### Access rules in one place

| Situation | Status |
|---|---|
| Not signed in | 401 |
| Signed in, no relationship to the project | **404** (same body as a missing project) |
| Member, but the role can't do this | 403 |
| Two replies at once in one chat session | 409 |
| Retry when the last reply succeeded | 409 |
| Preview with no files | 409 |

---

## 14. Billing: running it for real

Billing is **off** unless you switch it on. With `FORGEFLOW_BILLING_PROVIDER`
unset, quotas are still enforced (everyone is on FREE) but checkout answers
503.

### Locally, without Stripe

```bash
FORGEFLOW_BILLING_PROVIDER=fake ./mvnw spring-boot:run
```

Checkout then returns a link to a test page with a "Complete test payment"
button. It runs the same activation code a real webhook does. **Never set
`fake` on Render** — anyone could upgrade for free.

### With Stripe (test mode)

1. In the Stripe dashboard (test mode): create a product "ForgeFlow Pro" with a
   monthly recurring price. Copy the price id (`price_...`).
2. Copy the test secret key (`sk_test_...`).
3. Add a webhook endpoint: `https://<your-app>/api/v1/billing/webhook/stripe`,
   with events `checkout.session.completed`, `customer.subscription.created`,
   `customer.subscription.updated`, `customer.subscription.deleted`. Copy its
   signing secret (`whsec_...`).
4. Set on Render:

| Variable | Value |
|---|---|
| `FORGEFLOW_BILLING_PROVIDER` | `stripe` |
| `STRIPE_SECRET_KEY` | `sk_test_...` |
| `STRIPE_PRO_PRICE_ID` | `price_...` |
| `STRIPE_WEBHOOK_SECRET` | `whsec_...` |
| `APP_BASE_URL` | `https://forgeflow-7m08.onrender.com` |

Test card: `4242 4242 4242 4242`, any future date, any CVC.

For local webhooks, the Stripe CLI forwards them and prints its own secret:
`stripe listen --forward-to localhost:8081/api/v1/billing/webhook/stripe`.

### Gotchas

- **The webhook must read the raw body** (`@RequestBody byte[]`). Parse it into
  an object and re-serialise, and the bytes change — the signature then fails
  on genuine events.
- Stripe moved `current_period_end` from the subscription onto its items in API
  version 2025-03-31. `StripeWebhooks.periodEnd` reads both.
- Hibernate runs INSERTs before UPDATEs when it flushes. Retiring an old live
  subscription and saving a new one in the same flush would trip the
  one-live-per-user index — hence the explicit `flush()` in between.
- `Propagation.REQUIRES_NEW` plus a foreign key to a row the outer transaction
  just inserted = a deadlock the database can't see. (WORKLOG Day 10.)

---

## 15. Rate limiting and Redis

### Turning on Redis

Without `REDIS_URL` the limiter keeps its buckets in memory — fine while Render
runs one instance. To share limits across instances, create a Redis (Render's
**Key Value**, Upstash or Redis Cloud all work) and set:

| Variable | Example |
|---|---|
| `REDIS_URL` | `rediss://default:<password>@<host>:6379` |

`rediss://` (two s's) means TLS. The client handles a username, a password
and a `/<db>` suffix.

Locally: `redis-server --daemonize yes`, then
`REDIS_URL=redis://localhost:6379 ./mvnw spring-boot:run`.

### Looking at a bucket

```bash
redis-cli --scan --pattern 'ff:rl:*'
redis-cli HGETALL ff:rl:ai:user:7      # tokens left, and when it was last touched
```

Keys expire on their own once a bucket would be full again.

### RESP in five lines

| First byte | Meaning | Example |
|---|---|---|
| `+` | simple string | `+OK` |
| `-` | error | `-ERR unknown command` |
| `:` | integer | `:42` |
| `$` | bulk string (length, then bytes; `$-1` = nil) | `$5\r\nhello` |
| `*` | array of any of these | `*2\r\n:1\r\n:0` |

A command is always an array of bulk strings. The one real trap: bulk-string
lengths count **bytes**, not characters — `"héllo"` is 6.

### Gotchas

- A `Filter` that is a `@Component` gets registered twice: once by Spring
  Security (where you put it) and once by Spring Boot as a servlet filter
  (before security). Build it with `new` inside `SecurityConfig` instead.
- `RateLimitTest` gives each test its own fake client IP
  (`.with(r -> { r.setRemoteAddr(...); return r; })`), or per-IP buckets
  leak between tests.
- Test profile sets `forgeflow.ratelimit.enabled: false` — every test calls
  from 127.0.0.1 and signs up dozens of users.

---

## 16. RAG notes

### Looking inside the index

```sql
SELECT file_path, chunk_index, start_line, end_line, embedding_model,
       embedding IS NOT NULL AS has_vector, left(content, 60)
FROM file_chunks WHERE project_id = 42 ORDER BY file_path, chunk_index;
```

Or through the API: `GET /api/v1/projects/42/search?q=renderTodos`.

### pgvector from JDBC, without the pgvector library

- Write a vector as its text form, `'[0.1,0.2,...]'`, with `CAST(? AS vector)`.
  Postgres can cast any string type to any type through the type's input
  function, so a plain `String` parameter works.
- `<=>` is cosine distance (0 = same direction). `<->` is Euclidean, `<#>` is
  negative inner product.
- A `null` bound to a bare `? IS NOT NULL` fails: Postgres can't infer the
  parameter's type. Build the SQL without that half instead.

### Gotchas

- Embedding models expect to be told whether the text is a **document** being
  stored or a **query** being searched for (`RETRIEVAL_DOCUMENT` /
  `RETRIEVAL_QUERY`). Mixing them up quietly lowers result quality.
- Vectors from two different models aren't comparable at all. Each chunk
  stores `embedding_model`; changing models means a re-embed, which the
  indexer does on its own because the stored model no longer matches.
- `to_tsquery` treats `& | ! ( ) :` as syntax. User text must never reach it
  raw — `CodeIndex.keywordQuery` keeps only `[A-Za-z0-9_]` words.
- Never call an embedding API inside a `@Transactional` method: the database
  connection is held for the whole network wait.

---

## 17. Tracing, API docs, boundaries

### Following one request

Every response has an `X-Trace-Id`. Every error body has `traceId`. Then:

```bash
grep 4bf92f3577b34da6a3ce929d0e0e4736 app.log           # every line from that request
```

```sql
SELECT id, status, stop_reason FROM generation_runs WHERE trace_id = '4bf92f...';
```

### Seeing it as a timeline (Zipkin)

```bash
docker run -d -p 9411:9411 openzipkin/zipkin
ZIPKIN_URL=http://localhost:9411 ./mvnw spring-boot:run
```

Open http://localhost:9411 and search by trace id. You'll see the request,
the agent run inside it, and each model call and build inside that.

### Continuing someone else's trace

Send `traceparent: 00-<32 hex>-<16 hex>-01` and ForgeFlow joins that trace
instead of starting a new one. All-zero ids or anything malformed are
ignored (new trace), never an error.

### API docs

- http://localhost:8081/docs.html — Swagger UI. Click **Authorize** and paste
  a token from `/api/v1/auth/login` to try calls.
- Adding an endpoint? Add it to `src/main/resources/static/openapi.yaml` too,
  or `OpenApiContractTest` fails.

### Gotchas

- MDC and ThreadLocals don't cross threads. Anything submitted to an executor
  loses the trace unless wrapped in `Tracer.wrap(...)`.
- Span names must be low-cardinality: `/projects/{id}`, never
  `/projects/42`, or Zipkin's service view becomes thousands of one-off names.
- `ModuleBoundaryTest` failing on a new import usually means the code wants
  a method on the other module's *service*, not its repository.

---

## 18. The workbench and the browser walk

### Running the UI with no API key

```bash
FORGEFLOW_LLM_PROVIDER=demo FORGEFLOW_BILLING_PROVIDER=fake \
FORGEFLOW_EMBEDDER=hashing FORGEFLOW_SANDBOX_PROVIDER=in-process \
./mvnw spring-boot:run
```

Every feature works; the "model" just writes the same starter page each time.

### The browser walk

```bash
pip install playwright && playwright install chromium
python3 scripts/ui-walk.py          # screenshots in ui-walk-shots/
```

It prints `UI walk passed`, or the first step that failed and why.

### Gotchas

- **SSE + stateless security:** a finished `SseEmitter` re-dispatches the
  request (`DispatcherType.ASYNC`) through every filter. A `OncePerRequestFilter`
  JWT filter skips that pass, so Security sees an anonymous request. Permit
  `ASYNC` dispatches. MockMvc never does this dispatch — only a real server
  test shows it.
- `EventSource` can't send an `Authorization` header. Read SSE off `fetch()` and
  split frames on blank lines.
- A link can't carry a JWT either; downloads are fetched as a blob and saved
  through an object URL.
- In a `<pre>`, block-level line spans joined with `\n` render double-spaced.
- `GET /preview` answers 404 when nothing runs (by design); the browser
  console logs that as a failed resource.
- Redis writes `dump.rdb` into the directory it was started from. Start it
  from somewhere else, or `redis-cli CONFIG SET dir /tmp`.

---

## 19. Day 11 notes

### Locking /mcp

Set `FORGEFLOW_MCP_API_KEY` (any long random string) on Render. Then:

```bash
claude mcp add --transport http forgeflow https://forgeflow-7m08.onrender.com/mcp \
  --header "Authorization: Bearer <the key>"
```

`scripts/verify-mcp.sh` picks the key up from the same environment variable.

### What the agent sees about runtime errors

Every distinct `console.error` / uncaught exception the preview reported since
the last build, appended to the request:

```
RUNTIME ERRORS - reported by the browser running this project's preview since
the last build. The build check cannot see these. ...
- Uncaught TypeError: total is undefined (app.js:3)
```

A build (the agent's own, or "Run build") starts the list over.

### Is the prompt cache working?

```sql
SELECT id, prompt_tokens, cached_tokens,
       round(100.0 * cached_tokens / nullif(prompt_tokens, 0)) AS pct_cached
FROM generation_runs ORDER BY id DESC LIMIT 10;
```

Multi-round runs should show a healthy cached share; single-round runs ~0.

### Gotchas

- A background job (`cmd &`) in a non-interactive shell ignores SIGINT.
  To test Ctrl+C handling, use `timeout -s INT 9 cmd` instead.
- The eval runner logs in before every case, and logins are rate-limited
  (10/min per IP). It now waits out 429s; with the default 75s pause it never
  hits them.
- **SSE event ids must survive restarts.** If they double as "resume after"
  cursors (`Last-Event-ID`), a restart that numbers from 1 again silently
  hides everything new. Seed them from the clock, and treat an id from the
  future as "send everything".
- Proxies drop idle connections. A long-lived SSE stream needs a periodic
  comment line (`: keep-alive`) even when there's nothing to say.
- **Sliding sessions.** Tokens last `forgeflow.jwt.expiry-minutes` (120).
  Past halfway, any authenticated response carries `X-Auth-Token` with a
  replacement; `app.js` stores it. Renewal stops `max-session-days` (30)
  after the real sign-in, carried in the `auth_time` claim. Free-tier Render
  also sleeps after ~15 idle minutes; the first request after that takes a
  minute or two while it wakes — that's not a crash.
- **The model follows the first rule it reads.** The prompt said "code
  reaches the user only by calling write_file" and, later, "prefer edit_file".
  Gemini rewrote whole files. When a prompt, a tool description and a tool
  result disagree, fix all three — and the tool *result* is the strongest
  lever mid-run, because the model reads it right before its next move.
- **Which tool did a run actually use?** The assistant message stores it:
  `GET /api/v1/projects/{id}/chat/sessions/{sid}/messages` → `toolCalls`.
  In SQL:

  ```sql
  SELECT id, tool_calls, tokens_used FROM chat_messages
  WHERE role = 'assistant' ORDER BY id DESC LIMIT 10;
  ```
- **Evals and plan limits.** The eval account is on the Free plan: 3
  projects, 200k AI tokens per UTC day (resets 05:30 IST). The runner
  deletes each project after scoring and stops on the token limit. A full
  20-case sweep uses roughly 150–170k tokens (more with the five
  follow-ups), so run it once a day at most, or use `--limit`.
- **Reading an eval run.** The summary's `how they changed` line counts the
  follow-ups: `edit` = edit_file only (what we want for small changes),
  `rewrite` = whole files resent, `mixed` = both. Per case, the results
  JSON has `followup.toolUsage`. The demo model always rewrites — only a
  real-model run means anything here.
- **Lost password?** There's no reset flow yet (no email sender). Sign up
  again — Gmail ignores everything after a `+`, so `you+ff@gmail.com` is a
  new ForgeFlow account that still reaches your inbox.

