#!/usr/bin/env bash
# Checks that built APKs cannot use the network: no INTERNET permission (or any
# other network permission) may appear in the merged manifest.
#
# Usage:
#   scripts/verify_offline.sh [APK ...]
# With no arguments it checks every APK under app/build/outputs/apk.
set -euo pipefail

FORBIDDEN="android.permission.INTERNET android.permission.ACCESS_NETWORK_STATE \
android.permission.ACCESS_WIFI_STATE android.permission.CHANGE_NETWORK_STATE \
android.permission.CHANGE_WIFI_STATE"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

die() { echo "error: $*" >&2; exit 1; }

# Find aapt2 in the newest installed build-tools.
AAPT2=""
for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${LOCALAPPDATA:-}/Android/Sdk" \
           "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    [ -n "$sdk" ] && [ -d "$sdk/build-tools" ] || continue
    for dir in $(ls -1 "$sdk/build-tools" | sort -rV); do
        for exe in aapt2 aapt2.exe; do
            if [ -x "$sdk/build-tools/$dir/$exe" ]; then
                AAPT2="$sdk/build-tools/$dir/$exe"
                break 3
            fi
        done
    done
done
[ -n "$AAPT2" ] || die "aapt2 not found. Set ANDROID_HOME to an SDK with build-tools installed."

if [ $# -gt 0 ]; then
    APKS=("$@")
else
    APKS=()
    while IFS= read -r apk; do APKS+=("$apk"); done \
        < <(find "$REPO_ROOT/app/build/outputs/apk" -name '*.apk' 2>/dev/null)
fi
[ ${#APKS[@]} -gt 0 ] || die "no APK found. Build one first: ./gradlew assembleDebug"

status=0
for apk in "${APKS[@]}"; do
    [ -f "$apk" ] || die "not a file: $apk"
    permissions="$("$AAPT2" dump permissions "$apk")"
    bad=""
    for p in $FORBIDDEN; do
        if printf '%s\n' "$permissions" | grep -q "name='$p'"; then bad="$bad $p"; fi
    done
    if [ -n "$bad" ]; then
        echo "FAIL $apk declares:$bad"
        status=1
    else
        declared="$(printf '%s\n' "$permissions" | grep -c "uses-permission" || true)"
        echo "PASS $apk (no network permission; $declared uses-permission entries in total)"
    fi
done
exit $status
