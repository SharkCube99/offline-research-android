#!/usr/bin/env python3
"""Builds the offline knowledge index: one SQLite file per corpus.

    python data-pipeline/build_index.py                 # everything in corpora.json
    python data-pipeline/build_index.py --limit 2000    # small index for quick tests

Steps, each resumable:
  1. download  the Wikimedia search-index dump shards (JSON, bzip2)
  2. stage     each shard in parallel: filter, clean, chunk -> stage/<corpus>/<shard>.db
  3. merge     the staged shards, in order, into <out>/<corpus>.db with an FTS5 index
  4. report    sizes per corpus and in total (also written to <out>/manifest.json)

Only the Python standard library is used.
"""

import argparse
import bz2
import json
import re
import sqlite3
import sys
import time
import urllib.parse
import urllib.request
import zlib
from concurrent.futures import ThreadPoolExecutor
from multiprocessing import Pool
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from size_report import format_report, measure  # noqa: E402
from textproc import chunk_text, clean_text, count_words, index_text  # noqa: E402

HERE = Path(__file__).resolve().parent
USER_AGENT = "offline-research-index-builder/0.1 (one-off corpus build; python-urllib)"
# Bump when staging output changes, so stale stage files are rebuilt.
STAGE_VERSION = 2
MAX_ALIAS_CHARS = 300
BATCH = 5000
# Index file bytes per byte of compressed passage text, measured on test builds.
# Only used to turn --budget-gb into a popularity cutoff; the real size is reported after the build.
INDEX_BYTES_PER_TEXT_BYTE = 2.3


# ---------------------------------------------------------------- downloading

def http_open(url, start=0):
    headers = {"User-Agent": USER_AGENT}
    if start:
        headers["Range"] = f"bytes={start}-"
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60)


def list_dir(url):
    """Returns [(name, size_or_None)] from an nginx-style directory listing."""
    html = http_open(url).read().decode("utf-8", "replace")
    entries = []
    # One entry per line: link, date, time, size. Horizontal whitespace only, so
    # the "../" line (which has no date) cannot swallow the entry after it.
    for name, size in re.findall(r'<a href="([^"?]+)">[^<]*</a>[ \t]+\S+[ \t]+\S+[ \t]+(\d+|-)', html):
        entries.append((urllib.parse.unquote(name), None if size == "-" else int(size)))
    return entries


def resolve_dump_date(root, index_name, requested):
    """The requested date, or the newest dump in which this index finished."""
    if requested != "latest":
        return requested
    dates = sorted((n.rstrip("/") for n, _ in list_dir(root + "/") if re.fullmatch(r"\d{8}/", n)),
                   reverse=True)
    for date in dates:
        names = [n for n, _ in list_dir(f"{root}/{date}/index_name={index_name}/")]
        if "_SUCCESS" in names:
            return date
    raise SystemExit(f"no complete dump found for {index_name} under {root}")


def list_shards(root, date, index_name):
    base = f"{root}/{date}/index_name={index_name}/"
    shards = sorted((n, s) for n, s in list_dir(base) if n.endswith(".json.bz2"))
    if not shards:
        raise SystemExit(f"no shards listed at {base}")
    return [(base + urllib.parse.quote(name), name, size) for name, size in shards]


def download(url, dest, size):
    """Downloads url to dest, resuming a partial file. Skips if already complete."""
    if dest.exists() and (size is None or dest.stat().st_size == size):
        return dest
    part = dest.with_name(dest.name + ".part")
    for attempt in range(5):
        try:
            done = part.stat().st_size if part.exists() else 0
            if size is None or done < size:
                with http_open(url, done) as response, open(part, "ab" if done else "wb") as out:
                    if done and response.status != 206:
                        out.truncate(0)
                    while True:
                        block = response.read(1 << 20)
                        if not block:
                            break
                        out.write(block)
            if size is not None and part.stat().st_size != size:
                raise IOError(f"got {part.stat().st_size} bytes, expected {size}")
            part.replace(dest)
            return dest
        except Exception as error:  # network errors are retried, then reported
            print(f"  download of {dest.name} failed ({error}); retry {attempt + 1}/5", flush=True)
            time.sleep(5 * (attempt + 1))
    raise SystemExit(f"could not download {url}")


# -------------------------------------------------------------------- staging

STAGE_SCHEMA = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE articles(page_id INTEGER PRIMARY KEY, title TEXT, popularity REAL, aliases TEXT,
                      text_bytes INTEGER);
-- body: the passage as the app stores it. idx: the text that goes into the FTS index.
CREATE TABLE passages(page_id INTEGER, seq INTEGER, body BLOB, idx BLOB, PRIMARY KEY(page_id, seq));
"""


def stage_signature(params):
    return json.dumps({"v": STAGE_VERSION, **params}, sort_keys=True)


def stage_is_done(stage_path, signature):
    if not stage_path.exists():
        return False
    try:
        con = sqlite3.connect(stage_path)
        row = con.execute("SELECT value FROM meta WHERE key='signature'").fetchone()
        con.close()
        return row is not None and row[0] == signature
    except sqlite3.Error:
        return False


def is_disambiguation(doc):
    return any("disambiguation" in category.lower() for category in doc.get("category", ()))


def stage_shard(job):
    """Turns one dump shard into a stage database. Runs in a worker process."""
    shard_path, stage_path, params, limit = job
    signature = stage_signature(params)
    if limit is None and stage_is_done(stage_path, signature):
        return {"shard": shard_path.name, "skipped": True}

    stage_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = stage_path.with_name(stage_path.name + ".tmp")
    tmp.unlink(missing_ok=True)
    con = sqlite3.connect(tmp)
    con.executescript("PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;" + STAGE_SCHEMA)

    stats = {"shard": shard_path.name, "docs": 0, "redirects": 0, "disambiguation": 0,
             "too_short": 0, "articles": 0, "passages": 0, "words": 0}
    articles, passages = [], []

    def flush():
        con.executemany("INSERT OR IGNORE INTO articles VALUES (?,?,?,?,?)", articles)
        con.executemany("INSERT OR IGNORE INTO passages VALUES (?,?,?,?)", passages)
        articles.clear()
        passages.clear()

    with bz2.open(shard_path, "rb") as dump:
        for line in dump:
            if len(line) < 200 and line.startswith(b'{"index"'):
                continue  # bulk-format action line that precedes every document
            doc = json.loads(line)
            stats["docs"] += 1
            if doc.get("namespace") != 0:
                continue
            if doc.get("page_type") == "redirect" or doc.get("redirect_target"):
                stats["redirects"] += 1
                continue
            if is_disambiguation(doc):
                stats["disambiguation"] += 1
                continue
            text = clean_text(doc.get("text") or "")
            words = count_words(text)
            if words < params["min_article_words"]:
                stats["too_short"] += 1
                continue

            title = doc["title"]
            aliases = []
            for redirect in doc.get("redirect") or ():
                alias = redirect.get("title")
                if redirect.get("namespace") == 0 and alias and alias not in aliases:
                    aliases.append(alias)
            alias_text = " | ".join(aliases)[:MAX_ALIAS_CHARS]

            chunks = chunk_text(text, params["target_words"], params["max_words"],
                                params["min_tail_words"])
            text_bytes = 0
            for seq, chunk in enumerate(chunks):
                body = zlib.compress(f"{title}: {chunk}".encode("utf-8"), 9)
                idx = zlib.compress(index_text(chunk).encode("utf-8"), 1)
                passages.append((doc["page_id"], seq, body, idx))
                text_bytes += len(body)
            articles.append((doc["page_id"], title, doc.get("popularity_score") or 0.0, alias_text,
                             text_bytes))
            stats["articles"] += 1
            stats["passages"] += len(chunks)
            stats["words"] += words
            if len(passages) >= BATCH:
                flush()
            if limit is not None and stats["articles"] >= limit:
                break
    flush()
    # A limited run stops part-way through a shard, so it must not be reused by a full build.
    con.execute("INSERT INTO meta VALUES ('signature', ?)",
                (signature if limit is None else f"limited:{limit}",))
    con.execute("INSERT INTO meta VALUES ('stats', ?)", (json.dumps(stats),))
    con.commit()
    con.close()
    tmp.replace(stage_path)
    return stats


# -------------------------------------------------------------------- merging

INDEX_SCHEMA = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
CREATE TABLE articles(
    id INTEGER PRIMARY KEY,
    page_id INTEGER NOT NULL,
    title TEXT NOT NULL,
    url TEXT NOT NULL,
    popularity REAL NOT NULL,
    first_passage_id INTEGER NOT NULL,
    passage_count INTEGER NOT NULL);
CREATE TABLE passages(
    id INTEGER PRIMARY KEY,
    article_id INTEGER NOT NULL,
    seq INTEGER NOT NULL,
    body BLOB NOT NULL);
CREATE VIRTUAL TABLE passages_fts USING fts5(
    title, aliases, body, content='', tokenize='porter unicode61');
"""


def popularity_cutoff(stage_paths, max_articles, budget_gb):
    """The lowest popularity to keep so that the most-viewed articles fit the limits.

    Returns None when everything fits.
    """
    if max_articles is None and budget_gb is None:
        return None
    scored = []
    for path in stage_paths:
        con = sqlite3.connect(path)
        scored.extend(con.execute("SELECT popularity, text_bytes FROM articles"))
        con.close()
    scored.sort(reverse=True)
    budget_bytes = None if budget_gb is None else budget_gb * 1e9 / INDEX_BYTES_PER_TEXT_BYTE
    used = 0
    for count, (popularity, text_bytes) in enumerate(scored):
        used += text_bytes
        over_count = max_articles is not None and count >= max_articles
        over_budget = budget_bytes is not None and used > budget_bytes
        if over_count or over_budget:
            return scored[count - 1][0] if count else float("inf")
    return None


def merge(corpus, stage_paths, out_path, max_articles, budget_gb, limit, meta):
    cutoff = popularity_cutoff(stage_paths, max_articles, budget_gb)
    if cutoff is not None:
        print(f"  keeping articles with popularity >= {cutoff:.3g}", flush=True)
    tmp = out_path.with_name(out_path.name + ".tmp")
    tmp.unlink(missing_ok=True)
    con = sqlite3.connect(tmp)
    con.executescript("PRAGMA page_size=4096; PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;"
                      "PRAGMA cache_size=-1000000; PRAGMA locking_mode=EXCLUSIVE;" + INDEX_SCHEMA)

    article_rows, passage_rows, fts_rows = [], [], []

    def flush():
        con.executemany("INSERT INTO articles VALUES (?,?,?,?,?,?,?)", article_rows)
        con.executemany("INSERT INTO passages VALUES (?,?,?,?)", passage_rows)
        con.executemany("INSERT INTO passages_fts(rowid, title, aliases, body) VALUES (?,?,?,?)",
                        fts_rows)
        article_rows.clear()
        passage_rows.clear()
        fts_rows.clear()

    article_id = passage_id = 0
    started = time.time()
    for number, path in enumerate(stage_paths, 1):
        stage = sqlite3.connect(path)
        where = "" if cutoff is None else f"WHERE a.popularity >= {cutoff!r}"
        rows = stage.execute(
            f"SELECT a.page_id, a.title, a.popularity, a.aliases, p.seq, p.body, p.idx "
            f"FROM articles a JOIN passages p USING (page_id) {where} "
            f"ORDER BY a.page_id, p.seq")
        current_page = None
        for page_id, title, popularity, aliases, seq, body, idx in rows:
            if page_id != current_page:
                if limit is not None and article_id >= limit:
                    break
                # Flush only between articles: the open article's row is still being counted.
                if len(passage_rows) >= BATCH:
                    flush()
                current_page = page_id
                article_id += 1
                url = corpus["article_url"] + urllib.parse.quote(title.replace(" ", "_"), safe="/:()',!*")
                article_rows.append([article_id, page_id, title, url, popularity, passage_id + 1, 0])
            passage_id += 1
            article_rows[-1][6] += 1
            passage_rows.append((passage_id, article_id, seq, body))
            # Aliases (redirect titles) are indexed on the lead passage only.
            fts_rows.append((passage_id, title, aliases if seq == 0 else "",
                             zlib.decompress(idx).decode("utf-8")))
        stage.close()
        flush()
        print(f"  merged {number}/{len(stage_paths)} shards: {article_id:,} articles, "
              f"{passage_id:,} passages, {time.time() - started:.0f}s", flush=True)
        if limit is not None and article_id >= limit:
            break

    print("  optimising the FTS index", flush=True)
    con.execute("INSERT INTO passages_fts(passages_fts) VALUES ('optimize')")
    meta = dict(meta, articles=article_id, passages=passage_id)
    con.executemany("INSERT INTO meta VALUES (?,?)", [(k, str(v)) for k, v in meta.items()])
    con.commit()
    con.close()
    out_path.unlink(missing_ok=True)
    tmp.replace(out_path)
    return article_id, passage_id


# ----------------------------------------------------------------------- main

def build_corpus(corpus, config, args, params):
    name = corpus["name"]
    root = config["dump_root"]
    date = resolve_dump_date(root, corpus["index_name"], args.dump_date)
    shards = list_shards(root, date, corpus["index_name"])
    downloads = args.work / "downloads"
    stage_dir = args.work / "stage" / name / date
    downloads.mkdir(parents=True, exist_ok=True)
    total_bytes = sum(size or 0 for _, _, size in shards)
    print(f"[{name}] dump {date}: {len(shards)} shards, {total_bytes / 1e9:.2f} GB compressed",
          flush=True)

    stats = []
    if args.limit is not None:
        # Take shards one at a time and stop as soon as there are enough articles.
        stage_dir = args.work / "stage-limited" / name / date
        stage_paths, have = [], 0
        for url, shard_name, size in shards:
            shard_path = download(url, downloads / shard_name, size)
            stage_path = stage_dir / (shard_name + ".db")
            result = stage_shard((shard_path, stage_path, params, args.limit - have))
            stats.append(result)
            stage_paths.append(stage_path)
            have += result["articles"]
            if have >= args.limit:
                break
    else:
        print(f"[{name}] downloading (resumable)", flush=True)
        with ThreadPoolExecutor(args.download_threads) as pool:
            paths = list(pool.map(lambda s: download(s[0], downloads / s[1], s[2]), shards))
        stage_paths = [stage_dir / (p.name + ".db") for p in paths]
        jobs = [(p, s, params, None) for p, s in zip(paths, stage_paths)]
        print(f"[{name}] staging {len(jobs)} shards with {args.workers} workers", flush=True)
        with Pool(args.workers) as pool:
            for done, result in enumerate(pool.imap_unordered(stage_shard, jobs), 1):
                stats.append(result)
                label = "already staged" if result.get("skipped") else f"{result['articles']:,} articles"
                print(f"  staged {done}/{len(jobs)}: {result['shard']} ({label})", flush=True)

    print(f"[{name}] merging into {args.out / (name + '.db')}", flush=True)
    meta = {
        "corpus": name,
        "source": f"{root}/{date}/index_name={corpus['index_name']}/",
        "dump_date": date,
        "licence": corpus["licence"],
        "attribution": corpus["attribution"],
        "article_url": corpus["article_url"],
        "chunking": json.dumps(params, sort_keys=True),
        "tokenizer": "porter unicode61",
        "fts_body": "content words only (function words removed before indexing)",
        "passage_encoding": "zlib(utf-8 '<title>: <text>')",
        "limit": args.limit,
        "max_articles": args.max_articles,
        "budget_gb": args.budget_gb,
    }
    articles, passages = merge(corpus, stage_paths, args.out / f"{name}.db",
                               args.max_articles, args.budget_gb, args.limit, meta)
    filtered = {}
    for key in ("docs", "redirects", "disambiguation", "too_short"):
        filtered[key] = sum(s.get(key, 0) for s in stats)
    return {"name": name, "dump_date": date, "source": meta["source"], "licence": corpus["licence"],
            "articles": articles, "passages": passages, "staging": filtered}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--corpus", action="append",
                        help="corpus name from corpora.json; repeatable (default: all)")
    parser.add_argument("--limit", type=int, default=None,
                        help="keep at most N articles per corpus, downloading only the shards needed")
    parser.add_argument("--max-articles", type=int, default=None,
                        help="full build only: keep the N most-viewed articles per corpus")
    parser.add_argument("--budget-gb", type=float, default=None,
                        help="full build only: keep the most-viewed articles that fit in about "
                             "this many GB per corpus")
    parser.add_argument("--dump-date", default="latest", help="YYYYMMDD, or 'latest' (default)")
    parser.add_argument("--out", type=Path, default=HERE / "work" / "index")
    parser.add_argument("--work", type=Path, default=HERE / "work")
    parser.add_argument("--workers", type=int, default=4, help="parallel staging processes")
    parser.add_argument("--download-threads", type=int, default=2)
    parser.add_argument("--target-words", type=int, default=250)
    parser.add_argument("--max-words", type=int, default=300)
    parser.add_argument("--min-tail-words", type=int, default=80)
    parser.add_argument("--min-article-words", type=int, default=50)
    args = parser.parse_args()

    config = json.loads((HERE / "corpora.json").read_text(encoding="utf-8"))
    corpora = config["corpora"]
    if args.corpus:
        unknown = set(args.corpus) - {c["name"] for c in corpora}
        if unknown:
            parser.error(f"unknown corpus: {', '.join(sorted(unknown))}")
        corpora = [c for c in corpora if c["name"] in args.corpus]
    params = {"target_words": args.target_words, "max_words": args.max_words,
              "min_tail_words": args.min_tail_words, "min_article_words": args.min_article_words}

    args.out.mkdir(parents=True, exist_ok=True)
    started = time.time()
    built = [build_corpus(corpus, config, args, params) for corpus in corpora]

    sizes = measure(args.out)
    manifest_path = args.out / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    manifest.setdefault("corpora", {})
    for entry in built:
        manifest["corpora"][entry["name"]] = entry
    manifest["sizes"] = sizes
    manifest["chunking"] = params
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    print(f"\nbuilt in {time.time() - started:.0f}s\n")
    print(format_report(sizes))


if __name__ == "__main__":
    main()
