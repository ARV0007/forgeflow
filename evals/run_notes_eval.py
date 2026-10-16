#!/usr/bin/env python3
"""
ForgeFlow knowledge-base retrieval eval: does search_docs find the right note?

The code eval (run_retrieval_eval.py) measures search over a project's code.
This one measures the other corpus: ForgeFlow's own reference notes
(src/main/resources/knowledge), which the agent gets attached to each run and
can search with search_docs. 30 labelled questions in three kinds - exact
names, plain descriptions, paraphrases - each asked in three modes:

    hybrid    vector + keyword, weighted RRF (what ships)
    vector    meaning only
    keyword   words only

Scored at the note level: recall@1, recall@3 and MRR.

Usage:
    python3 evals/run_notes_eval.py --base https://forgeflow-7m08.onrender.com

Read-only, and spends no AI tokens (embeddings aren't metered): safe to run
any time, even after the app evals have used the day's allowance.
"""

import argparse
import json
import os
import time
import urllib.parse
from datetime import datetime, timezone

from run_evals import call, sign_in

HERE = os.path.dirname(os.path.abspath(__file__))
MODES = ["keyword", "vector", "hybrid"]
KINDS = ["identifier", "described", "paraphrase"]


def ranks(base, token, q, mode, pause):
    hits = call(base, "/api/v1/knowledge/search?" + urllib.parse.urlencode({"q": q, "k": 10, "mode": mode}), token=token)
    time.sleep(pause)
    seen = []
    for h in hits:
        if h["doc"] not in seen:
            seen.append(h["doc"])
    return seen


def metrics(rs):
    n = len(rs) or 1
    return {"recall@1": sum(1 for r in rs if r == 1) / n,
            "recall@3": sum(1 for r in rs if r and r <= 3) / n,
            "mrr": sum(1 / r for r in rs if r) / n}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8081")
    ap.add_argument("--email", default="evals@forgeflow.dev")
    ap.add_argument("--password", default="evalrunner2026")
    ap.add_argument("--pause", type=float, default=1.0, help="seconds between searches (embedding per-minute cap)")
    args = ap.parse_args()

    queries = json.load(open(os.path.join(HERE, "retrieval", "notes-queries.json")))["queries"]
    token = sign_in(args.base, args.email, args.password)
    notes = call(args.base, "/api/v1/knowledge", token=token)
    print(f"Notes eval - {len(queries)} questions over {len(notes)} notes, against {args.base}\n")

    rows = []
    for i, q in enumerate(queries, 1):
        row = {"q": q["q"], "kind": q["kind"], "expect": q["expect"], "ranks": {}, "top": {}}
        for mode in MODES:
            docs = ranks(args.base, token, q["q"], mode, args.pause)
            row["ranks"][mode] = docs.index(q["expect"]) + 1 if q["expect"] in docs else None
            row["top"][mode] = docs[:3]
        rows.append(row)
        print(f"[{i:>2}/{len(queries)}] {q['kind']:<10} {q['q'][:52]:<52} "
              + " ".join(f"{row['ranks'][m] or '-':>2}" for m in MODES))

    summary = {m: {"all": metrics([r["ranks"][m] for r in rows]),
                   **{k: metrics([r["ranks"][m] for r in rows if r["kind"] == k]) for k in KINDS}} for m in MODES}
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    os.makedirs(os.path.join(HERE, "results"), exist_ok=True)
    out = os.path.join(HERE, "results", f"notes-{stamp}.json")
    with open(out, "w") as f:
        json.dump({"base": args.base, "ranAt": stamp, "notes": len(notes), "questions": len(rows),
                   "summary": summary, "rows": rows}, f, indent=2)

    print("\n" + "=" * 64)
    print(f"  {'':<10}{'recall@1':>10}{'recall@3':>10}{'MRR':>8}")
    for m in MODES:
        a = summary[m]["all"]
        print(f"  {m:<10}{a['recall@1']:>10.0%}{a['recall@3']:>10.0%}{a['mrr']:>8.2f}")
    print(f"\n  recall@1 by kind")
    print(f"  {'':<10}" + "".join(f"{k[:10]:>12}" for k in KINDS))
    for m in MODES:
        print(f"  {m:<10}" + "".join(f"{summary[m][k]['recall@1']:>12.0%}" for k in KINDS))
    misses = [r for r in rows if not r["ranks"]["hybrid"] or r["ranks"]["hybrid"] > 3]
    if misses:
        print("\n  hybrid misses (right note not in top 3):")
        for r in misses:
            print(f"    - {r['q']}  -> wanted {r['expect']}, got {', '.join(r['top']['hybrid'])}")
    print("=" * 64)
    print(f"\nwritten to {os.path.relpath(out, os.getcwd())}")


if __name__ == "__main__":
    main()
