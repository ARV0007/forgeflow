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

### Phase 4 — plans, quotas, Stripe

FREE and PRO, enforced in three places: projects owned, previews running, AI
tokens per day. Hitting a limit is a **402 Payment Required** with the numbers
in the body (`quota`, `limit`, `used`, `plan`), so a UI can say "3 of 3
projects — upgrade?" without guessing.

Two decisions worth remembering:

- **Who pays for tokens in a shared project?** Whoever typed the prompt. If the
  owner paid, inviting someone would let them spend your allowance.
- **The token limit is soft.** It's checked before a run, and a run that starts
  under the limit finishes. Cutting a generation off halfway leaves a
  half-written app, which is worse than going a little over.

Stripe without the SDK: Checkout is one form-encoded POST, and the webhook
signature is twenty lines of HMAC. **The plan is only ever granted by the
signed webhook** — never by the browser landing on the success URL, which
anyone could visit by hand.

**Mistake & fix (mine, caught before commit).** The usage logger first ran in
its own transaction (`REQUIRES_NEW`), so a failed run's tokens would still be
recorded. But a new project's usage row has a foreign key to the project row,
which isn't committed yet — the second connection would wait on the first,
which is waiting on the second. A deadlock Postgres can't detect, because both
halves are the same app. It now joins the caller's transaction.

**Mistake & fix (caught by a test).** Webhook events can arrive out of order,
so each subscription remembers the newest event it applied and ignores older
ones. My first version used "now" when an event had no timestamp — which made
that event look newer than everything after it. A test with a missing
timestamp went red; an untimestamped event now just doesn't take part in
ordering.

81 tests. Sabotage-checked: skipping the signature check, the event ordering,
and the preview-restart allowance each turn the suite red.

### Phase 5 — rate limiting

Quotas cap how much someone can use in a day; rate limits cap how *fast*. A
script firing a hundred generate requests in a second wouldn't break its
daily quota for a while, but it would hit Gemini's per-minute limit for
everybody.

**Token bucket, not a counter per minute.** "10 per minute" means a burst of
10, then one more every six seconds. A fixed-window counter would allow 10 at
12:00:59 and another 10 at 12:01:00 — twenty in two seconds.

**Redis, without a Redis library.** The Redis protocol (RESP) turned out to be
small enough to implement in one class: a request is an array of strings, a
reply is one of five types marked by its first byte. The bucket itself is a
Lua script, because Redis runs a script atomically — "read the bucket, decide,
write it back" as three separate commands would let two servers both spend
the last token.

**When Redis is down**, the limiter quietly switches to the same bucket in
memory. That limits per server instead of across all of them. Blocking
everyone (fail closed) or letting everything through (fail open) would both be
worse.

**Placement mattered.** The filter has to run *after* the JWT filter, or it
can't tell users apart. And it can't be a `@Component`, because Spring Boot
would also register it as a plain servlet filter — one that runs before
security, sees everyone as anonymous, and lumps every user together. A test
that moves it before the JWT filter goes red.

94 tests; CI now runs a Redis container too.

### Phase 6 — RAG over the project's own code

The spec draws Qdrant. The vectors went into Postgres instead — pgvector was
already installed, and V1 had already sketched a `file_chunks` table with a
`vector(768)` column and a full-text `tsv` column side by side. One database
means a chunk and its file can never disagree about whether the other exists.

**Hybrid search, because code is full of exact names.** Embeddings are good
at meaning ("where's the dark mode toggle?") and unreliable at identifiers —
`renderTodos` might rank below a chunk that's merely *about* rendering.
Keyword search is the opposite. Both run, and Reciprocal Rank Fusion merges
them by position, so a chunk ranked well by both wins.

**Incremental indexing.** Each file's SHA-256 is stored with its chunks; a
re-index only re-embeds files whose hash changed. After a run, a background
listener re-indexes; before every search, the index re-checks itself anyway,
so freshness never depends on the listener.

**When the agent uses it.** Up to 15 files, nothing changes — the agent can
read the whole project. Past that, each request arrives with the most relevant
excerpts attached, and the agent has a `search_code` tool for anything else.

**The `code.generated` event exists now.** `AgentService` publishes it after
every run that changed files; the indexer is its first listener. That's the
seam where Kafka would plug in — the publisher doesn't know who's listening.

108 tests. Tests use an offline "hashing" embedder: deterministic, no network,
and honest that it only knows about shared words.

### Phase 7 — tracing, an API reference, and enforced boundaries

**Tracing.** Every request gets a trace id (or continues the caller's, from a
W3C `traceparent` header), returned in `X-Trace-Id` and printed on every log
line it causes. Inside it: spans for the agent run, each model call, each
build and each indexing pass. Set `ZIPKIN_URL` and they appear in Zipkin as a
timeline; leave it unset and you still get ids in headers, logs, error bodies,
and on every `generation_runs` row. No tracing library — only the API jar was
in the cache — so it's a ThreadLocal, the logging MDC, and a background
batcher that drops spans rather than piling them up when Zipkin is down.

**OpenAPI, kept honest.** A hand-written `openapi.yaml` with a Swagger UI page
at `/docs.html`. Hand-written docs drift, so a test compares the document
against every route Spring actually serves; adding an endpoint without
documenting it now fails the build.

**Module boundaries, enforced.** `architecture.md` has said since Day 1 that
modules talk through service classes and never touch each other's tables. A
new test reads the source and fails on any import that breaks that. It found
one straight away: three modules imported billing's `UsageLog` *entity* just
for its string constants. Moved to a plain `UsageKind` class.

122 tests.

### Phase 8 — the workbench, and the bug only a browser could find

Everything from Phase 1 on was API-only. The workbench is now chat-shaped:
sessions and the conversation on the left, with each reply streaming in as a
live list of what the agent is doing; preview, code, live logs and search on
the right; sharing and a plan badge on top.

To test it in a real browser without an API key, ForgeFlow gained a **demo
model** (`FORGEFLOW_LLM_PROVIDER=demo`). It writes the same small page for any
request, but through the real pipeline, so every screen works. A Playwright
script (`scripts/ui-walk.py`) then drives the whole product: sign up, chat,
reload, preview, click inside the generated app, see that click's console line
in Logs, search, download, upgrade, share, quota, phone-sized screen.

**The bug it found.** The first run failed at step two: the reply never
appeared. The server log said the run had *succeeded* — and then
`AccessDenied`. When a streamed response finishes, Tomcat sends the request
back through every filter one more time (an "async dispatch"). The JWT filter
only runs once per request, so on that last pass nobody was logged in, and
Spring Security threw an error onto a response that was already half sent.
The browser saw a broken stream.

It had very likely been happening in production since streaming was added in
Day 4. The old UI quietly ignored the broken ending; the new one couldn't.
None of the 122 tests caught it, because MockMvc never performs that final
dispatch. The fix is one line (let async dispatches through — they're the
tail end of a request that was already authorised). The regression test runs
a **real Tomcat** and reads the stream over a socket; it fails with "EOF
reached while reading" without the fix.

**Smaller things the walk-through caught:** code shown double-spaced, line
numbers almost invisible, log timestamps wrapping, an empty plan badge before
it loaded. And one I caused myself: a broad `git add` committed the local
Redis server's snapshot file. Removed and ignored; I check `git status` before
staging now.

Also: a README at last, a `.env.example` that actually works (the old one had
the shell command that created it pasted inside it), and Redis in
`docker-compose.yml`.

123 tests + the browser walk.

**Tests:** 58, every one against a real Postgres. Before trusting a new suite I
break the behaviour on purpose and check it goes red — dropping `updated_by`
and breaking live log delivery each failed it, as they should.

---

## Day 11 — Closing the loops · 10 Oct 2026

Three things from "Still to build", and three open items closed.

### edit_file

Until now, changing one line meant the agent rewrote the whole file — paying
for every line in output tokens, and risking silently dropping parts it never
meant to touch, including anything the user changed by hand. `edit_file` takes
an exact piece of text and its replacement. The text must appear **exactly
once**; if it doesn't, the agent is told why (not found → copy it from
`read_file`; found 3 times → include more surrounding lines) and corrects
itself, the same way it handles a rejected path. Fuzzy matching was the
tempting alternative and the wrong one: "close enough" is how an edit lands in
the wrong place.

### The runtime loop

The build gate proves the code parses and that `index.html` points at files
that exist. It can't see a button that throws when clicked. The preview can —
it runs in a real browser, and since Day 10 its console errors arrive in the
logs stream. Now they also arrive in the **agent's next request**: every
distinct error since the last build, under a RUNTIME ERRORS heading. And the
workbench shows a bar — "Preview error: … · Ask the agent to fix it" — that
needs no copy-pasting, because the server already attaches the errors.

The browser walk now throws an error *inside the generated app* and follows it
all the way: bridge → logs → bar → fix run → bar gone after the new build.

### Closed open items

- **Prompt caching / `cached_tokens`.** Gemini already caches a request's
  prefix it has seen recently — our system prompt, tools and history are
  identical between agent rounds — and reports how many tokens it reused. The
  column was 0 because nobody read the field. Now it's recorded per run.
- **`/mcp` authentication.** `FORGEFLOW_MCP_API_KEY`, when set, is required
  on every MCP call (as a Bearer token or `X-API-Key`). Blank keeps it open
  locally.
- **Evals that survive interruption.** Results are saved after every case,
  Ctrl+C keeps what finished, and the runner waits out the new login rate
  limit instead of failing a case. Testing this taught me something: a
  background job (`cmd &`) in a script ignores Ctrl+C entirely, so my first
  "interrupt" test interrupted nothing.

136 tests.

**Verified live (10 Oct, 00:16).** On Render, with real Gemini: signed up,
asked for a tip calculator, and the reply streamed in and was saved
(index.html, styles.css, app.js; 7,626 tokens), the build passed, and the
Logs tab connected live. That's the redesign, the new UI and the SSE fix
working on the real deployment.

**…and one bug only a deploy could show.** Testing the preview on the live
site right after a deploy, the Logs tab sat on "connecting…" and showed
nothing new. The page had seen log lines up to #6 from the *old* server;
the new server numbered from #1 again; the browser resumed "after #6" and
so threw every new line away. Log sequence numbers now start from the clock
(microseconds), so they keep rising across restarts; the server also treats a
resume id "from the future" as "send everything"; the page says "live" as soon
as the stream connects; and a 25-second keep-alive stops proxies closing a
quiet stream. Two regression tests, each sabotage-checked.

**Signed out mid-use.** Coming back the next morning, the live site had
signed Aman out — a token lasted two hours, full stop, active or not.
Sessions now slide: once a token is past half its life, the next request it
authenticates returns a fresh one in an `X-Auth-Token` header, and the page
swaps it in. An idle tab still expires after two hours. The original sign-in
time (`auth_time`) rides along in every renewed token and renewal stops 30
days after it, so a stolen token can't be kept alive forever just by using it.
143 tests.

**Second live check (10 Oct, 08:25).** Aman signed in with a fresh account
(no password reset exists yet — see Still to build) and rebuilt the tip
calculator. On Render:

- Logs said **live** straight away. *Run build* put two build lines in it
  as they happened; *Start preview* added the preview line and three
  `GET … 200`s. The deploy-restart bug is fixed in production.
- Asked "Make the Calculate button green and rounded": only `styles.css`
  changed, the build passed, the preview showed a green rounded button,
  5,022 tokens.

But the stored run showed `write_file`, not `edit_file`: Gemini resent the
whole stylesheet to change one rule. The reason was in our own prompt — one
paragraph said "code reaches the user only by calling write_file", and a
later one said to prefer edit_file. The model obeyed the first. Fixed three
ways: the prompt now names both tools and gives "make the button green" as
an edit_file example; write_file's own description says to use edit_file
for partial changes; and when write_file rewrites a file of 10+ lines while
keeping at least 80% of them word for word, its result now carries a note
pointing at edit_file. The write still happens (it's not wrong, only
wasteful) — the note is for the rest of that run, since tool results are
what the model actually reads mid-task. Two tests, sabotage-checked.
145 tests.

**The evals vs. my own Free plan.** Aman ran the eval suite against Render:
cases 1–3 passed, then every case after that got a 402 — "The Free plan
allows 3 projects." The runner makes a fresh project per case, and Day 10's
quotas apply to the eval account like anyone else. The runner now deletes
each project once it's scored (deleted projects don't count), clears any
`eval-*` projects an interrupted run left behind, and stops cleanly —
keeping finished cases — if the account runs out of daily AI tokens, since
every remaining case would fail on the plan, not the agent. Checked locally
against the demo model: 3 leftovers removed, 5 cases passed on a 3-project
plan, 0 projects left; with the token limit shrunk to 3,000, it stopped at
case 3 with cases 1–2 saved.

**Live eval, 10 Oct 11:06 IST — 20/20 again.** Real Gemini on Render, with
edit_file, the runtime loop, the RAG context and the new prompt:

| | 27 Sep (Day 9) | 10 Oct |
|---|---|---|
| pass rate | 20/20 | 20/20 |
| repair rounds | 0 | 0 |
| mean tokens per case | 5,236 | 7,565 (+44%) |
| mean time per case | 13.4 s | 9.1 s |

Nothing broke. The token rise is most likely the price of what was added
since Day 9 - two more tools (search_code, edit_file) and a longer system
prompt, both re-sent on every round. (Likely, not measured: the next step
would be one case run with and without them.) Time went *down* - that's Gemini's
side, not ours; don't read it as a win.

What this run could **not** show: whether edit_file gets used. All twenty
cases build from an empty project, where write_file is the right tool. So:

- `GenerateResponse` now includes `toolUsage` - calls per tool name.
- Five golden cases gained a `followup` - a small change asked of the app
  just built, in the same project ("make the Calculate button green with
  rounded corners"). The runner reports, separately from the pass rate,
  whether each follow-up still builds and whether it was an **edit**, a
  **rewrite** or **mixed**. Separate, so pass rates stay comparable with
  every earlier run.

The eval account spent ~174k of its 200k daily tokens today, so the first
live run with follow-ups waits until the allowance resets (05:30 IST).

### Measuring the R in RAG

Aman wants RAG and the LLM to be the project's headline - which means
numbers, not "built RAG". Until now retrieval was built and tested but never
*measured*, and on projects under 16 files it doesn't even switch on.

- **A retrieval eval** (`evals/run_retrieval_eval.py`). A fixed 29-file
  sample shop, FreshCart (`evals/retrieval/fixture`, ~1,100 lines: pages,
  cart, promo codes, delivery, checkout validation, i18n, dark mode…), and 42
  labelled questions (`evals/retrieval/queries.json`) in three kinds: 14
  exact identifiers (`applyPromoCode`), 14 plain descriptions ("evening
  delivery surcharge") and 14 paraphrases that share few or no words with
  the code ("how much does it cost to bring the groceries to my house"). Each
  question is run in three modes - hybrid, vector only, keyword only - and
  scored by recall@1/3/5 and MRR at the file level.
- **`?mode=` on search** (`CodeIndex.Mode`), so each half can run alone.
  The product still always uses hybrid.
- **Honest measurement.** If Gemini embeddings fail, search quietly falls
  back to keywords - fine for the product, fatal for a measurement. Search
  now says so in `X-Search-Degraded` and `X-Index-Missing-Vectors`, and the
  runner waits and retries instead of scoring a keyword result as "vector".
- **`PUT /files/content`** - save a file by hand (EDITOR or owner, same path
  rules and 200 KB cap as the agent). The eval loads its fixture with it;
  it's also Lovable's "edit the code yourself".
- **RAG made visible.** When a request goes out with retrieved code, the
  chat shows "→ used 8 code excerpts from js/shipping.js, checkout.html …".
- **CI guard** (`RetrievalEvalTest`): the same fixture and the 28
  non-paraphrase questions, with the offline embedder, must all find their
  file in the top 5. Sabotage-checked: reversing the ranking fails it.

Dry run with the offline hashing embedder (word-level, so its "vector" mode
is really keywords again - **not** the real numbers): hybrid recall@5 88%,
keyword 95%, MRR 0.83 / 0.77; identifiers and descriptions 100% in every
mode, paraphrases 64% hybrid. One thing it already shows: fusing a strong
list with a weak one can *lower* recall@5 below the strong list alone. The
live run with Gemini embeddings is what counts. 151 tests.

**Live result (v1, Gemini embeddings on Render, 12:12 IST):**

| | recall@1 | recall@3 | recall@5 | MRR | paraphrase recall@5 |
|---|---|---|---|---|---|
| hybrid (shipped) | 83% | 98% | 98% | 0.90 | 93% |
| vector only | 86% | 100% | 100% | 0.92 | 100% |
| keyword only | 69% | 86% | 95% | 0.78 | 86% |

Two findings. Embeddings earn their keep: on paraphrases, vector 100% vs
keyword 86%. And **the hybrid we ship lost to vector alone** - its one miss
was a question where the keyword list had nothing relevant and its noise
pushed the right file from 3rd to 6th. Also: vector-only at 100% recall@5
means v1 is too easy to separate methods any further.

### Tuning retrieval with the benchmark

- **v2 question set.** 11 decoy files (`evals/retrieval/fixture-v2`) that
  borrow v1's vocabulary - gift cards and loyalty points beside promo codes,
  driver tracking beside delivery cost, address lookup beside postcode
  validation, back-in-stock alerts beside the in-stock filter - and 28
  questions aimed at the decoys *and* at the files they imitate. 70
  questions over 40 files; the runner's default is now `--set v2`.
- **Fusion moved from SQL to Java** (`CodeIndex.fuse`): each half returns a
  ranked id list, RRF merges them with weights - `w_v/(60+r_v) +
  w_k/(60+r_k)`. Defaults 1/1 (`forgeflow.retrieval.vector-weight`,
  `keyword-weight`); a search can override them (`?vectorWeight=&
  keywordWeight=`). Also the prerequisite for a vector store outside
  Postgres (Qdrant, next).
- **LLM rerank** (`LlmReranker`, `?rerank=true`): the top 15 go to the chat
  model with the question; it returns the order. Metered against the
  caller's daily AI tokens (else `rerank=true` on a read endpoint would be
  free model calls); failure keeps the fused order and sets
  `X-Rerank-Failed`, which the eval treats as "retry", never as a result.
- **Query-embedding cache** (LRU, 512, keyed by model + text): the eval asks
  each question six ways; the agent and `search_code` often repeat a query.
- The runner compares keyword / vector / hybrid / hybrid kw0.5 / hybrid
  kw0.25 (+ hybrid+rerank with `--rerank`) and prints recall@1 by kind.

Offline dry run on v2 (hashing embedder, so not the real numbers): hybrid
recall@1 70%, keyword 64%, vector 54%; paraphrase recall@1 drops to 19-38%
- the decoys work. 157 tests.

### The diagram, running: Kafka, MinIO, Qdrant, a gateway, Kubernetes

Aman's ask: follow the spec's architecture as closely as possible, and make
the project worth explaining in an interview. Decision (his pick of three):
**both modes, same code** - every infrastructure box gets a real
implementation behind the seam it already had, chosen by config. Render keeps
the light mode; `docker-compose.full.yml` and `deploy/k8s` run the diagram.

Constraint discovered first: Maven Central is unreachable from both the
cloud workspace and the Mac's sandboxed shell, so no new libraries could be
downloaded. kafka-clients 3.8.1 happened to be in the local cache - the only
new dependency. MinIO and Qdrant clients are written by hand over HTTP (as
Redis was on Day 10).

- **Kafka** (`shared/events`): `EventBus` → `InProcessEventBus` |
  `KafkaEventBus`. code.generated keyed by project, idempotent producer,
  manual commits after the handler (at-least-once), retries then a dead-letter
  topic, `traceparent` in headers so one trace crosses the hop. Two consumer
  groups - "indexer" (can be its own worker process) and "execution" (a new
  `CodeChangeNotifier`: "run N changed …" in an open preview's logs).
  `CodeGenerated` moved to shared: it's a contract between modules.
- **MinIO / S3** (`shared/storage`): a SigV4 signer - it matched the worked
  example in AWS's docs on the first run - and a small S3 client. File
  contents become content-addressed objects; rows keep metadata and the key
  (V8).
- **Qdrant** (`VectorIndex`): Postgres stays the record, Qdrant is the index;
  payload-filtered HNSW per project; falls back to pgvector (and says so)
  when Qdrant is down. Tested against a real Qdrant 1.12.4 binary from
  GitHub releases.
- **Gateway** (`gateway/`, its own deployable): route table, JWT checked at
  the edge, proxy headers, and unbuffered SSE - the test that proves the
  first event arrives before the stream ends fails if the flush is removed.
- **Compose and Kubernetes**: gateway, api, worker (same image, other env),
  Postgres, Kafka (KRaft), MinIO, Qdrant, Redis, Zipkin. k8s: Deployments,
  StatefulSets, an HPA on the worker capped at the partition count, an
  Ingress with nginx buffering off for SSE, probes on Boot's
  liveness/readiness groups.

Verification: the browser walk-through ran end to end **through the
gateway** against an api in S3 + Qdrant mode - 213 requests over three
service names, chat and logs streams live, blobs visible in the bucket,
vectors in Qdrant. Kafka and MinIO integration tests run in CI against the
real images (Kafka binaries can't be downloaded here); the CI summary now
lists every integration test class with its counts, because the raw logs
aren't reachable from this workspace either. 172 tests + 6 gateway tests.

**CI, first full run:** every integration test ran, none skipped (Kafka
bus 2, Kafka end-to-end 1, S3 store 2, S3 mode 1, Qdrant index 1, Qdrant
modes 2). One surprise: MinIO's Docker images no longer pull - MinIO stopped
publishing community images in 2025. The S3 server in CI, compose and k8s is
now Zenko CloudServer, which verifies signatures too. The client speaks S3,
so this cost a config line, not code - the point of not using a vendor SDK.

Interview line: *"Every box on the diagram exists and is tested, but the
free-tier deployment runs one process - because which boxes you pay for is a
deployment decision, and the code shouldn't have to change when you make
it."*

### Version history

Stage 3 of Aman's list. Every AI run that changes files is now a version,
labelled with what was asked; any version can be diffed against the one
before it and restored. A restore is itself a version, so it can be undone -
and if the project had unsaved hand edits, they're checkpointed before a
restore overwrites them ("Before restoring to #N").

Storage is content-addressed, the same idea as the S3 keys: a checkpoint is
a list of (path, SHA-256), contents live once per fingerprint in
`file_blobs`, and identical trees aren't recorded twice. In s3 mode history
costs no bucket space at all - the blobs are the objects file writes already
made. The diff is a small LCS line diff in git's unified format.

UI: a History tab - versions on the left (#, kind, age, +added ~changed
−removed), the coloured diff on the right, and a two-click restore (no
modal; the first click arms it for four seconds). The browser walk now opens
History, checks the diff renders, and restores the first build.
178 tests (4 in CheckpointTest: diffs, restore and undo, baseline for hand
edits, access; plus restore over S3).


### Screenshot to app

Stage 4. Attach, paste or drop up to three images in the chat - a
screenshot of a site you like, a Figma export, a photo of a paper sketch -
and the agent builds from them. Gemini is multimodal, so the images travel
as `inlineData` parts after the text, and the request opens with a brief:
treat the picture as the spec - layout, sections, visible text verbatim,
colours, spacing.

Decisions worth defending: the browser scales big screenshots down before
upload (the model doesn't need retina pixels; the server caps 4 MB); the
server checks each image's first bytes against its claimed type, so a
renamed file is a 400 before anything is saved; images are kept with the
message so reload shows them and **retry resends them**; past images are
mentioned in memory, not re-sent, so a long chat doesn't pay for the same
picture every turn.

Real Gemini can't be reached from the build workspace, so the wire format
is pinned by a unit test (text part, then one `inlineData` part per image)
and the flow by API tests with the scripted model; the browser walk attaches
a real PNG and checks the thumbnail survives a reload. The live check is
Aman's: paste a screenshot on Render. 183 tests.

### AI checks its own app

Stage 5. The agent already had two inspectors: the build gate (does the code
parse and link?) and the console bridge (does it throw when it runs?).
Neither notices a page that works but **looks** wrong - white text on a
white button, a "pricing table" with no prices, three cards collapsed into a
pile. Now, after every reply that changed files, the workbench photographs
the live preview and a vision model judges it against what was asked.

```
reply lands ─► preview reloads ─► parent posts {ff:snapshot} into the iframe
   bridge (already injected for console logs) loads html-to-image from /p/{token}/__snapshot.js,
   renders the page to a JPEG (≤1024 px wide, ≤2000 px tall), posts it back
─► POST …/messages/{replyId}/visual-review  ─► Gemini: screenshot + the last 3 requests
◄─ {score, verdict, summary, issues[major|minor]}  kept in visual_reviews (V11)
NEEDS_FIXES with a major issue ─► ONE follow-up turn: "Visual check (6/10) found problems… Fix these: …"
   ─► that reply is checked too, but never chased: one round, so it can't loop
```

The decisions:

- **The screenshot is taken in the visitor's browser**, not on the server.
  No headless Chrome on Render (512 MB wouldn't hold it), zero new
  infrastructure, and the review sees exactly what the user sees at the size
  they see it.
- **html2canvas didn't work, html-to-image does.** The preview runs in a
  sandboxed opaque origin (that's what keeps generated code away from the
  user's token). html2canvas clones the page into a child iframe, and in a
  sandbox that child is *another* opaque origin it may not touch - "Blocked
  a frame with origin null". html-to-image clones in place and renders
  through an SVG `foreignObject`, which works inside the sandbox. Found by
  the browser walk, not by guessing. Vendored (20 KB, MIT), not loaded from
  a CDN.
- **The bridge answers only its parent** (`e.source === parent`) and is
  **muted while it photographs**, so the library's own warnings never show
  up as the app's runtime errors.
- **The verdict follows the issues, not the label.** A review that says
  "looks right" but lists a major issue needs fixes; one that lists none
  looks right. Fenced or chatty JSON is repaired; an unreadable reply is a
  502 and nothing is stored (its tokens are still metered - they were spent).
- **Metered and editor-only**, like every model call.
- **Toggle**: "AI checks the preview" by the composer, on by default,
  remembered per browser. No preview running → nothing happens.

The demo model plays the loop honestly labelled (a first look always finds
one thing, the look after the fix passes), so the browser walk runs the
whole path offline: real screenshot (checked to be a real 785 px image),
review card, automatic fix turn, 9/10 re-check, exactly one round, verdicts
still there after a reload, a viewer can read them but not act on them.
186 tests.

### React apps, running in the browser

Stage 6, the diagram's "Code Execution Service: WebContainer" box. A project
is now created as **HTML/CSS/JS** or **React + Vite** (V12, `projects.stack`).
For React the agent gets a different target in its instructions - a real
Vite layout: `package.json`, `vite.config.js`, `index.html`, `src/main.jsx`,
`src/App.jsx`, components - and everything else (tools, loop, memory, RAG,
version history, the visual check) is shared.

Three places the same files run:

| Where | How | Cost |
|---|---|---|
| **live preview** | the preview swaps `<script type="module">` for an inert tag and loads **ForgeFlow's runner**: Sucrase compiles each file (JSX, TS, ES imports → CommonJS), a 40-line loader runs them, React from a vendored 18.3.1 build, other packages from esm.sh against our React | instant, nothing on the server |
| **Run with Node** | `/run.html` boots a **WebContainer** - Node.js in WebAssembly, in the tab - mounts the files, runs `npm install` and `npm run dev`, and shows Vite's server in a frame | real npm, real Vite; needs StackBlitz's runtime |
| **your machine** | Download → `npm install && npm run dev` | it's a normal Vite project |

The build gate learned React (`ModuleProjectCheck`): package.json valid and
naming react/react-dom, index.html loads an entry that exists, every relative
import lands on a file (trying Vite's extensions), every package import is
declared, braces balance. The JSX itself is compiled in the browser - a
compile error shows as an overlay in the preview, goes to the Logs Stream,
and the "fix it" bar sends it back to the agent like any runtime error.

Decisions:

- **Why not WebContainers for the preview itself?** Booting downloads a
  runtime and `npm install` takes 10-30 s every time; the preview should be
  instant and work with no third party. So the preview compiles in the page,
  and the WebContainer is one click away for "is it real?".
- **Why CommonJS in the browser and not import maps + blob URLs?** `require()`
  is synchronous and cycle-safe, `new Function` + `//# sourceURL` makes stack
  traces name `src/App.jsx`, and there's no URL rewriting. Import maps are
  used only for third-party packages from esm.sh, which must share our React.
- **JSX vs the brace checker.** JSX text breaks a JavaScript tokenizer -
  "Don't" opens no string, "https://" starts no comment, ":)" closes no paren.
  JSX mode treats a quote after a letter and `//` after a colon as text and
  balances only `{}` (a stray `{` in JSX text is illegal anyway).
- **Cross-origin isolation only where it's needed.** A WebContainer needs
  SharedArrayBuffer, so `/run.html` gets COOP/COEP; the workbench doesn't,
  because `require-corp` would break its sandboxed preview iframe and Stripe's
  redirect.
- **Vendored, reproducible.** `preview-runner/` holds the runner source and a
  build script (esbuild) that writes the runner, React and the WebContainer
  client into resources.

Verified in the browser walk: React project from the dialog, a real Vite
layout written, the preview compiles it and `useState` counts clicks, the
component's `console.log` reaches the Logs Stream, a broken component shows
the error overlay and the runtime bar, and Run with Node opens isolated
(`crossOriginIsolated === true`). The WebContainer's own boot needs
stackblitz.com, which the build workspace can't reach - offline it fails
with a reason after 45 s, as designed; the online check is on Render.
195 tests.

### A stateless API: two copies behind the gateway

Stage 7. Until now the API kept three things in its own memory - the chat
"one reply at a time" lock, the preview Logs Stream, and the fake checkout's
pending sessions - which is why Kubernetes ran exactly one replica. All
three now live in Redis when `REDIS_URL` is set (and in memory without it):

| State | In Redis | Why it's safe |
|---|---|---|
| chat session lock | `SET ff:lock:chat:{id} {token} NX PX 360000`; release = Lua "delete only if it's still my token" | expires if the instance dies; a late release can't free someone else's lock |
| preview logs | `INCR` one sequence; `RPUSH`+`LTRIM`+`EXPIRE`+`PUBLISH` in one Lua call; one `PSUBSCRIBE ff:logs:ch:*` per instance | the list is the record (replay by sequence), pub/sub just says "new line"; Redis down → delivered locally |
| fake checkout | `SET … EX 3600`, taken with GET+DEL in Lua (one use) | test tooling, but now it survives the redirect landing on the other instance |

The gateway learned **round robin with failover**: a service's URL can be a
list; requests take turns, and an instance that refuses the connection is
skipped (safe - it never saw the request, and the body is already
buffered). A timeout is *not* retried: that instance may be working on it.

The RESP client moved to `shared/redis` and gained `psubscribe` (a
dedicated connection read by one virtual thread, reconnecting with backoff).
docker-compose.full runs `api` and `api-2`; Kubernetes runs 2 API replicas
with an HPA (2-6) and a PodDisruptionBudget.

Proof, beyond the unit tests (two `SessionLocks` and two `PreviewLogs` on one
Redis): the whole browser walk-through, run through the gateway against two
API instances on one Redis. Requests split evenly (three agent runs on each),
and everything that crosses instances - a log line from a build on one
reaching a viewer on the other, a chat lock, the checkout redirect - worked.
199 tests.

### A Kubernetes pod per preview

Stage 8, the last box in the diagram: `code.generated → Kafka →
execution-service → Kubernetes pods (project-456:3000, new namespace)`.
`FORGEFLOW_SANDBOX_PROVIDER=kubernetes` gives every preview its own
namespace, created over the Kubernetes REST API (no client library -
`KubernetesApi` is ~150 lines of HTTPS + the pod's service-account token
and CA):

```
ffp-{token}   Namespace      labelled forgeflow.dev/project-id=456
  quota       ResourceQuota  2 pods, 1 CPU, 1 GiB
  preview     NetworkPolicy  in: only from the gateway's namespace; out: nothing (static) / DNS+443 (React, for npm)
  files       ConfigMap      the project's files (keys escaped: "/" can't be in a key; mapped back by volume items)
  preview     Pod            static: nginx-unprivileged :8080   React: node, npm install, vite :3000
  preview     Service        :80 -> the pod
```

Every pod: non-root, read-only root filesystem, all capabilities dropped,
seccomp RuntimeDefault, **no service-account token** (generated code gets
no cluster credentials), and `activeDeadlineSeconds` = the preview's 30
minutes, so Kubernetes kills it even if ForgeFlow forgets.

The preview's address is `https://{token}.preview.domain/`. A wildcard
Ingress sends every preview host to the gateway, which proxies
`{token}.{domain}` to `preview.ffp-{token}.svc` - the token *is* the
namespace, so there's no lookup table, and any API replica can stop or
refresh a preview by its project label. Anything that isn't 32 hex
characters isn't a preview host, so the template can't be steered.

And the arrow from Kafka: when `code.generated` arrives for a project with
a running pod, the execution consumer replaces the ConfigMap and recreates
the pod in the same namespace - **same address, new code**. (Docker
previews do the same by rewriting their mounted folder; in-process previews
read the project live and need nothing.)

Permissions: a ClusterRole (namespaces are cluster-scoped and created on
the fly) plus a **ValidatingAdmissionPolicy** for what RBAC can't say: the
API's service account may only touch namespaces named `ffp-*`.

Tested against a fake Kubernetes API server (the Stripe approach: real
protocol, fake server): order of creation, every security setting,
ConfigMap key escaping, React on :3000 with npm egress, replace-on-restart,
stop-by-label, ImagePullBackOff failing fast and cleaning up, too-big
refused before touching the cluster, redeploy surviving a 409 from a
terminating pod - and end to end with the whole app: start a preview →
generate a change → `code.generated` → the ConfigMap holds the new file and
the URL is unchanged. Not yet run on a real cluster. 207 tests.

Also fixed: the test suite ran Postgres out of connections (one pool of 10
per cached Spring context); the test profile now caps pools at 4.

### Found live: the visual check photographed React apps blank

Aman's first live test: a React habit tracker rendered perfectly in the
preview, and the visual check scored it **1/10 - "the page is completely
blank"** - then sent the agent off to "fix" it, twice. The camera fired
0.9 s after the page's load event, but a React preview isn't drawn at load:
the runner then fetches the sources and compiles them, and it fetched them
**one at a time** - seven round trips to Render in a row. Locally that's
instant, so every local test passed.

Reproduced with 400 ms of simulated latency (Chrome DevTools protocol): the
screenshot had 0 dark pixels. Three fixes, each enough on its own in the
common case:

- the runner fetches every source **in parallel**, then compiles;
- the bridge's camera **waits for the page to settle**: the runner reports
  done, then no DOM changes for 400 ms (at most 8 s);
- the workbench **checks the photo**: if every pixel is the same colour it
  waits 1.5 s and takes it again (twice at most) before asking the model.

After: 8,310 dark pixels at 400 ms and 800 ms latency. The browser walk now
runs the React visual check on a throttled link and fails if the photo is
blank.

Lesson worth saying in an interview: the test that mattered was the one
with a slow network. Everything passed on localhost.

### What a run costs, and garbage collection for the bucket

Two long-standing open items.

**Cost per run.** `cost_usd` had been 0 since Day 1. `LlmPricing` now turns
the token counts Gemini reports into dollars at list price (Gemini 3.1
Flash-Lite: $0.25 / 1M input, $0.025 cached, $1.50 output - from Google's
pricing page, overridable by env). Three meters, because they're billed
differently: uncached prompt, cached prompt, and output - where output is
`total - prompt`, so the thinking tokens are counted (they're billed as
output, and `total` is not `prompt + completion`). A model with no known
price costs 0 rather than a guess. Each run stores its cost, each chat
reply shows it ("2,336 tokens · $0.0011", V13), and the eval report prints
**cost per app** - a number for the resume once the evals run.

**Blob sweep.** File contents in S3 are content-addressed and never deleted
on write (another file or a checkpoint may share them), so overwritten
versions piled up forever. `BlobSweeper` is a nightly **mark and sweep**:
mark every key a row still points at (current files + checkpoint blobs),
list the bucket (ListObjectsV2, paged, SigV4-signed query), delete what's
unmarked and older than a 24 h grace period (a write puts the object a
moment before its row). It lists *before* marking, so nothing written
mid-sweep can be lost. One replica sweeps at a time - a Postgres
`pg_try_advisory_xact_lock`; the others skip. Tested against moto: garbage
gone, history and current files kept, a fresh object inside the grace
period untouched. 210 tests.


---

## Open items

- The agent timeout counts time spent waiting out rate limits.
- `/mcp` runs as one service account for every caller; set
  `FORGEFLOW_MCP_API_KEY` on Render before advertising it.
- Runtime errors and the visual check only happen if someone has the preview
  open - nothing exercises the app on its own (a server-side browser would).
- Visual check isn't measured against real Gemini yet: how often does it
  flag something real, and how often does the fix round raise the score?
- Render isn't given a `REDIS_URL` yet, so production keeps locks, logs and
  rate limits in memory - right for its single instance.
- The console-line rate cap (120/min per project) is per instance.
- (pgvector mode) The HNSW index filters by project *after* the nearest-neighbour scan;
  Qdrant mode filters inside the search. Fine
  at this size; with many projects, a busy one could crowd a small one out of
  the candidate list (pgvector 0.8's iterative scans fix this).
- Embedding calls aren't counted against the token quota (rerank calls are).
- Kubernetes previews are tested against a fake API server, not a real
  cluster yet (kind or minikube would do it).
- Kubernetes previews don't get the console bridge or the visual check (nginx
  and Vite serve the files, not ForgeFlow); Vite's hot-reload websocket isn't
  proxied by the gateway, so the page reloads instead.
- Run with Node hasn't been watched booting yet (needs stackblitz.com - check on Render).
- React previews load non-React packages from esm.sh at view time; offline, only
  react/react-dom work.
- Real Stripe is built and tested against a local stub, but never run against
  Stripe itself — that needs Aman's test-mode keys (notes.md §14).
- Two project creates racing can both pass the quota check and land one over.
  Accepted; closing it needs a per-user lock on every create.

## Still to build

1. Run the eval suite against real Gemini with `edit_file` and the runtime
   loop in place, and compare with Day 9's 20/20
2. A headless browser in the build gate, so runtime errors are caught without
   anyone opening the preview (needs a host that can run Chromium)
3. "Forgot password?" — needs an email sender (a reset link mailed to the
   user); until then a lost password means a new account
4. Run the evals (after 05:30 IST) and read the follow-up line: how many of
   the five small changes were edits, not rewrites

## Done since the original plan

- Evals — twenty golden prompts, a measured pass rate, and a model migration
  decided on its evidence (Day 9)
- Deploy — live on Render with a config-selected sandbox provider (Day 8)
- Frontend — a workbench that makes the self-healing loop visible (Day 7)
- MCP server — ForgeFlow drivable by another agent
- CI — every push runs the suite against a real Postgres (Day 10)
- Members and roles, chat memory, zip download, logs stream (Day 10)
- Plans, quotas, usage log, Stripe checkout and webhook (Day 10)
- Rate limiting — token buckets in Redis, in-memory fallback (Day 10)
- RAG — hybrid pgvector + full-text search, `search_code` tool, `code.generated` event (Day 10)
- Tracing (traceparent, Zipkin), OpenAPI with a contract test, module-boundary test (Day 10)
- Chat-shaped workbench, demo model, browser walk-through, README (Day 10)
- `edit_file`, runtime errors fed back to the agent, cached tokens, MCP key (Day 11)
