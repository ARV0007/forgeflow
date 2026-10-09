# ForgeFlow — Work Log

One entry per day. Each one answers five questions: what got built, why it was
built that way, what concept it taught, how I'd say it in an interview, and
what went wrong and how it got fixed.

The "Mistake & fix" sections are deliberately honest. Most of the time lost on
this project went to tooling, not to the agent — and knowing *why* something
broke is worth more in an interview than pretending it didn't.

---

## Day 1 — Skeleton that runs · 10–11 Sep 2026

**Built.** A fresh repo with Spring Boot 4.1.1 on Java 21, Postgres 16 with
pgvector running in Docker, and a Flyway migration (`V1__init.sql`) that creates
nine tables: `users`, `projects`, `project_files`, `chat_sessions`,
`chat_messages`, `generation_runs`, `tool_calls`, `file_chunks`, `previews`.
Health check answers `{"status":"UP"}` on port 8081.

**Why.**
- Postgres runs on host port **5433** and the app on **8081**, because Transakt
  already owns 5432 and 8080. No debugging port collisions on day one.
- The Docker image is `pgvector/pgvector:pg16`, not plain `postgres:16`, because
  the migration's first line is `CREATE EXTENSION vector` — and that extension
  has to exist in the image before the migration can create it.
- `ddl-auto: validate`. Flyway owns the schema; Hibernate only checks that the
  entities match it. If they disagree, the app refuses to start.
- The whole schema went in on day one, including tables that won't be used for
  a week. Designing the data model first forced the architecture decisions
  early.

**Concepts.** Flyway runs at startup, so a failed boot means no migration ran at
all. A failed migration statement rolls back the whole migration.

**Interview line.** *"I designed the full schema up front, including a
`generation_runs` table that became my observability, quota and evaluation
store all at once — one table, three jobs."*

**Mistake & fix.** Almost the entire day went on file transfer, not Spring:
- Files created in IntelliJ were **empty** — created, but nothing pasted in.
- Downloads never happened, and a **stale Transakt `architecture.md`** was
  sitting in `~/Downloads` and got copied into the new repo. Caught and deleted.
- **Heredocs broke.** Pastes arrived with leading spaces, so the closing `EOF`
  wasn't at column 0, the shell never saw the end, and the heredoc swallowed the
  *next* paste as text. `cat > pom.xml << 'EOF'` ended up inside `pom.xml`.
- Spring Initializr returned a **189-byte error** instead of a zip when asked for
  Boot 3.4.1 — that version had been dropped. Let it choose instead: **Boot
  4.1.1**.
- A `sed '1d'` meant to remove a stray line from `V1__init.sql` deleted
  `CREATE EXTENSION vector` instead, so Flyway failed with *type "vector" does
  not exist*. Put it back, `docker compose down -v`, clean restart.
- Port 8081 still held by an earlier run. `lsof -ti :8081 | xargs kill`.

The fix for the whole class of paste problems came on Day 2: **base64-encoded
single-line commands**. No newlines, so nothing for indentation to break.

---

## Day 2 — Auth and projects · 12 Sep 2026

**Built.**
- BCrypt signup and login, JWT issue and verify (jjwt 0.13.0), a
  `SecurityConfig`, a `JwtAuthFilter`.
- Project CRUD — create, list, get, update, soft delete — every query scoped to
  the caller.

**Why.**
- **Login fails identically** for a wrong password and an unknown email. Same
  status, byte-identical body. Otherwise login becomes a tool for discovering
  who has an account.
- **The JWT subject is the user ID, not the email.** Emails change; IDs don't.
- **`ownerId` is not a field on `CreateProjectRequest`.** A client that sends
  `"ownerId": 999` isn't rejected — the value simply has nowhere to land, and the
  project is created under the caller's ID from the token. Making the lie
  impossible beats validating against it.
- **Someone else's project returns 404, not 403**, with the same message as a
  project that doesn't exist. Probing IDs teaches an attacker nothing.
- **Ownership is baked into the query** — `findByIdAndOwnerIdAndDeletedAtIsNull`
  — so "not yours" and "doesn't exist" are literally the same database result.
- `POST /projects` returns **201 with a `Location` header**, not a bare 200.
- `/error` is the **first** matcher in `SecurityConfig`, permitted. That line is
  Transakt's three-day outage written down: Tomcat re-dispatches errors to
  `/error`, the re-dispatch arrives anonymous, and without this a real 500
  comes back as an empty 403.

**Concepts.** 401 means "who are you"; 403 means "I know who you are, and no."
Spring Security picks an `AuthenticationEntryPoint` based on what you
configured — with no `httpBasic()` or `formLogin()`, it falls back to
`Http403ForbiddenEntryPoint`.

**Interview line.** *"Unauthorised access to another user's project returns 404
with the same message as a missing one — the ownership check is part of the
query itself, so the two cases are genuinely indistinguishable."*

**Mistake & fix.** An anonymous request came back **403** instead of 401. Cause:
no entry point configured, so Spring used its 403 fallback. Fix: an explicit
`authenticationEntryPoint` that sends 401. Verified: anonymous → 401, valid
token → 200, garbage token → 401 (not 500, because the filter catches a bad
token and stays anonymous instead of throwing).

---

## Day 3 — The agent loop · 12–14 Sep 2026

**Built.** The core of the project. `POST /api/v1/projects/{id}/generate` takes
a prompt; an agent plans, calls tools to write files into `project_files`, and
calls `finish` when done. First run: **SUCCEEDED, 3 files, 4 tool calls, 7,102
tokens, 15.4 seconds.**

**Why.**
- **No Spring AI.** Its stable line targets Boot 3; Boot 4 needs a 2.x milestone
  from Spring's milestone repo. Instead, a one-method `LlmClient` interface with
  a `GeminiClient` over the JDK's own `java.net.http.HttpClient`. Zero
  dependency risk, and the request shape stays visible.
- **The model calls tools; it never writes code as prose.** Four tools:
  `list_files`, `read_file`, `write_file`, `finish`. There is no
  `run_arbitrary_command` — the allowlist *is* the security boundary.
- **A path guard** in `AgentTools` rejects `..`, strips leading slashes, and
  refuses null bytes. Every path the model writes comes from arbitrary user
  text, so it's hostile until proven otherwise.
- **A rejected tool call returns `ERROR: reason`**, not an exception. The model
  reads it as an observation and corrects itself.
- **Every exit records a `stop_reason`:** `FINISH_TOOL`, `MAX_TOOL_CALLS`,
  `TIMEOUT`, `TOKEN_BUDGET`, `NO_TOOL_CALL`, `ERROR`.
- **Model turns are echoed back verbatim** (`rawModelParts`), because Gemini
  2.5 attaches a `thoughtSignature` to each part. Rebuild the turn from your own
  fields and the model loses its reasoning between rounds.

**Concepts.** `totalTokenCount` is **not** prompt + completion. On run 1:
5,461 + 1,258 = 6,719, but the total was 7,102. The gap is *thinking* tokens,
billed on top. Always use the total the API reports.

**Interview line.** *"I implemented the tool-calling loop directly against the
raw API rather than a framework, so I can explain exactly how a turn works —
including why the model's own turn has to be echoed back byte for byte."*

**Mistake & fix.**
- **Jackson 3.** Boot 4 ships Jackson 3, whose package is `tools.jackson`, not
  `com.fasterxml.jackson` — seven compile errors, one cause. And
  `JsonNode.fieldNames()` is now `propertyNames()`, returning a `Set`, so
  `forEach` instead of `forEachRemaining`.
- **YAML indentation.** Earlier sed damage had left the whole `forgeflow:` block
  one level too deep. `llm:` happened to land where Spring could see it; `jwt:`
  didn't. Error: *Could not resolve placeholder `forgeflow.jwt.secret`*. Fixed
  by rewriting `application.yml` whole rather than patching it.
- **The API key.** Copying each command from the chat overwrote the key on the
  clipboard, three times. Fix: copy the key, then *type* `pbpaste > k.txt` by
  hand. Also, my assumption that keys start `AIza` and run 39 characters was
  wrong — Google changed the format. Testing the key with a real API call ended
  the guessing.
- **A 13KB paste got mangled.** Split into chunks under ~4.4KB.

**Quality signal worth remembering:** `app.js` on run 1 was **46 bytes**. The
system prompt said to keep JS in `app.js`, so the model created the file with
nothing to put in it. Not a bug — exactly the kind of thing only a measured
eval notices.

---

## Day 4 — Streaming · 14–18 Sep 2026

**Built.** `POST /api/v1/projects/{id}/generate/stream` — Server-Sent Events.
You watch the run happen: `status`, `thinking`, `tool`, `file`, `done`.

**Why.**
- **We stream our own events, not the model's tokens.** Tested Gemini's
  `streamGenerateContent`: a function call arrived as **one single chunk**. A
  tool call can't be half-emitted — the model has to produce the whole JSON
  before it means anything. So the unit of progress in an agent is a completed
  tool call.
- **Ownership is checked on the request thread**, before the emitter exists.
  Check it inside the background task and a forbidden request gets a 200 with
  an error event instead of a clean 404.
- **Virtual threads** run each generation. A run spends nearly all its time
  blocked on the model; a platform thread per run would be almost pure waste.
- **A listener that throws can't kill the run.** A browser closing its tab isn't
  a reason to abandon work in progress.

**Concepts.** Once an SSE response has started, Spring can't send an error page
anymore — *"Cannot render error page... response has already been committed."*
Errors have to travel as events.

**Interview line.** *"I don't stream model tokens — a tool call arrives whole,
so the meaningful progress event in an agent is a completed tool call, not a
token."*

**Mistake & fix.**
- **404 on the new endpoint.** The app had been running for three days across
  Mac sleep — it was still Day 3 code. And separately, the install command had
  never actually run. Checked the files existed, reinstalled, restarted.
- **`RESOURCE_EXHAUSTED`.** Gemini's free tier allows **5 requests per minute**
  per model. A multi-round run burns through that in seconds, and the run died.
  That became the retry feature below.

**Also seen:** run 3 opened with `read_file index.html` — the system prompt's
"read before you edit" rule working. But it ended `NO_TOOL_CALL`: the model
wrote a prose summary instead of calling `finish`. Recorded as its own stop
reason on purpose; Day 6 made it matter less.

---

## Retry with backoff · 22 Sep 2026

**Built.** `GeminiClient.sendWithRetry` — retries 429 and 5xx up to 5 times.

**Why.**
- **Honours Google's `retryDelay`.** The 429 body says exactly how long to wait
  (e.g. `6.9s`). Falls back to exponential backoff (2s, 4s, 8s…) when it's
  absent.
- **Jitter** — a random extra 0–500ms — so concurrent runs don't retry in
  lockstep and hit the limit together again.
- **Other 4xx are not retried.** A malformed request is just as malformed the
  second time.

**Interview line.** *"On the free tier, hitting the rate limit mid-run is normal
operation, not an exception — so the client honours the server's own retry
delay instead of guessing."*

**Mistake & fix.**
- **The Mac rebooted** and `/tmp` was wiped, taking two paste chunks with it.
- **I garbled a chunk.** It ended in a long repeating run (`67Ee67Ee…`) and I
  miscounted a repeat. From then on, **every chunk ships with an MD5**, so a bad
  one is caught in one command instead of surfacing as a mystery tar error.
- The Java file had extracted fine anyway: it was first in the archive, and the
  corruption was in the tail. And the new YAML keys turned out to be optional —
  `@Value("${forgeflow.llm.max-retries:5}")` carries its own default.

---

## Day 5 — The sandbox · 22 Sep 2026

**Built.** `SandboxProvider` with a `DockerSandboxProvider`.
- `POST /projects/{id}/build` — verifies the project in a locked-down container.
- `POST /projects/{id}/preview` — serves it with nginx and returns a URL.

Results: build passed in **224ms**; preview at `127.0.0.1:61559`; a planted
`broken.js` failed in 163ms with **`broken.js: SyntaxError: Unexpected
number`**.

**Why.**
- **Two containers, two threat models.** The build container processes
  generated code, so it gets everything: `--network=none`, `--read-only`,
  non-root user, `--cap-drop=ALL`, `no-new-privileges`, memory, CPU and PID
  limits, a hard timeout. The preview container only serves bytes — the
  generated JS runs in the viewer's browser, inside the browser's own sandbox —
  so it gets a lighter lockdown and binds to **127.0.0.1 only**.
- **The check compiles, never executes.** `check.js` uses Node's `vm.Script` to
  parse each JS file without running it, and verifies every local `src=`/`href=`
  in the HTML points at a real file.
- **A second path guard**, independent of the first. `AgentTools` protects the
  database; `DockerSandboxProvider.writeFiles` protects the host filesystem,
  because that's where a path turns into a real file on a real disk.
- **Killing the `docker` CLI does not kill the container.** On timeout we
  `docker rm -f` by name, or a runaway build keeps burning CPU.
- **Output is drained on a separate thread.** A child process that fills its
  pipe buffer blocks forever if you only read after `waitFor()`.
- **Previews don't use `--rm`**, so a container that fails to start still has
  logs to read.
- **`docker` CLI via `ProcessBuilder`**, not the docker-java library — same
  reasoning as skipping Spring AI.
- **Static HTML was the right target.** 224ms per build. A React build would
  spend 60+ seconds on `npm install` alone.

**Interview line.** *"The build container and the preview container have
different threat models, so they have different lockdowns — the one that
processes generated code gets no network, no root and no capabilities; the one
that only serves files binds to localhost."*

**Mistake & fix.** After a reboot, Docker Desktop wasn't running and the
Postgres container was down. The login command failed with *Expecting value* —
but that was downstream: the app had died at startup on a Hikari connection
failure. Order after any reboot: Docker Desktop → `docker compose up -d` → app.

---

## Day 6 — Self-healing · 22 Sep 2026

**Built.** The build now runs **inside the `finish` tool**. A failing build comes
back to the model as an error listing each problem; the model fixes it and calls
`finish` again. Up to 3 repair rounds.

**The demo.** A syntax error was planted in `broken.js`. The prompt asked only
for *"a footer showing the current year."* The model built the footer, called
`finish`, was stopped by the gate, read the error, fixed a file it had never
touched and wasn't asked about, and only then got out. Its own summary:
*"Added a footer … and fixed a syntax error in broken.js."*
**SUCCEEDED · 1 repair round · build passed · 8 tool calls · 113.9s.**

**Why.**
- **The gate lives inside `finish`,** not after the loop. The naive version
  sends a new "please fix this" message after the model finishes — but the
  conversation already ended on a user turn, and two user turns in a row can be
  rejected. As a tool result, the build failure is just another observation.
- **The model can't skip verification**, because the only way out goes through
  it. "Finished" stopped meaning "the model says it's done" and started meaning
  "the code works."
- **Status is the outcome; `stop_reason` is the mechanism.** A run that ended
  `NO_TOOL_CALL` but passed the build is `SUCCEEDED` — while `NO_TOOL_CALL`
  stays visible for the eval harness.
- **The `NO_TOOL_CALL` path is verified too.** Stop without calling `finish` and
  the build still runs.

**Interview line.** *"I put the build check inside the finish tool, so the only
way for the agent to end a run is to produce code that actually passes — and a
failure comes back as a tool result it can act on, not a dead end."*

**Mistake & fix.** 113.9 seconds would have blown the 90-second timeout — the
free tier's rate limit was stretching the run. Raised the default to **240s**.
On a paid key, the same run is around 20 seconds.

---

## Day 7 — The workbench · 23 Sep 2026

**Goal.** Stop demoing ForgeFlow with curl. A person should be able to type a
prompt and watch the site get built.

**What was built.** A workbench UI in plain HTML, CSS and JavaScript, served
straight out of Spring Boot's own `static/` resources. No separate frontend
build, no second deployment, no framework.

The left pane is the interesting half: a live **run record** of the SSE events
as they arrive — status, thinking, each tool call, each file written, done. The
self-healing loop is the thing that makes this project unusual, and it was
previously invisible unless you read JSON. Now you watch the agent get stopped
by the build check and go back to fix its own work.

**Interview line.** *"The UI exists to make the loop visible. A build that
fails and gets repaired is the whole point of the system, and you cannot see
that in a final screenshot — only in the sequence."*

**Why no framework.** One deployable, one language at the boundary, and the
static files carry no data of their own: every call they make still needs a
token. A React app would have added a build step and a second thing to deploy
in exchange for nothing this page needs.

---

## Day 8 — Live · 25 Sep 2026

**Goal.** A public URL. "Not just the backend part."

**Where it runs.** Render free tier, Docker runtime, Singapore, against a new
Neon Postgres project in the same region.

**The constraint that shaped it.** Free hosting tiers cannot run a Docker
daemon, and the sandbox depends on one. So `SandboxProvider` gained a second
implementation chosen by config: `FORGEFLOW_SANDBOX_PROVIDER=in-process`
validates in the JVM and serves previews from Spring at `/p/{token}/` instead
of nginx. The deployed build check is therefore **structural only** — it parses
and checks references, but nothing executes in a locked-down container.

That is a real reduction in guarantee, and it is config, not a code fork: the
interface was already the seam.

**Two blockers on the way.**

1. The main class file was `ForgeFlowApplication.java` while the class inside
   was `ForgeflowApplication`. macOS's case-insensitive filesystem had hidden
   this for weeks; Linux did not. Fixed with `git mv`. Same species of bug as
   the Transakt `Context.md`/`CONTEXT.md` case — one string, two layers,
   different rules.
2. Lombok came out of all five entities in favour of explicit getters and
   setters. `annotationProcessorPaths` carries no version from dependency
   management, and it behaved differently on Render than locally. Explicit
   accessors are more lines and no mystery.

**Interview line.** *"The deploy didn't need a rewrite because the sandbox was
already behind an interface. It needed a second implementation and one
environment variable."*

---

## Day 9 — Evals, and a model that quietly died · 25–27 Sep 2026

**Goal.** Turn *"it works"* into a number.

### The harness

`evals/golden.json` holds twenty fixed prompts. `evals/run_evals.py` runs each
against a **fresh project** — accumulated files would let a later case pass on
an earlier case's work — and scores four checks, all of which must pass:

| check | what it catches |
|---|---|
| `build_passed` | the build gate approved it |
| `has_index` | there is an entry point |
| `no_empty_files` | nothing under 20 bytes |
| `js_when_needed` | a prompt asking for behaviour produced ≥100 bytes of JS |

The fourth exists because of an observed failure: the agent would write a
near-empty `app.js` purely to satisfy the system prompt's file convention,
passing the first three checks while producing something that does nothing.
**Every check here is traceable to a failure actually seen.**

The harness re-authenticates every case, because tokens expire in two hours and
a full sweep takes longer than that.

### The model migration

`gemini-2.5-flash-lite` started returning 404 with an explicit message: *no
longer available to new users, use `gemini-3.5-flash-lite`.* `gemini-2.5-flash`
was on the same line and the deployed demo was sitting on it.

Four probes, in increasing strictness:

1. `GET /v1beta/models` — what the key can see. Stopped guessing names.
2. POST `"hi"` — reachable and callable, separating 200 from 403/404.
3. The same call with a real `functionDeclarations` block, grepping the reply
   for `functionCall`. A model that chats is not necessarily a model that emits
   structured tool calls, and the agent needs the latter.
4. `time curl` — latency.

| model | reachable | tool call | latency |
|---|---|---|---|
| `gemini-2.5-flash` | 200 | yes | 3.6s |
| `gemini-2.5-flash-lite` | 404 | — | deprecated for new keys |
| `gemini-3.1-flash-lite` | 200 | yes | **1.7s** |
| `gemini-flash-lite-latest` | 200 | yes | 1.9s |
| `gemini-3.5-flash-lite` | 200 | yes | **94s, then 134s** |
| `gemini-3.5-flash` | 503 | yes on retry | intermittent |
| `gemini-3.8-flash` | 200 | yes | 10.3s |

**Pinned `gemini-3.1-flash-lite`.** Google's own named successor,
`gemini-3.5-flash-lite`, passed both the reachability and the tool-call probe
and was **rejected on latency** — 90 to 134 seconds for a three-line haiku,
which blows the client's HTTP timeout on the first call of a run.

**The lesson worth keeping: latency is a capability, and neither of the first
two probes measured it.** A floating alias (`-latest`) was also rejected: it
was observed inheriting a 503 from its target in the same session, and it would
inherit the latency problem too.

### Verification

20/20 on the golden set, mean 5,236 tokens, mean 13.4s per case, all four
checks green. The single-prompt comparison: 4,380 tokens / 8.4s against the Day
3 baseline of 7,102 tokens / 15.4s on 2.5-flash — roughly half the tokens and
half the wall time. Self-healing re-verified on the new model: asked to write
deliberately invalid JavaScript, the agent wrote it, failed its own build, read
the error and repaired it (1 repair round, build passed).

### The head-to-head

Flipping back to `gemini-2.5-flash` the same day, same machine, same prompts:

| case | 2.5-flash | 3.1-flash-lite |
|---|---|---|
| 1 recipe-cards | PASS 13s | PASS 7s |
| 4 todo-list | PASS 20s | PASS 11s |
| 5 portfolio | FAIL 17s (`no_empty_files`) | PASS 10s |
| 6 pricing-table | FAIL 230s | PASS 11s |
| 7 contact-form | FAIL 285s (0 files) | PASS 10s |
| 9 faq-accordion | FAIL 583s (0 files) | PASS 7s |
| full suite | **abandoned** | **20/20** |

The shape matters more than any number. The first four cases pass normally;
from case 5 onward each is slower than the last, ending at 583 seconds —
nearly ten minutes — producing nothing. That is a **progressive collapse, not
constant slowness**, and the likeliest cause is free-tier quota rather than raw
latency: 2.5-flash allows around 10 requests a minute, each case makes 4–6
calls, and after the allowance is spent `sendWithRetry` backs off until the HTTP
timeout fires.

**Stated honestly:** this does not cleanly separate "2.5-flash is degraded" from
"2.5-flash has a tighter quota than 3.1-flash-lite". Both hypotheses produce
that curve. Separating them would need per-call HTTP status logging during a
throttled sweep. For the decision at hand it does not matter — the old model
cannot finish the suite and the new one can.

**The part worth sitting with.** An eval run from 26 Sep at 01:16 scored 1/20
with a 119.2s mean. Same collapse, recorded automatically, a full day before
anyone went looking. **The harness caught the regression first and it made no
difference, because nothing was watching.** That is the gap between having a
test suite and having CI.

**Mistake & fix.** A config bug found the same day: `llm.timeout-seconds` was
wired to `HttpClient.connectTimeout` while the *request* timeout was hardcoded
to 120s. The property that looked like it governed slow generations governed the
one that almost never matters. Split into `connect-timeout-seconds: 10` and
`request-timeout-seconds: 60`, and verified by inversion — at 1 second a
generation fails in 297ms; at 60 the same generation succeeds in 6.1s. A config
fix is not done until the knob is shown to move something.

---

## Day 10 — Redesign to the spec, part 1 · 9 Oct 2026

The brief changed shape. The Lovable-clone spec lists features by area —
Projects, Auth, AI generation, Files, Preview — plus an ER diagram (members with
roles, chat sessions and messages, plans, subscriptions, usage logs) and a
service diagram (gateway, Kafka, Qdrant, MinIO, Kubernetes). ForgeFlow had the
hard part — the self-healing agent loop — and almost none of the product around
it: one owner per project, no conversation memory, no zip download, no logs.

### The ground rule: no new dependencies

The build machine can't reach Maven Central, so every library has to already be
in the local Maven cache. That sounds like a handicap and turned out to be a
design discipline. Redis gets spoken to directly over its wire protocol, Stripe
over plain HTTP, and the spec's service boxes become **modules inside one
application** with the same seams. Each place a real service would go
(Kafka, MinIO, Qdrant) gets a note in the code saying where the cut would be.

### CI first, and Lombok out

Day 9 ended with "the eval harness caught a regression and nobody was
watching". So before any feature: a GitHub Actions workflow running the whole
test suite against a real `pgvector/pgvector:pg16` Postgres on every push. Green
on the first run. Lombok was declared, wired into the compiler, and used by
nothing — removed.

### Phase 1 — who may do what

`project_members` with EDITOR / VIEWER roles, public projects, and `GET/PATCH
/me`. Every endpoint now asks one question — `ProjectAccess.require(project,
user, permission)` — instead of comparing owner ids by hand.

The rule that matters most: **a stranger gets 404, a member without the right
role gets 403.** A stranger shouldn't learn that a project id exists; a viewer
already knows it does, so telling them "forbidden" leaks nothing and is more
useful than "not found".

**Mistake & fix (mine).** While moving every caller to the new check I found the
MCP `generate_app` tool had **no access check at all** — any MCP client could
generate into any project by id. It also ran everything as user id 1, which
would have been whoever signed up first on a fresh database. Fixed with a real
check and a dedicated passwordless service account, plus a regression test that
fails if the check is ever removed.

### Phase 2 — conversation memory

The spec's five AI-generation features: list sessions, create a session, load
full history, chat stream, retry if failed. `chat_sessions` and
`chat_messages` had existed since V1 and nothing had ever written to them.

The interesting part is what the model gets to remember. The last ten messages
go in, as alternating user / model turns — and Gemini **rejects** a
conversation that doesn't alternate or that opens with the model. So the
window drops a leading reply and collapses a failure-then-retry pair to the
later one. Past replies are remembered with the files they wrote
(`[Files written: index.html, app.js]`), so "make the header blue" knows which
file the header is in.

One reply at a time per session (`SessionLocks`): two quick sends would
otherwise both read the same history and append two replies in a row.

Before any of this, the agent loop got real tests: a **scripted model** that
plays back canned tool calls, so the self-healing gate is verified on every
push instead of by hand.

### Phase 3 — files and preview

- **Zip download** of the whole project, under one folder named after it.
- **Who wrote each file** — `created_by` / `updated_by`. The agent's writes
  count as the requesting user's: the model is their tool, not an author.
- **Get Preview** — what's running, and when it expires. An expired preview is
  closed lazily the next time anyone asks.
- **Logs stream** — builds, preview start/stop, every file the preview served
  (and every 404), and the generated app's **own console output**. That last
  one works by injecting a tiny script into served HTML that forwards
  `console.*` and uncaught errors back to the server. It's the closest thing a
  static site has to a dev-server terminal.

`ProjectFileService` is now the only code that touches file contents, which
pays off two open items at once.

**Tests:** 58, every one against a real Postgres. Before trusting a new suite I
break the behaviour on purpose and check it goes red — dropping `updated_by`
and breaking live log delivery each failed it, as they should.

---

## Open items

- `cost_usd` and `cached_tokens` are always 0.
- The agent timeout counts time spent waiting out rate limits.
- `run_evals.py` writes its results file only after the whole loop, so a
  `Ctrl+C` mid-sweep loses every completed case.
- `/mcp` is unauthenticated and runs as one service account. Acceptable
  locally; a decision to make before the endpoint is advertised publicly.
- Session locks and preview logs live in memory — correct for one instance
  (Render runs one), wrong the moment there are two.
- The live Gemini path hasn't been re-verified on Render since the redesign
  started; the tests use a scripted model.

## Still to build (redesign, in order)

1. Plans and quotas — FREE / PRO, projects, tokens per day, previews; Stripe
   checkout and a signed webhook
2. Rate limiting on Redis
3. RAG over the project's own code, on pgvector
4. A `code.generated` event (the Kafka seam), request tracing, an OpenAPI page
5. A chat-shaped workbench UI
6. `edit_file` + prompt caching
7. README

## Done since the original plan

- Evals — twenty golden prompts, a measured pass rate, and a model migration
  decided on its evidence (Day 9)
- Deploy — live on Render with a config-selected sandbox provider (Day 8)
- Frontend — a workbench that makes the self-healing loop visible (Day 7)
- MCP server — ForgeFlow drivable by another agent
- CI — every push runs the suite against a real Postgres (Day 10)
- Members and roles, chat memory, zip download, logs stream (Day 10)
