# ForgeFlow

**Describe an app in plain English and get a working, running app back** — then
keep refining it by conversation. An AI app builder in the spirit of Lovable,
built from scratch in Java.

The part that makes it more than a wrapper around a model: **the agent isn't
allowed to finish until the code it wrote passes a build.** When the build
fails, the errors go back to the agent, it fixes them, and tries again.

```
"build me a recipe site with search"
      │
      ▼
agent plans → writes files → calls finish → build check
      │                                        │
      │                              fails? ◄──┘  errors go back to the agent,
      │                                           it fixes them, finish again
      ▼
live preview · file tree · every step streamed to the browser
```

**Live:** https://forgeflow-7m08.onrender.com · **API reference:** `/docs.html`

---

## What it does

| Area | Features |
|---|---|
| **Projects** | create, list, rename, delete; share with **editors** and **viewers**; public projects |
| **Auth** | sign up, log in (JWT), get / edit my profile |
| **AI generation** | chat sessions with **memory** (last 10 messages); streaming replies; **retry if failed**; a self-healing build gate; targeted `edit_file` changes; errors from the running preview fed back to the agent |
| **Files** | file tree, file content, **download as zip**, who created / last changed each file |
| **Preview** | live preview link, start / stop, **logs stream** — builds, requests, 404s and the generated app's own `console` output |
| **Search (RAG)** | hybrid code search (pgvector + full-text, rank-fused); the agent has a `search_code` tool; big projects get relevant code attached to each request |
| **Plans** | FREE / PRO — projects, live previews, AI tokens per day; **Stripe** Checkout and a signed webhook |
| **Platform** | Redis **rate limiting**, request **tracing** (W3C traceparent, optional Zipkin), an **MCP server** so other AI agents can drive it |

## Architecture in one picture

One deployable, split into modules along the lines a service split would
follow. Modules talk only through each other's service classes — a test fails
the build if one reaches into another's tables.

```
                         ┌─────────────── browser (static/app.js) ───────────────┐
                         │  chat · preview · code · logs · search · share · plan │
                         └──────────────┬──────────────────────────┬─────────────┘
                                        │ REST + SSE (JWT)          │ /mcp (JSON-RPC)
  TracingFilter → JwtAuthFilter → RateLimitFilter (Redis) → controllers
        │
  ┌─────┴────────┬────────────┬─────────────┬──────────────┬────────────┬─────────┐
  account      workspace     chat       intelligence     execution    billing     mcp
  users, /me   projects,     sessions,  agent loop,      build gate,  plans,      tools for
               members,      messages,  tools, RAG,      preview,     quotas,     other agents
               files         memory     code.generated   logs stream  Stripe
        └──────────────── PostgreSQL 16 + pgvector ─────────────────┘     Gemini API
```

Details, decisions and trade-offs: [`docs/architecture.md`](docs/architecture.md).

### Two deployments, one codebase

The spec's diagram draws a gateway, Kafka, MinIO, Qdrant and Kubernetes. All
of them are built - each behind a seam, switched on by configuration:

```
all-in-one (Render, free tier)          full topology (docker-compose.full.yml, deploy/k8s)

  browser ─► app ─► postgres              browser ─► gateway ─► api ─┬─► postgres   rows, chunks
                                                       edge JWT,    ├─► minio      file contents
  events:  in-process                                  SSE proxy    ├─► qdrant     vector search
  files:   postgres                                                 ├─► redis      rate limits
  vectors: pgvector                                                 └─► kafka ─ code.generated ─┬─► worker (indexer)
                                                                                                └─► api (preview notices)
```

```bash
docker compose -f docker-compose.full.yml up --build     # then http://localhost:8080
```

## Stack

Java 21 · Spring Boot 4.1 · PostgreSQL 16 + pgvector · Flyway · Gemini
(`gemini-3.1-flash-lite`, embeddings `gemini-embedding-001`) · Redis (optional) ·
plain HTML/CSS/JS front end · JUnit 5 against a real database · GitHub Actions.

Deliberately **no** Spring AI, no Stripe SDK, no Redis client library, no
tracing library: each of those integrations is a small, readable class over
plain HTTP or a socket — see the chapters in `docs/UNDERSTANDING.md`.

## Run it locally

```bash
docker compose up -d                 # Postgres (+ pgvector) on 5433, Redis on 6379
cp .env.example .env                 # then fill in GOOGLE_API_KEY and JWT_SECRET
set -a; source .env; set +a
./mvnw spring-boot:run               # http://localhost:8081
```

**No API key? Use demo mode.** The demo model writes the same small starter page
for any request, but through the real pipeline — tools, build gate, memory,
logs, quotas — so everything in the UI works:

```bash
FORGEFLOW_LLM_PROVIDER=demo FORGEFLOW_BILLING_PROVIDER=fake \
FORGEFLOW_EMBEDDER=hashing FORGEFLOW_SANDBOX_PROVIDER=in-process \
./mvnw spring-boot:run
```

### Configuration

| Variable | Default | What it does |
|---|---|---|
| `GOOGLE_API_KEY` | — | Gemini, for generation and embeddings |
| `FORGEFLOW_LLM_PROVIDER` | `gemini` | `demo` = offline stand-in model |
| `FORGEFLOW_SANDBOX_PROVIDER` | `docker` | `in-process` where there's no Docker daemon (Render, CI) |
| `FORGEFLOW_EMBEDDER` | `gemini` | `hashing` = offline, keyword-level embeddings |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | local compose | Postgres |
| `JWT_SECRET` | dev-only value | **set this in production** |
| `REDIS_URL` | — | share rate limits across instances (`redis://` or `rediss://`) |
| `FORGEFLOW_BILLING_PROVIDER` | `none` | `fake` (local only) or `stripe` |
| `STRIPE_SECRET_KEY`, `STRIPE_PRO_PRICE_ID`, `STRIPE_WEBHOOK_SECRET`, `APP_BASE_URL` | — | Stripe — setup in `docs/notes.md` §14 |
| `ZIPKIN_URL` | — | export traces, e.g. `http://localhost:9411` |
| `FORGEFLOW_MCP_SERVICE_EMAIL` | `mcp-service@forgeflow.internal` | the account MCP calls act as |
| `FORGEFLOW_MCP_API_KEY` | — | require this key on `/mcp` (set it on any public deployment) |

## Tests

```bash
./mvnw verify          # needs Postgres; Redis tests run if Redis is up, skip if not
```

Every test runs against a real Postgres — no mocked repositories. The model is
replaced by a **scripted** one that plays back tool calls, which is how the
self-healing loop is tested on every push. Each feature's test was checked by
breaking the feature on purpose and watching it go red.

Also under test: the API reference matches the routes Spring serves, module
boundaries hold, and an SSE stream ends cleanly on a real Tomcat.

Two evals against real Gemini:

```bash
python3 evals/run_evals.py --base <url>            # 20 prompts: does the app build? (+5 follow-up edits)
python3 evals/run_retrieval_eval.py --base <url>   # 42 questions: does search find the right file?
```

Browser walk-through (sign up → chat → preview → logs → search → upgrade →
share → quota), against a demo-mode server:

```bash
python3 scripts/ui-walk.py
```

## Docs

| | |
|---|---|
| [`docs/UNDERSTANDING.md`](docs/UNDERSTANDING.md) | how it works, from scratch, chapter by chapter, each ending in interview-style questions |
| [`docs/architecture.md`](docs/architecture.md) | the design, the spec mapped onto the code, every deliberate deviation |
| [`docs/WORKLOG.md`](docs/WORKLOG.md) | what was built when, including the mistakes |
| [`docs/notes.md`](docs/notes.md) | practical notes and gotchas — setup, Stripe, Redis, tracing |

## Not built (on purpose)

The spec this follows draws a gateway, Kafka, Qdrant, MinIO and Kubernetes.
At this scale each would add a system to keep consistent without adding a
feature, so each has a **seam** instead — an interface or an event where it
would plug in. The reasoning is in `docs/architecture.md` §17.6.

---

Built by [Aman Raj Verma](https://github.com/ARV0007).
