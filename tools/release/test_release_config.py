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

    def test_set_edits_existing_numeric_keys_and_adds_missing_ones(self):
        self.config.write_bytes(FIXTURE + b'    BOT_ROSTER_PEAK_ONLINE: 1200  # soaked\n    BOT_EXTRA:\n        k: v\n')
        result = self.run_helper('set-release-config', '--set', 'BOT_ROSTER_PEAK_ONLINE=3000',
                                 '--set', 'BOT_POPULATION_PEAK=3500', '--set', 'BOT_ROSTER_VETERAN_FILL_BELOW=1.0')
        text = self.config.read_text()
        self.assertIn('    BOT_ROSTER_PEAK_ONLINE: 3000  # soaked\n', text)
        self.assertTrue(text.endswith('        k: v\n    BOT_POPULATION_PEAK: 3500\n    BOT_ROSTER_VETERAN_FILL_BELOW: 1.0\n'))
        self.assertEqual(text.count('BOT_ROSTER_PEAK_ONLINE'), 1)
        self.assertIn('server.BOT_ROSTER_PEAK_ONLINE: 1200 -> 3000', result.stdout)
        self.assertIn('server.BOT_POPULATION_PEAK: unset -> 3500', result.stdout)
        before = self.config.read_bytes()
        self.run_helper('set-release-config', '--set', 'BOT_ROSTER_PEAK_ONLINE=3000',
                        '--set', 'BOT_POPULATION_PEAK=3500', '--set', 'BOT_ROSTER_VETERAN_FILL_BELOW=1.0')
        self.assertEqual(self.config.read_bytes(), before, 'idempotent')
        self.run_helper('set-release-config', '--set', 'BOT_ROSTER_VETERAN_FILL_BELOW=0.5')
        self.assertIn('    BOT_ROSTER_VETERAN_FILL_BELOW: 0.5\n', self.config.read_text())

    def test_set_keeps_crlf_and_inserts_before_the_next_top_level_block(self):
        self.config.write_bytes(b'server:\r\n  A: 1\r\nlater:\r\n  B: 2\r\n')
        self.run_helper('set-release-config', '--set', 'BOT_MAP_CAP=50', '--set', 'A=7')
        self.assertEqual(self.config.read_bytes(), b'server:\r\n  A: 7\r\n  BOT_MAP_CAP: 50\r\nlater:\r\n  B: 2\r\n')

    def test_set_refuses_bad_input_and_leaves_the_file(self):
        self.config.write_bytes(FIXTURE + b'    BOT_ROSTER_TIMEZONE: "America/Los_Angeles"\n')
        data = self.config.read_bytes()
        for args in (['--set', 'BOT_ROSTER_PEAK_ONLINE'], ['--set', 'bot_x=1'], ['--set', 'BOT_X=-1'],
                     ['--set', 'BOT_X=1e3'], ['--set', 'BOT_X=1; echo BAD'], ['--set', 'CHANNEL_LOAD=105'],
                     ['--set', 'MIN_CLIENT_BUILD=1'], ['--set', 'BOT_ROSTER_TIMEZONE=5'],
                     ['--set', 'BOT_X=1', '--set', 'BOT_X=2']):
            self.run_helper('set-release-config', *args, ok=False)
            self.assertEqual(self.config.read_bytes(), data, args)
        self.config.write_bytes(FIXTURE + b'    BOT_X: 1\n    BOT_X: 2\n')
        self.run_helper('set-release-config', '--set', 'BOT_X=3', ok=False)

    def test_set_flips_a_boolean_and_never_mixes_kinds(self):
        # 1.6.0: USE_ENABLE_CUSTOM_NPC_SCRIPT goes false -> true in the deploy, not by hand
        self.config.write_bytes(FIXTURE + b'    USE_ENABLE_CUSTOM_NPC_SCRIPT: false  # custom NPCs\n    BOT_MAP_CAP: 40\n')
        result = self.run_helper('set-release-config', '--set', 'USE_ENABLE_CUSTOM_NPC_SCRIPT=true')
        self.assertIn('    USE_ENABLE_CUSTOM_NPC_SCRIPT: true  # custom NPCs\n', self.config.read_text())
        self.assertIn('server.USE_ENABLE_CUSTOM_NPC_SCRIPT: false -> true', result.stdout)
        data = self.config.read_bytes()
        self.run_helper('set-release-config', '--set', 'USE_ENABLE_CUSTOM_NPC_SCRIPT=1', ok=False)
        self.run_helper('set-release-config', '--set', 'BOT_MAP_CAP=true', ok=False)
        self.run_helper('set-release-config', '--set', 'USE_ENABLE_CUSTOM_NPC_SCRIPT=yes', ok=False)
        self.assertEqual(self.config.read_bytes(), data)
        self.run_helper('set-release-config', '--set', 'USE_EXTRA_FLAG=false')
        self.assertTrue(self.config.read_text().endswith('    USE_EXTRA_FLAG: false\n'))

    def test_set_writes_text_quoted_and_only_over_text(self):
        # 1.6.0 busy towns: BOT_LIFE_TOWN_WEIGHTS is a String field
        self.config.write_bytes(FIXTURE + b'    BOT_LIFE_TOWN_WEIGHTS: "henesys:100"  # towns\n    BOT_MAP_CAP: 40\n')
        result = self.run_helper('set-release-config', '--set', 'BOT_LIFE_TOWN_WEIGHTS=near:55,home:20,henesys:25')
        self.assertIn('    BOT_LIFE_TOWN_WEIGHTS: "near:55,home:20,henesys:25"  # towns\n', self.config.read_text())
        self.assertIn('server.BOT_LIFE_TOWN_WEIGHTS: "henesys:100" -> "near:55,home:20,henesys:25"', result.stdout)
        data = self.config.read_bytes()
        self.run_helper('set-release-config', '--set', 'BOT_MAP_CAP=abc', ok=False)
        self.run_helper('set-release-config', '--set', 'BOT_LIFE_TOWN_WEIGHTS=a b', ok=False)
        self.run_helper('set-release-config', '--set', 'BOT_LIFE_TOWN_WEIGHTS=x"y', ok=False)
        self.assertEqual(self.config.read_bytes(), data)
        self.run_helper('set-release-config', '--set', 'BOT_NEW_TEXT=a:1')
        self.assertTrue(self.config.read_text().endswith('    BOT_NEW_TEXT: "a:1"\n'))

    def test_set_dry_run_does_not_write(self):
        result = self.run_helper('set-release-config', '--set', 'BOT_MAP_CAP=50', '--dry-run')
        self.assertIn('server.BOT_MAP_CAP: unset -> 50', result.stdout)
        self.assertEqual(self.config.read_bytes(), FIXTURE)

    def generate_fails(self, conf):
        (self.work / 'release.conf').write_text(conf)
        (self.work / 'BUILD.txt').write_text('commit=fixture\nversion=1.5.0\njar_sha256=1234567890abcdef\n')
        result = subprocess.run([str(HERE / 'gen-deploy'), str(self.work / 'release.conf'),
                                 str(self.work), str(self.work / 'out'), '1.5.0'],
                                text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        return result.stderr

    def test_server_set_keys_must_be_numeric_server_config_fields(self):
        self.assertIn('not a numeric, boolean or text field', self.generate_fails('LEDGER_BEFORE=""\nSERVER_SET=(BOT_NOT_A_KEY=1)\n'))
        self.assertIn('is a text field; 1 is not text', self.generate_fails('LEDGER_BEFORE=""\nSERVER_SET=(BOT_ROSTER_CURVE=1)\n'))
        # 1.6.0: booleans go only into boolean fields, numbers only into numeric ones
        self.assertIn('not true/false', self.generate_fails('LEDGER_BEFORE=""\nSERVER_SET=(USE_ENABLE_CUSTOM_NPC_SCRIPT=1)\n'))
        self.assertIn('not a number', self.generate_fails('LEDGER_BEFORE=""\nSERVER_SET=(BOT_MAP_CAP=true)\n'))
        deploy, _ = self.generate('LEDGER_BEFORE=""\nSERVER_SET=(USE_ENABLE_CUSTOM_NPC_SCRIPT=true)\n')
        self.assertIn('USE_ENABLE_CUSTOM_NPC_SCRIPT=true', deploy)
        self.assertIn('not a whole number', self.generate_fails('LEDGER_BEFORE=""\nSERVER_SET=(BOT_MAP_CAP=1.5)\n'))
        self.assertIn('not a number', self.generate_fails('LEDGER_BEFORE=""\nSERVER_SET=(BOT_MAP_CAP=abc)\n'))
        deploy, _ = self.generate('LEDGER_BEFORE=""\nSERVER_SET=(BOT_LIFE_TOWN_WEIGHTS=near:55,home:20,henesys:25)\n')
        self.assertIn('BOT_LIFE_TOWN_WEIGHTS=near:55,home:20,henesys:25', deploy)
        self.assertIn('BOT_ROSTER_TIMEZONE=America/Los_Angeles', deploy)   # a zone id has a slash
        self.assertIn("'s# -jar # -Dsolomapling.events=true -jar #'", deploy)
        self.assertIn('twice', self.generate_fails('LEDGER_BEFORE=""\nSERVER_SET=(BOT_MAP_CAP=1 BOT_MAP_CAP=2)\n'))
        self.assertIn('use CHANNEL_LOAD', self.generate_fails('LEDGER_BEFORE=""\nSERVER_SET=(CHANNEL_LOAD=1)\n'))
        deploy, _ = self.generate('LEDGER_BEFORE=""\n')
        self.assertIn('SERVER_SET=()', deploy)
        pre = deploy.index('"$ST/set-release-config" "$C" "${SET_ARGS[@]}" --dry-run')
        stop = deploy.index('step "stop"')
        apply = deploy.index('"$ST/set-release-config" "$C" "${SET_ARGS[@]}" || down')
        start = deploy.index('step "start"', apply)
        self.assertLess(pre, stop)
        self.assertLess(deploy.index('touch "$B/.apply-start.started"'), apply)
        self.assertLess(apply, start)

    def test_release_150_sets_3k_bots_and_the_6g_heap(self):
        deploy, _ = self.generate((ROOT / 'docs/release/1.5.0.conf').read_text())
        self.assertIn("SERVER_SET=('BOT_ROSTER_PEAK_ONLINE=3000' 'BOT_POPULATION_PEAK=3500' 'BOT_MAP_CAP=50' "
                      "'BOT_ROSTER_VETERAN_FILL_BELOW=1.0' 'BOT_ROSTER_VETERANS_PER_DAY=7200')", deploy)
        self.assertIn("UNIT_EDITS=('s# -Dslumsms.channelLoad.botWeight=0.1##' "
                      "'s#/usr/bin/java -Xmx4G #/usr/bin/java -Xms4G -Xmx6G #')", deploy)
        self.assertIn("CHANNEL_LOAD='125'", deploy)
        unit = ('ExecStart=/usr/bin/java -Xmx4G -Dmapleroot.login-port=8485 -Dsolomapling.enabled=true '
                '-Dslumsms.channelLoad.botWeight=0.1 -jar target/Cosmic.jar\n')
        path = self.work / 'slumsms.service'
        for edit in ('s# -Dslumsms.channelLoad.botWeight=0.1##', 's#/usr/bin/java -Xmx4G #/usr/bin/java -Xms4G -Xmx6G #'):
            path.write_text(unit)
            subprocess.run(['sed', '-i', '-e', edit, str(path)], check=True)
            self.assertNotEqual(path.read_text(), unit, edit)   # each edit changes live's unit on its own
        for edit in ('s# -Dslumsms.channelLoad.botWeight=0.1##', 's#/usr/bin/java -Xmx4G #/usr/bin/java -Xms4G -Xmx6G #'):
            subprocess.run(['sed', '-i', '-e', edit, str(path)], check=True)
        self.assertEqual(path.read_text(), 'ExecStart=/usr/bin/java -Xms4G -Xmx6G -Dmapleroot.login-port=8485 '
                                           '-Dsolomapling.enabled=true -jar target/Cosmic.jar\n')

    def test_release_160_turns_on_the_staff_dashboard(self):
        deploy, _ = self.generate((ROOT / 'docs/release/1.6.0.conf').read_text())
        dash = ('s# -jar # -Dsolomapling.dashboard=true -Dsolomapling.dashboard.dir=/srv/slums-dashboard/data '
                '-Dsolomapling.dashboard.periodSeconds=60 -jar #')
        self.assertIn("'" + dash + "'", deploy)
        self.assertIn("-Dslums.auth.revoked=/var/lib/slums-login/revoked.txt -jar #' '" + dash + "'", deploy,
                      "the existing edit stays first and intact")
        unit = 'ExecStart=/usr/bin/java -Xms4G -Xmx6G -Dsolomapling.enabled=true -jar target/Cosmic.jar\n'
        path = self.work / 'slumsms.service'
        path.write_text(unit)
        subprocess.run(['sed', '-i', '-e', dash, str(path)], check=True)
        self.assertNotEqual(path.read_text(), unit)   # the edit changes live's unit on its own
        self.assertIn(' -Dsolomapling.dashboard.periodSeconds=60 -jar target/Cosmic.jar', path.read_text())
        # owner 2026-10-27: OneJev shadows on live, scores only (no chat text), and the busy towns ship on
        self.assertIn("'s# -jar # -Dslums.decide.shadow=onejev -Dslums.decide.shadow.text=false -jar #'", deploy)
        self.assertIn('BOT_TOWN_PULL=0.3', deploy)
        self.assertIn('BOT_LIFE_TOWN_WEIGHTS=near:55,home:20,henesys:25', deploy)

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

    def test_publish_client_release_without_no_packs_does_not_exit(self):
        # `x=$([ 0 = 1 ] && echo y)` fails under set -e: publish-client stopped silently after the hash-diff
        # whenever --no-packs was not given (found publishing the 1.5.0-rc4 client to /launcher-rc/).
        src = (HERE / 'publish-client').read_text()
        line = next(l for l in src.splitlines() if l.startswith('pub_rel='))
        self.assertIn('echo " --no-packs" || true)', line)
        for nopacks, want in (('0', ''), ('1', ' --no-packs')):
            out = subprocess.run(['bash', '-c', 'set -euo pipefail; NOPACKS=$1; '
                                  'x="r$([ "$NOPACKS" = 1 ] && echo " --no-packs" || true)"; echo "$x"', '_', nopacks],
                                 text=True, capture_output=True)
            self.assertEqual(out.returncode, 0)
            self.assertEqual(out.stdout.strip(), 'r' + want)

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
