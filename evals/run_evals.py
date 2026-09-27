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

Usage:
    python3 evals/run_evals.py                      # local, all 20
    python3 evals/run_evals.py --limit 3            # quick smoke run
    python3 evals/run_evals.py --base https://...   # against the deployed app
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


def call(base, path, method="GET", body=None, token=None, timeout=420):
    req = urllib.request.Request(base + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(req, data, timeout=timeout) as r:
            raw = r.read().decode()
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} -> {e.code}: {e.read().decode()[:300]}")


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
    args = ap.parse_args()

    golden = json.load(open(os.path.join(HERE, "golden.json")))
    cases = golden["cases"][: args.limit] if args.limit else golden["cases"]

    print(f"ForgeFlow evals - {len(cases)} case(s) against {args.base}\n")
    token = sign_in(args.base, args.email, args.password)

    results, started = [], time.time()

    for i, case in enumerate(cases, 1):
        label = f"[{i}/{len(cases)}] {case['id']:<16}"
        print(label, end=" ", flush=True)

        try:
            # Tokens expire in two hours and a full sweep takes longer than
            # that. Re-authenticate every case rather than once at the start.
            token = sign_in(args.base, args.email, args.password)

            # A fresh project each time: accumulated files would let a later
            # case pass on an earlier case's work.
            project = call(args.base, "/api/v1/projects", "POST",
                           {"name": f"eval-{case['id']}"}, token)
            pid = project["id"]

            run = call(args.base, f"/api/v1/projects/{pid}/generate", "POST",
                       {"prompt": case["prompt"]}, token)
            files = call(args.base, f"/api/v1/projects/{pid}/files", token=token)

            checks, passed, empty = score(case, run, files)
            results.append({
                "id": case["id"], "passed": passed, "checks": checks,
                "status": run.get("status"), "stopReason": run.get("stopReason"),
                "files": len(files), "emptyFiles": empty,
                "repairRounds": run.get("repairRounds"),
                "totalTokens": run.get("totalTokens"),
                "durationMs": run.get("durationMs"),
            })

            mark = "PASS" if passed else "FAIL"
            failed = [k for k, v in checks.items() if not v]
            print(f"{mark}  {run.get('stopReason','?'):<14} "
                  f"{len(files)} files  {run.get('repairRounds',0)} repair  "
                  f"{(run.get('durationMs') or 0)/1000:.0f}s"
                  + (f"   <- {', '.join(failed)}" if failed else ""))

        except Exception as e:
            print(f"ERROR  {e}")
            results.append({"id": case["id"], "passed": False, "error": str(e)})

        if i < len(cases):
            time.sleep(args.pause)

    # ---- report ----
    ok = [r for r in results if r.get("passed")]
    done = [r for r in results if "error" not in r]
    rate = 100.0 * len(ok) / len(results) if results else 0.0

    def avg(key):
        vals = [r[key] for r in done if r.get(key) is not None]
        return sum(vals) / len(vals) if vals else 0

    print("\n" + "=" * 62)
    print(f"  pass rate        {len(ok)}/{len(results)}  ({rate:.0f}%)")
    print(f"  mean tokens      {avg('totalTokens'):.0f}")
    print(f"  mean duration    {avg('durationMs')/1000:.1f}s")
    print(f"  mean repairs     {avg('repairRounds'):.2f}")
    print(f"  wall clock       {(time.time()-started)/60:.1f} min")

    for name in ["build_passed", "has_index", "no_empty_files", "js_when_needed"]:
        n = sum(1 for r in done if r.get("checks", {}).get(name))
        print(f"  {name:<17}{n}/{len(done)}")
    print("=" * 62)

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = os.path.join(HERE, "results")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, f"run-{stamp}.json")
    json.dump({"base": args.base, "ranAt": stamp, "passRate": rate,
               "results": results}, open(out, "w"), indent=2)
    print(f"\nwritten to {os.path.relpath(out, os.getcwd())}")

    sys.exit(0 if len(ok) == len(results) else 1)


if __name__ == "__main__":
    main()
