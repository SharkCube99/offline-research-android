# Architecture

This file records how the app is built and every default chosen without asking, with the reason. It covers Milestone 1 only; later milestones add to it.

## What exists after M1

```
ChatScreen (Compose) -> ChatViewModel -> InferenceEngine (interface)
                                              |
                                         LlamaEngine (Kotlin, one dedicated thread)
                                              |
                                         LlamaBridge (JNI) -> llama_bridge.cpp -> llama.cpp
```

- `engine/InferenceEngine.kt` is the only surface the rest of the app sees: `load`, `generate` (a `Flow` of text fragments), `cancel`, `metrics`, `unload`. The model, its quantisation and any caching can change behind it without touching UI or retrieval code.
- `engine/LlamaEngine.kt` runs every native call on one dedicated thread, so calls never overlap and never block a shared dispatcher.
- `app/src/main/cpp/llama_bridge.cpp` holds one model and one context. It uses only the public C API in `llama.h`.
- `profiles/ModelProfile.kt` reads the active profile. `ProfileStore` looks for a `profile.json` pushed over adb and falls back to the bundled `low.json`.
- `engine/MetricsLog.kt` writes one JSON line per answer to logcat and to `logs/metrics.jsonl` on the device.

## Facts checked against current sources (2026-10-01)

| Fact | Finding | Source |
|---|---|---|
| llama.cpp version | Pinned to tag `b11311`, commit `f7b384c1` (2026-09-30) | `git ls-remote --tags` on the upstream repo |
| Android cross-compile flags | `GGML_NATIVE=OFF`, `GGML_OPENMP=OFF`, `GGML_LLAMAFILE=OFF`, `LLAMA_OPENSSL=OFF`, platform `android-28`, ABI `arm64-v8a` | `third_party/llama.cpp/docs/android.md` |
| Runtime CPU detection | `BUILD_SHARED_LIBS=ON`, `GGML_BACKEND_DL=ON`, `GGML_CPU_ALL_VARIANTS=ON`; NDK `29.0.13113456`, CMake `3.31.6`; native libraries extracted to disk | `third_party/llama.cpp/examples/llama.android/lib/build.gradle.kts` and its manifest |
| ARM variants built for Android | armv8.0, armv8.2 (dotprod, then +fp16), armv8.6 (+i8mm), armv9.0 (+SVE2), armv9.2 (+SME) | `third_party/llama.cpp/ggml/src/CMakeLists.txt` |
| mmap and mlock | `llama_model_params` no longer has `use_mmap` / `use_mlock`. It has `load_mode` (`LLAMA_LOAD_MODE_MMAP`, `_MLOCK`, `_MMAP_MLOCK`, `_NONE`, `_DIRECT_IO`) | `third_party/llama.cpp/include/llama.h` |
| Lazy tensor loading | `llama_model_params.lazy_mode` reads marked tensors on demand and requires mmap. Not used in M1; relevant to streaming the 30B model in M4 | `third_party/llama.cpp/include/llama.h` |
| Qwen3-4B file | `Qwen3-4B-Q4_K_M.gguf`, 2.5 GB, Apache-2.0. The official repository has no Q4_0 file | <https://huggingface.co/Qwen/Qwen3-4B-GGUF> |
| Qwen3-30B-A3B file | `Qwen3-30B-A3B-Q4_K_M.gguf`, 18.6 GB, Apache-2.0 | <https://huggingface.co/Qwen/Qwen3-30B-A3B-GGUF> |
| Qwen3 sampling, non-thinking | temperature 0.7, top_p 0.8, top_k 20, presence penalty 1.5 for quantised models | Qwen3-4B-GGUF model card |
| Qwen3 thinking switch | `/no_think` in a system or user message turns reasoning off | Qwen3-4B-GGUF model card |

## Checked on the Redmi 12 5G (2026-10-01)

| Question | Answer |
|---|---|
| CPU features on the Snapdragon 4 Gen 2 (SM4450) | NEON, ARM_FMA, FP16_VA, DOTPROD. No i8mm, no SVE |
| Can `adb push` write into `/sdcard/Android/data/app.offlineresearch/files` on Android 15? | Yes. The `--internal` fallback was not needed |
| Does Qwen3-4B Q4_K_M with `n_ctx` 4096 load on 8 GB? | Yes, but most of the app's memory went to swap. See `docs/PERFORMANCE.md` |
| Does turning off weight repacking remove the second copy of the weights? | Yes: about 2.2 GB more memory available and no measurable speed loss at 4 threads |
| 2 or 4 threads? | No clear winner in a small sample; left at the default |

## Still unverified

| Question | How it gets answered |
|---|---|
| Why prompt processing is only about 5 to 8 tokens per second on the Redmi | Open. Not swap (see `docs/PERFORMANCE.md`). Longer prompts and other batch sizes have not been tried |
| `setup.sh --internal` | Never run on a device |

## Knowledge index (M2)

Design, sizes and search results are in `docs/KNOWLEDGE_INDEX.md`. The decisions:

| Decision | Choice | Reason |
|---|---|---|
| Source | Wikimedia CirrusSearch index dumps (`other/cirrus_search_index/`) | The text is already plain text, and each page carries its redirects and a page-view score. Wikitext dumps would need a markup parser; Kiwix ZIM files would need an HTML parser and a native library |
| Pipeline dependencies | Python standard library only | Nothing to install or license |
| One index file per corpus | `wikipedia.db`, `wikivoyage.db` | Exact per-corpus sizes, corpora can be rebuilt or left out independently |
| Passage size | About 250 words, at most 300, ending on sentence boundaries | Inside the 200 to 300 words CLAUDE.md asks for |
| Passage storage | zlib-compressed, title-prefixed text | About 60% smaller than plain text; Android can inflate it with `java.util.zip` |
| FTS index | Contentless FTS5, `porter unicode61`, function words removed from the indexed body | Contentless avoids storing the text twice. Removing function words cut the index about 10% on a test build. `remove_diacritics 2` was avoided because older Android SQLite versions do not accept it |
| FTS detail level | Full | `detail=column` and `detail=none` are much smaller but break BM25 ranking (measured) |
| Trimming Wikipedia | Keep the most-viewed articles up to `--budget-gb 24` | All of Wikipedia is an estimated 36 GB in this format and does not fit beside the 18.6 GB model |
| Finding articles by name | A `names` table of titles and redirects, looked up with n-grams of the question | Plain BM25 buried main articles under more specific ones; this raised the test-set hit rate from 72% to 90% |

Open for M3: whether Android's built-in SQLite has FTS5 on the Redmi, and how long a search takes on a phone.

## Retrieval-augmented answering (M3)

The pipeline, its tests and the on-device results are in `docs/RAG.md`. The decisions:

| Decision | Choice | Reason |
|---|---|---|
| SQLite on the phone | Bundle requery sqlite-android 3.49.0 (SQLite with FTS5), from JitPack | Android's own SQLite is not guaranteed to have FTS5 and differs between releases. 3.50.4 is named in the library's README but is not published |
| Retriever testability | The retriever uses a four-method `SqlDatabase` interface; the app backs it with the bundled SQLite, tests with JDBC | The real SQL runs against a real FTS5 index on the build machine |
| Keeping Kotlin and Python in step | Shared test vectors, plus a parity test against the real index | The index was built by the Python code; the two must tokenise and query identically |
| Planner model | Qwen3-1.7B Q8_0 (1.83 GB) | The only file in the official repository; there is no Q4 build |
| Planner output | Article titles, not search phrases, with two examples in the prompt | The small model paraphrased the question until shown examples |
| Planner fallback | Search with the question alone | The retriever already extracts keywords. Also used when no planner model is on the phone |
| Which queries are searched | The question, then each planner query; result lists take turns | The question-only search is what M2 evaluated; the planner adds to it and cannot take it away |
| Token counting | The answerer's own tokenizer, through the engine | A word-count estimate would be wrong by a model-dependent factor |
| Per-source limit | Two passages per article, in the retriever and again in the budgeter | Several queries can each return two |
| No sources | Reply "Not covered by the offline sources." without running the model | Nothing to ground an answer on, and it saves minutes |
| Invented citation numbers | Dropped from the display, recorded in the log | A link to a source that does not exist is worse than no link |
| Two models loaded at once | The JNI bridge keeps one session per model, addressed by handle | The planner stays resident, as CLAUDE.md asks |
| Session handles | Any value other than the two error codes is a handle | Android tags the top byte of native pointers, so a valid handle can be negative (found on the Redmi) |
| Low-tier budget | 800 passage tokens and 384 answer tokens in `low.json`; `high.json` keeps 1,000 and 512 | On the Redmi a 1,096-token prompt took 188 s to process |
| Prompt wording per model | `prompt_suffix` in the profile ("/no_think"); the contract itself is in code | The contract is product behaviour; the suffix is a model quirk |

Open for M4 and M5: thread pinning to the fast cores, prefix caching of the fixed part of the prompt, and whether Wikivoyage should be favoured for travel questions.

## Speed work (ahead of M4)

Measurements are in `docs/PERFORMANCE.md`. The decisions:

| Decision | Choice | Reason |
|---|---|---|
| Thread placement | Profile field `thread_affinity`: "none", or "fastest" to pin threads to the cores with the highest reported capacity | Unpinned threads wandered across fast and slow cores. Pinned to the two fast cores, generation was about twice as fast on the Redmi |
| Separate thread counts | `n_threads` for generating, `n_threads_batch` for processing prompts | Generation was fastest on the two fast cores only; prompt processing was a little faster with all eight |
| How pinning is done | Two ggml thread pools with CPU masks, attached to the context; the CPU backend's pool functions are looked up by name | The CPU backend is a separately loaded library, so its functions cannot be linked directly |
| Prefix caching | The bridge remembers the tokens in the context and reprocesses only what differs from the previous prompt | The system prompt is identical for every question. Saves most of the planner's prompt and 18% of the answerer's |
| Device-specific values | Only `low.json` uses pinning, with values measured on the Redmi | Core layouts differ between phones; `high.json` waits for measurements on a 12 GB device |

## Shorter sources and an instant preview (2026-10-03)

Reading the prompt is most of the wait for an answer, so the prompt was made shorter and the wait was given something to show.

- **Sentence-level compression** (`rag/SourceCompressor.kt`). Instead of two or three whole passages, the answerer gets the sentences that bear on the question, taken from up to six passages (two per article), within `retrieval_budget_tokens` (now 450 in both profiles; it was 800 and 1000). Sentences are copied, never rewritten, so a citation still points at text that is in the source; gaps are marked with "…". The sources panel shows the excerpt and then the full passage.
- **How a sentence is scored.** Question words the article's title already covers pick the article; the other words pick the sentence. An article's opening sentence, better-ranked passages and shorter sentences are preferred. Leftover budget goes to the sentence after a chosen one. Wikipedia editing notes (`[citation needed]`), run-on lists over 400 characters and reference-list fragments are skipped.
- **Token budget by estimate.** Sentences are sized at four characters per token, not with the tokenizer, so compression does not depend on the inference engine. The true prompt size is still logged per answer (`prompt_tokens`), together with `source_chars` and `source_chars_full`.
- **Instant preview.** The pipeline now searches with the question itself before the planner runs and emits `RagEvent.Preview`: a short excerpt of the best hit, labelled "Found in …", shown in the answer bubble until the model's first word arrives. Stage order is now search, plan, search again if the planner added queries, think, answer.
- `compress_sources: false` in a profile restores whole passages.

Measured on the PC against the full index (`docs/measurements/2026-10-03-compression-450.md`, 50 evaluation questions, sizes estimated at four characters per token): the median source text falls from 755 to 428 tokens (largest 441), and the median number of passages drawn on rises from 2 to 5.

Later the same day, after the first answers on the phone leaned on the history of a subject instead of explaining it:

- **Opening passages.** `Retriever.withLeads` puts the opening passage of up to two articles the question names (every content word of the title is in the question) in front of the ranked list, and the compressor prefers that passage's first four sentences even when they repeat none of the question's words. `Retriever.search` itself is unchanged and still matches the Python reference.
- **Less filler.** A sentence from an article whose title shares no word with the question counts 0.6 of its score, and sentences below 0.4 of the best sentence's score are not taken at all, so the prompt can come in under budget.
- **Answer clean-up** (`Citations`). A "Not covered by the offline sources." line standing on its own next to an answer that cites sources is removed; an answer without citations keeps it. List markers become bullets, and headings and `**bold**` become bold text. The contract also says to use the sentence only as the whole reply.

- **Articles named by redirect.** Passages that the name channel found carry `named = true`, so the opening of "Myocardial infarction" is offered for a question about a "heart attack", and of "Diffuse sky radiation" for "Why is the sky blue?" (see `docs/KNOWLEDGE_INDEX.md`, whole-question lookup).

Not measured: the effect on time to first word and on answer quality on a phone. Known weakness, visible in that file: where retrieval returns an off-topic article (for example "Guinea Pig Club" for a question about treating burns), compression now passes on a few sentences from it, and leftovers of "External links" sections still slip through. Dropping those sections belongs in the data pipeline.

## Second engine: BigMoeOnEdge for the high profile (2026-10-03)

The high profile now names `Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf` (12.3 GB) and `"engine": "bmoe"`. The earlier plan, the 4-bit Qwen3-30B-A3B memory-mapped by plain llama.cpp, is kept as `profiles/high-llama.json`.

- **Why.** An 18.6 GB file memory-mapped on a 12 GB phone leaves the kernel to page experts in and out on its own. BigMoeOnEdge (Apache-2.0) reads the experts a token needs itself and keeps recent ones in a cache it sizes to the phone. Another entry to the same bounty publishes much higher speeds with it than plain mmap gave us on the Redmi. None of that is measured by us yet.
- **How it is attached.** The engine is a program, `bmoe-cli --session`, not a library. `BmoeEngine` starts it from the app's native library directory and talks to it over standard input and output (`BmoeProtocol`, from the engine's `docs/telemetry.md`). It implements the same `InferenceEngine` interface as `LlamaEngine`, so retrieval and UI code did not change. The planner still runs on llama.cpp in the app's own process.
- **One file, statically linked.** `scripts/build_engine.sh` builds it with `BUILD_SHARED_LIBS=OFF` and the static C++ runtime, so it needs only system libraries (checked with `llvm-readelf -d`: libm, libdl, libandroid, libc) and does not clash with the `libllama.so` and `libggml*.so` of the JNI bridge. It is placed at `app/src/main/jniLibs/arm64-v8a/libbmoe-cli.so`, which is not committed. An APK built without it still works with the llama profiles.
- **Engine flags are configuration.** `engine_args` in the profile is passed through. The shipped flags (`--moe-stream --overlap --io-threads 2` and the sampling settings) are a starting point taken from the engine's documentation, not tuned.
- **What the engine cannot do that llama.cpp could.** No separate system prompt: the answer contract is put in front of the sources in the one prompt. Sampling is fixed at load. No presence penalty. No tokenize command, so `countTokens` is an estimate of four characters per token. The app's memory figures (RSS, page faults) cover the app's own process and not the engine's; the engine reports its own in `BMOE_DONE`, which is not logged yet.
- **CPU requirement.** The engine is built for ARMv8.2 with dot-product and half-float instructions, as its own build script does.

Not run on any phone. Unknown until it is: whether it starts from the native library directory on the test phones, load time, speed, memory, and whether the 2-bit model follows the answer contract.

## Source packs beyond Wikipedia (2026-10-03)

The first benchmark (`docs/BENCHMARK.md`) lost most of its points to questions the index could not answer: restaurants, and individual Ethereum proposals. Two small packs were added. Each is one more index file in the same format, so the app needed no new reading code: it already loads every `*.db` in its index folder.

| Pack | Built by | Contents | Size |
|---|---|---|---|
| `ethereum.db` | `data-pipeline/build_ethereum.py` | 557 EIPs, 607 ERCs, 54 consensus-specification documents, 244 English pages of ethereum.org, 4 NIST post-quantum standards (FIPS 203, 204, 205, SP 800-208) | 14.2 MB |
| `places.db` | `data-pipeline/build_places.py` | 50,382 vegan and vegetarian places to eat from OpenStreetMap, as 6,101 lists by city ("Vegan restaurants in Berlin") | 14.0 MB |

- **Strict matching.** A pack sets `match = strict` in its meta table. The retriever then takes its passages only when one of its articles is named in the question or a passage holds every content word of the question; it does not relax to "all words but one" as it does for Wikipedia. Without this, a question about tides pulls in whichever proposal mentions "causes". The retrieval evaluation is unchanged with both packs present (46/50 and 24/25).
- **Each proposal opens with its key facts** from the file's header: number, title, status, type, creation date, what it requires, and the network upgrade that included it, read from the final "Hardfork Meta" proposals.
- **Places are lists per city.** A place belongs to the largest GeoNames city (15,000 people or more) within 15 km, or failing that the nearest within 50 km. Fully vegan places come first, then places that offer vegan dishes; entries with more details come first within a group. Each list opens by saying that the map data has no ratings and that a place may have closed. Opening hours and websites are left out to fit more places into the prompt.
- **The named article is served first.** The sentence picker now adds a bonus to every sentence of an article the question names, so that a list is not crowded out by passing remarks in other articles that happen to repeat more of the question's words.

Limits: only eating places tagged vegan or vegetarian are included (places that merely offer vegetarian options are left out, as are all other kinds of place); nothing says which place is best; the app does not know where the phone is, so "near me" questions still cannot be answered; the public Overpass servers were overloaded during the build, and two of five tag queries needed retries.

Not measured: answers from these packs on a phone, and their effect on the benchmark.

## Search fixes after the 35B benchmark (2026-10-04, version 0.7.3)

The 35B model declined questions the 4B model had half-answered from memory, which showed how poor the sources for them were.

- **The planner had been silent on the high profile.** It took the answerer's `prompt_suffix`, which is empty there, so the Qwen3 planner was not told `/no_think` and returned no searches. The planner now has its own `prompt_suffix` (default `/no_think`). A unit test checks the shipped profiles.
- **A word of the question is not its subject.** "Hiking", "Snake" and "Boiling" were treated as the articles the question names, because each title's words all occur in the question, and their openings took the place of "Snakebite" or "Burn". A title, or a name found in the question, now counts only if it accounts for at least a third of the question's content words.
- **Planner-named articles are shown by what matches the question.** When the planner proposes "Hypothermia", the passages taken from that article are those that best match the user's words (shivering, confused), and an article with no passage matching any of them is dropped, which removes titles the planner made up.
- **The planner's words help choose sentences.** Sentence selection uses the question's words and the planner's searches together: a question about a child and boiling water never says "burn".
- **Order of sources:** openings of up to three named articles, then their other passages, then the rest.

Checked on the PC by replaying the 61 benchmark questions with the searches the planner produced in the 4B run (`CompressionReportTest` with `OFFLINE_QUERIES`): the sources for the five emergency questions are now led by "Snakebite", "Hypothermia", "Burn" and "First aid", "Earthquake safety" and "Earthquake preparedness", and "Water purification". Retrieval evaluation unchanged (46/50, 24/25); the Kotlin search still matches the Python reference on all 50.

Not measured: the effect on answers and on the benchmark score.

## Second round of search fixes, and two general packs (2026-10-05, version 0.7.3)

Three travel questions still missed although the answer was in the index (Thailand's emergency numbers, Brazil's plugs, getting from Lisbon airport to the centre). The causes, found by listing the ranked candidates for each:

- **A planner's suggestion is not the question naming something.** Passages found through a planner's search are marked `suggested`, not `named`. Suggested articles get a smaller bonus (1.0 against 2.5), so a guess such as "Brazilian plug" no longer crowds out passages that match the question, while "Hypothermia" still gets its opening shown.
- **Passages that cover the whole question come first.** A passage holding every content word of the question adds up to 2 points to its sentences; one holding half of them or fewer adds nothing. "Telephone numbers in Thailand" (emergency, numbers, Thailand) now beats the education section of "Thailand".
- **Word forms.** `sameWord` strips plural, past and -ing endings before comparing: emergency/emergencies, confused/confusion.
- **Two spellings.** Search terms with an American and a British spelling match either (`center` finds `centre`); 19 pairs, the same list in the Python reference.
- **Implied words.** "How do I get from… to…" adds transport, metro, bus, taxi, train and shuttle. They are used only to choose passages and sentences inside articles already found, never to find articles.
- **A wider pool.** Sentences are drawn from up to ten passages (was six); an article's opening no longer uses one of its two places; a planner-named article needs two of the question's words in its best passage to be kept.

Two more packs in the index format, both general and both strict:

| Pack | Built by | Contents | Size |
|---|---|---|---|
| `firstaid.db` | `data-pipeline/build_firstaid.py` | The Wikibooks "First Aid" book, 52 chapters, with everyday names as aliases ("choking" for "Obstructed Airway") | 0.33 MB |
| `travelfacts.db` | `data-pipeline/build_travel_facts.py` | 197 countries from Wikidata: emergency numbers and what each is for, mains voltage and plug types, driving side, currency, dialling code, capital, official languages, time zones | 1.2 MB |

Checked on the PC: 119 unit tests pass; the Kotlin search matches the Python reference on all 50 evaluation questions with all six index files present; retrieval evaluation 46/50 and 24/25, unchanged; in the replay of the 61 benchmark questions the three travel questions now receive the passages with the answer.

Known leftovers: the planner's "Brazilian plug" still brings in an article on a fruit tree beside the right passages; a first-aid chapter that merely contains all of a question's words (for example "Electrocution" for a question about treating a burn) can appear as a source.

Not measured: any of this on a phone or on the benchmark.

A held-out set of 40 questions (`bench/heldout40/`) was written by a separate agent that saw nothing of this repository, with reference answers by another. It is for measuring only: nothing is to be tuned on it. The questions were not read while the packs above were built.

### Everyday places by city (build 0.7.4)

The benchmark's place questions were all about vegan and vegetarian food, and `places.db` holds nothing else. A traveller also asks for a pharmacy, a hospital, a hostel, a bank. `cityplaces.db` covers those.

| Pack | Built by | Contents | Size |
|---|---|---|---|
| `cityplaces.db` | `data-pipeline/fetch_overture_places.py`, then `data-pipeline/build_places_overture.py` | 13,990,257 open places from Overture Maps (release 2026-09-23.1, confidence 0.6 or more) in 24,812 cities, as 519,248 lists ("Pharmacies in Nairobi", "Hostels in Cusco", "Vegan restaurants in Berlin"); up to 40 places per list, those Overture is most sure of first, with address, and phone number for health, lodging, police and embassy lists | 1,614 MB |

- **Two-part names.** "Is there a pharmacy open late in central Nairobi?" never says "pharmacy in Nairobi". For strict packs the search also looks up names made of two phrases of the question with words between them ("pharmacy nairobi"); each list carries such a name.
- **Names only.** The pack marks itself `match = names` in its meta table: it is strict, and its passages are never searched by their words. With millions of shop names some list holds the words of almost any question; before this, "Why is the sky blue?" received a hospital list. It answers only when one of its lists is named.
- **Beside `places.db`.** For vegan and vegetarian questions both the OpenStreetMap list and the Overture list are found and share the source budget. In the replay of the 61 benchmark questions the Berlin question receives four places from one and three from the other.

Checked on the PC: 120 unit tests pass; the Kotlin search matches the Python reference on all 50 evaluation questions with all seven index files present; retrieval evaluation 46/50 and 24/25, unchanged; in the replay of the 61 benchmark questions the pack is a source only for the 16 place questions.

Known limits: 410,598 places have no city of 15,000 people or more within 50 km and are left out; a city with the same name as an ordinary word ("Central") can be found beside the right one; Overture has no opening hours, so "open late" cannot be answered; a small town may lack a kind altogether (no ATM list for Luang Prabang).

Not measured: any of this on a phone or on the benchmark.

### Own knowledge, labelled, and a calculator (build 0.7.5)

In the 35B benchmark run about 14 of 61 answers were refusals in one wording or another, and several arithmetic answers were right on the sum and then declined the second half ("can I take it on a plane?", "how much does the water weigh?") because no source said so. A reader who is offline has nothing else to ask.

- **Own knowledge under a label.** The answer rules now tell the model to answer what the sources leave out from its own general knowledge, under a line that reads exactly "From general knowledge, not from the offline sources:", with no source numbers below it. The app looks for that line and shows a warning in red under the answer; the log records `unsourced`. The fixed "Not covered" reply remains for what neither can answer (the reader's location, today's prices, weather, opening hours). When the search finds nothing, the model is now asked anyway. `own_knowledge: false` in a profile restores the sources-only rules. This changes the answer contract in `CLAUDE.md` section 4; the owner decided it on 2026-10-05.
- **Conversions as a source (`rag/UnitConverter.kt`).** Quantities in the question ("7.5 liters per 100 km", "104°F", "5,000 mAh at 3.85 V", km, miles, m, feet, kg, pounds, liters, gallons) are converted exactly by the app and given to the model as the last numbered source, titled "Calculator". The 4B model's wrong arithmetic answers were wrong formulas (7.5 × 235.2 for a fuel conversion), not slips of addition; this removes the choice of formula.
- **A check of the sums (`rag/Calculator.kt`).** The model is asked to show each calculation as an equation. The app recomputes the left side and, where the stated result is wrong, writes "(calculator: …)" after it; the log records `calculator_fixes`. LaTeX that models write around sums is turned into plain text. It leaves alone whatever it cannot read and whatever may be a change of unit.

Checked on the PC: 134 unit tests pass. Run over the 183 saved benchmark answers (`CalculatorReportTest`), the check of the sums changes nothing: no false alarm, and no slip to catch either, so its benefit is unproven. The conversions give 31.36 mpg, 40 °C and 19.25 Wh for the three benchmark questions that ask for them.

Risk, stated plainly: an unsourced part can be wrong, and for a first-aid question that matters. The label and the warning are the safeguard; the benchmark's count of verified errors will show the cost.

Not measured: any of this on a phone or on the benchmark. How the 35B and 4B models actually use the label is unknown until then.

### Questions about "near me" (build 0.8.0)

Three of the 61 benchmark questions depend on where the reader is, and the app refused them. The owner decided on 2026-10-05 that the app may ask for the location permission.

- **Permission.** `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, asked for by Android's own dialog the first time a question is about "near me", "nearby", "around here" or "the nearest ...". The app still has no network permission, so the position cannot leave the phone; `scripts/verify_offline.sh` still passes. Only the city name is written to the log, never the coordinates.
- **Receiver (`location/GpsLocator.kt`).** Android's own `LocationManager`, no Google Play Services. A position under 15 minutes old is used as it is; otherwise one fix is requested from the satellite receiver, with a 90-second limit. The receiver only listens, so it works in airplane mode, but with no network help the first fix can take a minute or more and needs a view of the sky.
- **Position to city (`rag/Here.kt`).** `app/src/main/assets/cities.tsv` holds 31,761 cities from GeoNames (1.3 MB, built by `data-pipeline/build_cities_asset.py`). The rule is the one the place packs were built with: the largest city within 15 km, else the nearest within 50 km. So the name is one the packs' lists use. This small file ships inside the APK rather than over adb: it is needed before any pack is, and it is a thousandth of the size of one.
- **The question is rewritten.** "Vegan restaurants near me" becomes "vegan restaurants in Denver" before search, planning and the prompt, and the prompt gains a line with the reader's city. The log keeps the question as asked.
- **Scripts.** `--es gps "lat,lon"` on the start command stands in for the receiver; `bench/run_bench.py` passes a question's `"gps"` field that way, which is how the benchmark states the device's position.

Limits: "near me" means "in my city", since the lists carry no coordinates and are not sorted by distance; a reader more than 50 km from any city of 15,000 people gets no place; a small city that shares its name with a larger one can be answered with the larger one's list.

Checked: 140 unit tests pass. On the emulator, with the position passed by script, "Tell me the best vegan restaurants near me" found "Vegan restaurants in Denver" and named four places (`docs/measurements/2026-10-05-location-emulator.jsonl`). Through the emulated receiver the app got no fix in 90 seconds and answered "Not covered"; whether that is the emulator or the code is not known. **The receiver path is unverified** until it is tried on a phone outdoors. Tried on the Redmi on 2026-10-06 with build 0.8.1: the permission dialog and a position the phone already held work (see `docs/PERFORMANCE.md`); a fresh satellite fix is still unseen.

### Fresher restaurant lists

In the 35B benchmark run the graders verified 20 restaurant errors, mostly places that have closed. `data-pipeline/build_places.py` now asks OpenStreetMap for the date of each place's last edit as well, and:

- leaves out places the map marks as closed (`disused`, `abandoned`, `was:amenity`, `end_date`, opening hours "closed");
- lists first the places someone checked on the ground or edited most recently, by half-year, and among those the better-kept entries;
- says in each list's opening line how many of its places were seen in the last two years.

It cannot know that a place is open today. The public servers refuse the largest request whole, so a region that is refused is asked for in four quarters.

### A "fast" profile with under 1B active parameters (build 0.8.2)

The bounty's text suggests a mixture-of-experts model with under 1B parameters active per token as one promising direction ("not a requirement"). The owner asked on 2026-10-06 for an answerer that meets it. The two existing answerers do not: Qwen3-4B uses all 4B per token, and Qwen3.6-35B-A3B about 3B.

What was found on Hugging Face that day, at about 1B active or less:

| Model | Total / active | Licence | Why used or not |
|---|---|---|---|
| EuroMoE-2.6B-A0.6B-Instruct-2512 | 2.6B / 0.6B | Apache-2.0 | **Used.** Instruction-tuned, an architecture stock llama.cpp runs, 1.62 GB at Q4_K_M |
| maple-preview | 20B / 1B | MIT | At 1B, not under; needs its makers' fork of llama.cpp |
| LFM2.5-8B-A1B | 8B / about 1B | LFM Open License 1.0 | Not an open-source licence |
| AliceAI-T5-35B-A0.6B | 35B / 0.6B | Apache-2.0 on the converted files | A base encoder-decoder model, not instruction-tuned; needs a fork of llama.cpp |
| granite-swash-3b-a600m | 3B / 0.6B | Apache-2.0 | A base model, not instruction-tuned |

`profiles/fast.json` named the EuroMoE file in build 0.8.2; since 0.8.3 that profile is `profiles/fast-euromoe.json` (see the next section). "Fast" is a third choice in Settings and for scripts (`--es profile fast`); it is never picked automatically. The planner stays Qwen3-1.7B. EuroMoE's context is 4,096 tokens, which the profile's budget fits (450 for sources, 384 for the answer). Its sampling settings are placeholders, not tuned.

**First trial, on the emulator (2026-10-06, record `docs/measurements/2026-10-06-fast-profile-emulator.jsonl`).** The file loads and answers through the app's llama.cpp with its own chat format, so nothing in the engine had to change. The answers were poor, on three questions:

- With the full answer rules the model copied their fixed sentences ("Not covered by the offline sources", "From general knowledge, ...") into its replies in place of an answer. A profile can therefore ask for short rules (`"answer_rules": "short"`: three plain sentences that name nothing to copy), and `fast.json` does.
- With the short rules it writes fluent answers that are weak or wrong: no citation in any of the three; it called 104 °F "not dangerous for a long walk" and ignored the calculator's 40 °C; asked what to do when someone is choking it said only to call for help, with the first-aid chapter among its sources; for Brazil's plugs it gave the voltages and no plug types.

So the profile meets the "under 1B active" line and, on this evidence, does not give useful research answers. It stays an option and is not recommended. Speed on a phone is still unmeasured; the emulator's timings say nothing about it.

### The three tiers, with Ling-3.0-tiny as the fast one (build 0.8.3)

After the EuroMoE trial the owner decided on 2026-10-06: the 35B model stays the entry for 12 GB phones, the 4B model stays for small phones, and the fast tier becomes Ling-3.0-tiny (inclusionAI, MIT; 7.9B parameters, 1.3B active per token; `Ling-3.0-tiny-Q4_0.gguf`, 4.62 GB). It is just over the bounty's "under 1B active" suggestion; it was chosen because another entry to the bounty reports useful answers and a seven-second wait with it on a 12 GB phone, where the only model found under 1B did not answer usefully.

| Tier | Profile | Answerer | Total / active per token | Picked |
|---|---|---|---|---|
| High | `high.json` | Qwen3.6-35B-A3B, 2-bit, streamed from storage | 35B / about 3B | automatically from 10 GB of RAM |
| Low | `low.json` | Qwen3-4B | 4B / 4B | automatically under 10 GB |
| Fast | `fast.json` | Ling-3.0-tiny | 7.9B / 1.3B | only by choice |

What the fast profile needed:

- **Thinking off.** Ling thinks before answering unless told otherwise. The profile ends the instructions with "detailed thinking off" (`prompt_suffix`) and starts the model's reply with an empty thinking block (`assistant_prefix`, new: text the engine writes after the assistant header). Both come from the notes of the Commonplace project, which found that the chat format llama.cpp applies to this model adds neither.
- **One model for both jobs.** The file is 4.62 GB; with the 1.83 GB planner beside it an 8 GB phone would have little left. When a profile names the same file for the planner and the answerer, the app now uses the loaded model for planning too and loads nothing else. The cost: planning and answering take turns in one context, so the fixed instructions are read again for each question instead of being kept.
- llama.cpp at the pinned tag already knows the architecture (`bailingmoe3`).

**First run on the Redmi, 2026-10-07** (`docs/PERFORMANCE.md`): it loads through the stock engine, thinking stays off, and on five questions the first word came after a median of 83 s against 159 s for the 4B model. Its answers were about as good on four and wrong on one. The sampling settings are still placeholders, and its quality has not been benchmarked.

### Rules read ahead, and a fast tier without a planner (build 0.8.6)

On the Redmi the fast tier's time to the first word (median 83 s over five questions, `docs/measurements/2026-10-07-redmi12-fast-ling.jsonl`) split into about 16 to 23 s writing search queries, 1 to 13 s searching and 52 to 67 s reading a prompt of 770 to 1,010 tokens. None of the prompt was ever reused. Three changes follow from that.

- **The answer rules are read when the model has loaded, not when the first question arrives.** They are the same for every question and are roughly half of a prompt. `InferenceEngine.prime()` has the model read them while the user is still typing; the bridge's prefix cache then skips them for every question. A question asked before the reading has finished waits for it. This applies to every profile on the llama.cpp engine (low and fast); the BigMoeOnEdge helper has no such call.
- **The fast profile has no planner.** Its planner was the Ling answerer itself, which cost the 16 to 23 s and, sharing one context, threw away the cached rules on every question. The fast tier now searches on the question alone. Cost: no article titles suggested by a model, so questions whose words differ from the article's are found less often.
- **The fast profile carries fewer sources**: 300 tokens from at most 6 passages instead of 450 from 10.
- **The rules were left at full length.** Once they are read ahead their length costs nothing per question, and the short rules gave worse answers.

Not yet measured on the Redmi. On the emulator with a stand-in model, 440 of 765 and 448 of 827 prompt tokens were reused and planning took 0 ms (`docs/measurements/2026-10-08-read-ahead-emulator.log`). The benchmark runner restarts the app for every question, so it measures the first question after a start; a question asked into a running app is the case this helps most.

## Decisions and defaults

| Decision | Choice | Reason |
|---|---|---|
| Submodule location | `third_party/llama.cpp` at the repository root | CLAUDE.md Section 6 is ambiguous; the root keeps native dependencies out of the app module |
| Submodule depth | Shallow clone, pinned to a release tag | The full history is large and not needed |
| Which llama.cpp libraries | Core only (`llama`, `ggml`); `LLAMA_BUILD_COMMON=OFF` | The `common` helper library changes often and is not a stable API. The bridge needs only `llama.h` |
| Chat templating | `llama_chat_apply_template` with the template embedded in the GGUF | No Jinja engine needed. It recognises Qwen's ChatML format. A profile can name another built-in template |
| Native build type | Always `Release`, including in the debug APK | A debug build of llama.cpp is several times slower and would make measurements meaningless |
| OpenMP | Off | The official cross-compile instructions turn it off. llama.cpp's Android example turns it on; comparing the two is a candidate for M5 |
| KleidiAI | Off | It is fetched from the network at build time, which hurts reproducible builds. Candidate for M5 |
| GPU backends | None; CPU only | Boring and predictable. The Redmi's GPU is weak |
| Weight repacking | Off in both profiles (`"repack": false`) | Measured: it holds a second copy of the weights in RAM for no speed gain on the Redmi, and cannot fit for the 30B model |
| Asking over adb | `am start ... --es ask "question"` asks a question | Lets `scripts/measure.sh`, and later the benchmark runner, drive the app without touching the screen |
| Thread count | Profile value, or `clamp(cores - 2, 2, 4)` when the profile says 0 | Same heuristic as llama.cpp's Android example |
| Conversation memory | None. Each question starts from a clean context | The product is question answering over retrieved passages, not chat. Prefix caching of the system prompt is M5 |
| Reasoning output | `/no_think` in the profile's system prompt; any `<think>` block is hidden by the UI | Reasoning tokens are slow on a phone. This is profile configuration and can be changed without code |
| Text across JNI | UTF-8 byte arrays, split on character boundaries in native code | JNI strings use modified UTF-8 and corrupt emoji and other 4-byte characters |
| Time to first token | Measured from the `generate` call to the first sampled token | It includes prompt processing, which is what the user waits for |
| Tokens per second | Generated tokens divided by the time after prompt processing ends | Separates generation speed from prompt length |
| Model location | `<external files>/models/`, then `<internal files>/models/` | External app storage needs no permission and is reachable by adb. Internal is the fallback |
| Profile selection | Bundled `low.json` unless `profile.json` was pushed | Automatic selection by RAM is part of the profile system in M4 |
| Permissions | No network permission in any build type, including debug | The offline claim is then true of the build that is actually tested |
| JSON library | kotlinx-serialization-json (Apache-2.0) | Android's built-in `org.json` cannot run in plain JVM unit tests |
| minSdk / targetSdk / compileSdk | 28 / 35 / 36 | 28 matches the platform level in llama.cpp's Android instructions |
| Package name | `app.offlineresearch` | Placeholder; change before release if wanted |
| Licence of this code | MIT | Same as llama.cpp |
| Scripts | bash; run from Git Bash on Windows | One script for all three desktop platforms |
