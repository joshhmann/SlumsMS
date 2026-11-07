import unittest

import wzxml

SAMPLE = b"""<?xml version="1.0" encoding="UTF-8"?>
<imgdir name="Check.img">
<imgdir name="1021">
<imgdir name="0">
<int name="npc" value="2000"/>
<string name="startscript" value="q1021s"/>
</imgdir>
<imgdir name="1">
<int name="npc" value="2000"/>
<imgdir name="item">
<imgdir name="0">
<int name="id" value="2010007"/>
</imgdir>
</imgdir>
</imgdir>
</imgdir>
</imgdir>
"""


class WzXmlTest(unittest.TestCase):
    def test_entries_have_the_line_they_start_on(self):
        q = wzxml.parse(SAMPLE)["1021"]
        self.assertEqual(q["line"], 3)
        self.assertEqual(q["dirs"]["0"]["line"], 4)
        self.assertEqual(q["dirs"]["1"]["dirs"]["item"]["dirs"]["0"]["props"]["id"], 2010007)

    def test_props_are_typed(self):
        n = wzxml.parse(SAMPLE)["1021"]["dirs"]["0"]["props"]
        self.assertEqual(n, {"npc": 2000, "startscript": "q1021s"})


if __name__ == "__main__":
    unittest.main()
