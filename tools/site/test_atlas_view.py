"""The staff Atlas page (sections/atlas.py and the --atlas-dir option): python3 -m unittest discover -s tools/site -p 'test_*.py'

A tiny atlas folder stands in for a real build (a few rows per kind, a dangling reference, an item no source turns up
for, a name that looks like HTML). The real folder (/root/work/atlas-data/) is 50 MB and never goes into a test.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from sections import admin, atlas  # noqa: E402

WEB_CSS = ":root { --bg: #fff; --surface-2: #eee; --surface: #fafafa; --s1: #2a78d6; }\n"
WEB_HTML = ('<html><head><title>SlumsMS Admin</title><link rel="stylesheet" href="style.css"></head>'
            '<body><main id="m">y</main><script src="app.js"></script></body></html>')
MARKUP = '<img src=x onerror=alert(1)>'


def fixture(folder):
    """A small atlas build: the files docs/atlas/SCHEMA.md describes, with the awkward rows the page has to survive."""
    os.makedirs(folder)
    rows = {
        "quests": {
            "1000": {"id": 1000, "name": "A test quest", "reach": "player", "requirements": {"level": 10},
                     "rewards": {"exp": 500, "items": [{"ref": {"k": "item", "id": 2000000}, "count": 2}]},
                     "start_npc": {"k": "npc", "id": 10000}, "end_npc": {"k": "npc", "id": 99999},
                     "text": {"start": ["Come back later."], "end": []},
                     "src": [{"kind": "wz", "path": "Quest.wz/Check.img.xml", "line": 12}]},
            "1001": {"id": 1001, "name": "A quest with no source", "reach": "unobtainable",
                     "reach_note": "no quest start found", "src": [{"kind": "wz", "path": "Quest.wz/QuestInfo.img.xml",
                                                                   "line": None}]},
        },
        "npcs": {
            "10000": {"id": 10000, "name": "Test NPC", "reach": "player", "maps": [{"k": "map", "id": 1}],
                      "shop": [], "quests_start": [{"k": "quest", "id": 1000}],
                      "src": [{"kind": "wz", "path": "Npc.wz/10000.img.xml", "line": 3}]},
        },
        "items": {
            "2010007": {"id": 2010007, "name": MARKUP, "reach": "unobtainable",
                        "reach_note": "no source in the data", "type": "etc",
                        "src": [{"kind": "db-export", "table": "drop_data", "key": 2010007,
                                 "snapshot": "test-1.6.5", "date": "2026-11-07"}]},
            "2000000": {"id": 2000000, "name": "Test Potion", "reach": "player", "price": 25,
                        "shops": [{"ref": {"k": "npc", "id": 10000}, "price": 25}],
                        "src": [{"kind": "wz", "path": "Item.wz/Consume/0200.img.xml", "line": 7}]},
        },
        "mobs": {"100100": {"id": 100100, "name": "Test Snail", "reach": "player", "boss": False, "level": 1,
                            "drops": [{"ref": {"k": "item", "id": 2000000}, "chance": 10}],
                            "src": [{"kind": "wz", "path": "Mob.wz/100100.img.xml", "line": 1}]}},
        "maps": {"1": {"id": 1, "name": "Test Town", "reach": "player", "street": "Test",
                       "npcs": [{"k": "npc", "id": 10000}], "portals": [{"name": "in", "to": 100000000}],
                       "src": [{"kind": "wz", "path": "Map.wz/Map/Map1/000000001.img.xml", "line": 4}]}},
        "scripts": {"npc/Test": {"id": "npc/Test", "path": "npc/Test", "kind": "npc", "lines": 30,
                                 "header": ["# nothing here"], "calls": [{"fn": "addText", "args": ["hi"], "line": 4}],
                                 "mentions": [{"k": "item", "id": 2010007, "line": 9}],
                                 "src": [{"kind": "wz", "path": "Script.npc/Test.py", "line": 1}]}},
        "pq": {},
        "bosses": {"200100": {"id": 200100, "name": "Test Boss", "reach": "event", "level": 50,
                              "src": [{"kind": "wz", "path": "Mob.wz/200100.img.xml", "line": 1}]}},
    }
    for kind, rows_of in rows.items():
        with open(os.path.join(folder, kind + ".json"), "w", encoding="utf-8") as f:
            json.dump(rows_of, f)
    meta = {"release": "9.9.9-test", "schema_version": "1.0.0",
            "server_commit": "abcdef1234567890abcdef1234567890abcdef12", "built_at": "2026-11-07T00:00:00Z",
            "counts": {k: len(v) for k, v in rows.items()}}
    with open(os.path.join(folder, "meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f)
    with open(os.path.join(folder, "dangling.json"), "w", encoding="utf-8") as f:
        json.dump({"item": [9999999], "npc": [99999]}, f)
    with open(os.path.join(folder, "MANIFEST.sha256"), "w", encoding="utf-8") as f:
        f.write("00  meta.json\n")
    return folder


class AtlasTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.src = fixture(os.path.join(self.dir, "atlas"))
        self.out = os.path.join(self.dir, "out")
        self.made = atlas.build(self.out, self.src)

    def tearDown(self):
        shutil.rmtree(self.dir)

    def read(self, rel):
        with open(os.path.join(self.out, rel), encoding="utf-8") as f:
            return f.read()

    def files(self):
        found = []
        for dirpath, _dirs, names in os.walk(self.out):
            for name in names:
                found.append(os.path.relpath(os.path.join(dirpath, name), self.out).replace(os.sep, "/"))
        return sorted(found)

    def search(self):
        return json.loads(self.read("admin/atlas/data/9.9.9-test/search.json"))

    # ── what a build writes ───────────────────────────────────────────────────────────────────────

    def test_every_file_is_under_admin_atlas(self):
        for rel in self.files():
            self.assertTrue(rel.startswith("admin/atlas/"), f"{rel} is outside admin/atlas/")
        for rel in ("admin/atlas/index.html", "admin/atlas/atlas.js", "admin/atlas/atlas.css",
                    "admin/atlas/data/9.9.9-test/search.json", "admin/atlas/data/9.9.9-test/meta.json",
                    "admin/atlas/data/9.9.9-test/items.json", "admin/atlas/data/9.9.9-test/dangling.json"):
            self.assertTrue(os.path.isfile(os.path.join(self.out, rel)), f"{rel} was not written")

    def test_the_data_is_a_copy_of_the_atlas_and_the_manifest_is_not(self):
        data = os.path.join(self.out, "admin", "atlas", "data", "9.9.9-test")
        with open(os.path.join(self.src, "items.json"), "rb") as f, \
                open(os.path.join(data, "items.json"), "rb") as g:
            self.assertEqual(f.read(), g.read(), "copied byte for byte")
        self.assertFalse(os.path.exists(os.path.join(data, "MANIFEST.sha256")), "the manifest is not data")
        self.assertFalse(os.path.exists(os.path.join(data, "unknown.json")))

    def test_the_search_index_holds_every_row_sorted_by_kind_then_id(self):
        index = self.search()
        self.assertIn(["quest", 1000, "A test quest", "player"], index)
        self.assertIn(["item", 2010007, MARKUP, "unobtainable"], index)
        self.assertIn(["script", "npc/Test", "npc/Test", ""], index)   # a script's id is its path
        self.assertIn(["boss", 200100, "Test Boss", "event"], index)
        self.assertEqual(len(index), 9, "two quests, one npc, two items, one mob, one map, one script, one boss")
        kinds = [row[0] for row in index]
        self.assertEqual(kinds, sorted(kinds), "sorted by kind")
        for kind in atlas.KIND_KEY.values():
            ids = [row[1] for row in index if row[0] == kind]
            self.assertEqual(ids, sorted(ids), f"{kind} rows are sorted by id")
        self.assertEqual({"quest", "npc", "item", "mob", "map", "script", "boss"}, {row[0] for row in index})
        self.assertEqual(self.made["rows"], 9)

    def test_the_script_knows_every_kind_the_data_names(self):
        """A reference is {"k": "item"}, dangling.json is keyed the same way and a row lives at #item/1: the script's
        tables have to use those words. When they did not, every reference rendered as plain text with no link and
        the dangling view got no links at all."""
        script = self.read("admin/atlas/atlas.js")
        known = set(re.findall(r"(\w+):", re.search(r"var FILE = \{([^}]*)\}", script).group(1)))
        self.assertEqual(known, set(re.findall(r'"([a-z]+)"', re.search(r"var KINDS = \[([^]]*)\]",
                                                                       script).group(1))))
        refs = set()
        for name in sorted(atlas.KNOWN & {n + ".json" for n in atlas.KINDS}):
            if name in ("meta.json", "dangling.json"):
                continue
            with open(os.path.join(self.src, name), encoding="utf-8") as f:
                stack = [json.load(f)]
            while stack:
                value = stack.pop()
                if isinstance(value, dict):
                    if set(value) == {"k", "id"}:
                        refs.add(value["k"])
                    else:
                        stack.extend(value.values())
                elif isinstance(value, list):
                    stack.extend(value)
        with open(os.path.join(self.src, "dangling.json"), encoding="utf-8") as f:
            dangling = set(json.load(f))
        self.assertTrue(refs, "the fixture has references")
        self.assertEqual(set(), refs - known, "a reference kind the script does not know")
        self.assertEqual(set(), dangling - known, "a dangling kind the script does not know")

    def test_the_header_shows_the_meta_and_the_counts(self):
        page = self.read("admin/atlas/index.html")
        self.assertIn("9.9.9-test", page)                       # release
        self.assertIn("1.0.0", page)                            # schema_version
        self.assertIn("abcdef1", page)                          # server_commit, short
        self.assertNotIn("abcdef1234567890", page, "the commit is shown short")
        self.assertIn("2026-11-07T00:00:00Z", page)             # built_at
        for kind, n in (("quests", 2), ("items", 2), ("npcs", 1)):
            self.assertRegex(page, rf"<b>{n}</b> {kind}")
        self.assertIn('data-root="data/9.9.9-test/"', page, "the page reads its data from the release's folder")

    def test_the_page_is_a_staff_tab_in_the_sites_frame(self):
        page = self.read("admin/atlas/index.html")
        self.assertIn('<a class="chip" href="../atlas/" aria-current=page>Atlas</a>', page)
        self.assertIn('<a class="chip" href="../bots">Bots</a>', page, "the tabs lead back up to admin/")
        self.assertIn('href="../../assets/tokens.css"', page, "the site's own tokens, light and dark")
        self.assertIn("noindex", page)
        self.assertIn('<script src="atlas.js" defer>', page)
        self.assertIn('<link rel="stylesheet" href="atlas.css">', page)
        self.assertIn('id="dangling"', page, "the dangling view is a place the page can link to")

    def test_nothing_is_loaded_from_another_site(self):
        for rel in ("admin/atlas/index.html", "admin/atlas/atlas.js", "admin/atlas/atlas.css"):
            text = self.read(rel)
            self.assertNotIn("http://", text, f"{rel} loads something over http")
            self.assertNotIn("https://", text, f"{rel} loads something over https")
            self.assertNotIn("//cdn", text)

    # ── the wording rule and the trap ─────────────────────────────────────────────────────────────

    def test_the_unobtainable_reach_is_worded_as_no_known_source(self):
        script = self.read("admin/atlas/atlas.js")
        self.assertIn('unobtainable: "No known source"', script)
        self.assertIn("no drop, shop, quest reward, script or cash-shop source was found in the data", script)
        for rel in ("admin/atlas/index.html", "admin/atlas/atlas.js", "admin/atlas/atlas.css"):
            self.assertNotIn("impossible", self.read(rel).lower(), f"{rel} says impossible")

    def test_data_never_goes_in_as_html(self):
        script = self.read("admin/atlas/atlas.js")
        self.assertNotIn("innerHTML", script, "everything the data says goes in with textContent")
        self.assertIn("textContent", script)
        self.assertNotIn("onerror", script.lower(), "nothing from the data can become an attribute")

    def test_a_row_whose_name_is_markup_stays_text(self):
        page, script = self.read("admin/atlas/index.html"), self.read("admin/atlas/atlas.js")
        self.assertNotIn(MARKUP, page, "a name is never written into the page's HTML")
        index = self.search()
        self.assertIn(MARKUP, [row[2] for row in index], "the index keeps the name as data")
        for tainted in (MARKUP, "<img", "onerror"):
            self.assertNotIn(tainted, script)

    def test_a_reference_is_a_link_and_a_missing_row_says_dangling(self):
        script = self.read("admin/atlas/atlas.js")
        self.assertIn('"#" + kind + "/" + encodeURIComponent(String(ref.id))', script)
        self.assertIn("missing (dangling)", script)
        self.assertIn("Referenced by", script)
        self.assertIn("dangling.json", script)
        self.assertIn("line unknown", script)
        self.assertIn("db export: table ", script)

    # ── the awkward builds ───────────────────────────────────────────────────────────────────────

    def test_an_unknown_file_in_the_atlas_folder_refuses_the_build(self):
        with open(os.path.join(self.src, "notes.txt"), "w", encoding="utf-8") as f:
            f.write("not data\n")
        with self.assertRaises(ValueError) as caught:
            atlas.build(os.path.join(self.dir, "out2"), self.src)
        self.assertIn("notes.txt", str(caught.exception))
        self.assertFalse(os.path.exists(os.path.join(self.dir, "out2", "admin", "atlas", "index.html")),
                         "nothing is written when the folder is not an atlas")

    def test_without_an_atlas_the_page_says_so_and_writes_no_data(self):
        out = os.path.join(self.dir, "plain")
        self.assertEqual({"release": None, "rows": 0, "kinds": {}}, atlas.build(out, None))
        with open(os.path.join(out, "admin", "atlas", "index.html"), encoding="utf-8") as f:
            page = f.read()
        self.assertIn("No atlas built for this site build", page)
        self.assertIn("--atlas-dir", page, "the page says how to build one")
        self.assertIn('<a class="chip" href="../atlas/" aria-current=page>Atlas</a>', page)
        self.assertFalse(os.path.exists(os.path.join(out, "admin", "atlas", "data")))
        self.assertEqual(["admin/atlas/index.html"],
                         sorted(os.path.relpath(os.path.join(d, n), out).replace(os.sep, "/")
                                for d, _s, ns in os.walk(out) for n in ns))

    def test_an_empty_folder_is_the_same_as_none(self):
        empty = os.path.join(self.dir, "empty")
        os.makedirs(empty)
        out = os.path.join(self.dir, "out-empty")
        self.assertEqual(0, atlas.build(out, empty)["rows"])
        with open(os.path.join(out, "admin", "atlas", "index.html"), encoding="utf-8") as f:
            self.assertIn("No atlas built for this site build", f.read())

    def test_a_release_name_cannot_leave_the_data_folder(self):
        with open(os.path.join(self.src, "meta.json"), encoding="utf-8") as f:
            meta = json.load(f)
        meta["release"] = "../../escape"
        with open(os.path.join(self.src, "meta.json"), "w", encoding="utf-8") as f:
            json.dump(meta, f)
        out = os.path.join(self.dir, "out-escape")
        atlas.build(out, self.src)
        for dirpath, _dirs, names in os.walk(out):
            for name in names:
                rel = os.path.relpath(os.path.join(dirpath, name), out).replace(os.sep, "/")
                self.assertTrue(rel.startswith("admin/atlas/"), f"{rel} left admin/atlas/")
                self.assertNotIn("..", rel)
        self.assertTrue(os.path.isdir(os.path.join(out, "admin", "atlas", "data", "escape")),
                        "the release name is reduced to a plain folder name")

    # ── the section next to the other staff pages ────────────────────────────────────────────────

    def test_the_atlas_tab_is_on_the_staff_pages(self):
        self.assertIn(("atlas/", "Atlas", "atlas"), admin.ADMIN_TABS)
        self.assertEqual("index", admin.ADMIN_TABS[0][2], "the dashboard still comes first")

    def test_the_staff_build_without_an_atlas_is_unchanged(self):
        web = os.path.join(self.dir, "web")
        os.makedirs(web)
        for name, text in (("style.css", WEB_CSS), ("index.html", WEB_HTML), ("charts.js", ""), ("mapview.js", ""),
                           ("app.js", "")):
            with open(os.path.join(web, name), "w", encoding="utf-8") as f:
                f.write(text)
        plain, with_atlas = os.path.join(self.dir, "admin-plain"), os.path.join(self.dir, "admin-atlas")
        admin.build(plain, web)                      # the two-argument call every existing script and test uses
        admin.build(with_atlas, web, self.src)
        for rel in ("index.html", "bots.html", "invites.html", "reports.html", "app.html", "dashboard.css", "app.js"):
            with open(os.path.join(plain, "admin", rel), "rb") as f:
                before = f.read()
            with open(os.path.join(with_atlas, "admin", rel), "rb") as f:
                self.assertEqual(before, f.read(), f"admin/{rel} changed when an atlas was added")
        with open(os.path.join(with_atlas, "admin", "atlas", "index.html"), encoding="utf-8") as f:
            self.assertIn("9.9.9-test", f.read())
        with open(os.path.join(with_atlas, "admin", "bots.html"), encoding="utf-8") as f:
            self.assertIn('href="atlas/"', f.read(), "the other tabs link to the atlas")

    def test_the_build_script_takes_atlas_dir_and_needs_it_not(self):
        root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        help_text = subprocess.run([sys.executable, os.path.join(root, "tools", "site", "build.py"), "--help"],
                                   capture_output=True, text=True, cwd=root, check=True).stdout
        self.assertIn("--atlas-dir", help_text)
        self.assertIn("--dashboard-web", help_text, "the other options are still there")


@unittest.skipUnless(shutil.which("node"), "node is not installed: the page's search is checked by hand only")
class SearchTest(unittest.TestCase):
    """The search itself, run under node against the index a fixture build makes."""

    def setUp(self):
        self.dir = tempfile.mkdtemp()
        src = fixture(os.path.join(self.dir, "atlas"))
        self.out = os.path.join(self.dir, "out")
        atlas.build(self.out, src)

    def tearDown(self):
        shutil.rmtree(self.dir)

    def run_search(self, q, kind="", reach=""):
        script = os.path.join(self.out, "admin", "atlas", "atlas.js")
        code = (
            "const m = require(process.argv[1]);"
            "const idx = require(process.argv[2]);"
            "const hits = m.searchRows(idx, process.argv[3], process.argv[4], process.argv[5]);"
            "console.log(JSON.stringify(hits));"
        )
        done = subprocess.run(["node", "-e", code, script,
                               os.path.join(self.out, "admin", "atlas", "data", "9.9.9-test", "search.json"),
                               q, kind, reach], capture_output=True, text=True, check=True)
        return json.loads(done.stdout)

    def test_search_rows_by_exact_id_and_by_part_of_a_name(self):
        self.assertEqual([["item", 2010007, MARKUP, "unobtainable"]], self.run_search("2010007"))
        self.assertEqual([1000, 1001], [h[1] for h in self.run_search("QUEST")],
                         "a name matches as a substring, in any case")

    def test_search_filters_by_kind_and_by_reach(self):
        self.assertEqual([["quest", 1001, "A quest with no source", "unobtainable"]],
                         self.run_search("", "quest", "unobtainable"))
        self.assertEqual([], self.run_search("", "mob", "unobtainable"))
        self.assertEqual(2, len(self.run_search("", "item", "")))


if __name__ == "__main__":
    unittest.main()
