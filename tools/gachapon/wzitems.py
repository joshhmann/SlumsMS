"""Item facts from the server WZ XML dump, for the gachapon pool tools.

Reads ``Character.wz`` (one file per equip) and ``Item.wz`` (bundled consume/etc/install/cash
files), plus the names in ``String.wz``. Returns one flat dict per item id with what a pool
check needs: name, inventory tab, NPC price, cash/quest/untradeable flags, required level and
scroll success rate. Cached as JSON keyed on the files' sizes and mtimes (~20 s to build).

Standard library only. The WZ is ``--wz DIR``, ``$WZ_PATH``, ``/root/work/bake/wz`` or ``./wz``.
"""
from __future__ import annotations

import glob
import hashlib
import json
import os
import xml.etree.ElementTree as ET

CACHE_VERSION = 3
SKIP_CHAR_DIRS = {"Hair", "Face", "Afterimage", "Dragon"}
INT_TAGS = ("int", "short", "long")


def default_wz_dir():
    for cand in (os.environ.get("WZ_PATH"), "/root/work/bake/wz", "wz"):
        if cand and os.path.isdir(cand):
            return cand
    return "wz"


def _child(el, name):
    for c in el:
        if c.get("name") == name:
            return c
    return None


def _info(el):
    out = {}
    if el is None:
        return out
    for c in el:
        n = c.get("name")
        if c.tag in INT_TAGS:
            try:
                out[n] = int(c.get("value"))
            except (TypeError, ValueError):
                pass
        elif c.tag == "string":
            out[n] = c.get("value")
        elif c.tag in ("float", "double"):
            try:
                out[n] = float(c.get("value"))
            except (TypeError, ValueError):
                pass
    return out


def _facts(info, tab, folder):
    def flag(k):
        v = info.get(k)
        return 1 if v in (1, "1") else 0
    return {
        "tab": tab, "dir": folder,
        "price": int(info.get("price", 0) or 0),
        "cash": flag("cash"), "quest": flag("quest"),
        "untradeable": flag("tradeBlock"),
        "only": flag("only"),
        "reqLevel": int(info.get("reqLevel", 0) or 0),
        "success": int(info.get("success", 0) or 0),
        "slotMax": int(info.get("slotMax", 0) or 0),
        "timeLimited": flag("timeLimited"),
        "tuc": int(info.get("tuc", 0) or 0),
    }


def _files(wz):
    files = []
    for d in glob.glob(os.path.join(wz, "Character.wz", "*")):
        if os.path.isdir(d) and os.path.basename(d) not in SKIP_CHAR_DIRS:
            files += glob.glob(os.path.join(d, "*.img.xml"))
    for sub in ("Consume", "Etc", "Install", "Cash"):
        files += glob.glob(os.path.join(wz, "Item.wz", sub, "*.img.xml"))
    for f in ("Cash", "Consume", "Eqp", "Etc", "Ins", "Pet"):
        files.append(os.path.join(wz, "String.wz", f + ".img.xml"))
    return [f for f in files if os.path.exists(f)]


def _names(wz):
    names = {}

    def walk(el):
        for c in el:
            if c.tag != "imgdir":
                continue
            n = c.get("name")
            if n.isdigit():
                nm = _child(c, "name")
                if nm is not None:
                    names[int(n)] = nm.get("value") or ""
                    continue
            walk(c)

    for f in ("Cash", "Consume", "Eqp", "Etc", "Ins", "Pet"):
        p = os.path.join(wz, "String.wz", f + ".img.xml")
        if os.path.exists(p):
            walk(ET.parse(p).getroot())
    return names


def _build(wz):
    items = {}
    for d in sorted(glob.glob(os.path.join(wz, "Character.wz", "*"))):
        folder = os.path.basename(d)
        if not os.path.isdir(d) or folder in SKIP_CHAR_DIRS:
            continue
        for f in glob.glob(os.path.join(d, "*.img.xml")):
            base = os.path.basename(f)[:-8]
            if base.isdigit():
                info = _info(_child(ET.parse(f).getroot(), "info"))
                items[int(base)] = _facts(info, "EQUIP", folder)
    tabs = {"Consume": "USE", "Etc": "ETC", "Install": "SETUP", "Cash": "CASH"}
    for sub, tab in tabs.items():
        for f in glob.glob(os.path.join(wz, "Item.wz", sub, "*.img.xml")):
            for c in ET.parse(f).getroot():
                n = c.get("name")
                if c.tag == "imgdir" and n and n.isdigit():
                    items[int(n)] = _facts(_info(_child(c, "info")), tab, sub)
    names = _names(wz)
    for k, v in items.items():
        v["name"] = names.get(k)
    return {str(k): v for k, v in items.items()}


def load(wz_dir=None, cache_dir=None, quiet=False):
    """{item id: facts} for every item in the WZ dump at ``wz_dir``."""
    wz = os.path.abspath(wz_dir or default_wz_dir())
    files = _files(wz)
    if not files:
        raise SystemExit(f"no WZ XML under {wz} (use --wz DIR)")
    h = hashlib.sha1()
    for p in sorted(files):
        st = os.stat(p)
        h.update(f"{p}:{st.st_size}:{int(st.st_mtime)}\n".encode())
    fp = h.hexdigest() + f":v{CACHE_VERSION}"
    cache_dir = cache_dir or os.path.join(os.path.expanduser("~"), ".cache", "slumsms-gachapon")
    os.makedirs(cache_dir, exist_ok=True)
    cache = os.path.join(cache_dir, "wzitems-" + hashlib.sha1(wz.encode()).hexdigest()[:12] + ".json")
    if os.path.exists(cache):
        try:
            with open(cache, encoding="utf-8") as fh:
                data = json.load(fh)
            if data.get("fingerprint") == fp:
                return {int(k): v for k, v in data["items"].items()}
        except (OSError, ValueError):
            pass
    if not quiet:
        print(f"indexing WZ items at {wz} (cached afterwards) ...", flush=True)
    items = _build(wz)
    tmp = cache + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump({"fingerprint": fp, "items": items}, fh)
    os.replace(tmp, cache)
    return {int(k): v for k, v in items.items()}
