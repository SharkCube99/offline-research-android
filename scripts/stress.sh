#!/usr/bin/env bash
# Stress test: makes the app answer N questions in a row and records whether it
# survived. The app runs the questions itself; this script starts the run,
# watches it, and collects the evidence under docs/measurements/.
#
# The app, the models and the index must already be on the phone (scripts/setup.sh).
#
# Usage:
#   scripts/stress.sh [options]
#
# Options:
#   --count N         Questions to ask, 1 to 20 (default: 20)
#   --profile NAME    auto, low or high: set the profile before the run
#                     (default: leave the phone's current choice)
#   --label NAME      Name used in output files (default: the profile name)
#   --answer-timeout S  Give up if one answer takes longer than S seconds (default: 1800)
#   --serial ID       adb device serial, if more than one device is attached
#
# Exit status: 0 if every question was answered with no crash or kill, 1 otherwise.
set -euo pipefail

PKG="app.offlineresearch"
ACTIVITY="$PKG/.MainActivity"
EXT_DIR="/sdcard/Android/data/$PKG/files"
LOAD_TIMEOUT=900

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COUNT=20
PROFILE=""
LABEL=""
ANSWER_TIMEOUT=1800
SERIAL=""

# shellcheck source=scripts/_adb.sh
source "$REPO_ROOT/scripts/_adb.sh"

while [ $# -gt 0 ]; do
    case "$1" in
        --count)          COUNT="${2:-}"; shift 2 ;;
        --profile)        PROFILE="${2:-}"; shift 2 ;;
        --label)          LABEL="${2:-}"; shift 2 ;;
        --answer-timeout) ANSWER_TIMEOUT="${2:-}"; shift 2 ;;
        --serial)         SERIAL="${2:-}"; shift 2 ;;
        -h|--help)        sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)                die "unknown option: $1" ;;
    esac
done

case "$COUNT" in ''|*[!0-9]*) die "--count must be a number" ;; esac
case "$PROFILE" in ''|auto|low|high) ;; *) die "--profile must be auto, low or high" ;; esac
[ -n "$LABEL" ] || LABEL="${PROFILE:-current}"

require_device
adb shell pm path "$PKG" >/dev/null 2>&1 || die "$PKG is not installed; run scripts/setup.sh first"

DEVICE="$(adb shell getprop ro.product.model | tr -d '\r' | tr ' ' '-')"
OUT_DIR="$REPO_ROOT/docs/measurements"
OUT="$OUT_DIR/$(date +%F)-$DEVICE-stress-$LABEL"
mkdir -p "$OUT_DIR"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

lines() { adb shell "wc -l < $EXT_DIR/logs/$1 2>/dev/null || echo 0" | tr -d '\r '; }
app_pid() { adb shell pidof "$PKG" | tr -d '\r'; }
app_log() { adb logcat -d -s OfflineResearch LlamaBridge | tr -d '\r'; }

snapshot() {
    echo "## $1 ($(date +%T))"
    adb shell dumpsys meminfo "$PKG" | tr -d '\r' | grep -E "TOTAL PSS" || echo "process not running"
    adb shell cat /proc/meminfo | tr -d '\r' | grep -E "MemTotal|MemAvailable|SwapFree"
    adb shell dumpsys battery | tr -d '\r' | grep -E "temperature|level" || true
    echo "airplane_mode_on=$(adb shell settings get global airplane_mode_on | tr -d '\r')"
}

# Pulls the evidence and writes the report. $1 is a note for the log.
finish() {
    echo "# $1" >> "$OUT.txt"
    local answered=$(( $(lines metrics.jsonl) - ANSWERS_BEFORE ))
    adb pull "$EXT_DIR/logs/metrics.jsonl" "$(local_path "$TMP/metrics.jsonl")" >/dev/null 2>&1 || : > "$TMP/metrics.jsonl"
    if [ "$answered" -gt 0 ]; then tail -n "$answered" "$TMP/metrics.jsonl" > "$OUT.jsonl"; else : > "$OUT.jsonl"; fi

    : > "$OUT.exits.jsonl"
    local new_exits=$(( $(lines exits.jsonl) - EXITS_BEFORE ))
    if [ "$new_exits" -gt 0 ]; then
        adb pull "$EXT_DIR/logs/exits.jsonl" "$(local_path "$TMP/exits.jsonl")" >/dev/null
        tail -n "$new_exits" "$TMP/exits.jsonl" > "$OUT.exits.jsonl"
    fi

    local status=1
    if command -v python >/dev/null 2>&1; then
        python "$REPO_ROOT/scripts/stress_report.py" "$OUT.jsonl" --expected "$COUNT" --exits "$OUT.exits.jsonl" > "$OUT.md" && status=0
        cat "$OUT.md"
    else
        echo "python not found: no report written; $answered of $COUNT answers are in $OUT.jsonl"
        [ "$answered" -eq "$COUNT" ] && [ "$new_exits" -eq 0 ] && status=0
    fi
    echo "saved $OUT.md, .jsonl, .txt and .exits.jsonl"
    exit "$status"
}

# The app died: restart it so it can ask Android why, then report.
died() {
    echo "the app is no longer running: $1"
    {
        snapshot "after the app died"
        echo "## system log around the death"
        adb logcat -d | tr -d '\r' | grep -iE "lowmemorykiller|lmkd|Killing .*$PKG|Fatal signal|am_proc_died.*$PKG|$PKG.*died" | tail -20 || true
        echo "## dumpsys activity exit-info"
        adb shell dumpsys activity exit-info "$PKG" | tr -d '\r' | head -30 || true
    } >> "$OUT.txt"
    adb shell am start -n "$ACTIVITY" >/dev/null 2>&1 || true
    sleep 10   # the app records the exit reason on start, before loading models
    finish "FAILED: $1"
}

echo "stress run: $COUNT questions, profile ${PROFILE:-unchanged} -> $OUT.*"
adb shell am force-stop "$PKG"
ANSWERS_BEFORE="$(lines metrics.jsonl)"
adb logcat -c

# First start: sets the profile (if asked) and lets the app note earlier exits,
# so they are not mistaken for this run's.
if [ -n "$PROFILE" ]; then
    adb shell am start -n "$ACTIVITY" --es profile "$PROFILE" >/dev/null
else
    adb shell am start -n "$ACTIVITY" >/dev/null
fi

waited=0
until app_log | grep -q "index: "; do
    if app_log | grep -qE "load failed|failed$"; then
        app_log | grep -E "failed|Exception" | tail -5
        die "the app could not load its models; see: adb logcat -s OfflineResearch LlamaBridge llama.cpp"
    fi
    [ -n "$(app_pid)" ] || { EXITS_BEFORE=0; : > "$OUT.txt"; died "it died while loading the models"; }
    [ "$waited" -lt "$LOAD_TIMEOUT" ] || die "the app was not ready within ${LOAD_TIMEOUT}s"
    sleep 3
    waited=$((waited + 3))
done
EXITS_BEFORE="$(lines exits.jsonl)"

{
    echo "# stress $LABEL on $DEVICE, $COUNT questions, $(date -u +%FT%TZ)"
    app_log | grep -E "profile: |loaded '|pinned|index: " | sed 's/.*\(LlamaBridge\|OfflineResearch\): //'
    snapshot "after load"
} > "$OUT.txt"
grep -E "profile: |loaded '" "$OUT.txt" || true

adb shell am start -n "$ACTIVITY" --ei stress "$COUNT" >/dev/null

seen=0
waited=0
while [ "$seen" -lt "$COUNT" ]; do
    sleep 5
    waited=$((waited + 5))
    now=$(( $(lines metrics.jsonl) - ANSWERS_BEFORE ))
    if [ "$now" -gt "$seen" ]; then
        seen="$now"
        waited=0
        echo "$(date +%T) answered $seen of $COUNT: $(app_log | grep 'generate: ' | tail -1 | sed 's/.*LlamaBridge: //')"
        snapshot "after question $seen" >> "$OUT.txt"
        continue
    fi
    [ -n "$(app_pid)" ] || died "it stopped after $seen of $COUNT questions"
    if app_log | grep -q "STRESS end"; then
        finish "STOPPED: the run ended in the app after $seen of $COUNT questions"
    fi
    [ "$waited" -lt "$ANSWER_TIMEOUT" ] || finish "FAILED: no answer within ${ANSWER_TIMEOUT}s after question $seen"
done

finish "completed $COUNT questions"
