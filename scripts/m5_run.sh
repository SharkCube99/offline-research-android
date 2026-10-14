#!/usr/bin/env bash
# M5 measurement: one labelled configuration, a fixed list of questions, repeated
# in rounds. Pushes a profile made from a base profile plus overrides, restarts the
# app for every round, asks the questions over adb and saves the app's own metrics
# lines under docs/measurements/. Compare two runs with scripts/m5_compare.py.
#
# The app, the models and the index must already be on the phone (scripts/setup.sh).
# Nothing here is a result until it has been run on the phone.
#
# Usage:
#   scripts/m5_run.sh --label NAME [options]
#
# Options:
#   --label NAME      Name used in the output files (required)
#   --base FILE       Profile to start from (default: profiles/high.json)
#   --set KEY=JSON    Override one top-level profile key; JSON value, repeatable.
#                     e.g. --set retrieval_budget_tokens=300
#                          --set n_threads=4
#                          --set engine_args='["--moe-stream","--overlap","--cache-mb","1500"]'
#   --questions FILE  One question per line, # for comments (default: bench/m5/questions.txt)
#   --count N         Use only the first N questions (default: all)
#   --repeat R        Rounds; the app is restarted before each (default: 3)
#   --max-tokens N    Cap on generated tokens, so answers are comparable (default: 128)
#   --cold            Delete the saved start of the prompt (rules-*.state) before each
#                     round, so the first question pays for reading the answer rules.
#                     Needs a debuggable build (adb run-as); warns if it cannot.
#   --answer-timeout S  Give up on one answer after S seconds (default: 1800)
#   --serial ID       adb device serial, if more than one device is attached
#
# Output (docs/measurements/<date>-<device>-m5-<label>.*):
#   .jsonl   the app's metrics line for every answer, in the order asked
#   .txt     setup, memory / battery / thermal readings before and after every round
#   .profile.json  the exact profile that was pushed
#   .bmoe.txt      engine telemetry lines from logcat, if the app logged any
#
# Exit status: 0 if every question was answered, 1 otherwise (partial files are kept).
set -euo pipefail

PKG="app.offlineresearch"
ACTIVITY="$PKG/.MainActivity"
EXT_DIR="/sdcard/Android/data/$PKG/files"
LOAD_TIMEOUT=900

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE="$REPO_ROOT/profiles/high.json"
QUESTIONS_FILE="$REPO_ROOT/bench/m5/questions.txt"
LABEL=""
COUNT=0
REPEAT=3
MAX_TOKENS=128
COLD=0
ANSWER_TIMEOUT=1800
SERIAL=""
SETS=()

# shellcheck source=scripts/_adb.sh
source "$REPO_ROOT/scripts/_adb.sh"

while [ $# -gt 0 ]; do
    case "$1" in
        --label)          LABEL="${2:-}"; shift 2 ;;
        --base)           BASE="${2:-}"; shift 2 ;;
        --set)            SETS+=("${2:-}"); shift 2 ;;
        --questions)      QUESTIONS_FILE="${2:-}"; shift 2 ;;
        --count)          COUNT="${2:-}"; shift 2 ;;
        --repeat)         REPEAT="${2:-}"; shift 2 ;;
        --max-tokens)     MAX_TOKENS="${2:-}"; shift 2 ;;
        --cold)           COLD=1; shift ;;
        --answer-timeout) ANSWER_TIMEOUT="${2:-}"; shift 2 ;;
        --serial)         SERIAL="${2:-}"; shift 2 ;;
        -h|--help)        sed -n '2,38p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)                die "unknown option: $1" ;;
    esac
done

[ -n "$LABEL" ] || die "--label NAME is required"
case "$LABEL" in *[!A-Za-z0-9._-]*) die "--label may hold only letters, digits, . _ -" ;; esac
for n in COUNT REPEAT MAX_TOKENS ANSWER_TIMEOUT; do
    case "${!n}" in ''|*[!0-9]*) die "$n must be a number" ;; esac
done
[ "$REPEAT" -ge 1 ] || die "--repeat must be at least 1"
[ -f "$BASE" ] || die "profile not found: $BASE"
[ -f "$QUESTIONS_FILE" ] || die "questions file not found: $QUESTIONS_FILE"
command -v python3 >/dev/null 2>&1 || command -v python >/dev/null 2>&1 || die "python is needed to write the profile"
PY="$(command -v python3 || command -v python)"

QUESTIONS=()
while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|'#'*) continue ;; esac
    case "$line" in *\'*|*\"*|*\\*) die "question must not hold quotes or backslashes: $line" ;; esac
    QUESTIONS+=("$line")
done < "$QUESTIONS_FILE"
[ "$COUNT" -eq 0 ] || QUESTIONS=("${QUESTIONS[@]:0:$COUNT}")
[ "${#QUESTIONS[@]}" -gt 0 ] || die "no questions"
TOTAL=$(( ${#QUESTIONS[@]} * REPEAT ))

require_device
adb shell pm path "$PKG" >/dev/null 2>&1 || die "$PKG is not installed; run scripts/setup.sh first"

DEVICE="$(adb shell getprop ro.product.model | tr -d '\r' | tr ' ' '-')"
OUT_DIR="$REPO_ROOT/docs/measurements"
OUT="$OUT_DIR/$(date +%F)-$DEVICE-m5-$LABEL"
mkdir -p "$OUT_DIR"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# The profile: the base with the overrides applied, the name set to the label and
# the answer length capped. Written by a JSON parser, not sed, so engine_args works.
"$PY" - "$BASE" "$TMP/profile.json" "$LABEL" "$MAX_TOKENS" "${SETS[@]+"${SETS[@]}"}" <<'EOF'
import json, sys
base, out, label, max_tokens, *sets = sys.argv[1:]
profile = json.load(open(base, encoding="utf-8"))
for item in sets:
    key, sep, raw = item.partition("=")
    if not sep or not key:
        sys.exit(f"--set needs KEY=JSON, got: {item}")
    try:
        profile[key] = json.loads(raw)
    except ValueError:
        sys.exit(f"--set {key}: not valid JSON: {raw}")
profile["name"] = label
profile["max_tokens"] = int(max_tokens)
json.dump(profile, open(out, "w", encoding="utf-8"), indent=2)
EOF
cp "$TMP/profile.json" "$OUT.profile.json"

bridge_log() { adb logcat -d -s LlamaBridge | tr -d '\r'; }
answers() { adb shell "wc -l < $EXT_DIR/logs/metrics.jsonl 2>/dev/null || echo 0" | tr -d '\r '; }

snapshot() {
    echo "## $1"
    adb shell dumpsys meminfo "$PKG" | tr -d '\r' | grep -E "TOTAL PSS" || true
    adb shell cat /proc/meminfo | tr -d '\r' | grep -E "MemAvailable|SwapFree"
    adb shell dumpsys battery | tr -d '\r' | grep -E "level|temperature" || true
    adb shell dumpsys thermalservice | tr -d '\r' | grep -E "Thermal Status" | head -1 || true
    echo "airplane_mode_on=$(adb shell settings get global airplane_mode_on | tr -d '\r')"
}

{
    echo "# m5 $LABEL on $DEVICE, $(date -u +%FT%TZ)"
    echo "base: $BASE"
    echo "overrides: ${SETS[*]:-none}"
    echo "max_tokens: $MAX_TOKENS  rounds: $REPEAT  questions per round: ${#QUESTIONS[@]}  cold: $COLD"
    echo "questions file: $QUESTIONS_FILE"
    snapshot "before the run"
} > "$OUT.txt"
echo "config: $LABEL -> $OUT.*   (${#QUESTIONS[@]} questions x $REPEAT rounds)"

adb shell am force-stop "$PKG"
adb push "$(local_path "$TMP/profile.json")" "$EXT_DIR/profile.json" >/dev/null
start_count="$(answers)"

failed=0
for round in $(seq 1 "$REPEAT"); do
    adb shell am force-stop "$PKG"
    if [ "$COLD" -eq 1 ]; then
        adb shell "run-as $PKG sh -c 'rm -f cache/rules-*.state cache/rules-*.state.part'" >/dev/null 2>&1 ||
            echo "warning: could not delete the saved prompt start (needs a debuggable build); this round is not cold" | tee -a "$OUT.txt"
    fi
    adb logcat -c
    adb shell am start -n "$ACTIVITY" >/dev/null

    # Ready when the index line is logged: the models are loaded by then.
    waited=0
    until adb logcat -d -s OfflineResearch | grep -q "index: "; do
        if bridge_log | grep -q "failed"; then
            echo "model load failed; see: adb logcat -s LlamaBridge llama.cpp" | tee -a "$OUT.txt"
            failed=1; break 2
        fi
        if [ "$waited" -ge "$LOAD_TIMEOUT" ]; then
            echo "the app was not ready within ${LOAD_TIMEOUT}s" | tee -a "$OUT.txt"
            failed=1; break 2
        fi
        sleep 3
        waited=$((waited + 3))
    done
    snapshot "round $round, after load" >> "$OUT.txt"

    for question in "${QUESTIONS[@]}"; do
        before="$(answers)"
        adb shell "am start -n $ACTIVITY --es ask '$question'" >/dev/null 2>&1
        waited=0
        until [ "$(answers)" -gt "$before" ]; do
            if [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then
                echo "the app died while answering: $question" | tee -a "$OUT.txt"
                failed=1; break 3
            fi
            if [ "$waited" -ge "$ANSWER_TIMEOUT" ]; then
                echo "no answer within ${ANSWER_TIMEOUT}s: $question" | tee -a "$OUT.txt"
                failed=1; break 3
            fi
            sleep 3
            waited=$((waited + 3))
        done
        echo "round $round: answered: $question"
    done
    snapshot "round $round, after ${#QUESTIONS[@]} questions" >> "$OUT.txt"
    adb logcat -d | tr -d '\r' | grep -E "BMOE_|cache_hit|stall_ms" >> "$OUT.bmoe.txt" || true
done

done_count=$(( $(answers) - start_count ))
adb pull "$EXT_DIR/logs/metrics.jsonl" "$(local_path "$TMP/metrics.jsonl")" >/dev/null
tail -n "$done_count" "$TMP/metrics.jsonl" > "$OUT.jsonl"
echo "answered $done_count of $TOTAL" | tee -a "$OUT.txt"

adb shell rm -f "$EXT_DIR/profile.json"
adb shell am force-stop "$PKG"
echo "saved $OUT.jsonl, .txt and .profile.json"
[ "$failed" -eq 0 ] && [ "$done_count" -eq "$TOTAL" ]
