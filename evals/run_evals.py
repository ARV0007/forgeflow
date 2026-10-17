#!/usr/bin/env python3
"""
ForgeFlow eval harness.

Runs every golden prompt against a fresh project and scores the result on four
checks. A case passes only if all four pass.

    build_passed        the build gate approved it
    has_index           index.html exists
    no_empty_files      no file under 20 bytes
    js_when_needed      an interactive prompt produced real JavaScript

The point is not the absolute number. The point is running this before and
after a change and comparing - "diff-based editing took the pass rate from
X to Y" is a claim; "it feels better" is not.

Beyond pass/fail, every run is also read for HOW the agent worked (its
trajectory, from the run's agent report): did it plan, did it read a file
before editing it, what did the reviewer say, did a guardrail fire, which
model did routing pick. With --judge, a judge model also scores each app
1-5 on a fixed rubric (fulfils the request, works, accessibility, design,
code quality) - quality, not just "it built".

Usage:
    python3 evals/run_evals.py                      # local, all 20
    python3 evals/run_evals.py --limit 3            # quick smoke run
    python3 evals/run_evals.py --base https://...   # against the deployed app
    python3 evals/run_evals.py --judge              # + LLM-as-judge rubric scores
    python3 evals/run_evals.py --review off         # A/B the reviewer agent
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
MIN_FILE_BYTES = 20          # below this, a file is a stub, not content


def call(base, path, method="GET", body=None, token=None, timeout=420, retries=3):
    data = json.dumps(body).encode() if body is not None else None
    for attempt in range(retries + 1):
        req = urllib.request.Request(base + path, method=method)
        req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, data, timeout=timeout) as r:
                raw = r.read().decode()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            # ForgeFlow rate-limits (429 + Retry-After). An eval is a polite
            # client: wait as told and try again, rather than scoring the
            # case as a failure of the agent.
            if e.code == 429 and attempt < retries:
                wait = int(e.headers.get("Retry-After") or 5)
                print(f"(rate limited, waiting {wait}s) ", end="", flush=True)
                time.sleep(wait)
                continue
            raise RuntimeError(f"{method} {path} -> {e.code}: {e.read().decode()[:600]}")


def sign_in(base, email, password):
    """Sign up, or log in if the account already exists."""
    try:
        return call(base, "/api/v1/auth/signup", "POST",
                    {"email": email, "password": password, "name": "evals"})["token"]
    except RuntimeError as e:
        if "409" not in str(e):
            raise
        return call(base, "/api/v1/auth/login", "POST",
                    {"email": email, "password": password})["token"]


class OutOfQuota(Exception):
    """The eval account hit a plan limit that waiting a minute won't fix."""


def delete_project(base, pid, token):
    """Best effort: a leftover project only matters for the next run's quota."""
    try:
        call(base, f"/api/v1/projects/{pid}", "DELETE", token=token)
    except Exception as e:
        print(f"(could not delete project {pid}: {e}) ", end="", flush=True)


def clean_up_earlier_runs(base, token):
    """
    Delete projects an earlier, interrupted run left behind.

    The Free plan allows three projects. A sweep creates one per case, so
    without this the fourth case of the first live run was refused with a
    402 - and so was every case after it. Only "eval-*" projects are
    touched: the eval account is a normal account and could own others.
    """
    leftovers = [p for p in call(base, "/api/v1/projects", token=token) or []
                 if str(p.get("name", "")).startswith("eval-")]
    for p in leftovers:
        delete_project(base, p["id"], token)
    if leftovers:
        print(f"removed {len(leftovers)} project(s) left by an earlier run\n")


def score(case, run, files):
    """Four checks. All must pass."""
    sizes = {f["path"]: f["sizeBytes"] for f in files}

    build_passed = run.get("buildPassed") is True
    has_index = "index.html" in sizes
    empty = [p for p, n in sizes.items() if n < MIN_FILE_BYTES]
    no_empty_files = not empty

    # A prompt that asks for behaviour should produce behaviour. This check
    # exists because the agent repeatedly created a near-empty app.js purely
    # to satisfy the system prompt's file convention.
    if case.get("expect_js"):
        js_bytes = sum(n for p, n in sizes.items() if p.endswith(".js"))
        js_when_needed = js_bytes >= 100
    else:
        js_when_needed = True

    checks = {
        "build_passed": build_passed,
        "has_index": has_index,
        "no_empty_files": no_empty_files,
        "js_when_needed": js_when_needed,
    }
    return checks, all(checks.values()), empty


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8081")
    ap.add_argument("--email", default="evals@forgeflow.dev")
    ap.add_argument("--password", default="evalrunner2026")
    ap.add_argument("--limit", type=int, default=0, help="run only the first N cases")
    ap.add_argument("--pause", type=float, default=75.0,
                    help="seconds between cases. The free tier allows 5 requests\n"
                         "per minute and one run makes 4-6, so anything less than\n"
                         "about a minute starts the next case inside a spent quota.")
    # RAG A/B: run the same cases with retrieval switched differently and
    # compare. Leave both out for the product's defaults.
    ap.add_argument("--code-context", choices=["auto", "always", "off"],
                    help="retrieved code per run: auto (product default), always, off")
    ap.add_argument("--notes", type=int, choices=range(0, 6), metavar="0-5",
                    help="reference notes per run (product default 3; 0 = none)")
    ap.add_argument("--review", choices=["on", "off"],
                    help="the reviewer agent per run (product default on)")
    ap.add_argument("--judge", action="store_true",
                    help="score each built app 1-5 on a rubric with a judge model (one more call per case)")
    args = ap.parse_args()
    args.rag = {k: v for k, v in (("codeContext", args.code_context), ("notes", args.notes),
                                  ("review", None if args.review is None else args.review == "on")) if v is not None}

    golden = json.load(open(os.path.join(HERE, "golden.json")))
    cases = golden["cases"][: args.limit] if args.limit else golden["cases"]

    print(f"ForgeFlow evals - {len(cases)} case(s) against {args.base}"
          + (f"  [rag: {', '.join(f'{k}={v}' for k, v in args.rag.items())}]" if args.rag else "") + "\n")
    token = sign_in(args.base, args.email, args.password)
    clean_up_earlier_runs(args.base, token)

    results, started = [], time.time()

    # The results file is created now and rewritten after EVERY case. It used
    # to be written once, at the end - so a Ctrl+C, a crash or a closed laptop
    # mid-sweep lost every completed case, exactly when the record mattered.
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = os.path.join(HERE, "results")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, f"run-{stamp}.json")

    def save(finished):
        ok_now = sum(1 for r in results if r.get("passed"))
        tmp = out + ".tmp"
        with open(tmp, "w") as f:
            json.dump({"base": args.base, "ranAt": stamp, "finished": finished, "rag": args.rag or "defaults",
                       "casesPlanned": len(cases), "casesRun": len(results),
                       "passRate": 100.0 * ok_now / len(results) if results else 0.0,
                       "results": results}, f, indent=2)
        os.replace(tmp, out)      # atomic: a crash mid-write can't leave half a file

    try:
        run_cases(args, cases, results, save)
    except KeyboardInterrupt:
        print("\n\ninterrupted - keeping the cases that finished")
    except OutOfQuota as e:
        print(f"\n\nstopped: {e}\n"
              "The cases that finished are kept. The AI-token allowance resets at\n"
              "midnight UTC (05:30 in India); run again after that, or use --limit.")
    save(finished=len(results) == len(cases))
    report(results, started)
    print(f"\nwritten to {os.path.relpath(out, os.getcwd())}")
    sys.exit(0 if results and all(r.get("passed") for r in results) and len(results) == len(cases) else 1)


def trajectory(run):
    """
    How the agent worked, from the run's agent report. Older servers don't
    send one: then this is None and the report skips the section.
      planned          it called update_plan
      read_before_edit every edit_file was preceded by a read_file or write_file of that file
      review           the reviewer's verdict and how many looks it took
    """
    agent = run.get("agent")
    if not agent:
        return None
    steps = agent.get("trajectory") or []
    seen, edits, edits_ok = set(), 0, 0
    for step in steps:
        name, _, path = step.partition(" ")
        if name == "edit_file":
            edits += 1
            edits_ok += path in seen
        if name in ("read_file", "write_file", "edit_file"):
            seen.add(path)
    review = agent.get("review") or {}
    return {"planned": any(s.startswith("update_plan") for s in steps), "edits": edits, "editsAfterRead": edits_ok,
            "review": review.get("verdict"), "reviewRounds": review.get("rounds", 0),
            "guards": len(agent.get("guards") or []), "tier": agent.get("tier"), "model": agent.get("model"),
            "fellBack": bool(agent.get("fellBack")), "asked": agent.get("question") is not None,
            "steps": len(steps)}


def edit_style(tool_usage):
    """
    How a follow-up changed the app. "edit" is the goal for a small change:
    edit_file touched only what changed. "rewrite" means whole files were
    resent. An older server that doesn't report tool usage gives "unknown".
    """
    if tool_usage is None:
        return "unknown"
    edits, writes = tool_usage.get("edit_file", 0), tool_usage.get("write_file", 0)
    if edits and not writes:
        return "edit"
    if writes and not edits:
        return "rewrite"
    return "mixed" if edits else "none"


def run_followup(args, case, pid, token):
    """
    A small change asked of the app the case just built, in the same project -
    the way a person actually uses ForgeFlow. Scored on its own: did the build
    still pass, and did the agent edit or rewrite. It doesn't change whether
    the case passes, so pass rates stay comparable with older runs.
    """
    time.sleep(min(args.pause, 30))     # the build just used a chunk of the model's per-minute quota
    run = call(args.base, f"/api/v1/projects/{pid}/generate", "POST",
               {"prompt": case["followup"], **args.rag}, token)
    usage = run.get("toolUsage")
    return {"prompt": case["followup"], "buildPassed": run.get("buildPassed") is True,
            "style": edit_style(usage), "toolUsage": usage,
            "totalTokens": run.get("totalTokens"), "costUsd": run.get("costUsd"), "durationMs": run.get("durationMs"),
            "rag": run.get("rag"), "trajectory": trajectory(run)}


def run_cases(args, cases, results, save):
    token = None
    for i, case in enumerate(cases, 1):
        label = f"[{i}/{len(cases)}] {case['id']:<16}"
        print(label, end=" ", flush=True)

        pid = None
        try:
            # Tokens expire in two hours and a full sweep takes longer than
            # that. Re-authenticate every case rather than once at the start.
            token = sign_in(args.base, args.email, args.password)

            # A fresh project each time: accumulated files would let a later
            # case pass on an earlier case's work.
            project = call(args.base, "/api/v1/projects", "POST",
                           {"name": f"eval-{case['id']}"}, token)
            pid = project["id"]

            follow = None
            judged = None
            try:
                run = call(args.base, f"/api/v1/projects/{pid}/generate", "POST",
                           {"prompt": case["prompt"], **args.rag}, token)
                files = call(args.base, f"/api/v1/projects/{pid}/files", token=token)
                if args.judge and run.get("buildPassed"):
                    # Judged on what the case asked for, before any follow-up changes it.
                    judged = call(args.base, f"/api/v1/projects/{pid}/judge", "POST", {"request": case["prompt"]}, token)
                if case.get("followup") and run.get("buildPassed"):
                    follow = run_followup(args, case, pid, token)
            finally:
                # Scored or not, the project has done its job. Deleting it
                # keeps the account under the plan's project limit.
                delete_project(args.base, pid, token)

            checks, passed, empty = score(case, run, files)
            results.append({
                "id": case["id"], "passed": passed, "checks": checks,
                "status": run.get("status"), "stopReason": run.get("stopReason"),
                "files": len(files), "emptyFiles": empty,
                "repairRounds": run.get("repairRounds"),
                "totalTokens": run.get("totalTokens"),
                "costUsd": run.get("costUsd"),
                "durationMs": run.get("durationMs"),
                "toolUsage": run.get("toolUsage"),
                "rag": run.get("rag"),
                "trajectory": trajectory(run),
            })
            if follow:
                results[-1]["followup"] = follow
            if judged:
                results[-1]["judge"] = {"scores": judged.get("scores"), "overall": judged.get("overall"),
                                        "notes": judged.get("notes"), "model": judged.get("model")}

            mark = "PASS" if passed else "FAIL"
            failed = [k for k, v in checks.items() if not v]
            print(f"{mark}  {run.get('stopReason','?'):<14} "
                  f"{len(files)} files  {run.get('repairRounds',0)} repair  "
                  f"{(run.get('durationMs') or 0)/1000:.0f}s"
                  + (f"   <- {', '.join(failed)}" if failed else "")
                  + (f"   | follow-up: {follow['style']}"
                     + ("" if follow["buildPassed"] else " (build FAILED)") if follow else "")
                  + (f"   | judge {judged.get('overall')}" if judged else ""))

        except Exception as e:
            # Out of daily AI tokens: every remaining case would fail the same
            # way, and that measures the plan, not the agent. Stop instead.
            if "-> 402" in str(e) and "AI_TOKENS" in str(e):
                print("STOPPED - daily AI-token limit reached")
                raise OutOfQuota("the eval account used up its daily AI tokens") from e
            print(f"ERROR  {e}")
            results.append({"id": case["id"], "passed": False, "error": str(e)})

        save(finished=False)
        if i < len(cases):
            time.sleep(args.pause)


def report(results, started):
    ok = [r for r in results if r.get("passed")]
    done = [r for r in results if "error" not in r]
    rate = 100.0 * len(ok) / len(results) if results else 0.0

    def avg(key):
        vals = [r[key] for r in done if r.get(key) is not None]
        return sum(vals) / len(vals) if vals else 0

    print("\n" + "=" * 62)
    print(f"  pass rate        {len(ok)}/{len(results)}  ({rate:.0f}%)")
    print(f"  mean tokens      {avg('totalTokens'):.0f}")
    spent = sum(float(r.get("costUsd") or 0) for r in done)
    if spent:
        print(f"  cost per app     ${spent/len(done):.4f} mean  (${spent:.4f} for {len(done)}, at list price)")
    print(f"  mean duration    {avg('durationMs')/1000:.1f}s")
    print(f"  mean repairs     {avg('repairRounds'):.2f}")
    rag = [r.get("rag") or {} for r in done]
    if any(rag):
        print(f"  retrieval used   {sum(x.get('codeExcerpts', 0) for x in rag)/len(rag):.1f} code excerpts, "
              f"{sum(x.get('notes', 0) for x in rag)/len(rag):.1f} notes per app (mean)")
    print(f"  wall clock       {(time.time()-started)/60:.1f} min")

    for name in ["build_passed", "has_index", "no_empty_files", "js_when_needed"]:
        n = sum(1 for r in done if r.get("checks", {}).get(name))
        print(f"  {name:<17}{n}/{len(done)}")

    trajs = [r["trajectory"] for r in done if r.get("trajectory")]
    if trajs:
        n = len(trajs)
        edits = sum(t["edits"] for t in trajs)
        reviewed = [t for t in trajs if t["review"]]
        tiers = {}
        for t in trajs:
            tiers[t["tier"]] = tiers.get(t["tier"], 0) + 1
        print(f"  -- how the agent worked ({n} runs) --")
        print(f"  planned first    {sum(t['planned'] for t in trajs)}/{n}")
        if reviewed:
            print(f"  reviewer         {sum(t['review'] == 'approve' for t in reviewed)}/{len(reviewed)} approved, "
                  f"{sum(t['reviewRounds'] > 1 for t in reviewed)} sent back for a fix")
        if edits:
            print(f"  read before edit {sum(t['editsAfterRead'] for t in trajs)}/{edits} edits")
        print(f"  guard notices    {sum(t['guards'] for t in trajs)}   asked a question: {sum(t['asked'] for t in trajs)}")
        print(f"  routing          " + ", ".join(f"{k} {v}" for k, v in sorted(tiers.items(), key=lambda kv: str(kv[0])))
              + (f"   fallbacks: {sum(t['fellBack'] for t in trajs)}" if any(t['fellBack'] for t in trajs) else ""))
    judged = [r["judge"] for r in done if r.get("judge")]
    if judged:
        print(f"  -- judge ({len(judged)} apps, 1-5) --")
        for c in ["fulfils_request", "works", "accessibility", "design", "code_quality"]:
            vals = [j["scores"][c] for j in judged if j.get("scores")]
            print(f"  {c:<17}{sum(vals)/len(vals):.2f}")
        print(f"  {'overall':<17}{sum(j['overall'] for j in judged)/len(judged):.2f}")

    follows = [r["followup"] for r in done if r.get("followup")]
    if follows:
        styles = {}
        for f in follows:
            styles[f["style"]] = styles.get(f["style"], 0) + 1
        print(f"  follow-ups       {sum(f['buildPassed'] for f in follows)}/{len(follows)} still build")
        print(f"  how they changed " + ", ".join(f"{k} {v}" for k, v in sorted(styles.items())))
        print(f"  follow-up tokens {sum(f['totalTokens'] or 0 for f in follows)/len(follows):.0f} mean")
        frag = [f.get("rag") or {} for f in follows]
        if any(frag):
            print(f"  follow-up rag    {sum(x.get('codeExcerpts', 0) for x in frag)/len(frag):.1f} code excerpts, "
                  f"{sum(x.get('notes', 0) for x in frag)/len(frag):.1f} notes (mean)")
    print("=" * 62)


if __name__ == "__main__":
    main()
