#!/usr/bin/env python3
"""add_slums_chan.py on a small fake WZ: dry run writes nothing, apply adds both edits (with a backup), a re-run is a no-op.

    python3 -m unittest discover -s tools/data-align -p 'test_slums_chan.py'
"""
import datetime
import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, 'add_slums_chan.py')
STRINGS = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n<imgdir name="Npc.img">\n'
           '<imgdir name="2000">\n<string name="name" value="Roger"/>\n</imgdir>\n</imgdir>')


def run(wz, *extra):
    return subprocess.run([sys.executable, TOOL, '--wz', wz, *extra], capture_output=True, text=True, check=True).stdout


class SlumsChanTool(unittest.TestCase):
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
        self.assertFalse(os.path.exists(os.path.join(self.wz, 'Npc.wz', '9990000.img.xml')))
        with open(self.strings, encoding='utf-8') as f:
            self.assertEqual(STRINGS, f.read())

    def test_apply_adds_both_backs_up_and_rerun_changes_nothing(self):
        run(self.wz, '--apply')
        npc = ET.parse(os.path.join(self.wz, 'Npc.wz', '9990000.img.xml')).getroot()
        self.assertEqual('9990000.img', npc.get('name'))
        root = ET.parse(self.strings).getroot()
        entry = root.find("imgdir[@name='9990000']")
        self.assertEqual('Slums-chan', entry.find("string[@name='name']").get('value'))
        self.assertEqual('SlumsMS Guide', entry.find("string[@name='func']").get('value'))
        self.assertIsNotNone(root.find("imgdir[@name='2000']"), 'the rest is kept')
        backups = [d for d in os.listdir(self.tmp.name) if d.startswith('wz-backup-')]
        self.assertEqual(1, len(backups))
        with open(os.path.join(self.tmp.name, backups[0], 'String.wz', 'Npc.img.xml'), encoding='utf-8') as f:
            self.assertEqual(STRINGS, f.read())
        self.assertIn('nothing to do', run(self.wz, '--apply'))
        self.assertEqual(1, len([d for d in os.listdir(self.tmp.name) if d.startswith('wz-backup-')]))

    def test_back_to_back_with_the_penguin_tool_keeps_the_pristine_backup(self):
        run(self.wz, '--apply')
        subprocess.run([sys.executable, os.path.join(HERE, 'add_lazy_penguins.py'), '--wz', self.wz, '--apply'],
                       capture_output=True, text=True, check=True)
        backups = sorted(d for d in os.listdir(self.tmp.name) if d.startswith('wz-backup-'))
        self.assertEqual(2, len(backups), backups)
        mine = [d for d in backups if '-slums-chan-' in d]
        self.assertEqual(1, len(mine), backups)
        with open(os.path.join(self.tmp.name, mine[0], 'String.wz', 'Npc.img.xml'), encoding='utf-8') as f:
            self.assertEqual(STRINGS, f.read(), 'the first backup is the untouched file')

    def test_backup_folder_is_never_reused(self):
        spec = importlib.util.spec_from_file_location('add_slums_chan', TOOL)
        tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tool)
        now = datetime.datetime(2026, 10, 28, 12, 0, 0, 123456, tzinfo=datetime.timezone.utc)
        path = tool.new_backup_dir(self.tmp.name, 'slums-chan', now=now, pid=42)
        self.assertEqual('wz-backup-20261028T120000.123456Z-slums-chan-42', os.path.basename(path))
        with self.assertRaises(FileExistsError):
            tool.new_backup_dir(self.tmp.name, 'slums-chan', now=now, pid=42)


if __name__ == '__main__':
    unittest.main()
