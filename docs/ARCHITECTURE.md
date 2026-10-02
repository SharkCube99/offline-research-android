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
