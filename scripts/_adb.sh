# Sourced by the other scripts. Finds adb and defines adb(), local_path() and
# require_device(). Honours $SERIAL when it is set.

die() { echo "error: $*" >&2; exit 1; }

# Find adb: PATH first, then the usual SDK locations.
ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
    for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${LOCALAPPDATA:-}/Android/Sdk" \
               "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
        for exe in adb adb.exe; do
            if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/$exe" ]; then
                ADB="$sdk/platform-tools/$exe"
                break 2
            fi
        done
    done
fi
[ -n "$ADB" ] || die "adb not found. Install Android platform-tools or set ANDROID_HOME."

# Git Bash on Windows rewrites arguments that look like Unix paths (/sdcard/...)
# into Windows paths. Turn that off for everything adb is given.
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL='*'

# stdin comes from /dev/null: adb shell would otherwise swallow the input of a
# surrounding "while read" loop.
adb() { if [ -n "${SERIAL:-}" ]; then "$ADB" -s "$SERIAL" "$@" < /dev/null; else "$ADB" "$@" < /dev/null; fi; }

# With path rewriting off, local files must be handed to adb.exe as Windows paths.
local_path() {
    if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s\n' "$1"; fi
}

require_device() {
    [ "$(adb get-state 2>/dev/null || true)" = "device" ] ||
        die "no authorised device. Enable USB debugging, accept the prompt on the phone, check 'adb devices'."
}
