#!/usr/bin/env python3
"""Tests for tools/ops/script-refs (P1-C1a). Fixture WZ + scripts in a temp dir, no server needed.

    python3 -m unittest tools/ops/test_script_refs.py -v
"""
import importlib.machinery, importlib.util, os, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
_l = importlib.machinery.SourceFileLoader("script_refs", os.path.join(HERE, "script-refs"))
sr = importlib.util.module_from_spec(importlib.util.spec_from_loader("script_refs", _l))
_l.exec_module(sr)

MAP = """<?xml version="1.0"?><imgdir name="%(id)d.img"><imgdir name="info">
<string name="onUserEnter" value="%(enter)s"/><string name="onFirstUserEnter" value=""/></imgdir>
<imgdir name="portal"><imgdir name="0"><string name="pn" value="p0"/><string name="script" value="%(portal)s"/></imgdir></imgdir>
<imgdir name="life"><imgdir name="0"><string name="type" value="n"/><string name="id" value="9000"/></imgdir></imgdir>
<imgdir name="reactor"><imgdir name="0"><string name="id" value="2000"/><string name="name" value="r"/></imgdir></imgdir>
</imgdir>"""
NPC = '<imgdir name="9000.img"><imgdir name="info"><imgdir name="script"><imgdir name="0"><string name="script" value="%s"/></imgdir></imgdir></imgdir></imgdir>'


def put(path, text):
    with open(path, "w") as f:
        f.write(text)


def build(root, enter, portal, npc, have):
    wz, sc = os.path.join(root, "wz"), os.path.join(root, "scripts")
    os.makedirs(os.path.join(wz, "Map.wz", "Map", "Map1"))
    os.makedirs(os.path.join(wz, "Npc.wz"))
    os.makedirs(sc)
    put(os.path.join(wz, "Map.wz", "Map", "Map1", "100000000.img.xml"),
        MAP % {"id": 100000000, "enter": enter, "portal": portal})
    put(os.path.join(wz, "Npc.wz", "9000.img.xml"), NPC % npc)
    for rel in have:
        os.makedirs(os.path.dirname(os.path.join(sc, rel)), exist_ok=True)
        put(os.path.join(sc, rel), "//")
    return wz, sc


class T(unittest.TestCase):
    def test_all_present_is_clean(self):
        with tempfile.TemporaryDirectory() as d:
            wz, sc = build(d, "enterA", "portB", "npcC",
                           ["map/onUserEnter/enterA.js", "portal/portB.js", "npc/9000.js"])
            fatal, info = sr.scan(wz, sc)
            self.assertEqual(fatal, {})
            self.assertEqual(len(info), 1)  # reactor 2000 has no script: informational only

    def test_each_kind_of_missing_script_is_fatal(self):
        with tempfile.TemporaryDirectory() as d:
            wz, sc = build(d, "enterA", "portB", "npcC", [])
            fatal, info = sr.scan(wz, sc)
            self.assertEqual(set(fatal), {"map/onUserEnter\tenterA", "portal\tportB"})
            self.assertEqual(len(info), 2)  # reactor 2000, and NPC 9000 (WZ script, no 9000.js: could be a shop)

    def test_blank_script_names_are_not_references(self):
        with tempfile.TemporaryDirectory() as d:
            wz, sc = build(d, "", "", "", [])
            self.assertEqual(sr.scan(wz, sc)[0], {})

    def test_baseline_gate(self):
        with tempfile.TemporaryDirectory() as d:
            wz, sc = build(d, "enterA", "portB", "", [])
            base = os.path.join(d, "base")
            self.assertEqual(sr.main.__module__, "script_refs")
            import sys
            argv = sys.argv
            try:
                sys.argv = ["x", "--wz", wz, "--scripts", sc, "--baseline", base]
                self.assertEqual(sr.main(), 1)                      # empty baseline: both are new
                sys.argv += ["--write-baseline"]
                self.assertEqual(sr.main(), 0)
                sys.argv = ["x", "--wz", wz, "--scripts", sc, "--baseline", base]
                self.assertEqual(sr.main(), 0)                      # now known
                os.makedirs(os.path.join(sc, "portal"), exist_ok=True)
                put(os.path.join(sc, "portal", "portB.js"), "//")
                self.assertEqual(sr.main(), 0)                      # fixed entries do not fail
                os.remove(os.path.join(sc, "portal", "portB.js"))
                put(base, "map/onUserEnter\tenterA\n")
                self.assertEqual(sr.main(), 1)                      # portB is new against this baseline
            finally:
                sys.argv = argv


if __name__ == "__main__":
    unittest.main()
