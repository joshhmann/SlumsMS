#!/usr/bin/env python3
"""Tests for SlumsMS Hidden Street dataset builder and reference sweep (Ticket 78)."""

import os
import shutil
import tempfile
import unittest

from tools.hiddenstreet.build_data import (
    WzDataset,
    DbDataset,
    build_normalized_data,
    generate_report_markdown,
    run_build,
)


class TestHiddenStreetBuildData(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="slums_hs_test_")
        self.wz_dir = os.path.join(self.test_dir, "wz")
        self.db_dir = os.path.join(self.test_dir, "db")
        self.out_dir = os.path.join(self.test_dir, "out")
        self.report_path = os.path.join(self.test_dir, "report.md")

        os.makedirs(self.wz_dir, exist_ok=True)
        os.makedirs(self.db_dir, exist_ok=True)
        self._build_fixture()

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def _write_file(self, rel_path: str, content: str) -> None:
        full_path = os.path.join(self.wz_dir, rel_path)
        os.makedirs(os.path.dirname(full_path), exist_ok=True)
        with open(full_path, "w", encoding="utf-8") as f:
            f.write(content.strip())

    def _build_fixture(self) -> None:
        # String.wz
        self._write_file("String.wz/Map.img.xml", """
<imgdir name="Map.img">
    <imgdir name="victoria">
        <imgdir name="100000000">
            <string name="mapName" value="Henesys"/>
            <string name="streetName" value="Victoria Road"/>
        </imgdir>
        <imgdir name="100000001">
            <string name="mapName" value="Maya's House"/>
            <string name="streetName" value="Victoria Road"/>
        </imgdir>
    </imgdir>
</imgdir>
""")
        self._write_file("String.wz/Mob.img.xml", """
<imgdir name="Mob.img">
    <imgdir name="100100">
        <string name="name" value="Snail"/>
    </imgdir>
    <imgdir name="100101">
        <string name="name" value="Blue Snail"/>
    </imgdir>
</imgdir>
""")
        self._write_file("String.wz/Npc.img.xml", """
<imgdir name="Npc.img">
    <imgdir name="1012000">
        <string name="name" value="Regular Cab"/>
    </imgdir>
    <imgdir name="1012001">
        <string name="name" value="Athena Pierce"/>
    </imgdir>
</imgdir>
""")
        self._write_file("String.wz/Eqp.img.xml", """
<imgdir name="Eqp.img">
    <imgdir name="Eqp">
        <imgdir name="Weapon">
            <imgdir name="1302000">
                <string name="name" value="Sword"/>
                <string name="desc" value="A basic sword."/>
            </imgdir>
        </imgdir>
    </imgdir>
</imgdir>
""")
        self._write_file("String.wz/Consume.img.xml", """
<imgdir name="Consume.img">
    <imgdir name="2000000">
        <string name="name" value="Red Potion"/>
        <string name="desc" value="Restores 50 HP."/>
    </imgdir>
</imgdir>
""")
        self._write_file("String.wz/Pet.img.xml", """
<imgdir name="Pet.img">
    <imgdir name="5000000">
        <string name="name" value="Puppy"/>
        <string name="desc" value="A cute puppy."/>
    </imgdir>
</imgdir>
""")

        # Character.wz
        # 1. Valid sword item
        self._write_file("Character.wz/Weapon/01302000.img.xml", """
<imgdir name="01302000.img">
    <imgdir name="info">
        <int name="reqJob" value="0"/>
        <int name="reqLevel" value="0"/>
        <int name="reqSTR" value="0"/>
        <int name="incPAD" value="17"/>
        <int name="price" value="1"/>
        <int name="tuc" value="7"/>
    </imgdir>
</imgdir>
""")
        # 2. Cap item with NO name in String.wz -> triggers items_no_name
        self._write_file("Character.wz/Cap/01000000.img.xml", """
<imgdir name="01000000.img">
    <imgdir name="info">
        <int name="reqJob" value="0"/>
        <int name="reqLevel" value="10"/>
        <int name="incPDD" value="5"/>
    </imgdir>
</imgdir>
""")

        # Item.wz
        # 1. Container with Red Potion
        self._write_file("Item.wz/Consume/0200.img.xml", """
<imgdir name="0200.img">
    <imgdir name="2000000">
        <imgdir name="info">
            <int name="price" value="25"/>
            <int name="slotMax" value="100"/>
        </imgdir>
        <imgdir name="spec">
            <int name="hp" value="50"/>
        </imgdir>
    </imgdir>
</imgdir>
""")
        # 2. Single item Pet
        self._write_file("Item.wz/Pet/5000000.img.xml", """
<imgdir name="5000000.img">
    <imgdir name="info">
        <int name="hungry" value="2"/>
        <int name="life" value="90"/>
    </imgdir>
</imgdir>
""")

        # Mob.wz
        # 1. Normal mob
        self._write_file("Mob.wz/0100100.img.xml", """
<imgdir name="0100100.img">
    <imgdir name="info">
        <int name="level" value="1"/>
        <int name="maxHP" value="8"/>
        <int name="maxMP" value="0"/>
        <int name="exp" value="3"/>
    </imgdir>
</imgdir>
""")
        # 2. Linked mob referencing 0100100
        self._write_file("Mob.wz/0100101.img.xml", """
<imgdir name="0100101.img">
    <imgdir name="info">
        <string name="link" value="0100100"/>
    </imgdir>
</imgdir>
""")

        # Npc.wz
        self._write_file("Npc.wz/1012000.img.xml", "<imgdir name='1012000.img'><imgdir name='info'/></imgdir>")
        self._write_file("Npc.wz/1012001.img.xml", "<imgdir name='1012001.img'><imgdir name='info'/></imgdir>")

        # Map.wz
        self._write_file("Map.wz/Map/Map1/100000000.img.xml", """
<imgdir name="100000000.img">
    <imgdir name="info">
        <int name="town" value="1"/>
    </imgdir>
    <imgdir name="life">
        <imgdir name="0">
            <string name="id" value="100100"/>
            <string name="type" value="m"/>
        </imgdir>
        <imgdir name="1">
            <string name="id" value="100100"/>
            <string name="type" value="m"/>
        </imgdir>
        <imgdir name="2">
            <string name="id" value="9999999"/>
            <string name="type" value="m"/>
        </imgdir>
        <imgdir name="3">
            <string name="id" value="1012000"/>
            <string name="type" value="n"/>
        </imgdir>
        <imgdir name="4">
            <string name="id" value="8888888"/>
            <string name="type" value="n"/>
        </imgdir>
    </imgdir>
    <imgdir name="portal">
        <imgdir name="0">
            <string name="pn" value="sp"/>
            <int name="pt" value="0"/>
            <int name="tm" value="999999999"/>
            <string name="tn" value=""/>
        </imgdir>
        <imgdir name="1">
            <string name="pn" value="out00"/>
            <int name="pt" value="2"/>
            <int name="tm" value="100000001"/>
            <string name="tn" value="in00"/>
        </imgdir>
        <imgdir name="2">
            <string name="pn" value="broken01"/>
            <int name="pt" value="2"/>
            <int name="tm" value="999999900"/>
            <string name="tn" value="in00"/>
        </imgdir>
    </imgdir>
</imgdir>
""")
        self._write_file("Map.wz/Map/Map1/100000001.img.xml", """
<imgdir name="100000001.img">
    <imgdir name="info"/>
    <imgdir name="life"/>
    <imgdir name="portal">
        <imgdir name="0">
            <string name="pn" value="in00"/>
            <int name="pt" value="2"/>
            <int name="tm" value="100000000"/>
            <string name="tn" value="out00"/>
        </imgdir>
    </imgdir>
</imgdir>
""")

        # Quest.wz
        self._write_file("Quest.wz/QuestInfo.img.xml", """
<imgdir name="QuestInfo.img">
    <imgdir name="1000">
        <string name="name" value="Valid Quest"/>
    </imgdir>
    <imgdir name="1001">
        <string name="name" value="Missing Start NPC Quest"/>
    </imgdir>
    <imgdir name="1002">
        <string name="name" value="Missing Map Quest"/>
    </imgdir>
    <imgdir name="1003">
        <string name="name" value="Missing Item Quest"/>
    </imgdir>
    <imgdir name="1004">
        <string name="name" value="Missing Mob Quest"/>
    </imgdir>
    <imgdir name="1005">
        <string name="name" value="Missing Reward Item Quest"/>
    </imgdir>
</imgdir>
""")
        self._write_file("Quest.wz/Check.img.xml", """
<imgdir name="Check.img">
    <imgdir name="1000">
        <imgdir name="0">
            <int name="npc" value="1012000"/>
            <int name="lvmin" value="10"/>
            <imgdir name="job"><int name="0" value="0"/></imgdir>
            <imgdir name="item">
                <imgdir name="0">
                    <int name="id" value="2000000"/>
                    <int name="count" value="1"/>
                </imgdir>
            </imgdir>
        </imgdir>
        <imgdir name="1">
            <int name="npc" value="1012001"/>
            <imgdir name="mob">
                <imgdir name="0">
                    <int name="id" value="100100"/>
                    <int name="count" value="5"/>
                </imgdir>
            </imgdir>
        </imgdir>
    </imgdir>
    <imgdir name="1001">
        <imgdir name="0">
            <int name="npc" value="7777777"/>
        </imgdir>
    </imgdir>
    <imgdir name="1002">
        <imgdir name="0">
            <int name="fieldEnter" value="6666666"/>
        </imgdir>
    </imgdir>
    <imgdir name="1003">
        <imgdir name="0">
            <imgdir name="item">
                <imgdir name="0">
                    <int name="id" value="5555555"/>
                    <int name="count" value="1"/>
                </imgdir>
            </imgdir>
        </imgdir>
    </imgdir>
    <imgdir name="1004">
        <imgdir name="1">
            <imgdir name="mob">
                <imgdir name="0">
                    <int name="id" value="4444444"/>
                    <int name="count" value="3"/>
                </imgdir>
            </imgdir>
        </imgdir>
    </imgdir>
</imgdir>
""")
        self._write_file("Quest.wz/Act.img.xml", """
<imgdir name="Act.img">
    <imgdir name="1000">
        <imgdir name="1">
            <int name="exp" value="100"/>
            <int name="money" value="500"/>
            <int name="pop" value="1"/>
            <imgdir name="item">
                <imgdir name="0">
                    <int name="id" value="1302000"/>
                    <int name="count" value="1"/>
                </imgdir>
            </imgdir>
        </imgdir>
    </imgdir>
    <imgdir name="1005">
        <imgdir name="1">
            <imgdir name="item">
                <imgdir name="0">
                    <int name="id" value="3333333"/>
                    <int name="count" value="1"/>
                </imgdir>
            </imgdir>
        </imgdir>
    </imgdir>
</imgdir>
""")

        # DB TSV tables
        drop_data_content = """dropperid\titemid\tminimum_quantity\tmaximum_quantity\tquestid\tchance
100100\t1302000\t1\t1\t0\t1000
100100\t2222222\t1\t1\t0\t500
100100\t0\t10\t20\t0\t400000
"""
        with open(os.path.join(self.db_dir, "drop_data.tsv"), "w", encoding="utf-8") as f:
            f.write(drop_data_content)

        reactordrops_content = """reactorid\titemid\tchance\tquestid
100\t2000000\t100\t-1
100\t1111111\t50\t-1
"""
        with open(os.path.join(self.db_dir, "reactordrops.tsv"), "w", encoding="utf-8") as f:
            f.write(reactordrops_content)

        shops_content = """shopid\tnpcid
1\t1012000
"""
        with open(os.path.join(self.db_dir, "shops.tsv"), "w", encoding="utf-8") as f:
            f.write(shops_content)

        shopitems_content = """shopitemid\tshopid\titemid\tprice\tpitch\tposition
1\t1\t2000000\t25\t0\t1
2\t1\t9999998\t100\t0\t2
"""
        with open(os.path.join(self.db_dir, "shopitems.tsv"), "w", encoding="utf-8") as f:
            f.write(shopitems_content)

    def test_run_build_and_relations(self):
        dataset, audit = run_build(
            wz_dir=self.wz_dir,
            db_dir=self.db_dir,
            out_dir=self.out_dir,
            report_path=self.report_path,
            workers=1,
        )

        items = dataset["items"]
        mobs = dataset["mobs"]
        maps = dataset["maps"]
        npcs = dataset["npcs"]
        quests = dataset["quests"]

        # 1. Item checks
        sword = items["1302000"]
        self.assertEqual(sword["name"], "Sword")
        self.assertEqual(sword["type"], "Weapon")
        self.assertEqual(sword["stats"]["incPAD"], 17)
        self.assertEqual(len(sword["drops"]), 1)
        self.assertEqual(sword["drops"][0]["mob_id"], 100100)
        self.assertEqual(len(sword["quests_reward"]), 1)
        self.assertEqual(sword["quests_reward"][0]["quest_id"], 1000)

        potion = items["2000000"]
        self.assertEqual(potion["name"], "Red Potion")
        self.assertEqual(len(potion["shops"]), 1)
        self.assertEqual(potion["shops"][0]["npc_id"], 1012000)
        self.assertEqual(len(potion["quests_require"]), 1)
        self.assertEqual(potion["quests_require"][0]["quest_id"], 1000)

        # Pet item
        pet = items["5000000"]
        self.assertEqual(pet["type"], "Pet")
        self.assertEqual(pet["stats"]["hungry"], 2)

        # 2. Mob checks
        snail = mobs["100100"]
        self.assertEqual(snail["name"], "Snail")
        self.assertEqual(snail["level"], 1)
        self.assertEqual(snail["hp"], 8)
        self.assertEqual(len(snail["maps"]), 1)
        self.assertEqual(snail["maps"][0]["map_id"], 100000000)
        self.assertEqual(snail["maps"][0]["count"], 2)

        # Linked mob stats resolution
        blue_snail = mobs["100101"]
        self.assertEqual(blue_snail["name"], "Blue Snail")
        self.assertEqual(blue_snail["level"], 1)
        self.assertEqual(blue_snail["hp"], 8)
        self.assertEqual(blue_snail["exp"], 3)

        # 3. Map checks
        henesys = maps["100000000"]
        self.assertEqual(henesys["name"], "Henesys")
        self.assertEqual(henesys["street"], "Victoria Road")
        self.assertEqual(len(henesys["mobs"]), 2)  # Snail (count 2), missing mob (count 1)
        self.assertEqual(len(henesys["npcs"]), 2)  # Regular Cab (count 1), missing npc (count 1)
        self.assertIn(100000001, henesys["targets"])
        self.assertIn(999999900, henesys["targets"])

        # 4. NPC checks
        cab = npcs["1012000"]
        self.assertEqual(cab["name"], "Regular Cab")
        self.assertEqual(len(cab["maps"]), 1)
        self.assertEqual(cab["maps"][0]["map_id"], 100000000)
        self.assertEqual(len(cab["shop_items"]), 2)
        self.assertEqual(len(cab["quests_start"]), 1)
        self.assertEqual(cab["quests_start"][0]["quest_id"], 1000)

        athena = npcs["1012001"]
        self.assertEqual(len(athena["quests_end"]), 1)
        self.assertEqual(athena["quests_end"][0]["quest_id"], 1000)

        # 5. Quest checks
        q1000 = quests["1000"]
        self.assertEqual(q1000["name"], "Valid Quest")
        self.assertEqual(q1000["start_npc"]["id"], 1012000)
        self.assertEqual(q1000["end_npc"]["id"], 1012001)
        self.assertEqual(q1000["rewards"]["exp"], 100)
        self.assertEqual(q1000["rewards"]["meso"], 500)
        self.assertEqual(q1000["rewards"]["fame"], 1)
        self.assertEqual(len(q1000["requirements"]["items"]), 1)
        self.assertEqual(q1000["requirements"]["items"][0]["item_id"], 2000000)
        self.assertEqual(len(q1000["requirements"]["mobs"]), 1)
        self.assertEqual(q1000["requirements"]["mobs"][0]["mob_id"], 100100)

    def test_broken_reference_detection(self):
        dataset, audit = run_build(
            wz_dir=self.wz_dir,
            db_dir=self.db_dir,
            out_dir=self.out_dir,
            report_path=self.report_path,
            workers=1,
        )

        counts = audit["counts"]
        details = audit["details"]

        # Category 1: Items with no name
        # 1000000 (Cap) has no name in String.wz
        self.assertEqual(counts["items_no_name"], 1)
        self.assertEqual(details["items_no_name"][0]["item_id"], 1000000)

        # Category 2: Drops / rewards / shop missing items
        # drop_data item 2222222, reactordrops item 1111111, shopitems item 9999998, quest 1005 item 3333333
        self.assertEqual(counts["drops_missing_items"], 4)
        missing_item_ids = {d["item_id"] for d in details["drops_missing_items"]}
        self.assertEqual(missing_item_ids, {2222222, 1111111, 9999998, 3333333})

        # Category 3: Quests with missing references
        # quest 1001 (npc 7777777), quest 1002 (map 6666666), quest 1003 (item 5555555), quest 1004 (mob 4444444)
        self.assertEqual(counts["quests_missing_references"], 4)
        q_refs = {(q["quest_id"], q["ref_type"], q["missing_id"]) for q in details["quests_missing_references"]}
        expected_q_refs = {
            (1001, "npc", 7777777),
            (1002, "map", 6666666),
            (1003, "item", 5555555),
            (1004, "mob", 4444444),
        }
        self.assertEqual(q_refs, expected_q_refs)

        # Category 4: Portals leading to missing maps
        # map 100000000 has portal broken01 targeting 999999900
        self.assertEqual(counts["portals_missing_maps"], 1)
        self.assertEqual(details["portals_missing_maps"][0]["target_map_id"], 999999900)

        # Category 5: Mobs/NPCs placed on maps that don't exist
        # map 100000000 places mob 9999999 and npc 8888888
        self.assertEqual(counts["missing_life_placed"], 2)
        life_refs = {(l["map_id"], l["life_type"], l["life_id"]) for l in details["missing_life_placed"]}
        self.assertEqual(life_refs, {(100000000, "mob", 9999999), (100000000, "npc", 8888888)})

        # Report markdown file was created and contains summary counts
        self.assertTrue(os.path.isfile(self.report_path))
        with open(self.report_path, "r", encoding="utf-8") as f:
            content = f.read()
        self.assertIn("SlumsMS Hidden Street Data Sweep Report", content)
        self.assertIn("Items with no name", content)
        self.assertIn("Portals leading to missing maps", content)
        self.assertIn("999999900", content)

    def test_exported_json_files(self):
        run_build(
            wz_dir=self.wz_dir,
            db_dir=self.db_dir,
            out_dir=self.out_dir,
            report_path=self.report_path,
            workers=1,
        )

        for name in ("items", "mobs", "maps", "npcs", "quests", "summary"):
            p = os.path.join(self.out_dir, f"{name}.json")
            self.assertTrue(os.path.isfile(p), f"Missing {p}")

        import json
        with open(os.path.join(self.out_dir, "summary.json"), "r", encoding="utf-8") as f:
            summary = json.load(f)
        self.assertIn("total_items", summary)
        self.assertIn("total_mobs", summary)
        self.assertIn("total_maps", summary)
        self.assertIn("total_npcs", summary)
        self.assertIn("total_quests", summary)

    def test_cli_invocation(self):
        import subprocess
        cmd = [
            "python3",
            "tools/hiddenstreet/build_data.py",
            "--wz", self.wz_dir,
            "--db-dir", self.db_dir,
            "--out-dir", self.out_dir,
            "--report", self.report_path,
            "--workers", "1",
        ]
        res = subprocess.run(cmd, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, f"CLI error: {res.stderr}")
        self.assertTrue(os.path.isfile(self.report_path))


if __name__ == "__main__":
    unittest.main()
