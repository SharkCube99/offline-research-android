# Retrieval-augmented answering (M3)

How a question becomes a cited answer on the phone, and what happened when it ran on the Redmi 12 5G. Every number here comes from the logs in `docs/measurements/`.

## Pipeline

```
question
  -> planner (Qwen3-1.7B)        up to 3 article names to look up
  -> retriever (SQLite FTS5)     top 20 passages per query, across both corpora
  -> context budgeter            best passages within the profile's token budget
  -> answerer (Qwen3-4B on the Redmi)   streamed answer with [n] citations
```

| Step | Code | What it does |
|---|---|---|
| Planner | `rag/Planner.kt` | Asks the small model for 1 to 3 encyclopedia article titles. Unusable output (empty, prose, unfinished reasoning) or an engine error falls back to searching with the question alone. With no planner model on the phone, search uses the question's keywords only |
| Retriever | `rag/Retriever.kt` | Kotlin port of `data-pipeline/retrieval.py`: title and redirect lookup, plus BM25 requiring every content word and relaxing one word at a time. The question itself is always searched; planner queries are searched too, and the result lists take turns |
| Budgeter | `rag/ContextBudgeter.kt` | Takes passages best-first until the token budget is used, counted with the answerer's own tokenizer. No passage twice, at most two per article, passages that do not fit are skipped rather than cut |
| Prompt | `rag/PromptBuilder.kt` | The answer contract: facts only from the numbered sources, cite as `[1]`, general reasoning allowed to connect facts, and the exact reply `Not covered by the offline sources.` when the sources do not answer |
| Citations | `rag/Citations.kt` | Splits the answer into text and citations. A number with no matching source is dropped from the display and logged |
| Orchestration | `rag/RagPipeline.kt` | Runs the steps and reports stages to the UI. If nothing is retrieved it replies "not covered" without running the model |

The pipeline talks to models through `InferenceEngine` and to the index through `SqlDatabase`, so neither llama.cpp nor SQLite appears in it.

## What the user sees

- The answer streams in. Citations such as `[1]` are links.
- A "Sources (n)" button under each answer opens a panel listing each source's title, corpus, URL and passage text.
- While waiting: "Planning searches…", "Searching offline sources…", then "Thinking deeper… reading n sources" until the first word arrives.

Screenshots from the airplane-mode run are in `docs/measurements/screens/`.

## Tests

`./gradlew testDebugUnitTest`: 62 tests, 61 run by default.

| Area | What is checked |
|---|---|
| Retrieval | The retriever's SQL runs against a real FTS5 index (built in the test through JDBC): redirect lookup, plural handling, per-article cap, all-words-then-relax, two corpora, hostile query text |
| Query handling | Tokenising, stop words, query building and name n-grams match vectors written by the Python reference (`data-pipeline/eval/query_vectors.json`); the Python tests check the same file |
| Budgeting | Rank order, budget limit, skipping, de-duplication, per-source cap, merging several result lists |
| Citations | `[1]`, `[1][2]`, `[1, 2]`, invented numbers, other bracketed text, unfinished markers while streaming, the not-covered reply |
| Planner | Parsing of numbered, bulleted and quoted output, reasoning blocks, prose, and the three fallbacks |
| Pipeline | Stage order, the prompt the answerer receives, not-covered without a model call, planner queries being searched |

The 62nd test, `RetrieverParityTest`, runs only when pointed at a real index. Run on the full 22.9 GB index on 2026-10-01, the Kotlin retriever returned the same top 5 as the Python reference for all 50 test questions and all 25 held-out questions.

## On the Redmi 12 5G, airplane mode on

Source: `docs/measurements/2026-10-02-23076RN4BI-m3-airplane.jsonl`. Five questions asked over adb (`am start --es ask`), full 22.9 GB index, Qwen3-4B Q4_K_M answerer, Qwen3-1.7B Q8_0 planner, profile `low` (800-token passage budget, 384-token answers), app 0.3.0-m3. Airplane mode was read as on before and after every question.

| Question | Planner's queries | Sources chosen | Outcome |
|---|---|---|---|
| What are the symptoms of dehydration? | Dehydration; Symptoms of dehydration | Dehydration (2 passages) | Correct list of symptoms, cited [1] |
| How does a refrigerator work? | Refrigeration; Thermodynamics; Food preservation | Refrigerator, Refrigeration | Sound explanation, cited [1] |
| What is the best way to get from Charles de Gaulle airport to central Paris? | Charles de Gaulle Airport (CDG) to Paris; Paris Metro; Paris transport | Paris Charles de Gaulle Airport, Paris Metro | Names the RER as the way in, cited [2], but with errors (below) |
| What is the wifi password at my hotel? | WiFi password; Hotel network | Password, Wi-Fi hotspot | Says the sources do not contain it and that it "is not covered by the offline sources"; adds a cited remark about hotspots |
| What was the closing price of Apple stock yesterday? | Apple Inc.; Apple stock | Open-high-low-close chart, Stock | Exactly "Not covered by the offline sources." |

Citations: every number cited in the five answers referred to a real source. No invented citation numbers appeared in any of the 12 answers logged during M3.

Timing, median of the five:

| Measure | Value |
|---|---|
| Planner | 13.5 s |
| Search (2 to 4 queries, both corpora) | 6.0 s |
| Prompt size | 955 tokens |
| Prompt processing speed | 6.2 tokens per second |
| First word of the answer after | 174 s |
| Answer speed | 1.83 tokens per second |

### Where it falls short

- **It is slow.** About three minutes to the first word and four to five for a full answer. Prompt processing dominates. During a run, the answerer's four threads were spread over two fast and two slow cores and kept migrating, and the cores were running below their maximum frequency. Thread pinning and prefix caching are M5 work and were not attempted.
- **The 4B model makes factual slips inside cited answers.** In the Paris answer it expanded RER as "Réseau Express Metropole" (it is Réseau Express Régional), said the airport is served by RER "B or D" (only B) and that it is "served by the Paris Metro" (it is not). A citation shows where a claim should come from; it does not guarantee the model copied it correctly.
- **It does not always stay inside the sources.** An earlier fire-starting answer suggested a lighter and rubbing sticks together, neither of which was in its sources.
- **The not-covered reply is not always the exact sentence.** The wifi answer paraphrased it and padded it with a loosely related fact.
- **Wikivoyage was outranked for a travel question.** The Paris question got two Wikipedia sources, though Wikivoyage's Paris article has a practical "get in" section. Only two or three passages fit in 800 tokens, so the order of the merged list matters a great deal.
- **Junk sources get through.** Planner queries resolved to "Ignition (film)" and "Beijing North Star" in earlier runs.

### Planner prompt

The first planner prompt asked for "search queries"; the 1.7B model paraphrased the question ("fire without matches"). Asking for article titles and showing two examples changed its output to names such as "Fire-starting" and "Celestial navigation", which the name lookup resolves. Two questions that failed retrieval in M2 then found an expected article ("Fire making", "Celestial navigation"). That is two questions, not an evaluation.

## Not verified

- **Tapping a citation and the Sources panel.** The links and the button are visible in the screenshots, but this phone refuses simulated taps over adb, so opening the panel has not been exercised on the device. The panel's content logic is covered by the pipeline tests; the Compose sheet itself is not.
- **Answer quality at scale.** 12 answers were read by hand. The 100-question benchmark is M6.
- **The 1,000-token budget** of the high profile, and the 30B model, on any 12 GB phone (M4).
- **Search time with a cold file cache.** Searches took 2 to 15 s; the slowest were the first after the 22.9 GB file was pushed.
