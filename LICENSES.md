# Licences

Every model, dataset and library this project uses is recorded here. Nothing is added without an entry.

## This project

The app's own source code is released under the MIT licence (see `LICENSE`).

## Code

| Component | Version | Licence | Why it is here |
|---|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | tag `b11311` (git submodule) | MIT | On-device inference engine |
| Android Gradle Plugin | 8.13.2 | Apache-2.0 | Build |
| Kotlin and the Compose compiler plugin | 2.2.20 | Apache-2.0 | Language and UI compiler |
| Jetpack Compose (BOM 2025.10.00: ui, material3) | per BOM | Apache-2.0 | UI toolkit required by CLAUDE.md |
| androidx.activity:activity-compose | 1.9.3 | Apache-2.0 | Hosts Compose in the activity |
| androidx.lifecycle (viewmodel-compose, runtime-compose) | 2.9.4 | Apache-2.0 | ViewModel and lifecycle-aware state collection |
| kotlinx-coroutines-android | 1.9.0 | Apache-2.0 | Streams tokens from the engine thread to the UI |
| kotlinx-serialization-json | 1.9.0 | Apache-2.0 | Parses model profiles and writes metrics lines; also runs in plain JVM unit tests, which Android's built-in `org.json` does not |
| JUnit | 4.13.2 | EPL-1.0 | Unit tests only; not shipped in the APK |

llama.cpp bundles a few third-party sources of its own under `third_party/llama.cpp/vendor/`. Only the core library (`llama`, `ggml`) is built into this app; `LLAMA_BUILD_COMMON` is off. The vendored components that actually end up in the APK will be audited before release (M6).

## Models

Models are not stored in this repository or in the APK. They are pushed to the phone with `scripts/setup.sh`.

| Model | File | Size | Licence | Source | Used for |
|---|---|---|---|---|---|
| Qwen3-4B | `Qwen3-4B-Q4_K_M.gguf` | 2.5 GB | Apache-2.0 | <https://huggingface.co/Qwen/Qwen3-4B-GGUF> | Low tier and development (M1) |
| Qwen3-30B-A3B | `Qwen3-30B-A3B-Q4_K_M.gguf` | 18.6 GB | Apache-2.0 | <https://huggingface.co/Qwen/Qwen3-30B-A3B-GGUF> | High tier (planned, M4) |

File names, sizes and licences were read from the Hugging Face repository pages on 2026-10-01.

## Data

None yet. Offline corpora are added in M2, each with its licence.
