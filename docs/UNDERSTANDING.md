# Understanding ForgeFlow

A from-scratch explanation, in the order it was built. Each chapter starts in
plain English, then gets specific, then ends with **counter-questions** — the
questions an interviewer asks next. Try each one out loud before opening the
answer.

---

## The running analogy: a building site

Transakt is a restaurant. ForgeFlow is a **building site**.

| ForgeFlow | On the site |
|---|---|
| The user's prompt | The client's brief — *"build me a house with a garden"* |
| The model (Gemini) | The **contractor** — fast, clever, occasionally careless |
| `AgentService` | The **site foreman** — runs the day, keeps the diary, enforces the budget |
| The tools | The only jobs the contractor may ask for: survey the plot, open a drawing, build a room, declare it done |
| The path guard | The **plot boundary** — no building on the neighbour's land |
| The build check | The **building inspector** — no certificate, no handover |
| Build errors | The inspector's **snag list** |
| The sandbox | A **sealed test yard**, away from any real street |
| The preview | The **show home** you can walk round |
| `generation_runs` | The **site diary** — what happened, how long, what it cost |
| The caps | The **budget and the deadline** |
| JWT | A **visitor badge** at the site gate |
| The MCP server | The **trade line** — a standard phone line another firm's foreman can call to commission work |
| Project members | The **keyholder list** — who may walk in, who may build, who may only look |
| A chat session | The **job book** — every instruction and every reply, in order |
| The logs stream | The **site radio** — everything happening on site, as it happens |
| A plan (FREE / PRO) | The **contract tier** — how many sites, how many show homes, how many contractor-hours a day |
| The Stripe webhook | The **bank's signed letter** — the only proof of payment the office accepts |
| Rate limiting | The **turnstile at the gate** — a few people can rush through, then it lets one in every so often |
| RAG | The **site archivist** — finds the right few drawings in a filing room too big to carry around |
| A trace id | The **job ticket number** — stamped on every form, delivery note and inspection for one job |

The most important thing on this site: **the contractor never touches the
building directly.** They ask the foreman, and the foreman checks every request
before anything happens.

---

## Chapter 1 — The big picture

You type *"build me a recipe page."* Fifteen seconds later there's a working
website with an `index.html`, a stylesheet and some JavaScript. Then you type
*"add a dark mode toggle"* and it changes just that.

Under the hood, that's a **loop**. The model looks at the request, decides to
write a file, and asks for it. ForgeFlow writes the file and tells the model how
it went. The model looks again, decides the next step, and so on — until it
says it's done, and an inspector checks the work.

Everything else — logins, databases, streaming — exists to support that loop.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Isn't this just ChatGPT writing code?**
No. ChatGPT gives you text and you copy it. Here the model never writes code into
its reply at all — it *asks for files to be written* through tools, and ForgeFlow
decides whether to allow each request. Then an automated check verifies the result
before the run is allowed to finish.

**Q: What does it build, exactly?**
Plain static websites — HTML, CSS and vanilla JavaScript, no build step. That was a
deliberate choice: checking a static site takes about 200 milliseconds, while a
React project would spend a minute on `npm install` every single time.

**Q: What's the hardest part?**
Not the model — the model is an API call. The hard part is everything around it:
stopping it from doing damage, stopping it from running forever, and knowing
whether what it built actually works.

</details>

---

## Chapter 2 — The skeleton

**Plain English.** Before the site can open, you need the office, the filing
cabinets, and a way to know the lights are on.

- **Spring Boot** is the office. One Java application.
- **PostgreSQL** is the filing cabinet. It runs in Docker so it's identical on
  any machine.
- **Flyway** sets up the cabinet's drawers. `V1__init.sql` describes every table,
  and Flyway runs it once, on first start.
- **`/actuator/health`** is the lights. If it says `UP`, the app is running and
  can reach the database.

**The decision that matters:** `ddl-auto: validate`. Hibernate (the part that turns
Java objects into database rows) is only allowed to *check* the tables, never
change them. If a Java class disagrees with a table, the app refuses to start —
and the error names the exact column.

The Postgres image is `pgvector/pgvector:pg16`, not plain Postgres, because the
very first line of the migration creates the `vector` extension — used later for
search — and plain Postgres doesn't include it.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why not let Hibernate create the tables? It's less work.**
Because then two things would be editing the schema — Flyway and Hibernate — with
no coordination. Your real database drifts away from your migration files, and you
find out in production. With `validate`, Flyway is the only writer.

**Q: What happens if a migration fails halfway?**
Postgres runs the migration inside a transaction, so it rolls back completely —
nothing half-created. And because Flyway runs at startup, the app doesn't start at
all. You fix the migration and try again.

**Q: Why did you create tables you weren't using yet?**
To make the design decisions early. Writing `generation_runs` on day one forced me
to decide what a "run" is and what I'd measure — which shaped the agent loop before
I'd written it.

**Q: Why ports 5433 and 8081?**
My other project, Transakt, already uses 5432 and 8080. Different ports mean the
two can run side by side without one silently connecting to the other's database.

</details>

---

## Chapter 3 — Who are you? (authentication)

**Plain English.** The site gate. You sign up once, then log in and get a
**visitor badge** — a JWT. You show the badge on every request, and the guard
checks it's genuine without phoning head office.

- **Passwords** are stored as **BCrypt hashes**, never as text. BCrypt is
  deliberately slow and adds a random salt, so even two identical passwords
  produce different hashes.
- **The JWT** is signed with a secret only the server knows. Change one character
  and the signature fails. It carries the user's **ID**, and expires after two
  hours.
- **`JwtAuthFilter`** reads the `Authorization: Bearer …` header on every request.
  A valid badge becomes "this is user 1." A bad one is simply ignored — you stay
  anonymous.

<details>
<summary><b>Counter-questions</b></summary>

**Q: What's the difference between 401 and 403?**
401 means *"I don't know who you are."* 403 means *"I know exactly who you are, and
you still can't."* An anonymous request to a protected endpoint must be 401.
ForgeFlow originally returned 403 — Spring Security falls back to a 403 entry point
when you haven't configured one — so I set an explicit one that sends 401.

**Q: Why does a bad token give 401 and not a 500 error?**
The filter catches the failure and just carries on without logging anyone in. Then
security sees an anonymous request and sends 401. If the filter threw instead, it
would happen *before* Spring's error handling can see it, and you'd get a 500.

**Q: Why put the user ID in the token and not the email?**
Emails change; IDs don't. A token issued before someone changes their email would
otherwise point at the wrong account, or at nothing.

**Q: Why does login give the same error for a wrong password and an unknown email?**
Because different errors would let anyone test which emails are registered. Same
status code, byte-identical message, both ways.

**Q: What's the weakness of JWTs?**
You can't revoke one before it expires. It's checked locally, with no database
lookup — which is the whole speed benefit, and the whole problem. That's why expiry
is two hours, not two weeks.

</details>

---

## Chapter 4 — Whose is it? (ownership)

**Plain English.** Your badge gets you onto the site, but only into *your* houses.

Three rules, each closing a different gap:

1. **The owner comes from the badge, never from the request.**
   `CreateProjectRequest` has no `ownerId` field at all. Send `"ownerId": 999`
   and it has nowhere to land — the project is created under your ID.
2. **Ownership is part of the database query.**
   `findByIdAndOwnerIdAndDeletedAtIsNull` — the check isn't a line of Java that
   someone could forget to write. It's what the query *is*.
3. **Someone else's project returns 404, not 403**, with the same message as one
   that doesn't exist.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why 404 and not 403 for another user's project? 403 is more "correct."**
403 confirms the project *exists*. An attacker could walk through IDs 1, 2, 3… and
map every project on the system. 404 with an identical message makes "not yours"
and "doesn't exist" indistinguishable. GitHub does the same with private repos.

**Q: What's wrong with checking `if (project.getOwnerId() != caller)` in Java?**
Nothing, until someone adds a new endpoint and forgets it. Putting the owner in the
query means there's no version of that endpoint that skips the check.

**Q: Why not reject a request that includes `ownerId`?**
Rejecting means writing validation code that has to be correct forever. Leaving the
field off the class means the value is simply never read. You make the lie
impossible instead of detecting it.

**Q: What's a soft delete, and why use one?**
Setting `deleted_at` instead of removing the row. The data survives mistakes, and
every query filters on `deleted_at IS NULL` so deleted projects behave as if gone.

</details>

---

## Chapter 5 — Talking to the contractor (the model)

**Plain English.** The contractor lives off-site. The foreman phones them with
the full situation, and they reply with what they want to do next.

- **`LlmClient`** is the phone line: one method, `chat(...)`. It doesn't care which
  contractor is on the other end.
- **`GeminiClient`** is one particular contractor: it builds the HTTP request,
  sends it with Java's built-in `HttpClient`, and reads the reply.

Each call sends three things:
1. **The system prompt** — the standing rules (`AgentPrompt`)
2. **The history** — everything said so far in this run
3. **The tool list** — the jobs the contractor may request

**The subtle part: echo the reply back exactly.** Gemini 2.5 thinks before
answering, and attaches a `thoughtSignature` to its reply. On the next call, its
previous reply has to go back *exactly as received*. Rebuild it from your own
fields and the signature is lost — the model forgets its own reasoning mid-job.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why not use Spring AI, like most Spring projects?**
Spring AI's stable version targets Spring Boot 3. ForgeFlow is on Boot 4, which
needs an unreleased milestone from a separate repository. And writing the client
myself means I can explain exactly what a turn contains — which is what gets asked.

**Q: How would you switch to Claude or OpenAI?**
Write one more class implementing `LlmClient`. The agent loop never mentions Gemini.

**Q: A run reported 5,461 prompt tokens and 1,258 completion tokens, but a total of
7,102. Where did the rest go?**
Thinking tokens. Gemini 2.5 reasons before answering and bills for it. Prompt plus
completion misses it, so ForgeFlow records the API's own total.

**Q: Why does the context grow every round?**
The model has no memory between calls. Every call re-sends the whole history. That's
why there's a cap on input tokens, and why prompt caching — reusing an unchanged
prefix cheaply — is on the list.

</details>

---

## Chapter 6 — The loop (the heart of it)

**Plain English.** The foreman's day, on repeat:

1. Phone the contractor with the brief, the history, and the list of allowed jobs.
2. The contractor asks for jobs — *"survey the plot," "build the kitchen."*
3. The foreman checks each request against the rules, does the allowed ones,
   and notes the result.
4. Phone back with the results. Repeat.
5. Stop when the contractor declares done — or the budget runs out.

**The four tools:** `list_files`, `read_file`, `write_file`, `finish`. That's the
entire list. There's no "run a command" — **the allowlist is the security
boundary.** The contractor can't do anything the foreman doesn't implement.

**The plot boundary.** Every path the model asks to write is checked: no `..`, no
leading `/`, no null bytes. The model writes these paths in response to whatever a
stranger typed, so they're treated as hostile.

**A refused job is information, not a crash.** Ask to build on the neighbour's land
and the reply is `ERROR: path may not contain '..'`. The contractor reads it and
tries again properly.

**The budget.** 25 tool calls, 120,000 input tokens, 240 seconds. When a run ends,
its `stop_reason` says why: `FINISH_TOOL`, `NO_TOOL_CALL`, `MAX_TOOL_CALLS`,
`TOKEN_BUDGET`, `TIMEOUT`, `MAX_REPAIRS`, or `ERROR`.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why tools? Why not just ask for the code?**
Because then you're pulling code out of prose with a regular expression, which
breaks the first time the model writes a stray backtick. Tools give structured
requests you can check one by one — and they let the model *read* files before
editing, instead of guessing.

**Q: What stops the model deleting everything?**
There's no delete tool, and no command tool. It can only list, read, write — within
the project, checked paths — and finish.

**Q: Why return errors to the model instead of throwing?**
Because the model can often fix the problem itself. A thrown exception ends the run
over something the next round could have corrected.

**Q: What if the model loops forever?**
The caps. Every exit records which one tripped, so a stuck run shows up as
`MAX_TOOL_CALLS` in the database rather than as a mystery.

**Q: What's `NO_TOOL_CALL`?**
The model stopped by writing a paragraph instead of calling `finish`. It's recorded
separately because it's a real model behaviour worth counting — not quite a
failure, not a clean finish.

</details>

---

## Chapter 7 — Watching it happen (streaming)

**Plain English.** Instead of waiting 15 seconds for a final report, the client
watches through the site window as each room goes up.

**Server-Sent Events** keep one HTTP response open and write updates into it:
`status`, `thinking`, `tool`, `file`, `build`, `repair`, `done`.

**We stream our own events, not the model's words.** When I tried Gemini's
streaming mode, a tool call arrived as **one complete chunk**. It can't be sent in
halves — half a JSON request means nothing. So the meaningful unit of progress is a
*finished tool call*, and those are events ForgeFlow already knows about.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why check ownership before opening the stream, not inside the background task?**
Once a streaming response starts, its status code has already been sent as 200.
Check afterwards and someone else's project gets "200 OK, then an error event."
Checking first gives a proper 404.

**Q: What happens if the browser closes mid-run?**
Sending the next event fails — but that failure is caught, so the run carries on
and finishes. Files written stay written. A closed tab isn't a reason to throw
work away.

**Q: Why virtual threads?**
A generation spends almost all its time waiting for the model to reply. A normal
thread sitting idle is wasted memory; virtual threads make waiting nearly free, so
many runs can wait at once.

</details>

---

## Chapter 8 — When the contractor says "slow down" (rate limits)

**Plain English.** Gemini's free tier takes **5 calls a minute**. A single run makes
one call per round, so it routinely hits that limit mid-job — and at first, the
whole run just died.

Now `GeminiClient` retries:
- **429 (too many requests) and 5xx (server trouble)** — retried, up to 5 times.
- **Other 4xx** — not retried. A broken request is just as broken next time.
- **How long to wait:** Google's error says exactly (`retryDelay: 6.9s`), so we
  honour that. If it's missing: 2s, 4s, 8s… plus a random extra up to half a
  second.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why add randomness to the wait?**
If several runs are limited at the same moment and all wait exactly 4 seconds, they
all retry at the same instant — and get limited together again. A little
randomness spreads them out. That's called jitter.

**Q: Why not just retry everything?**
Retrying a request the server rejected as malformed only wastes time. It'll fail
identically.

**Q: What did this cost you?**
Wall-clock time. The Day 6 demo took 114 seconds, mostly waiting on the limit. On a
paid key it's about 20. I raised the run timeout from 90 to 240 seconds for it.

</details>

---

## Chapter 9 — The sealed test yard (the sandbox)

**Plain English.** The code was written by a model, prompted by a stranger. You
don't plug that straight into your house. You test it in a sealed yard first.

**Two containers, two jobs, two levels of lockdown:**

**The build container** reads the generated code, so it's locked down hard:
no network at all, a read-only filesystem, not running as root, every Linux
permission removed, and caps on CPU, memory, number of processes, and time.
Inside, `check.js` confirms `index.html` exists, every JavaScript file *parses*,
and every file the page links to actually exists.

**The preview container** only serves files to a browser. The JavaScript runs in
the *viewer's browser*, which has its own sandbox. So preview is lighter — and
only reachable from your own machine (`127.0.0.1`), not your Wi-Fi.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Does the build actually run the generated code?**
No. `vm.Script` *compiles* JavaScript without executing it. A syntax error is caught;
nothing runs. The lockdown is defence in depth — and it'll matter more once
ForgeFlow builds React apps that do run scripts.

**Q: You already check paths in the tools. Why check again?**
The first check protects the database. The second protects the real disk, where a
path becomes an actual file. Two independent checks mean one bug isn't enough.

**Q: What happens if the build hangs forever?**
After 30 seconds the Java side kills the `docker` command — but that alone doesn't
stop the container, because the command is just a client talking to Docker. So
ForgeFlow also removes the container by name.

**Q: Why did you use the docker command instead of a Java library?**
Same reason as skipping Spring AI: no library to version-match against Boot 4, and
every security flag sits visibly in the code.

**Q: Why did the preview container not use `--rm`?**
`--rm` deletes a container the moment it stops — logs included. If a preview fails
to start, I want those logs to explain why.

</details>

---

## Chapter 10 — The inspector (self-healing)

**Plain English.** On a real site, the contractor saying *"done"* isn't enough. The
inspector has to sign off. No certificate, no handover.

In ForgeFlow, the inspection happens **inside `finish`**. When the model calls it,
ForgeFlow runs the build before replying:

- **Passes** → "Build passed." The run ends, `SUCCEEDED`.
- **Fails** → `finish` replies with the snag list: *"You can't finish yet —
  `broken.js: SyntaxError`."* The model reads it, fixes the files, calls `finish`
  again. Up to three times.

**The demo that proves it.** A broken file was planted. The prompt only asked for a
footer. The model built the footer, called `finish` — and was stopped. It read the
error, fixed a file it had never touched and wasn't asked about, and only then got
out. Its own summary: *"…and fixed a syntax error in broken.js."*

**What "done" means now.** Before: the model *says* it's finished. After: the code
*passes the check*. So a run's **status** comes from the build, and its **stop
reason** separately records how the loop ended.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why put the check inside `finish` instead of running it after the loop?**
Three reasons. The model can't skip it — the only exit goes through it. A failure
arrives as a tool result, which models handle well. And it avoids sending two user
messages in a row, which Gemini can reject: after the loop, the last message is
already on the user side.

**Q: What if the model can never fix it?**
Three repair rounds, then the run ends `FAILED` with stop reason `MAX_REPAIRS`, and
the database records the build as failed.

**Q: What if the model just stops without calling `finish`?**
The build runs anyway. If it passes, the run is `SUCCEEDED` with stop reason
`NO_TOOL_CALL` — good code, untidy ending, and still visible for measurement.

**Q: Why are status and stop reason separate fields?**
They answer different questions. Status: *did it work?* Stop reason: *how did it
end?* Merge them and you can't tell "worked but ended messily" from "ended cleanly."
That difference is exactly what an evaluation needs to count.

**Q: How would you prove self-healing actually helps?**
That's the next job: evals. Run the same twenty prompts with and without the gate,
and compare how often the final code passes.

</details>

---

## Chapter 11 — The shell is a language, not a text substituter

**Plain English.** Most people read this line as *"put the value of `m` where
`$m` is"*:

```bash
curl ".../models/$m:generateContent"
```

That is wrong, and the wrongness stays invisible until it costs you an hour. The
shell is a **language with its own parser**. It reads your line as syntax first,
and only then hands the resulting text to the program. If a character means
something in that language, the shell acts on it before `curl` ever sees it.

Three characters caused three separate failures in one afternoon: `:`, `*`, and
the quoting on a heredoc.

### Instance 1 — the colon is a modifier in zsh

zsh lets you hang *history modifiers* off an unbraced parameter expansion with a
colon. `$file:t` is the tail of a path, `$file:r` strips the extension, `$file:e`
gives the extension. This works inside double quotes too. bash does not do it.

So zsh reads `"$m:generateContent"` as: variable `m`, then modifiers. With
`m=gemini-2.5-flash` the URL becomes:

```
models/5-flashnerateContent
```

Confirmed directly:

```bash
m=gemini-2.5-flash
echo "models/$m:generateContent"     # models/5-flashnerateContent
echo "models/${m}:generateContent"   # models/gemini-2.5-flash:generateContent
```

Braces end the variable name explicitly, so the colon is just a colon.

### Instance 2 — the colon collides with Spring's syntax

Spring's placeholder syntax is `${VAR:default}`. zsh's modifier syntax is
`${VAR:modifier}`. Identical punctuation, different languages. Pasting a line of
`application.yml` into a terminal gives `zsh: unrecognized modifier`. The YAML
was correct; the shell was the wrong place to put it.

### Instance 3 — the asterisk is a glob

```bash
grep -rn "Mapping" src/main/java --include=*Controller.java
# zsh: no matches found
```

The `*` never reached grep. zsh tried to expand it against filenames in the
current directory, found none, and **aborted the whole command**. bash would
have passed the unmatched pattern through; zsh refuses by default. Quoting fixes
it: `--include="*Controller.java"`.

### The rule

**Quote anything you mean literally.** `*`, `:`, `{`, `$`, `~`, `?`, `[` are all
syntax in the shell's language, not text in yours. Three habits:

- `${var}` not `$var` when anything follows the name
- `<<'EOF'` not `<<EOF` for a heredoc you want taken literally
- quote any argument containing a glob character

### Why this project specifically keeps hitting it

| what broke | layer A | layer B |
|---|---|---|
| `ForgeFlowApplication.java` vs class `ForgeflowApplication` | macOS filesystem (case-insensitive) | Linux filesystem (case-sensitive) |
| `Context.md` vs `CONTEXT.md` in Transakt | same | same |
| `$m:` vs `${m}:` | your intent | zsh's parser |
| `${VAR:default}` in YAML vs in zsh | Spring's parser | zsh's parser |

All four are **one string interpreted by two layers with different rules.** The
Java has never hit this class of bug, because Java string concatenation has no
opinion about colons, asterisks or case. These bugs live exactly where two
languages meet.

<details>
<summary><b>Counter-questions</b></summary>

**Q: The first probe loop printed *nothing at all* for every model; a later loop
printed `404` for every model. Both used the same mangled `$m:`. Why the
difference?**
Look at the mangled path: `models/5-flashnerateContent` has **no colon in it**.
Google's API routes on `models/{name}:{method}`; with no `:method` the request
never reaches the model router and dies at the gateway with a bare 404 and no
body. The later loop used `-w '%{http_code}'`, which prints a status even when
the body is empty. Same 404, two different origins — and `curl -s` was hiding
the difference.

**Q: `curl -s` hides curl's own errors. Name two failures that look identical
under it.**
A DNS or connection failure (curl never got a response) and a gateway 404 with
an empty body (curl got a response with nothing in it). Both print a blank line.
`-w '%{http_code}'` separates them: the first prints `000`, the second `404`.

**Q: The YAML line was `${FORGEFLOW_MODEL:gemini-3.5-flash-lite}}` — two closing
braces — and YAML rejected the file. Suppose it had been quoted. What then?**
YAML would have parsed it happily, Spring would have resolved the placeholder,
and `GeminiClient` would have received the model name `gemini-3.5-flash-lite}`
with a trailing brace. The app starts, looks healthy, and every generation 404s.
**A crash at startup is a gift** — it cost three minutes and pointed at the file.
The quiet version costs an afternoon.

**Q: The over-indented YAML produced *"mapping values are not allowed here, line
29, column 12"*. The line had six leading spaces. Why column 12?**
Count: `m`=7, `o`=8, `d`=9, `e`=10, `l`=11, `:`=12. The parser is objecting to
the **colon**. At indent 6 the line cannot start a new key under a parent at
indent 4, so YAML reads `model` as a continuation of the previous value — a
plain multi-line scalar — and a `:` inside a plain scalar is illegal. The column
number named the exact character, and reading it would have saved two wrong
guesses.

</details>

---

## Chapter 12 — What an eval suite is actually for

**Plain English.** A test asks *"is this correct?"* An eval asks *"is this better
or worse than it was?"*

The difference matters because an LLM-backed system has no single correct
output. `index.html` can be written a thousand valid ways. The only question you
can meaningfully answer is a **comparative** one.

That is why `golden.json` says at the top: keep this file stable. The prompts are
not good because they are clever. They are good because they **do not change** —
a fixed measuring stick against a moving system.

### The four checks, and why each exists

- `build_passed` — the sandbox approved it. Necessary but weak: an empty file
  parses fine.
- `has_index` — there is an entry point. Catches a run that wrote CSS and quit.
- `no_empty_files` — nothing under 20 bytes. Catches the stub.
- `js_when_needed` — a prompt asking for behaviour produced ≥100 bytes of JS.

The fourth exists because of an observed failure: the agent would create a
near-empty `app.js` purely to satisfy the system prompt's file convention,
passing the first three checks while producing something that does nothing.

**Every check in an eval suite should be traceable to a failure you actually
saw.** Checks invented in the abstract test what is easy to measure rather than
what matters.

### Hard assertions versus soft metrics

`build_passed` and `has_index` are pass/fail. Tokens and duration are recorded
but never asserted on, because they vary run to run — a suite that fails on a
10% token swing is a suite you will start ignoring. Soft metrics earn their keep
in the **diff between runs**, not in any single run.

<details>
<summary><b>Counter-questions</b></summary>

**Q: The harness recorded the 2.5-flash collapse on 26 Sep at 01:16 — 1/20,
119.2s mean — a full day before anyone investigated. Why did that signal not
reach anyone?**
Because nothing was watching. The run wrote a JSON file to disk and that was the
end of it. That is precisely the gap between *having a test suite* and *having
CI*: a result nobody is notified of is a result that does not exist. It is the
single most valuable thing this project is still missing.

**Q: The durations went 13s, 17s, 9s, 20s, then 17s, 230s, 285s, 225s, 583s. Two
explanations fit. What one extra piece of data would separate them?**
Per-call HTTP status, logged during the sweep. Steady 200s getting slower means
the model is degraded. A burst of 429s means the quota is spent and
`sendWithRetry` is backing off. The run-level timing cannot tell them apart;
the status codes can.

**Q: `js_when_needed` uses a 100-byte threshold. Give a file that passes it and
is still worthless, and a legitimate one that fails.**
Worthless pass: 100 bytes of comments, or a single `console.log` padded out.
Legitimate fail: a genuinely tiny but correct handler —
`document.querySelector('button').onclick=()=>alert('hi')` is under 100 bytes and
does exactly what a simple prompt asked. Threshold checks are proxies: cheap,
directionally useful, and wrong at the edges. The honest framing is that it
catches the *specific* failure it was written for, not that it measures quality.

**Q: Every case creates a fresh project, so a later case cannot pass on an
earlier one's files. But real users edit existing projects — that is most of
what the product does. What is the suite therefore not testing?**
Iteration. Nothing in the twenty cases exercises "generate, then change what is
already there", which is where `read_file` matters, where the model can overwrite
work it should have kept, and where diff-based editing would show up. A case for
it would run two prompts against one project and assert the second preserved
something from the first.

</details>

---

## Chapter 13 — The trade line (MCP)

**Plain English.** So far there are two ways onto the site: the workbench, where
a person types a brief, and the REST API behind it. Both assume a **human**
client.

MCP is a third door, for **machines**. It is a standard protocol an AI assistant
speaks when it wants to use an external tool. The assistant knows nothing about
ForgeFlow — it knows how to speak MCP, asks "what can you do?", and gets back a
list. Expose an MCP server and someone sitting in a different AI tool can say
*"build me a landing page"* and have ForgeFlow do it.

On the site: a **trade line**. A standard phone number another firm's foreman
can ring, in an industry-standard language, to commission work — without
knowing anything about how your site is run.

### The protocol is small

It is JSON-RPC 2.0, which is a twenty-year-old convention for "call a method
over a wire". Every message in:

```json
{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
```

Every message out echoes the `id` with **either** `result` **or** `error`,
never both. Three methods are enough: `initialize` (who are you, what version),
`tools/list` (what can you do), `tools/call` (do it).

The whole server is one `POST /mcp` endpoint and a switch statement.

### The tool list *is* a prompt

`tools/list` returns names, descriptions and JSON Schemas, and the calling model
reads them to decide what to call and how. They are literally part of its
prompt. A vague description produces a model that calls the wrong tool or passes
garbage.

So ForgeFlow's descriptions are written for a reader who knows nothing about it:
`generate_app` says it needs a `project_id` from `create_project`, that it takes
5–20 seconds, and that calling it again on the same project iterates on what is
there.

Three tools, not six. `/build` and `/preview` exist in the REST API and are
deliberately left out — the agent runs the build itself inside `finish`, and a
preview URL means nothing to an agent.

### The distinction that matters most

There are two ways to report a failure, and getting them backwards breaks the
client.

| | means | the client sees |
|---|---|---|
| JSON-RPC `error` | **the client is wrong** — unknown method, malformed JSON | "the server is broken" |
| `result` with `isError: true` | **the world is wrong** — no such project, build failed | a message the model can read and act on |

"No such project" is a **tool** error. It goes back into the calling model's
context, where it can read *"project 99999 not found"* and call `create_project`
instead. That is the same reasoning as the self-healing loop one chapter back:
feed the error to the model and let it recover.

### Nobody is logged in

Every service method takes an owner, and an MCP request has no JWT to derive one
from. ForgeFlow uses a **fixed service account** — `forgeflow.mcp.owner-id`,
default 1. Projects made through MCP belong to that user.

The ownership check still runs inside the executor. That check is what turns a
wrong `project_id` into a readable tool error instead of someone else's files.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why pin protocol version `2025-06-18` when `2026-07-28` exists?**
Because the newest revision is not an increment — it removes the `initialize`
handshake entirely, drops session headers, and adds required `resultType`,
`ttlMs` and `cacheScope` fields. Two things make pinning safe: Anthropic's
clients still perform the handshake, and the newer spec explicitly tells clients
to treat a result from an earlier-protocol server that omits `resultType` as
complete. Same reasoning as pinning the Gemini model instead of using `-latest`:
a moving dependency under something you demo is a liability.

**Q: Why not use Spring AI's MCP starter? It would have taken an afternoon.**
Same reason `GeminiClient` has no SDK: one fewer dependency to version-match
against Boot 4, and the wire format stays visible. The protocol is three methods.
The honest counter-argument is that the starter is maintained and handles
version negotiation — if ForgeFlow had users rather than an interviewer, that
would be the right call.

**Q: A JSON-RPC message arrives with no `id`. What do you do?**
Nothing — return `202` with an empty body. A message with no `id` is a
*notification* and expects no reply. MCP sends `notifications/initialized` right
after the handshake, and answering it confuses clients that are not waiting for
a response.

**Q: `project_id` was first declared `"type": "string"`. What breaks?**
The model reads the schema and sends `"43"` with quotes, and you end up parsing
strings into `Long` at every call site. The schema is not documentation — it is
the instruction the model follows.

**Q: `/mcp` is `permitAll`. Is that acceptable?**
Locally, yes. Publicly, it means anyone who finds the URL can spend the Gemini
quota. The real options are a static API key header (Claude Desktop's
custom-header support is only partial, so it may not survive the connector path)
or proper OAuth 2.1, which is what Claude Desktop's custom connectors assume and
is a substantial piece of work. It is a known gap, not an oversight.

**Q: Claude Desktop's config file takes MCP servers. Why can't you just add a
URL there?**
Because that file is stdio-only — every entry describes a local *process to
launch*, with command, args and env. There is no `url` field. Remote servers go
through Settings → Connectors instead, and that runtime connects from
Anthropic's infrastructure rather than from your machine, so it cannot see
`localhost`. It needs the public Render URL. Claude Code's CLI is the opposite:
it runs locally and `claude mcp add --transport http` reaches localhost fine.

</details>

---

## Chapter 14 — The keyholder list (members and roles)

Until now every project had exactly one person: the owner. The spec wants
teams — an owner, editors who can change things, viewers who can only look.

**Plain English first.** Picture the site gate. The owner holds the master key.
Editors have keys that open the site and the tool shed. Viewers have a visitor
pass: they can walk round, but they can't pick anything up. Everybody else
isn't on the list.

In code that list is the `project_members` table, and every endpoint asks the
same single question before doing anything:

```java
projectAccess.require(projectId, userId, Permission.WRITE);
```

It returns the caller's role, or throws. Three permissions, four roles:

- **READ** — see the project, its files, its chat, its preview and logs
- **WRITE** — generate, chat, build, start a preview (anything that spends compute)
- **ADMIN** — rename, delete, manage members (owner only)

A public project gives every signed-in user READ, and nothing more.

### The 404 / 403 rule

This is the bit worth understanding properly.

- Someone with **no relationship** to the project gets **404 Not Found** — the
  exact same response as for a project id that doesn't exist.
- A **member whose role isn't enough** gets **403 Forbidden**.

Why not 403 for everybody? Because "forbidden" means "this exists, and you
can't have it". A stranger probing ids would learn which ones are real. A
viewer already *knows* the project exists — they can see it in their list — so
403 tells them nothing new, and it's more honest than pretending it's gone.

### The owner isn't a member row

The owner lives in `projects.owner_id`, not in `project_members`. If they were
both, the two could disagree — a member row saying VIEWER for the owner, say —
and then which one wins? One fact, one place.

### The bug this found

Moving every endpoint onto `require(...)` meant reading every endpoint. The MCP
`generate_app` tool turned out to have **no check at all**: any MCP client
could generate into any project by guessing its id. It also acted as user id 1
— whoever happened to sign up first. The fix: a real access check, and a
dedicated passwordless "service account" for MCP. Plus a test that fails if
the check is ever removed.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why return 404 to a stranger instead of 403?**
So probing ids teaches them nothing. 403 confirms the project exists. The test
for this compares the two response bodies and requires them to be identical.

**Q: An editor removes the owner from the members list. What happens?**
Nothing — the owner isn't in that list. Removing members is ADMIN, which an
editor doesn't have anyway, and `ProjectRole.isAssignable()` refuses OWNER as a
role you can hand out.

**Q: Why does starting a preview need WRITE, when it doesn't change any files?**
Because it spends resources — a container, or a live link. "Can this cost
money or capacity?" is the line between WRITE and READ, not "does it change
data?".

**Q: A viewer leaves the project. Whose permission do they need?**
Nobody's. Removing *yourself* is always allowed — you can't be forced to stay
on a project.

**Q: Where would you look first if a user could see a project they shouldn't?**
`ProjectRepository.findAccessible` (the list query) and `ProjectAccess.require`
(every single-project call). Those two are the whole access model. That's the
point of having one place.

</details>

---

## Chapter 15 — The job book (chat sessions and memory)

Before this, every generate started from nothing. You'd say "build a todo
app", then "make the buttons blue", and the second request had no idea there
was a todo app.

**Plain English.** A chat session is a job book. Every instruction the client
gives goes in, and every reply. When the contractor starts the next job, the
foreman reads them the last few pages first.

### What the model actually remembers

The **last ten messages** — not the whole history. Ten is enough to know what
"it" and "the button" refer to, and it keeps every request a sensible size.

Two rules come from Gemini, which **rejects** a conversation that doesn't
alternate user / model, or that starts with the model:

1. If the ten-message window starts with a reply, drop that reply.
2. If two replies sit next to each other — a failure, then a successful retry —
   keep only the later one.

Each past reply is remembered **with the files it wrote**:

```
Built a todo list with add and delete.
[Files written: index.html, styles.css, app.js]
```

So "make the header blue" can work out that the header is in `index.html`
without listing every file again.

### Failed replies stay in the history

If a run fails, the reply is still saved — with `status = FAILED` and a
sentence a person can read ("I couldn't get the build to pass after 3 repair
attempts"). Two reasons: it keeps user / model alternating, and it's what
**retry** retries. Retry only works when the last reply failed — it's for
recovering, not for rolling the dice again on something that worked (that's a
409).

### One reply at a time

Two sends in quick succession would both read the same history and both append
a reply: user, user, reply, reply. Nonsense to a person, rejected by Gemini.
`SessionLocks` lets only one reply be in progress per session; the second send
gets a 409.

### Why a turn is split in two

`begin()` runs on the request thread: access check, lock, save your message.
Anything wrong there is a clean 403 / 404 / 409.

`complete()` runs the agent — which can take a minute — and saves the reply.
It's deliberately **not** one database transaction: holding a connection open
for a minute per reply would let a handful of slow replies exhaust the pool
for everyone.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why ten messages and not the whole conversation?**
Cost and focus. Every message goes into every model call, every round. The
whole conversation grows without bound; ten is enough for "it" and "that
button" to make sense. It's a config value (`forgeflow.chat.memory-messages`).

**Q: The app crashes halfway through a reply. What's left in the database?**
Your message, saved by `begin()`, with no reply after it. The lock was in
memory, so it's gone too. Next message: the window sees user, user — and
collapses them to the later one, so the conversation stays valid.

**Q: Why store `status` on the chat message instead of joining to `generation_runs`?**
So the chat module never has to read the intelligence module's tables. The
message keeps a `run_id` for the details, but answering "did this reply work?"
shouldn't need another module's data.

**Q: Why throw 409 from `begin()` instead of sending an error inside the stream?**
Because once a stream starts, the HTTP status is already 200. An error inside
it is just an event the client has to know to look for. Failing before the
stream opens gives a real status code that every HTTP client understands.

</details>

---

## Chapter 16 — The site radio (the logs stream)

When you run a website on your own laptop, there's a terminal window telling
you what's happening: files being served, a 404 for an image you forgot, the
error your JavaScript just threw. A preview link on someone else's server has
none of that. The spec's "logs stream" puts it back.

**Plain English.** The site radio. Everyone on site can tune in and hear what's
happening: the inspector starting and finishing, the show home opening, every
visitor walking through a door — and every time something in the show home
breaks.

### Four kinds of line

| source | what it is |
|---|---|
| `build` | every build — the agent's own gate *and* the Build button — with each problem on its own line |
| `preview` | started, stopped, expired |
| `http` | every file the preview served, and every **404** |
| `console` | the generated app's own `console.log` / `console.error` and uncaught exceptions |

### The clever bit: hearing the browser

The generated app runs in *the visitor's* browser, not on our server. So how
does its `console.error` reach us?

When the preview serves an HTML page, it slips a tiny script in right after
`<head>` — before the page's own scripts, so it's listening from the start. The
script wraps `console.log/warn/error` and listens for uncaught errors, and
sends each one back to `/p/{token}/__log`.

Two details make that safe:

- The preview page is sandboxed into an "opaque origin" (Chapter 9), so it
  can't read anything of ours. It sends with `navigator.sendBeacon` — a plain
  text POST that browsers allow from anywhere without a permission check, and
  whose response the page never sees.
- Anyone with the preview link could post fake lines. The worst they can do is
  add noise to a log, so the endpoint only works while the preview is live and
  is capped at 120 lines a minute per project.

### Replay, then follow

Opening the stream sends what's already buffered (the newest 500 lines), then
each new line as it happens. Every line has a sequence number, sent as the SSE
event id. If the connection drops, the browser reconnects with `Last-Event-ID`
and gets only what it missed.

There's a small race hidden in "send the backlog, then follow": a line added
between reading the backlog and starting to listen would be lost — or, done the
other way round, sent twice. The stream subscribes and sends the backlog under
one lock, and the live listener skips anything the backlog already covered.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why keep logs in memory and not in Postgres?**
They're high-volume, short-lived, and nobody needs last week's. A bounded
buffer (500 lines × 200 projects) costs a few MB and no queries. The price:
with two app instances, a viewer on one wouldn't see builds on the other. The
fix then is a Redis stream or Kafka topic per project — the code comment says so.

**Q: Why inject the script after `<head>` and not before `</body>`?**
A page's own scripts can throw while they load. If the bridge loads last, it
misses exactly the errors you most want to see.

**Q: A subscriber's browser tab closes. What cleans up?**
The next send to it throws. `PreviewLogs` catches that and removes the
subscriber. The emitter's completion and timeout callbacks unsubscribe too.

**Q: Couldn't the agent use these console errors?**
Yes — and it should, eventually. Today the build gate checks structure. Feeding
runtime errors from a real browser back into the repair loop would catch bugs
the structural check can't. The log already has the data.

</details>

---

## Chapter 17 — The contract tier (plans, quotas and Stripe)

Every model call costs real money, and a free tier with no limits is a free
tier one enthusiastic user can empty for everybody. The spec asks for FREE and
PRO plans with limits on projects, AI tokens and previews, paid for with Stripe.

**Plain English.** Clients sign a contract tier. The basic tier gets three
sites, one show home open at a time, and so many contractor-hours a day. The
premium tier gets more. Before the foreman starts anything, he checks the
client's tier against what they're already using. And the office only changes
someone's tier when a **signed letter from the bank** arrives — not because a
client walks in waving a receipt.

### Three limits, one check

```java
entitlements.requireRoomFor(userId, Quota.PROJECTS);
```

Before creating a project, starting a preview, or running the agent, the code
asks one question. `Entitlements` looks up the plan (a live subscription, or
FREE if there isn't one), asks how much is used, and either returns or throws.

The throw becomes **402 Payment Required**. It's the one HTTP status that
means "this would work if you paid", which is exactly the situation. The body
carries `quota`, `limit`, `used` and `plan`, so a UI can say "3 of 3 projects"
without a second request.

### Who counts what

Billing knows the *limits*. It doesn't know how many projects you have —
that's the workspace module's data. Rather than billing reaching into
workspace's tables, billing defines a small interface:

```java
public interface UsageSource {
    Quota quota();
    long used(Long userId);
}
```

Workspace implements it for projects, execution for previews, and billing
itself for tokens (from `usage_logs`). Billing collects whichever ones exist.
The data stays with its owner, and the arrows point the right way.

### The decisions that aren't obvious

- **Tokens are charged to whoever typed the prompt**, not the project owner.
  Otherwise inviting someone onto your project lets them spend your allowance.
- **The token limit is soft.** It's checked before a run starts. A run that
  starts under the limit is allowed to finish, because stopping halfway leaves
  a half-written app. The overshoot is bounded by one run's own token budget.
- **Restarting a preview isn't a second preview.** The check is told how many
  previews this action will free up, so a restart doesn't count against itself.
- **Shared projects don't count** against your project limit. You didn't make
  them.

### Stripe: the browser lies, the webhook doesn't

The tempting version: send the user to Stripe, and when Stripe sends them back
to `/?billing=success`, upgrade them. But that URL is just a URL — anyone can
type it. **Nothing the browser does grants a plan.**

The plan is granted when Stripe calls our webhook, server to server, with a
`Stripe-Signature` header:

```
t=1700000000,v1=5257a869e7ec...
```

That's an HMAC-SHA256 of `"<t>.<the exact request body>"` using a secret only
Stripe and we know. We recompute it and compare. Three details matter:

1. **Raw bytes.** The signature covers the exact bytes Stripe sent. Parse the
   JSON and re-serialise it, and one reordered key breaks a genuine event.
2. **Constant-time comparison.** An ordinary comparison stops at the first
   wrong byte, so how long it takes leaks how much of a forged signature was
   right. `MessageDigest.isEqual` always takes the same time.
3. **A five-minute window** on the *signed* timestamp. A captured request
   replayed tomorrow fails — and the attacker can't just change `t`, because
   `t` is part of what's signed.

### At least once, in any order

Stripe promises every event arrives **at least** once — so sometimes twice —
and in **no particular** order. Three defences:

- **Event ids are remembered** (`stripe_events`), in the same transaction as
  the change. If the change fails, the id rolls back too, and Stripe's retry
  gets processed. Save the id first and fail afterwards, and the retry would
  be thrown away as a duplicate — a customer who paid and got nothing.
- **Upsert, never insert-then-update.** `subscription.created` can arrive before
  `checkout.session.completed`. Each event carries the full current state, and
  whichever comes first creates the row.
- **Newest event wins.** Each subscription remembers when the last applied
  event was created. A late, older "active" arriving after "deleted" is
  ignored, instead of quietly giving the plan back.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why 402 and not 403 or 429?**
403 means "you may never do this". 429 means "slow down and try again soon".
402 means "this would work on a plan that allows it". Only one of those tells
the client the right thing to do next.

**Q: A user on PRO cancels. When do they lose PRO?**
At the end of the period they paid for. Cancelling sets
`cancel_at_period_end`; Stripe sends `customer.subscription.deleted` when the
period actually ends, and that's when they drop to FREE.

**Q: A card renewal fails. Does the user lose PRO immediately?**
No. Stripe marks it `past_due` and retries over the next few days; PAST_DUE
still counts as live. Taking the plan away the moment one charge bounces
punishes people for an expired card.

**Q: The cancellation webhook never arrives. Is the user on PRO forever?**
No. A live subscription more than three days past its period end isn't
honoured. Three days, not zero, because renewals also arrive by webhook and a
slow one mustn't downgrade a paying customer.

**Q: Two "create project" requests arrive at the same instant from a user with
2 of 3 projects. What happens?**
Both can pass the check and both get created — 4 of 3. It's a check-then-act
race. Accepted on purpose: one extra project costs nothing, and closing it
properly means a per-user lock on every create. If the limit were money, the
answer would be different.

**Q: Why does the MCP service account have its own plan?**
It acts for everyone who calls `/mcp`, so FREE's three projects would last an
afternoon. INTERNAL gives it more room — but it's still capped, because `/mcp`
has no login, and an endpoint with no login must never be able to spend
unlimited model tokens.

**Q: Why no Stripe SDK?**
The whole integration is one form POST and one HMAC check. The SDK would hide
exactly the two things worth understanding — and this build can't download new
libraries anyway.

</details>

---

## Chapter 18 — The turnstile (rate limiting)

Chapter 8 was about the contractor telling *us* to slow down — Gemini's 429.
This is the other side: us telling *clients* to slow down.

**Plain English.** Quotas (Chapter 17) are a daily allowance: you get so many
contractor-hours a day. A turnstile is different: it doesn't care how much
you've used today, only how fast you're pushing through right now. A few
people can rush through at once, then it lets one more in every few seconds.

Why both? Someone with plenty of daily allowance can still fire fifty
requests in one second. Each one is a model call, and fifty at once would hit
Gemini's own per-minute limit — for *everyone* on the server, not just them.

### The token bucket

Every client has a bucket holding up to N tokens. Each request takes one. The
bucket refills steadily, N per minute. Empty bucket → **429 Too Many
Requests**, with a `Retry-After` header saying how many seconds until the next
token.

Why not just "count requests per minute and reset at :00"? Because of the
boundary. With a limit of 10, someone can send 10 at 12:00:59 and 10 more at
12:01:00: twenty in two seconds. The bucket never holds more than 10, however
the requests line up.

### Why Redis

With one server, a bucket in memory is fine. With two, each server has its
own bucket, and a client who alternates gets double the limit. Redis is one
shared place for every server's buckets.

There's a trap, though. "Read the bucket, work out the new value, write it
back" as three separate commands is a race: two servers read "1 token left"
at the same moment, and both spend it. So the whole thing is a small **Lua
script**, and Redis runs a script start to finish without anything else
interleaving. Read-decide-write becomes one step.

The script also asks Redis for the time instead of using each server's own
clock. Server clocks drift, and a fast clock would refill buckets early.

### When Redis is down

Three options:

- **Fail closed** — refuse everything. Redis hiccups and the whole app is down.
- **Fail open** — allow everything. Redis hiccups and there are no limits.
- **Fall back** — use the same bucket in memory. Limits hold per server.

ForgeFlow falls back. The limits get a bit looser (per server instead of
shared) but never disappear.

### Who is "a client"?

- For login and signup, nobody is logged in yet, so it's **per IP address**.
  This is what stops someone trying a thousand passwords a minute.
- For everything else, it's **per user**, from the JWT. Many people behind one
  office IP each get their own allowance.

Getting the IP right on Render takes one setting. Every request arrives from
Render's proxy, which puts the real client IP in an `X-Forwarded-For` header.
But a client can send that header too, and lie. Tomcat's "native" mode only
believes the header when the request came from a private-network address —
the proxy, never the open internet.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Quotas already limit tokens per day. Why rate limit as well?**
Different problems. A quota is about cost over a day; a rate limit is about
load right now. A user well inside their quota can still fire fifty requests
in a second and exhaust the model provider's per-minute limit for everyone.

**Q: Why does the filter have to run after the JWT filter?**
Before it, nobody is authenticated, so every request looks anonymous and all
users share one bucket. One busy user would rate-limit everybody. There's a
test that moves the filter before JWT and checks it fails.

**Q: Why isn't `RateLimitFilter` a Spring `@Component`?**
Spring Boot automatically registers every `Filter` bean as a servlet filter,
which runs before Spring Security. You'd get a second copy, in the wrong
place, limiting everyone as anonymous.

**Q: Redis goes down for ten minutes. What do users notice?**
Nothing, unless they were near a limit. Each server switches to its own
in-memory buckets, logs a warning at most once a minute, and switches back
when Redis returns.

**Q: Why write a Redis client instead of using Lettuce or Jedis?**
Partly necessity — this build can't download new libraries. But the protocol
is genuinely small: five reply types, each marked by its first byte. The one
real bug to avoid is counting characters instead of bytes in a length prefix.

**Q: Someone sends `X-Forwarded-For: 1.2.3.4` to dodge the login limit. Does it work?**
Not on Render. Tomcat only trusts that header when the request came from a
private-network proxy. A request straight from the internet keeps its real
address.

</details>

---

## Chapter 19 — The archivist (RAG)

Chapter 14 of the original plan said RAG would come last, because it only
helps once a project is too big to send whole. That's still true — and now
the machinery exists for when it is.

**Plain English.** On a small job the contractor carries every drawing. On a
big one that's impossible, so the site has an archivist. The contractor says
"I need whatever shows the dark-mode switch," and the archivist comes back
with the three relevant sheets — with page numbers, so the contractor can pull
the full drawing if needed.

RAG — Retrieval-Augmented Generation — is exactly that: before asking the
model to do something, *retrieve* the most relevant pieces of the project and
put them in front of it.

### Step 1: cut files into chunks

A whole file is the wrong unit. Turn a 400-line file into one vector and you
get an average of everything in it, close to nothing in particular. A single
line is too little to mean anything. ~40 lines is roughly one function or one
CSS block. Chunks overlap by 5 lines, so something sitting on a boundary still
appears whole in one of them. Each chunk keeps its line numbers.

### Step 2: turn each chunk into a vector

An **embedding** model turns text into a list of numbers (768 here) such that
texts with similar meaning get similar lists. "Toggle the dark theme" and
"switch to night mode" end up close together even though they share no words.

Embedding models want to know which side of the search a text is on: a
**document** being stored, or a **query** looking for one. They're trained to
put a question near its answer.

### Step 3: search two ways at once

Vectors understand meaning but are fuzzy about exact names. Ask for
`renderTodos` and a chunk that *talks about* rendering might outrank the one
that *defines* `renderTodos`. Plain keyword search is the opposite: exact
names, no understanding.

So both run, and the results are merged with **Reciprocal Rank Fusion**: each
chunk scores `1/(60 + its rank)` in each list it appears in, summed. Two
second-places beat one first-place and one absence. It uses positions only,
so it doesn't matter that a cosine distance and a keyword rank are on
completely different scales.

### Step 4: keep it fresh without redoing everything

Each file's chunks remember a SHA-256 of the file. Re-indexing compares
hashes and only re-embeds files that changed. It runs in the background after
every run, and again (cheaply) before every search — so search is always
current even if the background job hasn't finished.

### When does the agent actually use it?

- **Up to 15 files:** nothing changes. The agent lists and reads the whole
  project, which is more reliable than any retrieval.
- **Past 15:** the request arrives with the top 8 relevant excerpts attached.
- **Always:** the agent has a `search_code` tool for anything else.

### Why Postgres and not Qdrant?

The spec draws a separate vector database. But Postgres already has pgvector
installed, and the chunks sit next to the files they came from. A separate
store means two systems that can disagree — a file deleted in one, its chunks
alive in the other. At this size, one database is simpler and correct.

### The event

When a run changes files, `AgentService` publishes a `CodeGenerated` event.
It doesn't know who listens. Today the indexer does, in the same process. In
the spec's design, that event goes onto a Kafka topic called `code.generated`
and the indexer is a separate service. The event wouldn't change — only how
it's delivered. That's what "seam" means.

### Measuring it (added 10 Oct)

Building RAG is half the job; knowing how good it is is the other half. The
retrieval eval gives search a fixed exam: a 29-file sample shop and 42
questions where we know which file is the right answer. Two scores:

- **recall@5** - for what share of questions is the right file somewhere in
  the top 5? (Like: did the archivist's pile of five sheets include the one
  you needed?)
- **MRR** (mean reciprocal rank) - how high up it was. First place scores 1,
  second 1/2, third 1/3, missing 0; average over all questions.

The questions come in three kinds, because each half of the search is good at
different things: **exact names** (`applyPromoCode` - keywords are perfect),
**plain descriptions** ("evening delivery surcharge" - both work) and
**paraphrases** ("how much does it cost to bring the groceries to my house" -
no shared words, so only meaning-based search can get it). Every question is
asked three ways - hybrid, vector only, keyword only - so the report shows
what each half contributes.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why not embed whole files?**
One vector per file averages everything in it. A file that handles todos,
dark mode and local storage gets a vector that's a bit like all three and
close to none. Chunks keep each idea separate.

**Q: What is Reciprocal Rank Fusion, in one sentence?**
Score each result by `1/(60 + rank)` in every list it appears in and add the
scores up — so agreement between two searches beats a single strong vote.

**Q: The embedding API is down. What happens?**
Indexing stores chunks without vectors, so keyword search still works, and
marks them so the next pass fills the vectors in. Searches embed the query
too; if that fails, the search runs on keywords alone. Nothing errors.

**Q: You switch from one embedding model to another. What breaks?**
Nothing visibly — but vectors from different models live in different
spaces, and comparing them is meaningless. Each chunk stores which model made
it; the indexer sees the mismatch and re-embeds.

**Q: Why are tests using a "hashing" embedder instead of Gemini?**
Tests must be deterministic and work offline. The hashing embedder maps words
to slots in a 768-long list. It only knows about shared words, not meaning —
which is enough to check the pipeline end to end, and honest about what it
isn't.

**Q: The real run said vector-only beat hybrid. Isn't hybrid supposed to be better?**
Usually, not always. Hybrid helps when the two lists are *both* good and get
different things right. When one list has nothing relevant (exact-word
search on a question that shares no words with the code), its guesses still
get votes. So the fix isn't "drop keywords" - they're the best thing for
exact names - but *weight* them: count a keyword rank as half a vector rank,
and measure again. That's what the weighted RRF and the v2 set are for.

**Q: What's a reranker, and why not just use it for everything?**
Search ranks fast but shallow: an embedding compares two summaries; keywords
count words. A reranker reads the question and each candidate *together*
and judges which actually answers it - slower and more accurate. So you use
both: cheap search narrows thousands of chunks to 15, the expensive judge
orders those 15. It's a model call per search, so it's opt-in and metered.

**Q: Why did you make the questions harder instead of celebrating 100%?**
Because a test everyone passes can't tell two methods apart. The v2 decoy
files share vocabulary with the right answers (gift cards next to promo
codes), which is what real codebases look like.

**Q: Why is the score per file, not per chunk?**
Because the real question is "which file do I open?". A file counts at the
rank of its first chunk.

**Q: The embedding API failed halfway through the eval. What would happen?**
Search would quietly use keywords only - fine for users, but then a "vector"
score would really be a keyword score. So search now reports it (response
headers), and the eval waits and retries instead of recording a lie.

**Q: In the offline dry run, keyword-only beat hybrid on recall@5. How can merging make it worse?**
Rank fusion trusts both lists equally. If one list is weak (the offline
embedder only matches words, so its "vector" list is noise for paraphrases),
its wrong guesses get votes too and can push a right answer out of the top
five. With a good embedding model the two lists are both strong and disagree
usefully - which is exactly what the live run checks.

**Q: Can one project's search return another project's code?**
No. Both halves of the search filter by project id, and so does the final
query that joins them. There's a test that searches with two projects
present.

</details>

---

## Chapter 20 — The job ticket (tracing), and the rules on the wall

**Plain English.** Every job on the site gets a ticket number, and everyone
stamps it on everything they touch: the delivery note, the inspector's
report, the contractor's timesheet. When the client calls to complain, you
ask for one number and pull every piece of paper for that job — instead of
leafing through the whole day's paperwork.

That number is a **trace id**.

### How it works here

The very first thing that touches a request (`TracingFilter`, ahead even of
security) gives it a trace id — or, if the caller sent a `traceparent`
header, reuses theirs, so a trace can span several systems. The id goes:

- into the **response headers** (`X-Trace-Id`), so the client has it;
- into every **log line** the request causes (via the logging MDC);
- into every **error body**, so "it said 500" becomes one string to search;
- onto the **generation run** row in the database.

Inside the trace, each step is a **span** with a start time and a duration:
the agent run, each model call inside it, the build. Pointed at Zipkin, a slow
generation shows *which* step was slow, rather than just "it took a minute".

One trap: the trace lives in a ThreadLocal, and ThreadLocals don't follow work
onto another thread. Streaming generations run on a separate thread, so the
work is wrapped (`Tracer.wrap`) to carry the ticket number across.

### The rules on the wall

Two other things landed with tracing, both about keeping promises:

- **The API reference can't drift.** `openapi.yaml` is written by hand, and a
  test compares it with every route Spring actually serves. Add an endpoint
  and forget the docs, and the build fails.
- **Module boundaries can't drift.** The architecture says modules talk through
  service classes and never reach into each other's tables. A test reads the
  source and fails on any import that breaks that rule. It caught a real one
  the moment it ran.

<details>
<summary><b>Counter-questions</b></summary>

**Q: What's the difference between a trace and a span?**
A trace is one whole user action. A span is one timed step inside it. A trace
is a tree of spans, all sharing the trace id, each pointing at its parent.

**Q: Why does tracing start before security?**
So rejected requests have trace ids too. A 401 or a 429 is exactly the kind of
thing someone reports, and it should be just as easy to look up.

**Q: Why name spans `/projects/{id}` instead of `/projects/42`?**
Tracing tools group by span name. One name per project id means thousands of
"operations", each seen once, and no useful averages. That's called
cardinality, and it's the most common way to break a tracing setup.

**Q: Zipkin is down. Does ForgeFlow slow down?**
No. Spans go into a bounded queue and are sent in the background once a
second. If Zipkin doesn't answer, that batch is dropped and counted. A request
never waits on its own telemetry.

**Q: Why test the module boundaries at all? Isn't that what code review is for?**
Code review is a person remembering a rule on a busy day. A test remembers
every time. And it found a violation immediately: three modules importing
another module's database entity just to use its constants.

**Q: Why hand-write the OpenAPI document instead of generating it?**
The generator (springdoc) isn't something this build can download. But
hand-written is fine *if* something checks it — and the contract test does.
The real risk with docs isn't who wrote them, it's that nothing notices when
they go stale.

</details>

---

## Chapter 21 — Walking the site (the browser test that found a real bug)

Unit tests check the parts. A walk-through checks what a person actually
experiences. ForgeFlow now has both, and the walk-through found something
122 tests had missed.

**Plain English.** Every inspection on site had passed. Then someone walked
the show home as a buyer would — and the front door jammed on the way out.
Nothing inside was wrong; the problem was in the last step, which no
inspector had been asked to check.

### What the walk-through does

`scripts/ui-walk.py` drives a real Chrome (headless) through the product the
way a person would: sign up, ask for an app, watch the reply stream in, reload
the page, open the code, start the preview, click a button *inside the
generated app*, check that click shows up in the Logs tab, search, download
the zip, upgrade, share with a second account, check that account can't edit.

To run it without an API key, ForgeFlow has a **demo model**: it writes the
same small page for any request, but through the real pipeline, so the walk
tests the real system and not a mock of it.

### The bug

The very first run failed: the reply never appeared. The server said the run
had succeeded — then logged `AccessDenied`.

When a streamed response (SSE) finishes, the web server sends the request back
through every filter one last time. That last pass is called an **async
dispatch**. ForgeFlow's JWT filter, by design, only runs once per request — so
on that last pass nobody was logged in, and Spring Security threw an error
onto a response that was already half-sent. The browser saw the stream break
off. The old UI happened to ignore that; the new one couldn't.

Why didn't the tests catch it? They use MockMvc, which simulates requests
without a real web server — and never performs that final dispatch. So the
fix (let async dispatches through; the request was already checked on the way
in) comes with a test that starts a **real** server and reads the stream over
a real socket.

<details>
<summary><b>Counter-questions</b></summary>

**Q: 122 tests passed. How did a browser find a bug they missed?**
They tested with a simulated server that skips the step where the bug lived.
Every layer of testing has blind spots; the walk-through's blind spots are
different ones. That's the argument for having more than one kind.

**Q: Is permitting async dispatches a security hole?**
No. An async dispatch is the same request finishing, not a new one. It was
authenticated and authorised when it arrived. Nothing new can arrive this way
from outside.

**Q: Why build a fake "demo" model instead of mocking the API in the browser test?**
A mocked API would test the UI against what I *think* the server does. The
demo model runs the real server — tools, build gate, memory, logs — and only
replaces the part that needs a paid key. That's how the walk found a real
server bug at all.

**Q: Why does the UI read the stream with `fetch()` instead of `EventSource`?**
`EventSource` is the browser's built-in SSE client, but it can't send an
`Authorization` header — and every ForgeFlow endpoint needs the JWT. So the
UI reads the response body itself and splits it into events on blank lines.

</details>

---

## Chapter 22 — Small repairs, and a second inspector

**Plain English.** Two upgrades to the building site. First, the contractor
can now fix a cracked tile without tearing down the wall — that's `edit_file`.
Second, there's a second inspector. The first one (the build gate) checks the
drawings: every door leads somewhere, every wall is where it says. The new one
watches people actually *using* the show home — and when a door jams in
someone's hand, the report lands on the contractor's desk before the next job.

### edit_file: change a line, not the file

Before, the only way to change a file was to rewrite all of it. That's
expensive (the model "types" every line again) and risky (it can quietly drop
something it didn't mean to touch — including a change the user made by hand).

`edit_file` takes three things: the file, the exact text to replace, and what
to put instead. The text must appear **exactly once**:

- **Not found?** The agent is told to `read_file` and copy the text exactly.
- **Found 3 times?** It's told to include more surrounding lines.

Why not "close enough" matching? Because a fuzzy match that picks the wrong
one of three similar lines is a bug that *looks* like a successful edit.
Refusing and explaining costs one extra round; guessing can cost a broken app.

### The second inspector: runtime errors

The build gate can prove that `app.js` parses. It cannot prove that clicking
"Add" doesn't throw `TypeError: total is undefined` — that only happens when
the code *runs*, in a browser.

ForgeFlow already had the pieces: the preview runs in a real browser, and a
small script in every preview page reports console errors back to the server
(Chapter 16). Now those errors go one step further: the next time the agent is
asked anything about the project, the request comes with every distinct error
the preview hit since the last build. And the chat shows a bar with "Ask the
agent to fix it" — one click, no copy-pasting.

"Since the last build" matters: errors from code that has since been replaced
are old news, and feeding them back would send the agent chasing ghosts.

### Two quiet fixes

- **Prompt caching** was already happening — Gemini reuses the unchanged
  start of each request — but ForgeFlow never read the number, so
  `cached_tokens` sat at 0. Now it's recorded per run.
- **`/mcp`** can require a key, so a public deployment isn't open to anyone
  who finds the URL.

### What the live test showed: mixed instructions

On the real site, "make the Calculate button green and rounded" worked — but
the agent rewrote all of `styles.css` to change one rule. Why? Our own
instructions contradicted each other. Near the top the prompt said *code
reaches the user only by calling write_file*; further down it said *prefer
edit_file*. Like a new employee with two conflicting memos, the model went
with the first one.

The fix talks to the model in all three places it listens:

1. **The prompt** (read once, at the start) now names both tools and gives
   "make the button green" as an example of an edit_file job.
2. **The tool description** (read when picking a tool) for write_file now
   says "to change part of an existing file, use edit_file instead".
3. **The tool result** (read right before the next move). If write_file
   rewrites a file of 10+ lines and keeps at least 80% of them unchanged,
   the write still goes through, but the reply says, in effect, "that was
   a small change — next time use edit_file".

Why not refuse the wasteful write outright? Because a refusal costs a round
and could leave a multi-file change half-done. The write isn't *wrong*, just
expensive — so it gets a note, not a "no".

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why must `old_text` be unique instead of replacing every match?**
Because the model usually means one specific place. "Replace every
`color: navy`" might be right, or might recolour three things it never looked
at. Forcing uniqueness makes the model say exactly where.

**Q: Why not let the agent decide when to use `edit_file` vs `write_file`?**
It does — the prompt only says to prefer `edit_file` for small changes. Both
go through the same build gate, so a bad edit gets caught and repaired like a
bad rewrite.

**Q: The runtime errors only exist if someone opened the preview. Isn't that a gap?**
Yes, and it's written down as one. Closing it means running a headless
browser inside the build gate, which needs a host that can run Chromium.
Render's free tier can't. Until then, the errors are caught whenever a person
actually uses the app — which is when they matter.

**Q: How did you find out the agent was using write_file for small edits?**
Not from the reply — that looked fine. Each saved assistant message records
which tools it called (`toolCalls`). Looking at that for one real run showed
`write_file`. Checking *how* a result was produced, not just *that* it came
out right, is what caught it.

**Q: The evals scored 20/20. Doesn't that prove the edit_file fix works?**
No - and this is the trap worth remembering. All twenty eval prompts build an
app from nothing, where rewriting (write_file) is the *right* tool. A test
can only measure what it exercises. So five cases now get a follow-up ("make
the button green") on the app they just built, and the report counts how
many were edits. A passing test suite tells you what you tested passed -
nothing more.

**Q: Why are follow-ups reported separately instead of counting toward the pass rate?**
Because the pass rate's value is comparison over time. If follow-ups could
fail a case, today's 19/20 and last month's 20/20 would be measuring
different things. Keep the old ruler; add a new one beside it.

**Q: Why count unchanged lines instead of comparing file sizes?**
A file can stay the same size and be completely different, or grow by one
line and be 99% the same. "How many of the old lines are still there, word
for word" measures what we care about: could a small edit have done this?
Each old line has to be matched once — ten copies of `}` can't all be
"kept" by a single `}` in the new file.

**Q: How do you know prompt caching is doing anything?**
Each run now records `cached_tokens` next to `prompt_tokens`. A multi-round run
should show a large cached share, because every round re-sends the same
system prompt, tools and history. One SQL query shows it.

</details>

---

## Chapter 23 — The whole building site: Kafka, MinIO, Qdrant, the gateway

**Plain English.** Until now ForgeFlow was a small workshop: one room, one
team, everything within arm's reach. The architecture diagram draws a big
building site. This chapter builds that site - but keeps the workshop too,
and lets a switch decide which one you're running.

| Diagram box | On the building site it is… | What it does in ForgeFlow |
|---|---|---|
| API Gateway | the security gate at the entrance | checks your badge (JWT), sends you to the right building, logs who came in |
| Kafka | the site's post room | "run 12 finished, these files changed" is posted once; every department that cares picks up its own copy |
| worker | a separate crew in its own hut | reads the post, files the new drawings into the archive (chunk + embed + store) |
| MinIO | the warehouse | holds the actual file contents; the office (Postgres) keeps the index cards |
| Qdrant | the specialist librarian | finds "drawings that mean something like this" fast, for one project at a time |
| Kubernetes | the site manager | starts crews, restarts the ones that fall over, adds more when it's busy |

### Kafka: post it once, everyone who cares gets it

When the AI finishes a run, ForgeFlow *publishes* one event: `code.generated`,
with the project and the files. It doesn't call the indexer, and it doesn't
know who's listening. Two listeners are:

- the **indexer** (consumer group "indexer") re-reads and re-embeds the
  changed files, and
- the **preview notifier** (group "execution") tells an open preview's logs
  "new code - reload".

A **consumer group** is a team: every team gets every event; inside a team the
work is shared. Run three indexer workers and Kafka gives each a third of the
**partitions** (lanes). Events for one project always use the same lane (the
project id is the **key**), so they're handled in order.

Two promises matter, and they're the classic Kafka interview questions:

- **At-least-once.** The worker marks an event done (commits the offset)
  only *after* handling it. Crash halfway, and the event comes again. So
  handlers must be **idempotent** - doing it twice must be harmless. Ours
  are: re-indexing compares file fingerprints and skips what's unchanged.
- **A bad event can't block the lane.** Three tries, then it goes to a
  **dead-letter topic** (`code.generated.DLT`) with the error attached, and
  the lane moves on. Nothing is silently dropped.

### MinIO: contents in a warehouse, index cards in the office

In "s3 mode" a file's text goes to object storage, and the database row keeps
only the metadata plus a key. The key is built from the content's
fingerprint (SHA-256): `projects/7/blobs/9f2c…`. So the same text is stored
once, writing it twice is harmless, and an object is never overwritten -
which is exactly what version history needs later.

Talking to S3 means signing every request (AWS Signature V4). We wrote the
signer ourselves and checked it against the worked example in AWS's own
documentation - it matched first time, and that test stays in the suite.

### Qdrant: a specialist for "find things that mean this"

Postgres can do vector search (pgvector), and still does by default. Qdrant
is a database built only for it. In Qdrant mode Postgres keeps the record of
every chunk, and Qdrant holds an index of the vectors - one it can rebuild.
Each vector carries its project id, and the search filters on it *inside* the
search, so a big project can't crowd a small one out of the results.

If Qdrant goes down, search quietly answers from Postgres (which still has
every vector) and the response says so in a header.

### The gateway: one door

Every request goes through one small app first. It:

1. finds the route ("chat goes to the intelligence service"),
2. rejects a missing or forged token **before** it reaches any service,
3. adds the standard proxy headers and a trace id,
4. streams the answer back.

Step 4 is the tricky one. Chat replies and logs are **Server-Sent Events** -
a response that stays open and trickles out. A proxy that waits for the
response to finish before passing it on would hold every chat message until
the chat ends, i.e. forever. So the gateway passes bytes through as they
arrive and flushes after each read. There's a test that measures it - and it
fails if you remove the flush.

### Same code, two deployments

Everything above is a **switch**, not a fork:

```
FORGEFLOW_EVENTS=kafka   FORGEFLOW_FILE_STORAGE=s3   FORGEFLOW_VECTOR_STORE=qdrant
```

Render (free, one process) runs with all three off. `docker compose -f
docker-compose.full.yml up` runs with all three on, plus a separate worker,
the gateway, and Zipkin to watch one request travel through all of it.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why not use Kafka for everything, all the time?**
It's a whole system to run, and on a single free-tier server it would add
failure modes without adding anything a method call doesn't do. Kafka earns
its place when work moves to another process - the indexer worker - or when
events must survive a restart. Which is why it's a switch.

**Q: What does "at-least-once" mean, and how do you cope with duplicates?**
Every event is handled one or more times, never zero. Duplicates are made
harmless (idempotency): indexing the same unchanged files again does nothing.

**Q: Why does the event have a key, and why the project id?**
Kafka keeps order only within a partition, and the key picks the partition.
Same project, same partition: a project's events are processed in the order
they happened. Different projects spread across partitions and run in parallel.

**Q: What's a dead-letter topic for?**
For events that keep failing. Without one, a single bad event either blocks
its partition forever (retry, retry…) or gets dropped. With one, it's parked
with the error for a human to look at, and everything behind it flows.

**Q: Why content-addressed keys in object storage?**
The key is the content's fingerprint, so: identical files are stored once,
uploading twice is harmless, and nothing is ever overwritten - an old
version stays readable by its key. The cost: old blobs pile up until a
cleanup job removes the ones no row points to (not built yet).

**Q: If Qdrant is the vector database, why do the vectors stay in Postgres too?**
Postgres is the source of truth; Qdrant is an index that can be rebuilt from
it. That also gives a fallback when Qdrant is down. The price is storing each
vector twice - fine at this size, and it's the standard "rebuildable index"
trade-off.

**Q: Why write your own gateway instead of Spring Cloud Gateway?**
Two reasons: the library couldn't be downloaded in this environment, and the
interesting part - streaming SSE through without buffering - is a few dozen
lines you can explain line by line. A framework would hide it.

**Q: Why does the API run only one replica on Kubernetes?**
Running previews and their live log buffers are kept in memory. A second
replica wouldn't know about a preview the first one started. Moving that
state to Redis is the next step; everything else (rate limits, events, files,
vectors) is already shared.

**Q: How do you know the infrastructure code works if it can't run here?**
Kafka and MinIO run in CI as real containers, and the CI summary lists each
integration test with its counts. Qdrant ran locally from its official
binary. And the full browser walk-through ran through the real gateway in
front of the app in S3 + Qdrant mode.

</details>

---

## Chapter 24 — Time travel (version history)

**Plain English.** Every time the AI changes your app, ForgeFlow now takes a
photo of the whole project and pins it to a wall, labelled with what you
asked for. You can look at any two photos side by side (what changed), or
say "make it look like that photo again" (restore). Restoring takes a new
photo too - so if you restore by mistake, the photo of how it was is right
there.

### Photos that don't waste film

Taking a full copy of every file on every change would fill the wall fast.
Instead, each file's content gets a **fingerprint** (SHA-256 - change one
character and the fingerprint is completely different). A photo is just a
list: "index.html → fingerprint A, app.js → fingerprint B". The actual text
is stored once per fingerprint. Ten versions where only `app.js` changed
cost ten small lists plus ten versions of `app.js` - not ten copies of
everything.

It also makes "nothing changed" easy to spot: fingerprint the whole list
(the **tree hash**). Same tree hash as the last photo → don't take another.
Git works exactly this way.

### What changed: the diff

To show what changed in a file, ForgeFlow finds the **longest common
subsequence** - the longest list of lines both versions share, in order.
Everything not in it was removed (red, `-`) or added (green, `+`). The output
is the same "unified diff" format `git diff` prints: `@@ -2,7 +2,7 @@`
means "7 lines starting at line 2 in the old file became 7 lines starting at
line 2 in the new one", with 3 unchanged lines of context around each change.

<details>
<summary><b>Counter-questions</b></summary>

**Q: Why store a fingerprint per file instead of a copy of the project?**
Most changes touch one or two files. Fingerprints let every version share the
contents that didn't change - the same reason Git is small.

**Q: Why is restore a new version instead of "going back"?**
Because going back would throw away what you're leaving. Making restore a
forward step keeps the whole timeline, so "undo the restore" is just another
restore.

**Q: What about edits I made by hand since the last AI run?**
Before a restore overwrites anything, the current state is checkpointed if it
isn't already a version ("Before restoring to #N"). Nothing is lost.

**Q: In S3 mode, where do old versions live?**
In the same objects the file writes created - their keys are already the
content fingerprint. History adds rows in Postgres, not bytes in the bucket.

**Q: Isn't comparing every line with every other line slow?**
It's O(lines × lines), which is instant for the few hundred lines an agent
writes. Past 4 million comparisons the file is shown as replaced instead.

</details>

---

## Chapter 25 — What's next

Done since this chapter was first written: **evals** (Chapter 12), **deploy**,
the **workbench**, the **MCP server** (Chapter 13), **CI**, **members and
roles** (Chapter 14), **chat memory** (Chapter 15) and the **logs stream**
(Chapter 16), **plans, quotas and Stripe** (Chapter 17), **rate limiting**
(Chapter 18), **RAG** (Chapter 19), **tracing** (Chapter 20), the
**workbench** (Chapter 21), `edit_file`, the runtime loop and prompt
caching (Chapter 22), and the full topology - Kafka, MinIO, Qdrant, the
gateway, Kubernetes (Chapter 23), and version history (Chapter 24). What's left:

- **Measure it** — re-run the evals against real Gemini and count how often
  edit requests now use `edit_file`.
- **A headless browser in the build gate**, so runtime errors are caught
  without anyone opening the preview.
- **"Forgot password?"** — needs an email sender to mail a reset link.
- **Screenshot to app** and **the AI checking its own app** — Gemini reads
  images; the preview runs in a real browser that can take the picture.
- **React apps running in the browser** (WebContainers) — the diagram's
  "Code Execution Service: WebContainer" box.
- **Preview state in Redis**, so the API can run more than one replica.

<details>
<summary><b>Counter-questions</b></summary>

**Q: If you only had time for one of these, which?**
Proving it live. Every feature since Day 10 is tested against a scripted or
demo model. The eval suite against real Gemini is what says whether
`edit_file` and the runtime loop actually make the agent better — and a
measurement beats a belief.

**Q: RAG is built now. Is it doing anything for today's projects?**
Mostly not, and that's by design. Generated sites are usually under fifteen
files, and below that the agent reads everything — more reliable than any
retrieval. RAG is there for when projects grow, and `search_code` is there
whenever the agent wants it.

</details>
