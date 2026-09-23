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

## Chapter 11 — What's next

- **`edit_file`** — change three lines instead of rewriting a whole file. Cheaper,
  faster, and it won't overwrite changes the user made by hand.
- **Prompt caching** — the rules and file list are identical every round; caching
  them cuts the cost of every call.
- **Evals** — twenty test prompts, a script that runs them, and a pass-rate number.
  Turning *"it works"* into *"it passes 85%, up from 60% before self-healing."*
- **RAG** — once a project has dozens of files, retrieve only the relevant ones
  instead of sending everything. First to cut if time runs short.
- **Deploy** — a public URL, a README, and CI running on every push.

<details>
<summary><b>Counter-questions</b></summary>

**Q: If you only had time for one of these, which?**
Evals. Everything else makes ForgeFlow better; evals are how you'd *know* it got
better. Without them, every other change is a guess.

**Q: Why is RAG last, when it's the most talked-about technique?**
Because it only helps when a project is too big to send whole — past about fifteen
files. ForgeFlow's generated sites are smaller than that today. Building it first
would be solving a problem I don't have yet.

</details>
