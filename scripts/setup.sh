#!/usr/bin/env bash
# Provision a phone over adb: install the APK, then push a GGUF model (and
# optionally a profile) into the app's files directory. The app itself never
# downloads anything; this script is the only way data reaches the device.
#
# Usage:
#   scripts/setup.sh --model path/to/model.gguf [options]
#
# Options:
#   --model FILE      GGUF file to push (required)
#   --apk FILE        APK to install (default: app/build/outputs/apk/debug/app-debug.apk)
#   --no-install      Skip installing the APK
#   --profile FILE    Push FILE as profile.json, overriding the bundled profile
#   --internal        Copy the model into internal storage with run-as instead of
#                     pushing to external storage (debug builds only; fallback for
#                     phones where adb cannot write to Android/data)
#   --serial ID       adb device serial, if more than one device is attached
set -euo pipefail

PKG="app.offlineresearch"
ACTIVITY="$PKG/.MainActivity"
EXT_DIR="/sdcard/Android/data/$PKG/files"
STAGING="/data/local/tmp"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
MODEL=""
PROFILE=""
INSTALL=1
INTERNAL=0
SERIAL=""

# shellcheck source=scripts/_adb.sh
source "$REPO_ROOT/scripts/_adb.sh"

while [ $# -gt 0 ]; do
    case "$1" in
        --model)      MODEL="${2:-}"; shift 2 ;;
        --apk)        APK="${2:-}"; shift 2 ;;
        --profile)    PROFILE="${2:-}"; shift 2 ;;
        --serial)     SERIAL="${2:-}"; shift 2 ;;
        --no-install) INSTALL=0; shift ;;
        --internal)   INTERNAL=1; shift ;;
        -h|--help)    sed -n '2,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *)            die "unknown option: $1" ;;
    esac
done

[ -n "$MODEL" ] || die "--model is required (see --help)"
[ -f "$MODEL" ] || die "model file not found: $MODEL"
[ -z "$PROFILE" ] || [ -f "$PROFILE" ] || die "profile file not found: $PROFILE"

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
# right owner, then stop it so it is not holding an old model open.
adb shell am start -n "$ACTIVITY" >/dev/null
sleep 2
adb shell am force-stop "$PKG"

MODEL_NAME="$(basename "$MODEL")"
MODEL_BYTES="$(wc -c < "$MODEL" | tr -d ' ')"
echo "pushing $MODEL_NAME ($((MODEL_BYTES / 1024 / 1024)) MiB); this can take several minutes"

if [ "$INTERNAL" -eq 1 ]; then
    adb push "$(local_path "$MODEL")" "$STAGING/$MODEL_NAME"
    adb shell run-as "$PKG" mkdir -p files/models
    adb shell run-as "$PKG" cp "$STAGING/$MODEL_NAME" "files/models/$MODEL_NAME"
    adb shell rm "$STAGING/$MODEL_NAME"
    REMOTE_BYTES="$(adb shell run-as "$PKG" stat -c %s "files/models/$MODEL_NAME" | tr -d '\r')"
else
    adb shell mkdir -p "$EXT_DIR/models"
    adb push "$(local_path "$MODEL")" "$EXT_DIR/models/$MODEL_NAME"
    REMOTE_BYTES="$(adb shell stat -c %s "$EXT_DIR/models/$MODEL_NAME" | tr -d '\r')"
fi

[ "$REMOTE_BYTES" = "$MODEL_BYTES" ] ||
    die "size mismatch after push: local $MODEL_BYTES bytes, device $REMOTE_BYTES bytes"
echo "model verified on device ($REMOTE_BYTES bytes)"

if [ -n "$PROFILE" ]; then
    adb push "$(local_path "$PROFILE")" "$EXT_DIR/profile.json"
    echo "profile override pushed"
fi

# Restart from a stopped state: an instance that was alive during the push would
# have tried to load a half-written model file.
adb shell am force-stop "$PKG"
adb shell am start -n "$ACTIVITY" >/dev/null
echo "done. The app is starting; turn on airplane mode and ask a question."
echo "watch metrics with: adb logcat -s OfflineResearch LlamaBridge"
