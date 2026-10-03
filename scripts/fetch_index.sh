#!/usr/bin/env bash
# Downloads the prebuilt knowledge index (22.9 GB) to this computer, so it does
# not have to be rebuilt from the Wikipedia dumps. Push it to the phone
# afterwards with scripts/setup.sh --index DIR. The app itself never downloads
# anything.
#
# Usage:
#   scripts/fetch_index.sh [DIR] [FILE...]
#
#   DIR    where to put the files (default: data-pipeline/work/index)
#   FILE   which files to fetch (default: wikipedia.db wikivoyage.db manifest.json)
#
# An interrupted download continues where it stopped when the script is run
# again. Each index file is checked against its SHA-256 afterwards.
#
# Source: https://huggingface.co/datasets/SHARK787/offline-research-index
# (English Wikipedia and Wikivoyage text, CC BY-SA 4.0).
set -euo pipefail

BASE="https://huggingface.co/datasets/SHARK787/offline-research-index/resolve/main"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

die() { echo "error: $*" >&2; exit 1; }

# name, size in bytes, SHA-256
expected() {
    case "$1" in
        wikipedia.db)  echo "22570754048 cca60646f543deef8b62949d40e004d05fb2fbad7b1332736d5df876a1f1ee2a" ;;
        wikivoyage.db) echo "355127296 b4bfa0b61da4588e59cbf9c42726c1d3694c7102cb2d49f75ecabcfb3daadc2b" ;;
        manifest.json) echo "1571 -" ;;
        *) die "unknown file: $1" ;;
    esac
}

case "${1:-}" in
    -h|--help) sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
esac

DEST="${1:-$REPO_ROOT/data-pipeline/work/index}"
[ $# -gt 0 ] && shift
FILES=("$@")
[ ${#FILES[@]} -gt 0 ] || FILES=(wikipedia.db wikivoyage.db manifest.json)

command -v curl > /dev/null || die "curl not found"
if command -v sha256sum > /dev/null; then SHA="sha256sum"; else SHA="shasum -a 256"; fi
mkdir -p "$DEST"

size_of() { wc -c < "$1" | tr -d ' '; }

for name in "${FILES[@]}"; do
    read -r bytes sha <<< "$(expected "$name")"
    target="$DEST/$name"
    if [ -f "$target" ] && [ "$(size_of "$target")" = "$bytes" ]; then
        echo "$name: already here"
    else
        echo "$name: downloading $bytes bytes"
        # -C - continues a partial file; --fail keeps an error page from being saved as data.
        curl -L --fail --retry 5 --retry-delay 5 -C - -o "$target.part" "$BASE/$name"
        [ "$(size_of "$target.part")" = "$bytes" ] || die "$name: got $(size_of "$target.part") bytes, expected $bytes. Run the script again to continue."
        mv "$target.part" "$target"
    fi
    if [ "$sha" != "-" ]; then
        echo "$name: checking SHA-256"
        actual="$($SHA "$target" | cut -c1-64)"
        [ "$actual" = "$sha" ] || die "$name: checksum mismatch ($actual). Delete the file and run the script again."
    fi
done

echo "index is in $DEST"
echo "next: scripts/setup.sh --model /path/to/model.gguf --index \"$DEST\""
