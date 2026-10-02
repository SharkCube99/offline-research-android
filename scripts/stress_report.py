#!/usr/bin/env python3
"""Turns a stress run's metrics lines into a Markdown report.

    python scripts/stress_report.py RUN.jsonl [--expected 20] [--exits EXITS.jsonl]

Prints the report and exits 0 only if every expected question was answered in
one run with no error. Used by scripts/stress.sh; the output is meant to be
pasted into docs/PERFORMANCE.md.
"""

import argparse
import json
import statistics
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("metrics")
    parser.add_argument("--expected", type=int, default=20)
    parser.add_argument("--exits", help="exits.jsonl lines recorded after the run started")
    args = parser.parse_args()

    rows = [json.loads(line) for line in open(args.metrics, encoding="utf-8") if line.strip()]
    rows = [r for r in rows if r.get("stress")]
    exits = []
    if args.exits:
        try:
            exits = [json.loads(line) for line in open(args.exits, encoding="utf-8") if line.strip()]
        except FileNotFoundError:
            pass

    if not rows:
        print("**Result: FAIL.** No stress answers were logged.")
        for e in exits:
            print(f"- App exit recorded by Android: {e['reason']} ({e['description']}) at {e['timestamp']}")
        return 1

    run = rows[-1]["stress"]["run"]
    rows = [r for r in rows if r["stress"]["run"] == run]
    first = rows[0]
    answered = [r["stress"]["index"] for r in rows]
    complete = answered == list(range(1, args.expected + 1))
    bad_exits = [e for e in exits if e["reason"] not in ("USER_REQUESTED", "USER_STOPPED")]
    passed = complete and not bad_exits

    print(f"**Result: {'PASS' if passed else 'FAIL'}.** {len(rows)} of {args.expected} consecutive questions answered"
          + (", no crash or low-memory kill recorded." if passed else "."))
    for e in exits:
        print(f"- App exit recorded by Android: {e['reason']} ({e['description']}) at {e['timestamp']}, RSS {e['rss_kb']} kB")
    print()
    print(f"- Device: {first['device']} ({first['soc']}, Android SDK {first['android_sdk']}), app {first['app_version']}")
    print(f"- Profile `{first['profile']}`: `{first['model_file']}`, n_ctx {first['n_ctx']}, n_batch {first['n_batch']}, "
          f"threads {first['threads']}/{first['threads_batch']} ({first['thread_affinity']}), repack {str(first['repack']).lower()}")
    print(f"- Planner: {first['rag']['planner']}. Model load time: {first['load_ms'] / 1000:.1f} s")
    print(f"- Run started {run}")
    print()
    print("| # | Prompt tokens (reused) | Prefill tok/s | First word after | Generated | Gen tok/s | RSS GB | Peak RSS GB | Major faults | Free GB | Thermal | Cited | Not covered |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        rag = r["rag"]
        print(f"| {r['stress']['index']} | {r['prompt_tokens']} ({r['prompt_reused']}) | {r['prompt_tokens_per_sec']:.2f} | "
              f"{rag['total_ttft_ms'] / 1000:.0f} s | {r['gen_tokens']} | {r['tokens_per_sec']:.2f} | "
              f"{r['rss_kb'] / 1e6:.2f} | {r['peak_rss_kb'] / 1e6:.2f} | {r['major_faults']} | "
              f"{r['mem_available_kb'] / 1e6:.2f} | {r['thermal']} | {len(rag['cited'])} | {'yes' if rag['not_covered'] else 'no'} |")

    ran = [r for r in rows if r["gen_tokens"] > 0]
    if ran:
        median = statistics.median
        print()
        print(f"Medians over {len(ran)} answers: first word after {median(r['rag']['total_ttft_ms'] for r in ran) / 1000:.0f} s, "
              f"prefill {median(r['prompt_tokens_per_sec'] for r in ran):.2f} tok/s, "
              f"generation {median(r['tokens_per_sec'] for r in ran):.2f} tok/s. "
              f"Peak RSS {max(r['peak_rss_kb'] for r in rows) / 1e6:.2f} GB. "
              f"Major faults per answer: median {median(r['major_faults'] for r in ran):.0f}, max {max(r['major_faults'] for r in ran)}. "
              f"Thermal statuses seen: {', '.join(sorted({r['thermal'] for r in rows}))}. "
              f"Invented citation numbers: {sum(len(r['rag']['invalid_citations']) for r in rows)}.")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
