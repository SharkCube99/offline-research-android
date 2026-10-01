#!/usr/bin/env python3
"""Reports the size of each corpus index and the total.

    python data-pipeline/size_report.py [INDEX_DIR]
"""

import sqlite3
import sys
from pathlib import Path

GB = 1e9


def measure(index_dir):
    """Returns {"corpora": [...], "total_bytes": n} for every *.db in index_dir."""
    corpora = []
    for path in sorted(Path(index_dir).glob("*.db")):
        con = sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)
        articles = con.execute("SELECT COUNT(*) FROM articles").fetchone()[0]
        passages, text_bytes = con.execute("SELECT COUNT(*), SUM(LENGTH(body)) FROM passages").fetchone()
        dump_date = con.execute("SELECT value FROM meta WHERE key='dump_date'").fetchone()
        con.close()
        corpora.append({
            "name": path.stem,
            "file": path.name,
            "bytes": path.stat().st_size,
            "articles": articles,
            "passages": passages,
            "compressed_text_bytes": text_bytes or 0,
            "dump_date": dump_date[0] if dump_date else None,
        })
    return {"corpora": corpora, "total_bytes": sum(c["bytes"] for c in corpora)}


def format_report(sizes):
    lines = ["| Corpus | Dump | Articles | Passages | Stored text (GB) | Index file (GB) |",
             "|---|---|---|---|---|---|"]
    for c in sizes["corpora"]:
        lines.append(f"| {c['name']} | {c['dump_date']} | {c['articles']:,} | {c['passages']:,} | "
                     f"{c['compressed_text_bytes'] / GB:.3f} | {c['bytes'] / GB:.3f} |")
    lines.append(f"| **Total** | | {sum(c['articles'] for c in sizes['corpora']):,} | "
                 f"{sum(c['passages'] for c in sizes['corpora']):,} | "
                 f"{sum(c['compressed_text_bytes'] for c in sizes['corpora']) / GB:.3f} | "
                 f"**{sizes['total_bytes'] / GB:.3f}** |")
    lines.append("")
    lines.append("GB = 1,000,000,000 bytes. The index file holds the compressed passage text, "
                 "the article table and the FTS5 index.")
    return "\n".join(lines)


if __name__ == "__main__":
    default = Path(__file__).resolve().parent / "work" / "index"
    print(format_report(measure(sys.argv[1] if len(sys.argv) > 1 else default)))
