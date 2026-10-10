#!/usr/bin/env python3
"""
ForgeFlow retrieval eval: does code search put the right file near the top?

The agent eval (run_evals.py) asks "did the app build?". This one isolates
the R in RAG. It loads a fixed 29-file sample app (evals/retrieval/fixture,
"FreshCart") into a fresh project, asks 42 labelled questions, and scores
where the right file lands - for each of the three search modes:

    hybrid    vector + keyword, merged with Reciprocal Rank Fusion (what ships)
    vector    meaning only (pgvector, Gemini embeddings)
    keyword   words only (Postgres full-text)

Metrics, per mode and per kind of question:

    recall@k  share of questions whose right file is among the top k files
    MRR       mean of 1/rank of the right file (0 if it never shows up)

Files, not chunks: the search returns chunks, and a file is ranked where its
first chunk appears. "Which file do I open?" is the question a developer, or
the agent, actually has.

Usage:
    python3 evals/run_retrieval_eval.py                      # local server
    python3 evals/run_retrieval_eval.py --base https://...   # the deployed app

Needs real embeddings to mean anything: run it against a server with
FORGEFLOW_EMBEDDER=gemini (the default). If the embedding API fails, the
server says so (X-Search-Degraded / X-Index-Missing-Vectors) and the runner
waits and retries rather than recording a keyword-only result as "vector".
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

from run_evals import call, delete_project, sign_in

HERE = os.path.dirname(os.path.abspath(__file__))
FIXTURE = os.path.join(HERE, "retrieval", "fixture")
QUERIES = os.path.join(HERE, "retrieval", "queries.json")
MODES = ["hybrid", "vector", "keyword"]
KINDS = ["identifier", "described", "paraphrase"]
DEPTH = 20            # chunks asked for per query; the server's maximum
PROJECT_NAME = "eval-retrieval"


class Unfair(Exception):
    """The server couldn't embed, so this result wouldn't measure what it claims to."""


def search(base, pid, query, mode, token, pause):
    url = f"{base}/api/v1/projects/{pid}/search?" + urllib.parse.urlencode(
        {"q": query, "k": DEPTH, "mode": mode})
    for attempt in range(6):
        req = urllib.request.Request(url)
        req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=180) as r:
                hits = json.loads(r.read().decode())
                degraded = r.headers.get("X-Search-Degraded") == "true"
                missing = int(r.headers.get("X-Index-Missing-Vectors") or 0)
        except urllib.error.HTTPError as e:
            if e.code == 429:
                time.sleep(int(e.headers.get("Retry-After") or 5))
                continue
            raise RuntimeError(f"search -> {e.code}: {e.read().decode()[:300]}")

        needs_vectors = mode != "keyword"
        if needs_vectors and (degraded or missing):
            if attempt < 5:
                why = "query not embedded" if degraded else f"{missing} chunk(s) without vectors"
                print(f"({why}; waiting 20s) ", end="", flush=True)
                time.sleep(20)          # each retry also re-tries the missing vectors server-side
                continue
            raise Unfair("embeddings kept failing - fix GOOGLE_API_KEY / quota and run again")
        time.sleep(pause)
        return hits
    raise RuntimeError("search kept being rate-limited")


def ranked_files(hits):
    seen = []
    for h in hits:
        if h["path"] not in seen:
            seen.append(h["path"])
    return seen


def rank_of(files, q):
    good = {q["expect"], *q.get("accept", [])}
    for i, path in enumerate(files, 1):
        if path in good:
            return i
    return None


def metrics(ranks):
    n = len(ranks) or 1
    hit = lambda k: sum(1 for r in ranks if r is not None and r <= k) / n
    return {"n": len(ranks), "recall@1": hit(1), "recall@3": hit(3), "recall@5": hit(5),
            "mrr": sum(1 / r for r in ranks if r) / n}


def load_fixture(base, pid, token):
    count = 0
    for root, _, names in os.walk(FIXTURE):
        for name in sorted(names):
            full = os.path.join(root, name)
            rel = os.path.relpath(full, FIXTURE).replace(os.sep, "/")
            with open(full, encoding="utf-8") as f:
                call(base, f"/api/v1/projects/{pid}/files/content", "PUT",
                     {"path": rel, "content": f.read()}, token)
            count += 1
    return count


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8081")
    ap.add_argument("--email", default="evals@forgeflow.dev")
    ap.add_argument("--password", default="evalrunner2026")
    ap.add_argument("--pause", type=float, default=1.0,
                    help="seconds between searches; each hybrid/vector search embeds the query,\n"
                         "and the free embedding tier has a per-minute cap")
    ap.add_argument("--keep", action="store_true", help="keep the project afterwards, to look around")
    args = ap.parse_args()

    queries = json.load(open(QUERIES))["queries"]
    token = sign_in(args.base, args.email, args.password)

    for p in call(args.base, "/api/v1/projects", token=token) or []:
        if p.get("name") == PROJECT_NAME:
            delete_project(args.base, p["id"], token)
    pid = call(args.base, "/api/v1/projects", "POST", {"name": PROJECT_NAME}, token)["id"]

    started = time.time()
    try:
        n_files = load_fixture(args.base, pid, token)
        print(f"Retrieval eval - {len(queries)} questions over {n_files} files, "
              f"{len(MODES)} modes, against {args.base}\n")

        # The first search indexes the project (embeds every chunk). Do it
        # once up front, waiting until every chunk has a vector.
        print("indexing ... ", end="", flush=True)
        search(args.base, pid, "warm up", "vector", token, 0)
        print(f"done ({time.time() - started:.0f}s)\n")

        rows = []
        for i, q in enumerate(queries, 1):
            print(f"[{i:>2}/{len(queries)}] {q['kind']:<10} {q['q'][:52]:<52}", end=" ", flush=True)
            row = {"q": q["q"], "kind": q["kind"], "expect": q["expect"], "ranks": {}, "top": {}}
            for mode in MODES:
                files = ranked_files(search(args.base, pid, q["q"], mode, token, args.pause))
                row["ranks"][mode] = rank_of(files, q)
                row["top"][mode] = files[:3]
            rows.append(row)
            print("  ".join(f"{m[0]}:{row['ranks'][m] or '-'}" for m in MODES))
    except Unfair as e:
        print(f"\n\nstopped: {e}")
        sys.exit(2)
    finally:
        if not args.keep:
            delete_project(args.base, pid, token)

    summary = {mode: {"all": metrics([r["ranks"][mode] for r in rows]),
                      **{k: metrics([r["ranks"][mode] for r in rows if r["kind"] == k]) for k in KINDS}}
               for mode in MODES}

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out_dir = os.path.join(HERE, "results")
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, f"retrieval-{stamp}.json")
    with open(out, "w") as f:
        json.dump({"base": args.base, "ranAt": stamp, "files": n_files, "questions": len(rows),
                   "summary": summary, "rows": rows}, f, indent=2)

    print("\n" + "=" * 66)
    print(f"  {'':<10}{'recall@1':>10}{'recall@3':>10}{'recall@5':>10}{'MRR':>8}")
    for mode in MODES:
        a = summary[mode]["all"]
        print(f"  {mode:<10}{a['recall@1']:>10.0%}{a['recall@3']:>10.0%}{a['recall@5']:>10.0%}{a['mrr']:>8.2f}")
    print(f"\n  recall@5 by kind of question")
    print(f"  {'':<12}" + "".join(f"{m:>10}" for m in MODES))
    for k in KINDS:
        n = summary["hybrid"][k]["n"]
        print(f"  {k + f' ({n})':<12}" + "".join(f"{summary[m][k]['recall@5']:>10.0%}" for m in MODES))
    misses = [r for r in rows if not r["ranks"]["hybrid"] or r["ranks"]["hybrid"] > 5]
    if misses:
        print("\n  hybrid misses (right file not in top 5):")
        for r in misses:
            print(f"    - {r['q']}  -> wanted {r['expect']}, got {', '.join(r['top']['hybrid'])}")
    print("=" * 66)
    print(f"\nwritten to {os.path.relpath(out, os.getcwd())}  ({(time.time() - started) / 60:.1f} min)")


if __name__ == "__main__":
    main()
