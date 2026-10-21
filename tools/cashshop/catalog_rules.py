"""The rules shared by build_catalog.py and lint_catalog.py.

Everything here is a statement about the SERVER or the CLIENT WINDOW, not about taste: what
the window can show, what the purchase path can deliver, what has a working use handler.
Taste (prices, featured, hiding things) lives in overrides.yaml.
"""
from __future__ import annotations

import os
import re

import yaml

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
DEFAULT_CATALOG = os.path.join(REPO, "cashshop", "catalog.tsv")
DEFAULT_OVERRIDES = os.path.join(HERE, "overrides.yaml")

# ---------------------------------------------------------------------------------------------
# The client window. kCats[] in DLL src/cashshopwnd.cpp (client repo). A row in any other
# (tab, category) is sent but never shown. load_kcats() re-reads the .cpp when it is around.
# ---------------------------------------------------------------------------------------------
KCATS = {
    (2, 0): "Hat", (2, 1): "Face", (2, 2): "Eye", (2, 3): "Overall", (2, 4): "Top",
    (2, 5): "Bottom", (2, 6): "Shoes", (2, 7): "Glove", (2, 8): "Weapon", (2, 9): "Ring",
    (2, 11): "Cape",
    (3, 1): "Messenger", (3, 2): "Weather",
    (5, 0): "Beauty Parlor", (5, 1): "Store", (5, 2): "Game", (5, 3): "Facial Expression",
    (5, 4): "Wedding", (5, 5): "Effect", (5, 6): "Character",
    (6, 0): "Pet", (6, 1): "Pet Equip.", (6, 2): "Pet Use",
    (7, 0): "Package",
}
CLIENT_CPP_CANDIDATES = ("/root/work/client/src/src/cashshopwnd.cpp",)

# Window and wire limits (cashshopwnd.cpp kMaxPerCategory / CashShopWindowPackets.MAX_NAME).
MAX_PER_CATEGORY = 65536
MAX_NAME = 40
MAX_NAME_BYTES = 64


def load_kcats(path=None):
    """kCats from the client source when available, else the copy above."""
    for p in ([path] if path else []) + [os.environ.get("CASHSHOP_CLIENT_CPP")] + list(CLIENT_CPP_CANDIDATES):
        if p and os.path.isfile(p):
            src = open(p, encoding="utf-8", errors="replace").read()
            m = re.search(r"const CatDef kCats\[\]\s*=\s*\{(.*?)\n\};", src, re.S)
            if m:
                body = re.sub(r"//[^\n]*", "", m.group(1))
                cats = {(int(t), int(s)): n for t, s, n in
                        re.findall(r"\{\s*(\d+)\s*,\s*(\d+)\s*,\s*\"([^\"]*)\"\s*\}", body)}
                if cats:
                    return cats, p
    return dict(KCATS), None


# ---------------------------------------------------------------------------------------------
# Classification: item-id prefix (itemId // 10000) -> "tab/cat", or a PENDING group name for
# merchandise the window has no category for yet (its home comes from overrides.yaml).
# ---------------------------------------------------------------------------------------------
CATEGORY_BY_PREFIX = {
    # equips
    100: "2/0", 101: "2/1", 102: "2/2", 103: "2/9", 104: "2/4", 105: "2/3", 106: "2/5",
    107: "2/6", 108: "2/7", 109: "2/8", 110: "2/11", 111: "2/9", 170: "2/8",
    112: "accessory", 113: "accessory", 114: "accessory", 115: "accessory",
    180: "6/1", 181: "6/1", 182: "6/1", 183: "6/1",
    190: "mount", 191: "mount",
    # set-up
    301: "chair",
    # cash
    500: "6/0", 501: "5/5", 502: "5/2", 503: "5/1", 504: "5/2", 505: "5/6", 506: "5/2",
    507: "3/1", 508: "3/1", 509: "3/1", 510: "3/1", 511: "3/2", 512: "3/2", 513: "5/2",
    514: "5/1", 515: "5/0", 516: "5/3", 517: "6/2", 518: "6/2", 520: "5/2", 521: "5/2",
    522: "5/2", 523: "5/1", 524: "6/2", 525: "5/4", 529: "3/1", 530: "5/2", 533: "3/1",
    536: "5/2", 537: "3/1", 538: "6/2", 539: "3/1", 542: "5/0", 543: "5/6", 545: "5/1",
    547: "5/1",  # Store Remote: RemoteStoreHandler serves the 0x3B request it makes
    551: "5/2", 552: "5/2", 557: "5/2", 559: "5/2", 561: "5/2",
    # services (CashShopServices): slot expansions
    911: "5/6",
}
PENDING_GROUPS = {
    "chair": "Chair (Set-up 301xxxx)",
    "mount": "Mount (190xxxx mounts, 191xxxx saddles)",
    "accessory": "Pendant / Belt / Medal / Shoulder (112-115xxxx)",
}

# Which items may be sold when they are NOT a stock v83 on-sale commodity. "any" = every item
# of the prefix with data; "stock" = only stock on-sale rows (newer variants of these types
# have no verified handler, are NPC-specific, or need a stock period we do not have).
NEWER_POLICY = {p: "any" for p in CATEGORY_BY_PREFIX if p < 300}
NEWER_POLICY.update({
    181: "stock",              # 1812008-10 pet skill equips: no server buff/cure code
    301: "any",
    500: "any", 501: "any", 502: "any", 503: "any", 509: "any", 510: "any", 512: "any",
    514: "any", 529: "any", 533: "any", 537: "any",
    515: "script", 542: "script",  # coupons only work for ids a stylist NPC script reads
    506: "ids", 559: "ids", 520: "ids",
})
NEWER_IDS = {
    506: {5060001},            # Item Guard: UseCashItemHandler 506 handles it
    559: {5590001},            # Hyper Teleport Rock (HyperTPHandler) -- flag-gated
    520: {5200000, 5200001, 5200002},  # mesobags -- flag-gated
}

# Ids / ranges that can never be sold through the window, whatever the source. Each has the
# reason the window path cannot make them work. (Taste-based hiding belongs in overrides.yaml.)
NEVER = [
    ((5222000, 5222000), "Cash Shop Surprise: only opens inside the stock Cash Shop stage"),
    ((5400000, 5401999), "Name Change / Transfer: only registered by the stock Cash Shop (0x2E)"),
    ((5460000, 5460999), "Pet Snack: no use handler (546)"),
    ((5280000, 5289999), "Passed Gas / Floral Scent: no use handler (528)"),
    ((5190000, 5199999), "pet skill items: no use handler (519)"),
    ((5490000, 5499999), "Master Keys: no use handler (549)"),
    ((5500000, 5509999), "Magical Sandglass: 550 handler is a no-op"),
    ((5530000, 5539999), "DS Egg: no use handler (553)"),
    ((5550000, 5559999), "Pendant slot extension: v83 has no extra pendant slot"),
    ((5070000, 5070999), "Cheap Megaphone: 507 switch has no case 0"),
    ((5073000, 5074999), "Heart/Skull Megaphone: 507 switch has no case 3/4"),
    ((4000000, 4999999), "Etc/quest item, not Cash Shop merchandise"),
    ((9110000, 9110000), "Storage +8: every storage is already at the server cap"),
]
# Rings that only work with a partner ring record (Ring.createRing + the partner's copy),
# which the window cannot make: every ring whose look lives in Effect.wz/ItemEff -- crush
# 11120xx, friendship 11128xx, wedding 1112803-09 and the newer couple rings. Those effects
# are drawn only for a ring with a ring record. Label/quote rings (no ItemEff) are fine.

PACKAGE_PREFIX_MILLION = 9   # 9xxxxxx: getInventoryType() == UNDEFINED -> loader drops it
SERVICE_IDS = {5430000, 9111000, 9112000, 9113000, 9114000}  # CashShopServices.isService
RATE_COUPON_TYPES = {5211, 5360}                           # CashShopServices.isRateCoupon
FLAG_GATED = {
    "sell_mesobags": ({5200000, 5200001, 5200002}, "mesobags (NX -> mesos): owner economy call"),
    "sell_hyper_teleport_rock": ({5590001}, "Hyper Teleport Rock: removes the HyperTP cooldown"),
}


# Equips that are not drawn on the avatar, so have no stand/default frames by design: rings,
# pet utility equips (Meso Magnet, label/quote rings). "Transparent ..." items are exempt too.
NO_FRAMES_PREFIXES = {111, 181, 182, 183}


def in_ranges(item_id, ranges):
    return any(lo <= item_id <= hi for lo, hi in ranges)


def never_reason(item_id):
    for (lo, hi), why in NEVER:
        if lo <= item_id <= hi:
            return why
    return None


def is_equip(item_id):
    return item_id // 1000000 == 1


def gender_of(item_id):
    """The client's own rule (and the server refusal's): equips whose thousands digit is 0 are
    male-only, 1 female-only; everything else is unisex. WZ has no reqGender on these."""
    if not is_equip(item_id):
        return 2
    d = (item_id // 1000) % 10
    return d if d in (0, 1) else 2


_BAD_NAME = re.compile(r"(?i)^(missing name|null|none|test|tbd|-+|\?+|name)$|\?\?|[가-힯぀-ヿ一-鿿]")


_STAFF_NAME = re.compile(r"^(GM|Test|TEST|Admin)\b|\bTest\b|_")


def name_problem(name, strict=True):
    """None when the name is fit for the shop, else why not. `strict` (everything that is not a
    stock on-sale row) also refuses staff/test items and internal names with underscores."""
    if name is None:
        return "no String.wz name"
    s = name.strip()
    if not s:
        return "blank name"
    if _BAD_NAME.search(s):
        return "garbled/placeholder/Korean name"
    if any(ord(ch) > 0xFF or (ord(ch) < 0x20) for ch in s):
        return "non-Latin characters in name"
    if re.fullmatch(r"[\d\s.]+", s):
        return "numeric name"
    if strict and _STAFF_NAME.search(s):
        return "GM/test/internal item name"
    return None


def clean_name(name):
    s = re.sub(r"\s+", " ", name.strip())
    return s[:MAX_NAME].rstrip()


def wz_problem(item_id, wz):
    """None when the WZ has what the shop and the client need for this id, else why not."""
    if item_id in SERVICE_IDS:
        return None
    it = wz.items.get(item_id)
    if it is None:
        return "no WZ data"
    if not it.get("icon"):
        return "no icon"
    if not it.get("frames") and item_id // 10000 not in NO_FRAMES_PREFIXES \
            and "transparent" not in (wz.name(item_id) or "").lower():
        return "no stand/default frames"
    if it["src"] == "equip" and it.get("cash") != 1:
        if isinstance(it.get("cash"), str):
            # <string name="cash" value="1"/>: the server's getInt(path, data, 0) parses it, but
            # nothing guarantees the client does; cashshop-fixes dropped these as "not cash".
            return "WZ cash flag is a string, not an int (treated as not cash)"
        return "equip is not cash-flagged in WZ"
    return None


def ring_record_needed(item_id, wz):
    return item_id // 10000 == 111 and item_id in wz.item_eff


# ---------------------------------------------------------------------------------------------
# overrides.yaml
# ---------------------------------------------------------------------------------------------
def load_overrides(path=None):
    path = path or DEFAULT_OVERRIDES
    with open(path, encoding="utf-8") as fh:
        data = yaml.safe_load(fh) or {}
    data.setdefault("flags", {})
    data.setdefault("pending_categories", {})
    data.setdefault("categories", {})
    data.setdefault("prices", {})
    data.setdefault("tiers", {})
    data.setdefault("items", [])
    data.setdefault("featured_files", [])
    data["_path"] = path
    return data


def item_overrides(ov):
    """Resolves overrides.yaml `items` into {itemId: merged dict} lazily: returns a function.
    Ranges apply first (in file order), then single ids, so a single id always beats a range."""
    ranges, singles = [], {}
    for e in ov.get("items", []):
        if "range" in e:
            lo, hi = e["range"]
            ranges.append((int(lo), int(hi), e))
        if "id" in e:
            ids = e["id"] if isinstance(e["id"], list) else [e["id"]]
            for i in ids:
                singles.setdefault(int(i), {}).update({k: v for k, v in e.items() if k != "id"})

    def lookup(item_id):
        out = {}
        for lo, hi, e in ranges:
            if lo <= item_id <= hi:
                out.update({k: v for k, v in e.items() if k != "range"})
        out.update(singles.get(item_id, {}))
        return out

    lookup.single_ids = set(singles)
    lookup.ranges = [(lo, hi) for lo, hi, _ in ranges]
    return lookup


def resolve_category(key, ov):
    """'tab/cat' or a pending-group name -> (tab, cat, pending_group|None)."""
    key = str(key)
    pending = None
    if key in PENDING_GROUPS or key in ov.get("pending_categories", {}):
        pending = key
        key = str(ov["pending_categories"].get(key, {}).get("target", "5/2"))
    t, c = key.split("/")
    return int(t), int(c), pending


def category_key(item_id, ov):
    prefix = item_id // 10000
    cats = {int(k): v for k, v in ov.get("categories", {}).items()}
    return cats.get(prefix, CATEGORY_BY_PREFIX.get(prefix))


def script_ids(repo=REPO):
    """Every 7-digit number that appears in an NPC script: coupon ids a stylist reads."""
    out = set()
    d = os.path.join(repo, "scripts", "npc")
    for f in os.listdir(d) if os.path.isdir(d) else []:
        if f.endswith(".js"):
            out.update(int(x) for x in re.findall(r"\b(5\d{6})\b", open(os.path.join(d, f), encoding="utf-8", errors="replace").read()))
    return out


def read_catalog(path):
    """[(lineNo, fields)] of data rows."""
    rows = []
    with open(path, encoding="utf-8") as fh:
        for n, line in enumerate(fh, 1):
            s = line.rstrip("\n").strip()
            if not s or s.startswith("#"):
                continue
            rows.append((n, line.rstrip("\n").split("\t")))
    return rows
