# data-pipeline

Builds the offline knowledge index the app searches: one SQLite file per corpus, each with an FTS5 (BM25) index.

Needs Python 3.9 or newer. Only the standard library is used.

## Build

```bash
python data-pipeline/build_index.py
```

That one command downloads the dumps, cleans and chunks the text, builds the indexes in `data-pipeline/work/index/` and prints the size report. Every step is resumable: rerun the same command after an interruption.

The full English Wikipedia download is about 42 GB and the build needs roughly 100 GB of free disk while it runs. On the build PC it took about 4.5 hours to download, 2.5 hours to stage and 45 minutes to merge. On Windows the script asks the system to stay awake until it finishes; on macOS or Linux run it under `caffeinate` or `systemd-inhibit`.

The index described in `docs/KNOWLEDGE_INDEX.md` was built with:

```bash
python data-pipeline/build_index.py --budget-gb 24 --workers 8
```

Useful options:

| Option | Effect |
|---|---|
| `--limit N` | Keep at most N articles per corpus and download only the shards needed. For quick tests |
| `--corpus NAME` | Build only `wikipedia` or `wikivoyage` (repeatable) |
| `--budget-gb X` | Keep the most-viewed articles that fit in about X GB per corpus |
| `--max-articles N` | Keep the N most-viewed articles per corpus |
| `--dump-date YYYYMMDD` | Use a specific dump instead of the newest complete one |
| `--workers N` | Parallel processes for the cleaning and chunking step |

A small test index:

```bash
python data-pipeline/build_index.py --limit 2000
```

Note that `--limit` takes whichever articles come first in the dump, which is an arbitrary sample. It is for testing the pipeline, not for judging search quality.

## Source

Wikimedia's CirrusSearch index dumps, <https://dumps.wikimedia.org/other/cirrus_search_index/>. They are published weekly as bzip2-compressed JSON, one document per page, with the article text already converted from wiki markup to plain text. Each document also carries the page's redirects and a page-view popularity score, both of which the pipeline uses.

Wikimedia keeps only the last few weekly dumps. A build is reproducible for as long as its dump is online; the dump date used is recorded in `manifest.json` and in each index file. The older `other/cirrussearch/` location is deprecated and no longer updated.

Licences: Wikipedia and Wikivoyage text is CC BY-SA 4.0. See `LICENSES.md`.

## What the pipeline does

1. **Download** every shard of the dump (resumable, size-checked).
2. **Stage** each shard in a worker process:
   - skip redirects, disambiguation pages, pages outside the article namespace and articles under 50 words;
   - normalise Unicode and whitespace;
   - split the text into passages of about 250 words (at most 300) that end on sentence boundaries;
   - prefix each passage with its article title and compress it with zlib.
3. **Merge** the staged shards, in shard order, into `<corpus>.db`. With `--budget-gb` or `--max-articles`, only the most-viewed articles are kept.
4. **Report** sizes per corpus and in total, and write `manifest.json`.

## Index format

Each `<corpus>.db` contains:

| Table | Columns |
|---|---|
| `articles` | `id`, `page_id`, `title`, `url`, `popularity`, `first_passage_id`, `passage_count` |
| `passages` | `id` (the passage ID), `article_id`, `seq`, `body` |
| `passages_fts` | FTS5 table over `title`, `aliases`, `body`; its `rowid` is the passage ID |
| `names` | `key`, `article_id`, `is_title`: normalised article titles and redirect titles, indexed by `key` |
| `meta` | source URL, dump date, licence, chunking settings |

- `passages.body` is zlib-compressed UTF-8 text of the form `Title: passage text`.
- `passages_fts` is contentless and uses the `porter unicode61` tokenizer. The indexed body has function words ("the", "of", ...) removed, which makes the index smaller; queries drop the same words.
- `aliases` holds the titles of redirects to the article ("Heart attack" for "Myocardial infarction"), on the article's first passage only.
- A passage is identified across corpora as `<corpus>:<id>`.

## Search

`retrieval.py` is the reference implementation of query handling; the Android app must do the same thing.

The question is lower-cased and tokenised, and function words and question filler are dropped. Two channels then run, and their results are interleaved:

- **Name channel.** Word n-grams of the question (up to five words) are looked up in `names`. "heart attack" finds the article "Myocardial infarction". For each article found, its passages that best match the whole question are taken.
- **Passage channel.** `bm25(passages_fts, 4.0, 3.0, 1.0)` (title, aliases, body weights) over all passages, requiring every remaining word. If that finds fewer than 20 passages, one word at a time is dropped.

At most two passages per article are kept.

## Evaluate

```bash
python data-pipeline/eval_retrieval.py
```

Runs the 50 questions in `eval/queries.jsonl` and reports how many have a passage from an expected article in the top 5. It lists every miss, and separates ranking misses from coverage misses (the expected article is not in the index at all). It exits non-zero below 80%.

`eval/queries_holdout.jsonl` holds 25 more questions that were not used for tuning. Do not tune against them:

```bash
python data-pipeline/eval_retrieval.py --queries data-pipeline/eval/queries_holdout.jsonl
```

Results are in `docs/KNOWLEDGE_INDEX.md`.

## Other commands

```bash
python data-pipeline/size_report.py                      # size table for an existing index
python -m unittest discover -s data-pipeline/tests       # unit tests
```
