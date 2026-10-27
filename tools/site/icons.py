#!/usr/bin/env python3
"""Pixel icons for the database pages, decoded from the v83 client's Data/*.img files on the RC base layer.

    python3 tools/site/icons.py --kinds items             # fetch what's missing, decode, write the manifest
    python3 tools/site/icons.py --kinds items,mobs,npcs --no-fetch
    python3 tools/site/icons.py --chrome                  # the few icons the site's own pages use (committed)
    python3 tools/site/icons.py --thumbs                  # small copies of big monster/NPC pictures for list pages

Only icons for reachable entities (sections/dbdata.py) are made. Steps:
1. Work out which .img files hold them (equips: Character/<Slot>/0<id>.img; other items: Item/<Tab>/<prefix>.img;
   pets: Item/Pet/<id>.img; monsters: Mob/<id>.img; NPCs: Npc/<id>.img).
2. Copy the missing ones from 164 with one read-only tar over ssh at idle priority into
   cache/site-wz/<layer>/Data/ (git-ignored). The layer is pinned, so each file is copied once.
3. Decode each icon to cache/site-icons/<kind>/<id>.png (item info/icon; monster and NPC: the first stand frame,
   else fly or move; "link" entries follow the linked id) and write cache/site-icons/manifest.json.
Needs Pillow, pycryptodome and the client repo's tools/wz/native_wz.py (--native-wz).
"""
import argparse
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
from sections import dbdata  # noqa: E402

LAYER = "v83-2026.10.19"
HOST = "root@192.168.0.164"
REMOTE = "/srv/slums-patch/launcher-rc/layers/base/{layer}/files/Data"
CACHE_WZ = os.path.join(REPO, "cache", "site-wz")
CACHE_ICONS = os.path.join(REPO, "cache", "site-icons")
NATIVE_WZ = "/root/work/client/src/tools/wz"

# equip id prefix (id // 10000) -> Character/ folder
EQUIP_DIRS = [(100, 100, "Cap"), (101, 103, "Accessory"), (104, 104, "Coat"), (105, 105, "Longcoat"),
              (106, 106, "Pants"), (107, 107, "Shoes"), (108, 108, "Glove"), (109, 109, "Shield"), (110, 110, "Cape"),
              (111, 111, "Ring"), (112, 119, "Accessory"), (130, 170, "Weapon"), (180, 183, "PetEquip"),
              (190, 199, "TamingMob")]
ITEM_TABS = {2: "Consume", 3: "Install", 4: "Etc", 5: "Cash", 9: "Special"}
FRAMES = ["stand/0", "fly/0", "move/0", "stand", "fly", "move"]


def item_source(item_id):
    """item id -> (img path under Data/, node path inside it), or None."""
    tab = item_id // 1000000
    if tab == 1:
        pre = item_id // 10000
        for lo, hi, folder in EQUIP_DIRS:
            if lo <= pre <= hi:
                return f"Character/{folder}/{item_id:08d}.img", "info/icon"
        return None
    if tab == 5 and item_id // 10000 == 500:
        return f"Item/Pet/{item_id:07d}.img", "info/icon"
    if tab in ITEM_TABS:
        return f"Item/{ITEM_TABS[tab]}/{item_id // 10000:04d}.img", f"{item_id:08d}/info/icon"
    return None


def life_source(kind, life_id):
    return f"{'Mob' if kind == 'mobs' else 'Npc'}/{life_id:07d}.img"


def fetch(paths, layer, host):
    """Copy the missing .img files from the RC layer on 164 (read-only, idle priority). Returns the paths that are
    still missing afterwards (not on the layer either)."""
    base = os.path.join(CACHE_WZ, layer, "Data")
    need = sorted(p for p in set(paths) if not os.path.isfile(os.path.join(base, p)))
    if not need:
        return []
    os.makedirs(base, exist_ok=True)
    remote = REMOTE.format(layer=layer)
    # --ignore-failed-read: a path that is not on the layer is reported, not fatal
    cmd = ["ssh", "-o", "BatchMode=yes", host,
           f"cd {remote} && nice -n 19 ionice -c3 tar --ignore-failed-read -cf - -T - 2>/dev/null"]
    print(f"fetching {len(need)} .img file(s) from {host}:{remote}", file=sys.stderr)
    tar = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE)
    untar = subprocess.Popen(["tar", "-xf", "-", "-C", base], stdin=tar.stdout)
    tar.stdout.close()
    tar.stdin.write("\n".join(need).encode() + b"\n")
    tar.stdin.close()
    untar.wait()
    tar.wait()
    return [p for p in need if not os.path.isfile(os.path.join(base, p))]


class Decoder:
    def __init__(self, layer, native_wz):
        sys.path.insert(0, native_wz)
        import native_wz as nw  # noqa: E402
        from PIL import Image  # noqa: E402
        self.nw, self.Image = nw, Image
        self.base = os.path.join(CACHE_WZ, layer, "Data")
        self.cache = {}

    def root(self, rel):
        if rel not in self.cache:
            if len(self.cache) > 64:
                self.cache.clear()
            path = os.path.join(self.base, rel)
            if not os.path.isfile(path):
                return None
            with open(path, "rb") as f:
                self.cache[rel] = self.nw.parse_img(f.read())[0]
        return self.cache[rel]

    @staticmethod
    def child(node, name):
        return next((c for c in node.children or [] if c.name == name), None)

    def find(self, root, path):
        """Walk a slash path from root, following UOL links (relative to the node's parent)."""
        trail = [root]
        parts = path.split("/")
        hops = 0
        while parts:
            part = parts.pop(0)
            if part == "..":
                trail.pop()
                continue
            node = self.child(trail[-1], part)
            if node is None:
                return None
            if node.kind == "uol" and hops < 8:
                hops += 1
                parts = node.value.split("/") + parts
                continue
            trail.append(node)
        node = trail[-1]
        # an animation folder: use its first frame
        if node.kind != "canvas" and node.children:
            first = next((c for c in node.children if c.kind in ("canvas", "uol")), None)
            if first is None:
                return None
            return self.find(root, "/".join(n.name for n in trail[1:]) + "/" + first.name)
        return node if node.kind == "canvas" else None

    def image(self, node):
        raw = self.nw.pixels(node)
        v = node.value
        w, h, fmt = v["width"], v["height"], v["format"] + v["format2"]
        Image = self.Image
        if fmt == 2:
            return Image.frombytes("RGBA", (w, h), raw, "raw", "BGRA")
        if fmt == 1:
            px = bytearray()
            for i in range(0, len(raw), 2):
                b0, b1 = raw[i], raw[i + 1]
                px += bytes([(b1 & 15) * 17, (b0 >> 4) * 17, (b0 & 15) * 17, (b1 >> 4) * 17])
            return Image.frombytes("RGBA", (w, h), bytes(px))
        if fmt == 513:
            px = bytearray()
            for i in range(0, len(raw), 2):
                c = raw[i] | raw[i + 1] << 8
                px += bytes([((c >> 11) & 31) * 255 // 31, ((c >> 5) & 63) * 255 // 63, (c & 31) * 255 // 31, 255])
            return Image.frombytes("RGBA", (w, h), bytes(px))
        raise ValueError(f"canvas format {fmt} not supported")

    def life_link(self, root):
        info = self.child(root, "info")
        link = info and self.child(info, "link")
        if link is None or link.value in (None, ""):
            return None
        try:
            return int(str(link.value))
        except ValueError:
            return None


# Icons the site's own pages use (guide menu, home tiles), copied into docs/site/assets/icons/ by --chrome so the
# public build never needs the cache. Keys are item ids; Special/0900 (mesos) keeps its icon under iconRaw.
CHROME = {4001126: "Maple Leaf", 4031180: "Beginner's Shopping Guide", 9000000: "Mesos", 5030000: "Mushroom House Elf",
          2030000: "Return Scroll", 1302000: "Sword", 4000000: "Blue Snail Shell", 4031045: "Ticket to Orbis",
          2340000: "White Scroll", 5072000: "Super Megaphone", 5220000: "Gachapon Ticket", 5390000: "Diablo Messenger",
          9000003: "Bag of mesos", 2000005: "Power Elixir", 4161001: "MapleRoot Guide", 5071000: "Megaphone",
          2000000: "Red Potion",
          1142107: "Beginner Adventurer Medal"}  # the stand-in for medals with no icon (database.FALLBACK_ICONS)
CHROME_DIR = os.path.join(REPO, "docs", "site", "assets", "icons")


def chrome_source(item_id):
    if item_id // 1000000 == 9:
        return f"Item/Special/{item_id // 10000:04d}.img", f"{item_id:08d}/iconRaw/0"
    return item_source(item_id)


def make_chrome(dec, layer, host, fetch_ok):
    srcs = {i: chrome_source(i) for i in CHROME}
    if fetch_ok:
        fetch([s[0] for s in srcs.values()], layer, host)
    os.makedirs(CHROME_DIR, exist_ok=True)
    for i, (img, node_path) in sorted(srcs.items()):
        root = dec.root(img)
        node = root and dec.find(root, node_path)
        if node is None:
            raise SystemExit(f"chrome icon {i} ({CHROME[i]}) not found in {img}")
        dec.image(node).save(os.path.join(CHROME_DIR, f"{i}.png"), optimize=True)
    print(f"chrome: {len(srcs)} icons in {CHROME_DIR}")


def plan(data, kinds, extra_items=()):
    """extra_items: item ids with no database page whose icons the market still shows (dbdata.extra_icon_items)."""
    jobs = []  # (kind, id, img path, node path or None for life)
    if "items" in kinds:
        for item_id in sorted(set(data["items"]) | set(extra_items)):
            src = item_source(item_id)
            if src:
                jobs.append(("items", item_id, src[0], src[1]))
    for kind in ("mobs", "npcs"):
        if kind in kinds:
            jobs.extend((kind, i, life_source(kind, i), None) for i in data[kind])
    return jobs


THUMB = 64  # list pages show icons in a 30px slot; whole monster and NPC sprites can be 700px across


def make_thumbs(out):
    """cache/site-icons/thumbs/<kind>/<id>.webp: monsters and NPCs bigger than THUMB, shrunk to fit it. List pages use
    these (sections/database.py); an entity's own page keeps the full picture. Item icons are small already."""
    from PIL import Image
    n = 0
    for kind in ("mobs", "npcs"):
        src, dst = os.path.join(out, kind), os.path.join(out, "thumbs", kind)
        os.makedirs(dst, exist_ok=True)
        for name in os.listdir(src):
            p, t = os.path.join(src, name), os.path.join(dst, name[:-4] + ".webp")
            if not name.endswith(".png") or (os.path.isfile(t) and os.path.getmtime(t) >= os.path.getmtime(p)):
                continue
            with Image.open(p) as im:
                if max(im.size) <= THUMB:
                    continue
                im = im.convert("RGBA")
                im.thumbnail((THUMB, THUMB), Image.LANCZOS)
                im.save(t, "WEBP", quality=88, method=6)
                n += 1
    print(f"{n} thumbnails -> {os.path.join(out, 'thumbs')}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--hs-data", help="build_data.py output folder (default: %s)" % dbdata.DEFAULT_DIR)
    ap.add_argument("--kinds", default="items,mobs,npcs")
    ap.add_argument("--market-data", help="also make icons for every item this market export has traded that has no "
                                          "database page (besides data/site/extra-icon-items.txt)")
    ap.add_argument("--layer", default=LAYER)
    ap.add_argument("--host", default=HOST)
    ap.add_argument("--native-wz", default=NATIVE_WZ)
    ap.add_argument("--no-fetch", action="store_true", help="only use files already in the cache")
    ap.add_argument("--out", default=CACHE_ICONS)
    ap.add_argument("--chrome", action="store_true", help="only (re)make the site's own icons in docs/site/assets/icons")
    ap.add_argument("--thumbs", action="store_true", help="only (re)make the small list-page copies (thumbs/) from the icons")
    args = ap.parse_args()
    if args.thumbs:
        make_thumbs(args.out)
        return
    if args.chrome:
        make_chrome(Decoder(args.layer, args.native_wz), args.layer, args.host, not args.no_fetch)
        return
    kinds = set(args.kinds.split(","))
    data = dbdata.load(args.hs_data)
    extra = dbdata.extra_icon_items()
    if args.market_data:  # every item the market has traded that has no database page
        from sections import market
        m = market.load(args.market_data)
        seen = market.traded_items(m) | {r["item_id"] for r in m["listings"]["listings"] + m["sales"]["sales"]}
        extra |= {i for i in seen if i not in data["items"]}
    jobs = plan(data, kinds, extra)
    if not args.no_fetch:
        fetch([j[2] for j in jobs], args.layer, args.host)

    dec = Decoder(args.layer, args.native_wz)
    manifest_path = os.path.join(args.out, "manifest.json")
    manifest = {"layer": args.layer, "icons": {}}
    if os.path.isfile(manifest_path):
        with open(manifest_path, encoding="utf-8") as f:
            old = json.load(f)
        if old.get("layer") == args.layer:
            manifest = old
    links, failed = [], {}
    jobs.sort(key=lambda j: j[2])  # one .img file at a time
    for kind, ident, img, node_path in jobs:
        root = dec.root(img)
        if root is None:
            failed[f"{kind}:{ident}"] = "no file"
            continue
        if node_path is None:
            target = dec.life_link(root)
            if target and not any(dec.find(root, f) for f in FRAMES):
                links.append((kind, ident, life_source(kind, target)))
                continue
        try:
            node = dec.find(root, node_path) if node_path else next(
                (n for n in (dec.find(root, f) for f in FRAMES) if n), None)
            if node is None:
                failed[f"{kind}:{ident}"] = "no icon node"
                continue
            im = dec.image(node)
        except Exception as e:  # a bad canvas must not stop the run
            failed[f"{kind}:{ident}"] = f"{type(e).__name__}: {e}"
            continue
        os.makedirs(os.path.join(args.out, kind), exist_ok=True)
        im.save(os.path.join(args.out, kind, f"{ident}.png"), optimize=True)
        manifest["icons"].setdefault(kind, {})[str(ident)] = [im.width, im.height]
    # monsters and NPCs that borrow another id's sprite
    if links:
        if not args.no_fetch:
            fetch([l[2] for l in links], args.layer, args.host)
        for kind, ident, img in links:
            root = dec.root(img)
            node = root and next((n for n in (dec.find(root, f) for f in FRAMES) if n), None)
            if node is None:
                failed[f"{kind}:{ident}"] = f"linked sprite {img} missing"
                continue
            im = dec.image(node)
            os.makedirs(os.path.join(args.out, kind), exist_ok=True)
            im.save(os.path.join(args.out, kind, f"{ident}.png"), optimize=True)
            manifest["icons"].setdefault(kind, {})[str(ident)] = [im.width, im.height]
    manifest["failed"] = dict(sorted(failed.items()))
    os.makedirs(args.out, exist_ok=True)
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=0, sort_keys=True)
    made = {k: len(v) for k, v in manifest["icons"].items()}
    print(f"icons: {made}; {len(failed)} without an icon (listed in {manifest_path})")


if __name__ == "__main__":
    main()
