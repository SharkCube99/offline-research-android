# Knowledge index (M2)

What the offline index contains, how big it is, and how well search finds the right article. Every number here comes from a build or evaluation run on 2026-10-01; the raw outputs are in `docs/measurements/`.

How to build it and the file format: `data-pipeline/README.md`.

## Size

From `data-pipeline/size_report.py` on the finished index (also in `docs/measurements/2026-10-01-index-manifest.json`):

| Corpus | Dump | Articles | Passages | Stored text (GB) | Index file (GB) |
|---|---|---|---|---|---|
| wikipedia | 20260927 | 2,056,411 | 12,741,441 | 10.444 | 22.571 |
| wikivoyage | 20260927 | 32,072 | 188,396 | 0.160 | 0.355 |
| **Total** | | 2,088,483 | 12,929,837 | 10.605 | **22.926** |

GB = 1,000,000,000 bytes.

Against the 50 GB limit for the whole install:

| Item | GB | Status |
|---|---|---|
| Knowledge index | 22.93 | measured |
| Qwen3-30B-A3B Q4_K_M | 18.6 | size listed on Hugging Face; not downloaded yet |
| App | 0.03 | measured (debug APK, 31 MB) |
| **Subtotal** | **41.6** | |
| Planner model, further corpora | up to about 8 | not chosen or measured yet |

## What is and is not in the Wikipedia index

English Wikipedia has about 6.6 million articles after redirects, disambiguation pages and articles under 50 words are removed (66 dump shards of about 99,700 articles each). The whole of it would not fit: a 50,000-article sample came to 0.284 GB, which scales to roughly 36 GB.

The build therefore ran with `--budget-gb 24`, which keeps the most-viewed articles until the budget is reached. That kept **2,056,411 articles, about 31%**, with a page-view popularity score of at least 4.39e-08. The least-viewed 69% are not in the index.

None of the 75 evaluation questions lost its expected article to this cut. That is weak evidence, because the questions are about well-known topics; obscure subjects are the ones that were cut.

Wikivoyage is complete.

## Search quality

`data-pipeline/eval_retrieval.py` asks each question and checks whether any of the top 5 passages comes from one of the question's expected articles.

| Question set | Questions | In top 5 | In top 3 | First result | Rate (top 5) |
|---|---|---|---|---|---|
| Test set (`eval/queries.jsonl`) | 50 | 45 | 43 | 38 | **90%** |
| Held-out set (`eval/queries_holdout.jsonl`) | 25 | 24 | 23 | 19 | **96%** |

The acceptance bar is 80% on the 50-question test set.

By category, test set: travel 12/12, health 10/10, compare 6/6, explain 9/10, multi-step 3/4, how-to 5/8.

Search time on the build PC, per question, both corpora: median 0.58 s and maximum 7.2 s on the test set; median 0.26 s and maximum 3.2 s on the held-out set. These are not phone measurements. Search time on a phone is unmeasured and is a risk for M3.

### How to read these numbers

- **The test set was used for tuning.** The search method below was designed by looking at which of the 50 questions failed, so 90% is optimistic for that set. The held-out set was written before the final code ran and was run once, with no changes afterwards; it is the fairer figure, but it is only 25 questions.
- **Both sets were written by the same author as the search code**, about well-known topics, in plain phrasing. Real questions will be harder.
- **"Relevant" means "from an expected article"**, not "contains the answer". A passage from the right article can still be the wrong part of it.
- **One label was corrected.** Question 6 expected the Wikivoyage article "United States of America"; that article is now titled "United States", and its tipping section was already being returned. With the old label the test-set result was 44/50 (88%).

### What failed

Test set:

| # | Question | What came back instead |
|---|---|---|
| 24 | How can I start a fire without matches? | "Fire", "Match", King's Cross fire. Expected "Fire making" |
| 29 | How do I change a car tire? | "Tire", "Tire maintenance", "Car". Expected "Flat tire" or "Spare tire" |
| 30 | How do you find north using the stars? | "North", "Star". Expected "Polaris" or "Celestial navigation" |
| 32 | Why is the sky blue? | "Sky blue" (the colour). Expected "Rayleigh scattering" |
| 48 | Who was the first person to walk on the Moon and when did it happen? | "Moon", "Walking". Expected "Neil Armstrong" or "Apollo 11" |

Held-out set: question 115, "How do I signal for help in the wilderness?", returned "Signal for Help" (a hand gesture) and "Wilderness" instead of "Distress signal".

The pattern: the answer lives in an article whose title shares no words with the question, and the question's own words are common enough to match many other things. How-to questions are the weakest category. In M3 the planner model rewrites a question into search queries ("start a fire without matches" into "fire making"), which is aimed at exactly this.

## How search works

`data-pipeline/retrieval.py` is the reference implementation. Two channels, interleaved:

1. **Name channel.** Word n-grams of the question are looked up in a table of article titles and redirect titles. "heart attack" finds "Myocardial infarction", "north star" finds "Polaris", "vaccines" finds "Vaccine". The passages of those articles that best match the whole question are taken.
2. **Passage channel.** BM25 over all passages, requiring every content word of the question; if that finds too few, one word at a time is dropped.

At most two passages per article are returned.

### Tuning history

All on the full index, 50-question test set:

| Method | Top-5 hits | Median search time (PC) |
|---|---|---|
| Any question word matches (OR), BM25 | 35 (70%) | 1.32 s |
| OR with a heavier title weight | 36 (72%) | 1.33 s |
| All words required (AND), falling back to OR | 36 (72%) | 0.26 s |
| Name channel + AND passage channel (prototype) | 45 (90%) | 0.26 s |
| Final code in `retrieval.py`, before the label fix | 44 (88%) | 0.38 s |
| Final code, after the label fix | 45 (90%) | 0.58 s |

The search-time column was measured with other work running on the same machine at times, so small differences between rows are not meaningful.

Plain BM25 failed mostly by burying the main article: "How do vaccines work?" returned "Polio vaccine" and "Vaccine hesitancy" but not "Vaccine". Changing chunk size was not tried, because the failures were about which article was found, not which part of it.

Rejected along the way: SQLite FTS5's smaller `detail=column` and `detail=none` index modes. They cut the index to 55% and 27% of its size on a test build, but BM25 then returned passages in arbitrary order.

## Known limits

- **Redirect lists are cut at 300 characters per article.** Heavily redirected articles lose some of their alternative names, which weakens the name channel. Lifting the cap means re-staging all shards (about 2.5 hours on the build PC).
- **Android compatibility is unverified.** The index needs SQLite with FTS5. Android's built-in SQLite does not guarantee FTS5 on every version. M3 has to check this on the Redmi and bundle its own SQLite if needed.
- **Dumps expire.** Wikimedia keeps only a few weekly dumps, so this exact build can be reproduced only while the 20260927 dump is online.
- **The Wikipedia `names` table was added to the already-built file** by a one-off script that calls the same function the build uses, to avoid a third 45-minute merge. A clean build creates the table itself; this was checked by rebuilding Wikivoyage, which produced an identical table.

## Build record

- Download: 66 shards, 41.77 GB. Partly from `dumps.wikimedia.org` and partly from the ACC Umeå mirror (`mirror.accum.se`) after Wikimedia's server slowed to about 0.5 MB/s. File sizes match Wikimedia's listing and all 66 files decompressed without error.
- Staging: 8 worker processes, roughly 2.5 hours in total.
- Merge: 38 minutes, plus about 5 minutes for optimisation.
- The build PC crashed and rebooted twice during the run (16:47 and 19:22). Completed downloads and staged shards survived; the merge was redone. `build_index.py` now asks Windows to stay awake while it runs.
