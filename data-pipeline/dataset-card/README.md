---
license: cc-by-sa-4.0
language:
- en
pretty_name: Offline research index (English Wikipedia and Wikivoyage, SQLite FTS5)
size_categories:
- 10M<n<100M
task_categories:
- text-retrieval
- question-answering
tags:
- wikipedia
- wikivoyage
- sqlite
- fts5
- bm25
- rag
- offline
---

# Offline research index: English Wikipedia and Wikivoyage

Two SQLite files that an Android app searches with no network connection. The text of English Wikipedia (the most-viewed 31% of articles) and all of English Wikivoyage is cut into short passages and stored with a full-text (FTS5, BM25) index, so a phone can find the passages that bear on a question in a second or two.

The files are data for a retrieval-augmented answering app: the app finds passages here, and a language model on the phone writes an answer that cites them.

## Files

| File | Bytes | Articles | Passages | Source dump |
|---|---|---|---|---|
| `wikipedia.db` | 22,570,754,048 | 2,056,411 | 12,741,441 | `enwiki_content`, 2026-09-27 |
| `wikivoyage.db` | 355,127,296 | 32,072 | 188,396 | `enwikivoyage_content`, 2026-09-27 |
| `manifest.json` | 1,571 | | | counts, sizes and chunking settings of this build |

Total: 22,925,881,344 bytes (22.9 GB).

## What is in it, and what is not

- **Wikipedia is not complete.** English Wikipedia had about 6.6 million articles in this dump after redirects, disambiguation pages and articles under 50 words were removed. To fit a phone, the build kept the most-viewed articles up to a 24 GB budget: 2,056,411 articles, about 31%. The least-viewed 69% are absent, so obscure subjects are likely to be missing.
- **Wikivoyage is complete.**
- **Text only.** No images, tables, infoboxes or citations. Some remains of "See also" and "External links" sections are present in the passage text.
- **A snapshot.** The content is as it stood in the Wikimedia dump of 27 September 2026 and is not updated.
- **Only article text was changed in form, not in wording.** Whitespace was normalised and articles were split into passages of about 250 words (300 at most), each stored with its article's title in front.

## Format

Each file is an ordinary SQLite database. Tables:

| Table | Columns | Notes |
|---|---|---|
| `articles` | `id, page_id, title, url, popularity, first_passage_id, passage_count` | `url` is the article's address on Wikipedia or Wikivoyage; `popularity` is the page-view score from the dump |
| `passages` | `id, article_id, seq, body` | `body` is zlib-compressed UTF-8, "Title: passage text"; `seq` 0 is the article's opening |
| `passages_fts` | `title, aliases, body` | FTS5, contentless, tokenizer `porter unicode61`; `rowid` equals `passages.id`; common function words are left out of the indexed body |
| `names` | `key, article_id, is_title` | lower-cased titles and redirect titles, for finding an article by name |
| `meta` | key and value | source, dump date and licence of the file |

Reading a passage in Python:

```python
import sqlite3, zlib

db = sqlite3.connect("wikipedia.db")
rows = db.execute(
    "SELECT rowid FROM passages_fts WHERE passages_fts MATCH ? "
    "AND rank MATCH 'bm25(4.0, 3.0, 1.0)' ORDER BY rank LIMIT 5",
    ('"tides" AND "moon"',),
).fetchall()
for (passage_id,) in rows:
    title, url, body = db.execute(
        "SELECT a.title, a.url, p.body FROM passages p "
        "JOIN articles a ON a.id = p.article_id WHERE p.id = ?",
        (passage_id,),
    ).fetchone()
    print(title, url)
    print(zlib.decompress(body).decode("utf-8")[:300])
```

SQLite must be built with FTS5, which the Python distributions from python.org are.

## Download

```bash
hf download SHARK787/offline-research-index --repo-type dataset --local-dir index
```

## How it was built

From the Wikimedia CirrusSearch index dumps at <https://dumps.wikimedia.org/other/cirrus_search_index/> (dump date 20260927), with a script that uses only the Python standard library:

```bash
python data-pipeline/build_index.py --budget-gb 24 --workers 8
```

The script downloads the dumps, removes redirects, disambiguation pages and articles under 50 words, cleans and splits the text, and writes these files. The same command rebuilds them from the same dump.

## How well search finds the right article

Measured with the app's search method (a lookup of article names found in the question, combined with BM25 over passages) on questions written for the project:

| Question set | Questions | A relevant passage in the top 5 |
|---|---|---|
| Test set (also used while tuning the search) | 50 | 46 (92%) |
| Held-out set | 25 | 24 (96%) |

The questions are about well-known topics. The figures say little about obscure subjects, which are the ones the 31% cut removed.

## Licence and attribution

The text comes from **English Wikipedia** and **English Wikivoyage** and was written by their contributors. It is available under the [Creative Commons Attribution-ShareAlike 4.0 International licence](https://creativecommons.org/licenses/by-sa/4.0/) (Wikipedia text is also available under the GFDL). These index files are an adaptation of that text and are released under the same licence, CC BY-SA 4.0.

- **Attribution.** Every passage is stored with the title and URL of the article it came from; the URL leads to the article and its edit history, which lists its authors. Anything built on these files should show that title and link wherever it shows or uses a passage.
- **Changes made.** Whitespace normalised; articles split into passages; article title placed in front of each passage; function words left out of the search index (the stored text is complete).
- **Share alike.** If you change these files or build on them and distribute the result, it must carry the same licence.

Wikipedia and Wikivoyage are trademarks of the Wikimedia Foundation. This dataset is not produced or endorsed by the Wikimedia Foundation.
