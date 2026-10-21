#!/usr/bin/env python3
"""Structural fingerprints of v83 WZ data, comparable across the client's binary .img files and
the server's XML dump.

Standard library only (Python 3.8+), because the same file is copied to the owner's Windows
desktop and run there next to the client's ``Data`` folder: hashing 23 GB remotely and
shipping back a few MB of JSON beats copying the files.

The fingerprint is a Merkle hash over the property tree that ignores everything the server
XML cannot carry faithfully: the DATA digest treats every canvas/UOL as an opaque art node (see
node_hash), the ART digest keeps canvas sizes and children but never pixels; a sound is just
"a sound". Ints of every width compare equal,
floats compare to 6 significant digits, children are compared as a multiset keyed by name
(so a re-ordered export does not show as a change), and Convex2D children are numbered.

Binary string decryption needs the GMS AES key stream. AES is not in the standard library, so
the caller (clientcheck.py, which has pycryptodome) generates the stream once and passes it in
as a file; ``make_keystream`` below is that generator.

CLI (remote side)::

    python wzscan.py scan --data <Data dir> --keystream ks.bin --cache scan.json.gz \
        --dirs Map Mob Npc ... [--dump UI/UIWindow.img ...] [--hash-file X ...] [--jobs 8]
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import mmap
import os
import struct
import sys
import time
from collections import Counter

SCAN_VERSION = 9
HLEN = 12          # hex chars kept per hash; 48 bits is plenty to tell two trees apart

GMS_IV = bytes.fromhex("4d23c72b")
AES_KEY = bytes.fromhex("130000000800000006000000b40000001b0000000f0000003300000052000000")


def make_keystream(size=1 << 20):
    """GMS WZ key stream (AES-256 ECB chained from the IV). Needs pycryptodome."""
    from Crypto.Cipher import AES  # noqa: local side only
    aes = AES.new(AES_KEY, AES.MODE_ECB)
    out = bytearray()
    block = GMS_IV * 4
    while len(out) < size:
        block = aes.encrypt(bytes(block))
        out += block
    return bytes(out[:size])


class Masks:
    """Pre-combined XOR masks (key stream ^ the WZ per-char mask) for fast string decryption."""

    def __init__(self, ks: bytes):
        n = len(ks)
        self.ascii = bytes(ks[i] ^ ((0xAA + i) & 0xFF) for i in range(n))
        uni = bytearray(n)
        for i in range(n // 2):
            m = (0xAAAA + i) & 0xFFFF
            uni[2 * i] = ks[2 * i] ^ (m & 0xFF)
            uni[2 * i + 1] = ks[2 * i + 1] ^ (m >> 8)
        self.uni = bytes(uni)


class FormatError(ValueError):
    pass


def _xor(raw: bytes, mask: bytes) -> bytes:
    n = len(raw)
    if n > len(mask):
        raise FormatError("string longer than key stream")
    return (int.from_bytes(raw, "little") ^ int.from_bytes(mask[:n], "little")).to_bytes(n, "little")


# ---------------------------------------------------------------------------------------------
# Binary .img parser -> (name, kind, value, children) tuples. Canvas/sound payloads are skipped.
# kinds: D dir, I int, F float, S string, V vector, U uol, C canvas, A sound, X convex, N null
# ---------------------------------------------------------------------------------------------
class ImgReader:
    def __init__(self, data, masks: Masks):
        self.d = data
        self.m = masks
        self.p = 0
        self.memo = {}

    def u8(self):
        v = self.d[self.p]
        self.p += 1
        return v

    def i8(self):
        v = self.d[self.p]
        self.p += 1
        return v - 256 if v > 127 else v

    def cint(self):
        v = self.i8()
        if v == -128:
            v = struct.unpack_from("<i", self.d, self.p)[0]
            self.p += 4
        return v

    def clong(self):
        v = self.i8()
        if v == -128:
            v = struct.unpack_from("<q", self.d, self.p)[0]
            self.p += 8
        return v

    def string(self):
        size = self.i8()
        if size == 0:
            return ""
        if size > 0:
            n = struct.unpack_from("<i", self.d, self.p)[0] if size == 127 else size
            if size == 127:
                self.p += 4
            raw = bytes(self.d[self.p:self.p + 2 * n])
            self.p += 2 * n
            return _xor(raw, self.m.uni).decode("utf-16le", "replace")
        n = struct.unpack_from("<i", self.d, self.p)[0] if size == -128 else -size
        if size == -128:
            self.p += 4
        if not 0 <= n <= 10_000_000:
            raise FormatError("bad string length")
        raw = bytes(self.d[self.p:self.p + n])
        self.p += n
        return _xor(raw, self.m.ascii).decode("latin1")

    def string_at(self, off):
        s = self.memo.get(off)
        if s is None:
            save = self.p
            self.p = off
            s = self.string()
            self.p = save
            self.memo[off] = s
        return s

    def string_block(self):
        tag = self.u8()
        if tag in (0, 0x73):
            return self.string()
        if tag in (1, 0x1B):
            off = struct.unpack_from("<i", self.d, self.p)[0]
            self.p += 4
            return self.string_at(off)
        raise FormatError(f"string block tag {tag:#x} at {self.p}")

    def properties(self):
        count = self.cint()
        if not 0 <= count <= 1_000_000:
            raise FormatError("bad property count")
        out = []
        for _ in range(count):
            name = self.string_block()
            tag = self.u8()
            if tag == 0:
                out.append((name, "N", None, ()))
            elif tag in (2, 11):
                out.append((name, "I", struct.unpack_from("<h", self.d, self.p)[0], ()))
                self.p += 2
            elif tag in (3, 19):
                out.append((name, "I", self.cint(), ()))
            elif tag == 20:
                out.append((name, "I", self.clong(), ()))
            elif tag == 4:
                if self.u8() == 0x80:
                    v = struct.unpack_from("<f", self.d, self.p)[0]
                    self.p += 4
                else:
                    v = 0.0
                out.append((name, "F", v, ()))
            elif tag == 5:
                out.append((name, "F", struct.unpack_from("<d", self.d, self.p)[0], ()))
                self.p += 8
            elif tag == 8:
                out.append((name, "S", self.string_block(), ()))
            elif tag == 9:
                length = struct.unpack_from("<I", self.d, self.p)[0]
                self.p += 4
                end = self.p + length
                out.append(self.extended(name, end))
                self.p = end
            else:
                raise FormatError(f"property tag {tag:#x} at {self.p}")
        return out

    def extended(self, name, end=None):
        kind = self.string_block()
        if kind == "Property":
            self.p += 2
            return (name, "D", None, self.properties())
        if kind == "Canvas":
            self.p += 1
            has = self.u8()
            children = []
            if has:
                self.p += 2
                children = self.properties()
            # dump tooling stamps some canvases with a pixel digest; either side may or may not carry it
            children = [c for c in children if c[0] != "_hash"]
            w, h = self.cint(), self.cint()
            self.cint()
            self.p += 1 + 4
            size = struct.unpack_from("<i", self.d, self.p)[0]
            self.p += 4 + size
            return (name, "C", (w, h), children)
        if kind == "Shape2D#Vector2D":
            return (name, "V", (self.cint(), self.cint()), ())
        if kind == "Shape2D#Convex2D":
            n = self.cint()
            return (name, "X", None, [self.extended(str(i)) for i in range(n)])
        if kind == "UOL":
            self.p += 1
            return (name, "U", self.string_block(), ())
        if kind == "Sound_DX8":
            if end is None:
                raise FormatError("sound outside a property")
            self.p = end
            return (name, "A", None, ())
        raise FormatError(f"extended type {kind!r}")


def parse_img_bytes(data, masks: Masks):
    r = ImgReader(data, masks)
    if r.string_block() != "Property":
        raise FormatError("not a Property image (wrong key?)")
    r.p += 2
    return ("", "D", None, r.properties())


# ---------------------------------------------------------------------------------------------
# Server XML -> the same tuples
# ---------------------------------------------------------------------------------------------
_XML_KIND = {"imgdir": "D", "int": "I", "short": "I", "long": "I", "float": "F", "double": "F",
             "string": "S", "vector": "V", "uol": "U", "canvas": "C", "sound": "A",
             "extended": "X", "null": "N"}


def _xml_node(el):
    k = _XML_KIND.get(el.tag)
    if k is None:
        raise FormatError(f"xml tag {el.tag}")
    a = el.attrib
    name = a.get("name", "")
    v = None
    if k == "I":
        v = int(a.get("value", "0"))
    elif k == "F":
        v = float(a.get("value", "0"))
    elif k in ("S", "U"):
        v = a.get("value", "")
    elif k == "V":
        v = (int(a.get("x", "0")), int(a.get("y", "0")))
    elif k == "C":
        v = (int(a.get("width", "0")), int(a.get("height", "0")))
    # Some dumps tag canvases with a pixel digest (<string name="_hash">); ignored on both sides.
    kids = [_xml_node(c) for c in el if not (k == "C" and c.get("name") == "_hash")]
    return (name, k, v, kids)


def parse_xml_file(path):
    import xml.etree.ElementTree as ET
    root = ET.parse(path).getroot()
    n = _xml_node(root)
    return ("", "D", None, n[3])


# ---------------------------------------------------------------------------------------------
# Fingerprints
# ---------------------------------------------------------------------------------------------
_WS = str.maketrans({"\n": " ", "\t": " "})


def norm_str(v):
    """XML attribute-value normalisation, applied to BOTH sides: the server dump stores strings in
    attributes, where a raw newline/tab reads back as a space."""
    return v.replace("\r\n", "\n").replace("\r", "\n").translate(_WS)


def _val(kind, v):
    if v is None:
        return ""
    if kind in ("S", "U"):
        return norm_str(v)
    if kind == "F":
        return format(v, ".6g")
    if kind in ("V", "C"):
        return f"{v[0]},{v[1]}"
    return str(v)


_ART = ("C", "U")


def node_hash(node):
    """(data digest, art digest) of a subtree.

    DATA ignores art entirely: every canvas and UOL is just "an art node called <name>" -- no size, no
    origin, no children. The server dump is lossy there (UOLs and outlinked canvases come out as 1x1
    placeholders with an _outlink), so art compares noisily; data (stats, footholds, portals, strings,
    delays outside canvases) compares exactly. ART is the full tree, kept for an informational count.
    """
    name, kind, v, kids = node
    if kind == "X":
        kids = [(str(i),) + tuple(c[1:]) for i, c in enumerate(kids)]
    if kind == "C":
        kids = [c for c in kids if c[0] != "_outlink"]
    a = hashlib.sha1(f"{kind}\0{name}\0{_val(kind, v)}\0".encode("utf-8", "surrogatepass"))
    if kind in _ART:
        d = hashlib.sha1(f"C\0{name}\0\0".encode("utf-8", "surrogatepass"))
    else:
        d = hashlib.sha1(f"{kind}\0{name}\0{_val(kind, v)}\0".encode("utf-8", "surrogatepass"))
    if kids:
        subs = [node_hash(c) for c in kids]
        for x in sorted(t[1] for t in subs):
            a.update(x)
        if kind not in _ART:
            for x in sorted(t[0] for t in subs):
                d.update(x)
    return d.digest(), a.digest()


def flatten(tree, art=False):
    """{path: "kind value"} for a field-level diff; duplicate sibling names get a #n suffix. Without
    `art`, canvases and UOLs are opaque ("art"), matching the data digest."""
    out = {}
    def walk(n, pre):
        seen = Counter()
        kids = n[3]
        if n[1] == "X":
            kids = [(str(i),) + tuple(c[1:]) for i, c in enumerate(kids)]
        for c in kids:
            seen[c[0]] += 1
            name = c[0] if seen[c[0]] == 1 else f"{c[0]}#{seen[c[0]]}"
            if c[0] == "_outlink" and n[1] == "C":
                continue
            p = f"{pre}/{name}" if pre else name
            out[p] = f"{c[1]} {_val(c[1], c[2])}"
            if c[1] in _ART and not art:
                out[p] = "art"
                continue
            walk(c, p)
    walk(tree, "")
    return out


def normalize(tree, rel):
    """Remove differences that v83 never reads, applied to BOTH sides before hashing or diffing.

    - Mob/*.img: the MapleRoot client uses the later layout for attack data: the attack's stat leaves
      (type, magic, conMP, disease, level, action ...) sit in ``info/attack/<N-1>`` and ``attackN/info``
      keeps only the visual part (range, hit, effect ...). The server XML has all of it in ``attackN/info``.
      Both are folded into the server layout (``info/attack/<i>`` leaves merge into ``attack<i+1>/info``,
      an existing leaf wins), so only real value differences remain. Duplicate leaf names under ``info``
      (e.g. two ``acc`` nodes) keep the first, as the client's property lookup does.
    - Map/Map/*.img: footholds are compared by layer and id, not by zMass group. The group number
      (``foothold/<layer>/<group>/<id>``) is used by neither Cosmic nor bot nav, and MapleRoot's client
      and server number the groups differently on some maps with identical geometry.
    """
    name, kind, v, kids = tree
    if rel.startswith("Mob/"):
        later = {}
        for c in kids:
            if c[0] == "info" and c[1] == "D":
                for g in c[3]:
                    if g[0] == "attack" and g[1] == "D":
                        for a in g[3]:
                            if a[0].isdigit() and a[1] == "D":
                                later.setdefault(f"attack{int(a[0]) + 1}", []).extend(x for x in a[3] if not x[3])
        if later:
            merged = []
            for c in kids:
                if c[0] in later and c[1] == "D":
                    sub = list(c[3])
                    idx = next((i for i, x in enumerate(sub) if x[0] == "info" and x[1] == "D"), None)
                    have = set(x[0] for x in sub[idx][3]) if idx is not None else set()
                    add = [x for x in later.pop(c[0]) if x[0] not in have]
                    if idx is None:
                        sub.append(("info", "D", None, add))
                    else:
                        sub[idx] = (sub[idx][0], "D", sub[idx][2], list(sub[idx][3]) + add)
                    c = (c[0], c[1], c[2], sub)
                merged.append(c)
            kids = merged
        out = []
        for c in kids:
            if c[0] == "info" and c[1] == "D":
                seen, ik = set(), []
                for g in c[3]:
                    if g[0] == "attack" and g[1] == "D":
                        continue
                    if not g[3] and g[0] in seen:
                        continue
                    seen.add(g[0])
                    ik.append(g)
                c = (c[0], c[1], c[2], ik)
            out.append(c)
        return (name, kind, v, out)
    if rel.startswith("Map/Map/"):
        out = []
        for c in kids:
            if c[0] == "foothold" and c[1] == "D":
                layers = []
                for layer in c[3]:
                    fhs = [fh for grp in layer[3] if grp[1] == "D" for fh in grp[3]]
                    other = [grp for grp in layer[3] if grp[1] != "D"]
                    layers.append((layer[0], layer[1], layer[2], fhs + other))
                c = (c[0], c[1], c[2], layers)
            out.append(c)
        return (name, kind, v, out)
    return tree


def summarize(tree, rel, dump=False):
    """The per-image record both sides produce."""
    tree = normalize(tree, rel)
    top = {}
    hroot = hashlib.sha1(b"D\0\0\0")
    aroot = hashlib.sha1(b"D\0\0\0")
    digests = []
    for c in tree[3]:
        d = node_hash(c)
        digests.append(d)
        # duplicate top-level names: keep the first, as the client and the XML loader do
        top.setdefault(c[0], d[0].hex()[:HLEN])
    for d in sorted(x[0] for x in digests):
        hroot.update(d)
    for d in sorted(x[1] for x in digests):
        aroot.update(d)
    rec = {"h": hroot.hexdigest()[:HLEN], "a": aroot.hexdigest()[:HLEN], "top": top}
    icons = []
    def has_icon(n):
        for c in n[3]:
            if c[0] == "info" and c[1] == "D":
                return any(g[0] in ("icon", "iconRaw") and g[1] in _ART for g in c[3])
        return False
    if has_icon(tree):
        icons.append("")
    for c in tree[3]:
        if c[1] == "D" and has_icon(c):
            icons.append(c[0])
    if icons:
        rec["icons"] = icons
    if rel.startswith("String/"):
        names = {}
        def walk(n):
            for c in n[3]:
                if c[1] != "D":
                    continue
                nm = next((g[2] for g in c[3] if g[0] == "name" and g[1] == "S"), None)
                if nm is not None and c[0].isdigit():
                    names.setdefault(str(int(c[0])), norm_str(nm))
                walk(c)
        walk(tree)
        rec["names"] = names
    if dump:
        paths = []
        def walk2(n, pre):
            for c in n[3]:
                p = f"{pre}/{c[0]}" if pre else c[0]
                paths.append(f"{c[1]}:{p}")
                walk2(c, p)
        walk2(tree, "")
        rec["paths"] = paths
    return rec


# ---------------------------------------------------------------------------------------------
# PKG1 archive reader (Custom.wz / List.wz style); yields (path, bytes) for every image
# ---------------------------------------------------------------------------------------------
def pkg1_images(path, masks: Masks, version=83):
    with open(path, "rb") as f:
        data = f.read()
    if data[:4] != b"PKG1":
        raise FormatError("not PKG1")
    start = struct.unpack_from("<I", data, 12)[0]
    vh = 0
    for ch in str(version):
        vh = ((vh << 5) + ord(ch) + 1) & 0xFFFFFFFF
    enc = 0xFF
    for s in (0, 8, 16, 24):
        enc ^= (vh >> s) & 0xFF
    if struct.unpack_from("<H", data, start)[0] != enc:
        raise FormatError(f"not a v{version} archive")

    def omask(pos):
        v = (((pos - start) ^ 0xFFFFFFFF) * vh - 0x581C3F6D) & 0xFFFFFFFF
        s = v & 31
        return ((v << s) | (v >> ((32 - s) & 31))) & 0xFFFFFFFF

    out = []

    def directory(pos, prefix):
        r = ImgReader(data, masks)
        r.p = pos
        count = r.cint()
        entries = []
        for _ in range(count):
            tag = r.u8()
            if tag == 2:
                off = struct.unpack_from("<i", data, r.p)[0] + start
                r.p += 4
                nr = ImgReader(data, masks)
                nr.p = off
                tag = nr.u8()
                name = nr.string()
            elif tag in (3, 4):
                name = r.string()
            else:
                raise FormatError(f"dir entry {tag:#x}")
            size = r.cint()
            r.cint()
            o = (struct.unpack_from("<I", data, r.p)[0] ^ omask(r.p)) + 2 * start
            r.p += 4
            entries.append((tag, name, size, o & 0xFFFFFFFF))
        for tag, name, size, o in entries:
            p = f"{prefix}/{name}" if prefix else name
            if tag == 3:
                directory(o, p)
            else:
                out.append((p, data[o:o + size]))

    directory(start + 2, "")
    return out


# ---------------------------------------------------------------------------------------------
# Remote scan
# ---------------------------------------------------------------------------------------------
_MASKS = None


def _init_worker(ks_path):
    global _MASKS
    with open(ks_path, "rb") as f:
        _MASKS = Masks(f.read())


def _scan_one(args):
    full, rel, dump = args
    try:
        with open(full, "rb") as f:
            size = os.fstat(f.fileno()).st_size
            if size == 0:
                return rel, {"err": "empty file"}
            with mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as mm:
                tree = parse_img_bytes(mm, _MASKS)
        return rel, summarize(tree, rel, dump)
    except Exception as e:  # noqa: a bad file is a finding, not a crash
        return rel, {"err": f"{type(e).__name__}: {e}"[:300]}


def cmd_scan(a):
    cache = {}
    if os.path.exists(a.cache):
        try:
            with gzip.open(a.cache, "rt", encoding="utf-8") as f:
                old = json.load(f)
            if old.get("version") == SCAN_VERSION and old.get("data") == a.data:
                cache = old.get("files", {})
        except Exception:
            cache = {}
    dumps = set(a.dump or [])
    todo, files = [], {}
    for top in a.dirs:
        base = os.path.join(a.data, top)
        for dp, _, fns in os.walk(base):
            for fn in fns:
                if not fn.endswith(".img"):
                    continue
                full = os.path.join(dp, fn)
                rel = os.path.relpath(full, a.data).replace("\\", "/")
                st = os.stat(full)
                key = [st.st_size, int(st.st_mtime)]
                want_dump = rel in dumps
                c = cache.get(rel)
                if c and c.get("k") == key and (not want_dump or "paths" in c or "err" in c):
                    files[rel] = c
                else:
                    todo.append((full, rel, want_dump, key))
    t0 = time.time()
    if todo:
        from multiprocessing import Pool
        keys = {rel: key for _, rel, _, key in todo}
        with Pool(a.jobs, initializer=_init_worker, initargs=(a.keystream,)) as pool:
            for i, (rel, rec) in enumerate(pool.imap_unordered(
                    _scan_one, [(f, r, d) for f, r, d, _ in todo], chunksize=16)):
                rec["k"] = keys[rel]
                files[rel] = rec
                if i % 2000 == 0:
                    print(f"  scanned {i}/{len(todo)} ({time.time() - t0:.0f}s)", file=sys.stderr, flush=True)
    extra = {}
    for hf in a.hash_file or []:
        if os.path.isfile(hf):
            h = hashlib.sha256()
            with open(hf, "rb") as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            extra[os.path.basename(hf)] = {"sha256": h.hexdigest(), "size": os.path.getsize(hf)}
        else:
            extra[os.path.basename(hf)] = None
    out = {"version": SCAN_VERSION, "data": a.data, "scanned_at": int(time.time()),
           "rescanned": len(todo), "files": files, "extra": extra}
    tmp = a.cache + ".tmp"
    with gzip.open(tmp, "wt", encoding="utf-8", compresslevel=6) as f:
        json.dump(out, f, separators=(",", ":"))
    os.replace(tmp, a.cache)
    print(f"ok files={len(files)} rescanned={len(todo)} secs={time.time() - t0:.0f}")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("scan")
    s.add_argument("--data", required=True)
    s.add_argument("--keystream", required=True)
    s.add_argument("--cache", required=True)
    s.add_argument("--dirs", nargs="+", required=True)
    s.add_argument("--dump", nargs="*")
    s.add_argument("--hash-file", nargs="*")
    s.add_argument("--jobs", type=int, default=max(1, (os.cpu_count() or 2) - 2))
    a = ap.parse_args()
    if a.cmd == "scan":
        cmd_scan(a)


if __name__ == "__main__":
    main()
