#!/usr/bin/env bash
# Measures one engine configuration on a connected phone: pushes a profile
# override, restarts the app, asks a fixed set of questions over adb and saves
# the metrics lines and memory readings under docs/measurements/.
#
# The app and the model must already be on the phone (scripts/setup.sh).
#
# Usage:
#   scripts/measure.sh --threads N --repack true|false [options]
#
# Options:
#   --base FILE       Profile to start from (default: profiles/low.json)
#   --max-tokens N    Cap on generated tokens per answer (default: 128)
#   --label NAME      Name used in output files (default: t<threads>-repack-<bool>)
#   --keep-profile    Leave the profile override on the phone afterwards
#   --serial ID       adb device serial, if more than one device is attached
set -euo pipefail

PKG="app.offlineresearch"
ACTIVITY="$PKG/.MainActivity"
EXT_DIR="/sdcard/Android/data/$PKG/files"
LOAD_TIMEOUT=300
ANSWER_TIMEOUT=600

QUESTIONS=(
    "Explain how a refrigerator works."
    "What are the main differences between a virus and a bacterium?"
    "How should I treat a minor burn?"
)

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="$REPO_ROOT/profiles/low.json"
THREADS=""
REPACK=""
MAX_TOKENS=128
LABEL=""
KEEP=0
SERIAL=""

# shellcheck source=scripts/_adb.sh
source "$REPO_ROOT/scripts/_adb.sh"

while [ $# -gt 0 ]; do
    case "$1" in
        --threads)      THREADS="${2:-}"; shift 2 ;;
        --repack)       REPACK="${2:-}"; shift 2 ;;
        --base)         BASE="${2:-}"; shift 2 ;;
        --max-tokens)   MAX_TOKENS="${2:-}"; shift 2 ;;
        --label)        LABEL="${2:-}"; shift 2 ;;
        --serial)       SERIAL="${2:-}"; shift 2 ;;
        --keep-profile) KEEP=1; shift ;;
        -h|--help)      sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)              die "unknown option: $1" ;;
    esac
done

case "$THREADS" in ''|*[!0-9]*) die "--threads N is required" ;; esac
case "$REPACK" in true|false) ;; *) die "--repack true|false is required" ;; esac
[ -f "$BASE" ] || die "profile not found: $BASE"
for key in n_threads repack max_tokens name; do
    grep -q "\"$key\"" "$BASE" || die "$BASE must contain \"$key\""
done
[ -n "$LABEL" ] || LABEL="t${THREADS}-repack-${REPACK}"

require_device
adb shell pm path "$PKG" >/dev/null 2>&1 || die "$PKG is not installed; run scripts/setup.sh first"

DEVICE="$(adb shell getprop ro.product.model | tr -d '\r' | tr ' ' '-')"
OUT_DIR="$REPO_ROOT/docs/measurements"
OUT="$OUT_DIR/$(date +%F)-$DEVICE-$LABEL"
mkdir -p "$OUT_DIR"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
sed -e "s/\"n_threads\": *[0-9]*/\"n_threads\": $THREADS/" \
    -e "s/\"repack\": *[a-z]*/\"repack\": $REPACK/" \
    -e "s/\"max_tokens\": *[0-9]*/\"max_tokens\": $MAX_TOKENS/" \
    -e "s/\"name\": *\"[^\"]*\"/\"name\": \"$LABEL\"/" "$BASE" > "$TMP/profile.json"

bridge_log() { adb logcat -d -s LlamaBridge | tr -d '\r'; }

memory_snapshot() {
    echo "## $1"
    adb shell dumpsys meminfo "$PKG" | tr -d '\r' | grep -E "TOTAL PSS" || true
    adb shell cat /proc/meminfo | tr -d '\r' | grep -E "MemTotal|MemAvailable|SwapTotal|SwapFree"
    adb shell dumpsys battery | tr -d '\r' | grep -E "temperature" || true
    echo "airplane_mode_on=$(adb shell settings get global airplane_mode_on | tr -d '\r')"
}

echo "config: threads=$THREADS repack=$REPACK max_tokens=$MAX_TOKENS -> $OUT.*"
adb shell am force-stop "$PKG"
adb push "$(local_path "$TMP/profile.json")" "$EXT_DIR/profile.json" >/dev/null
adb logcat -c
adb shell am start -n "$ACTIVITY" >/dev/null

waited=0
until bridge_log | grep -q "loaded '"; do
    if bridge_log | grep -q "failed"; then
        die "model load failed; see: adb logcat -s LlamaBridge llama.cpp"
    fi
    [ "$waited" -lt "$LOAD_TIMEOUT" ] || die "model did not load within ${LOAD_TIMEOUT}s"
    sleep 3
    waited=$((waited + 3))
done
bridge_log | grep "loaded '" | tail -1 | sed 's/.*LlamaBridge: //'

{
    echo "# $LABEL on $DEVICE, $(date -u +%FT%TZ)"
    bridge_log | grep -E "backend initialised|loading |loaded '" | sed 's/.*LlamaBridge: //'
    adb logcat -d -s llama.cpp | tr -d '\r' | grep -E "model buffer size" | sed 's/.*llama.cpp: //' || true
    memory_snapshot "after load"
} > "$OUT.txt"

done_count() { bridge_log | grep -c "generate: " || true; }

for question in "${QUESTIONS[@]}"; do
    before="$(done_count)"
    adb shell "am start -n $ACTIVITY --es ask '$question'" >/dev/null
    waited=0
    until [ "$(done_count)" -gt "$before" ]; do
        [ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ] || die "the app died while answering: $question"
        [ "$waited" -lt "$ANSWER_TIMEOUT" ] || die "no answer within ${ANSWER_TIMEOUT}s: $question"
        sleep 3
        waited=$((waited + 3))
    done
    bridge_log | grep "generate: " | tail -1 | sed 's/.*LlamaBridge: //'
done

memory_snapshot "after ${#QUESTIONS[@]} questions" >> "$OUT.txt"

adb pull "$EXT_DIR/logs/metrics.jsonl" "$(local_path "$TMP/metrics.jsonl")" >/dev/null
tail -n "${#QUESTIONS[@]}" "$TMP/metrics.jsonl" > "$OUT.jsonl"

if [ "$KEEP" -eq 0 ]; then
    adb shell rm "$EXT_DIR/profile.json"
    adb shell am force-stop "$PKG"
fi
echo "saved $OUT.jsonl and $OUT.txt"
