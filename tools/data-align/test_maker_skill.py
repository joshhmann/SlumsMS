#!/usr/bin/env python3
"""add_maker_skill.py on a small fake Skill.wz: dry run writes nothing, apply adds exactly the four Maker nodes (with a
backup), a re-run is a no-op, and the rest of each file is untouched.

    python3 -m unittest discover -s tools/data-align -p 'test_maker_skill.py'
"""
import os
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, 'add_maker_skill.py')
HEAD = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
# One tag per line, like the TEST/live dump; 000 and 2000 have a skill after where Maker goes, 1000 has none.
FILES = {
    '000.img.xml': HEAD + '<imgdir name="000.img">\n<imgdir name="info">\n</imgdir>\n<imgdir name="skill">\n'
                   '<imgdir name="0001003">\n<imgdir name="level">\n<imgdir name="1">\n<int name="x" value="1"/>\n'
                   '</imgdir>\n</imgdir>\n</imgdir>\n<imgdir name="0001050">\n<int name="x" value="2"/>\n</imgdir>\n'
                   '</imgdir>\n</imgdir>',
    '1000.img.xml': HEAD + '<imgdir name="1000.img">\n<imgdir name="skill">\n<imgdir name="10001000">\n'
                    '<int name="x" value="1"/>\n</imgdir>\n</imgdir>\n</imgdir>',
    # one line, like the stock Cosmic dump
    '2000.img.xml': HEAD.strip() + '<imgdir name="2000.img"><imgdir name="skill"><imgdir name="20001000">'
                    '<int name="x" value="1"/></imgdir><imgdir name="20001050"><int name="x" value="2"/></imgdir>'
                    '</imgdir></imgdir>',
}
WANT = {'000.img.xml': '0001007', '1000.img.xml': '10001007', '2000.img.xml': '20001007', '2001.img.xml': '20011007'}


def run(wz, *extra):
    return subprocess.run([sys.executable, '-I', TOOL, '--wz', wz, *extra], capture_output=True, text=True,
                          check=True).stdout


def skills(path):
    root = ET.parse(path).getroot()
    return [d.get('name') for d in root.find("imgdir[@name='skill']").findall('imgdir')]


class MakerSkillTool(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.wz = os.path.join(self.tmp.name, 'wz')
        self.skill = os.path.join(self.wz, 'Skill.wz')
        os.makedirs(self.skill)
        for name, text in FILES.items():
            with open(os.path.join(self.skill, name), 'w', encoding='utf-8', newline='') as f:
                f.write(text)

    def tearDown(self):
        self.tmp.cleanup()

    def read(self, name):
        with open(os.path.join(self.skill, name), encoding='utf-8', newline='') as f:
            return f.read()

    def backups(self):
        return [d for d in os.listdir(self.tmp.name) if d.startswith('wz-backup-')]

    def test_dry_run_writes_nothing(self):
        out = run(self.wz)
        self.assertIn('[dry run]', out)
        for name, text in FILES.items():
            self.assertEqual(text, self.read(name))
        self.assertFalse(os.path.exists(os.path.join(self.skill, '2001.img.xml')))
        self.assertEqual([], self.backups())

    def test_apply_inserts_exactly_the_four_maker_nodes(self):
        run(self.wz, '--apply')
        self.assertEqual(['0001003', '0001007', '0001050'], skills(os.path.join(self.skill, '000.img.xml')))
        self.assertEqual(['10001000', '10001007'], skills(os.path.join(self.skill, '1000.img.xml')))
        self.assertEqual(['20001000', '20001007', '20001050'], skills(os.path.join(self.skill, '2000.img.xml')))
        self.assertEqual(['20011007'], skills(os.path.join(self.skill, '2001.img.xml')))
        for name, sid in WANT.items():
            root = ET.parse(os.path.join(self.skill, name)).getroot()
            node = root.find("imgdir[@name='skill']/imgdir[@name='%s']" % sid)
            self.assertEqual(['1', '2', '3'], [d.get('name') for d in node.find("imgdir[@name='level']")],
                             'master level 3: levels 1-3')
            self.assertEqual('1', node.find("int[@name='invisible']").get('value'))
            self.assertEqual(['h1', 'h2', 'h3'],
                             [d.find("string[@name='hs']").get('value') for d in node.find("imgdir[@name='level']")])
        # the rest of each file is byte for byte what it was, the inserted text removed
        for name, text in FILES.items():
            after = self.read(name)
            self.assertEqual(len(text.splitlines()) > 1, '>\n<' in after, 'keeps the file layout')
            node = open(os.path.join(HERE, 'maker-skill', '%s-%s.xml' % (name[:-4], WANT[name])),
                        encoding='utf-8').read().strip()
            if '>\n<' in text:
                node = node.replace('><', '>\n<') + '\n'
            self.assertEqual(text, after.replace(node, '', 1))
        # one backup folder holding the three untouched files (2001 is new: nothing to back up)
        backups = self.backups()
        self.assertEqual(1, len(backups))
        for name, text in FILES.items():
            with open(os.path.join(self.tmp.name, backups[0], 'Skill.wz', name), encoding='utf-8', newline='') as f:
                self.assertEqual(text, f.read())

    def test_rerun_is_idempotent(self):
        run(self.wz, '--apply')
        before = {n: self.read(n) for n in WANT}
        self.assertIn('nothing to do', run(self.wz, '--apply'))
        self.assertEqual(before, {n: self.read(n) for n in WANT})
        self.assertEqual(1, len(self.backups()))

    def test_a_file_that_already_has_maker_is_left_alone(self):
        run(self.wz, '--apply')
        os.remove(os.path.join(self.skill, '2001.img.xml'))
        out = run(self.wz, '--apply')
        self.assertIn('create Skill.wz/2001.img.xml', out)
        self.assertNotIn('000.img.xml', out)
        self.assertEqual(1, skills(os.path.join(self.skill, '000.img.xml')).count('0001007'))

    def test_shipped_nodes_are_the_stock_ones(self):
        """The three stock nodes are the Cosmic dump's; Evan's is Aran's under its own id."""
        def node(name, sid):
            with open(os.path.join(HERE, 'maker-skill', '%s-%s.xml' % (name, sid)), encoding='utf-8') as f:
                return f.read().strip()
        aran = node('2000.img', '20001007')
        self.assertEqual(aran.replace('"20001007"', '"20011007"', 1), node('2001.img', '20011007'))
        for name, sid in (('000.img', '0001007'), ('1000.img', '10001007')):
            self.assertEqual(aran.replace('"20001007"', '"%s"' % sid, 1), node(name, sid),
                             'the stock Maker nodes differ only in their id')


if __name__ == '__main__':
    unittest.main()
