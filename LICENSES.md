# Licences

Every model, dataset and library this project uses is recorded here. Nothing is added without an entry.

## This project

The app's own source code is released under the MIT licence (see `LICENSE`).

## Code

| Component | Version | Licence | Why it is here |
|---|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) | tag `b11311` (git submodule) | MIT | On-device inference engine |
| [BigMoeOnEdge](https://github.com/Helldez/BigMoeOnEdge) | commit `374f562` (git submodule) | Apache-2.0 | Engine for the high profile: streams a mixture-of-experts model's experts from flash. Built as a separate program by `scripts/build_engine.sh` |
| [Helldez/llama.cpp](https://github.com/Helldez/llama.cpp) | commit `dce9698` (submodule of BigMoeOnEdge) | MIT | The llama.cpp fork BigMoeOnEdge is built on; linked statically into that program |
| Android Gradle Plugin | 8.13.2 | Apache-2.0 | Build |
| Kotlin and the Compose compiler plugin | 2.2.20 | Apache-2.0 | Language and UI compiler |
| Jetpack Compose (BOM 2025.10.00: ui, material3) | per BOM | Apache-2.0 | UI toolkit required by CLAUDE.md |
| androidx.activity:activity-compose | 1.9.3 | Apache-2.0 | Hosts Compose in the activity |
| androidx.lifecycle (viewmodel-compose, runtime-compose) | 2.9.4 | Apache-2.0 | ViewModel and lifecycle-aware state collection |
| kotlinx-coroutines-android | 1.9.0 | Apache-2.0 | Streams tokens from the engine thread to the UI |
| kotlinx-serialization-json | 1.9.0 | Apache-2.0 | Parses model profiles and writes metrics lines; also runs in plain JVM unit tests, which Android's built-in `org.json` does not |
| [requery sqlite-android](https://github.com/requery/sqlite-android) | 3.49.0 (from JitPack) | Apache-2.0; bundles SQLite, which is public domain | Reads the knowledge index. The phone's own SQLite is not guaranteed to have FTS5, and its version differs between Android releases |
| JUnit | 4.13.2 | EPL-1.0 | Unit tests only; not shipped in the APK |
| [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc) | 3.53.4.0 | Apache-2.0 | Unit tests only: runs the retriever's SQL against a real FTS5 index on the build machine |

llama.cpp bundles a few third-party sources of its own under `third_party/llama.cpp/vendor/`. Only the core library (`llama`, `ggml`) is built into this app; `LLAMA_BUILD_COMMON` is off. The vendored components that actually end up in the APK will be audited before release (M6).

## Benchmark material

| Item | Licence | Source |
|---|---|---|
| `bench/vitalik61/questions.jsonl` | MIT (`bench/vitalik61/LICENSE-boar-questions.txt`) | Evaluation set v2 of <https://github.com/rferrari/boar-app>, as copied in <https://github.com/Phineas1500/AndroidLM> (`eval/questions_vitalik.jsonl`, commit `0edb11f`) |
| `bench/heldout40/questions.jsonl`, `bench/heldout40/reference_answers.jsonl` | MIT, as this repository | Written for this project on 2026-10-05 by two separate Claude agents (questions; reference answers with web search) |
| `bench/vitalik61/reference_answers.jsonl` | Apache-2.0 | <https://github.com/Phineas1500/AndroidLM> (`eval/answers_web_vitalik.jsonl`, commit `0edb11f`) |

## Models

Models are not stored in this repository or in the APK. They are pushed to the phone with `scripts/setup.sh`.

| Model | File | Size | Licence | Source | Used for |
|---|---|---|---|---|---|
| Qwen3-4B | `Qwen3-4B-Q4_K_M.gguf` | 2.5 GB | Apache-2.0 | <https://huggingface.co/Qwen/Qwen3-4B-GGUF> | Low tier and development (M1) |
| Qwen3.6-35B-A3B, 2-bit quantisation by Unsloth | `Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf` | 12.3 GB | Apache-2.0 | <https://huggingface.co/unsloth/Qwen3.6-35B-A3B-GGUF> (base model <https://huggingface.co/Qwen/Qwen3.6-35B-A3B>) | High tier (`profiles/high.json`); licence read from the Hugging Face API on 2026-10-03 |
| Qwen3-30B-A3B | `Qwen3-30B-A3B-Q4_K_M.gguf` | 18.6 GB | Apache-2.0 | <https://huggingface.co/Qwen/Qwen3-30B-A3B-GGUF> | Earlier high tier, kept as `profiles/high-llama.json` |
| EuroMoE-2.6B-A0.6B-Instruct-2512 (UTTER project), quantisation by mradermacher | `EuroMoE-2.6B-A0.6B-Instruct-2512.i1-Q4_K_M.gguf` | 1.62 GB | Apache-2.0 | <https://huggingface.co/mradermacher/EuroMoE-2.6B-A0.6B-Instruct-2512-i1-GGUF> (base model <https://huggingface.co/utter-project/EuroMoE-2.6B-A0.6B-Instruct-2512>) | Optional fast tier (`profiles/fast.json`): 0.6B parameters active per token |
| Qwen3-1.7B | `Qwen3-1.7B-Q8_0.gguf` | 1.83 GB | Apache-2.0 | <https://huggingface.co/Qwen/Qwen3-1.7B-GGUF> | Planner: writes search queries. Q8_0 is the only file in the official repository |

File names, sizes and licences were read from the Hugging Face repository pages on 2026-10-01.

## Data

Corpora are not stored in this repository or in the APK. `data-pipeline/build_index.py` downloads them and builds the index files, which are pushed to the phone over adb.

| Corpus | Source | Licence | Attribution |
|---|---|---|---|
| English Wikipedia (article text) | Wikimedia CirrusSearch index dump `enwiki_content`, <https://dumps.wikimedia.org/other/cirrus_search_index/> | CC BY-SA 4.0 (also GFDL) | Wikipedia contributors; each passage keeps its article title and URL |
| English Wikivoyage (article text) | Wikimedia CirrusSearch index dump `enwikivoyage_content`, same location | CC BY-SA 4.0 | Wikivoyage contributors; each passage keeps its article title and URL |

Two smaller source packs are built by their own scripts into the same index format:

| Pack | Source | Licence | Attribution |
|---|---|---|---|
| `ethereum.db` (`data-pipeline/build_ethereum.py`) | Ethereum Improvement Proposals <https://github.com/ethereum/EIPs>, ERCs <https://github.com/ethereum/ERCs>, consensus specifications <https://github.com/ethereum/consensus-specs> | CC0-1.0 | Ethereum contributors |
| | English pages of ethereum.org, <https://github.com/ethereum/ethereum-org-website> | MIT | ethereum.org contributors |
| | NIST FIPS 203, 204, 205 and SP 800-208 (PDF from nvlpubs.nist.gov) | US Government works, public domain in the United States | National Institute of Standards and Technology |
| `places.db` (`data-pipeline/build_places.py`) | OpenStreetMap, eating places tagged vegan or vegetarian, through the Overpass API | Open Database Licence (ODbL) 1.0; the built file is a derived database under the same licence | (c) OpenStreetMap contributors |
| | GeoNames `cities15000` and `countryInfo`, used to assign places to cities | CC BY 4.0 | GeoNames |
| `firstaid.db` (`data-pipeline/build_firstaid.py`) | Wikibooks, "First Aid", <https://en.wikibooks.org/wiki/First_Aid>, through the MediaWiki API | CC BY-SA 4.0; the built file is an adaptation under the same licence | Wikibooks contributors; each chapter keeps its title and URL |
| `travelfacts.db` (`data-pipeline/build_travel_facts.py`) | Wikidata query service: emergency numbers, plugs, voltage, driving side, currency, dialling code, capital, languages and time zones of sovereign states | CC0 1.0 | Wikidata contributors |
| `cityplaces.db` (`data-pipeline/fetch_overture_places.py`, `data-pipeline/build_places_overture.py`) | Overture Maps Foundation, places theme, release 2026-09-23.1, <https://overturemaps.org> | CDLA-Permissive-2.0 | Overture Maps Foundation and its data contributors (Meta, Microsoft, Foursquare and others) |
| | GeoNames `cities15000` and `countryInfo`, used to assign places to cities | CC BY 4.0 | GeoNames |

`app/src/main/assets/cities.tsv`, which ships inside the APK, is a list of city names, countries, positions and populations taken from GeoNames `cities15000` and `countryInfo` (CC BY 4.0, GeoNames, <https://www.geonames.org>), reduced to five columns by `data-pipeline/build_cities_asset.py`.

`fetch_overture_places.py` reads Overture's files with [duckdb](https://github.com/duckdb/duckdb) (MIT), which is needed on the build computer only and is not shipped.

`build_ethereum.py` reads the NIST PDFs with [pypdf](https://github.com/py-pdf/pypdf) (BSD-3-Clause), which is needed on the build computer only and is not shipped; without it the NIST texts are left out.

The built index files are hosted at <https://huggingface.co/datasets/SHARK787/offline-research-index> under CC BY-SA 4.0, with the attribution and the list of changes on the dataset's description page (`data-pipeline/dataset-card/README.md`).

Licences were read from <https://en.wikipedia.org/wiki/Wikipedia:Copyrights> and <https://en.wikivoyage.org/wiki/Wikivoyage:Copyleft> on 2026-10-01.

What CC BY-SA 4.0 requires of this project:

- **Attribution.** Every passage is stored with its article title and URL, and the app must show them with any answer that uses the passage (M3).
- **Share-alike.** The index files are an adaptation of the text (cleaned, split into passages) and are therefore themselves CC BY-SA 4.0. Each index file records its source, dump date and licence in its `meta` table.
- **Indicating changes.** The text is unmodified apart from whitespace normalisation and splitting into passages; `data-pipeline/README.md` describes the processing.

The data pipeline itself uses only the Python standard library (PSF licence) and is not shipped in the app.
