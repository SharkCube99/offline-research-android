# M5 plan: speed and quality of the high profile on a 12 GB phone

Status (2026-10-14): **nothing in this file has been measured.** It is the procedure, the decision rules and a design. Every result cell is empty until `scripts/m5_run.sh` has been run on the phone and its files are committed. Do not fill in a cell that has no file behind it.

## 0. What M5 needs first

CLAUDE.md accepts M5 when "time to first token and tokens per second improve over the M4 baseline". There is no M4 baseline: `docs/PERFORMANCE.md` records M4 as not measured because no 12 GB phone has run the app.

So the first step of M5 is the baseline, taken on the unmodified code and profile:

```bash
scripts/stress.sh --profile high --label high-35b      # M4: 20 questions, no kill (docs/M4_RUNBOOK.md)
scripts/m5_run.sh --label m4-baseline --cold           # M5 baseline: fixed questions, 3 cold starts
```

Until both exist, any "improvement" can only be measured against the other runs in this file, and has to be labelled that way.

What is known from other hardware, for orientation only: on the 8 GB Redmi, build 0.8.3, the 35B model answered five questions with the first word after a median of 227 s (`docs/PERFORMANCE.md`, "The same five questions with the 35B model"). In the one answer saved in `docs/measurements/2026-10-07-redmi12-high-35b.jsonl`, reading the 766-token prompt took 151.8 s of the 153.4 s to the first word (5.0 tokens/s), and writing ran at 1.0 token/s with 16,624 major page faults. That phone is below the memory this profile is meant for, so the shape (reading dominates the wait) is a hint, not a result.

## 1. Rules for every comparison

1. **One change at a time.** Each run differs from its baseline by one `--set` (or one file state).
2. **Same phone, same state.** Airplane mode on, battery above 50% and not charging, screen on, no other apps, phone rested 10 minutes between runs (the Redmi lost about 20% reading speed warming from 35 to 40 °C; `docs/PERFORMANCE.md`).
3. **A, B, A.** Run the baseline, the change, then the baseline again. If the two baselines differ by more than the change being claimed, the comparison is void; let the phone cool and repeat.
4. **Same questions.** `bench/m5/questions.txt`, 10 questions, 3 rounds, `--max-tokens 128`. Do not edit the file between runs.
5. **The verdict comes from the script.** `scripts/m5_compare.py` says "better" or "worse" only for a change of at least 10% in the median with at least 75% of pairs moving the same way. Everything else is "no clear change", and is reported as such.
6. **Failures are results.** Keep the files of a run that did not help, with the reason, in the table in section 7.

Command shape, used by every task below:

```bash
scripts/m5_run.sh --label <name> [--set KEY=JSON ...] [--cold]
python scripts/m5_compare.py docs/measurements/<date>-<device>-m5-<before>.jsonl \
                             docs/measurements/<date>-<device>-m5-<after>.jsonl --count 10
```

`m5_run.sh` pushes a copy of `profiles/high.json` with the overrides applied, restarts the app for each round, asks the questions over adb, and saves the app's own metrics lines, the memory, battery and thermal readings, and the exact profile that ran.

## 2. Task 1: reuse of the saved start of the prompt (prefix caching)

**State of the code.** The answerer keeps the tokens a new prompt shares with the previous one (measured on the 4B: 171 of 927 prompt tokens reused, `docs/PERFORMANCE.md`). Since 0.8.7 the model's state after reading the fixed answer rules is saved to the app's cache directory as `rules-<digest>.state` and restored at the next start; 0.8.8 made this work with Ling. Nothing about it has been measured on the 35B engine.

There is no profile switch for it, so the before and after are two states of the file, not two builds:

| | Before | After |
|---|---|---|
| State | `rules-*.state` deleted at the start of each round | file present from an earlier round |
| Command | `scripts/m5_run.sh --label m5-prefix-cold --cold` | `scripts/m5_run.sh --label m5-prefix-warm` (run it after the cold run, same build) |
| Reads | `prompt_reused` and `ttft_ms` of the **first question after start** | same |

`--cold` needs a debuggable build for `adb run-as`; without one, delete the app's cache from Settings instead and say so in the table.

What a result means: if `prompt_reused` on the first question stays 0 on the high profile, the saved state is not being used by the BigMoeOnEdge engine (it implements `InferenceEngine` separately from `LlamaEngine`) and the work is to make it so, not to tune it.

| Run | First question: reused tokens | First question: time to first word | Later questions: reused | Files |
|---|---|---|---|---|
| cold | | | | |
| warm | | | | |

## 3. Task 2: reranking, fewer and better passages

**State of the code.** An optional reranking model sits between search and source selection (build 0.8.9, `profiles/fast-rerank.json`: 20 candidates, keep 4, jina-reranker-v1-turbo-en Q8_0, 41.7 MB). It is marked experimental and no before/after exists. The reranker is not in `high.json`.

Reranking trades two things, so it needs two measurements:

**Speed** (is the prompt shorter, and does the saving exceed the reranker's own time?):

```bash
scripts/m5_run.sh --label m5-rerank-off                       # baseline
scripts/m5_run.sh --label m5-rerank-on \
    --set reranker='{"model_file":"jina-reranker-v1-turbo-en-Q8_0.gguf","candidates":20,"keep":4}' \
    --set source_passages=4
```

The reranker file must be on the phone first (`scripts/setup.sh --model /path/to/jina-reranker-v1-turbo-en-Q8_0.gguf`). Read `prompt_tokens`, `prompt_ms` and `ttft_ms`. The reranker's own time is inside `ttft_ms`, so a shorter prompt that is not a shorter wait is a "no".

**Quality** (does it still answer as well?). Speed alone cannot accept this change. Use the held-out set, which the app has never been tuned on:

```bash
python bench/run_bench.py --questions bench/heldout40/questions.jsonl \
    --out bench/heldout40/answers_<device>_high_rerank_off.jsonl --profile high
# the same with the reranker profile, then grade both blind: bench/make_pairs.py, bench/report.py
```

Accept only if time to first word improves by the rule in section 1 **and** the blind score on the held-out set does not fall by more than the grader spread: `docs/BENCHMARK.md` records different grader instances differing by up to ten points on the same answers, so a change smaller than that is "no clear change".

| Run | Prompt tokens | Time to first word | Held-out score | Files |
|---|---|---|---|---|
| rerank off | | | | |
| rerank on | | | | |

## 4. Task 3: context budget, threads, batch size

One parameter per row, each against the baseline. Values below are what to try, not results. Current `high.json`: `retrieval_budget_tokens` 450, `source_passages` 10, `n_threads` 5, `n_batch` 512, `--io-threads 2`.

| Parameter | Tried values | Command (example) | Reads |
|---|---|---|---|
| Context budget | 450 (now), 300, 200 | `--set retrieval_budget_tokens=300` | `prompt_tokens`, `ttft_ms`, and quality (held-out) |
| Passages | 10 (now), 6, 4 | `--set source_passages=6` | same |
| Compute threads | 4, 5 (now), 6 | `--set n_threads=4` | `write_speed`, `read_speed` |
| Batch size | 128, 256, 512 (now) | `--set n_batch=256` | `read_speed` (on the 4B it made no difference) |
| I/O threads | 2 (now), 4 | `--set engine_args='[... "--io-threads","4" ...]'` | `write_speed`, `major_faults` |
| Pinning | none (now), fastest | `--set thread_affinity='"fastest"'` | `write_speed` (on the 4B pinning doubled it) |

`engine_args` replaces the whole list; copy the current list from `profiles/high.json` and change only the one value. Context budget and passage count change what the model is shown, so each needs the held-out quality check from section 3 before it is accepted.

Record every row, including the ones that did nothing:

| Parameter | Value | Verdict from `m5_compare.py` | Files |
|---|---|---|---|
| | | | |

## 5. Task 4: expert cache or prefetch, only if flash is the bottleneck

### The gate

The task is conditional. It starts only if measurement shows generation is limited by reading experts from flash. The test, on the 12 GB phone with the baseline profile:

- **Flash-bound** if, over steady-state tokens (after the first 10), the engine's `stall_ms / wall_ms` is at least 0.3 and `cache_hit_pct` is below 70. These two thresholds are our choice, not the engine's; they are written down here so they cannot move after the result is seen.
- **Compute-bound** otherwise. The engine's own documentation calls that "the expected success case": streaming has hidden most of the I/O.

**The app does not record these today.** `BmoeProtocol` parses `BMOE_PROGRESS` lines, but `metrics.jsonl` keeps only `major_faults` and the speeds. `m5_run.sh` saves any `BMOE_`, `cache_hit` or `stall_ms` lines found in logcat to `.bmoe.txt`; if that file is empty, the gate has to be read either from the engine's own command line run on the phone (`third_party/BigMoeOnEdge/README.md`, around line 459, run with `--progress`: per-token lines carry `stall_ms`, `wall_ms` and `cache_hit_pct` as documented in `docs/telemetry.md`, and the `# summary` line prints `token_demand_MiB`, `docs/cache-sizing.md`), or by adding the totals to the app's metrics line. The second is a small app change that has not been made.

What the existing numbers already suggest, to be confirmed: reading the prompt was 99% of the wait in the one saved 35B answer, and an expert cache affects only writing. If that holds on the 12 GB phone, tasks 2 and 3 are worth more than this one, and the honest result of task 4 is "not needed".

### The design if the gate says flash-bound

**Do not build a second cache.** The BigMoeOnEdge documentation (`third_party/BigMoeOnEdge/docs/`: `cache-sizing.md`, `prefetch.md`, `moe-streaming.md`, `telemetry.md`) show the engine already reads exactly the routed experts, keeps them in an LRU cache sized by `--cache-mb` (`auto` or a fixed MiB value), and has optional speculative reads (`--prefetch K`, `--predict-prefetch`). A cache of our own in the app would sit in a different process from the engine and could not see its reads. The prototype is therefore configuration plus a guard, behind the interface that already exists:

1. **Profile keys, no code.** `engine_args` in the profile is passed through `BmoeEngine`, so cache size and prefetch are selected by the profile alone: `"--cache-mb","1500"`, `"--prefetch","1"`. Swapping them is a config change, as CLAUDE.md requires.
2. **A sweep, not a guess.** Run `m5_run.sh` once per value, `--cache-mb` in `auto, 1000, 1500, 2000, 3000`, then `--prefetch 1` with the best size. The engine's stated default for the app is 2000.
3. **Two documented hazards to check, not assume:**
   - *The cliff.* A budget below one token cycle (`token_demand_MiB` for this model at its top-k) does not degrade gracefully: the hit rate is exactly 0%, the same reads as no cache plus the cost of managing it. Each candidate budget must be compared with `token_demand_MiB` from a previous run of the same model.
   - *Memory kill.* Too large a cache plus the resident model drives `MemAvailable` to zero and Android's low-memory killer ends the process. Every cache run must also pass `scripts/stress.sh` (20 questions, no kill), not just show a speed-up.
4. **Prefetch is not assumed to help.** The engine measured previous-token prediction at 18 to 38% accuracy and a 2x slowdown on one model. It has not been measured on this one.
5. **Optional by construction.** The default stays whatever `high.json` ships. A value is promoted into the profile only after it improves `write_speed` by the section 1 rule, passes the 20-question stress run, and leaves `mem_avail` no lower than the baseline's.

| Run | `--cache-mb` | `--prefetch` | Hit % | Stall share | Writing speed | 20-question stress | Files |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

Expert dropping and substitution (`--drop-cold-experts`, `--expert-substitute`) change the model's answers and are out of scope here: they trade quality for speed and would need the full benchmark, not this procedure.

## 6. Task 5: tune the prompts on the model that ships

CLAUDE.md requires prompts tuned on the high-tier model, not the 4B. The 35B has had one round (45% to 54% on the 61 questions; `docs/BENCHMARK.md` says the overall margin over 50% is inside the graders' spread, and that the held-out 40 have not been run).

Procedure, to avoid tuning on the test:

1. Make one change to the answer rules (the rules text is in the app source; `ui/ChatViewModel.kt` derives the name of the saved prompt-start file from it, so the file is replaced automatically).
2. Run `bench/vitalik61` with the high profile, grade blind (`bench/make_pairs.py`, `bench/report.py`). This is the **tuning set**; faults found here may be fixed.
3. Run `bench/heldout40` once with the final rules. This is the **only** score to report. Do not edit anything after seeing it.
4. Record both scores and the grader spread. A change inside the spread is "no clear change".

Open faults from the 0.8.4 run are listed in `docs/BENCHMARK.md`; several were addressed in 0.8.5 and have not been re-graded.

| Rules version | 61-question score | Held-out 40 score | Spread | Files |
|---|---|---|---|---|
| | | | | |

## 7. Report

At the end of M5 this table is the deliverable. A row with "no clear change" or "worse" stays in it.

| Change | Helped? | Evidence (files) |
|---|---|---|
| Saved prompt start (task 1) | | |
| Reranking (task 2) | | |
| Context budget | | |
| Passage count | | |
| Compute threads | | |
| Batch size | | |
| I/O threads | | |
| Thread pinning | | |
| Expert cache size / prefetch (task 4, if the gate passed) | | |
| Prompt rules on the 35B (task 5) | | |

M5 stops here. M6 (the 100-question benchmark and release) does not start until this table is filled from measurements.
