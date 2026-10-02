#!/usr/bin/env bash
# Measures one engine configuration on a connected phone: pushes a profile
# override, restarts the app, asks a fixed set of questions over adb and saves
# the metrics lines and memory readings under docs/measurements/.
#
# The app, the models and the index must already be on the phone (scripts/setup.sh).
#
# Usage:
#   scripts/measure.sh --threads N [options]
#
# Options:
#   --batch-threads N Threads for processing prompts (default: same as --threads)
#   --affinity MODE   none (default) or fastest: pin the threads to the fastest cores
#   --batch N         n_batch (default: the base profile's value)
#   --budget N        retrieval_budget_tokens (default: the base profile's value)
#   --base FILE       Profile to start from (default: profiles/low.json)
#   --max-tokens N    Cap on generated tokens per answer (default: 64)
#   --label NAME      Name used in output files (default: built from the options)
#   --keep-profile    Leave the profile override on the phone afterwards
#   --serial ID       adb device serial, if more than one device is attached
set -euo pipefail

PKG="app.offlineresearch"
ACTIVITY="$PKG/.MainActivity"
EXT_DIR="/sdcard/Android/data/$PKG/files"
LOAD_TIMEOUT=300
ANSWER_TIMEOUT=540

# The same questions every time, so the prompts are the same size in every configuration.
QUESTIONS=(
    "What are the symptoms of dehydration?"
    "How does a refrigerator work?"
)

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="$REPO_ROOT/profiles/low.json"
THREADS=""
BATCH_THREADS=0
AFFINITY="none"
BATCH=""
BUDGET=""
MAX_TOKENS=64
LABEL=""
KEEP=0
SERIAL=""

# shellcheck source=scripts/_adb.sh
source "$REPO_ROOT/scripts/_adb.sh"

while [ $# -gt 0 ]; do
    case "$1" in
        --threads)      THREADS="${2:-}"; shift 2 ;;
        --batch-threads) BATCH_THREADS="${2:-}"; shift 2 ;;
        --affinity)     AFFINITY="${2:-}"; shift 2 ;;
        --batch)        BATCH="${2:-}"; shift 2 ;;
        --budget)       BUDGET="${2:-}"; shift 2 ;;
        --base)         BASE="${2:-}"; shift 2 ;;
        --max-tokens)   MAX_TOKENS="${2:-}"; shift 2 ;;
        --label)        LABEL="${2:-}"; shift 2 ;;
        --serial)       SERIAL="${2:-}"; shift 2 ;;
        --keep-profile) KEEP=1; shift ;;
        -h|--help)      sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)              die "unknown option: $1" ;;
    esac
done

case "$THREADS" in ''|*[!0-9]*) die "--threads N is required" ;; esac
case "$BATCH_THREADS" in ''|*[!0-9]*) die "--batch-threads must be a number" ;; esac
case "$AFFINITY" in none|fastest) ;; *) die "--affinity must be none or fastest" ;; esac
[ -f "$BASE" ] || die "profile not found: $BASE"
for key in name n_threads n_threads_batch thread_affinity n_batch max_tokens retrieval_budget_tokens; do
    grep -q "\"$key\"" "$BASE" || die "$BASE must contain \"$key\""
done
BATCH_THREADS_LABEL=""
if [ "$BATCH_THREADS" -gt 0 ]; then BATCH_THREADS_LABEL="x$BATCH_THREADS"; fi
[ -n "$LABEL" ] || LABEL="t${THREADS}${BATCH_THREADS_LABEL}-${AFFINITY}${BATCH:+-b$BATCH}${BUDGET:+-budget$BUDGET}"

require_device
adb shell pm path "$PKG" >/dev/null 2>&1 || die "$PKG is not installed; run scripts/setup.sh first"

DEVICE="$(adb shell getprop ro.product.model | tr -d '\r' | tr ' ' '-')"
OUT_DIR="$REPO_ROOT/docs/measurements"
OUT="$OUT_DIR/$(date +%F)-$DEVICE-$LABEL"
mkdir -p "$OUT_DIR"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
sed -e "s/\"n_threads\": *[0-9]*/\"n_threads\": $THREADS/" \
    -e "s/\"n_threads_batch\": *[0-9]*/\"n_threads_batch\": $BATCH_THREADS/" \
    -e "s/\"thread_affinity\": *\"[a-z]*\"/\"thread_affinity\": \"$AFFINITY\"/" \
    -e "s/\"max_tokens\": *[0-9]*/\"max_tokens\": $MAX_TOKENS/" \
    -e "s/\"name\": *\"[^\"]*\"/\"name\": \"$LABEL\"/" \
    ${BATCH:+-e "s/\"n_batch\": *[0-9]*/\"n_batch\": $BATCH/"} \
    ${BUDGET:+-e "s/\"retrieval_budget_tokens\": *[0-9]*/\"retrieval_budget_tokens\": $BUDGET/"} \
    "$BASE" > "$TMP/profile.json"

bridge_log() { adb logcat -d -s LlamaBridge | tr -d '\r'; }
answers() { adb shell "wc -l < $EXT_DIR/logs/metrics.jsonl 2>/dev/null || echo 0" | tr -d '\r '; }

snapshot() {
    echo "## $1"
    adb shell dumpsys meminfo "$PKG" | tr -d '\r' | grep -E "TOTAL PSS" || true
    adb shell cat /proc/meminfo | tr -d '\r' | grep -E "MemAvailable|SwapFree"
    adb shell dumpsys battery | tr -d '\r' | grep -E "temperature" || true
    echo "airplane_mode_on=$(adb shell settings get global airplane_mode_on | tr -d '\r')"
}

echo "config: $LABEL -> $OUT.*"
adb shell am force-stop "$PKG"
adb push "$(local_path "$TMP/profile.json")" "$EXT_DIR/profile.json" >/dev/null
adb logcat -c
adb shell am start -n "$ACTIVITY" >/dev/null

# Ready when the index line is logged: both models are loaded by then.
waited=0
until adb logcat -d -s OfflineResearch | grep -q "index: "; do
    if bridge_log | grep -q "failed"; then
        die "model load failed; see: adb logcat -s LlamaBridge llama.cpp"
    fi
    [ "$waited" -lt "$LOAD_TIMEOUT" ] || die "the app was not ready within ${LOAD_TIMEOUT}s"
    sleep 3
    waited=$((waited + 3))
done

{
    echo "# $LABEL on $DEVICE, $(date -u +%FT%TZ)"
    bridge_log | grep -E "loaded '|pinned|not pinned" | sed 's/.*LlamaBridge: //'
    snapshot "after load"
} > "$OUT.txt"
grep -E "loaded '|pinned" "$OUT.txt" || true

for question in "${QUESTIONS[@]}"; do
    before="$(answers)"
    adb shell "am start -n $ACTIVITY --es ask '$question'" >/dev/null 2>&1
    waited=0
    until [ "$(answers)" -gt "$before" ]; do
        [ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ] || die "the app died while answering: $question"
        [ "$waited" -lt "$ANSWER_TIMEOUT" ] || die "no answer within ${ANSWER_TIMEOUT}s: $question"
        sleep 3
        waited=$((waited + 3))
    done
    bridge_log | grep "generate: " | tail -1 | sed 's/.*LlamaBridge: //'
done

snapshot "after ${#QUESTIONS[@]} questions" >> "$OUT.txt"

adb pull "$EXT_DIR/logs/metrics.jsonl" "$(local_path "$TMP/metrics.jsonl")" >/dev/null
tail -n "${#QUESTIONS[@]}" "$TMP/metrics.jsonl" > "$OUT.jsonl"

if [ "$KEEP" -eq 0 ]; then
    adb shell rm "$EXT_DIR/profile.json"
    adb shell am force-stop "$PKG"
fi
echo "saved $OUT.jsonl and $OUT.txt"
