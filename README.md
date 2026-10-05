# Offline Research

An Android app that answers research questions with no network connection, using a language model that runs on the phone.

**Status: work in progress.** The app answers questions on the phone with no network: a small model plans the search, the app searches an offline Wikipedia and Wikivoyage index, and a larger model writes an answer that cites its sources. So far it has run only on a low-end 8 GB phone (Redmi 12 5G): usably with a 4B model, where the first word takes about a minute and a half, and as a proof that it starts with the 12 GB mixture-of-experts model meant for 12 GB phones, which is far too slow on that phone. Nothing has been measured on a 12 GB phone yet.

This README quotes no performance numbers. Measured results, with their raw logs, are in `docs/PERFORMANCE.md` (speed on the phone), `docs/KNOWLEDGE_INDEX.md` (index size and search quality) and `docs/RAG.md` (cited answers on the phone).

## Quick start without building

A signed APK is attached to each [release](https://github.com/SharkCube99/offline-research-android/releases). You need a computer with `git`, `curl`, bash (Git Bash on Windows) and Android platform-tools (`adb`), and a phone with USB debugging on and about 32 GB free.

```bash
git clone https://github.com/SharkCube99/offline-research-android.git    # scripts only; no submodules needed
cd offline-research-android
scripts/fetch_index.sh                                                    # 24.6 GB knowledge index (seven files)
scripts/setup.sh --apk /path/to/offline-research-0.7.5.apk                  --model /path/to/Qwen3-4B-Q4_K_M.gguf                  --model /path/to/Qwen3-1.7B-Q8_0.gguf                  --index data-pipeline/work/index
```

The model files come from the pages listed under "Put it on a phone". For a 12 GB phone, also push `Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf` (12.3 GB, <https://huggingface.co/unsloth/Qwen3.6-35B-A3B-GGUF>); the app picks the high profile by itself when the phone reports 10 GB of RAM or more. The time this takes is mostly download and USB copy time.

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

The first build compiles llama.cpp and takes several minutes. A clone of this repository into an empty folder, followed by exactly these steps, was timed on a Windows 11 laptop on 2026-10-03: about 9 minutes to clone with submodules, 10 minutes for the engine and 12 minutes for the app and its 109 unit tests (`docs/measurements/2026-10-03-clean-clone.log`).

## Put it on a phone

Three kinds of file go on the phone, all over adb:

| What | File | Where it comes from |
|---|---|---|
| Answerer model | `Qwen3-4B-Q4_K_M.gguf` (2.5 GB) | <https://huggingface.co/Qwen/Qwen3-4B-GGUF> |
| Planner model | `Qwen3-1.7B-Q8_0.gguf` (1.83 GB) | <https://huggingface.co/Qwen/Qwen3-1.7B-GGUF> |
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
| `data-pipeline/` | Builds the offline Wikipedia and Wikivoyage index (see its README) |
| `bench/` | Placeholder for a later milestone |

## Licences

The app's own code is MIT-licensed. Third-party code, models and data are listed in `LICENSES.md`.
