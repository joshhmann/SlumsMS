"""Item facts from the server's WZ XML (Item.wz, Character.wz, String.wz) for the economy tools.

ItemInfo(itemid) -> dict with name, kind ('equip' | 'consume' | 'etc' | ...), and the info flags that matter for
drops: cash, quest, tradeBlock, only, accountSharable, equipTradeBlock, timeLimited, reqLevel, success (scrolls).
"""
import xml.etree.ElementTree as ET
from functools import lru_cache
from pathlib import Path

EQUIP_DIRS = ("Accessory", "Cap", "Cape", "Coat", "Glove", "Longcoat", "Pants", "Ring", "Shield", "Shoes", "Weapon")
FLAGS = ("cash", "quest", "tradeBlock", "only", "accountSharable", "equipTradeBlock", "timeLimited", "notSale",
         "reqLevel", "success", "price", "incPAD", "incMAD")


class WzItems:
    def __init__(self, wz):
        self.wz = Path(wz)
        self._names = None
        self._equip_files = None

    # ---- names -------------------------------------------------------------------------------------------------
    def names(self):
        if self._names is None:
            self._names = {}
            for f in ("Consume", "Etc", "Ins", "Cash", "Pet"):
                self._read_names(self.wz / "String.wz" / f"{f}.img.xml")
            self._read_names(self.wz / "String.wz" / "Eqp.img.xml")
        return self._names

    def _read_names(self, path):
        if not path.exists():
            return
        for el in ET.parse(path).getroot().iter("imgdir"):
            nm = el.get("name", "")
            if nm.isdigit():
                n = el.find("string[@name='name']")
                if n is not None:
                    self._names.setdefault(int(nm), n.get("value"))

    # ---- info --------------------------------------------------------------------------------------------------
    def _equip_index(self):
        if self._equip_files is None:
            self._equip_files = {}
            for d in EQUIP_DIRS:
                for f in (self.wz / "Character.wz" / d).glob("*.img.xml"):
                    stem = f.name.split(".")[0]
                    if stem.isdigit():
                        self._equip_files[int(stem)] = (d, f)
        return self._equip_files

    @lru_cache(maxsize=None)
    def _item_file(self, folder, prefix):
        path = self.wz / "Item.wz" / folder / f"{prefix:04d}.img.xml"
        out = {}
        if not path.exists():
            return out
        for node in ET.parse(path).getroot():
            nm = node.get("name", "")
            if not nm.isdigit():
                continue
            info = node.find("imgdir[@name='info']")
            vals = {}
            if info is not None:
                vals = {c.get("name"): c.get("value") for c in info if c.tag != "imgdir" and c.tag != "canvas"}
            out[int(nm)] = vals
        return out

    def info(self, itemid):
        """None when the item is not in the WZ data."""
        kind = itemid // 1000000
        if kind == 1:
            ent = self._equip_index().get(itemid)
            if not ent:
                return None
            d, f = ent
            root = ET.parse(f).getroot()
            info = root.find("imgdir[@name='info']")
            vals = {c.get("name"): c.get("value") for c in info if c.tag not in ("imgdir", "canvas")} if info is not None else {}
            vals["_slot"] = d
            out = {"kind": "equip"}
        else:
            folder = {2: "Consume", 3: "Install", 4: "Etc", 5: "Cash"}.get(kind)
            if folder is None:
                return None
            vals = self._item_file(folder, itemid // 10000).get(itemid)
            if vals is None:
                return None
            out = {"kind": folder.lower()}
        out["name"] = self.names().get(itemid)
        out["slot"] = vals.get("_slot")
        for k in FLAGS:
            v = vals.get(k)
            out[k] = int(v) if v not in (None, "") and v.lstrip("-").isdigit() else (v or 0)
        return out

    def droppable(self, itemid):
        """Reason the item must not be a mob drop, or None when it may be one."""
        i = self.info(itemid)
        if i is None:
            return "not in WZ"
        if not i["name"]:
            return "no String.wz name"
        if i["kind"] == "cash" or i["cash"]:
            return "cash"
        if i["quest"]:
            return "quest item"
        for k in ("tradeBlock", "accountSharable", "equipTradeBlock", "timeLimited", "only"):
            if i[k]:
                return k
        return None
