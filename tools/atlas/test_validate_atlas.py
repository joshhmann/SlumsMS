"""Tests for validate_atlas.py: a tiny valid atlas in a tempdir, then one mutation per rule."""
import contextlib
import copy
import io
import json
import os
import tempfile
import unittest

import validate_atlas as V

META = {"schema_version": "1.0.0", "release": "2026-11-01", "server_commit": "abc123", "wz_sha": "0" * 64,
        "built_at": "2026-11-01T10:00:00Z",
        "counts": {"quests": 2, "npcs": 1, "items": 1, "mobs": 1, "maps": 1, "scripts": 1, "pq": 1, "bosses": 1}}

QUESTS = {
    "1000": {
        "id": 1000, "name": "Test Quest", "reach": "player",
        "start_npc": {"k": "npc", "id": 2100},
        "requirements": {"min_level": 5, "mobs": [{"ref": {"k": "mob", "id": 100100}, "count": 3}]},
        "rewards": {"items": [{"ref": {"k": "item", "id": 4000000}, "count": 1}],
                    "next_quest": {"k": "quest", "id": 1001}},
        "text": {"start": ["Hello there."], "end": ["Done."]},
        "src": [{"kind": "wz", "path": "Quest.wz/Check.img.xml", "line": 2}],
    },
    "1001": {
        "id": 1001, "name": "Second Quest", "reach": "player",
        "src": [{"kind": "script", "path": "scripts/quest/1001.js", "line": 1}],
    },
}
NPCS = {
    "2100": {
        "id": 2100, "name": "Sera", "reach": "player",
        "maps": [{"ref": {"k": "map", "id": 10000}, "x": 0, "y": 0}],
        "src": [{"kind": "wz", "path": "Npc.wz/2100.img.xml", "line": 2},
                {"kind": "db-export", "snapshot": "db-2026-11-01", "date": "2026-11-01",
                 "table": "shops", "key": "2100"}],
    },
}
ITEMS = {
    "4000000": {
        "id": 4000000, "name": "Red Potion", "desc": "Heals 50 HP.", "type": "Use", "req_level": 0,
        "reach": "player",
        "src": [{"kind": "wz", "path": "Item.wz/Consume/0200.img.xml", "line": 4}],
    },
}
MOBS = {
    "100100": {
        "id": 100100, "name": "Snail", "level": 1, "hp": 15, "reach": "player",
        "drops": [{"ref": {"k": "item", "id": 4000000}, "chance": 5000, "min": 1, "max": 1}],
        "src": [{"kind": "wz", "path": "Mob.wz/100100.img.xml", "line": 2}],
    },
}
MAPS = {
    "10000": {
        "id": 10000, "name": "Mushroom Town", "reach": "player",
        "src": [{"kind": "wz", "path": "Map.wz/Map/Map1/10000.img.xml", "line": None}],
    },
}
SCRIPTS = {
    "quest/1001": {
        "id": "quest/1001", "name": "Quest 1001 script", "reach": "player",
        "path": "scripts/quest/1001.js", "kind": "quest", "lines": 10,
        "src": [{"kind": "script", "path": "scripts/quest/1001.js", "line": 1}],
    },
}
PQ = {
    "zakumpq": {
        "id": "zakumpq", "name": "Zakum PQ", "reach": "event",
        "src": [{"kind": "yaml", "path": "data/pq/zakum.yaml", "line": 3}],
    },
}
BOSSES = {
    "zakum": {
        "id": "zakum", "name": "Zakum", "reach": "player",
        "skills": [{"k": "skill", "id": 100}],
        "src": [{"kind": "java", "path": "src/main/java/server/life/RebirthBosses.java", "line": 10}],
    },
}


def base():
    """A minimal atlas that satisfies every rule."""
    return {"meta": copy.deepcopy(META), "quests": copy.deepcopy(QUESTS), "npcs": copy.deepcopy(NPCS),
            "items": copy.deepcopy(ITEMS), "mobs": copy.deepcopy(MOBS), "maps": copy.deepcopy(MAPS),
            "scripts": copy.deepcopy(SCRIPTS), "pq": copy.deepcopy(PQ), "bosses": copy.deepcopy(BOSSES)}


class ValidateAtlasTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()

    def write(self, docs, folder=None):
        folder = folder or self.dir
        os.makedirs(folder, exist_ok=True)
        for name, doc in docs.items():
            with open(os.path.join(folder, name + ".json"), "w", encoding="utf-8") as f:
                json.dump(doc, f, ensure_ascii=False, indent=1)
        return folder

    def broken(self, mutate):
        docs = base()
        mutate(docs)
        return V.validate(self.write(docs))

    def messages(self, problems):
        return " | ".join(f"{p['file']}:{p['id']}:{p['field']}:{p['message']}" for p in problems)

    def assertProblem(self, problems, text):
        self.assertTrue(any(text in self.messages([p]) and not p.get("warning") for p in problems),
                        f"no problem matching {text!r} in {self.messages(problems)}")

    def test_valid_fixture_has_no_problems(self):
        self.assertEqual([], V.validate(self.write(base())))

    def test_meta_missing_field(self):
        problems = self.broken(lambda d: d["meta"].pop("wz_sha"))
        self.assertProblem(problems, "meta.json:None:wz_sha:missing")

    def test_schema_version_semver(self):
        problems = self.broken(lambda d: d["meta"].update(schema_version="1.0"))
        self.assertProblem(problems, "schema_version")

    def test_counts_match_files(self):
        problems = self.broken(lambda d: d["meta"]["counts"].update(items=7))
        self.assertProblem(problems, "counts.items")

    def test_keys_are_sorted(self):
        problems = self.broken(lambda d: d.update(quests=dict(reversed(list(d["quests"].items())))))
        self.assertProblem(problems, "quests.json:1000:None:ids are not sorted")

    def test_row_id_matches_key(self):
        problems = self.broken(lambda d: d["items"]["4000000"].update(id=4000001))
        self.assertProblem(problems, "items.json:4000000:id:")

    def test_name_is_a_string(self):
        problems = self.broken(lambda d: d["items"]["4000000"].update(name=5))
        self.assertProblem(problems, "items.json:4000000:name:")

    def test_reach_is_flagged(self):
        problems = self.broken(lambda d: d["maps"]["10000"].update(reach="all"))
        self.assertProblem(problems, "maps.json:10000:reach:")

    def test_src_is_not_empty(self):
        problems = self.broken(lambda d: d["mobs"]["100100"].update(src=[]))
        self.assertProblem(problems, "mobs.json:100100:src:must be a non-empty list")

    def test_src_kind_is_known(self):
        problems = self.broken(lambda d: d["items"]["4000000"]["src"][0].update(kind="xml"))
        self.assertProblem(problems, "src[0].kind")

    def test_src_line_positive_or_null(self):
        problems = self.broken(lambda d: d["maps"]["10000"]["src"][0].update(line=0))
        self.assertProblem(problems, "maps.json:10000:src[0].line:")

    def test_db_export_source_shape(self):
        problems = self.broken(lambda d: d["npcs"]["2100"]["src"][1].pop("table"))
        self.assertProblem(problems, "npcs.json:2100:src[1].table:")

    def test_ref_kind_is_known(self):
        problems = self.broken(lambda d: d["quests"]["1000"]["rewards"].update(next_quest={"k": "thing", "id": 1}))
        self.assertProblem(problems, "unknown ref kind")

    def test_ref_must_resolve(self):
        problems = self.broken(lambda d: d["quests"]["1000"]["rewards"].update(next_quest={"k": "quest", "id": 999}))
        self.assertProblem(problems, "quests.json:1000:rewards.next_quest:quest 999 does not resolve")

    def test_dangling_allow_list_is_a_warning(self):
        docs = base()
        docs["quests"]["1000"]["rewards"]["next_quest"] = {"k": "quest", "id": 999}
        self.write(docs)
        self.write({"dangling": {"quest": [999]}})
        problems = V.validate(self.dir)
        self.assertEqual([], [p for p in problems if not p.get("warning")])
        self.assertEqual(1, len([p for p in problems if p.get("warning")]))
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = V.main([self.dir])
        self.assertEqual(0, code)
        self.assertIn("1 dangling reference warning(s)", out.getvalue())

    def test_text_has_no_client_codes(self):
        problems = self.broken(lambda d: d["quests"]["1000"]["text"].update(start=["#bHello#k"]))
        self.assertProblem(problems, "quests.json:1000:text.start[0]:client formatting code")

    def test_no_secret_keys_anywhere(self):
        problems = self.broken(lambda d: d["quests"]["1000"].update(owner={"ip": "10.0.0.1"}))
        self.assertProblem(problems, "forbidden key 'ip'")

    def test_no_secret_keys_in_meta(self):
        problems = self.broken(lambda d: d["meta"].update({'db': {"password": "x"}}))
        self.assertProblem(problems, "meta.json:None:db.password:forbidden key 'password'")

    def test_hangul_needs_lang_ko(self):
        problems = self.broken(lambda d: d["items"]["4000000"].update(name="빨간 물약"))
        self.assertProblem(problems, "items.json:4000000:name:Hangul")
        problems = self.broken(lambda d: d["items"]["4000000"].update(name="빨간 물약", lang="ko"))
        self.assertFalse(any("Hangul" in p["message"] for p in problems), self.messages(problems))

    def test_new_nested_field_is_walked(self):
        problems = self.broken(lambda d: d["bosses"]["zakum"].update(extra={"ref": {"k": "npc", "id": 424242}}))
        self.assertProblem(problems, "bosses.json:zakum:extra.ref:npc 424242 does not resolve")

    def test_main_exits_1_and_groups_output(self):
        docs = base()
        docs["items"]["4000000"]["src"] = []
        self.write(docs)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = V.main([self.dir])
        self.assertEqual(1, code)
        self.assertIn("items.json: 4000000  src", out.getvalue())
        self.assertIn("problem(s) in", out.getvalue())

    def test_release_diff(self):
        old = self.write(base(), tempfile.mkdtemp())
        docs = base()
        docs["meta"]["schema_version"] = "2.0.0"
        docs["quests"].pop("1001")
        docs["quests"]["1002"] = {"id": 1002, "name": "Third Quest", "reach": "player",
                                  "src": [{"kind": "wz", "path": "Quest.wz/Check.img.xml", "line": 9}]}
        docs["quests"]["1000"]["name"] = "Renamed Quest"
        docs["quests"]["1000"].pop("reach")
        docs["meta"]["counts"].update(quests=2)
        new = self.write(docs, tempfile.mkdtemp())
        lines = V.release_diff(old, new)
        text = "\n".join(lines)
        self.assertIn("quests: 1 added, 1 removed, 1 changed (2 -> 2 rows)", text)
        self.assertIn("added: 1002", text)
        self.assertIn("removed: 1001", text)
        self.assertIn("changed: 1000", text)
        self.assertIn("MAJOR schema change: every quests row had field(s) reach", text)
        self.assertIn("MAJOR schema change: schema_version 1.0.0 -> 2.0.0", text)


if __name__ == "__main__":
    unittest.main()
