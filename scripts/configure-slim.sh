#!/usr/bin/env bash
# Slim libvpx for Telegram video stickers (docs/VPX.md).
# Usage: ANDROID_NDK_HOME=... scripts/configure-slim.sh <abi> [decode|encode]
# ABI: armeabi-v7a | arm64-v8a | x86_64
set -euo pipefail
set +o histexpand

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ABI="${1:-x86_64}"
MODE="${2:-decode}"
SRC="${LIBVPX_SRC:-$ROOT/vendor/libvpx}"
OUT="$ROOT/native/vpx/prebuilt/$ABI"
NDK="${ANDROID_NDK_HOME:-}"
if [[ -n "$NDK" ]] && command -v cygpath >/dev/null 2>&1; then
  NDK="$(cygpath -u "$NDK")"
fi
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "Set ANDROID_NDK_HOME (got '${NDK:-}')" >&2
  exit 1
fi
echo "NDK=$NDK ABI=$ABI MODE=$MODE ROOT=$ROOT"

API=27
case "$ABI" in
  armeabi-v7a)
    TARGET="armv7-android-gcc"
    TRIPLE="armv7a-linux-androideabi${API}"
    EXTRA=(--disable-neon-asm)
    ;;
  arm64-v8a)
    TARGET="arm64-android-gcc"
    TRIPLE="aarch64-linux-android${API}"
    EXTRA=()
    ;;
  x86_64)
    TARGET="x86_64-android-gcc"
    TRIPLE="x86_64-linux-android${API}"
    EXTRA=(--as=nasm)
    ;;
  *)
    echo "unknown abi $ABI" >&2
    exit 1
    ;;
esac

CODEC=(--disable-vp8 --enable-vp9)
if [[ "$MODE" == "encode" ]]; then
  CODEC+=(--disable-vp9-decoder)
else
  CODEC+=(--disable-vp9-encoder)
fi

if [[ ! -d "$SRC/.git" ]]; then
  mkdir -p "$(dirname "$SRC")"
  git clone --depth 1 --branch v1.15.2 https://chromium.googlesource.com/webm/libvpx "$SRC"
fi

HOST_TAG="windows-x86_64"
case "$(uname -s)" in
  Linux) HOST_TAG="linux-x86_64" ;;
  Darwin) HOST_TAG="darwin-x86_64" ;;
esac
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
export PATH="$TOOLCHAIN/bin:/c/msys64/ucrt64/bin:/c/msys64/usr/bin:$PATH"
export CC="$TOOLCHAIN/bin/${TRIPLE}-clang"
export CXX="$TOOLCHAIN/bin/${TRIPLE}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export LD="$CC"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
if [[ "$ABI" == "x86_64" ]]; then
  export AS="$(command -v nasm)"
else
  export AS="$CC"
fi
TMPDIR_HOST="$ROOT/native/vpx/tmp"
mkdir -p "$TMPDIR_HOST"
if command -v cygpath >/dev/null 2>&1; then
  export TMPDIR="$(cygpath -m "$TMPDIR_HOST")"
else
  export TMPDIR="$TMPDIR_HOST"
fi
export TMP="$TMPDIR"
export TEMP="$TMPDIR"

BUILD="$ROOT/native/vpx/build/$ABI"
rm -rf "$BUILD"
mkdir -p "$BUILD" "$OUT"
cd "$BUILD"

"$SRC/configure" \
  --prefix="$OUT" \
  --target="$TARGET" \
  --enable-static --disable-shared \
  --enable-pic \
  --enable-small \
  --enable-realtime-only \
  --enable-multithread \
  --disable-docs --disable-examples --disable-tools --disable-unit-tests \
  --disable-webm-io --disable-libyuv \
  --disable-postproc --disable-vp9-postproc \
  --disable-vp9-highbitdepth \
  --disable-vp9-temporal-denoising \
  --disable-internal-stats \
  --disable-error-concealment \
  --disable-coefficient-range-checking \
  --disable-spatial-resampling \
  --size-limit=512x512 \
  "${CODEC[@]}" \
  "${EXTRA[@]}"

# Windows bash history expansion can corrupt libvpx's "Do not edit!" banners.
if [[ -f libs-${TARGET}.mk ]]; then
  sed -i '/^it!$/d' "libs-${TARGET}.mk" || true
fi
if [[ -f vpx_config.h ]]; then
  sed -i '/^e found in the LICENSE/d;/^\/\* tree\. An additional/d;/^\/\* in the file PATENTS/d;/^\/\* be found in the AUTHORS/d' vpx_config.h || true
fi
if ! grep -q 'ALL_TARGETS' config.mk 2>/dev/null; then
  printf '\nTOOLCHAIN := %s\nALL_TARGETS += libs\n' "$TARGET" >> config.mk
fi

MAKE_BIN="$(command -v make || true)"
if [[ -z "$MAKE_BIN" ]]; then MAKE_BIN=/c/msys64/usr/bin/make; fi
"$MAKE_BIN" -j4
"$MAKE_BIN" install
STRIP_BIN="$TOOLCHAIN/bin/llvm-strip"
if [[ -x "$STRIP_BIN" ]]; then
  "$STRIP_BIN" --strip-unneeded "$OUT/lib/libvpx.a" || true
fi
ls -lh "$OUT/lib/libvpx.a"
