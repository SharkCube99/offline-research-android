# Demo recording, post and claim

What the bounty asks for as proof, and ready text for each part. The bounty page is <https://poidh.xyz/mainnet/bounty/31>.

The bounty wants a public post on X or Farcaster that shows the app running offline, several questions with their answers (including ones a 1B model would fail on), a link to this repository and a short explanation of the approach. Then a claim on poidh with a screenshot and links to the post and the repository. The repository must hold the working version when the claim is made.

## Before recording

1. The phone has build 0.8.1 or newer, the models and all seven library files (`scripts/setup.sh`).
2. Charge it and let it cool; a warm phone answers more slowly.
3. Turn on airplane mode. Wi-Fi and Bluetooth off as well, so the status bar shows nothing but the airplane.
4. Do one dry run of every question below without recording. Swap out any question whose answer is poor; say nothing in the post that the recording does not show.
5. Stand near a window for the "near me" question, so the phone has a position.

## Recording

Use the phone's own screen recorder. One take per question is fine; they can be joined afterwards.

1. **Show it is offline.** Pull down the quick settings so the airplane icon is in view, then open the app. The status line names the model and the library files it found.
2. **Ask the questions.** Type each one, press Send, and leave the screen alone until the answer is finished. The search result appears within a few seconds; the answer follows.
3. **Open the sources.** After at least one answer, tap a `[1]` in the text and scroll the source passage.
4. **Show the warning.** One of the answers below should carry the red note for an unsourced part; leave it on screen for a moment.

On the Redmi the wait for the first word is about two minutes. Speed the waiting up in the edit and put the real time on screen ("2 min 05 s, shown at 8x"). Do not cut the wait out without saying so.

## Questions

| # | Question | What it shows | Tried |
|---|---|---|---|
| 1 | What is the emergency number in Thailand, and is a 5,000 mAh power bank at 3.85 V allowed on a plane? | two parts; a fact from the travel-facts file with a citation, the app's own conversion to 19.25 Wh, and the airline rule marked as unsourced | on the Redmi, 4B model, 2026-10-06 |
| 2 | Is there a pharmacy near me? | position to city on the phone, then a list of pharmacies with addresses | on the Redmi, 4B model, 2026-10-06 |
| 3 | What plugs and voltage does Brazil use, and what is 220 V good for? | a cited fact plus an explanation | on the emulator with the 1.7B model, 2026-10-06 |
| 4 | I just got bitten by a snake while hiking, two hours from the nearest road. What do I do right now? | a first-aid question answered from the first-aid book and Wikipedia | sources checked on the PC only; the answer itself has not been seen on a phone |
| 5 | A hike is 18 km with 1,200 m of total climbing. Using Naismith's rule, about how long will it take? | a rule looked up and then applied in steps | answered correctly by the 35B model in the benchmark; not tried with the 4B |

The bounty asks for questions a 1B model would fail on. No 1B model has been run on these, so the post should not claim it. Questions 1 and 5 are of the kind small models get wrong: two parts, and arithmetic that follows a looked-up rule.

## Text for the post

Main post (fits X's limit):

> Offline Research: an Android app that answers research questions in airplane mode, with sources.
>
> A small model plans the search, the app searches 24.6 GB of offline Wikipedia, Wikivoyage, places, first aid and more, and a larger model writes a cited answer. No network permission at all.
>
> Entry for the offline AI research bounty.
> https://github.com/SharkCube99/offline-research-android

Replies under it:

> How it works: Qwen3-1.7B writes the searches. SQLite full-text search finds passages and the app keeps only the sentences that matter. The answer comes from Qwen3-4B on small phones, or Qwen3.6-35B-A3B (2-bit, streamed from storage with BigMoeOnEdge) on 12 GB phones. Citations open the source passage.

> What is not from a source is labelled: the model may add what it knows, under a line that marks it as unsourced, and the app warns beside it. Unit conversions are computed by the app. "Near me" turns the phone's position into a city on the phone; nothing can be sent anywhere.

> Honest numbers, all logged in the repo: 45% of Claude Opus 5.5 with web search on 61 blind-graded questions (35B model, an earlier build). This video is a low-end 8 GB Redmi 12 5G with the 4B model: about two minutes to the first word. No 12 GB phone measured yet.

> Signed APK, one script to download the library and one to set up the phone: https://github.com/SharkCube99/offline-research-android/releases

Change the third reply if a newer benchmark result exists by then, and the fourth if the recording was made on another phone or model.

## Text for the claim

Title:

> Offline Research: cited answers in airplane mode, Android

Description:

> Demo: (link to the post)
> Repo: https://github.com/SharkCube99/offline-research-android
> Signed APK: https://github.com/SharkCube99/offline-research-android/releases
> Library: https://huggingface.co/datasets/SHARK787/offline-research-index
>
> Offline Research answers questions from a 24.6 GB library on the phone (Wikipedia, Wikivoyage, places by city, first aid, travel facts, Ethereum texts) and cites the passages it used. A 1.7B model plans the search; the answer is written by Qwen3-4B on small phones or Qwen3.6-35B-A3B at 2 bits, streamed from storage, on 12 GB phones. The app has no network permission and needs no Google Play Services. Parts of an answer that have no source are labelled as such. On 61 blind-graded questions the 35B model reached 45% of Claude Opus 5.5 with web search (an earlier build; later changes are not yet benchmarked). Tested on a Redmi 12 5G with 8 GB, where the 4B model takes about two minutes to the first word; no 12 GB phone has been measured. Storage: 28.9 GB with the 4B model, 38.7 GB with the 35B.

Screenshot: an answer on the phone with the airplane icon in the status bar, citations in the text and the Sources button visible.

## Submitting the claim

The bounty is on Ethereum mainnet. On the bounty page, connect a wallet, choose to create a claim, and enter the title, the description and the screenshot. Creating a claim is a transaction and costs a gas fee in ETH. Whether a claim can be edited afterwards is not stated on the page, so treat it as final and check every link first.
