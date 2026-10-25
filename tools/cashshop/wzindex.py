"""Index of the server WZ XML that the Cash Shop catalogue tools need.

Reads the XML dump the server itself loads (``wz/`` or ``-Dwz-path``; on the dev box
``/root/work/bake/wz``) and returns one flat dict per item id. The scan takes ~30 s, so the
result is cached as JSON keyed on the WZ files' mtimes and sizes; any change rebuilds it.

Only the standard library is used here, so the lint step can run anywhere Python 3 runs.
"""
from __future__ import annotations

import glob
import hashlib
import json
import os
import re
import xml.etree.ElementTree as ET

# Character.wz folders that are never shop merchandise (salon ids, skill art, real weapons
# are filtered later by the cash flag).
SKIP_CHAR_DIRS = {"Hair", "Face", "Afterimage", "Dragon"}
# Frame names that prove an equip draws on the avatar.
FRAME_NAMES = {"stand1", "stand2", "default", "walk1", "walk2"}
CACHE_VERSION = 6

_NUM = re.compile(r"-?\d+")


def _info(el):
    """info imgdir -> {name: value} for scalars, plus 'icon' presence."""
    out = {}
    if el is None:
        return out
    for c in el:
        n = c.get("name")
        if c.tag in ("int", "short", "long"):
            out[n] = int(c.get("value"))
        elif c.tag == "string" and n == "cash":
            out[n] = "str:" + (c.get("value") or "")
        elif c.tag == "string":
            out[n] = c.get("value")
        elif n in ("icon", "iconRaw"):
            out["_" + n] = True
    return out


def _child(el, name):
    for c in el:
        if c.get("name") == name:
            return c
    return None


def _fingerprint(paths):
    h = hashlib.sha1()
    for p in sorted(paths):
        st = os.stat(p)
        h.update(f"{p}:{st.st_size}:{int(st.st_mtime)}\n".encode())
    return h.hexdigest()


def _source_files(wz):
    files = []
    for d in glob.glob(os.path.join(wz, "Character.wz", "*")):
        if os.path.isdir(d) and os.path.basename(d) not in SKIP_CHAR_DIRS:
            files += glob.glob(os.path.join(d, "*.img.xml"))
    files += glob.glob(os.path.join(wz, "Item.wz", "Cash", "*.img.xml"))
    files += glob.glob(os.path.join(wz, "Item.wz", "Install", "*.img.xml"))
    files += glob.glob(os.path.join(wz, "Item.wz", "Special", "*.img.xml"))
    files += glob.glob(os.path.join(wz, "Item.wz", "Pet", "*.img.xml"))
    for f in ("Cash", "Consume", "Eqp", "Etc", "Ins", "Pet"):
        files.append(os.path.join(wz, "String.wz", f + ".img.xml"))
    files += [os.path.join(wz, "Etc.wz", n) for n in
              ("Commodity.img.xml", "CashPackage.img.xml", "Category.img.xml")]
    files.append(os.path.join(wz, "Effect.wz", "ItemEff.img.xml"))
    return files


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
        walk(ET.parse(os.path.join(wz, "String.wz", f + ".img.xml")).getroot())
    return names


def _build(wz):
    items = {}

    # Equips: one file per id.
    for d in sorted(glob.glob(os.path.join(wz, "Character.wz", "*"))):
        folder = os.path.basename(d)
        if not os.path.isdir(d) or folder in SKIP_CHAR_DIRS:
            continue
        for f in glob.glob(os.path.join(d, "*.img.xml")):
            base = os.path.basename(f)[:-8]
            if not base.isdigit():
                continue
            root = ET.parse(f).getroot()
            info = _info(_child(root, "info"))
            kids = [c.get("name") for c in root if c.tag == "imgdir" and c.get("name") != "info"]
            if folder in ("PetEquip", "TamingMob"):
                frames = bool(kids)          # per-pet / per-pose subtrees
            elif folder == "Weapon":
                # NX covers keep one subtree per weapon type ("30", "33", ...).
                frames = any(k in FRAME_NAMES or k.isdigit() for k in kids)
            else:
                frames = any(k in FRAME_NAMES for k in kids)
            items[int(base)] = {"src": "equip", "dir": folder,
                                "cash": info.get("cash", 0), "icon": bool(info.get("_icon")),
                                "frames": frames, "reqGender": info.get("reqGender")}

    # Pets: one file per id.
    for f in glob.glob(os.path.join(wz, "Item.wz", "Pet", "*.img.xml")):
        base = os.path.basename(f)[:-8]
        if not base.isdigit():
            continue
        root = ET.parse(f).getroot()
        info = _info(_child(root, "info"))
        kids = {c.get("name") for c in root if c.tag == "imgdir"}
        items[int(base)] = {"src": "pet", "dir": "Pet", "cash": info.get("cash", 1),
                            "icon": bool(info.get("_icon")),
                            "frames": bool(kids & {"stand0", "stand1", "move"})}

    # Bundled item files: one imgdir per id.
    for sub, src in (("Cash", "cash"), ("Install", "install"), ("Special", "special")):
        for f in glob.glob(os.path.join(wz, "Item.wz", sub, "*.img.xml")):
            root = ET.parse(f).getroot()
            for c in root:
                n = c.get("name")
                if c.tag != "imgdir" or not n.isdigit():
                    continue
                info_el = _child(c, "info")
                info = _info(info_el)
                items[int(n)] = {"src": src, "dir": sub, "cash": info.get("cash", 0),
                                 "icon": bool(info.get("_icon")), "frames": True}
                # Rate coupons (0521 EXP, 0536 drop): info/rate and the info/time list
                # ("MON:07-11", ...) that the shop name's schedule is built from.
                tm = _child(info_el, "time") if info_el is not None else None
                if tm is not None:
                    items[int(n)]["time"] = [t.get("value") for t in tm if t.tag == "string"]
                    rate = next((r.get("value") for r in info_el if r.get("name") == "rate"), None)
                    items[int(n)]["rate"] = int(float(rate)) if rate else None   # int in 0521, float "2.0" in 0536

    names = _names(wz)

    commodity = []
    for d in ET.parse(os.path.join(wz, "Etc.wz", "Commodity.img.xml")).getroot():
        e = {}
        for c in d:
            v = c.get("value")
            e[c.get("name")] = int(v) if v is not None and _NUM.fullmatch(v) else v
        if "ItemId" in e:
            commodity.append(e)

    item_eff = sorted(int(c.get("name")) for c in
                      ET.parse(os.path.join(wz, "Effect.wz", "ItemEff.img.xml")).getroot()
                      if c.get("name", "").isdigit())

    return {"items": {str(k): v for k, v in items.items()},
            "names": {str(k): v for k, v in names.items()},
            "commodity": commodity, "itemEff": item_eff}


class WzIndex:
    def __init__(self, data):
        self.items = {int(k): v for k, v in data["items"].items()}
        self.names = {int(k): v for k, v in data["names"].items()}
        self.commodity = data["commodity"]
        self.item_eff = set(data["itemEff"])

    def name(self, item_id):
        return self.names.get(item_id)


def default_wz_dir():
    for cand in (os.environ.get("WZ_PATH"), "/root/work/bake/wz", "wz"):
        if cand and os.path.isdir(os.path.join(cand, "Etc.wz")):
            return cand
    raise SystemExit("WZ XML directory not found: pass --wz or set WZ_PATH")


def load(wz_dir=None, cache_dir=None, quiet=False):
    wz = os.path.abspath(wz_dir or default_wz_dir())
    files = [p for p in _source_files(wz) if os.path.exists(p)]
    fp = _fingerprint(files) + f":v{CACHE_VERSION}"
    cache_dir = cache_dir or os.environ.get("CASHSHOP_CACHE") or os.path.join(
        os.path.expanduser("~"), ".cache", "slumsms-cashshop")
    os.makedirs(cache_dir, exist_ok=True)
    cache = os.path.join(cache_dir, "wzindex-" + hashlib.sha1(wz.encode()).hexdigest()[:12] + ".json")
    if os.path.exists(cache):
        try:
            with open(cache, encoding="utf-8") as fh:
                data = json.load(fh)
            if data.get("fingerprint") == fp:
                return WzIndex(data)
        except (OSError, ValueError):
            pass
    if not quiet:
        print(f"indexing WZ at {wz} (cached afterwards) ...", flush=True)
    data = _build(wz)
    data["fingerprint"] = fp
    tmp = cache + ".tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(data, fh)
    os.replace(tmp, cache)
    return WzIndex(data)
