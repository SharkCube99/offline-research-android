#!/usr/bin/env bash
# One command from a fresh clone to a working app on a phone: picks the models
# for the phone's memory, downloads them and the knowledge index, checks every
# file, installs the APK, pushes everything over USB and starts the app.
#
# The app itself never downloads anything; this script runs on the computer.
#
# Usage:
#   scripts/quickstart.sh [options]
#
# Options:
#   --tier auto|low|high  Which answerer: low = Qwen3-4B (phones under 10 GB),
#                         high = Qwen3.6-35B 2-bit (10 GB or more). Default: auto,
#                         from the phone's memory.
#   --fast                Also fetch the optional fast answerer (Ling-3.0-tiny, 4.6 GB)
#   --core-only           Fetch only wikipedia.db and wikivoyage.db, not the small packs
#                         and the city places (saves 1.6 GB; those answer fewer kinds of question)
#   --apk FILE            Install this APK (default: newest GitHub release, else a local build)
#   --dir DIR             Where downloads are kept (default: downloads/ in the repository)
#   --yes                 Do not ask before downloading
#   --dry-run             Print the plan and check the download addresses; change nothing
#   --serial ID           adb device serial, if more than one device is attached
#
# Safe to run again: finished downloads are recognised and files already on the
# phone are skipped; an interrupted download continues where it stopped.
#
# You need: bash (Git Bash on Windows), curl, adb (Android platform-tools), a phone
# with USB debugging on, and room for the files on the computer and the phone.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="SharkCube99/offline-research-android"
TIER="auto"
FAST=0
CORE_ONLY=0
APK=""
DIR="$REPO_ROOT/downloads"
YES=0
DRY=0
SERIAL=""

# shellcheck source=scripts/_adb.sh
source "$REPO_ROOT/scripts/_adb.sh"

while [ $# -gt 0 ]; do
    case "$1" in
        --tier)      TIER="${2:-}"; shift 2 ;;
        --fast)      FAST=1; shift ;;
        --core-only) CORE_ONLY=1; shift ;;
        --apk)       APK="${2:-}"; shift 2 ;;
        --dir)       DIR="${2:-}"; shift 2 ;;
        --yes)       YES=1; shift ;;
        --dry-run)   DRY=1; shift ;;
        --serial)    SERIAL="${2:-}"; shift 2 ;;
        -h|--help)   sed -n '2,31p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)           die "unknown option: $1 (see --help)" ;;
    esac
done
case "$TIER" in auto|low|high) ;; *) die "--tier must be auto, low or high" ;; esac

command -v curl >/dev/null 2>&1 || die "curl is needed"
if command -v sha256sum >/dev/null 2>&1; then
    sha256() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
    sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
    die "sha256sum or shasum is needed to check the downloads"
fi

# ---- what the phone is --------------------------------------------------------
DEVICE_NAME="(none attached)"
RAM_GB=""
if [ "$(adb get-state 2>/dev/null || true)" = "device" ]; then
    DEVICE_NAME="$(adb shell getprop ro.product.manufacturer | tr -d '\r') $(adb shell getprop ro.product.model | tr -d '\r')"
    ABI="$(adb shell getprop ro.product.cpu.abi | tr -d '\r')"
    [ "$ABI" = "arm64-v8a" ] || die "the app is arm64 only; this phone reports $ABI"
    MEM_KB="$(adb shell grep MemTotal /proc/meminfo | tr -d '\r' | awk '{print $2}')"
    RAM_GB=$(( MEM_KB / 1000000 ))
    PHONE_FREE_KB="$(adb shell df -k /sdcard | tr -d '\r' | awk 'NR==2 {print $4}')"
elif [ "$DRY" -eq 0 ]; then
    require_device
fi

if [ "$TIER" = "auto" ]; then
    if [ -z "$RAM_GB" ]; then
        die "no phone attached to read its memory from; give --tier low or --tier high"
    fi
    if [ "$RAM_GB" -ge 10 ]; then TIER="high"; else TIER="low"; fi
fi

# ---- what to fetch: name|url ----------------------------------------------------
HF="https://huggingface.co"
MODELS=("Qwen3-1.7B-Q8_0.gguf|$HF/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q8_0.gguf")
if [ "$TIER" = "high" ]; then
    MODELS+=("Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf|$HF/unsloth/Qwen3.6-35B-A3B-GGUF/resolve/main/Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf")
else
    MODELS+=("Qwen3-4B-Q4_K_M.gguf|$HF/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf")
fi
if [ "$FAST" -eq 1 ]; then
    MODELS+=("Ling-3.0-tiny-Q4_0.gguf|$HF/bartowski/Ling-3.0-tiny-GGUF/resolve/main/Ling-3.0-tiny-Q4_0.gguf")
fi

# Size and SHA-256 of a Hugging Face file, from its own response headers, so
# nothing has to be copied into this script by hand. Prints "SIZE SHA256".
hf_facts() {
    local headers size etag
    headers="$(curl -sIL --max-time 60 "$1" | tr -d '\r')" || return 1
    size="$(printf '%s\n' "$headers" | awk 'tolower($1)=="x-linked-size:" {print $2; exit}')"
    etag="$(printf '%s\n' "$headers" | awk 'tolower($1)=="x-linked-etag:" {gsub(/"/,"",$2); print $2; exit}')"
    [ -n "$size" ] && [ -n "$etag" ] || return 1
    echo "$size $etag"
}

echo "phone:  $DEVICE_NAME${RAM_GB:+, about $RAM_GB GB of memory}"
echo "tier:   $TIER  ($([ "$TIER" = high ] && echo "Qwen3.6-35B-A3B 2-bit, streamed from storage" || echo "Qwen3-4B"))"
echo
echo "Checking the download addresses..."
TOTAL=0
FACTS=()
for item in "${MODELS[@]}"; do
    name="${item%%|*}"; url="${item#*|}"
    facts="$(hf_facts "$url")" || die "could not read $name from Hugging Face (no network, or the file moved): $url"
    FACTS+=("$facts")
    TOTAL=$((TOTAL + ${facts%% *}))
    printf '  %-36s %6d MB\n' "$name" $(( ${facts%% *} / 1000000 ))
done
INDEX_MB=24600
[ "$CORE_ONLY" -eq 0 ] || INDEX_MB=22930
printf '  %-36s %6d MB  (scripts/fetch_index.sh)\n' "knowledge index" "$INDEX_MB"
NEED_MB=$(( TOTAL / 1000000 + INDEX_MB ))
echo "  total to download and push:          $NEED_MB MB"
echo

# ---- room ------------------------------------------------------------------------
mkdir -p "$DIR" 2>/dev/null || true
LOCAL_FREE_KB="$(df -Pk "$DIR" 2>/dev/null | awk 'NR==2 {print $4}')"
if [ -n "${LOCAL_FREE_KB:-}" ] && [ $((LOCAL_FREE_KB / 1000)) -lt "$NEED_MB" ]; then
    echo "warning: only $((LOCAL_FREE_KB / 1000)) MB free on this computer where $DIR is; $NEED_MB MB are needed (a finished download is kept)."
fi
if [ -n "${PHONE_FREE_KB:-}" ] && [ $((PHONE_FREE_KB / 1000)) -lt "$NEED_MB" ]; then
    die "the phone has $((PHONE_FREE_KB / 1000)) MB free; $NEED_MB MB are needed. Free some space, or use --core-only."
fi

# ---- the APK ----------------------------------------------------------------------
if [ -z "$APK" ]; then
    # Releases here are all pre-releases, which /releases/latest skips, so the list is read.
    APK_URL="$(curl -s --max-time 30 "https://api.github.com/repos/$REPO/releases?per_page=10" |
        grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | sed 's/.*: *"\(.*\)"/\1/' || true)"
    if [ -n "$APK_URL" ]; then
        APK="$DIR/$(basename "$APK_URL")"
        echo "apk:    $(basename "$APK_URL") from the newest GitHub release"
    else
        for candidate in "$REPO_ROOT/app/build/outputs/apk/release/app-release.apk" \
                         "$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"; do
            if [ -f "$candidate" ]; then APK="$candidate"; break; fi
        done
        [ -n "$APK" ] || die "no APK: no GitHub release could be read and no local build exists. Pass --apk FILE or build with ./gradlew assembleDebug"
        echo "apk:    $APK (local build; no release could be read)"
    fi
else
    [ -f "$APK" ] || die "APK not found: $APK"
    echo "apk:    $APK"
fi
echo

if [ "$DRY" -eq 1 ]; then
    echo "dry run: nothing downloaded, installed or pushed."
    exit 0
fi

if [ "$YES" -eq 0 ]; then
    printf 'Download about %d MB and install on %s? [y/N] ' "$NEED_MB" "$DEVICE_NAME"
    read -r answer < /dev/tty || answer=""
    case "$answer" in y|Y|yes|YES) ;; *) echo "stopped; nothing changed."; exit 1 ;; esac
fi

# ---- download and check each file -------------------------------------------------
fetch_checked() { # NAME URL "SIZE SHA256"
    local name="$1" url="$2" size="${3%% *}" want="${3##* }" file="$DIR/$1" have
    if [ -f "$file.verified" ] && [ -f "$file" ] && [ "$(wc -c < "$file" | tr -d ' ')" = "$size" ] &&
       [ "$(cat "$file.verified")" = "$want" ]; then
        echo "$name: already downloaded and checked"
        return
    fi
    echo "$name: downloading ($((size / 1000000)) MB)"
    curl -L --fail -C - --retry 5 --retry-delay 5 -o "$file" "$url"
    have="$(wc -c < "$file" | tr -d ' ')"
    [ "$have" = "$size" ] || die "$name: $have bytes on disk, $size expected; run the script again to continue the download"
    echo "$name: checking SHA-256"
    [ "$(sha256 "$file")" = "$want" ] || { rm -f "$file"; die "$name: checksum does not match; the file was removed, run the script again"; }
    echo "$want" > "$file.verified"
}

i=0
for item in "${MODELS[@]}"; do
    fetch_checked "${item%%|*}" "${item#*|}" "${FACTS[$i]}"
    i=$((i + 1))
done

if [ -z "${APK_URL:-}" ] && [ -f "$APK" ]; then
    :   # --apk or a local build
else
    if [ ! -s "$APK" ]; then
        echo "$(basename "$APK"): downloading"
        curl -L --fail --retry 3 -o "$APK" "$APK_URL"
    fi
fi

INDEX_FILES=()
[ "$CORE_ONLY" -eq 0 ] || INDEX_FILES=(wikipedia.db wikivoyage.db)
"$REPO_ROOT/scripts/fetch_index.sh" "$DIR/index" ${INDEX_FILES[@]+"${INDEX_FILES[@]}"}

# ---- onto the phone -----------------------------------------------------------------
SETUP=("$REPO_ROOT/scripts/setup.sh" --apk "$APK" --index "$DIR/index")
for item in "${MODELS[@]}"; do SETUP+=(--model "$DIR/${item%%|*}"); done
[ -z "$SERIAL" ] || SETUP+=(--serial "$SERIAL")
"${SETUP[@]}"

echo
echo "Done. On the phone: turn on airplane mode, then ask a question."
echo "The first answer takes a while: the models have to be read from storage."
echo "To confirm the build has no network permission: scripts/verify_offline.sh"
