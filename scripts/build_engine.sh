#!/usr/bin/env bash
# Builds the BigMoeOnEdge engine (third_party/BigMoeOnEdge) for arm64 Android
# as one self-contained program and puts it where the APK build picks it up:
#   app/src/main/jniLibs/arm64-v8a/libbmoe-cli.so
#
# It is linked statically so it brings its own copy of llama.cpp and does not
# clash with the libllama.so / libggml*.so the app's JNI bridge uses.
#
# Usage: scripts/build_engine.sh      (then build the app with ./gradlew assembleDebug)
# Needs the Android SDK with NDK 29.0.13113456 and CMake 3.31.6, the same as the app.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/third_party/BigMoeOnEdge"
NDK_VERSION=29.0.13113456
CMAKE_VERSION=3.31.6

die() { echo "error: $*" >&2; exit 1; }

[ -f "$SRC/CMakeLists.txt" ] && [ -f "$SRC/third_party/llama.cpp/CMakeLists.txt" ] || die \
    "engine source missing. Run: git -c core.longpaths=true submodule update --init --recursive --depth 1"

SDK=""
for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${LOCALAPPDATA:-}/Android/Sdk" \
                 "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [ -n "$candidate" ] && [ -d "$candidate/ndk/$NDK_VERSION" ]; then SDK="$candidate"; break; fi
done
[ -n "$SDK" ] || die "Android SDK with NDK $NDK_VERSION not found. Set ANDROID_HOME."
NDK="$SDK/ndk/$NDK_VERSION"
CMAKE_BIN="$SDK/cmake/$CMAKE_VERSION/bin"
[ -d "$CMAKE_BIN" ] || die "CMake $CMAKE_VERSION not found in the SDK ($CMAKE_BIN)."
export PATH="$CMAKE_BIN:$PATH"   # cmake and the ninja that ships with it

BUILD="$ROOT/app/build/bmoe"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"

# The engine targets ARMv8.2 with dot-product and half-float instructions, as
# its own build script does. Phones older than about 2018 cannot run it.
cmake -S "$SRC" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-28 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DBMOE_BUILD_TESTS=OFF \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENCL=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_CPU_ARM_ARCH="armv8.2-a+dotprod+fp16" \
    -DLLAMA_CURL=OFF
cmake --build "$BUILD" --target bmoe-cli -j

STRIP="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip* | head -n 1)"
mkdir -p "$OUT"
# Android only lets an app run programs from its native library directory, and
# only packages files named lib*.so there; hence the name.
"$STRIP" -o "$OUT/libbmoe-cli.so" "$BUILD/cli/bmoe-cli"
echo "engine: $OUT/libbmoe-cli.so ($(wc -c < "$OUT/libbmoe-cli.so") bytes)"
