#!/usr/bin/env python3
"""Turns blind grades into the benchmark table.

Usage:
  python bench/report.py GRADES KEY APP_ANSWERS [--out REPORT.md]

GRADES is what the grader returns for the sheet from make_pairs.py: a JSON list
with one object per question,
  {"id": "...", "A": {"score": 0-10, "errors": ["..."]}, "B": {...}, "preferred": "A" | "B" | "tie"}
where score says how well the answer serves the person asking and errors lists
its factual mistakes. KEY is the key file from make_pairs.py.

The headline figure is the app's total score as a share of the reference's
total, overall and per question group, as in AndroidLM's report on the same
questions. Timings come from the app's own records in APP_ANSWERS.
"""
import argparse
import json
from pathlib import Path
from statistics import median

GROUPS = [("food", "Restaurants"), ("cry", "Crypto"), ("trv", "Travel"), ("dng", "Emergencies"), ("mth", "Arithmetic")]


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("grades")
    parser.add_argument("key")
    parser.add_argument("app")
    parser.add_argument("--out")
    args = parser.parse_args()

    grades = {g["id"]: g for g in json.loads(Path(args.grades).read_text(encoding="utf-8"))}
    key = json.loads(Path(args.key).read_text(encoding="utf-8"))
    app = {r["id"]: r for r in (json.loads(line) for line in Path(args.app).read_text(encoding="utf-8").splitlines() if line.strip())}
    ungraded = sorted(set(key) - set(grades))
    if ungraded:
        raise SystemExit(f"{len(ungraded)} questions have no grade: {ungraded[:8]}")

    rows = []
    for qid, grade in grades.items():
        side = {who: letter for letter, who in key[qid].items()}  # "app" -> "A" or "B"
        mine, theirs = grade[side["app"]], grade[side["reference"]]
        preferred = grade.get("preferred", "tie")
        record = app.get(qid, {})
        metrics = record.get("metrics") or {}
        rag = metrics.get("rag") or {}
        first = rag.get("total_ttft_ms")
        rows.append({
            "id": qid,
            "group": qid.split("-")[0],
            "app": float(mine["score"]),
            "ref": float(theirs["score"]),
            "app_errors": len(mine.get("errors") or []),
            "ref_errors": len(theirs.get("errors") or []),
            "preferred": "tie" if preferred == "tie" else key[qid][preferred],
            "answered": record.get("status") == "ok" and bool(record.get("draft")),
            "first_s": first / 1000 if first else None,
            "done_s": record.get("wall_seconds"),
        })

    def line(label, part):
        if not part:
            return None
        app_total, ref_total = sum(r["app"] for r in part), sum(r["ref"] for r in part)
        share = f"{100 * app_total / ref_total:.0f}%" if ref_total else "n/a"
        firsts = [r["first_s"] for r in part if r["first_s"]]
        dones = [r["done_s"] for r in part if r["answered"] and r["done_s"]]
        return (f"| {label} | {len(part)} | {app_total / len(part):.2f} | {ref_total / len(part):.2f} | **{share}** | "
                f"{sum(r['preferred'] == 'app' for r in part)} / {sum(r['preferred'] == 'reference' for r in part)} / "
                f"{sum(r['preferred'] == 'tie' for r in part)} | "
                f"{sum(r['app_errors'] for r in part)} / {sum(r['ref_errors'] for r in part)} | "
                f"{sum(not r['answered'] for r in part)} | "
                f"{median(firsts):.0f} s | {median(dones):.0f} s |" if firsts and dones else
                f"| {label} | {len(part)} | {app_total / len(part):.2f} | {ref_total / len(part):.2f} | **{share}** | "
                f"{sum(r['preferred'] == 'app' for r in part)} / {sum(r['preferred'] == 'reference' for r in part)} / "
                f"{sum(r['preferred'] == 'tie' for r in part)} | "
                f"{sum(r['app_errors'] for r in part)} / {sum(r['ref_errors'] for r in part)} | "
                f"{sum(not r['answered'] for r in part)} | n/a | n/a |")

    out = ["| Group | n | App (mean /10) | Reference | App as share of reference | Preferred app / reference / tie | "
           "Errors app / reference | Not answered | First word (median) | Done (median) |",
           "|---|---|---|---|---|---|---|---|---|---|"]
    for prefix, label in GROUPS:
        row = line(label, [r for r in rows if r["group"] == prefix])
        if row:
            out.append(row)
    out.append(line("All", rows))
    out.append("")
    out.append("Lowest-scoring answers of the app:")
    out.append("")
    for r in sorted(rows, key=lambda r: r["app"])[:10]:
        out.append(f"- `{r['id']}`: {r['app']:.1f} against {r['ref']:.1f}" + ("" if r["answered"] else " (not answered)"))
    text = "\n".join(out) + "\n"
    if args.out:
        Path(args.out).write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
