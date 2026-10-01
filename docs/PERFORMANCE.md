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

**Not measured yet.** No device has run the app. This section is filled in from the first `metrics.jsonl` pulled from the Redmi.

| Metric | Value | Source file |
|---|---|---|
| Model load time | not measured | |
| Time to first token | not measured | |
| Tokens per second | not measured | |
| CPU features reported by llama.cpp | not measured | |
