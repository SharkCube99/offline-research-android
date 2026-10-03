#!/usr/bin/env python3
"""Builds the blind grading sheet: for every question, the app's answer and the
reference answer as "A" and "B" in an order chosen at random, and a separate
key file that says which is which. The grader sees only the sheet.

Usage:
  python bench/make_pairs.py QUESTIONS APP_ANSWERS REFERENCE_ANSWERS PAIRS_OUT KEY_OUT [--seed N]

A question the app did not answer (timeout, crash) stays in the sheet with the
text "(no answer)", so a failure costs points instead of disappearing.

The method follows scripts/answer_pairs.py of AndroidLM, whose reference
answers these are, so that the two apps are graded the same way.
"""
import argparse
import json
import random
from pathlib import Path

NO_ANSWER = "(no answer)"


def read(path):
    return {r["id"]: r for r in (json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip())}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("questions")
    parser.add_argument("app")
    parser.add_argument("reference")
    parser.add_argument("pairs_out")
    parser.add_argument("key_out")
    parser.add_argument("--seed", type=int, default=20261003)
    args = parser.parse_args()

    questions, app, reference = read(args.questions), read(args.app), read(args.reference)
    missing = sorted(set(questions) - set(reference))
    if missing:
        raise SystemExit(f"no reference answer for: {missing}")
    rng = random.Random(args.seed)
    pairs, key = [], {}
    for qid in sorted(questions):
        mine = (app.get(qid, {}).get("draft") or "").strip() or NO_ANSWER
        theirs = reference[qid]["draft"].strip()
        app_is_a = rng.random() < 0.5
        pairs.append({
            "id": qid,
            "q": questions[qid]["q"],
            "expect": questions[qid].get("expect") or questions[qid].get("check"),
            "A": mine if app_is_a else theirs,
            "B": theirs if app_is_a else mine,
        })
        key[qid] = {"A": "app" if app_is_a else "reference", "B": "reference" if app_is_a else "app"}
    Path(args.pairs_out).write_text(json.dumps(pairs, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    Path(args.key_out).write_text(json.dumps(key, indent=1) + "\n", encoding="utf-8")
    unanswered = sum(1 for qid in questions if not (app.get(qid, {}).get("draft") or "").strip())
    print(f"{len(pairs)} pairs; {unanswered} without an answer from the app")


if __name__ == "__main__":
    main()
