#!/usr/bin/env bash
# Build small Debian Linux ARM64/armhf sysroots and real Linux ELF probes.
# QEMU used only on the CI host, never installed into the APK.
# Linux guests use NDK clang -nostdlib and direct Linux syscalls (no Android ABI).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/.native-cache/debian"
OUT="$ROOT/app/src/main/assets/native-linux"
NDK_VERSION="${MAIZON_NDK_VERSION:-29.0.14206865}"
NDK="${ANDROID_NDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}/ndk/$NDK_VERSION}"
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
mkdir -p "$CACHE" "$OUT"

python3 "$ROOT/tools/native/fetch-qemu-test.py" "$CACHE/qemu"

for pair in "arm64 arm64-v8a aarch64-linux-android26-clang aarch64" \
            "armhf armeabi-v7a armv7a-linux-androideabi26-clang arm"; do
  read -r debarch abi compiler guest <<< "$pair"
  stage="$CACHE/$debarch/sysroot"
  rm -rf "$stage"
  mkdir -p "$stage/bin" "$stage/app" "$stage/webos" "$stage/dev" "$stage/proc" "$stage/tmp" "$stage/usr/share"
  echo "Building Linux sysroot $debarch ($abi)"
  python3 "$ROOT/tools/native/fetch-debian-libs.py" "$debarch" "$stage" \
    libc6 busybox-static libgcc-s1 libstdc++6 zlib1g
  "$TC/$compiler" -O2 -nostdlib -static -Wl,-e,_start \
    "$ROOT/tools/native/guest-probe-$guest.S" -o "$stage/bin/maizon-probe"
  chmod 755 "$stage/bin/maizon-probe"
  if [ ! -e "$stage/bin/sh" ]; then ln -s busybox "$stage/bin/sh"; fi
  "$TC/llvm-readelf" -h "$stage/bin/maizon-probe" | grep -E 'Machine:|Type:'
  "$CACHE/qemu/usr/bin/qemu-$guest-static" -L "$stage" "$stage/bin/maizon-probe" \
    | grep -F 'MAIZON_LINUX_GUEST_OK'
  "$CACHE/qemu/usr/bin/qemu-$guest-static" -L "$stage" "$stage/bin/busybox" echo "$debarch-rootfs-smoke-ok" \
    | grep -F "$debarch-rootfs-smoke-ok"
  tar -czf "$OUT/$abi-rootfs.bundle" -C "$stage" .
  printf 'Built %s: ' "$OUT/$abi-rootfs.bundle"
  du -h "$OUT/$abi-rootfs.bundle"
done
