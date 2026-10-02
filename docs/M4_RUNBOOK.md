# M4 runbook: the 30B model on a 12 GB phone

M4 is accepted when the 30B model answers 20 consecutive questions on a 12 GB phone with no crash and no out-of-memory kill, and the measurements are saved in `docs/PERFORMANCE.md`.

**This has not been run.** No 12 GB phone has been available. Everything below is the procedure; none of it is a result.

## What you need

- A 12 GB arm64 Android phone (Android 11 or newer, so the app can read why a process died) with about 45 GB free and USB debugging on.
- A USB cable to the build computer, and the phone charged or charging.
- On the computer, already present if you followed the README:

| File | Size | Where |
|---|---|---|
| `app-debug.apk` | 33 MB | `app/build/outputs/apk/debug/` after `./gradlew assembleDebug` |
| `Qwen3-30B-A3B-Q4_K_M.gguf` | 18.56 GB | <https://huggingface.co/Qwen/Qwen3-30B-A3B-GGUF> |
| `Qwen3-1.7B-Q8_0.gguf` | 1.83 GB | <https://huggingface.co/Qwen/Qwen3-1.7B-GGUF> |
| `wikipedia.db`, `wikivoyage.db` | 22.93 GB | `data-pipeline/work/index/`, built by `data-pipeline/build_index.py` |

Run every command from the repository root. On Windows use Git Bash.

## Steps

### 1. Check the phone is visible

```bash
adb devices
```

One line ending in `device`. If it says `unauthorized`, accept the prompt on the phone.

### 2. Install and push everything (about 25 minutes)

```bash
scripts/setup.sh --model /path/to/Qwen3-30B-A3B-Q4_K_M.gguf \
                 --model /path/to/Qwen3-1.7B-Q8_0.gguf \
                 --index data-pipeline/work/index
```

Each file's size is checked on the phone after it is pushed. Files already there are skipped, so the command can be rerun.

### 3. Check which profile the app picked

```bash
adb logcat -d -s OfflineResearch | grep "profile:"
```

On a 12 GB phone it should print `profile: high (auto, 11.x GB RAM)`. If it prints `low`, the phone reports less than 10 GB; force the high profile in step 5 with `--profile high`.

The first load of the 30B model can take a minute or more. The app's status line shows the model, the profile and why it was chosen. "Settings" under it lets you change the profile by hand and start the stress test from the phone.

### 4. Turn on airplane mode

On the phone. The app has no network permission either way; this is so the result is recorded with the radio off.

### 5. Run the stress test

```bash
scripts/stress.sh --profile high --label high-30b
```

It restarts the app, waits for the models to load, starts 20 questions in the app and watches. Leave the phone alone: screen on (the app keeps it on), not swiped away, not locked. Swiping the app out of the recent-apps list kills it and fails the run.

The script prints one line per answer and ends with a report. It exits 0 only if all 20 were answered and Android recorded no crash or kill.

How long it takes is unknown. On the 8 GB test phone with the 4B model one answer took four to five minutes.

### 6. Collect the results

The script writes four files under `docs/measurements/`:

| File | Contents |
|---|---|
| `<date>-<device>-stress-high-30b.md` | The report: PASS or FAIL, one row per question, medians |
| `...jsonl` | The raw metrics line for every answer |
| `...txt` | Model load lines, and memory and battery readings after every question |
| `...exits.jsonl` | Why Android says the app exited, if it did |

Commit all four.

### 7. Paste into `docs/PERFORMANCE.md`

Use the template in the last section of that file. The report's table and medians paste in as they are.

## If it fails

| What you see | Likely meaning | What to try |
|---|---|---|
| `the app could not load its models` | The model file is damaged or there is not enough memory to create the context | `adb logcat -d -s llama.cpp LlamaBridge \| tail -40` shows llama.cpp's own error. Rerun `setup.sh`; it re-checks file sizes |
| Report says `LOW_MEMORY` | Android killed the app for memory | Close other apps and reboot the phone, then rerun. If it repeats, lower `n_ctx` or `retrieval_budget_tokens` in a copy of `profiles/high.json` and push it with `scripts/setup.sh --no-install --profile my-high.json` |
| Report says `CRASH_NATIVE` | llama.cpp crashed | Save `adb logcat -d -b crash` with the results |
| `no answer within 1800s` | Too slow to finish a question in 30 minutes | Rerun with `--answer-timeout 7200` to find out how slow, and record it. This is still a failed acceptance |
| `USER_REQUESTED` exit in the report | The app was swiped away or force-stopped | Not a model failure. Rerun and leave the phone alone |

Whatever happens, keep the files: a failed run is a measurement too.

## What is and is not known

Run on an 8 GB Redmi 12 5G (see `docs/PERFORMANCE.md`): profile selection, the overlay, the exit log, and the stress mode with the 4B model.

Not run anywhere: the 30B model on a 12 GB phone. Unknown until then: whether it loads, how long loading takes, how much memory it holds, how many page faults an answer costs, prefill and generation speed, whether the phone throttles, and whether 20 questions complete. The thread settings in `profiles/high.json` are untuned defaults (`n_threads` 0, which lets the engine pick at most 4 threads, not pinned).
