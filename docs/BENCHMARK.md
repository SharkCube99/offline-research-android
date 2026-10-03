# Benchmark

How the app's answers compare with a frontier model that has internet search. Method, files and commands: `bench/README.md`. Every number here comes from the files under `bench/vitalik61/`.

## Low profile (Qwen3-4B) on the Redmi 12 5G, 2026-10-03

**The app reaches 26% of the reference. The bounty's bar is more than 50%. This configuration does not meet it.**

| Group | n | App (mean /10) | Reference | App as share of reference | Preferred app / reference / tie | Errors app / reference | Not answered | First word (median) | Done (median) |
|---|---|---|---|---|---|---|---|---|---|
| Restaurants | 20 | 1.25 | 8.88 | **14%** | 0 / 20 / 0 | 5 / 6 | 0 | 134 s | 165 s |
| Crypto | 20 | 2.67 | 9.97 | **27%** | 0 / 20 / 0 | 2 / 0 | 0 | 126 s | 161 s |
| Travel | 10 | 3.10 | 9.60 | **32%** | 0 / 10 / 0 | 3 / 0 | 0 | 138 s | 177 s |
| Emergencies | 5 | 3.60 | 9.50 | **38%** | 0 / 5 / 0 | 2 / 0 | 0 | 143 s | 185 s |
| Arithmetic | 6 | 3.92 | 10.00 | **39%** | 0 / 6 / 0 | 7 / 0 | 0 | 161 s | 224 s |
| All | 61 | 2.48 | 9.52 | **26%** | 0 / 61 / 0 | 19 / 6 | 0 | 134 s | 169 s |

- **Questions:** the 61 questions another entry to the bounty, AndroidLM, was measured on (20 restaurants, 20 Ethereum and post-quantum cryptography, 10 travel facts, 5 emergencies, 6 travel arithmetic).
- **Reference:** AndroidLM's published reference answers, written by Claude Opus 5.5 with web search.
- **The app:** signed build 0.6.0, low profile (Qwen3-4B answers, Qwen3-1.7B plans the search), Wikipedia and Wikivoyage index, each question from a cold start. All 61 were answered; none crashed or timed out. Airplane mode was switched on after the ninth question; the app has no network permission in any case.
- **Grading:** blind pairs in random order, three graders (restaurants, crypto, the rest), each a separate Claude instance that saw only its sheet and used web search to check facts. Scores are 0 to 10 for how well an answer serves the asker. The share is the app's total divided by the reference's total. Sheets, grades and key: `bench/vitalik61/grading_redmi12_low/`.
- **Timings** are of an 8 GB low-end phone and say nothing about a 12 GB phone.

For comparison, AndroidLM reports 64% overall on the same questions and reference (restaurants 68%, crypto 52%, travel 67%, emergencies 74%, arithmetic 82%), with its 35B model on a Pixel 8 Pro. Its graders were different instances with their own instructions, so the two figures are close in method but not identical.

## Where the app loses

The graders preferred the reference on all 61 questions; the app did not match it on any.

**Refusals (17 of 61, mean score 1.0).** Eight restaurant, eight crypto and one travel question got only "Not covered by the offline sources." The refusals are honest, and a grader gives an honest refusal about 1 point.
- Restaurants: the app has no places data and no location. Wikipedia and Wikivoyage name few vegan restaurants, and three questions ask about "the city I am currently in".
- Crypto: individual Ethereum proposals (EIP-7702, EIP-7251, EIP-4895, ERC-4337, ERC-4626) are not covered by the Wikipedia articles in the index. One refusal is a miss: for "Which signature algorithms are quantum resistant?" the article "Post-quantum cryptography" was retrieved and the model still declined.

**Answers that were attempted (44 of 61) reach 32% of the reference.** They are mostly correct in what they say but short: one or two sentences against a reference that gives the mechanism, the numbers and what to do.

**Wrong answers (19 verified errors against 6 for the reference).**
- Arithmetic is the worst: three of six calculations are wrong (fuel consumption multiplied where it should be divided, Naismith's rule at 300 m per hour, a currency conversion with a spurious extra dollar). A 4B model does not calculate reliably.
- A confident answer about the wrong subject: "MEV" was answered as the Mission Extension Vehicle spacecraft (0.5 points).
- Travel facts stated wrongly: Brazil's plug type and voltage, the Thai for "thank you".
- Restaurants: non-vegan restaurants offered for vegan questions (Seoul, Kyoto, Cape Town), and places outside the city.
- First aid: pressure on a snakebite given as general advice, and burn ointment suggested for a fresh scald.

## What this run does not show

- The high profile (Qwen3.6-35B-A3B), which is the configuration meant for the bounty's 12 GB phone, has not been benchmarked.
- The graders were language-model instances. They finished in one to two and a half minutes each with 10 to 30 tool calls, so their fact-checking was a sample, not exhaustive; no human has reviewed the grades.
- The graders could often tell the app's answers by their `[1]` citation marks and the refusal sentence, so the grading is blind in order only.
