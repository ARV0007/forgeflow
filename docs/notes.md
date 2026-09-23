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
