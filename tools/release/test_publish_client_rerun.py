"""Offline: publish-client checks the news file before publishing anything, and a rerun reuses an identical layer (b052).

The 1.6.x release step published client layer <v>, then the release manifest failed on the news file ('at most 10
entries'); the rerun aborted on 'layer already published'. These tests run publish-client end to end against a
scratch feed with ssh/scp stubbed to run locally and the launcher repo's real publisher (skipped without node or
the launcher repo), plus the news rules side by side with the publisher's validateManifest.

python3 -m unittest discover -s tools/release -p 'test_*.py'
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
LAUNCHER = Path(os.environ.get('LAUNCHER_REPO', '/root/work/launcher'))
NODE = shutil.which('node')
HAVE_PUBLISHER = bool(NODE) and (LAUNCHER / 'tools/publish.mjs').is_file()

spec = importlib.util.spec_from_file_location('publish_checks', HERE / 'publish_checks.py')
pc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pc)

SSH_STUB = r'''#!/bin/bash
# ssh stub: drop options and the host, run the command here (the "host" is this machine).
while [ $# -gt 0 ]; do case $1 in -o) shift 2;; -*) shift;; *) shift; break;; esac; done
exec bash -c "${*//\/tmp\/slums-client-allow.txt/$FAKE_TMP/slums-client-allow.txt}"
'''
SCP_STUB = r'''#!/bin/bash
args=(); for a in "$@"; do case $a in -*) ;; *) args+=("$a");; esac; done
dst=${args[1]#*:}; cp "${args[0]}" "${dst//\/tmp\/slums-client-allow.txt/$FAKE_TMP/slums-client-allow.txt}"
'''


def news(n):
    return [{'title': f'Item {i}', 'date': '2026-10-30', 'body': 'line one\nline two'} for i in range(n)]


def node_accepts_news(value):
    """The publisher's own verdict: validateManifest from the launcher repo's src/updater.mjs."""
    script = ("import(process.argv[1]).then(({ validateManifest }) => { try { validateManifest({ schemaVersion: 1, "
              "version: 'x', launch: { executable: 'a.exe', args: [] }, files: [{ path: 'a.exe', size: 0, "
              "sha256: '0'.repeat(64), url: 'a.exe' }], news: JSON.parse(process.argv[2]) }); console.log('ok'); } "
              "catch (e) { console.log('refused: ' + e.message); } })")
    out = subprocess.run([NODE, '-e', script, str(LAUNCHER / 'src/updater.mjs'), json.dumps(value)],
                         capture_output=True, text=True, check=True).stdout.strip()
    return out == 'ok', out


class NewsRulesTest(unittest.TestCase):
    CASES = [
        news(10), news(11), [], {'title': 'not a list'}, [None], ['text'], [[]], [{}],
        [{'title': ' '}], [{'title': 'x' * 120}], [{'title': 'x' * 121}], [{'title': 'a\nb'}],
        [{'title': '\U0001F600' * 60}], [{'title': '\U0001F600' * 61}],
        [{'title': 't', 'date': '2026-1-30'}], [{'title': 't', 'date': None}], [{'title': 't', 'date': '2026-10-30\n'}],
        [{'title': 't', 'body': 'x' * 1000}], [{'title': 't', 'body': 'x' * 1001}], [{'title': 't', 'body': 'a\tb'}],
        [{'title': 't', 'body': None}], [{'title': '﻿'}], [{'title': '\x85'}], [{'title': 't', 'extra': 1}],
    ]

    def test_eleven_entries_refused_with_the_publishers_message(self):
        with self.assertRaisesRegex(ValueError, 'at most 10 entries'):
            pc.check_news(news(11))
        pc.check_news(news(10))

    @unittest.skipUnless(HAVE_PUBLISHER, 'needs node and the launcher repo')
    def test_same_verdict_as_the_publisher(self):
        for value in self.CASES:
            with self.subTest(value=repr(value)[:60]):
                ok, why = node_accepts_news(value)
                try:
                    pc.check_news(value)
                    mine = True
                except ValueError:
                    mine = False
                self.assertEqual(mine, ok, why)


@unittest.skipUnless(HAVE_PUBLISHER, 'needs node and the launcher repo')
class PublishClientRerunTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        r = self.root = Path(self.tmp.name)
        for d in ('bin', 'faketmp', 'feed', 'staging', 'reference'):
            (r / d).mkdir()
        pub = r / 'publisher'
        shutil.copytree(LAUNCHER / 'tools', pub / 'tools')
        shutil.copytree(LAUNCHER / 'src', pub / 'src')
        shutil.copy(LAUNCHER / 'package.json', pub / 'package.json')
        for name, body in (('ssh', SSH_STUB), ('scp', SCP_STUB)):
            (r / 'bin' / name).write_text(body)
            (r / 'bin' / name).chmod(0o755)
        self.env = {**os.environ, 'PATH': f"{r / 'bin'}:{os.environ['PATH']}", 'FAKE_TMP': str(r / 'faketmp'),
                    'RELEASE_HOST': 'stub', 'PATCH_DIR': str(r / 'feed'), 'STAGING_DIR': str(r / 'staging'),
                    'PUBLISHER': str(pub), 'LAUNCHER_REPO': str(pub), 'NODE': NODE, 'REFERENCE': str(r / 'reference'),
                    'REF_CACHE': str(r / 'staging/ref.json'), 'LIVE_PATCH_DIR': str(r / 'not-live')}
        self.env.pop('PUBLISH_TEST_FAIL', None)
        base = {'SlumsMS.exe': b'exe', 'Data/Map.wz': b'map'}
        client = {'slums.dll': b'dll 1', 'Data/Custom.wz': b'custom 1'}
        self.tree(r / 'src-base', base)
        self.tree(r / 'src-1.0', client)
        self.tree(r / 'reference', {**base, **client})
        self.node('--layer', 'base', '--source', r / 'src-base', '--version', 'v83')
        self.node('--layer', 'client', '--source', r / 'src-1.0', '--version', '1.0')
        self.node('--release', 'r1', '--use', 'base@v83', '--use', 'client@1.0', '--no-packs')
        self.src = r / 'staging/client-1.1'
        self.tree(self.src, client)
        (r / 'staging/news-bad.json').write_text(json.dumps(news(11)))
        (r / 'staging/news-ok.json').write_text(json.dumps(news(3)))

    @staticmethod
    def tree(root, files):
        for rel, data in files.items():
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            (root / rel).write_bytes(data)

    def node(self, *args):
        subprocess.run([NODE, 'tools/publish.mjs', *map(str, args), '--out', str(self.root / 'feed')],
                       cwd=self.root / 'publisher', check=True, capture_output=True)

    def publish(self, news_file, *extra, fail=None):
        env = dict(self.env, **({'PUBLISH_TEST_FAIL': fail} if fail else {}))
        return subprocess.run(['bash', str(HERE / 'publish-client'), '1.1', '--source', str(self.src),
                               '--release', 'r2', '--news', str(self.root / 'staging' / news_file), '--no-packs',
                               '--no-announce', *extra], env=env, capture_output=True, text=True)

    def manifest(self):
        return json.loads((self.root / 'feed/manifest.json').read_text())

    def layer(self):
        return self.root / 'feed/layers/client/1.1'

    def test_bad_news_stops_dry_run_and_real_run_before_the_layer(self):
        for extra in (('--dry-run',), ()):
            with self.subTest(extra=extra):
                out = self.publish('news-bad.json', *extra)
                self.assertNotEqual(out.returncode, 0, out.stdout)
                self.assertIn('at most 10 entries', out.stderr)
                self.assertFalse(self.layer().exists(), 'nothing may be published')
                self.assertEqual(self.manifest()['version'], 'r1')

    def test_rerun_reuses_an_identical_layer(self):
        out = self.publish('news-ok.json', fail='after-layer')
        self.assertEqual(out.returncode, 1, out.stdout + out.stderr)
        self.assertIn('PUBLISH_TEST_FAIL=after-layer', out.stderr)
        published = (self.layer() / 'manifest.json').read_bytes()
        self.assertEqual(self.manifest()['version'], 'r1')
        # The comparison is against the real publisher's bytes.
        self.assertEqual(pc.layer_manifest_body(str(self.src), 'client', '1.1'), published)

        dry = self.publish('news-ok.json', '--dry-run')
        self.assertEqual(dry.returncode, 0, dry.stdout + dry.stderr)
        self.assertIn('already published, identical: skipped', dry.stdout)

        out = self.publish('news-ok.json')
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
        self.assertIn('reusing it, not publishing it again', out.stdout)
        self.assertIn(hashlib.sha256(published).hexdigest(), out.stdout)
        self.assertEqual((self.layer() / 'manifest.json').read_bytes(), published)
        m = self.manifest()
        self.assertEqual((m['version'], [(l['name'], l['version']) for l in m['layers']]),
                         ('r2', [('base', 'v83'), ('client', '1.1')]))
        self.assertEqual(len(m['news']), 3)

    def test_rerun_with_a_changed_tree_still_aborts(self):
        self.assertEqual(self.publish('news-ok.json', fail='after-layer').returncode, 1)
        (self.src / 'slums.dll').write_bytes(b'dll 2')
        for extra in (('--dry-run',), ()):
            with self.subTest(extra=extra):
                out = self.publish('news-ok.json', *extra)
                self.assertNotEqual(out.returncode, 0)
                self.assertIn('already published with different files', out.stderr)
                self.assertEqual(self.manifest()['version'], 'r1')


@unittest.skipUnless(HAVE_PUBLISHER, 'needs node and the launcher repo')
class LayerManifestMirrorTest(unittest.TestCase):
    def test_odd_names_match_the_publisher(self):
        with tempfile.TemporaryDirectory() as t:
            src, out = Path(t) / 'src', Path(t) / 'out'
            for rel in ('B.txt', 'a.txt', '_x', 'Data/Map (old)/x+y!.img', 'Data/café #1.wz',
                        'Data/\U0001F600.txt', 'Data/Ａ.txt', "it's (1).txt", 'z/empty'):
                (src / rel).parent.mkdir(parents=True, exist_ok=True)
                (src / rel).write_bytes(rel.encode())
            subprocess.run([NODE, str(LAUNCHER / 'tools/publish.mjs'), '--layer', 'client', '--source', str(src),
                            '--out', str(out), '--version', '9.9'], check=True, capture_output=True)
            body = (out / 'layers/client/9.9/manifest.json').read_bytes()
            self.assertEqual(pc.layer_manifest_body(str(src), 'client', '9.9'), body)
            self.assertEqual(pc.same_layer(str(src), str(out / 'layers/client/9.9'), 'client', '9.9')[0], True)
            (out / 'layers/client/9.9/files/B.txt').unlink()
            self.assertEqual(pc.same_layer(str(src), str(out / 'layers/client/9.9'), 'client', '9.9')[0], False)


if __name__ == '__main__':
    unittest.main()
