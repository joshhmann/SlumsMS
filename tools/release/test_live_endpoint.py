"""Offline: publish-client / verify-client.py refuse a client that points away from the live server on the live feed.

python3 -m unittest discover -s tools/release -p 'test_*.py'
"""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('verify_client', HERE / 'verify-client.py')
vc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(vc)

LIVE_INI = """[general]
resolution=5
[clienthax]
nobulb=true
[config]
host=slumsms.asslorde.com
port=8485
"""


def ini(host, port):
    return LIVE_INI.replace('slumsms.asslorde.com', host).replace('8485', str(port))


class LiveEndpointTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.feed = self.root / 'feed'
        self.layer('base', 'v83', {'Data/a.wz': b'a'})
        self.layer('client', '1.4.1', {'config.ini': LIVE_INI.encode()})
        (self.feed / 'manifest.json').write_text(json.dumps({'schemaVersion': 1, 'version': '1.4.1', 'layers': [
            {'name': 'base', 'version': 'v83', 'manifestUrl': 'layers/base/v83/manifest.json'},
            {'name': 'client', 'version': '1.4.1', 'manifestUrl': 'layers/client/1.4.1/manifest.json'}]}))

    def layer(self, name, version, files):
        d = self.feed / 'layers' / name / version
        entries = []
        for rel, data in files.items():
            (d / 'files' / rel).parent.mkdir(parents=True, exist_ok=True)
            (d / 'files' / rel).write_bytes(data)
            entries.append({'path': rel, 'size': len(data), 'sha256': vc.hashlib.sha256(data).hexdigest(),
                            'url': 'files/' + rel})
        (d / 'manifest.json').write_text(json.dumps({'schemaVersion': 1, 'layer': name, 'version': version,
                                                     'files': entries}))
        return d / 'manifest.json'

    def client_dir(self, text):
        d = self.root / ('staging-%d' % len(list(self.root.iterdir())))
        d.mkdir()
        if text is not None:
            (d / 'config.ini').write_text(text)
        return d

    def check(self, **kw):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            rc = vc.check_live_endpoint(str(self.feed), **kw)
        return rc, out.getvalue()

    def test_the_live_endpoint_passes(self):
        rc, out = self.check(client_dir=str(self.client_dir(LIVE_INI.replace('nobulb=true', 'nobulb=false'))))
        self.assertEqual(rc, 0, out)
        self.assertIn('LIVE ENDPOINT PASS', out)

    def test_a_test_server_host_or_port_is_refused(self):
        for text in (ini('test.slumsms.local', 8485), ini('slumsms.asslorde.com', 7575), ini('192.168.0.164', 8484)):
            rc, out = self.check(client_dir=str(self.client_dir(text)))
            self.assertEqual(rc, 1, out)
            self.assertIn('LIVE ENDPOINT FAIL', out)

    def test_host_case_and_spacing_do_not_matter(self):
        rc, out = self.check(client_dir=str(self.client_dir(LIVE_INI.replace('host=slumsms', 'host = SlumsMS'))))
        self.assertEqual(rc, 0, out)

    def test_a_client_without_config_ini_is_refused(self):
        rc, out = self.check(client_dir=str(self.client_dir(None)))
        self.assertEqual(rc, 1, out)

    def test_a_published_layer_manifest_is_checked_too(self):
        bad = self.layer('client', '1.5.0-rc6', {'config.ini': ini('test.slumsms.local', 8485).encode()})
        good = self.layer('client', '1.5.0', {'config.ini': LIVE_INI.encode()})
        self.assertEqual(self.check(client_manifest=str(bad))[0], 1)
        self.assertEqual(self.check(client_manifest=str(good))[0], 0)

    def test_a_deliberate_move_needs_the_flag(self):
        moved = str(self.client_dir(ini('new.slumsms.com', 8485)))
        self.assertEqual(self.check(client_dir=moved)[0], 1)
        rc, out = self.check(client_dir=moved, allow_change=True)
        self.assertEqual(rc, 0, out)
        self.assertIn('CHANGE ALLOWED', out)

    def server_ini(self, d, host, port):
        Path(d, 'server.ini').write_text(f'[server]\nhost={host}\nport={port}\n')
        return d

    def test_a_server_ini_pointing_away_is_refused(self):
        # b301: slums.dll reads server.ini before config.ini, so a matching config.ini alone is not enough
        d = self.server_ini(self.client_dir(LIVE_INI), '192.168.0.111', 8489)
        rc, out = self.check(client_dir=str(d))
        self.assertEqual(rc, 1, out)
        self.assertIn('LIVE ENDPOINT FAIL', out)

    def test_a_server_ini_naming_the_live_server_passes(self):
        d = self.server_ini(self.client_dir(LIVE_INI), 'SlumsMS.asslorde.com', 8485)
        rc, out = self.check(client_dir=str(d))
        self.assertEqual(rc, 0, out)
        self.assertIn('server.ini', out)

    def test_a_server_ini_without_a_usable_port_falls_back_to_config_ini(self):
        for port in ('', 'abc', '0', '70000'):
            d = self.server_ini(self.client_dir(LIVE_INI), '192.168.0.111', port)
            self.assertEqual(self.check(client_dir=str(d))[0], 0, port)

    def test_config_ini_must_still_match_for_older_dlls(self):
        d = self.server_ini(self.client_dir(ini('192.168.0.111', 8489)), 'slumsms.asslorde.com', 8485)
        rc, out = self.check(client_dir=str(d))
        self.assertEqual(rc, 1, out)
        self.assertEqual(self.check(client_dir=str(d), allow_change=True)[0], 1)

    def test_main_fails_on_the_endpoint_even_when_the_hash_diff_passes(self):
        reference = self.root / 'reference'
        (reference / 'Data').mkdir(parents=True)
        (reference / 'Data/a.wz').write_bytes(b'a')
        allow = self.root / 'allow.txt'
        allow.write_text('extra config.ini\n')
        argv = ['--base-manifest', str(self.feed / 'layers/base/v83/manifest.json'),
                '--reference', str(reference), '--allow', str(allow), '--live-feed', str(self.feed)]
        for text, want in ((LIVE_INI, 0), (ini('test.slumsms.local', 8485), 1)):
            d = self.client_dir(text)
            with contextlib.redirect_stdout(io.StringIO()) as out:
                import sys
                old = sys.argv
                sys.argv = ['verify-client.py', *argv, '--client-dir', str(d)]
                try:
                    rc = vc.main()
                finally:
                    sys.argv = old
            self.assertEqual(rc, want, out.getvalue())
            self.assertIn('HASH-DIFF PASS', out.getvalue())

    def test_publish_client_checks_the_live_feed_only(self):
        script = (HERE / 'publish-client').read_text()
        self.assertIn('LIVE_PATCH_DIR=${LIVE_PATCH_DIR:-/srv/slums-patch}', script)
        self.assertIn('if [ "${PATCH_DIR%/}" = "${LIVE_PATCH_DIR%/}" ]; then', script)
        self.assertIn('--live-feed', script)
        verify = script[script.index('verify() {'):script.index('}', script.index('verify() {'))]
        self.assertIn('$live_args', verify, 'both hash-diffs (staging tree and published layer) carry the check')


if __name__ == '__main__':
    unittest.main()
