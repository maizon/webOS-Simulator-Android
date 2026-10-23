#!/usr/bin/env python3
"""Fetch prebuilt x86_64 QEMU user-static from Debian for CI ONLY.

Never included in the Android APK. The package SHA is verified against Debian
bookworm Packages.xz. This avoids apt-get operations on GitHub runners.
"""
import hashlib
import lzma
import pathlib
import subprocess
import sys
import urllib.request

dest = pathlib.Path(sys.argv[1])
dest.mkdir(parents=True, exist_ok=True)
index = "https://deb.debian.org/debian/dists/bookworm/main/binary-amd64/Packages.xz"
with urllib.request.urlopen(index, timeout=100) as res:
    data = lzma.decompress(res.read()).decode("utf-8")
pkg = None
for paragraph in data.split("\n\n"):
    if paragraph.startswith("Package: qemu-user-static\n"):
        pkg = dict(line.split(": ", 1) for line in paragraph.splitlines()
                   if ": " in line and not line.startswith(" "))
        break
if not pkg:
    raise SystemExit("qemu-user-static missing from Debian amd64 index")
url = "https://deb.debian.org/debian/" + pkg["Filename"]
print("Download pinned QEMU-user-static CI package:", url, flush=True)
with urllib.request.urlopen(url, timeout=120) as res:
    content = res.read()
if hashlib.sha256(content).hexdigest() != pkg["SHA256"]:
    raise SystemExit("QEMU SHA256 mismatch")
package = dest / "qemu-user-static.deb"
package.write_bytes(content)
subprocess.run(["dpkg-deb", "-x", str(package), str(dest)], check=True)
package.unlink()
for name in ("qemu-aarch64-static", "qemu-arm-static"):
    path = dest / "usr/bin" / name
    if not path.is_file():
        raise SystemExit("QEMU binary missing: " + str(path))
    path.chmod(0o755)
print("QEMU Linux user-mode test binaries ready")
