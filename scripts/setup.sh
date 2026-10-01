#!/usr/bin/env bash
# Provision a phone over adb: install the APK, then push models, the knowledge
# index and optionally a profile into the app's files directory. The app itself
# never downloads anything; this script is the only way data reaches the device.
#
# Usage:
#   scripts/setup.sh [--model FILE]... [--index DIR] [options]
#
# Options:
#   --model FILE      GGUF file to push; repeat for the answerer and the planner
#   --index DIR       Push every *.db corpus index in DIR
#   --apk FILE        APK to install (default: app/build/outputs/apk/debug/app-debug.apk)
#   --no-install      Skip installing the APK
#   --profile FILE    Push FILE as profile.json, overriding the bundled profile
#   --internal        Copy files into internal storage with run-as instead of
#                     pushing to external storage (debug builds only; fallback for
#                     phones where adb cannot write to Android/data)
#   --serial ID       adb device serial, if more than one device is attached
#
# Files already on the phone with the same size are skipped.
set -euo pipefail

PKG="app.offlineresearch"
ACTIVITY="$PKG/.MainActivity"
EXT_DIR="/sdcard/Android/data/$PKG/files"
STAGING="/data/local/tmp"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
MODELS=()
INDEX_DIR=""
PROFILE=""
INSTALL=1
INTERNAL=0
SERIAL=""

# shellcheck source=scripts/_adb.sh
source "$REPO_ROOT/scripts/_adb.sh"

while [ $# -gt 0 ]; do
    case "$1" in
        --model)      MODELS+=("${2:-}"); shift 2 ;;
        --index)      INDEX_DIR="${2:-}"; shift 2 ;;
        --apk)        APK="${2:-}"; shift 2 ;;
        --profile)    PROFILE="${2:-}"; shift 2 ;;
        --serial)     SERIAL="${2:-}"; shift 2 ;;
        --no-install) INSTALL=0; shift ;;
        --internal)   INTERNAL=1; shift ;;
        -h|--help)    sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            die "unknown option: $1" ;;
    esac
done

INDEXES=()
if [ -n "$INDEX_DIR" ]; then
    [ -d "$INDEX_DIR" ] || die "index directory not found: $INDEX_DIR"
    for db in "$INDEX_DIR"/*.db; do
        [ -f "$db" ] && INDEXES+=("$db")
    done
    [ ${#INDEXES[@]} -gt 0 ] || die "no *.db files in $INDEX_DIR"
fi
for model in ${MODELS[@]+"${MODELS[@]}"}; do
    [ -f "$model" ] || die "model file not found: $model"
done
[ -z "$PROFILE" ] || [ -f "$PROFILE" ] || die "profile file not found: $PROFILE"
[ ${#MODELS[@]} -gt 0 ] || [ ${#INDEXES[@]} -gt 0 ] || [ -n "$PROFILE" ] || [ "$INSTALL" -eq 1 ] ||
    die "nothing to do: give --model, --index or --profile (see --help)"

require_device

echo "device: $(adb shell getprop ro.product.manufacturer | tr -d '\r') $(adb shell getprop ro.product.model | tr -d '\r')," \
     "Android $(adb shell getprop ro.build.version.release | tr -d '\r')," \
     "ABI $(adb shell getprop ro.product.cpu.abi | tr -d '\r')"

if [ "$INSTALL" -eq 1 ]; then
    [ -f "$APK" ] || die "APK not found: $APK (build it with ./gradlew assembleDebug)"
    echo "installing $(basename "$APK")"
    adb install -r "$(local_path "$APK")"
fi

adb shell pm path "$PKG" >/dev/null 2>&1 || die "$PKG is not installed on the device"

# Launch once so Android creates the app's external files directory with the
# right owner, then stop it so it is not holding old files open.
adb shell am start -n "$ACTIVITY" >/dev/null
sleep 2
adb shell am force-stop "$PKG"

# Is there room? /sdcard and the app's internal storage share one partition.
NEEDED=0
for file in ${MODELS[@]+"${MODELS[@]}"} ${INDEXES[@]+"${INDEXES[@]}"}; do
    NEEDED=$((NEEDED + $(wc -c < "$file" | tr -d ' ')))
done
FREE_KB="$(adb shell df -k /sdcard | tr -d '\r' | awk 'NR==2 {print $4}')"
echo "to push: $((NEEDED / 1000000)) MB; free on the phone: $((FREE_KB / 1000)) MB"

# push_file LOCAL SUBDIR: copies LOCAL into <files>/SUBDIR and checks its size.
push_file() {
    local file="$1" subdir="$2" name bytes remote
    name="$(basename "$file")"
    bytes="$(wc -c < "$file" | tr -d ' ')"
    if [ "$INTERNAL" -eq 1 ]; then
        remote="$(adb shell run-as "$PKG" stat -c %s "files/$subdir/$name" 2>/dev/null | tr -d '\r' || true)"
    else
        remote="$(adb shell stat -c %s "$EXT_DIR/$subdir/$name" 2>/dev/null | tr -d '\r' || true)"
    fi
    if [ "$remote" = "$bytes" ]; then
        echo "$subdir/$name is already on the phone ($bytes bytes); skipped"
        return
    fi
    [ $((bytes / 1024)) -lt "$FREE_KB" ] || die "not enough free space on the phone for $name"
    echo "pushing $subdir/$name ($((bytes / 1000000)) MB); this can take several minutes"
    if [ "$INTERNAL" -eq 1 ]; then
        adb push "$(local_path "$file")" "$STAGING/$name"
        adb shell run-as "$PKG" mkdir -p "files/$subdir"
        adb shell run-as "$PKG" cp "$STAGING/$name" "files/$subdir/$name"
        adb shell rm "$STAGING/$name"
        remote="$(adb shell run-as "$PKG" stat -c %s "files/$subdir/$name" | tr -d '\r')"
    else
        adb shell mkdir -p "$EXT_DIR/$subdir"
        adb push "$(local_path "$file")" "$EXT_DIR/$subdir/$name"
        remote="$(adb shell stat -c %s "$EXT_DIR/$subdir/$name" | tr -d '\r')"
    fi
    [ "$remote" = "$bytes" ] || die "size mismatch after push: local $bytes bytes, device $remote bytes"
    echo "$name verified on device ($remote bytes)"
}

for model in ${MODELS[@]+"${MODELS[@]}"}; do push_file "$model" models; done
for db in ${INDEXES[@]+"${INDEXES[@]}"}; do push_file "$db" index; done

if [ -n "$PROFILE" ]; then
    adb push "$(local_path "$PROFILE")" "$EXT_DIR/profile.json"
    echo "profile override pushed"
fi

# Restart from a stopped state: an instance that was alive during the push would
# have tried to load half-written files.
adb shell am force-stop "$PKG"
adb shell am start -n "$ACTIVITY" >/dev/null
echo "done. The app is starting; turn on airplane mode and ask a question."
echo "watch metrics with: adb logcat -s OfflineResearch LlamaBridge"
