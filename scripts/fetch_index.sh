#!/usr/bin/env bash
# Downloads the prebuilt knowledge index (24.6 GB) to this computer, so it does
# not have to be rebuilt from the Wikipedia dumps. Push it to the phone
# afterwards with scripts/setup.sh --index DIR. The app itself never downloads
# anything.
#
# Usage:
#   scripts/fetch_index.sh [DIR] [FILE...]
#
#   DIR    where to put the files (default: data-pipeline/work/index)
#   FILE   which files to fetch (default: all of them)
#
#   wikipedia.db    22.57 GB  English Wikipedia, the most-viewed 31% of articles
#   wikivoyage.db    0.36 GB  English Wikivoyage, complete
#   cityplaces.db    1.61 GB  everyday places by city (Overture Maps)
#   ethereum.db      14 MB    Ethereum proposals and specifications, NIST standards
#   places.db        14 MB    vegan and vegetarian places to eat (OpenStreetMap)
#   travelfacts.db   1.2 MB   practical facts per country (Wikidata)
#   firstaid.db      0.3 MB   the Wikibooks "First Aid" book
#
# The app works with any subset; wikipedia.db and wikivoyage.db are the core.
# An interrupted download continues where it stopped when the script is run
# again. Each file is checked against its SHA-256 afterwards.
#
# Source: https://huggingface.co/datasets/SHARK787/offline-research-index
# (licences per file are listed there and in LICENSES.md).
set -euo pipefail

BASE="https://huggingface.co/datasets/SHARK787/offline-research-index/resolve/main"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE=(wikipedia.db wikivoyage.db)
PACKS=(cityplaces.db ethereum.db places.db travelfacts.db firstaid.db)

die() { echo "error: $*" >&2; exit 1; }

# name -> size in bytes, SHA-256
expected() {
    case "$1" in
        wikipedia.db)   echo "22570754048 cca60646f543deef8b62949d40e004d05fb2fbad7b1332736d5df876a1f1ee2a" ;;
        wikivoyage.db)  echo "355127296 b4bfa0b61da4588e59cbf9c42726c1d3694c7102cb2d49f75ecabcfb3daadc2b" ;;
        cityplaces.db)  echo "1613914112 d3e7229037c8e857dd0d8b62bae847dc43e187fdb23c60898a614670e78fac38" ;;
        ethereum.db)    echo "14196736 c3870a3690b3e1ddc432136feb39a3b74d5caf6ae1c2b00d9ff03d589dc5ec85" ;;
        places.db)      echo "13869056 f6aebc2d25923a6558d57128342e547a9e52a794d3ce89d2f43ffc107e616137" ;;
        travelfacts.db) echo "1204224 126bbb4a4bfc000d7a2fd1dd2a9522cc1302132fe3f6d9100896872a3b242a8d" ;;
        firstaid.db)    echo "331776 ae775ced55e0cc9f9e7dcef65f06f771a26268a102abc2e71bd149ec7ebd6eb4" ;;
        *) die "unknown file: $1" ;;
    esac
}

is_core() { case " ${CORE[*]} " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

case "${1:-}" in
    -h|--help) sed -n '2,26p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
esac

DEST="${1:-$REPO_ROOT/data-pipeline/work/index}"
[ $# -gt 0 ] && shift
FILES=("$@")
[ ${#FILES[@]} -gt 0 ] || FILES=("${CORE[@]}" "${PACKS[@]}")

command -v curl > /dev/null || die "curl not found"
if command -v sha256sum > /dev/null; then SHA="sha256sum"; else SHA="shasum -a 256"; fi
mkdir -p "$DEST"

size_of() { wc -c < "$1" | tr -d ' '; }

SKIPPED=()
for name in "${FILES[@]}"; do
    read -r bytes sha <<< "$(expected "$name")"
    target="$DEST/$name"
    if [ -f "$target" ] && [ "$(size_of "$target")" = "$bytes" ]; then
        echo "$name: already here"
    else
        echo "$name: downloading $bytes bytes"
        # -C - continues a partial file; --fail keeps an error page from being saved as data.
        if ! curl -L --fail --retry 5 --retry-delay 5 -C - -o "$target.part" "$BASE/$name"; then
            # The app answers without a smaller pack, so one that cannot be fetched is not fatal.
            is_core "$name" && die "$name: download failed. Run the script again to continue."
            echo "warning: $name could not be downloaded; continuing without it" >&2
            rm -f "$target.part"
            SKIPPED+=("$name")
            continue
        fi
        [ "$(size_of "$target.part")" = "$bytes" ] || die "$name: got $(size_of "$target.part") bytes, expected $bytes. Run the script again to continue."
        mv "$target.part" "$target"
    fi
    echo "$name: checking SHA-256"
    actual="$($SHA "$target" | cut -c1-64)"
    [ "$actual" = "$sha" ] || die "$name: checksum mismatch ($actual). Delete the file and run the script again."
done

echo "index is in $DEST"
[ ${#SKIPPED[@]} -eq 0 ] || echo "not fetched: ${SKIPPED[*]}"
echo "next: scripts/setup.sh --model /path/to/model.gguf --index \"$DEST\""
