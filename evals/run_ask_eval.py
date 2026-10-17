#!/usr/bin/env python3
"""
ForgeFlow Ask eval: are answers about a codebase right, grounded and honest?

Loads the 40-file FreshCart fixture into a fresh project and asks two kinds of
question (evals/retrieval/ask-questions.json):

    answerable     12 questions whose answer lives in a known file
    unanswerable    6 questions about things the shop doesn't have

Every question is asked with verify=true, so each answer comes back with the
faithfulness check: a second model reads every cited sentence against only the
sources it cites.

Scored:
    answered        an answerable question got an answer (not "couldn't find")
    cited right     ...and the answer cites the file the answer is in
    faithful        cited claims backed by their sources, over all answers
    refused         an unanswerable question got "not in this project" -
                    the hallucination check: the wrong answer here is a
                    confident description of a PayPal integration that doesn't exist

Spends AI tokens (3-6 model calls a question with agentic retrieval). Usage:
    python3 evals/run_ask_eval.py --base https://forgeflow-7m08.onrender.com
"""

import argparse
import json
import os
import time
from datetime import datetime, timezone

from run_evals import call, delete_project, sign_in
from run_retrieval_eval import load_fixture

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT_NAME = "eval-ask"


def ask(base, pid, question, token):
    for attempt in range(4):
        try:
            return call(base, f"/api/v1/projects/{pid}/ask", "POST", {"question": question, "verify": True}, token)
        except RuntimeError as e:
            if "-> 402" in str(e):
                raise
            if attempt == 3:
                raise
            time.sleep(20)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8081")
    ap.add_argument("--email", default="evals@forgeflow.dev")
    ap.add_argument("--password", default="evalrunner2026")
    ap.add_argument("--pause", type=float, default=20.0, help="seconds between questions (per-minute model quota)")
    ap.add_argument("--keep", action="store_true", help="keep the project afterwards")
    args = ap.parse_args()

    qs = json.load(open(os.path.join(HERE, "retrieval", "ask-questions.json")))
    token = sign_in(args.base, args.email, args.password)
    for p in call(args.base, "/api/v1/projects", token=token) or []:
        if p.get("name") == PROJECT_NAME:
            delete_project(args.base, p["id"], token)
    pid = call(args.base, "/api/v1/projects", "POST", {"name": PROJECT_NAME}, token)["id"]

    rows = []
    started = time.time()
    try:
        n_files = load_fixture(args.base, pid, token, ["fixture", "fixture-v2"])
        total = len(qs["answerable"]) + len(qs["unanswerable"])
        print(f"Ask eval - {total} questions over {n_files} files, against {args.base}\n")
        items = [(q, True) for q in qs["answerable"]] + [({"q": q}, False) for q in qs["unanswerable"]]
        for i, (q, answerable) in enumerate(items, 1):
            print(f"[{i:>2}/{total}] {'ans ' if answerable else 'none'} {q['q'][:58]:<58}", end=" ", flush=True)
            r = ask(args.base, pid, q["q"], token)
            cited = [c["path"] for c in r.get("citations", [])]
            good = {q.get("expect"), *q.get("accept", [])} if answerable else set()
            check = r.get("check") or {}
            row = {"q": q["q"], "answerable": answerable, "found": r.get("found"), "cited": cited,
                   "citedRight": bool(good & set(cited)) if answerable else None,
                   "claims": check.get("claims", 0), "supported": check.get("supported", 0),
                   "unsupported": check.get("unsupported", []), "searches": r.get("searches", []),
                   "tokens": r.get("tokens"), "costUsd": r.get("costUsd"), "answer": r.get("answer")}
            rows.append(row)
            if answerable:
                print(("answered" if row["found"] else "NOT FOUND") + (" , cites right file" if row["citedRight"] else "")
                      + (f" , {row['supported']}/{row['claims']} claims backed" if row["claims"] else ""))
            else:
                print("refused (good)" if not row["found"] else "ANSWERED - check it for invention")
            if i < total:
                time.sleep(args.pause)
    finally:
        if not args.keep:
            delete_project(args.base, pid, token)

    ans = [r for r in rows if r["answerable"]]
    none = [r for r in rows if not r["answerable"]]
    claims = sum(r["claims"] for r in rows)
    supported = sum(r["supported"] for r in rows)
    summary = {
        "answered": sum(bool(r["found"]) for r in ans), "answerable": len(ans),
        "citedRight": sum(bool(r["citedRight"]) for r in ans),
        "refused": sum(not r["found"] for r in none), "unanswerable": len(none),
        "claims": claims, "supported": supported,
        "tokens": sum(r["tokens"] or 0 for r in rows), "costUsd": sum(float(r["costUsd"] or 0) for r in rows),
    }
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    out = os.path.join(HERE, "results", f"ask-{stamp}.json")
    with open(out, "w") as f:
        json.dump({"base": args.base, "ranAt": stamp, "summary": summary, "rows": rows}, f, indent=2)

    print("\n" + "=" * 62)
    print(f"  answered         {summary['answered']}/{summary['answerable']}")
    print(f"  cited right file {summary['citedRight']}/{summary['answerable']}")
    print(f"  claims backed    {supported}/{claims}" + (f"  ({100 * supported / claims:.0f}%)" if claims else ""))
    print(f"  refused correctly{summary['refused']:>3}/{summary['unanswerable']}  (unanswerable questions)")
    print(f"  tokens / cost    {summary['tokens']}  /  ${summary['costUsd']:.4f}")
    print(f"  wall clock       {(time.time() - started) / 60:.1f} min")
    bad = [r for r in rows if r["unsupported"]] + [r for r in none if r["found"]]
    if bad:
        print("\n  to read by hand (unsupported claims, or an answer to an unanswerable question):")
        for r in bad:
            print(f"    - {r['q']}\n      {(r['answer'] or '')[:160]}")
    print("=" * 62)
    print(f"\nwritten to {os.path.relpath(out, os.getcwd())}")


if __name__ == "__main__":
    main()
