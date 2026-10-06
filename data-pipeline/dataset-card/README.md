---
license: other
license_name: per-file-see-card
license_link: https://huggingface.co/datasets/SHARK787/offline-research-index#licence-and-attribution
language:
- en
pretty_name: Offline research index (English Wikipedia, Wikivoyage and five smaller packs, SQLite FTS5)
size_categories:
- 10M<n<100M
task_categories:
- text-retrieval
- question-answering
tags:
- wikipedia
- wikivoyage
- openstreetmap
- overture-maps
- wikidata
- sqlite
- fts5
- bm25
- rag
- offline
---

# Offline research index: English Wikipedia, Wikivoyage and five smaller packs

SQLite files that an Android app searches with no network connection. Two hold the core, Wikipedia and Wikivoyage; five smaller packs, added later, cover what those two answer badly (see "The smaller packs" below). The text of English Wikipedia (the most-viewed 31% of articles) and all of English Wikivoyage is cut into short passages and stored with a full-text (FTS5, BM25) index, so a phone can find the passages that bear on a question in a second or two.

The app and the script that built these files are at <https://github.com/SharkCube99/offline-research-android>.

The files are data for a retrieval-augmented answering app: the app finds passages here, and a language model on the phone writes an answer that cites them.

## Files

| File | Bytes | Articles | Passages | Source dump |
|---|---|---|---|---|
| `wikipedia.db` | 22,570,754,048 | 2,056,411 | 12,741,441 | `enwiki_content`, 2026-09-27 |
| `wikivoyage.db` | 355,127,296 | 32,072 | 188,396 | `enwikivoyage_content`, 2026-09-27 |
| `cityplaces.db` | 1,613,914,112 | 519,248 lists | 888,169 | Overture Maps places, release 2026-09-23.1 |
| `ethereum.db` | 14,196,736 | | | Ethereum repositories and NIST publications, fetched 2026-10-03 |
| `places.db` | 14,503,936 | 6,102 lists | | OpenStreetMap, fetched 2026-10-06 |
| `travelfacts.db` | 1,204,224 | 197 countries | | Wikidata, fetched 2026-10-05 |
| `firstaid.db` | 331,776 | 52 chapters | | Wikibooks "First Aid", fetched 2026-10-05 |
| `manifest.json` | 1,571 | | | counts, sizes and chunking settings of the Wikipedia and Wikivoyage build |

Total: 24,570,032,128 bytes (24.6 GB) for the seven database files. The licence differs by file; see "Licence and attribution".

## The smaller packs

All five use the format described below. Each marks itself in its `meta` table so that the app uses it only when a question names one of its entries (`match = strict`; `cityplaces.db` uses `match = names` and is never searched by the words inside its lists).

| File | What it holds | Built by |
|---|---|---|
| `cityplaces.db` | 13,990,257 open places from Overture Maps in 24,812 cities, as lists by city and kind ("Pharmacies in Nairobi", "Hostels in Cusco"): places to eat and sleep, pharmacies, hospitals, banks, shops for daily needs, stations, police, embassies, museums. Up to 40 places per list, those Overture is most sure of first, with address | `data-pipeline/fetch_overture_places.py`, `data-pipeline/build_places_overture.py` |
| `ethereum.db` | Ethereum Improvement Proposals, ERCs, consensus specifications, the English pages of ethereum.org, and NIST FIPS 203, 204, 205 and SP 800-208 | `data-pipeline/build_ethereum.py` |
| `places.db` | 50,458 places to eat that OpenStreetMap tags as vegan or vegetarian, as lists by city ("Vegan restaurants in Berlin"); places the map marks as closed are left out, and those checked or edited most recently come first | `data-pipeline/build_places.py` |
| `travelfacts.db` | One entry per country: emergency numbers and what each is for, mains voltage and plug types, driving side, currency, dialling code, capital, official languages, time zones | `data-pipeline/build_travel_facts.py` |
| `firstaid.db` | The Wikibooks "First Aid" book, one entry per chapter, with everyday names as aliases ("choking" for "Obstructed Airway") | `data-pipeline/build_firstaid.py` |

Limits worth knowing: map data goes stale, so a listed place may have closed; the lists are not ranked by quality and carry no opening hours; places with no city of 15,000 people or more within 50 km are left out; the first-aid text is a volunteer-written book, not medical advice.

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

SHA-256, to check a download:

```
cca60646f543deef8b62949d40e004d05fb2fbad7b1332736d5df876a1f1ee2a  wikipedia.db
b4bfa0b61da4588e59cbf9c42726c1d3694c7102cb2d49f75ecabcfb3daadc2b  wikivoyage.db
d3e7229037c8e857dd0d8b62bae847dc43e187fdb23c60898a614670e78fac38  cityplaces.db
c3870a3690b3e1ddc432136feb39a3b74d5caf6ae1c2b00d9ff03d589dc5ec85  ethereum.db
c607b15cc956cdb271f69c8b8f4828d53905a5bc1071bb3c2d27792ab91d8276  places.db
126bbb4a4bfc000d7a2fd1dd2a9522cc1302132fe3f6d9100896872a3b242a8d  travelfacts.db
ae775ced55e0cc9f9e7dcef65f06f771a26268a102abc2e71bd149ec7ebd6eb4  firstaid.db
```

The app's repository has a script that downloads the files, continues an interrupted download and checks these sums: `scripts/fetch_index.sh`.

## How it was built

Wikipedia and Wikivoyage: from the Wikimedia CirrusSearch index dumps at <https://dumps.wikimedia.org/other/cirrus_search_index/> (dump date 20260927), with a script that uses only the Python standard library:

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

The licence differs by file.

| File | Licence | Credit |
|---|---|---|
| `wikipedia.db`, `wikivoyage.db` | CC BY-SA 4.0 | Contributors to English Wikipedia and English Wikivoyage |
| `firstaid.db` | CC BY-SA 4.0 | Contributors to the Wikibooks "First Aid" book |
| `places.db` | Open Database Licence (ODbL) 1.0 | (c) OpenStreetMap contributors, <https://www.openstreetmap.org/copyright>; city names from GeoNames (CC BY 4.0) |
| `cityplaces.db` | CDLA-Permissive-2.0 | Overture Maps Foundation and its data contributors, <https://overturemaps.org>; city names from GeoNames (CC BY 4.0) |
| `travelfacts.db` | CC0 1.0 | Wikidata contributors |
| `ethereum.db` | CC0 1.0 (proposals and specifications), MIT (ethereum.org pages), US public domain (NIST publications) | Ethereum contributors; ethereum.org contributors; National Institute of Standards and Technology |

`places.db` is a database derived from OpenStreetMap and is offered under the ODbL: if you change it and distribute the result, that result must be under the ODbL too.

### Wikipedia, Wikivoyage and Wikibooks text

The text of `wikipedia.db` and `wikivoyage.db` comes from **English Wikipedia** and **English Wikivoyage** and was written by their contributors. It is available under the [Creative Commons Attribution-ShareAlike 4.0 International licence](https://creativecommons.org/licenses/by-sa/4.0/) (Wikipedia text is also available under the GFDL). These index files are an adaptation of that text and are released under the same licence, CC BY-SA 4.0.

- **Attribution.** Every passage is stored with the title and URL of the article it came from; the URL leads to the article and its edit history, which lists its authors. Anything built on these files should show that title and link wherever it shows or uses a passage.
- **Changes made.** Whitespace normalised; articles split into passages; article title placed in front of each passage; function words left out of the search index (the stored text is complete).
- **Share alike.** If you change these files or build on them and distribute the result, it must carry the same licence.

Wikipedia and Wikivoyage are trademarks of the Wikimedia Foundation. This dataset is not produced or endorsed by the Wikimedia Foundation.
