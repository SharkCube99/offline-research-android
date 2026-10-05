# CLAUDE.md — Offline AI Research App for Android

A fully offline research tool built around a streamed 30B mixture-of-experts model.

Work one milestone at a time and provide evidence (test output, measurements) before a milestone is accepted. Items marked **VERIFY** depend on facts that change quickly (model builds, library flags); check them against current documentation before relying on them.

## 1. Role and mission

You are a senior Android and on-device ML engineer. Build an Android application that answers real research questions with no network connection at all: explanation, comparison, synthesis and multi-step reasoning, always with citations to offline source passages. The target is a tool that is more than 50% as good as internet search plus a frontier AI model, for people who are travelling and offline (flights, hikes, remote areas).

## 2. Context and success criteria

- This is an entry for a public bounty (poidh.xyz, bounty 31) that asks for the best offline AI research app for Android. The deadline for a confirmed winner is **31 October 2026**.
- Success is judged on real Android hardware, most likely a 12 GB RAM phone, against the bar of being more than 50% as good as internet plus frontier models. A public GitHub repository must let a stranger install and run it within minutes.
- The suggested architecture in the bounty is a very large mixture-of-experts model (around 100B parameters, most stored on disk, under 1B active per token). That is a direction, not a requirement. **Our approach:** a 30B-class MoE streamed from flash, combined with strong retrieval over offline corpora.
- Total storage for the app, model weights, indexes and databases must be **50 GB or less**. Streaming weights from disk is expected.

## 3. Hard constraints

- **Offline by construction.** The release app must not declare the `INTERNET` permission. Models and data are provisioned by a script over adb, never by the app. Every feature must work in airplane mode.
- **Target:** Android, `arm64-v8a` only. Kotlin and Jetpack Compose for the UI. Native inference through llama.cpp via a JNI bridge (NDK and CMake).
- **Development device** is a Redmi 12 5G (Snapdragon 4 Gen 2, 8 GB RAM, UFS 2.2). It is a low-end reference: it must run the small tier. The 30B tier is validated on a 12 GB device.
- **Model choice is configuration, not code.** Swapping models must be a config change with no code edits.
- No hidden dependencies on Google Play Services or any cloud API. Open-source licences only; record every data and model licence in `LICENSES.md`.
- **Never fabricate benchmark numbers.** Every performance claim in the README must come from a logged measurement in the repository.

## 4. Architecture

```
question
  -> [Planner: small resident model, ~1.7B]  -> up to 3 search queries
  -> [Retriever: SQLite FTS5 / BM25 over offline corpora] -> top 20 passages
  -> [Reranker + context budgeter]           -> best passages within ~1000 tokens
  -> [Answerer: Qwen3-30B-A3B-class MoE, mmap-streamed from flash]
  -> streamed answer with [1][2] citations tied to passage IDs
  -> UI: answer, sources panel, timing and 'thinking deeper' state
```

- **Inference layer (C++ and JNI):** wraps llama.cpp. Exposes `load`, `generate` (streaming callback), `cancel`, and `metrics`. Memory-map the model (mmap on, mlock off by default). Reuse the KV cache for the fixed system prompt (prefix caching).
- **Model profiles (JSON):** selected at first launch by available RAM. The high profile uses the 30B MoE; the low profile uses a 4B dense model (and is also the development profile). Each profile sets model path, context size, threads, batch size, retrieval budget and chat template.
- **Knowledge layer:** offline Wikipedia and Wikivoyage first, then practical corpora (first aid, survival, repair, plant and food safety) subject to licence review. Chunks of about 200 to 300 words, prefixed with the article title, stored with source metadata in SQLite FTS5.
- **Answer contract:** the answerer states facts from the supplied passages and cites passage numbers. Where the passages fall short it may answer from its own knowledge, but only under a fixed line that marks that part as unsourced, and the app shows a warning next to such an answer (decided 2026-10-05; `own_knowledge` in the profile switches it off). It says "not covered" only when neither can answer, such as questions that depend on the reader's location or on today's prices.

## 5. Models and data (VERIFY current availability)

| Role | Candidate | Notes |
|---|---|---|
| Answerer (high tier) | Qwen3-30B-A3B, Q4 GGUF (about 18 GB) | Around 3B active parameters per token. Streamed via mmap. Benchmark against 1 or 2 alternative sparse models before committing. |
| Planner and fast tier | Qwen3-1.7B, Q4 GGUF | Always resident in RAM. Rewrites questions into search queries. |
| Development / low tier | Qwen3-4B, Q4 GGUF | Fits an 8 GB phone. Same chat template family as the answerer. |
| Knowledge | Wikipedia and Wikivoyage dumps (or Kiwix ZIM files) | Measure the final index size early. Trim low-value articles if the 50 GB budget is at risk. |

## 6. Repository layout

```
app/                     Android app (Kotlin, Compose)
  src/main/cpp/          JNI bridge + llama.cpp (git submodule under third_party/)
  src/main/kotlin/...    ui/, rag/ (planner, retriever, reranker), engine/, profiles/
data-pipeline/           Python: download, clean, chunk, build FTS5 index, size report
profiles/                low.json, high.json  (model + retrieval settings)
bench/                   questions.jsonl, runner, grading sheet, report generator
scripts/                 setup.sh (adb push model + index), verify_offline.sh
docs/                    ARCHITECTURE.md, BENCHMARK.md, PERFORMANCE.md
LICENSES.md  README.md  CLAUDE.md
```

## 7. Milestones and acceptance criteria

Work strictly in order. Do not start a milestone until the previous one meets its acceptance criteria, and report the evidence in your summary.

| Milestone | Deliverables | Acceptance criteria |
|---|---|---|
| **M1 Foundation** | Repo skeleton, NDK build of llama.cpp for arm64, JNI bridge, minimal chat screen, 4B model running on device. | App builds from a clean clone. A prompt gets a streamed reply on the Redmi in airplane mode. Tokens per second and load time are logged. |
| **M2 Knowledge index** | data-pipeline that turns Wikipedia and Wikivoyage into a SQLite FTS5 index, plus a size report. | Index builds reproducibly with one command. A test set of 50 queries returns a relevant passage in the top 5 for at least 80% of them. Total index size is reported. |
| **M3 RAG pipeline** | Planner, retriever, context budgeter, answerer with citations, sources panel in the UI. | End to end answers on the device with correct citation numbers. Questions outside the corpus get an honest 'not covered' reply. Unit tests for retrieval and citation parsing pass. |
| **M4 30B on a 12 GB phone** | Profile system, 30B MoE loaded via mmap, metrics overlay (time to first token, tokens per second, RSS, thermal state). | 30B answers a RAG query on a 12 GB device with no crash and no out-of-memory kill in 20 consecutive questions. Measurements are saved in `docs/PERFORMANCE.md`. |
| **M5 Speed and quality** | Prefix caching, reranking, shorter context budget, tuned threads and batch size. Expert cache or prefetch only if measurements show flash reads are the bottleneck. | Documented before/after numbers. Time to first token and tokens per second improve over the M4 baseline. Prompts are tuned on the 30B, not the 4B. |
| **M6 Benchmark and release** | 100-question benchmark, blind grading sheet, report, one-command setup, demo script, clean README. | A stranger can follow the README on a fresh phone and reach a working answer. Benchmark report shows per-category results, including failures. |

## 8. Benchmark specification

- 100 realistic offline questions in the categories: travel, health and first aid, how-to and repair, explain, compare, multi-step reasoning, and 'not answerable offline'.
- For each question, store the app's answer and a frontier model's answer produced with internet access. Grading is blind: shuffle the order, hide which is which, score each answer 1 to 5 for correctness, usefulness and citation quality.
- Report the average score ratio (app divided by frontier), broken down by category, plus median time to first token and tokens per second on the 12 GB device. Publish the questions, raw answers and scores. State plainly where the app loses.

## 9. Working rules for Claude Code

- **Plan first:** for each milestone, write a short plan and list assumptions before writing code.
- Make small, reviewable commits with clear messages. Run the build and tests before declaring anything done.
- Prefer boring, well-documented approaches. Do not add a dependency without stating why and its licence.
- When a fact is uncertain (model file names, llama.cpp build flags, ARM instruction support on the target CPU), search current documentation first and note the source in the docs.
- **Do not claim a measurement you did not run.** If you cannot test on the target hardware, say so and give exact steps for me to run and report back.
- Keep the inference layer behind an interface so the model, quantisation and caching strategy can change without touching retrieval or UI code.
- Ask me a question only when blocked; otherwise choose a sensible default and record it in `docs/ARCHITECTURE.md`.

## 10. Definition of done

- Works fully in airplane mode on a 12 GB Android phone using the 30B model, with cited answers.
- Total on-device footprint (model, index, app) is 50 GB or less, and the size breakdown is documented.
- Public repo with a tested one-command setup, licence file, benchmark results and a short demo recording script.
- All numbers in the README trace back to logged measurements in the repository.

## 11. Out of scope

- Training or fine-tuning a new model (may be explored only after M6).
- iOS, web, cloud sync, accounts, analytics or any network feature.
- Image, voice or multi-modal input.
