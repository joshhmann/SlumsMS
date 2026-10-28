#!/usr/bin/env python3
"""add_lazy_penguins.py on a small fake WZ: dry run writes nothing, apply adds 15 files + 15 names, re-run is a no-op.

    python3 -m unittest discover -s tools/data-align -p 'test_lazy_penguins.py'
"""
import os
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, 'add_lazy_penguins.py')
STRINGS = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<imgdir name="Npc.img">\n'
           '<imgdir name="2000">\n<string name="name" value="Roger"/>\n</imgdir>\n</imgdir>')


def run(wz, *extra):
    return subprocess.run([sys.executable, TOOL, '--wz', wz, *extra], capture_output=True, text=True, check=True).stdout


class LazyPenguinsTool(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.wz = os.path.join(self.tmp.name, 'wz')
        os.makedirs(os.path.join(self.wz, 'Npc.wz'))
        os.makedirs(os.path.join(self.wz, 'String.wz'))
        self.strings = os.path.join(self.wz, 'String.wz', 'Npc.img.xml')
        with open(self.strings, 'w', encoding='utf-8', newline='') as f:
            f.write(STRINGS)

    def tearDown(self):
        self.tmp.cleanup()

    def test_dry_run_writes_nothing(self):
        self.assertIn('[dry run]', run(self.wz))
        self.assertEqual([], os.listdir(os.path.join(self.wz, 'Npc.wz')))
        with open(self.strings, encoding='utf-8') as f:
            self.assertEqual(STRINGS, f.read())

    def test_apply_adds_everything_and_a_rerun_is_a_noop(self):
        run(self.wz, '--apply')
        files = sorted(os.listdir(os.path.join(self.wz, 'Npc.wz')))
        self.assertEqual(['%d.img.xml' % i for i in range(2985, 3000)], files)
        img = ET.parse(os.path.join(self.wz, 'Npc.wz', '2985.img.xml')).getroot()
        self.assertEqual('39', img.find("./imgdir[@name='stand']/canvas[@name='0']").get('width'))
        names = ET.parse(self.strings).getroot()
        for i in range(2985, 3000):
            e = names.find("./imgdir[@name='%d']" % i)
            self.assertEqual('Lazy Penguin', e.find("./string[@name='name']").get('value'))
            self.assertTrue(e.find("./string[@name='func']").get('value'))
        self.assertEqual('Roger', names.find("./imgdir[@name='2000']/string").get('value'))
        self.assertEqual('Christmas', names.find("./imgdir[@name='2985']/string[@name='func']").get('value'))
        before = open(self.strings, encoding='utf-8').read()
        self.assertIn('nothing to do', run(self.wz, '--apply'))
        self.assertEqual(before, open(self.strings, encoding='utf-8').read())


if __name__ == '__main__':
    unittest.main()
