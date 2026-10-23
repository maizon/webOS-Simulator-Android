#!/usr/bin/env bash
# GPL-2.0 PRoot Linux ELF loader for ARM64 and ARM32 Android.
# Build procedure adapted from DroidNative/DroidNativeApp (GPL-3.0+).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/.native-cache"
OUT="$ROOT/app/src/main/jniLibs"
NDK_VERSION="${MAIZON_NDK_VERSION:-29.0.14206865}"
NDK="${ANDROID_NDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}/ndk/$NDK_VERSION}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=26
PROOT_COMMIT="eb906a77c6632c1e69c367c9de289243cb909cab"
TALLOC_VERSION="2.5.0"
TALLOC_SHA256="912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007"
SQLITE_VERSION="3540100"
SQLITE_SHA256="926ef8d3eab93fbf6e06011901273eec076b25594a4e1cdadd50c987f4f6d64e"
JOBS="${MAIZON_BUILD_JOBS:-$(nproc)}"
[ -x "$TC/llvm-strip" ] || { echo "Android NDK missing: $NDK" >&2; exit 1; }
mkdir -p "$CACHE" "$OUT" "$ROOT/app/src/main/assets/native-licenses"

fetch_tar() {
  local url="$1" digest="$2" extracted="$3" file="$CACHE/$(basename "$1")"
  [ -d "$CACHE/$extracted" ] && return
  curl --fail --location --retry 3 --silent --show-error "$url" -o "$file"
  printf '%s  %s\n' "$digest" "$file" | sha256sum -c -
  tar -xf "$file" -C "$CACHE"
}
fetch_tar "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" \
  "$TALLOC_SHA256" "talloc-$TALLOC_VERSION"
fetch_tar "https://www.sqlite.org/2026/sqlite-autoconf-$SQLITE_VERSION.tar.gz" \
  "$SQLITE_SHA256" "sqlite-autoconf-$SQLITE_VERSION"

if [ ! -d "$CACHE/proot/.git" ]; then
  git clone --quiet --filter=blob:none https://github.com/DroidNative/proot.git "$CACHE/proot"
fi
git -C "$CACHE/proot" fetch --quiet --depth 1 origin "$PROOT_COMMIT"
git -C "$CACHE/proot" checkout --quiet --detach "$PROOT_COMMIT"
cp "$CACHE/proot/COPYING" "$ROOT/app/src/main/assets/native-licenses/PROOT_GPL2.txt"

for abi in arm64-v8a armeabi-v7a; do
  case "$abi" in
    arm64-v8a) target="aarch64-linux-android$API" ;;
    armeabi-v7a) target="armv7a-linux-androideabi$API" ;;
  esac
  cc="$TC/${target}-clang"
  temp="$CACHE/build-$abi"
  mkdir -p "$temp/talloc" "$temp/proot" "$OUT/$abi"
  echo "BUILDING native PRoot $abi using $cc"
  "$cc" -c -O2 -fPIC -D_GNU_SOURCE -D__STDC_WANT_LIB_EXT1__=1 \
      -I"$ROOT/tools/native/talloc" \
      -I"$CACHE/talloc-$TALLOC_VERSION/lib/replace" \
      -I"$CACHE/talloc-$TALLOC_VERSION" \
      "$CACHE/talloc-$TALLOC_VERSION/talloc.c" -o "$temp/talloc/talloc.o"
  "$cc" -c -O2 -fPIC "$ROOT/tools/native/talloc/replace_min.c" -o "$temp/talloc/replace_min.o"
  "$TC/llvm-ar" rcs "$temp/talloc/libtalloc.a" "$temp/talloc/talloc.o" "$temp/talloc/replace_min.o"
  cp "$CACHE/talloc-$TALLOC_VERSION/talloc.h" "$temp/talloc/"
  tar -C "$CACHE/proot" --exclude=.git -cf - . | tar -C "$temp/proot" -xf -
  (
    cd "$temp/proot/src"
    CPPFLAGS="-I$temp/talloc -DARG_MAX=131072 -DVERSION=\\\"maizon-${PROOT_COMMIT:0:12}\\\"" \
    LDFLAGS="-L$temp/talloc -Wl,-z,max-page-size=16384" \
    make --jobs="$JOBS" proot \
      CC="$cc" STRIP="$TC/llvm-strip" OBJCOPY="$TC/llvm-objcopy" \
      OBJDUMP="$TC/llvm-objdump" \
      PROOT_UNBUNDLE_LOADER=/nonexistent \
      SQLITE_DIR="$CACHE/sqlite-autoconf-$SQLITE_VERSION"
  )
  "$TC/llvm-strip" -o "$OUT/$abi/libmaizon_proot.so" "$temp/proot/src/proot"
  cp "$temp/proot/src/loader/loader" "$OUT/$abi/libmaizon_loader.so"
  if [ -f "$temp/proot/src/loader/loader-m32" ]; then
    cp "$temp/proot/src/loader/loader-m32" "$OUT/$abi/libmaizon_loader32.so"
  fi
  chmod 755 "$OUT/$abi"/libmaizon_*.so
  file "$OUT/$abi"/libmaizon_*.so
done
echo "Native host binaries ready: ARM64 and ARMv7."
