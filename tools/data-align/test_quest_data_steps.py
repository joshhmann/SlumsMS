#!/usr/bin/env python3
"""The 1.5.2 quest deploy steps (zipangu-quest-dates.tsv, quest-start-npc.tsv) against the server's real Quest.wz.

    python3 -m unittest discover -s tools/data-align -p 'test_*.py'

Needs the server WZ (./wz or $WZ); skips without it, like the Java WzTestData tests. Each test applies a TSV to a
temp copy of Check.img.xml: every Mushroom Shrine quest 4300-4309 can start and complete today, quest 1205 starts at
an NPC that exists, and nothing else in the file changed.
"""
import datetime
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
WZ = os.environ.get('WZ', os.path.join(REPO, 'wz'))
CHECK = os.path.join(WZ, 'Quest.wz', 'Check.img.xml')
TSV = os.path.join(HERE, 'zipangu-quest-dates.tsv')
QUESTS = range(4300, 4310)


def read(path):
    with open(path, encoding='utf-8', newline='') as f:
        return f.read()


def closed_windows(path, now):
    """(quest, side) pairs of 4300-4309 whose `end` date (yyyyMMddHH) is already past."""
    root = ET.parse(path).getroot()
    out = []
    for q in QUESTS:
        node = root.find(f"imgdir[@name='{q}']")
        if node is None:
            continue
        for side in node:
            end = side.find("*[@name='end']")  # a string node in MapleRoot's data
            if end is not None and int(end.get('value')) < now:
                out.append((q, side.get('name')))
    return out


@unittest.skipUnless(os.path.isfile(CHECK), 'needs the server wz/Quest.wz, not in this checkout')
class ZipanguQuestDates(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.tmp, 'Quest.wz'))
        self.copy = os.path.join(self.tmp, 'Quest.wz', 'Check.img.xml')
        shutil.copy2(CHECK, self.copy)
        self.now = int(datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d%H'))

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def run_tool(self, *extra):
        return subprocess.run([sys.executable, os.path.join(HERE, 'wz_set_values.py'), '--wz', self.tmp,
                               '--tsv', TSV, '--column', 'new_value', *extra],
                              capture_output=True, text=True)

    def test_the_server_data_has_the_year_2000_dates_or_the_step_already_ran(self):
        # anything else means the WZ moved on (re-extracted): re-check the TSV against it
        closed = closed_windows(self.copy, self.now)
        if not closed:
            r = self.run_tool()
            self.assertIn('dry run: 0 value(s) in 0 file(s), 0 skipped', r.stdout, 'applied, so a re-run is a no-op')
            return
        self.assertEqual(9, len(closed))

    def test_the_step_opens_every_mushroom_shrine_quest_and_touches_nothing_else(self):
        if not closed_windows(self.copy, self.now):
            self.skipTest('this WZ already has the step applied')
        before = read(self.copy)
        r = self.run_tool('--apply')
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn('applied: 9 value(s) in 1 file(s), 0 skipped', r.stdout)
        self.assertEqual([], closed_windows(self.copy, self.now))
        after = read(self.copy)
        self.assertEqual(len(before), len(after), 'only same-length date values may change')
        diffs = sum(1 for a, b in zip(before, after) if a != b)
        self.assertLessEqual(diffs, 9 * 10)

    def test_a_second_run_changes_nothing(self):
        self.assertEqual(0, self.run_tool('--apply').returncode)
        r = self.run_tool()
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn('dry run: 0 value(s) in 0 file(s), 0 skipped', r.stdout)


@unittest.skipUnless(os.path.isfile(CHECK), 'needs the server wz/Quest.wz, not in this checkout')
class RomeoAndJulietStartNpc(unittest.TestCase):
    NPC_TSV = os.path.join(HERE, 'quest-start-npc.tsv')

    def start_npc(self, path):
        node = ET.parse(path).getroot().find("imgdir[@name='1205']/imgdir[@name='0']/int[@name='npc']")
        return node.get('value')

    def test_1205_starts_at_cosmics_npc_which_exists(self):
        tmp = tempfile.mkdtemp()
        try:
            os.makedirs(os.path.join(tmp, 'Quest.wz'))
            copy = os.path.join(tmp, 'Quest.wz', 'Check.img.xml')
            shutil.copy2(CHECK, copy)
            before = self.start_npc(copy)
            self.assertIn(before, ('9212000', '2112018'), 'the WZ moved on: re-check quest-start-npc.tsv')
            self.assertFalse(os.path.exists(os.path.join(WZ, 'Npc.wz', '9212000.img.xml')), 'MapleRoot\'s NPC is missing')
            r = subprocess.run([sys.executable, os.path.join(HERE, 'wz_set_values.py'), '--wz', tmp, '--tsv',
                                self.NPC_TSV, '--column', 'new_value', '--apply'], capture_output=True, text=True)
            self.assertEqual(0, r.returncode, r.stdout + r.stderr)
            self.assertEqual('2112018', self.start_npc(copy))
            self.assertTrue(os.path.isfile(os.path.join(WZ, 'Npc.wz', '2112018.img.xml')))
            self.assertEqual(len(read(CHECK)), len(read(copy)), 'same-length id swap only')
        finally:
            shutil.rmtree(tmp, ignore_errors=True)


if __name__ == '__main__':
    unittest.main()
