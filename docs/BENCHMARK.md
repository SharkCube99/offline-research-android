# Benchmark

How the app's answers compare with a frontier model that has internet search. Method, files and commands: `bench/README.md`. Every number here comes from the files under `bench/vitalik61/`.

## Summary

| Run | Build | Model | Sources | App as share of reference |
|---|---|---|---|---|
| 2026-10-03 | 0.6.0 | Qwen3-4B | Wikipedia, Wikivoyage | 26% |
| 2026-10-04 | 0.7.0 | Qwen3-4B | plus the Ethereum and places packs | 34% |
| 2026-10-04 | 0.7.2 | Qwen3.6-35B-A3B (2-bit) | the same packs; answer rules reworded | 45% |

The bounty's bar is more than 50%. No run meets it yet. The 35B run is the configuration meant for a 12 GB phone; its answers were produced on the 8 GB Redmi, so its quality figures stand and its timings do not carry over.

The two runs were graded on different days by different grader instances. Shares from different graders can differ by 10 points or more, so the 8-point difference overall is suggestive, not proof; the larger changes in the two groups the packs target (restaurants 14% to 31%, crypto 27% to 40%) go with a fall in flat refusals from 17 to 7.

## High profile (Qwen3.6-35B-A3B, BigMoeOnEdge), 2026-10-04

**The app reaches 45% of the reference: closer, and still under the bar.**

| Group | n | App (mean /10) | Reference | App as share of reference | Preferred app / reference / tie | Errors app / reference | Not answered | First word (median) | Done (median) |
|---|---|---|---|---|---|---|---|---|---|
| Restaurants | 20 | 3.85 | 8.80 | **44%** | 0 / 20 / 0 | 20 / 8 | 0 | 209 s | 571 s |
| Crypto | 20 | 4.85 | 9.93 | **49%** | 0 / 20 / 0 | 3 / 0 | 0 | 209 s | 419 s |
| Travel | 10 | 4.00 | 9.55 | **42%** | 0 / 10 / 0 | 2 / 0 | 0 | 197 s | 357 s |
| Emergencies | 5 | 1.20 | 9.50 | **13%** | 0 / 5 / 0 | 0 / 0 | 0 | 211 s | 327 s |
| Arithmetic | 6 | 7.08 | 10.00 | **71%** | 0 / 5 / 1 | 0 / 0 | 0 | 256 s | 444 s |
| All | 61 | 4.30 | 9.47 | **45%** | 0 / 60 / 1 | 25 / 8 | 0 | 209 s | 443 s |

- Same questions, reference, phone and grading method. Build 0.7.2, high profile, both packs, airplane mode on; all 61 answered with no crash or kill on an 8 GB phone. The run was paused twice by the user and resumed; each question still started from a cold app start. Files: `bench/vitalik61/answers_redmi12_high_v072.jsonl` and `bench/vitalik61/grading_redmi12_high_v072/`.
- **Build 0.7.2 differs from 0.7.0 in the answer rules as well as the model.** Under the old rules the 35B model answered "best vegan restaurants in Berlin" with a refusal, because the list has no ratings. The rules now lead with giving the most helpful answer the sources allow and say what to do with an unranked list.
- **Against the 4B run of the same day:** higher on 30 questions, lower on 9, about the same on 22. Answers are about twice as long (median 874 characters against 457).
- **Arithmetic (71%)** is where the larger model helps most: no verified errors, against nine for the 4B model, and the only tie with the reference.
- **Crypto (49%)** and **travel (42%)** improved. Three crypto errors remain, all misreadings of the proposal texts.
- **Restaurants (44%).** Seventeen questions get six or so named places with addresses. Twenty verified errors, nearly all of one kind: places that have closed and are still on the map (Lisbon, Tokyo, Bangkok, Istanbul, Taipei, Seoul, Prague, Kyoto, Medellín, São Paulo), plus the Starbucks tagged as vegan in Buenos Aires. The grader of this run checked far more places than the earlier ones (121 tool calls against 38), so the error count is not comparable with the 4B run's six.
- **A defect in this run, found afterwards:** the search planner produced no searches for any of the 61 questions (`planner_fallback` is true in every record). The high profile sets an empty `prompt_suffix` for its answerer, and the planner was given the same empty suffix; without `/no_think` the small Qwen3 planner spent its tokens thinking and returned nothing, so the app searched with the question's own words only, and wasted about 22 s per question doing so. Both 4B runs had a working planner. The 45% is therefore measured with a handicap, and is a lower bound for this configuration. Fixed in 0.7.3; not yet re-measured.
- **Emergencies (13%) got worse, and the cause is search, not the model.** For the snakebite question the sources were the articles "Snake" and "Hiking"; for the scald, "Boiling" and a criminal case; for hypothermia, "Shivering". None holds first-aid advice. The 35B model says so and declines. The 4B model, given equally poor sources, answered from its own memory and scored 3 to 5 points for it. The same pattern explains the low travel answers (Thailand's emergency numbers, Lisbon airport) and three crypto answers: the right article was not retrieved, and the model would not guess.
- **Refusals:** four in the fixed wording (the three location questions and the earthquake question) and about ten more written out in sentences ("the provided sources do not contain…"). Answers that were attempted reach 48% of the reference.
- The reference was preferred on 60 questions; one arithmetic answer tied.
- **Timings on the Redmi:** first word after a median of 209 s, finished after 443 s. A 12 GB phone has not been measured.

## Low profile with the Ethereum and places packs, 2026-10-04

| Group | n | App (mean /10) | Reference | App as share of reference | Preferred app / reference / tie | Errors app / reference | Not answered | First word (median) | Done (median) |
|---|---|---|---|---|---|---|---|---|---|
| Restaurants | 20 | 2.70 | 8.75 | **31%** | 0 / 20 / 0 | 6 / 5 | 0 | 139 s | 184 s |
| Crypto | 20 | 4.00 | 9.97 | **40%** | 0 / 20 / 0 | 4 / 0 | 0 | 127 s | 177 s |
| Travel | 10 | 2.50 | 9.40 | **27%** | 0 / 10 / 0 | 3 / 1 | 0 | 133 s | 171 s |
| Emergencies | 5 | 3.40 | 9.50 | **36%** | 0 / 5 / 0 | 1 / 0 | 0 | 132 s | 174 s |
| Arithmetic | 6 | 3.75 | 10.00 | **38%** | 0 / 6 / 0 | 9 / 0 | 0 | 159 s | 214 s |
| All | 61 | 3.25 | 9.44 | **34%** | 0 / 61 / 0 | 23 / 6 | 0 | 136 s | 179 s |

- Same questions, reference, phone, model and grading method as the first run below; build 0.7.0 with `ethereum.db` and `places.db` on the phone; airplane mode on throughout; all 61 answered. Files: `bench/vitalik61/answers_redmi12_low_v070.jsonl` and `bench/vitalik61/grading_redmi12_low_v070/`.
- **Refusals fell from 17 to 7:** the three "city I am currently in" questions (the app has no location), two crypto questions (ML-DSA against SLH-DSA; finality in proof of stake) and two travel questions (Thailand's emergency numbers, which the first run answered poorly; Lisbon airport transport).
- **Per question against the first run:** the app scored higher on 28, lower on 9 and about the same on 24.
- **Restaurants (31%).** Real places with addresses are now named for 17 of 20 questions. They still lose heavily: the 4B model names three to seven places and says little about them, where the reference describes ten. Six verified errors come from the map data and how the list is built: a Starbucks tagged as fully vegan in Buenos Aires; vegetarian or ordinary restaurants presented as vegan (Singapore, Istanbul); two closed places in Bangkok.
- **Crypto (40%).** Answers on individual proposals now draw on the proposal texts. One answer got worse and is wrong: it says Ethereum's consensus layer does not use BLS signatures (0.5 points).
- **Travel (27%), emergencies (36%), arithmetic (38%)** did not improve; the packs do not touch them. Arithmetic is still wrong in three of six (nine verified errors), and the Thai "thank you" answer is wrong in three ways.
- **Errors:** 23 verified for the app against 6 for the reference (19 against 6 in the first run). Fewer refusals mean more statements, and a 4B model makes mistakes in them.
- The reference was preferred on all 61 questions again.

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
