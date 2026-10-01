# Offline Research

An Android app that answers research questions with no network connection, using a language model that runs on the phone.

**Status: Milestone 1 (foundation).** The app loads a GGUF model through llama.cpp and streams replies in a chat screen. There is no retrieval and there are no citations yet; those arrive in later milestones (see `CLAUDE.md`, Section 7).

There are no performance numbers in this README yet, because none have been measured. Measurements are logged to `docs/PERFORMANCE.md` as they are taken.

## What you need

- A computer with Android Studio (or the Android SDK plus JDK 17 or newer) and `git`.
- In Android Studio's SDK Manager, under **SDK Tools** with "Show Package Details" ticked:
  - **NDK (Side by side) 29.0.13113456**
  - **CMake 3.31.6**
- An arm64 Android phone running Android 9 or newer, with USB debugging turned on.
- A GGUF model file. The default profile expects `Qwen3-4B-Q4_K_M.gguf` (2.5 GB, Apache-2.0), from <https://huggingface.co/Qwen/Qwen3-4B-GGUF>.

## Build

```bash
git clone --recurse-submodules --shallow-submodules <this repo>
cd <this repo>
./gradlew assembleDebug
```

Gradle needs to know where the SDK is: set `ANDROID_HOME`, or create `local.properties` with `sdk.dir=...` (Android Studio does this for you). `JAVA_HOME` must point at a JDK 17 or newer; Android Studio's bundled one (`<Android Studio>/jbr`) works.

The first build compiles llama.cpp and takes several minutes.

## Put it on a phone

```bash
scripts/setup.sh --model /path/to/Qwen3-4B-Q4_K_M.gguf
```

This installs the APK, pushes the model over adb, checks the file size on the device and starts the app. The app never downloads anything itself. On Windows, run the script from Git Bash.

Then turn on airplane mode and ask a question.

### The same thing by hand

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n app.offlineresearch/.MainActivity
adb shell am force-stop app.offlineresearch
adb shell mkdir -p /sdcard/Android/data/app.offlineresearch/files/models
adb push Qwen3-4B-Q4_K_M.gguf /sdcard/Android/data/app.offlineresearch/files/models/
adb shell am start -n app.offlineresearch/.MainActivity
```

The first `am start` makes Android create the app's files directory with the right owner. In Git Bash on Windows, put `MSYS_NO_PATHCONV=1` in front of any adb command that contains a `/sdcard/...` path, or Git Bash rewrites the path.

If `adb push` into `Android/data` is refused on your phone, use the fallback, which copies the model into the app's internal storage (debug builds only):

```bash
scripts/setup.sh --model /path/to/Qwen3-4B-Q4_K_M.gguf --internal
```

Xiaomi, Redmi and POCO phones usually also need **Install via USB** turned on in Developer options before `adb install` works.

## Check that it is offline

```bash
scripts/verify_offline.sh
```

This inspects every built APK and fails if it declares `INTERNET` or any other network permission.

## Read the measurements

Each answer logs one line of metrics: model load time, time to first token and tokens per second.

```bash
adb logcat -s OfflineResearch LlamaBridge
adb pull /sdcard/Android/data/app.offlineresearch/files/logs/metrics.jsonl
```

## Change the model

Models are configuration, not code. A profile is a small JSON file (`profiles/low.json`, `profiles/high.json`) that names the model file and its settings. To try another model, push the GGUF and a profile that names it:

```bash
scripts/setup.sh --no-install --model /path/to/other.gguf --profile my-profile.json
```

## Repository layout

| Path | Contents |
|---|---|
| `app/` | Android app (Kotlin, Jetpack Compose) and the JNI bridge in `app/src/main/cpp/` |
| `third_party/llama.cpp` | llama.cpp, a git submodule pinned to a release tag |
| `profiles/` | Model profiles |
| `scripts/` | `setup.sh` (provision a phone), `verify_offline.sh` (permission check) |
| `docs/` | Architecture decisions, performance log, benchmark notes |
| `data-pipeline/`, `bench/` | Placeholders for later milestones |

## Licences

The app's own code is MIT-licensed. Third-party code, models and data are listed in `LICENSES.md`.
