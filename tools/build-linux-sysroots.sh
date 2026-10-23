#!/usr/bin/env bash
# Construct two small, legal Debian Linux sysroots for the native application.
# Debian bookworm packages are downloaded with SHA256 from Debian's signed
# archive metadata endpoint (transport is HTTPS; SHA checked against Packages).
# Binaries from these rootfs images remain subject to Debian's package licenses.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/.native-cache/debian"
OUT="$ROOT/app/src/main/assets/native-linux"
mkdir -p "$CACHE" "$OUT"
source="$ROOT/tools/native/guest-probe.c"
for pair in "arm64 arm64-v8a aarch64-linux-gnu qemu-aarch64" \
            "armhf armeabi-v7a arm-linux-gnueabihf qemu-arm"; do
  read -r debarch abi triple qemu <<< "$pair"
  stage="$CACHE/$debarch/sysroot"
  rm -rf "$stage"
  mkdir -p "$stage/bin" "$stage/app" "$stage/dev" "$stage/proc" "$stage/tmp" "$stage/usr/share"
  echo "Building Linux sysroot $debarch ($abi)"
  python3 "$ROOT/tools/native/fetch-debian-libs.py" "$debarch" "$stage" \
    libc6 busybox-static libgcc-s1 libstdc++6 zlib1g
  "${triple}-gcc" -O2 -static -fPIE -pie "$source" -o "$stage/bin/maizon-probe"
  chmod 755 "$stage/bin/maizon-probe"
  if [ ! -e "$stage/bin/sh" ]; then ln -s busybox "$stage/bin/sh"; fi
  "$qemu" -L "$stage" "$stage/bin/maizon-probe" | grep -F 'MAIZON_LINUX_GUEST_OK'
  "$qemu" -L "$stage" "$stage/bin/busybox" echo "$debarch-rootfs-smoke-ok" \
    | grep -F "$debarch-rootfs-smoke-ok"
  tar -czf "$OUT/$abi-rootfs.tar.gz" -C "$stage" .
  printf 'Built %s: ' "$OUT/$abi-rootfs.tar.gz"
  du -h "$OUT/$abi-rootfs.tar.gz"
done
