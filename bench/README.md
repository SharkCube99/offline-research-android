# bench

How well the app answers, measured against a frontier model with internet search.

## The 61-question set (`vitalik61/`)

The questions another entry to the same bounty, [AndroidLM](https://github.com/Phineas1500/AndroidLM), was measured on, so the two apps can be compared on the same questions against the same reference.

| File | What | From |
|---|---|---|
| `questions.jsonl` | 61 questions with a note on what a good answer holds: 20 restaurants, 20 Ethereum and post-quantum cryptography, 10 travel facts, 5 emergencies, 6 travel arithmetic | Evaluation set v2 of [rferrari/boar-app](https://github.com/rferrari/boar-app) (MIT, `LICENSE-boar-questions.txt`), as copied in AndroidLM `eval/questions_vitalik.jsonl` at commit `0edb11f` |
| `reference_answers.jsonl` | The reference: Claude Opus 5.5 answering with web search, with the pages it cited | AndroidLM `eval/answers_web_vitalik.jsonl` at commit `0edb11f` (Apache-2.0) |
| `answers_<device>_<profile>.jsonl` | What this app answered, written by `run_bench.py` | this repository |

Neither file was edited.

## Running it

The app, the models and the index must be on the phone (`scripts/setup.sh`).

```bash
python bench/run_bench.py --questions bench/vitalik61/questions.jsonl \
    --out bench/vitalik61/answers_redmi12_low.jsonl --profile low
```

Every question starts from a cold app start. The answer, its sources and its timings come from the app's own metrics log. A question the app did not answer is recorded as `timeout` or `died` with an empty answer. Running the command again continues where it stopped.

## What is known in advance to be missing

- The app has no places database and no location. The 20 restaurant questions can only be answered from Wikipedia and Wikivoyage text, and the three that say "the city I am currently in" or "near me" cannot be answered at all.
- The knowledge index holds the most-viewed 31% of English Wikipedia. Individual Ethereum improvement proposals are thinly covered there.

## Grading

```bash
python bench/make_pairs.py bench/vitalik61/questions.jsonl bench/vitalik61/answers_redmi12_low.jsonl     bench/vitalik61/reference_answers.jsonl pairs.json key.json
python bench/report.py grades.json key.json bench/vitalik61/answers_redmi12_low.jsonl
```

`make_pairs.py` writes the blind sheet: each question with the app's answer and the reference as A and B in a random order, and a key kept apart from it. The grader sees only the sheet and gives each answer a score from 0 to 10 for how well it serves the person asking, lists its factual errors, and says which answer is better. `report.py` joins grades and key into the table: the app's total score as a share of the reference's, per group and overall, with the app's own timings. A question the app failed to answer stays in the sheet as "(no answer)".

This is the method of AndroidLM's report on the same questions (blind pairs, 0 to 10, share of the reference's total), so the two results can be set side by side.

## Not done yet

Grading and the report. No score exists until the answers have been graded.
