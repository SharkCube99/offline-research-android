"""Query handling and BM25 search over the corpus indexes.

This is the reference for the retriever the Android app will implement in M3:
the same query construction and the same SQL have to be used there.
"""

import sqlite3
import sys
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from textproc import FUNCTION_WORDS, tokenize  # noqa: E402

# Words that are common in the way questions are phrased but say nothing about
# the topic. Unlike FUNCTION_WORDS they stay in the index, because in an article
# they can carry meaning.
QUERY_FILLER = frozenset("""
best way ways know need tell much many good thing things work works make makes use used using
get difference differences different versus vs explain
""".split())

# bm25() column weights for (title, aliases, body).
DEFAULT_WEIGHTS = (4.0, 3.0, 1.0)


def query_terms(question):
    """Lower-cased content words of the question, in order, without repeats."""
    tokens = tokenize(question)
    terms = [t for t in tokens if t not in FUNCTION_WORDS and t not in QUERY_FILLER and len(t) > 1]
    if not terms:
        # Function words are absent from passage bodies but still present in
        # titles ("The Who"), so a question made only of them can still match.
        terms = tokens
    return list(dict.fromkeys(terms))


def to_fts_query(question):
    """An FTS5 query that matches passages containing any content word; BM25 does the ranking."""
    return " OR ".join(f'"{term}"' for term in query_terms(question))


def open_indexes(index_dir):
    """Opens every corpus database in index_dir, read-only. Returns {corpus: connection}."""
    return {path.stem: sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)
            for path in sorted(Path(index_dir).glob("*.db"))}


def search(indexes, question, k=5, weights=DEFAULT_WEIGHTS, per_article=2, candidates=20):
    """Top-k passages across all corpora.

    Each corpus contributes its best `candidates` passages; they are merged by
    BM25 score. At most `per_article` passages of one article are kept, so one
    long article cannot fill the whole result list.
    """
    fts_query = to_fts_query(question)
    if not fts_query:
        return []
    hits = []
    for corpus, con in indexes.items():
        rows = con.execute(
            "SELECT f.rowid, bm25(passages_fts, ?, ?, ?) AS score, a.title, a.url, p.seq, p.body "
            "FROM passages_fts f "
            "JOIN passages p ON p.id = f.rowid "
            "JOIN articles a ON a.id = p.article_id "
            "WHERE passages_fts MATCH ? ORDER BY score LIMIT ?",
            (*weights, fts_query, candidates))
        for passage_id, score, title, url, seq, body in rows:
            hits.append({"corpus": corpus, "passage_id": f"{corpus}:{passage_id}", "score": score,
                         "title": title, "url": url, "seq": seq,
                         "text": zlib.decompress(body).decode("utf-8")})
    hits.sort(key=lambda h: h["score"])  # bm25() is lower-is-better
    results, per_title = [], {}
    for hit in hits:
        key = (hit["corpus"], hit["title"])
        if per_title.get(key, 0) >= per_article:
            continue
        per_title[key] = per_title.get(key, 0) + 1
        results.append(hit)
        if len(results) == k:
            break
    return results
