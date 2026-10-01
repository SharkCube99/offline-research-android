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
