#!/usr/bin/env python3
"""Measures how often a relevant passage is in the top k for the test queries.

    python data-pipeline/eval_retrieval.py [--index DIR] [--k 5] [--min-rate 0.8]

A passage counts as relevant when it comes from one of the query's expected
articles. Exits non-zero if the hit rate is below --min-rate.
"""

import argparse
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import retrieval  # noqa: E402

HERE = Path(__file__).resolve().parent


def normalise(title):
    return title.casefold().replace("–", "-").strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--index", type=Path, default=HERE / "work" / "index")
    parser.add_argument("--queries", type=Path, default=HERE / "eval" / "queries.jsonl")
    parser.add_argument("--k", type=int, default=5)
    parser.add_argument("--min-rate", type=float, default=0.8)
    parser.add_argument("--json", type=Path, help="also write per-query results to this file")
    parser.add_argument("--verbose", action="store_true", help="print the top k for every query")
    args = parser.parse_args()

    queries = [json.loads(line) for line in args.queries.read_text(encoding="utf-8").splitlines()
               if line.strip()]
    indexes = retrieval.open_indexes(args.index)
    if not indexes:
        sys.exit(f"no index found in {args.index}")

    # Which expected articles exist in the index at all? A query whose expected
    # articles are all absent is a coverage miss, not a ranking miss.
    wanted = {normalise(title) for query in queries for title in query["expected"]}
    present = set()
    for con in indexes.values():
        # One pass over the titles; there is no index on title to look them up by.
        for (title,) in con.execute("SELECT title FROM articles"):
            if normalise(title) in wanted:
                present.add(normalise(title))

    results, by_category, latencies = [], {}, []
    for query in queries:
        expected = {normalise(t) for t in query["expected"]}
        started = time.perf_counter()
        hits = retrieval.search(indexes, query["question"], k=args.k)
        latencies.append(time.perf_counter() - started)
        rank = next((i for i, h in enumerate(hits, 1) if normalise(h["title"]) in expected), None)
        covered = bool(expected & present)
        results.append({
            "id": query["id"], "category": query["category"], "question": query["question"],
            "fts_query": retrieval.to_fts_query(query["question"]),
            "expected": query["expected"], "hit_rank": rank, "expected_in_index": covered,
            "top": [f"{h['corpus']}: {h['title']} #{h['seq']}" for h in hits],
        })
        stats = by_category.setdefault(query["category"], [0, 0])
        stats[0] += rank is not None
        stats[1] += 1

    hits_total = sum(r["hit_rank"] is not None for r in results)
    rate = hits_total / len(results)
    for r in results:
        mark = f"hit@{r['hit_rank']}" if r["hit_rank"] else ("MISS" if r["expected_in_index"] else "MISS (expected article not in index)")
        print(f"{r['id']:>3} {mark:<8} {r['question']}")
        if args.verbose or not r["hit_rank"]:
            print(f"      query: {r['fts_query']}")
            print(f"      expected: {', '.join(r['expected'])}")
            for line in r["top"]:
                print(f"      got: {line}")

    print()
    for category, (hit, total) in sorted(by_category.items()):
        print(f"{category:<12} {hit}/{total}")
    latencies.sort()
    print(f"\ntop-{args.k} hit rate: {hits_total}/{len(results)} = {rate:.0%}")
    print(f"coverage misses (no expected article in the index): "
          f"{sum(not r['expected_in_index'] for r in results)}")
    print(f"search time on this machine: median {latencies[len(latencies) // 2] * 1000:.0f} ms, "
          f"max {latencies[-1] * 1000:.0f} ms")

    if args.json:
        args.json.write_text(json.dumps({
            "k": args.k, "hit_rate": rate, "hits": hits_total, "queries": len(results),
            "by_category": {c: {"hits": h, "total": t} for c, (h, t) in by_category.items()},
            "median_search_ms": latencies[len(latencies) // 2] * 1000,
            "max_search_ms": latencies[-1] * 1000,
            "results": results,
        }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    sys.exit(0 if rate >= args.min_rate else 1)


if __name__ == "__main__":
    main()
