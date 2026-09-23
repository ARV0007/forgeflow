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

## Open items

- `AgentTools` still uses `ProjectFileRepository` directly, breaking the
  module-boundary rule. New code goes through `ProjectFileService`.
- `SandboxException` isn't mapped in `GlobalExceptionHandler`, so it surfaces as
  a default 500.
- Previews record `expires_at` but nothing reaps them yet.
- `tool_calls`, `chat_sessions`, `chat_messages` and `file_chunks` exist but
  aren't written to. Each generate starts with no memory of earlier ones.
- `cost_usd` and `cached_tokens` are always 0.
- No tests beyond Initializr's default. No CI. Not deployed.
- The timeout counts time spent waiting out rate limits.

## Still to build

1. `edit_file` — diff-based edits instead of full rewrites — plus prompt caching
2. Evals — 20 golden prompts, measured pass rate
3. RAG over the codebase (first to go if time runs short)
4. Deploy, README, CI
