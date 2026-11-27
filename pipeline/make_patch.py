#!/usr/bin/env python3
"""
Makes an update patch: the bytes needed to turn one release APK into the next on the phone.

    python3 pipeline/make_patch.py OLD.apk NEW.apk OUT.patch

The app (update/Bspatch.kt) rebuilds NEW.apk byte for byte from the installed APK plus the patch,
checks its SHA-256 against the release's .sha256 and installs it. The signature stays valid because
the result is the exact signed file.

Format "DIAPAT01" (all integers big-endian, signed 64-bit):
    magic        8 bytes  b"DIAPAT01"
    old size     int64
    new size     int64
    old sha-256  32 bytes  (the installed APK must match, else the app downloads the full APK)
    new sha-256  32 bytes
    ctrl length  int64     (compressed)
    diff length  int64     (compressed)
    ctrl   zlib: triples (x, y, z) — add x bytes of diff to old, copy y bytes of extra, move old by z
    diff   zlib
    extra  zlib, to the end of the file

This is bsdiff (Colin Percival's algorithm, via the bsdiff4 package) with zlib instead of bzip2, so the
phone can unpack it with java.util.zip and no extra library.
"""
import hashlib
import struct
import sys
import zlib

MAGIC = b"DIAPAT01"


def make(old: bytes, new: bytes) -> bytes:
    import bsdiff4.core  # pip install bsdiff4

    control, diff, extra = bsdiff4.core.diff(old, new)
    ctrl = b"".join(struct.pack(">qqq", x, y, z) for x, y, z in _triples(control))
    c_ctrl = zlib.compress(ctrl, 9)
    c_diff = zlib.compress(diff, 9)
    c_extra = zlib.compress(extra, 9)
    header = MAGIC + struct.pack(">qq", len(old), len(new)) + hashlib.sha256(old).digest() + hashlib.sha256(new).digest()
    return header + struct.pack(">qq", len(c_ctrl), len(c_diff)) + c_ctrl + c_diff + c_extra


def _offtin(b: bytes) -> int:
    """bsdiff's 8-byte sign-magnitude little-endian integer."""
    v = int.from_bytes(b, "little") & ~(1 << 63)
    return -v if b[7] & 0x80 else v


def _triples(control):
    """bsdiff4.core.diff gives the control block as packed offtin bytes (newer versions) or as tuples."""
    if isinstance(control, (bytes, bytearray)):
        vals = [_offtin(control[i:i + 8]) for i in range(0, len(control), 8)]
        return [tuple(vals[i:i + 3]) for i in range(0, len(vals), 3)]
    return [tuple(t) for t in control]


def apply(old: bytes, patch: bytes) -> bytes:
    """Reference implementation of what the app does, used to check every patch before it's published."""
    if patch[:8] != MAGIC:
        raise ValueError("not a DIAPAT01 patch")
    old_size, new_size = struct.unpack(">qq", patch[8:24])
    old_sha, new_sha = patch[24:56], patch[56:88]
    if len(old) != old_size or hashlib.sha256(old).digest() != old_sha:
        raise ValueError("patch is for a different base")
    ctrl_len, diff_len = struct.unpack(">qq", patch[88:104])
    p = 104
    ctrl = zlib.decompress(patch[p:p + ctrl_len]); p += ctrl_len
    diff = zlib.decompress(patch[p:p + diff_len]); p += diff_len
    extra = zlib.decompress(patch[p:])
    import numpy as np

    old_arr = np.frombuffer(old, dtype=np.uint8)
    out = bytearray()
    old_pos = d = e = 0
    for i in range(0, len(ctrl), 24):
        x, y, z = struct.unpack(">qqq", ctrl[i:i + 24])
        seg = np.frombuffer(diff, dtype=np.uint8, count=x, offset=d).copy(); d += x
        lo, hi = max(old_pos, 0), min(old_pos + x, old_size)
        if lo < hi:
            seg[lo - old_pos:hi - old_pos] += old_arr[lo:hi]  # wraps mod 256, like the phone
        out += seg.tobytes()
        out += extra[e:e + y]; e += y
        old_pos += x + z
    if len(out) != new_size or hashlib.sha256(out).digest() != new_sha:
        raise ValueError("result doesn't match")
    return bytes(out)


def main():
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    old = open(sys.argv[1], "rb").read()
    new = open(sys.argv[2], "rb").read()
    patch = make(old, new)
    open(sys.argv[3], "wb").write(patch)
    print(f"{sys.argv[3]}: {len(patch) / 1e6:.2f} MB (full APK {len(new) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
