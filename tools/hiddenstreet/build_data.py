#!/usr/bin/env python3
"""Build normalized game dataset and data sweep report for SlumsMS Hidden Street (Ticket 78, Phase 1).

Reads:
- WZ XML dumps (String.wz, Item.wz, Character.wz, Mob.wz, Map.wz, Npc.wz, Quest.wz)
- Database exports (drop_data, reactordrops, shops, shopitems) from TSV/JSON or test DB

Outputs:
- Normalized JSON dataset: items, mobs, maps, npcs, quests
- Broken reference sweep report in Markdown: tools/hiddenstreet/report.md
"""
from __future__ import annotations

import argparse
from concurrent.futures import ProcessPoolExecutor
import csv
import glob
import json
import os
import re
import sys
import time
import xml.etree.ElementTree as ET
from collections import defaultdict
from typing import Any, Dict, List, Optional, Set, Tuple


# Common WZ search paths when --wz is omitted
DEFAULT_WZ_PATHS = [
    "wz",
    "/opt/mapleroot-dev/persistence-test/Server/wz",
]


def resolve_wz_dir(given: Optional[str]) -> str:
    if given and os.path.isdir(given):
        return os.path.abspath(given)
    for p in DEFAULT_WZ_PATHS:
        if os.path.isdir(p):
            return os.path.abspath(p)
    raise FileNotFoundError(f"Could not find WZ directory. Checked: {given}, {DEFAULT_WZ_PATHS}")


def safe_int(v: Any, default: Optional[int] = None) -> Optional[int]:
    if v is None:
        return default
    if isinstance(v, int):
        return v
    if isinstance(v, float):
        return int(v)
    v_str = str(v).strip()
    if v_str.isdigit() or (v_str.startswith("-") and v_str[1:].isdigit()):
        try:
            return int(v_str)
        except ValueError:
            pass
    return default


def parse_xml_safely(path: str) -> Optional[ET.Element]:
    try:
        return ET.parse(path).getroot()
    except Exception as e:
        return None


def get_child_text_or_val(el: ET.Element, tag: str, name: str) -> Optional[str]:
    sub = el.find(f"{tag}[@name='{name}']")
    if sub is not None:
        return sub.attrib.get("value")
    return None


def get_int_prop(el: ET.Element, name: str, default: Optional[int] = None) -> Optional[int]:
    for tag in ("int", "short", "long", "string"):
        sub = el.find(f"{tag}[@name='{name}']")
        if sub is not None:
            v = sub.attrib.get("value", "")
            try:
                return int(v)
            except ValueError:
                pass
    return default


def parse_numeric_stats(info_el: ET.Element) -> Dict[str, Any]:
    stats: Dict[str, Any] = {}
    for c in info_el:
        n = c.attrib.get("name")
        v = c.attrib.get("value")
        if not n or v is None:
            continue
        if c.tag in ("int", "short", "long"):
            try:
                stats[n] = int(v)
            except ValueError:
                stats[n] = v
        elif c.tag in ("float", "double"):
            try:
                stats[n] = float(v)
            except ValueError:
                stats[n] = v
        elif c.tag == "string":
            # Some numeric stats are written as strings
            if v.isdigit() or (v.startswith("-") and v[1:].isdigit()):
                stats[n] = int(v)
            else:
                stats[n] = v
    return stats


# ---------------------------------------------------------------------------------------------
# Workers for parallel parsing
# ---------------------------------------------------------------------------------------------

def _parse_map_file(fpath: str) -> Tuple[int, List[Tuple[str, int]], List[Dict[str, Any]]]:
    """Parse a single Map XML file returning (map_id, life_entries, portals)."""
    base = os.path.basename(fpath).split(".")[0]
    if not base.isdigit():
        return (-1, [], [])
    map_id = int(base)

    root = parse_xml_safely(fpath)
    if root is None:
        return (map_id, [], [])

    life_entries: List[Tuple[str, int]] = []
    life_el = root.find("imgdir[@name='life']")
    if life_el is not None:
        for child in life_el:
            type_val = get_child_text_or_val(child, "string", "type")
            id_val = get_child_text_or_val(child, "string", "id")
            if not id_val:
                id_val = get_child_text_or_val(child, "int", "id")
            if type_val and id_val and id_val.isdigit():
                life_entries.append((type_val, int(id_val)))

    portals: List[Dict[str, Any]] = []
    portal_el = root.find("imgdir[@name='portal']")
    if portal_el is not None:
        for child in portal_el:
            pn = get_child_text_or_val(child, "string", "pn") or ""
            pt_s = get_child_text_or_val(child, "int", "pt")
            pt = int(pt_s) if pt_s and pt_s.isdigit() else 0
            tm_s = get_child_text_or_val(child, "int", "tm")
            tm = int(tm_s) if tm_s and (tm_s.isdigit() or (tm_s.startswith("-") and tm_s[1:].isdigit())) else 999999999
            tn = get_child_text_or_val(child, "string", "tn") or ""
            portals.append({
                "name": pn,
                "type": pt,
                "target_map_id": tm,
                "target_portal": tn,
            })

    return (map_id, life_entries, portals)


def _parse_mob_file(fpath: str) -> Tuple[int, Dict[str, Any]]:
    """Parse a single Mob XML file returning (mob_id, raw_info)."""
    base = os.path.basename(fpath).split(".")[0]
    if not base.isdigit():
        return (-1, {})
    mob_id = int(base)

    root = parse_xml_safely(fpath)
    if root is None:
        return (mob_id, {})

    info_el = root.find("imgdir[@name='info']")
    if info_el is None:
        return (mob_id, {})

    stats = parse_numeric_stats(info_el)
    link = get_child_text_or_val(info_el, "string", "link")
    if link and link.isdigit():
        stats["link"] = int(link)
    return (mob_id, stats)


def _parse_char_equip_file(fpath: str) -> Tuple[int, str, Dict[str, Any]]:
    """Parse a single Character.wz equip XML file returning (item_id, category, raw_info)."""
    base = os.path.basename(fpath).split(".")[0]
    if not base.isdigit():
        return (-1, "", {})
    item_id = int(base)
    category = os.path.basename(os.path.dirname(fpath))

    root = parse_xml_safely(fpath)
    if root is None:
        return (item_id, category, {})

    info_el = root.find("imgdir[@name='info']")
    if info_el is None:
        return (item_id, category, {})

    stats = parse_numeric_stats(info_el)
    return (item_id, category, stats)


# ---------------------------------------------------------------------------------------------
# Main Loader and Processor
# ---------------------------------------------------------------------------------------------

class WzDataset:
    def __init__(self, wz_dir: str, workers: Optional[int] = None):
        self.wz_dir = wz_dir
        self.workers = workers or min(8, os.cpu_count() or 4)

        # Extracted registries
        self.item_strings: Dict[int, Dict[str, str]] = {}
        self.mob_strings: Dict[int, str] = {}
        self.npc_strings: Dict[int, str] = {}
        self.map_strings: Dict[int, Dict[str, str]] = {}

        self.wz_item_defs: Dict[int, Dict[str, Any]] = {}
        self.wz_mob_defs: Dict[int, Dict[str, Any]] = {}
        self.wz_npc_ids: Set[int] = set()
        self.wz_map_defs: Dict[int, Dict[str, Any]] = {}
        self.wz_quest_defs: Dict[int, Dict[str, Any]] = {}

    def load_strings(self) -> None:
        # 1. Item Strings
        for fn in ("Eqp", "Consume", "Etc", "Ins", "Cash", "Pet"):
            fpath = os.path.join(self.wz_dir, "String.wz", f"{fn}.img.xml")
            root = parse_xml_safely(fpath)
            if root is None:
                continue

            def walk_items(el: ET.Element):
                for c in el:
                    if c.tag == "imgdir":
                        name_attr = c.attrib.get("name")
                        if name_attr and name_attr.isdigit():
                            iid = int(name_attr)
                            nm = c.find("string[@name='name']")
                            desc = c.find("string[@name='desc']")
                            name_val = nm.attrib.get("value", "") if nm is not None else ""
                            desc_val = desc.attrib.get("value", "") if desc is not None else ""
                            if name_val or iid not in self.item_strings:
                                self.item_strings[iid] = {"name": name_val, "desc": desc_val}
                        walk_items(c)

            walk_items(root)

        # 2. Mob Strings
        mob_str_path = os.path.join(self.wz_dir, "String.wz", "Mob.img.xml")
        root = parse_xml_safely(mob_str_path)
        if root is not None:
            for c in root:
                n = c.attrib.get("name")
                if n and n.isdigit():
                    nm = c.find("string[@name='name']")
                    if nm is not None:
                        self.mob_strings[int(n)] = nm.attrib.get("value", "")

        # 3. NPC Strings
        npc_str_path = os.path.join(self.wz_dir, "String.wz", "Npc.img.xml")
        root = parse_xml_safely(npc_str_path)
        if root is not None:
            for c in root:
                n = c.attrib.get("name")
                if n and n.isdigit():
                    nm = c.find("string[@name='name']")
                    if nm is not None:
                        self.npc_strings[int(n)] = nm.attrib.get("value", "")

        # 4. Map Strings
        map_str_path = os.path.join(self.wz_dir, "String.wz", "Map.img.xml")
        root = parse_xml_safely(map_str_path)
        if root is not None:
            for cat in root:
                for c in cat:
                    n = c.attrib.get("name")
                    if n and n.isdigit():
                        mid = int(n)
                        m_nm = c.find("string[@name='mapName']")
                        s_nm = c.find("string[@name='streetName']")
                        map_name = m_nm.attrib.get("value", "") if m_nm is not None else ""
                        street_name = s_nm.attrib.get("value", "") if s_nm is not None else ""
                        # Store or prioritize non-empty
                        if mid not in self.map_strings or (map_name and not self.map_strings[mid].get("name")):
                            self.map_strings[mid] = {"name": map_name, "street": street_name}

    def load_items(self) -> None:
        # A. Character.wz
        char_files = glob.glob(os.path.join(self.wz_dir, "Character.wz", "*", "*.img.xml"))
        if self.workers > 1 and len(char_files) > 100:
            with ProcessPoolExecutor(max_workers=self.workers) as ex:
                results = ex.map(_parse_char_equip_file, char_files)
                for item_id, category, stats in results:
                    if item_id >= 0:
                        self.wz_item_defs[item_id] = {
                            "category": category,
                            "type": category,
                            "stats": stats,
                        }
        else:
            for f in char_files:
                item_id, category, stats = _parse_char_equip_file(f)
                if item_id >= 0:
                    self.wz_item_defs[item_id] = {
                        "category": category,
                        "type": category,
                        "stats": stats,
                    }

        # B. Item.wz
        item_dirs = glob.glob(os.path.join(self.wz_dir, "Item.wz", "*"))
        for d in item_dirs:
            if not os.path.isdir(d):
                continue
            cat = os.path.basename(d)
            for fpath in glob.glob(os.path.join(d, "*.img.xml")):
                base = os.path.basename(fpath).split(".")[0]
                root = parse_xml_safely(fpath)
                if root is None:
                    continue

                info_top = root.find("imgdir[@name='info']")
                if info_top is not None and base.isdigit() and len(base) >= 7:
                    # Single item XML file (e.g. Pet/5000000.img.xml)
                    iid = int(base)
                    spec_top = root.find("imgdir[@name='spec']")
                    stats = {}
                    stats.update(parse_numeric_stats(info_top))
                    if spec_top is not None:
                        stats.update(parse_numeric_stats(spec_top))
                    self.wz_item_defs[iid] = {
                        "category": cat,
                        "type": cat,
                        "stats": stats,
                    }
                else:
                    # Container XML file (e.g. Consume/0200.img.xml)
                    for c in root:
                        n = c.attrib.get("name")
                        if n and n.isdigit():
                            iid = int(n)
                            info_el = c.find("imgdir[@name='info']")
                            spec_el = c.find("imgdir[@name='spec']")
                            stats = {}
                            if info_el is not None:
                                stats.update(parse_numeric_stats(info_el))
                            if spec_el is not None:
                                stats.update(parse_numeric_stats(spec_el))
                            self.wz_item_defs[iid] = {
                                "category": cat,
                                "type": cat,
                                "stats": stats,
                            }

    def load_mobs(self) -> None:
        mob_files = glob.glob(os.path.join(self.wz_dir, "Mob.wz", "*.img.xml"))
        raw_mob_stats: Dict[int, Dict[str, Any]] = {}

        if self.workers > 1 and len(mob_files) > 100:
            with ProcessPoolExecutor(max_workers=self.workers) as ex:
                for mob_id, stats in ex.map(_parse_mob_file, mob_files):
                    if mob_id >= 0:
                        raw_mob_stats[mob_id] = stats
        else:
            for f in mob_files:
                mob_id, stats = _parse_mob_file(f)
                if mob_id >= 0:
                    raw_mob_stats[mob_id] = stats

        # Resolve mob links for level, hp, mp, exp
        def resolve_stat(mid: int, key: str, depth: int = 0) -> Any:
            if depth > 5 or mid not in raw_mob_stats:
                return None
            st = raw_mob_stats[mid]
            if key in st:
                return st[key]
            if "link" in st and isinstance(st["link"], int):
                return resolve_stat(st["link"], key, depth + 1)
            return None

        for mob_id, st in raw_mob_stats.items():
            lvl = resolve_stat(mob_id, "level")
            hp = resolve_stat(mob_id, "maxHP")
            mp = resolve_stat(mob_id, "maxMP")
            exp = resolve_stat(mob_id, "exp")
            self.wz_mob_defs[mob_id] = {
                "level": safe_int(lvl, 0),
                "hp": safe_int(hp, 0),
                "mp": safe_int(mp, 0),
                "exp": safe_int(exp, 0),
                "stats": st,
            }

    def load_npcs(self) -> None:
        for f in glob.glob(os.path.join(self.wz_dir, "Npc.wz", "*.img.xml")):
            base = os.path.basename(f).split(".")[0]
            if base.isdigit():
                self.wz_npc_ids.add(int(base))

    def load_maps(self) -> None:
        map_files = glob.glob(os.path.join(self.wz_dir, "Map.wz", "Map", "Map*", "*.img.xml"))
        if not map_files:
            # Fallback in case of flat structure or lowercase
            map_files = glob.glob(os.path.join(self.wz_dir, "Map.wz", "**", "*.img.xml"), recursive=True)
            map_files = [f for f in map_files if os.path.basename(f).split(".")[0].isdigit()]

        if self.workers > 1 and len(map_files) > 100:
            with ProcessPoolExecutor(max_workers=self.workers) as ex:
                for map_id, life_entries, portals in ex.map(_parse_map_file, map_files):
                    if map_id >= 0:
                        self.wz_map_defs[map_id] = {
                            "life": life_entries,
                            "portals": portals,
                        }
        else:
            for f in map_files:
                map_id, life_entries, portals = _parse_map_file(f)
                if map_id >= 0:
                    self.wz_map_defs[map_id] = {
                        "life": life_entries,
                        "portals": portals,
                    }

    def load_quests(self) -> None:
        qinfo_path = os.path.join(self.wz_dir, "Quest.wz", "QuestInfo.img.xml")
        qcheck_path = os.path.join(self.wz_dir, "Quest.wz", "Check.img.xml")
        qact_path = os.path.join(self.wz_dir, "Quest.wz", "Act.img.xml")

        qinfo_root = parse_xml_safely(qinfo_path)
        qcheck_root = parse_xml_safely(qcheck_path)
        qact_root = parse_xml_safely(qact_path)

        quest_map: Dict[int, Dict[str, Any]] = defaultdict(lambda: {
            "name": "",
            "start_npc": None,
            "end_npc": None,
            "requirements": {
                "min_level": None,
                "max_level": None,
                "jobs": [],
                "quests": [],
                "items": [],
                "mobs": [],
                "field_enter": None,
                "meso": 0,
            },
            "rewards": {
                "exp": 0,
                "meso": 0,
                "fame": 0,
                "next_quest": None,
                "items": [],
            },
            "raw_check": {},
            "raw_act": {},
        })

        if qinfo_root is not None:
            for c in qinfo_root:
                n = c.attrib.get("name")
                if n and n.isdigit():
                    qid = int(n)
                    nm = c.find("string[@name='name']")
                    if nm is not None:
                        quest_map[qid]["name"] = nm.attrib.get("value", "")

        if qcheck_root is not None:
            for q_el in qcheck_root:
                n = q_el.attrib.get("name")
                if not (n and n.isdigit()):
                    continue
                qid = int(n)
                qdata = quest_map[qid]

                for state in ("0", "1"):
                    s_el = q_el.find(f"imgdir[@name='{state}']")
                    if s_el is None:
                        continue
                    qdata["raw_check"][state] = s_el

                    # NPC
                    npc_val = get_int_prop(s_el, "npc")
                    if npc_val is not None and npc_val > 0:
                        if state == "0":
                            qdata["start_npc"] = npc_val
                        else:
                            qdata["end_npc"] = npc_val

                    # Level
                    lvmin = get_int_prop(s_el, "lvmin")
                    if lvmin is not None and state == "0":
                        qdata["requirements"]["min_level"] = lvmin
                    lvmax = get_int_prop(s_el, "lvmax")
                    if lvmax is not None and state == "0":
                        qdata["requirements"]["max_level"] = lvmax

                    # fieldEnter
                    fe = get_int_prop(s_el, "fieldEnter")
                    if fe is not None:
                        qdata["requirements"]["field_enter"] = fe

                    # Meso
                    if state == "0":
                        money = get_int_prop(s_el, "money")
                        if money:
                            qdata["requirements"]["meso"] = money
                    elif state == "1":
                        endmeso = get_int_prop(s_el, "endmeso")
                        if endmeso:
                            qdata["requirements"]["meso"] = endmeso

                    # Jobs
                    jobs_el = s_el.find("imgdir[@name='job']")
                    if jobs_el is not None and state == "0":
                        for je in jobs_el:
                            val = je.attrib.get("value")
                            if val and val.isdigit():
                                qdata["requirements"]["jobs"].append(int(val))

                    # Required Quests
                    quests_el = s_el.find("imgdir[@name='quest']")
                    if quests_el is not None and state == "0":
                        for qe in quests_el:
                            qid_sub = get_int_prop(qe, "id")
                            if qid_sub:
                                qdata["requirements"]["quests"].append(qid_sub)

                    # Required Items
                    items_el = s_el.find("imgdir[@name='item']")
                    if items_el is not None:
                        for ie in items_el:
                            iid = get_int_prop(ie, "id")
                            cnt = get_int_prop(ie, "count", 1)
                            if iid:
                                qdata["requirements"]["items"].append({
                                    "item_id": iid,
                                    "count": cnt,
                                    "state": int(state),
                                })

                    # Required Mobs
                    mobs_el = s_el.find("imgdir[@name='mob']")
                    if mobs_el is not None:
                        for me in mobs_el:
                            mid = get_int_prop(me, "id")
                            cnt = get_int_prop(me, "count", 1)
                            if mid:
                                qdata["requirements"]["mobs"].append({
                                    "mob_id": mid,
                                    "count": cnt,
                                    "state": int(state),
                                })

        if qact_root is not None:
            for q_el in qact_root:
                n = q_el.attrib.get("name")
                if not (n and n.isdigit()):
                    continue
                qid = int(n)
                qdata = quest_map[qid]

                for state in ("0", "1"):
                    s_el = q_el.find(f"imgdir[@name='{state}']")
                    if s_el is None:
                        continue
                    qdata["raw_act"][state] = s_el

                    exp = get_int_prop(s_el, "exp")
                    if exp:
                        qdata["rewards"]["exp"] += exp
                    money = get_int_prop(s_el, "money")
                    if money:
                        qdata["rewards"]["meso"] += money
                    pop = get_int_prop(s_el, "pop")
                    if pop:
                        qdata["rewards"]["fame"] += pop
                    next_q = get_int_prop(s_el, "nextQuest")
                    if next_q:
                        qdata["rewards"]["next_quest"] = next_q

                    items_el = s_el.find("imgdir[@name='item']")
                    if items_el is not None:
                        for ie in items_el:
                            iid = get_int_prop(ie, "id")
                            cnt = get_int_prop(ie, "count", 1)
                            prop = get_int_prop(ie, "prop")
                            if iid:
                                qdata["rewards"]["items"].append({
                                    "item_id": iid,
                                    "count": cnt,
                                    "prop": prop,
                                    "state": int(state),
                                })

        self.wz_quest_defs = dict(quest_map)


# ---------------------------------------------------------------------------------------------
# DB Loader
# ---------------------------------------------------------------------------------------------

class DbDataset:
    def __init__(self, db_dir: str):
        self.db_dir = db_dir
        self.drop_data: List[Dict[str, Any]] = []
        self.reactordrops: List[Dict[str, Any]] = []
        self.shops: Dict[int, int] = {}  # shopid -> npcid
        self.shopitems: List[Dict[str, Any]] = []

    def _read_tsv_or_json(self, name: str) -> List[Dict[str, Any]]:
        json_path = os.path.join(self.db_dir, f"{name}.json")
        if os.path.isfile(json_path):
            with open(json_path, "r", encoding="utf-8") as f:
                return json.load(f)

        tsv_path = os.path.join(self.db_dir, f"{name}.tsv")
        if os.path.isfile(tsv_path):
            rows: List[Dict[str, Any]] = []
            with open(tsv_path, "r", encoding="utf-8") as f:
                reader = csv.DictReader(f, delimiter="\t")
                for row in reader:
                    conv = {}
                    for k, v in row.items():
                        if v is None:
                            continue
                        k_clean = k.strip()
                        v_clean = v.strip()
                        if v_clean.isdigit() or (v_clean.startswith("-") and v_clean[1:].isdigit()):
                            conv[k_clean] = int(v_clean)
                        else:
                            conv[k_clean] = v_clean
                    rows.append(conv)
            return rows

        return []

    def load(self) -> None:
        self.drop_data = self._read_tsv_or_json("drop_data")
        self.reactordrops = self._read_tsv_or_json("reactordrops")
        shops_rows = self._read_tsv_or_json("shops")
        for s in shops_rows:
            sid = s.get("shopid")
            nid = s.get("npcid")
            if sid is not None and nid is not None:
                self.shops[int(sid)] = int(nid)
        self.shopitems = self._read_tsv_or_json("shopitems")


# ---------------------------------------------------------------------------------------------
# Data Builder & Cross-Referencer
# ---------------------------------------------------------------------------------------------

def build_normalized_data(wz: WzDataset, db: DbDataset) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    """Build normalized dataset and conduct reference integrity sweep."""

    # 1. Gather all known master IDs
    all_item_ids: Set[int] = set(wz.wz_item_defs.keys()) | set(wz.item_strings.keys())
    # Mobs, NPCs and maps exist only if Mob.wz / Npc.wz / Map.wz has a file for them. String.wz only supplies names:
    # a name left behind in String.wz for a removed mob/NPC/map must still be reported as missing.
    all_mob_ids: Set[int] = set(wz.wz_mob_defs.keys())
    all_npc_ids: Set[int] = set(wz.wz_npc_ids)
    all_map_ids: Set[int] = set(wz.wz_map_defs.keys())
    all_quest_ids: Set[int] = set(wz.wz_quest_defs.keys())

    # 2. Master Entities Storage
    items_out: Dict[str, Dict[str, Any]] = {}
    mobs_out: Dict[str, Dict[str, Any]] = {}
    maps_out: Dict[str, Dict[str, Any]] = {}
    npcs_out: Dict[str, Dict[str, Any]] = {}
    quests_out: Dict[str, Dict[str, Any]] = {}

    # Initialize Items
    for iid in sorted(all_item_ids):
        str_info = wz.item_strings.get(iid, {})
        def_info = wz.wz_item_defs.get(iid, {})
        stats = def_info.get("stats", {})
        req_lvl = stats.get("reqLevel", 0)
        req_job = stats.get("reqJob", 0)
        category = def_info.get("type") or def_info.get("category") or "Unknown"

        items_out[str(iid)] = {
            "id": iid,
            "name": str_info.get("name", ""),
            "desc": str_info.get("desc", ""),
            "type": category,
            "req_level": req_lvl,
            "req_job": req_job,
            "stats": stats,
            "drops": [],
            "shops": [],
            "quests_reward": [],
            "quests_require": [],
        }

    # Initialize Mobs
    for mid in sorted(all_mob_ids):
        mdef = wz.wz_mob_defs.get(mid, {})
        mobs_out[str(mid)] = {
            "id": mid,
            "name": wz.mob_strings.get(mid, ""),
            "level": mdef.get("level", 0),
            "hp": mdef.get("hp", 0),
            "mp": mdef.get("mp", 0),
            "exp": mdef.get("exp", 0),
            "stats": mdef.get("stats", {}),
            "maps": [],
            "drops": [],
        }

    # Initialize NPCs
    for nid in sorted(all_npc_ids):
        npcs_out[str(nid)] = {
            "id": nid,
            "name": wz.npc_strings.get(nid, ""),
            "maps": [],
            "shop_items": [],
            "quests_start": [],
            "quests_end": [],
        }

    # Initialize Maps
    for mid in sorted(all_map_ids):
        mdef = wz.wz_map_defs.get(mid, {})
        str_info = wz.map_strings.get(mid, {})
        maps_out[str(mid)] = {
            "id": mid,
            "name": str_info.get("name", ""),
            "street": str_info.get("street", ""),
            "mobs": [],
            "npcs": [],
            "portals": mdef.get("portals", []),
            "targets": [],
        }

    # Initialize Quests
    for qid in sorted(all_quest_ids):
        qdef = wz.wz_quest_defs.get(qid, {})
        s_npc_id = qdef.get("start_npc")
        e_npc_id = qdef.get("end_npc")
        quests_out[str(qid)] = {
            "id": qid,
            "name": qdef.get("name", ""),
            "start_npc": {
                "id": s_npc_id,
                "name": wz.npc_strings.get(s_npc_id, "") if s_npc_id else "",
            } if s_npc_id else None,
            "end_npc": {
                "id": e_npc_id,
                "name": wz.npc_strings.get(e_npc_id, "") if e_npc_id else "",
            } if e_npc_id else None,
            "requirements": qdef.get("requirements", {}),
            "rewards": qdef.get("rewards", {}),
        }

    # 3. Sweep / Broken Reference Auditing
    audit: Dict[str, Any] = {
        "items_no_name": [],
        "drops_missing_items": [],
        "quests_missing_references": [],
        "portals_missing_maps": [],
        "missing_life_placed": [],
    }

    # Audit Category 1: Items with no name
    for iid in sorted(wz.wz_item_defs.keys()):
        sinfo = wz.item_strings.get(iid)
        name = sinfo.get("name", "").strip() if sinfo else ""
        if not name:
            audit["items_no_name"].append({
                "item_id": iid,
                "category": wz.wz_item_defs[iid].get("category", "Unknown"),
            })

    # Audit Category 2 & Cross-reference: Drops & Shops
    for d in db.drop_data:
        dropperid = d.get("dropperid")
        itemid = d.get("itemid")
        chance = d.get("chance", 0)
        min_qty = d.get("minimum_quantity", 1)
        max_qty = d.get("maximum_quantity", 1)
        questid = d.get("questid", 0)

        if itemid is None or dropperid is None:
            continue

        if itemid != 0 and itemid not in all_item_ids:
            audit["drops_missing_items"].append({
                "source": "drop_data",
                "source_id": dropperid,
                "item_id": itemid,
                "notes": f"dropperid={dropperid}, chance={chance}",
            })

        # Cross reference mob drops
        dropper_str = str(dropperid)
        if dropper_str in mobs_out:
            mobs_out[dropper_str]["drops"].append({
                "item_id": itemid,
                "item_name": "Mesos" if itemid == 0 else items_out.get(str(itemid), {}).get("name", ""),
                "chance": chance,
                "min_qty": min_qty,
                "max_qty": max_qty,
                "quest_id": questid,
            })

        # Cross reference item drops
        if itemid != 0 and str(itemid) in items_out:
            items_out[str(itemid)]["drops"].append({
                "mob_id": dropperid,
                "mob_name": wz.mob_strings.get(dropperid, ""),
                "chance": chance,
                "min_qty": min_qty,
                "max_qty": max_qty,
                "quest_id": questid,
            })

    for rd in db.reactordrops:
        reactorid = rd.get("reactorid")
        itemid = rd.get("itemid")
        chance = rd.get("chance", 0)
        questid = rd.get("questid", 0)

        if itemid is None or reactorid is None:
            continue

        if itemid != 0 and itemid not in all_item_ids:
            audit["drops_missing_items"].append({
                "source": "reactordrops",
                "source_id": reactorid,
                "item_id": itemid,
                "notes": f"reactorid={reactorid}, chance={chance}",
            })

    # Shop Items Cross-reference
    for si in db.shopitems:
        sid = si.get("shopid")
        itemid = si.get("itemid")
        price = si.get("price", 0)
        pos = si.get("position", 0)

        if sid is None or itemid is None:
            continue

        npcid = db.shops.get(sid, 0)

        if itemid not in all_item_ids:
            audit["drops_missing_items"].append({
                "source": "shopitems",
                "source_id": sid,
                "item_id": itemid,
                "notes": f"shopid={sid}, npcid={npcid}, price={price}",
            })

        if str(itemid) in items_out:
            items_out[str(itemid)]["shops"].append({
                "shop_id": sid,
                "npc_id": npcid,
                "npc_name": wz.npc_strings.get(npcid, ""),
                "price": price,
                "position": pos,
            })

        if str(npcid) in npcs_out:
            npcs_out[str(npcid)]["shop_items"].append({
                "shop_id": sid,
                "item_id": itemid,
                "item_name": items_out.get(str(itemid), {}).get("name", ""),
                "price": price,
                "position": pos,
            })

    # Cross-reference & Audit: Maps, Mobs, NPCs, Portals
    for mid, mdef in wz.wz_map_defs.items():
        mid_str = str(mid)
        map_obj = maps_out[mid_str]
        map_name = map_obj["name"]
        map_street = map_obj["street"]

        # Life count frequency
        mob_counts: Dict[int, int] = defaultdict(int)
        npc_counts: Dict[int, int] = defaultdict(int)

        for ltype, lid in mdef.get("life", []):
            if ltype == "m":
                mob_counts[lid] += 1
            elif ltype == "n":
                npc_counts[lid] += 1

        for mob_id, count in mob_counts.items():
            if mob_id not in all_mob_ids:
                audit["missing_life_placed"].append({
                    "map_id": mid,
                    "map_name": map_name,
                    "life_type": "mob",
                    "life_id": mob_id,
                    "count": count,
                })
            map_obj["mobs"].append({
                "mob_id": mob_id,
                "name": wz.mob_strings.get(mob_id, ""),
                "count": count,
            })
            if str(mob_id) in mobs_out:
                mobs_out[str(mob_id)]["maps"].append({
                    "map_id": mid,
                    "name": map_name,
                    "street": map_street,
                    "count": count,
                })

        for npc_id, count in npc_counts.items():
            if npc_id not in all_npc_ids:
                audit["missing_life_placed"].append({
                    "map_id": mid,
                    "map_name": map_name,
                    "life_type": "npc",
                    "life_id": npc_id,
                    "count": count,
                })
            map_obj["npcs"].append({
                "npc_id": npc_id,
                "name": wz.npc_strings.get(npc_id, ""),
                "count": count,
            })
            if str(npc_id) in npcs_out:
                npcs_out[str(npc_id)]["maps"].append({
                    "map_id": mid,
                    "name": map_name,
                    "street": map_street,
                })

        # Portals & Targets
        target_set: Set[int] = set()
        for p in map_obj["portals"]:
            tm = p["target_map_id"]
            if tm != 999999999 and tm > 0:
                target_set.add(tm)
                if tm not in all_map_ids:
                    audit["portals_missing_maps"].append({
                        "source_map_id": mid,
                        "source_map_name": map_name,
                        "portal_name": p["name"],
                        "target_map_id": tm,
                    })
        map_obj["targets"] = sorted(list(target_set))

    # Cross-reference & Audit: Quests
    for qid, qdef in wz.wz_quest_defs.items():
        qname = qdef.get("name", "")

        # NPC start/end
        s_npc = qdef.get("start_npc")
        if s_npc is not None:
            if s_npc not in all_npc_ids:
                audit["quests_missing_references"].append({
                    "quest_id": qid,
                    "quest_name": qname,
                    "ref_type": "npc",
                    "missing_id": s_npc,
                    "state": 0,
                })
            else:
                if str(s_npc) in npcs_out:
                    npcs_out[str(s_npc)]["quests_start"].append({
                        "quest_id": qid,
                        "quest_name": qname,
                    })

        e_npc = qdef.get("end_npc")
        if e_npc is not None:
            if e_npc not in all_npc_ids:
                audit["quests_missing_references"].append({
                    "quest_id": qid,
                    "quest_name": qname,
                    "ref_type": "npc",
                    "missing_id": e_npc,
                    "state": 1,
                })
            else:
                if str(e_npc) in npcs_out:
                    npcs_out[str(e_npc)]["quests_end"].append({
                        "quest_id": qid,
                        "quest_name": qname,
                    })

        # Field Enter
        fe = qdef.get("requirements", {}).get("field_enter")
        if fe is not None and fe not in all_map_ids:
            audit["quests_missing_references"].append({
                "quest_id": qid,
                "quest_name": qname,
                "ref_type": "map",
                "missing_id": fe,
                "state": 0,
            })

        # Required Items
        for r_item in qdef.get("requirements", {}).get("items", []):
            iid = r_item["item_id"]
            if iid not in all_item_ids:
                audit["quests_missing_references"].append({
                    "quest_id": qid,
                    "quest_name": qname,
                    "ref_type": "item",
                    "missing_id": iid,
                    "state": r_item.get("state", 0),
                })
            else:
                r_item["item_name"] = items_out.get(str(iid), {}).get("name", "")
                if str(iid) in items_out:
                    items_out[str(iid)]["quests_require"].append({
                        "quest_id": qid,
                        "quest_name": qname,
                        "count": r_item.get("count", 1),
                        "state": r_item.get("state", 0),
                    })

        # Required Mobs
        for r_mob in qdef.get("requirements", {}).get("mobs", []):
            mid = r_mob["mob_id"]
            if mid not in all_mob_ids:
                audit["quests_missing_references"].append({
                    "quest_id": qid,
                    "quest_name": qname,
                    "ref_type": "mob",
                    "missing_id": mid,
                    "state": r_mob.get("state", 0),
                })
            else:
                r_mob["mob_name"] = wz.mob_strings.get(mid, "")

        # Reward Items
        for rew in qdef.get("rewards", {}).get("items", []):
            iid = rew["item_id"]
            cnt = rew.get("count", 1)
            prop = rew.get("prop")

            if iid not in all_item_ids:
                audit["drops_missing_items"].append({
                    "source": "quest_reward",
                    "source_id": qid,
                    "item_id": iid,
                    "notes": f"quest_id={qid}, quest_name='{qname}', count={cnt}",
                })
            else:
                rew["item_name"] = items_out.get(str(iid), {}).get("name", "")
                if str(iid) in items_out:
                    if cnt > 0:
                        items_out[str(iid)]["quests_reward"].append({
                            "quest_id": qid,
                            "quest_name": qname,
                            "count": cnt,
                            "prop": prop,
                        })
                    else:
                        items_out[str(iid)]["quests_require"].append({
                            "quest_id": qid,
                            "quest_name": qname,
                            "count": -cnt,
                            "state": rew.get("state", 1),
                        })

    dataset = {
        "items": items_out,
        "mobs": mobs_out,
        "maps": maps_out,
        "npcs": npcs_out,
        "quests": quests_out,
        "summary": {
            "total_items": len(items_out),
            "total_mobs": len(mobs_out),
            "total_maps": len(maps_out),
            "total_npcs": len(npcs_out),
            "total_quests": len(quests_out),
        },
    }

    audit_summary = {
        "counts": {
            "items_no_name": len(audit["items_no_name"]),
            "drops_missing_items": len(audit["drops_missing_items"]),
            "quests_missing_references": len(audit["quests_missing_references"]),
            "portals_missing_maps": len(audit["portals_missing_maps"]),
            "missing_life_placed": len(audit["missing_life_placed"]),
        },
        "details": audit,
    }

    return dataset, audit_summary


# ---------------------------------------------------------------------------------------------
# Report Generator
# ---------------------------------------------------------------------------------------------

def generate_report_markdown(audit: Dict[str, Any], dataset_summary: Dict[str, Any]) -> str:
    counts = audit["counts"]
    details = audit["details"]

    lines = [
        "# SlumsMS Hidden Street Data Sweep Report",
        "",
        "Generated as part of Ticket 78 (Phase 1: Data Generator & Sweep Report).",
        "This sweep checks cross-references across WZ XML data and server database tables to detect broken references, missing assets, and unlinked content.",
        "",
        "## Summary of Broken References",
        "",
        "| Category | Issue Count | Description |",
        "| --- | --- | --- |",
        f"| **Items with no name** | {counts['items_no_name']} | Items in Item.wz/Character.wz with missing or empty String.wz name |",
        f"| **Drops / Rewards / Shop missing items** | {counts['drops_missing_items']} | DB drop_data, reactordrops, shopitems or quest rewards pointing to missing items |",
        f"| **Quests with missing references** | {counts['quests_missing_references']} | Quests pointing to non-existent NPCs, maps, items, or mobs |",
        f"| **Portals leading to missing maps** | {counts['portals_missing_maps']} | Map portals targeting non-existent map IDs |",
        f"| **Mobs / NPCs placed that don't exist** | {counts['missing_life_placed']} | Maps with life spawns (type 'm' or 'n') pointing to missing mob/NPC IDs |",
        "",
        "### Dataset Entity Counts",
        f"- **Items:** {dataset_summary.get('total_items', 0):,}",
        f"- **Monsters:** {dataset_summary.get('total_mobs', 0):,}",
        f"- **Maps:** {dataset_summary.get('total_maps', 0):,}",
        f"- **NPCs:** {dataset_summary.get('total_npcs', 0):,}",
        f"- **Quests:** {dataset_summary.get('total_quests', 0):,}",
        "",
        "---",
        "",
    ]

    # Category 1: Items with no name
    lines.append(f"## 1. Items with No Name (Total: {counts['items_no_name']})")
    lines.append("Items defined in WZ data files that have no matching name string in `String.wz`.")
    if audit["details"]["items_no_name"]:
        lines.append("")
        lines.append("| # | Item ID | Category / Path |")
        lines.append("| --- | --- | --- |")
        for idx, it in enumerate(details["items_no_name"][:50], 1):
            lines.append(f"| {idx} | `{it['item_id']}` | {it['category']} |")
        if counts["items_no_name"] > 50:
            lines.append(f"*(Showing first 50 of {counts['items_no_name']} items)*")
    else:
        lines.append("\n*None found.*")
    lines.append("\n---\n")

    # Category 2: Drops / rewards / shop rows pointing at missing items
    lines.append(f"## 2. Drops, Rewards, and Shop Rows Pointing at Missing Items (Total: {counts['drops_missing_items']})")
    lines.append("Database drops, reactor drops, NPC shop listings, or quest rewards referencing item IDs not found in game data.")
    if audit["details"]["drops_missing_items"]:
        lines.append("")
        lines.append("| # | Source | Source ID | Missing Item ID | Notes |")
        lines.append("| --- | --- | --- | --- | --- |")
        for idx, it in enumerate(details["drops_missing_items"][:50], 1):
            lines.append(f"| {idx} | `{it['source']}` | `{it['source_id']}` | **`{it['item_id']}`** | {it['notes']} |")
        if counts["drops_missing_items"] > 50:
            lines.append(f"*(Showing first 50 of {counts['drops_missing_items']} entries)*")
    else:
        lines.append("\n*None found.*")
    lines.append("\n---\n")

    # Category 3: Quests with missing references
    lines.append(f"## 3. Quests with Missing References (Total: {counts['quests_missing_references']})")
    lines.append("Quests requiring or involving an NPC, map, item, or mob that does not exist.")
    if audit["details"]["quests_missing_references"]:
        lines.append("")
        lines.append("| # | Quest ID | Quest Name | Ref Type | Missing ID | State (0=Start, 1=Complete) |")
        lines.append("| --- | --- | --- | --- | --- | --- |")
        for idx, it in enumerate(details["quests_missing_references"][:50], 1):
            lines.append(f"| {idx} | `{it['quest_id']}` | {it['quest_name'] or '*(unnamed)*'} | `{it['ref_type']}` | **`{it['missing_id']}`** | {it['state']} |")
        if counts["quests_missing_references"] > 50:
            lines.append(f"*(Showing first 50 of {counts['quests_missing_references']} entries)*")
    else:
        lines.append("\n*None found.*")
    lines.append("\n---\n")

    # Category 4: Portals leading to missing maps
    lines.append(f"## 4. Portals Leading to Missing Maps (Total: {counts['portals_missing_maps']})")
    lines.append("Map portals whose destination `tm` does not correspond to any valid map.")
    if audit["details"]["portals_missing_maps"]:
        lines.append("")
        lines.append("| # | Source Map ID | Source Map Name | Portal Name | Target Map ID |")
        lines.append("| --- | --- | --- | --- | --- |")
        for idx, it in enumerate(details["portals_missing_maps"][:50], 1):
            lines.append(f"| {idx} | `{it['source_map_id']}` | {it['source_map_name'] or '*(unnamed)*'} | `{it['portal_name']}` | **`{it['target_map_id']}`** |")
        if counts["portals_missing_maps"] > 50:
            lines.append(f"*(Showing first 50 of {counts['portals_missing_maps']} portals)*")
    else:
        lines.append("\n*None found.*")
    lines.append("\n---\n")

    # Category 5: Mobs/NPCs placed on maps that don't exist
    lines.append(f"## 5. Mobs and NPCs Placed on Maps That Do Not Exist (Total: {counts['missing_life_placed']})")
    lines.append("Life placements in map files referencing non-existent mob or NPC IDs.")
    if audit["details"]["missing_life_placed"]:
        lines.append("")
        lines.append("| # | Map ID | Map Name | Life Type | Life ID | Spawn Count |")
        lines.append("| --- | --- | --- | --- | --- | --- |")
        for idx, it in enumerate(details["missing_life_placed"][:50], 1):
            lines.append(f"| {idx} | `{it['map_id']}` | {it['map_name'] or '*(unnamed)*'} | `{it['life_type']}` | **`{it['life_id']}`** | {it.get('count', 1)} |")
        if counts["missing_life_placed"] > 50:
            lines.append(f"*(Showing first 50 of {counts['missing_life_placed']} placements)*")
    else:
        lines.append("\n*None found.*")
    lines.append("\n")

    return "\n".join(lines)


# ---------------------------------------------------------------------------------------------
# Main Execution Pipeline
# ---------------------------------------------------------------------------------------------

def run_build(
    wz_dir: Optional[str] = None,
    db_dir: Optional[str] = None,
    out_dir: Optional[str] = None,
    report_path: Optional[str] = None,
    workers: Optional[int] = None,
) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    t0 = time.time()
    wz_path = resolve_wz_dir(wz_dir)
    print(f"Reading WZ data from: {wz_path}")

    # Resolve db directory
    db_candidates = [db_dir, "cache/db", "/root/work/SlumsMS-152-site/cache/db"]
    resolved_db_dir = ""
    for cand in db_candidates:
        if cand and os.path.isdir(cand):
            resolved_db_dir = cand
            break

    print(f"Reading DB cache from: {resolved_db_dir or '(none - DB relations will be empty)'}")

    # Load WZ
    wz = WzDataset(wz_path, workers=workers)
    print("Loading String.wz...")
    wz.load_strings()
    print(f"  String items: {len(wz.item_strings)}, mobs: {len(wz.mob_strings)}, npcs: {len(wz.npc_strings)}, maps: {len(wz.map_strings)}")

    print("Loading Items (Character.wz + Item.wz)...")
    wz.load_items()
    print(f"  Total WZ items: {len(wz.wz_item_defs)}")

    print("Loading Mobs (Mob.wz)...")
    wz.load_mobs()
    print(f"  Total WZ mobs: {len(wz.wz_mob_defs)}")

    print("Loading NPCs (Npc.wz)...")
    wz.load_npcs()
    print(f"  Total WZ npcs: {len(wz.wz_npc_ids)}")

    print("Loading Maps (Map.wz)...")
    wz.load_maps()
    print(f"  Total WZ maps: {len(wz.wz_map_defs)}")

    print("Loading Quests (Quest.wz)...")
    wz.load_quests()
    print(f"  Total WZ quests: {len(wz.wz_quest_defs)}")

    # Load DB
    db = DbDataset(resolved_db_dir)
    if resolved_db_dir:
        db.load()
        print(f"  DB drop_data: {len(db.drop_data)}, reactordrops: {len(db.reactordrops)}, shops: {len(db.shops)}, shopitems: {len(db.shopitems)}")

    # Build & cross-reference
    print("Cross-referencing entities and sweeping for broken references...")
    dataset, audit = build_normalized_data(wz, db)

    # Output normalized dataset
    if out_dir:
        os.makedirs(out_dir, exist_ok=True)
        print(f"Writing normalized dataset JSON files to {out_dir}...")
        for name in ("items", "mobs", "maps", "npcs", "quests", "summary"):
            out_file = os.path.join(out_dir, f"{name}.json")
            with open(out_file, "w", encoding="utf-8") as f:
                json.dump(dataset[name], f, indent=2)

    # Output report
    rep_md = generate_report_markdown(audit, dataset["summary"])
    if report_path:
        os.makedirs(os.path.dirname(os.path.abspath(report_path)), exist_ok=True)
        with open(report_path, "w", encoding="utf-8") as f:
            f.write(rep_md)
        print(f"Wrote sweep report to {report_path}")

    elapsed = time.time() - t0
    print(f"Completed in {elapsed:.2f}s.")
    return dataset, audit


def main() -> int:
    parser = argparse.ArgumentParser(description="Build SlumsMS Hidden Street dataset and sweep report.")
    parser.add_argument("--wz", type=str, default=None, help="Path to WZ root folder")
    parser.add_argument("--db-dir", type=str, default=None, help="Path to directory containing exported DB TSV/JSON files")
    parser.add_argument("--out-dir", type=str, default="data/hiddenstreet", help="Directory for normalized JSON dataset")
    parser.add_argument("--report", type=str, default="tools/hiddenstreet/report.md", help="Output Markdown report path")
    parser.add_argument("--workers", type=int, default=None, help="Number of worker processes")
    args = parser.parse_args()

    run_build(
        wz_dir=args.wz,
        db_dir=args.db_dir,
        out_dir=args.out_dir,
        report_path=args.report,
        workers=args.workers,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
