# ForgeFlow in an interview

How to talk about this project: a two-minute pitch, a walk through the
architecture you can draw on a whiteboard, the questions you're likely to get
(with answers), the bugs worth telling, and resume lines.

Every number here comes from a real run. Where a run hasn't happened yet,
the line says so, with a placeholder.

---

## 1. The two-minute pitch

> ForgeFlow is an AI app builder, like Lovable, that I built from scratch in
> Java and Spring Boot. You describe an app in plain English, or paste a
> screenshot, and you get a running app back that you can keep changing by
> chatting.
>
> The interesting part is that I don't trust the model. The agent isn't
> allowed to finish until its code passes a build check. If the build fails,
> the errors go back to the agent and it fixes them. After that, the preview
> takes a screenshot of itself, a vision model scores it against what you
> asked for, and if something is clearly wrong the agent gets one more round
> to fix it.
>
> Behind that there's a proper backend: a gateway, a stateless API that runs
> as two copies behind it, Kafka carrying a `code.generated` event to an
> indexing worker and to the preview service, file contents in S3, code
> search in Qdrant, and a Kubernetes namespace and pod per preview. The
> free-tier deployment runs all of it in one process; the same code runs the
> full topology from config.
>
> And I measured it. Twenty test prompts against the real model: 20 out of
> 20 build, about 11 seconds, 7,500 tokens and a third of a cent each.
> When I ask for a small change afterwards, it edits the file instead of
> rewriting it: five out of five. I also wrote a 70-question benchmark for
> code search, with decoy files built to confuse it. Search finds the right
> file in the top three 99% of the time, after I used the benchmark to retune
> how the two halves of the search are weighted. There are 219 tests, all
> against real databases.

Shorter, for "tell me about a project" in 30 seconds:

> An AI app builder in Java where the model has to pass a build check before
> it's done, then checks a screenshot of its own app. Event-driven backend
> with Kafka, S3, a vector database and a pod per preview. 20 out of 20 on
> my eval set, and 99% top-3 accuracy on a 70-question code-search benchmark
> I built and used to tune it.

---

## 2. The architecture, as you'd draw it

Draw it left to right, in this order, and say one sentence per box.

```
browser ──► gateway ──► api (×2, stateless) ──► Postgres + pgvector
   │            │              │                Redis (locks, live logs, limits)
   │            │              │                S3 / MinIO (file contents)
   │            │              │                Qdrant (code vectors)
   │            │              └──► Kafka: code.generated ──┬──► indexer worker ──► Qdrant
   │            │                                           └──► execution ──► k8s pod per preview
   │            └── /mcp: other AI agents, with personal access tokens
   └── WebContainer: real Node in the browser ("Run with Node")
```

1. **Browser.** Plain HTML/JS. Chat on the left, live app on the right.
   Replies stream over Server-Sent Events.
2. **Gateway.** Checks the JWT at the edge, rate-limits, proxies to the API
   instances round-robin, and skips an instance that refuses the connection.
3. **API.** One Spring Boot app split into modules: account, workspace,
   chat, intelligence, execution, billing, mcp. Modules only call each
   other's service classes, and a test fails the build if one touches
   another's tables. It's stateless: anything shared lives in Redis, so it
   runs as two copies.
4. **The agent loop** (intelligence). The model gets tools: `list_files`,
   `read_file`, `write_file`, `edit_file`, `search_code`, `finish`. `finish` runs the
   build check. If it fails, the errors become the next message.
5. **Storage.** Rows in Postgres. File contents in S3, content-addressed
   (the key is the SHA-256 of the content), so a version is just a list of
   pointers and costs nothing extra.
6. **Kafka.** After every AI run, a `code.generated` event. Two consumers:
   the indexer (chunks the changed files, embeds them, writes to Qdrant)
   and the execution service (redeploys a running preview with the new
   files).
7. **Previews.** On Kubernetes, each preview gets its own namespace with a
   quota, a network policy that blocks everything outbound, and a locked-down
   pod. On the free tier, the API serves the preview itself inside a CSP
   sandbox.

The one line to remember: *"Every box on the diagram exists and is tested,
but the free deployment runs one process, because which boxes you pay for is
a config decision, not a code decision."*

---

## 3. Questions you'll probably get

**Why Java and not Python, when everything AI is Python?**
The hard parts here aren't the model call. They're concurrency, streaming,
transactions, auth and keeping services apart, and Spring is very good at
those. The model call is one HTTP request. I also deliberately used no AI
framework: the Gemini client, the tool loop, the S3 signer and the Redis
client are each a small class I can explain line by line.

**How does the self-healing loop actually work?**
The only way for the agent to end a run is to call `finish`, and `finish`
runs a build check:
- there's an `index.html`;
- every file it links to exists;
- the JavaScript is sound. With Docker, that's a real parse in a locked-down
  Node container. On the free host it's a structural check in pure Java
  (brackets, strings and comments balance), and the output says it's weaker;
- for React projects, every import resolves to a file or a declared package.

If it fails, the tool result is the list of errors, and the model sees it on
its next turn like any other tool result. It gets three repair rounds and a
deadline, so it can't loop forever. Time spent waiting out the model's rate
limits doesn't count against that deadline.

**What stops generated code from stealing a user's session?**
The preview is served with a CSP `sandbox` header without
`allow-same-origin`, so the page runs in an opaque origin. It can run
scripts, but it can't read our cookies or local storage, or call our API as
the user. Published sites get the same treatment. On Kubernetes, each preview
also gets a namespace with no outbound network.

**How does the visual check work without a headless browser on the server?**
The screenshot is taken in the user's browser, inside the preview, with a
small vendored library (html-to-image). It's sent to the parent window,
then to the API, which asks a vision model to score it from 1 to 10 against
the original request. If the model reports a major issue, the workbench
sends one automatic fix request. That's capped at one round so it can't
argue with itself forever. The trade-off: it only runs while someone has the
preview open.

**Why hybrid search and not just vectors?**
Honestly, it was close, and the benchmark is what showed that. Keyword search
is strong on exact names like `applyPromoCode` but weak on paraphrases ("how
much does it cost to bring the groceries to my house"). Vectors are the
opposite. Reciprocal rank fusion merges the two ranked lists.

With the two weighted equally, vector-only actually beat the hybrid. The
keyword list's noise pushed right answers down on paraphrased questions. So I
built a harder set: 70 questions, with 11 decoy files that borrow the real
files' vocabulary. Then I swept the keyword weight. At 0.25:
- the hybrid finds the right file first 87% of the time, up from 81%;
- it finds it in the top 3 99% of the time, up from 96%;
- it improved on both the original questions and the new decoy ones, so it
  isn't just fitted to one half.

It ties vector-only overall (87% top-1, MRR 0.92 against 0.93). Where it
earns its place is exact names: 100% against vector's 90%, and that's the
kind of question the agent's `search_code` tool asks most. So 0.25 is now the
default, and a test pins it.

**How did you make the API stateless?**
Three things lived in memory, and all three moved to Redis:
- **The chat lock.** One reply at a time per chat. It's `SET NX PX`, and the
  release is a Lua script that only deletes the lock if it still holds my
  token.
- **The live log stream.** A Redis list for replay by sequence number, plus
  pub/sub to say "new line".
- **The fake checkout sessions.**

Then I ran the full browser test through the gateway against two instances
and checked that requests split evenly and that a log line produced on one
reached a viewer on the other.

**Why Kafka? Isn't that overkill?**
At this scale, honestly, yes, and the free deployment uses an in-process bus
behind the same interface. The reason it exists: indexing and redeploying
are slow and shouldn't block the user's request, and they're independent of
each other. With Kafka, each one is a consumer group that can fail, retry
and scale on its own, and adding a third consumer doesn't touch the
producer.

**What happens if the same file content is written twice?**
Nothing new is stored. The S3 key is the hash of the content. That also
makes version history cheap: a version is a list of (path, hash) pairs.
The downside is that nothing is ever deleted on overwrite, so there's a
nightly mark-and-sweep job:
1. List the bucket first.
2. Mark every key a row still points to.
3. Delete what's unmarked and older than 24 hours.

A Postgres advisory lock makes sure only one replica sweeps at a time.

**How does publishing work, and how is rollback instant?**
A published site points at a version (a checkpoint), never at the live
files. Publishing is one `UPDATE` that moves a pointer, so a visitor sees
the old site or the new one, never half of each. Rollback is the same
operation pointed at an older version.

**How do other AI agents use it?**
There's an MCP server at `/mcp`. Each user creates personal access tokens.
A token is shown once and only its SHA-256 is stored, so an agent like
Claude Code acts as that user, with their projects and their quota. A token
can't create other tokens, so a leaked one can't entrench itself. `/mcp` is
closed by default.

**How do you test something that calls an LLM?**
Two layers:
- **Tests.** A scripted model plays back tool calls, so the self-healing loop
  is tested on every push, deterministically, against a real Postgres.
  Before trusting a test I break the feature on purpose and check it goes red.
- **Evals.** Twenty golden prompts against the real model, and a retrieval
  benchmark with labelled answers. Those measure quality, which a unit test
  can't.

**What would you do next?**
- Run the Kubernetes previews on a real cluster. Today they're tested
  against a fake API server that speaks the real protocol.
- Add a server-side browser so the visual check runs without anyone watching.
- Measure how often the visual check's fix round actually raises the score.

---

## 4. Bugs worth telling

Each one is a good answer to "tell me about a hard bug". The shape is the
same: what I saw, why, and what I changed so it can't come back.

**The visual check said a working React app was blank.**
A user's habit tracker rendered fine, but the AI check scored it 1/10,
"completely blank", and sent the agent off to fix a problem that didn't
exist. The screenshot fired just after page load. A React preview isn't drawn
at load, though: it fetched seven source files one after another, which takes
seconds over a real network and no time at all on localhost. I reproduced it
with 400 ms of simulated latency, then made three changes:
- fetch all the files in parallel;
- wait until the page reports it's done and the DOM has been quiet for
  400 ms;
- retake a photo that's a single colour.

The browser test now runs on a throttled connection. The lesson: everything
passed locally, and the test that mattered was the slow one.

**The streaming reply that broke at the very end.**
The first browser test failed at step two: the reply never appeared, but the
server said the run succeeded. When a streamed response finishes, Tomcat
sends the request through the filters once more (an async dispatch). My JWT
filter only ran once per request, so on that last pass nobody was logged in,
and Spring Security wrote an error onto a half-sent response. None of 122
tests caught it, because MockMvc never does that final dispatch. The fix was
one line. The regression test runs a real Tomcat and reads the stream over a
socket.

**Live logs went silent after every deploy.**
The browser had seen log lines up to #6 from the old server. The new server
started counting from #1 again, so the browser asked for lines "after #6" and
threw every new one away. Sequence numbers now start from the clock, so they
always rise across restarts. The server also treats an id "from the future"
as "send everything".

**The screenshot library that couldn't work in a sandbox.**
html2canvas renders by cloning the page into a child iframe. Inside a
sandboxed preview, that child is another opaque origin, and the browser
blocks it. html-to-image renders in place through an SVG `foreignObject`,
which works. The library's own warnings were also showing up as the app's
runtime errors, so the error bridge is muted while it takes the photo.

**The S3 server image disappeared.**
In 2025, MinIO stopped publishing community Docker images, and CI broke.
Because I'd written the S3 client by hand against the protocol, not a vendor
SDK, switching to another S3-compatible server was a config line, not a code
change.

**The test suite ran Postgres out of connections.**
Spring caches one application context per distinct test configuration, and
each one held a pool of 10 connections. With enough configurations, Postgres
hit its limit. The test profile now caps each pool at 4.

---

## 5. Resume lines

Pick three or four. The one bracketed number still needs the retrieval v2
run; fill it in from that output.

- Built an AI app builder (Java 21, Spring Boot) whose agent must pass a
  build check before finishing; **20/20** generated apps build on a golden
  eval set, averaging **~11 s, ~7.5k tokens and $0.003** per app at list
  price; **5/5** follow-up changes applied as targeted edits, not rewrites.
- Added a vision-model self-review: the preview screenshots itself
  in-browser, gets scored against the request, and triggers one automatic
  fix round. Found and fixed a latency-only bug by reproducing a 400 ms
  network in CI.
- Designed hybrid code search (pgvector/Qdrant + full-text, weighted
  reciprocal rank fusion, optional LLM rerank) and a 70-question retrieval
  benchmark with decoy files; used it to retune the fusion weights, raising
  top-1 accuracy from **81% to 87%** and top-3 from **96% to 99%**.
- Built an event-driven backend from the system design: gateway with
  failover, a stateless API running as two replicas (Redis locks and
  pub/sub), Kafka-driven indexing and preview redeploys, content-addressed
  S3 storage, and a Kubernetes namespace and pod per preview.
- Shipped immutable version history and one-click publish and rollback
  (a site is a pointer to a version), plus an MCP server with hashed
  personal access tokens so other AI agents act as the user.
- 219 tests against real Postgres, Redis, Qdrant and S3 in CI, and a
  Playwright walk-through of the whole product.

---

## 6. Things not to overclaim

Say these before someone else does:
- Kubernetes previews are tested against a fake API server, not a real
  cluster.
- The visual check only runs while someone has the preview open.
- The live deployment runs one process; the multi-service topology runs from
  `docker-compose.full.yml` and `deploy/k8s`.
- Overall, the tuned hybrid only ties vector-only search. Its edge is on
  exact names, not across the board. And the weights were tuned on the same
  70 questions they're scored on. They improved on both halves, but a fresh,
  held-out set would be the real test.
