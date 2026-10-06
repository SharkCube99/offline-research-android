# Offline Research

An Android app that answers research questions with no network connection. It searches an offline library on the phone, and a language model on the phone writes an answer that cites the passages it used.

It is an entry for the [offline AI research app bounty](https://poidh.xyz/mainnet/bounty/31). **Status: it works on a real phone, and it is below the bounty's bar on both quality and speed.** The measured state is under "Where it stands" below; nothing there is estimated.

## What it does

- **Answers with sources.** A small model (Qwen3-1.7B) turns the question into searches. The app searches its library with SQLite full-text search and picks the sentences that bear on the question. A larger model writes the answer with `[1]`-style citations that open the source passage.
- **Two model sizes, chosen by the phone's memory.** Under 10 GB of RAM: Qwen3-4B. From 10 GB: Qwen3.6-35B-A3B at 2 bits, a mixture-of-experts model larger than the phone's memory, streamed from storage by the [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge) engine. Changing the model is a change to a JSON profile, not to code.
- **A library of seven files** (24.6 GB): English Wikipedia (the most-viewed 31% of articles), all of Wikivoyage, everyday places by city, vegan and vegetarian restaurants, Ethereum and post-quantum cryptography texts, a first-aid book, and practical facts per country.
- **Says what has no source.** Where the library falls short the model may answer from what it knows, under a line that marks that part as unsourced, and the app shows a warning beside it.
- **Exact arithmetic for conversions.** Quantities in a question (fuel use, temperature, battery capacity, distance, weight) are converted by the app and given to the model as a source.
- **"Near me".** With the location permission, the phone's position becomes a city name on the phone and the question is answered for that city.
- **Offline by construction.** The app has no network permission (`scripts/verify_offline.sh` checks every build) and does not use Google Play Services. Models and the library reach the phone over USB. The location permission is asked for only when a question is about "near me", and with no network permission the position cannot leave the phone.

## Where it stands

Every figure here comes from a log in this repository; the files are named beside it.

| | Measured | Source |
|---|---|---|
| Quality, 35B model | 45% of Claude Opus 5.5 with web search, on 61 questions, graded blind (build 0.7.2) | `docs/BENCHMARK.md` |
| Quality, 4B model | 34% on the same questions (build 0.7.0) | `docs/BENCHMARK.md` |
| Speed on a Redmi 12 5G (8 GB), 4B model | first word after a median of 136 s; answer finished after 179 s | `docs/BENCHMARK.md` |
| Speed on the same phone, 35B model | first word after a median of 209 s; finished after 443 s | `docs/BENCHMARK.md` |
| Storage on the phone, 4B setup | 28.9 GB: library 24.6, models 4.3, app 0.03 | file sizes in "Put it on a phone" |
| Storage on the phone, 35B setup | 38.7 GB: library 24.6, models 14.1, app 0.03 | the same |
| Search quality | a relevant passage in the top 5 for 46 of 50 test questions and 24 of 25 held-out ones | `docs/KNOWLEDGE_INDEX.md` |

What this does not show:

- **No 12 GB phone has been measured.** The only phone used is a low-end 8 GB one. The 35B model needs a faster phone to be usable; on this one it is not.
- **Nothing after build 0.7.2 has been benchmarked.** Since then: fixes to search (in the 45% run the search planner was silent, by a defect since fixed), three more library files, unsourced answers under a label, conversions, and location. Each has been tried on single questions on the phone (`docs/PERFORMANCE.md`), which shows that it runs, not how good it is.
- **Known weak points:** restaurant lists come from map data and include places that have closed; unsourced parts of an answer can be wrong; a small model does not always use the unsourced label.

Other entries to the bounty report higher scores and faster answers on 12 GB phones.

## Quick start without building

A signed APK is attached to each [release](https://github.com/SharkCube99/offline-research-android/releases). You need a computer with `git`, `curl`, bash (Git Bash on Windows) and Android platform-tools (`adb`), and a phone with USB debugging on and about 32 GB free.

```bash
git clone https://github.com/SharkCube99/offline-research-android.git    # scripts only; no submodules needed
cd offline-research-android
scripts/fetch_index.sh                                                    # 24.6 GB knowledge index (seven files)
scripts/setup.sh --apk /path/to/offline-research-0.8.1.apk \
                 --model /path/to/Qwen3-4B-Q4_K_M.gguf \
                 --model /path/to/Qwen3-1.7B-Q8_0.gguf \
                 --index data-pipeline/work/index
```

Then unlock the phone, turn on airplane mode and ask a question.

The model files come from the pages listed under "Put it on a phone". For a 12 GB phone, also push `Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf` (12.3 GB, <https://huggingface.co/unsloth/Qwen3.6-35B-A3B-GGUF>); the app picks the high profile by itself when the phone reports 10 GB of RAM or more. The time this takes is mostly download and USB copy time.

These steps were followed from an empty folder on 2026-10-06 with the published 0.8.1 APK: clone 4 s, APK download 14 s, five of the seven library files (385 MB) 135 s, install and copy to a blank Android emulator 67 s, and a cited answer in airplane mode 131 s after asking. The two large files (Wikipedia, 22.6 GB, and the places file, 1.6 GB) were not downloaded again for that run, and the emulator, which has 4 GB of memory, was given the 1.7B model as answerer. Log, including two failed attempts caused by the emulator: `docs/measurements/2026-10-06-stranger-test.log`.

## Build it yourself

### What you need

- A computer with Android Studio (or the Android SDK plus JDK 17 or newer) and `git`.
- In Android Studio's SDK Manager, under **SDK Tools** with "Show Package Details" ticked:
  - **NDK (Side by side) 29.0.13113456**
  - **CMake 3.31.6**
- An arm64 Android phone running Android 9 or newer, with USB debugging turned on.
- The model files and the knowledge index listed under "Put it on a phone".

### Build

```bash
git -c core.longpaths=true clone --recurse-submodules --shallow-submodules https://github.com/SharkCube99/offline-research-android.git
cd offline-research-android
scripts/build_engine.sh      # only needed for the high profile (12 GB phones)
./gradlew assembleDebug
```

`scripts/build_engine.sh` builds the BigMoeOnEdge engine that the high profile uses and places it where the APK build finds it. Without it the app still builds and runs the low profile. `core.longpaths` matters on Windows, where some file names in the engine's source exceed the default path limit.

Gradle needs to know where the SDK is: set `ANDROID_HOME`, or create `local.properties` with `sdk.dir=...` (Android Studio does this for you). `JAVA_HOME` must point at a JDK 17 or newer; Android Studio's bundled one (`<Android Studio>/jbr`) works.

The first build compiles llama.cpp and takes several minutes. A clone of this repository into an empty folder, followed by exactly these steps, was timed on a Windows 11 laptop on 2026-10-03 (build 0.6.0): about 9 minutes to clone with submodules, 10 minutes for the engine and 12 minutes for the app and its unit tests (`docs/measurements/2026-10-03-clean-clone.log`).

## Put it on a phone

Three kinds of file go on the phone, all over adb. Every model and data source, with its licence, is listed in `LICENSES.md`.

| What | File | Where it comes from |
|---|---|---|
| Answerer model | `Qwen3-4B-Q4_K_M.gguf` (2.5 GB) | <https://huggingface.co/Qwen/Qwen3-4B-GGUF> |
| Planner model | `Qwen3-1.7B-Q8_0.gguf` (1.83 GB) | <https://huggingface.co/Qwen/Qwen3-1.7B-GGUF> |
| Optional fast answerer (0.6B active per token; its answers were poor in a first trial, see `docs/ARCHITECTURE.md`) | `EuroMoE-2.6B-A0.6B-Instruct-2512.i1-Q4_K_M.gguf` (1.62 GB) | <https://huggingface.co/mradermacher/EuroMoE-2.6B-A0.6B-Instruct-2512-i1-GGUF>; pick "Fast" in the app's Settings |
| Answerer for 12 GB phones | `Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf` (12.3 GB) | <https://huggingface.co/unsloth/Qwen3.6-35B-A3B-GGUF> |
| Knowledge index | `wikipedia.db`, `wikivoyage.db` (22.9 GB) | Downloaded by `scripts/fetch_index.sh` from <https://huggingface.co/datasets/SHARK787/offline-research-index>, or built from the Wikipedia dumps by `data-pipeline/build_index.py` (many hours; see `data-pipeline/README.md`) |
| Smaller packs | `cityplaces.db` (1.6 GB: pharmacies, hospitals, places to eat and sleep and more, by city), `ethereum.db`, `places.db`, `travelfacts.db`, `firstaid.db` (30 MB together) | Downloaded by the same script, or built by the `data-pipeline/build_*.py` scripts named in `LICENSES.md`. The app works without them; each adds answers to one kind of question |

```bash
scripts/fetch_index.sh       # downloads the index to data-pipeline/work/index and checks it
scripts/setup.sh --model /path/to/Qwen3-4B-Q4_K_M.gguf \
                 --model /path/to/Qwen3-1.7B-Q8_0.gguf \
                 --index data-pipeline/work/index
```

This installs the APK, pushes the files, checks each file's size on the device and starts the app. Files already on the phone are skipped. The app never downloads anything itself. On Windows, run the script from Git Bash.

The planner model is optional: without it the app searches with the question's own keywords. The index is required.

Then turn on airplane mode and ask a question. Citations like `[1]` in the answer are links; "Sources" under the answer lists the passages it was given.

### The same thing by hand

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.offlineresearch/.MainActivity
adb shell am force-stop app.offlineresearch
adb shell mkdir -p /sdcard/Android/data/app.offlineresearch/files/models
adb push Qwen3-4B-Q4_K_M.gguf /sdcard/Android/data/app.offlineresearch/files/models/
adb push Qwen3-1.7B-Q8_0.gguf /sdcard/Android/data/app.offlineresearch/files/models/
adb shell mkdir -p /sdcard/Android/data/app.offlineresearch/files/index
adb push data-pipeline/work/index/wikipedia.db /sdcard/Android/data/app.offlineresearch/files/index/
adb push data-pipeline/work/index/wikivoyage.db /sdcard/Android/data/app.offlineresearch/files/index/
adb shell am start -n app.offlineresearch/.MainActivity
```

The first `am start` makes Android create the app's files directory with the right owner. In Git Bash on Windows, put `MSYS_NO_PATHCONV=1` in front of any adb command that contains a `/sdcard/...` path, or Git Bash rewrites the path.

If `adb push` into `Android/data` is refused on your phone, use the fallback, which copies the model into the app's internal storage (debug builds only):

```bash
scripts/setup.sh --model /path/to/Qwen3-4B-Q4_K_M.gguf --index data-pipeline/work/index --internal
```

Xiaomi, Redmi and POCO phones usually also need **Install via USB** turned on in Developer options before `adb install` works. The phone then shows a prompt for each new install that has to be accepted within about ten seconds; if nobody taps it, the install fails with `INSTALL_FAILED_USER_RESTRICTED`.

## Check that it is offline

```bash
scripts/verify_offline.sh
```

This inspects every built APK and fails if it declares `INTERNET` or any other network permission.

## Read the measurements

Each answer logs one line: the question, the search queries, the sources, the answer, which sources it cited, and the timings.

```bash
adb logcat -s OfflineResearch LlamaBridge
adb pull /sdcard/Android/data/app.offlineresearch/files/logs/metrics.jsonl
```

To measure a configuration with the same three questions every time:

```bash
scripts/measure.sh --threads 2 --batch-threads 8 --affinity fastest
```

Results are saved under `docs/measurements/`.

## Change the model

Models are configuration, not code. A profile is a small JSON file (`profiles/low.json`, `profiles/high.json`) that names the model file and its settings. `"engine"` in the profile picks what runs the model: `"llama"` (llama.cpp, the default) or `"bmoe"` (BigMoeOnEdge, for mixture-of-experts models larger than RAM). To try another model, push the GGUF and a profile that names it:

```bash
scripts/setup.sh --no-install --model /path/to/other.gguf --profile my-profile.json
```

## Repository layout

| Path | Contents |
|---|---|
| `app/` | Android app (Kotlin, Jetpack Compose) and the JNI bridge in `app/src/main/cpp/` |
| `third_party/llama.cpp` | llama.cpp, a git submodule pinned to a release tag |
| `profiles/` | Model profiles |
| `scripts/` | `fetch_index.sh` (download the prebuilt index), `build_engine.sh` (build the high-profile engine), `setup.sh` (provision a phone), `measure.sh` (timed runs), `verify_offline.sh` (permission check) |
| `docs/` | Architecture decisions, performance log, benchmark notes |
| `third_party/BigMoeOnEdge` | The engine that streams the 35B model from storage, a git submodule |
| `data-pipeline/` | Builds the library: the Wikipedia and Wikivoyage index (see its README) and the five smaller files |
| `bench/` | The 61-question benchmark: questions, reference answers, the app's answers, blind grading sheets and scores; a second set of 40 questions not yet run |

## Licences

The app's own code is MIT-licensed. Third-party code, models and data are listed in `LICENSES.md`.
