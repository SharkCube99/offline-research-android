"""Writes an index file in the app's format from a list of documents.

build_index.py builds the Wikipedia and Wikivoyage indexes from Wikimedia
dumps. The smaller source packs (Ethereum texts, places) come from other kinds
of files; they build their documents themselves and hand them to write_index,
so every index file has the same tables and the app reads them all alike.
"""
import sqlite3
import zlib
from pathlib import Path

from textproc import article_names, index_text

SCHEMA = """
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
CREATE TABLE names(key TEXT NOT NULL, article_id INTEGER NOT NULL, is_title INTEGER NOT NULL);
"""


def write_index(out_path, documents, meta):
    """documents: dicts with title, url, passages (texts without the title in
    front) and optionally aliases (list of other names) and popularity.

    meta is stored as text; it should name the source, its date and its licence.
    Returns (articles, passages).
    """
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_path.with_name(out_path.name + ".tmp")
    tmp.unlink(missing_ok=True)
    con = sqlite3.connect(tmp)
    con.executescript("PRAGMA page_size=4096; PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;" + SCHEMA)
    article_id = passage_id = 0
    for document in documents:
        passages = [p for p in document["passages"] if p.strip()]
        if not passages:
            continue
        article_id += 1
        title = document["title"]
        aliases = " | ".join(a for a in document.get("aliases", []) if a)
        con.execute("INSERT INTO articles VALUES (?,?,?,?,?,?,?)",
                    (article_id, article_id, title, document["url"], document.get("popularity", 1e-6),
                     passage_id + 1, len(passages)))
        con.executemany("INSERT INTO names VALUES (?,?,?)",
                        [(key, article_id, is_title) for key, is_title in article_names(title, aliases)])
        for seq, text in enumerate(passages):
            passage_id += 1
            body = f"{title}: {text}"
            con.execute("INSERT INTO passages VALUES (?,?,?,?)",
                        (passage_id, article_id, seq, zlib.compress(body.encode("utf-8"), 9)))
            # Other names are indexed on the opening passage only, as in build_index.py.
            con.execute("INSERT INTO passages_fts(rowid, title, aliases, body) VALUES (?,?,?,?)",
                        (passage_id, title, aliases if seq == 0 else "", index_text(text)))
    con.execute("CREATE INDEX names_key ON names(key)")
    con.execute("INSERT INTO passages_fts(passages_fts) VALUES ('optimize')")
    meta = dict(meta, articles=article_id, passages=passage_id,
                tokenizer="porter unicode61", passage_encoding="zlib(utf-8 '<title>: <text>')",
                fts_body="content words only (function words removed before indexing)")
    con.executemany("INSERT INTO meta VALUES (?,?)", [(k, str(v)) for k, v in meta.items()])
    con.commit()
    con.execute("VACUUM")
    con.close()
    out_path.unlink(missing_ok=True)
    tmp.replace(out_path)
    return article_id, passage_id
