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
