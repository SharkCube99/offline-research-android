# Performance log

Every number in this file must come from a measurement taken on a real device and saved under `docs/measurements/`. Nothing here is estimated.

## How a measurement is taken

1. Install the app and push the model with `scripts/setup.sh`.
2. Turn on airplane mode.
3. Ask the questions for the run.
4. Pull the log and save it in the repository:

   ```bash
   adb pull /sdcard/Android/data/app.offlineresearch/files/logs/metrics.jsonl docs/measurements/<date>-<device>-<model>.jsonl
   ```

Each line of the file is one answer. The fields:

| Field | Meaning |
|---|---|
| `load_ms` | Time to load the model and create the context |
| `prompt_tokens`, `prompt_ms` | Size of the prompt and time to process it |
| `ttft_ms` | Time from sending the question to the first generated token (includes prompt processing) |
| `gen_tokens`, `gen_ms` | Tokens generated and the time spent generating them |
| `tokens_per_sec` | `gen_tokens / gen_ms` |
| `threads`, `n_ctx`, `model_file`, `profile` | Settings in force |
| `device`, `soc`, `android_sdk`, `app_version` | What it ran on |
| `stop` | Why generation ended: `EOS`, `MAX_TOKENS`, `CANCELLED`, `CONTEXT_FULL` |

## M1 baseline: Qwen3-4B Q4_K_M on Redmi 12 5G (8 GB)

Source: `docs/measurements/2026-10-01-redmi12-5g-qwen3-4b-q4km.jsonl` (3 questions, airplane mode on, typed by hand in the app). Load time and CPU features are from logcat of the same session.

Setup: Xiaomi 23076RN4BI (SoC SM4450, Android 15), app 0.1.0-m1, profile `low`, `n_ctx` 4096, 4 threads, mmap on, mlock off, llama.cpp `b11311`, native code built as Release.

| Run | Prompt tokens | Time to first token | Generated tokens | Tokens per second | Stop |
|---|---|---|---|---|---|
| 1 | 46 | 9.74 s | 86 | 3.33 | EOS |
| 2 | 48 | 8.99 s | 205 | 4.38 | EOS |
| 3 | 44 | 6.34 s | 109 | 4.31 | EOS |
| **Median** | | **8.99 s** | | **4.31** | |

| Metric | Value |
|---|---|
| Model load time | 17.8 s (one load) |
| CPU features reported by llama.cpp | NEON, ARM_FMA, FP16_VA, DOTPROD, REPACK |

Three runs is a small sample; treat these as a first baseline, not a tuned result.

### Observations

- **Prompt processing is slow.** About 45 prompt tokens took 6.3 to 9.7 s, which is 5 to 7 tokens per second. That is barely faster than generation, where batch processing would normally be several times faster. Not yet explained.
- **The weights are held twice.** At load, llama.cpp reported a 2,362.55 MiB memory-mapped buffer and a 2,375.16 MiB `CPU_REPACK` buffer. Shortly after load, `dumpsys meminfo` showed the app at 3,167,501 kB PSS with 2,653,026 kB of it in swap, and `/proc/meminfo` showed 1,020,852 kB available out of 7,614,048 kB. The repacked copy is controlled by `llama_model_params.use_extra_bufts`, which the bridge leaves at its default. Swapping is a likely cause of the slow prompt processing, but that has not been tested.
- **Thread count is untuned.** The default picked 4 threads on a 2 big + 6 little core chip. 2 threads has not been tried.

## Repack and thread count: Qwen3-4B Q4_K_M on Redmi 12 5G (2026-10-01)

Source: `docs/measurements/2026-10-01-23076RN4BI-t{2,4}-repack-{true,false}.jsonl` (timings) and the matching `.txt` files (load line, buffer sizes, memory). Taken with `scripts/measure.sh`: the same three questions per configuration, asked over adb, answers capped at 128 tokens, airplane mode on. Run order: t4 on, t4 off, t2 off, t2 on, 45 s apart. The model file was already in the page cache, so load times are warm.

Times to first token and tokens per second are listed per question, then the median.

| Threads | Repack | Load | Time to first token (s) | Median | Tokens per second | Median | MemAvailable after load | after 3 questions |
|---|---|---|---|---|---|---|---|---|
| 4 | on | 11.7 s | 6.08, 8.98, 7.98 | 7.98 | 4.16, 4.26, 4.19 | 4.19 | 1,477,836 kB | 790,640 kB |
| 4 | off | 2.4 s | 7.28, 8.02, 7.31 | 7.31 | 4.34, 4.25, 1.58 | 4.25 | 3,679,684 kB | 3,775,332 kB |
| 2 | off | 4.4 s | 34.60, 9.18, 9.72 | 9.72 | 3.52, 4.14, 3.53 | 3.53 | 3,745,876 kB | 3,548,264 kB |
| 2 | on | 10.4 s | 6.97, 11.48, 8.87 | 8.87 | 4.91, 4.47, 4.56 | 4.56 | 1,564,812 kB | 988,988 kB |

Prompts were 48, 54 and 49 tokens in every configuration.

### What this shows

- **Repack off removes the second copy of the weights.** With repack off llama.cpp reports only the 2,375.91 MiB mapped buffer; with it on, a 2,375.16 MiB `CPU_REPACK` buffer as well. About 2.2 GB more memory stays available, swap use by the app drops from hundreds of MB to under 10 MB, and the warm load is 2.4 to 4.4 s instead of 10.4 to 11.7 s.
- **Repack off did not cost measurable speed at 4 threads** (median 4.25 against 4.19 tokens per second).
- **Thread count has no clear winner.** 2 threads was faster with repack on and slower with repack off. With three questions per configuration and outliers in two of them, the differences are within the noise.
- **Two outliers are unexplained:** 1.58 tokens per second on the third question of t4/off, and 34.6 s to first token on the first question of t2/off. Thermal throttling or background activity are possible causes; neither was measured.
- **Prompt processing is slow in every configuration:** 48 to 54 tokens took 6 to 11 s outside the outlier, roughly 5 to 8 tokens per second. Removing the swap pressure did not change it, so swap was not the cause. At this rate about 1,000 tokens of retrieved passages would take minutes before the first token on this phone with this model.

### Decisions

- `repack` is `false` in both shipped profiles. For the 30B model it is required, not optional: a second 18.6 GB copy cannot fit in RAM.
- `n_threads` stays at 0 (the engine picks 4 on this phone). The data does not justify changing it.

### Not measured

Cold load time (after a reboot), longer runs, battery temperature effects, and prompts of realistic RAG length.

## M3: cited answers on the Redmi 12 5G (2026-10-02)

Source: `docs/measurements/2026-10-02-23076RN4BI-m3-airplane.jsonl` (five questions, airplane mode on, asked over adb) and `docs/measurements/2026-10-02-23076RN4BI-m3-adb-smoke.jsonl` (four earlier runs with a 4.1 GB index and no planner, airplane mode off).

Setup for the airplane-mode run: app 0.3.0-m3, profile `low` (800 passage tokens, 384 answer tokens), Qwen3-4B Q4_K_M answerer and Qwen3-1.7B Q8_0 planner both loaded, full 22.9 GB index, 4 threads, repack off.

| Question | Plan | Search | Prompt tokens | Prompt processing | First word after | Generated | Tokens per second |
|---|---|---|---|---|---|---|---|
| Symptoms of dehydration | 12 s | 2.1 s | 847 | 123 s | 137 s | 81 | 2.18 |
| How a refrigerator works | 13 s | 6.0 s | 927 | 144 s | 163 s | 134 | 1.97 |
| Airport to central Paris | 15 s | 7.8 s | 973 | 157 s | 180 s | 147 | 1.83 |
| Hotel wifi password | 13 s | 4.0 s | 955 | 157 s | 174 s | 85 | 1.63 |
| Apple closing price | 14 s | 6.1 s | 974 | 178 s | 199 s | 11 | 1.75 |
| **Median** | 13.5 s | 6.0 s | 955 | | **174 s** | | **1.83** |

Median prompt processing speed: 6.2 tokens per second.

With the 1,000-token budget (first smoke run): a 1,096-token prompt took 188 s to process and the answer came at 1.48 tokens per second.

### Observations

- **Prompt processing is the whole wait.** About 6 tokens per second, the same rate as the 50-token prompts in M1, so the time grows in step with the prompt.
- **Generation is slower than in M1** (1.6 to 2.2 tokens per second against about 4.3). M1's prompts were about 50 tokens; these are 850 to 1,100. The cause has not been isolated.
- **Thread placement.** Sampled three times during prompt processing, the answerer's four threads were on a mix of fast cores (cpu6, cpu7) and slow cores, different each time. Core frequencies read 1.29 to 1.48 GHz on the slow cores (maximum 1.96) and 1.65 to 1.90 GHz on the fast cores (maximum 2.21). The thermal service reported status 0 and the battery 38 °C. Pinning to the two fast cores has not been tried.
- **Search on the phone:** 2 to 8 s for two to four queries on the full index in this run, and up to 15 s in the first runs after the index was pushed.
- **Memory:** with both models and the index open, `MemAvailable` was 2.9 GB of 7.6 GB.

## Speed work on the Redmi 12 5G: thread pinning, thread counts, batch size, prefix caching (2026-10-02)

This is M5-style tuning done before M4, on the low-end phone with the 4B model. It is not the M5 result: that has to be measured against an M4 baseline on a 12 GB phone with the 30B model.

Source: `docs/measurements/2026-10-02-23076RN4BI-t*.jsonl` and `.txt`, taken with `scripts/measure.sh`. Each configuration: restart the app, ask the same two questions over adb, answers capped at 64 tokens, airplane mode on, full 22.9 GB index, profile `low` (800 passage tokens), app 0.5.0-speed. "Reading" is prompt processing over the tokens actually processed (prompt tokens minus reused tokens); "writing" is generation.

Run order as listed. The core capacities the phone reports are 1024 for cpu6 and cpu7 and 478 for cpu0 to cpu5.

| Configuration | Q1 prompt | Q1 reading | Q1 writing | Q2 prompt (reused) | Q2 reading | Q2 writing | Battery, start to end |
|---|---|---|---|---|---|---|---|
| 4 threads, not pinned (baseline) | 847 | 146 s, 5.82 tok/s | 1.45 tok/s | 927 (171) | 147 s, 5.15 tok/s | 1.69 tok/s | 35.0 to 38.0 °C |
| 2 threads pinned to cpu6-7 | 847 | 179 s, 4.73 tok/s | 3.46 tok/s | 927 (171) | 168 s, 4.49 tok/s | 3.04 tok/s | 38.0 to 39.0 °C |
| 8 threads, not pinned | 847 | 164 s, 5.16 tok/s | 1.89 tok/s | 927 (171) | 142 s, 5.31 tok/s | 1.59 tok/s | 39.0 °C at load |
| 2 pinned for writing, 8 for reading | 847 | 165 s, 5.13 tok/s | 2.93 tok/s | 927 (171) | 142 s, 5.31 tok/s | 3.12 tok/s | 39.0 to 40.0 °C |
| Same, `n_batch` 128 | 992 | 201 s, 4.94 tok/s | 2.94 tok/s | 927 (171) | 147 s, 5.14 tok/s | 2.60 tok/s | 40.0 °C |
| 4 threads, not pinned (repeat) | 847 | 182 s, 4.64 tok/s | 1.59 tok/s | not measured | | | 40.0 °C at load |

Notes on the table:

- The 8-thread run's results were saved by hand from the phone's metrics log; its `.txt` file has only the after-load readings, because the measurement script was edited while that run was using it and its last step failed.
- The repeat baseline lost its second question: the app was closed from the phone's recent-apps screen during it (the system log shows `Killing ... app.offlineresearch: SwipeUpClean`). Its first question is from the run's console output; there is no `.jsonl` for it.
- In the `n_batch` 128 run the planner chose different sources for the first question, so that prompt is 992 tokens, not 847.

### Prefix caching

The context keeps the tokens a new prompt shares with the previous one and processes only the rest.

| | First question after start | Second question |
|---|---|---|
| Planner prompt | 146 tokens, 0 reused | 145 tokens, 134 reused |
| Planner prompt processing (the first four runs, read from the phone's log) | 10.4 to 11.8 s | 3.9 to 6.2 s |
| Answerer prompt | 847 tokens, 0 reused | 927 tokens, 171 reused |

The answerer reuses its fixed instructions, 171 tokens, which is 18% of a 927-token prompt. The retrieved sources differ for every question and cannot be reused.

### What this shows

- **Writing is about twice as fast with two threads pinned to the fast cores:** 2.6 to 3.5 tokens per second against 1.5 to 1.9 unpinned, in every pinned run.
- **Reading does not respond much to thread settings.** All configurations fall between 4.5 and 5.8 tokens per second. Two pinned fast cores alone reach 4.5 to 4.7; adding the six slow cores brings 5.1 to 5.3.
- **The phone slowed as it warmed.** The baseline's first question read at 5.82 tokens per second at 35 °C battery temperature and 4.64 at 40 °C. So the first baseline flatters the unpinned setting; compared warm, 8 reading threads (5.13 to 5.31) beat 4 unpinned (4.64).
- **Batch size 128 against 512 made no difference** on the comparable question (5.14 against 5.31 tokens per second).
- **Total wait barely moved.** Reading 750 to 850 tokens still takes 140 to 180 s. Pinning shortens a 150-token answer by roughly 45 s; the prefix cache removes about 30 s of reading from every question after the first.

### Decisions

- `low.json`: `n_threads` 2, `n_threads_batch` 8, `thread_affinity` "fastest". Checked from the bundled profile after the phone had rested (`docs/measurements/2026-10-02-23076RN4BI-low-profile-check.jsonl`, one question, airplane mode off): 925 prompt tokens read in 123 s (7.54 tokens per second), 111 tokens written at 3.24 per second, first word after 142 s. The reading speed is higher than in any run above, which fits the phone having cooled; it is one question.
- `high.json` is unchanged (`thread_affinity` "none"). Its values have to come from measurements on the 12 GB phone.
- `n_batch` stays 512.

### Not measured

Each configuration is two questions, run once, on a phone that was warming up; differences of 10% are within the noise. Not tried: 4 pinned threads for reading, llama.cpp's polling and priority settings, an OpenMP or KleidiAI build, a smaller passage budget, and a smaller answerer model for the low tier.

## M4: 30B on a 12 GB phone

**Not measured.** No 12 GB phone has run the app. The procedure is in `docs/M4_RUNBOOK.md`. When it has been run, replace this paragraph with the filled-in template below. Do not fill in any cell that was not measured.

### Template

```markdown
## M4: Qwen3-30B-A3B Q4_K_M on <phone model> (<RAM> GB), <date>

Source: `docs/measurements/<date>-<device>-stress-high-30b.md`, `.jsonl`, `.txt` and `.exits.jsonl`, taken with
`scripts/stress.sh --profile high --label high-30b`. Airplane mode: <on/off, as printed in the .txt file>.

<paste the whole of the .md report here: the Result line, the setup lines, the table and the medians>

### Model load

| Measure | Value | Source |
|---|---|---|
| Load time, first load after pushing the file | <s> | `loaded '...' in N ms` line in the .txt file |
| Memory available after load | <kB> | "after load" block in the .txt file |
| App memory after load (TOTAL PSS) | <kB> | same block |

### Observations

- <Did all 20 complete? If not, at which question did it stop, and what reason does .exits.jsonl give?>
- <Did memory available fall across the run? First and last values.>
- <Did the thermal status leave NONE? At which question?>
- <Did speed change between the first and last five questions?>
- <Anything seen on the phone that the log does not show.>

### Not measured

<What this run did not cover: for example a cold start after reboot, a second run, other thread settings.>
```

## Week 1 first runs on the Redmi 12 5G (2026-10-03)

One question each ("What causes the tides?"), app 0.6.0-w1, source budget 450 with sentence compression. Raw records: `docs/measurements/2026-10-03-redmi12-w1-first-runs.jsonl`. Screens: `docs/measurements/screens/2026-10-03-preview-card.png`, `2026-10-03-bmoe-redmi-1.png`. One run per configuration, so these are observations, not benchmarks. Airplane mode was off.

| | low: Qwen3-4B, llama.cpp | high: Qwen3.6-35B-A3B 2-bit, BigMoeOnEdge |
|---|---|---|
| Model load | 5.1 s | 23.2 s |
| Prompt tokens | 600 | 606 |
| Prompt reading | 100.9 s (5.9 tok/s) | 130.3 s (4.7 tok/s) |
| Planner + search | 14.7 s + 9.8 s | 26.6 s + 2.1 s |
| First word after asking | 125.5 s | 160.9 s |
| Writing | 168 tokens at 3.27 tok/s | 261 tokens at 0.97 tok/s |
| Memory available afterwards | 4.02 GB | 1.23 GB |
| Thermal state | none | none |
| Crash or kill | no | no |

- The low run was made while a 12.3 GB file was being copied to the phone over USB, which competes for the flash; its search time in particular (9.8 s) is not representative. The earlier comparable run (800-token budget) had a 925-token prompt read in 123 s.
- The high run is the first time the BigMoeOnEdge engine ran inside this app. It started from the native library directory, loaded a 12.3 GB model on an 8 GB phone and answered without being killed. At about 1 token per second it is not usable on this phone; that was expected, and the 12 GB phone is still unmeasured.
- The app's RSS and page-fault figures in the high record describe the app's own process, not the engine's, and should be ignored for that row.
- Quality, from reading the two answers: both cite only real source numbers. Both lean on the history of tidal theory, because the sentences chosen for the prompt were mostly historical ones containing "caused"; the physical explanation was under-represented. The 35B answer ends with a stray "Not covered by the offline sources." after a full answer, and uses Markdown bullets and bold that the app shows as raw asterisks.

### After the answer-quality fixes (same day, same question)

Changes: the opening passage of the article the question names is always offered to the sentence picker and its first sentences are preferred; weak sentences from side articles are dropped; a "Not covered" line next to a cited answer is removed; list markers and `**bold**` are rendered. Raw records: `docs/measurements/2026-10-03-redmi12-w1-after-fixes.jsonl`. Screens: `2026-10-03-low-after-fixes.png`, `2026-10-03-bmoe-redmi-2.png`.

| | low: Qwen3-4B | high: Qwen3.6-35B-A3B 2-bit, BigMoeOnEdge |
|---|---|---|
| Prompt tokens | 556 | 618 |
| Prompt reading | 136.8 s (4.1 tok/s) | 174.0 s (3.6 tok/s) |
| Planner + search | 24.8 s + 11.5 s | 27.1 s + 2.8 s |
| First word after asking | 173.1 s | 206.9 s |
| Writing | 67 tokens at 2.51 tok/s | 203 tokens at 0.98 tok/s |

- Both answers now open with the physical cause (differential gravity of the Moon and the Sun) and cite the opening of "Tide" for it. Neither has a stray "Not covered" line; the 35B answer's list shows as bullets.
- The timings are worse than the first runs and should not be read as an effect of the changes: during these runs the phone was compiling another app's update (`dex2oat` at 100% of a core, seen with `top`) and another app was in the foreground. A clean timing of the 450-token budget on the 4B is still owed.

### Clean timing of the shorter prompt on the 4B (2026-10-03, phone idle)

`scripts/measure.sh --threads 2 --batch-threads 8 --affinity fastest --label w1-b450`: the same two questions, thread settings and 64-token answer cap as the `t2x8-fastest` run of 2026-10-02, which used whole passages and an 800-token budget. Raw records: `docs/measurements/2026-10-03-23076RN4BI-w1-b450.jsonl` and `.txt`. One run of two questions each; battery temperature 38 to 40 C in both runs; airplane mode off.

| Question | | 800, whole passages (2026-10-02) | 450, chosen sentences (2026-10-03) |
|---|---|---|---|
| Symptoms of dehydration | Prompt tokens | 847 | 557 |
| | Prompt reading | 165.1 s | 89.8 s |
| | First word after asking | 180.7 s | 101.4 s |
| | Sources cited | none | [2], [1], [4] |
| How a refrigerator works | Prompt tokens | 927 | 595 |
| | Prompt reading | 142.4 s | 76.6 s |
| | First word after asking | 157.9 s | 94.3 s |
| | Sources cited | [1] | [1] |

Writing speed is unchanged (2.9 and 3.2 tok/s in both runs). The wait for the first word fell by 44% and 40%. The second question's search took 10.1 s against 7.6 s the day before; search time on this phone varies with what is in the page cache.

## Signed release APK on the Android emulator (2026-10-03)

The released `offline-research-0.6.0.apk` (v0.6.0, SHA-256 `a81ff0e0…e91d`) was installed on a Pixel 8 emulator image (Android 17 preview, API 37, x86_64 with ARM translation, 16 KB memory pages, 4 GB RAM) as a check that the signed build installs, starts and answers. Record: `docs/measurements/2026-10-03-release-apk-emulator.jsonl`; screen: `screens/2026-10-03-release-emulator-2.png`.

- Installed without error, started, and chose the low profile by itself ("auto, 4.1 GB RAM").
- With a test profile (Qwen3-1.7B as the answerer, keyword search, Wikivoyage index only, 96-token cap) it loaded the model through the JNI bridge, searched, and wrote an answer from three Wikivoyage sources.
- The answer has no citation markers. That is the 1.7B model standing in as answerer, cut off at 96 tokens; it is not a configuration the app ships.
- The timings (first word 104 s, 4.3 tok/s) are of ARM code translated on a PC and say nothing about phones.

Not covered by this check: the BigMoeOnEdge engine (a separate program, and its model does not fit the emulator), and a real phone.

## Signed release APK on the Redmi 12 5G (2026-10-03)

The released `offline-research-0.6.0.apk` replaced the debug build on the phone (the debug build had to be uninstalled first; models and index were moved aside on the phone and back). Record: `docs/measurements/2026-10-03-redmi12-release-apk.jsonl`; screen: `screens/2026-10-03-release-redmi-1.png`.

- Low profile, "What causes the tides?": model loaded in 3.9 s, 547 prompt tokens read in 96.5 s, first word 113 s after asking, 47 tokens at 3.3 tok/s, one correct citation. In line with the debug build.
- High profile: the BigMoeOnEdge engine started from the signed build and loaded the 12.3 GB model in about 26 s (log line "system info: BigMoeOnEdge, qwen35moe"). No question was asked in this check.
- The phone asked for a tap to allow the install over USB; without it `adb install` fails with `INSTALL_FAILED_USER_RESTRICTED`.
