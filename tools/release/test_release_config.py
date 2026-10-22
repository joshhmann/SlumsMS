"""Offline release tests: python3 -m unittest discover -s tools/release -p 'test_*.py'.

Never execute deploy/stage/release or contact a host. Exercise config helpers and
inspect generated scripts for staging, backup, phase ordering and rollback wiring.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
FIXTURE = (HERE / 'fixtures/config.yaml').read_bytes()


class ReleaseConfigTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=ROOT)
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)
        self.config = self.work / 'config.yaml'
        self.config.write_bytes(FIXTURE)

    def run_helper(self, name='set-release-config', *args, ok=True):
        result = subprocess.run([str(HERE / name), str(self.config), *args],
                                text=True, capture_output=True)
        if ok:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0)
        return result

    def test_changes_only_values_and_prints_before_after(self):
        result = self.run_helper('set-release-config', '--channels', '5', '--channel-load', '105')
        self.assertEqual(self.config.read_bytes(), FIXTURE.replace(b'channels: 3', b'channels: 5')
                         .replace(b'channels: 2', b'channels: 5').replace(b'90 #', b'105 #'))
        self.assertIn('server.CHANNEL_LOAD: 90 -> 105', result.stdout)
        self.assertIn('worlds[0].channels: 3 -> 5', result.stdout)

    def test_gate_preserves_comments_and_refuses_lower(self):
        result = self.run_helper('set-min-client-build', '10500')
        self.assertEqual(self.config.read_bytes(), FIXTURE.replace(b'10402', b'10500'))
        self.assertIn('10402 -> 10500', result.stdout)
        self.run_helper('set-min-client-build', '10402', ok=False)
        self.assertEqual(self.config.read_bytes(), FIXTURE.replace(b'10402', b'10500'))

    def test_idempotent_and_keeps_inode_mode_crlf(self):
        self.config.write_bytes(FIXTURE.replace(b'\n', b'\r\n'))
        self.config.chmod(0o640)
        inode = self.config.stat().st_ino
        for _ in range(2):
            self.run_helper('set-release-config', '--channels', '5', '--channel-load', '105')
            self.run_helper('set-min-client-build', '10500')
            if _ == 0:
                first = self.config.read_bytes()
        self.assertEqual(self.config.read_bytes(), first)
        self.assertEqual(first.count(b'\n'), first.count(b'\r\n'))
        self.assertEqual(self.config.stat().st_ino, inode)
        self.assertEqual(self.config.stat().st_mode & 0o777, 0o640)

    def test_no_final_newline_is_preserved(self):
        self.config.write_bytes(FIXTURE.rstrip(b'\n'))
        self.run_helper('set-release-config', '--channels', '5', '--channel-load', '105')
        self.run_helper('set-min-client-build', '10500')
        self.assertFalse(self.config.read_bytes().endswith(b'\n'))

    def test_individual_channel_settings_leave_the_other_unchanged(self):
        self.run_helper('set-release-config', '--channel-load', '105')
        self.assertEqual(self.config.read_bytes(), FIXTURE.replace(b'90 #', b'105 #'))
        self.config.write_bytes(FIXTURE)
        self.run_helper('set-release-config', '--channels', '5')
        self.assertEqual(self.config.read_bytes(), FIXTURE.replace(b'channels: 3', b'channels: 5')
                         .replace(b'channels: 2', b'channels: 5'))

    def test_dry_runs_do_not_write(self):
        self.run_helper('set-release-config', '--channels', '5', '--channel-load', '105', '--dry-run')
        self.run_helper('set-min-client-build', '10500', '--dry-run')
        self.assertEqual(self.config.read_bytes(), FIXTURE)

    def test_unset_means_no_change(self):
        self.run_helper()
        self.assertEqual(self.config.read_bytes(), FIXTURE)

    def test_adds_only_known_missing_keys(self):
        self.config.write_bytes(b'worlds:\n- id: 1\n  exp_rate: 2\nserver:\n    SERVER_NAME: SlumsMS\n')
        self.run_helper('set-release-config', '--channels', '5', '--channel-load', '105')
        self.run_helper('set-min-client-build', '10500')
        text = self.config.read_text()
        self.assertIn('  channels: 5\nserver:', text)
        self.assertIn('    CHANNEL_LOAD: 105', text)
        self.assertIn('    MIN_CLIENT_BUILD: 10500', text)
        self.assertNotIn('    CHANNELS:', text)

    def test_first_world_key_can_be_channels(self):
        self.config.write_bytes(b'worlds:\n  - channels: 2 # first\n    id: 1\nserver:\n')
        self.run_helper('set-release-config', '--channels', '5')
        self.assertIn(b'  - channels: 5 # first', self.config.read_bytes())

    def test_invalid_values_or_structure_leave_file_unchanged(self):
        for value in ('0', '-1', '1.5', 'five', '1; echo BAD'):
            self.run_helper('set-release-config', '--channels', value, ok=False)
            self.assertEqual(self.config.read_bytes(), FIXTURE)
        for data in (FIXTURE.replace(b'channels: 3', b'channels: nope'),
                     FIXTURE.replace(b'worlds:\n', b'worlds: []\n'),
                     FIXTURE.replace(b'    CHANNEL_LOAD:', b'    CHANNEL_LOAD: 1\n    CHANNEL_LOAD:')):
            self.config.write_bytes(data)
            self.run_helper('set-release-config', '--channels', '5', '--channel-load', '105', ok=False)
            self.assertEqual(self.config.read_bytes(), data)

    def generate(self, conf):
        (self.work / 'release.conf').write_text(conf)
        (self.work / 'BUILD.txt').write_text('commit=fixture\nversion=1.5.0\njar_sha256=1234567890abcdef\n')
        result = subprocess.run([str(HERE / 'gen-deploy'), str(self.work / 'release.conf'),
                                 str(self.work), str(self.work / 'out'), '1.5.0'],
                                text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return ((self.work / 'out/deploy.sh').read_text(),
                (self.work / 'out/rollback.sh').read_text())

    def test_generated_settings_defaults_and_real_release(self):
        deploy, _ = self.generate('LEDGER_BEFORE=""\n')
        for key in ('MIN_CLIENT_BUILD', 'CHANNELS', 'CHANNEL_LOAD'):
            self.assertIn(f"{key}=''", deploy)
        deploy, _ = self.generate((ROOT / 'docs/release/1.5.0.conf').read_text())
        for key, value in [('MIN_CLIENT_BUILD', '10500'), ('CHANNELS', '5'), ('CHANNEL_LOAD', '125')]:
            self.assertIn(f"{key}='{value}'", deploy)

    def test_generated_order_backup_rollback_and_staging(self):
        deploy, rollback = self.generate('MIN_CLIENT_BUILD=10500\nCHANNELS=5\nCHANNEL_LOAD=105\nLEDGER_BEFORE=""\n')
        backup = deploy.index('tar -czf "$B/server-runtime.tgz"')
        apply = deploy.index('touch "$B/.apply-start.started"')
        hook = deploy.index('"$ST/set-min-client-build" "$C" "$MIN_CLIENT_BUILD" || down')
        channel_hook = deploy.index('"$ST/set-release-config" "$C" --channels "$CHANNELS" --channel-load "$CHANNEL_LOAD" || down')
        start = deploy.index('step "start"', apply)
        self.assertLess(backup, apply)
        self.assertLess(apply, hook)
        self.assertLess(hook, channel_hook)
        self.assertLess(channel_hook, start)
        self.assertIn('target/Cosmic.jar config.yaml scripts src/main', deploy)
        self.assertIn('cp "$C" "$B/config.yaml"', deploy)
        self.assertIn('"$MIN_CLIENT_BUILD" --dry-run', deploy)
        self.assertLess(rollback.index('tar -xzf'), rollback.index('step "start"'))
        self.assertIn('cmp -s "$B/config.yaml" "$SRV/config.yaml"', rollback)
        stage = (HERE / 'stage').read_text()
        self.assertLess(stage.index('cp "$here/set-min-client-build" "$here/set-release-config" "$ST/"'),
                        stage.index('find . -type f ! -name SHA256SUMS'))
        release = (HERE / 'release').read_text()
        self.assertLess(release.index('# ---- 3. client publish'), release.index('# ---- 4. apply + start'))

    def test_cashshop_catalogue_step(self):
        deploy, rollback = self.generate('LEDGER_BEFORE=""\n')
        self.assertIn("CASHSHOP_CATALOG=''", deploy)
        (ROOT / 'cashshop/catalog.tsv').stat()
        deploy, rollback = self.generate((ROOT / 'docs/release/1.5.0.conf').read_text())
        self.assertIn("CASHSHOP_CATALOG='catalog.tsv'", deploy)
        backup = deploy.index('rt+=(cashshop/catalog.tsv)')
        apply = deploy.index('touch "$B/.apply-start.started"')
        copy = deploy.index('cp "$ST/$CASHSHOP_CATALOG" "$SRV/cashshop/catalog.tsv"')
        start = deploy.index('step "start"', apply)
        self.assertLess(backup, apply)
        self.assertLess(apply, copy)
        self.assertLess(copy, start)
        self.assertIn('paths+=(cashshop/catalog.tsv)', rollback)
        self.assertLess(rollback.index('paths+=(cashshop/catalog.tsv)'), rollback.index('step "start"'))


if __name__ == '__main__':
    unittest.main()
