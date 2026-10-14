#!/usr/bin/env python3
"""Compares two scripts/m5_run.sh runs: before and after one change.

    python scripts/m5_compare.py BEFORE.jsonl AFTER.jsonl --count N

N is the number of questions per round (the --count the runs used, or the length
of the questions file). The runs are paired answer by answer, so both must hold
the same number of answers, in the same order. Questions are compared by text;
a mismatch is reported and the verdicts are marked as not paired.

For every measure the report gives the median before and after, the change, and
how many pairs improved. A change is called "better" or "worse" only when the
median moved by at least --min-change (default 10%) AND at least --min-consistent
(default 75%) of the pairs moved the same way. Anything else is "no clear change".
The 10% figure follows docs/PERFORMANCE.md: on this phone, differences of 10% are
within the noise of a single run.

Answers are split into the first question after an app start (it pays for loading
and for reading the answer rules) and the rest, because a change can help one and
not the other.
"""

import argparse
import json
import math
import statistics
import sys
from pathlib import Path

# name, label, key in the app's metrics line, scale, unit, higher is better
MEASURES = [
    ("ttft", "Time to first word", "ttft_ms", 1 / 1000, "s", False),
    ("prompt_ms", "Reading the prompt", "prompt_ms", 1 / 1000, "s", False),
    ("prompt_tokens", "Prompt tokens read", "prompt_tokens", 1, "tok", False),
    ("prompt_reused", "Prompt tokens reused", "prompt_reused", 1, "tok", True),
    ("read_speed", "Reading speed", "prompt_tokens_per_sec", 1, "tok/s", True),
    ("write_speed", "Writing speed", "tokens_per_sec", 1, "tok/s", True),
    ("gen_tokens", "Tokens written", "gen_tokens", 1, "tok", None),
    ("faults", "Major page faults", "major_faults", 1, "", False),
    ("peak_rss", "Peak RSS", "peak_rss_kb", 1 / 1024, "MB", False),
    ("mem_avail", "Memory available at the end", "mem_available_kb", 1 / 1024, "MB", True),
]


def load(path):
    rows = []
    for number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except ValueError:
            sys.exit(f"{path}:{number}: not JSON")
    return rows


def question_of(row):
    rag = row.get("rag") or {}
    return rag.get("question") or row.get("q") or ""


def value(row, key, scale):
    raw = row.get(key)
    if raw is None:
        raw = (row.get("metrics") or {}).get(key)
    if raw is None or isinstance(raw, bool):
        return None
    return float(raw) * scale


def verdict(before, after, higher_better, min_change, min_consistent):
    """(label, change fraction, improved pairs, pairs) for paired lists."""
    pairs = [(b, a) for b, a in zip(before, after) if b is not None and a is not None]
    if not pairs:
        return "not measured", None, 0, 0
    median_b = statistics.median(b for b, _ in pairs)
    median_a = statistics.median(a for _, a in pairs)
    if median_b == 0:
        # A baseline of zero has no percentage: any rise is infinitely large.
        change = 0.0 if median_a == 0 else math.copysign(math.inf, median_a)
    else:
        change = (median_a - median_b) / median_b
    if higher_better is None:
        return "context", change, 0, len(pairs)
    sign = 1 if higher_better else -1
    improved = sum(1 for b, a in pairs if sign * (a - b) > 0)
    worsened = sum(1 for b, a in pairs if sign * (a - b) < 0)
    good = sign * change
    if good >= min_change and improved / len(pairs) >= min_consistent:
        return "better", change, improved, len(pairs)
    if good <= -min_change and worsened / len(pairs) >= min_consistent:
        return "worse", change, improved, len(pairs)
    return "no clear change", change, improved, len(pairs)


def fmt(number, unit):
    if number is None:
        return "-"
    text = f"{number:,.1f}" if abs(number) < 100 else f"{number:,.0f}"
    return f"{text} {unit}".strip()


def section(title, before, after, args, out):
    out.append(f"### {title} ({len(before)} pairs)")
    out.append("")
    out.append("| Measure | Before (median) | After (median) | Change | Pairs improved | Verdict |")
    out.append("|---|---|---|---|---|---|")
    for _, label, key, scale, unit, higher in MEASURES:
        b = [value(r, key, scale) for r in before]
        a = [value(r, key, scale) for r in after]
        label_v, change, improved, n = verdict(b, a, higher, args.min_change, args.min_consistent)
        mb = statistics.median([x for x in b if x is not None]) if any(x is not None for x in b) else None
        ma = statistics.median([x for x in a if x is not None]) if any(x is not None for x in a) else None
        if change is None:
            change_text = "-"
        elif math.isinf(change):
            change_text = "from 0"
        else:
            change_text = f"{change:+.0%}"
        pairs_text = "-" if higher is None or n == 0 else f"{improved}/{n}"
        out.append(f"| {label} | {fmt(mb, unit)} | {fmt(ma, unit)} | {change_text} | {pairs_text} | {label_v} |")
    out.append("")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--count", type=int, required=True, help="questions per round")
    parser.add_argument("--min-change", type=float, default=0.10)
    parser.add_argument("--min-consistent", type=float, default=0.75)
    args = parser.parse_args()

    before, after = load(args.before), load(args.after)
    if not before or not after:
        sys.exit("an input file holds no answers")
    if len(before) != len(after):
        sys.exit(f"not paired: {len(before)} answers before, {len(after)} after")
    if len(before) % args.count:
        sys.exit(f"{len(before)} answers is not a whole number of rounds of {args.count}")

    out = [f"## Before: `{args.before.name}`  After: `{args.after.name}`", ""]
    mismatched = [i for i, (b, a) in enumerate(zip(before, after))
                  if question_of(b) and question_of(a) and question_of(b) != question_of(a)]
    if mismatched:
        out.append(f"**Not paired: the questions differ at answers {mismatched[:10]}.** "
                   "The verdicts below compare different prompts.")
        out.append("")
    stops = {}
    for name, rows in (("before", before), ("after", after)):
        for row in rows:
            stops.setdefault(name, {}).setdefault(row.get("stop", "?"), 0)
            stops[name][row.get("stop", "?")] += 1
    out.append(f"Stop reasons: before {stops['before']}, after {stops['after']}. "
               "Answers that ended on a limit are not comparable in length.")
    out.append("")

    first = [i for i in range(len(before)) if i % args.count == 0]
    rest = [i for i in range(len(before)) if i % args.count != 0]
    section("All answers", before, after, args, out)
    section("First question after an app start", [before[i] for i in first], [after[i] for i in first], args, out)
    if rest:
        section("Later questions in the same session", [before[i] for i in rest], [after[i] for i in rest], args, out)
    out.append("A verdict needs a change of at least "
               f"{args.min_change:.0%} in the median and at least {args.min_consistent:.0%} of pairs "
               "moving the same way. Run the baseline again after the change (A, B, A) and discard "
               "the comparison if the two baselines differ by more than the change being claimed.")
    print("\n".join(out))


if __name__ == "__main__":
    main()
