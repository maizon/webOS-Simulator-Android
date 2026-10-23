#!/usr/bin/env python3
"""Fetch a tiny Debian bookworm sysroot. Uses Debian HTTPS archive checksums.

This helper runs ONLY in GitHub Actions/CI, not on Android phones.
Fetches Packages.xz for the selected architecture and verifies every .deb.
"""
import hashlib
import lzma
import pathlib
import subprocess
import sys
import urllib.request

arch, root, *wanted = sys.argv[1:]
if arch not in {"arm64", "armhf"}:
    raise SystemExit("unsupported Debian architecture")
root = pathlib.Path(root)
url = f"https://deb.debian.org/debian/dists/bookworm/main/binary-{arch}/Packages.xz"
print("fetching", url, flush=True)
with urllib.request.urlopen(url, timeout=70) as response:
    metadata = lzma.decompress(response.read()).decode("utf-8")
packages = {}
for stanza in metadata.split("\n\n"):
    lines = dict(
        line.split(": ", 1) for line in stanza.splitlines()
        if ": " in line and not line.startswith(" ")
    )
    name = lines.get("Package")
    if name in wanted and name not in packages:
        packages[name] = lines
for name in wanted:
    item = packages.get(name)
    if not item:
        raise SystemExit(f"Debian package missing: {name} for {arch}")
    path = item["Filename"]
    digest = item["SHA256"]
    fetch = "https://deb.debian.org/debian/" + path
    print("download", name, item["Version"], fetch, flush=True)
    with urllib.request.urlopen(fetch, timeout=90) as response:
        content = response.read()
    if hashlib.sha256(content).hexdigest() != digest:
        raise SystemExit(f"SHA mismatch from Debian package index: {path}")
    debfile = root.parent / f"{name}_{arch}.deb"
    debfile.write_bytes(content)
    subprocess.run(["dpkg-deb", "-x", str(debfile), str(root)], check=True)
    debfile.unlink()
print("Rootfs validated:", ", ".join(wanted))
