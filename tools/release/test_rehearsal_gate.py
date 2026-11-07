"""P1-R1 offline tests: the rehearsal receipt binds the staged bytes, the extra-steps contract, the WZ copy, unit-flag removal.

python3 -m unittest discover -s tools/release -p 'test_*.py'

Everything runs on temp dirs with fakes: no SSH, no deploy.sh/rehearse-host.sh/release execution, no host, no live path.
"""
import hashlib
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
LIB = HERE / 'lib.sh'


def sh(script, *args, env=None, cwd=None):
    """Run a bash snippet with lib.sh sourced; returns CompletedProcess (never raises)."""
    full = dict(os.environ)
    if env:
        full.update(env)
    return subprocess.run(['bash', '-c', f'. "{LIB}"\n{script}', '_', *map(str, args)],
                          text=True, capture_output=True, env=full, cwd=cwd)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


class TmpCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)


class RehearsalGate(TmpCase):
    """A fake version folder + REHEARSAL.md; the gate is lib.sh rehearsal_gate (what release / release-live.sh call)."""

    def setUp(self):
        super().setUp()
        self.d = self.work / 'v9.9.9'
        (self.d / 'staging').mkdir(parents=True)
        (self.d / 'staging/Cosmic.jar').write_bytes(b'jar-1')
        (self.d / 'staging/release.conf').write_text('X=1\n')
        (self.d / 'deploy.sh').write_text('#!/bin/bash\necho deploy\n')
        (self.d / 'rollback.sh').write_text('#!/bin/bash\necho rollback\n')
        self.resum()
        self.receipt()

    def resum(self):
        subprocess.run('find . -type f ! -name SHA256SUMS -printf "%P\\n" | LC_ALL=C sort | xargs -d "\\n" sha256sum > SHA256SUMS',
                       shell=True, cwd=self.d / 'staging', check=True)

    def receipt(self, result='PASS'):
        lines = sh('rehearsal_binding_lines "$1"', self.d).stdout
        (self.d / 'REHEARSAL.md').write_text(f'# rehearsal\n\n- When: now. **Result: {result}**\n{lines}')

    def gate(self):
        return sh('rehearsal_gate "$1"', self.d)

    def test_a_matching_digest_and_pass_is_accepted(self):
        out = self.gate()
        self.assertEqual(out.returncode, 0, out.stderr)
        script = subprocess.run(['bash', str(HERE / 'check-rehearsal.sh'), str(self.d)], text=True, capture_output=True)
        self.assertEqual(script.returncode, 0, script.stderr)

    def test_b_older_staging_is_refused(self):
        # one staged file changed after the rehearsal, SHA256SUMS regenerated (a re-stage): the digest differs
        (self.d / 'staging/Cosmic.jar').write_bytes(b'jar-2')
        self.resum()
        out = self.gate()
        self.assertNotEqual(out.returncode, 0)
        self.assertIn('rehearsal is for different staged bytes (stale or tampered): rerun tools/release/rehearse', out.stderr)
        script = subprocess.run(['bash', str(HERE / 'check-rehearsal.sh'), str(self.d)], text=True, capture_output=True)
        self.assertNotEqual(script.returncode, 0)

    def test_b2_staged_file_changed_without_resumming_is_refused(self):
        (self.d / 'staging/Cosmic.jar').write_bytes(b'jar-2')   # SHA256SUMS untouched: the digest still matches
        out = self.gate()
        self.assertNotEqual(out.returncode, 0)
        self.assertIn('rehearsal is for different staged bytes (stale or tampered)', out.stderr)

    def test_b3_changed_deploy_script_is_refused(self):
        (self.d / 'deploy.sh').write_text('#!/bin/bash\necho evil\n')
        out = self.gate()
        self.assertNotEqual(out.returncode, 0)
        self.assertIn('deploy.sh/rollback.sh differ', out.stderr)

    def test_c_receipt_edited_to_pass_with_a_wrong_digest_is_refused(self):
        self.receipt(result='FAIL')
        text = (self.d / 'REHEARSAL.md').read_text().replace('Result: FAIL', 'Result: PASS')   # claims PASS, digest from the FAIL run is right...
        bad = text.replace(sh('staging_digest "$1"', self.d).stdout.strip(), '0' * 64)
        (self.d / 'REHEARSAL.md').write_text(bad)
        out = self.gate()
        self.assertNotEqual(out.returncode, 0)
        self.assertIn('different staged bytes (stale or tampered)', out.stderr)

    def test_c2_receipt_without_a_digest_is_refused(self):
        (self.d / 'REHEARSAL.md').write_text('# rehearsal\n\n- When: now. **Result: PASS**\n')
        out = self.gate()
        self.assertNotEqual(out.returncode, 0)
        self.assertIn('no staging digest', out.stderr)

    def test_c3_failed_rehearsal_is_refused_even_with_the_right_digest(self):
        self.receipt(result='FAIL')
        out = self.gate()
        self.assertNotEqual(out.returncode, 0)
        self.assertIn('does not show Result: PASS', out.stderr)

    def test_d_missing_receipt_is_refused(self):
        (self.d / 'REHEARSAL.md').unlink()
        out = self.gate()
        self.assertNotEqual(out.returncode, 0)
        self.assertIn('no REHEARSAL.md', out.stderr)

    def test_release_and_release_live_call_the_gate(self):
        release = (HERE / 'release').read_text()
        live = (HERE / 'release-live.sh').read_text()
        self.assertIn('check-rehearsal.sh', release)
        self.assertIn('check-rehearsal.sh', live)
        self.assertIn("grep -q '\\*\\*Result: PASS\\*\\*' \"$HERE/REHEARSAL.md\"", live)   # b323's check is kept
        # the gate runs before anything is stopped (release step 1 / release-live pre-flight)
        self.assertLess(release.index('check-rehearsal.sh'), release.index('--phase stop-backup"'))
        self.assertLess(live.index('check-rehearsal.sh'), live.index('1/4 stop-backup'))
        rehearse = (HERE / 'rehearse-host.sh').read_text()
        self.assertIn('rehearsal_binding_lines "$D"', rehearse)


class ExtraSteps(TmpCase):
    def make(self, body):
        x = self.work / 'staging/extra'
        x.mkdir(parents=True, exist_ok=True)
        (x / 'extra-steps.sh').write_text('#!/bin/bash\nset -euo pipefail\n' + body)
        return x

    def test_f_env_cwd_and_exit_code(self):
        x = self.make('{ echo "SRV=$SRV"; echo "WZ=$WZ"; echo "SQL=$SQL_CMD"; echo "REH=$REHEARSAL"; echo "PWD=$PWD"; '
                      'echo "ST=$ST"; echo "ED=$EXTRA_DIR"; } > "$EXTRA_DIR/env.out"; exit 0\n')
        out = sh('rehearsal_sandbox() { "$@"; }\nREHEARSAL=1 SRV="$2" SQL_CMD="mysql scratch" run_extra_steps "$1" "$3"', x, self.work / 'srv x', self.work / 'o.txt')
        self.assertEqual(out.returncode, 0, out.stderr)
        env = dict(l.split('=', 1) for l in (x / 'env.out').read_text().splitlines())
        self.assertEqual(env['SRV'], str(self.work / 'srv x'))
        self.assertEqual(env['WZ'], str(self.work / 'srv x') + '/wz')
        self.assertEqual(env['SQL'], 'mysql scratch')
        self.assertEqual(env['REH'], '1')
        self.assertEqual(env['PWD'], str(x))
        self.assertEqual(env['ST'], str(x.parent))
        self.assertEqual(env['ED'], str(x))
        self.assertIn('extra steps exit 0', out.stdout)

    def test_f_nonzero_exit_fails_the_run_and_is_recorded(self):
        x = self.make('echo before-the-failure; exit 7\n')
        out = sh('run_extra_steps "$1" "$2"', x, self.work / 'o.txt', env={'SRV': str(self.work / 's')})
        self.assertEqual(out.returncode, 7)
        self.assertIn('extra steps exit 7', out.stdout)
        self.assertIn('before-the-failure', (self.work / 'o.txt').read_text())

    def test_f_live_defaults_when_not_a_rehearsal(self):
        x = self.make('echo "$SRV|$SQL_CMD|$REHEARSAL" > "$EXTRA_DIR/env.out"\n')
        env = {k: v for k, v in os.environ.items() if k not in ('SRV', 'SQL_CMD', 'REHEARSAL')}
        out = subprocess.run(['bash', '-c', f'. "{LIB}"; run_extra_steps "$1" "$2"', '_', str(x), str(self.work / 'o')],
                             text=True, capture_output=True, env=env)
        self.assertEqual(out.returncode, 0, out.stderr)
        got = (x / 'env.out').read_text().strip()
        self.assertTrue(got.startswith('/opt/mapleroot/MapleRoot Full Repack/Server|docker exec -i buns-clean-db-1 '), got)
        self.assertTrue(got.endswith('|0'))

    def test_defaults_equal_the_deploy_body_defaults(self):
        # release night (live defaults here) and deploy.sh (templates/deploy.body.sh) must name the same server and SQL command
        probe = (f'. "{LIB}"; body="{HERE}/templates/deploy.body.sh"; '
                 'eval "$(grep -m1 "^SRV=" "$body")"; eval "$(grep -m1 "^SQL_CMD=" "$body")"; '
                 '[ "$SRV" = "$LIVE_SRV_DEFAULT" ] && [ "$SQL_CMD" = "$LIVE_SQL_CMD_DEFAULT" ] && echo same || { echo "$SRV|$SQL_CMD"; echo "$LIVE_SRV_DEFAULT|$LIVE_SQL_CMD_DEFAULT"; }')
        env = {k: v for k, v in os.environ.items() if k not in ('SRV', 'SQL_CMD')}
        out = subprocess.run(['bash', '-c', probe], text=True, capture_output=True, env=env)
        self.assertEqual(out.stdout.strip(), 'same', out.stdout + out.stderr)

    def test_rehearsal_runs_through_the_read_only_wrapper_and_live_does_not(self):
        x = self.make('echo ran > "$EXTRA_DIR/ran"\n')
        stub = ('rehearsal_sandbox() { echo "wrapped $*" >> "$MARK"; "$@"; }\n')
        mark = self.work / 'mark'
        env = {'SRV': str(self.work / 's'), 'MARK': str(mark)}
        self.assertEqual(sh(stub + 'REHEARSAL=1 run_extra_steps "$1" "$2"', x, self.work / 'o', env=env).returncode, 0)
        self.assertIn('wrapped env', mark.read_text())
        mark.unlink()
        (x / 'ran').unlink()
        self.assertEqual(sh(stub + 'REHEARSAL=0 run_extra_steps "$1" "$2"', x, self.work / 'o', env=env).returncode, 0)
        self.assertTrue((x / 'ran').exists())
        self.assertFalse(mark.exists())   # live path: not wrapped

    def test_a_write_to_live_inside_the_sandbox_fails_the_step(self):
        x = self.make('echo x > "$LIVEFILE"\n')   # stands in for a write to live that the sandbox turns into EROFS
        stub = 'rehearsal_sandbox() { chmod a-w "$LIVEDIR"; "$@"; }\n'
        live = self.work / 'live'
        live.mkdir()
        self.addCleanup(lambda: live.chmod(0o755))
        out = sh(stub + 'REHEARSAL=1 run_extra_steps "$1" "$2"', x, self.work / 'o',
                 env={'SRV': str(self.work / 's'), 'LIVEDIR': str(live), 'LIVEFILE': str(live / 'f')})
        if os.geteuid() != 0:   # root ignores directory modes; the property under test is "non-zero exit fails the run"
            self.assertNotEqual(out.returncode, 0)
        self.assertEqual(sh('rehearsal_sandbox() { false; }\nREHEARSAL=1 run_extra_steps "$1" "$2"', x, self.work / 'o2',
                            env={'SRV': str(self.work / 's')}).returncode, 1)

    def test_default_sandbox_is_systemd_run_with_live_read_only(self):
        out = sh('systemd-run() { printf "%s\\n" "$@"; }\nREHEARSAL_LIVE_SRV="/x/live srv" rehearsal_sandbox env A=1 bash t.sh')
        args = out.stdout.splitlines()
        for want in ('--wait', '--pipe', '--collect', 'ReadOnlyPaths=/x/live srv', 'ReadOnlyPaths=/opt/mapleroot', 'env', 'bash'):
            self.assertIn(want, args)

    def test_hard_coded_live_path_is_refused(self):
        x = self.make('W="/opt/mapleroot/MapleRoot Full Repack/Server/wz"; echo would-write "$W"\n')
        out = sh('run_extra_steps "$1" "$2"', x, self.work / 'o.txt', env={'SRV': str(self.work / 's')})
        self.assertEqual(out.returncode, 3)
        self.assertIn('hard-code a live path', out.stderr)
        self.assertFalse((self.work / 'o.txt').exists())   # it never ran

    def test_rehearsal_pointed_at_live_is_refused(self):
        x = self.make('echo ran > "$EXTRA_DIR/ran"\n')
        out = sh('REHEARSAL=1 run_extra_steps "$1" "$2"', x, self.work / 'o.txt', env={'SRV': '/opt/mapleroot/MapleRoot Full Repack/Server'})
        self.assertEqual(out.returncode, 4)
        self.assertFalse((x / 'ran').exists())

    def test_host_script_runs_checks_and_refuses_unstaged_extra(self):
        d = self.work / 'v1.2.3'
        x = d / 'staging/extra'
        x.mkdir(parents=True)
        (x / 'extra-steps.sh').write_text('echo "ran in $PWD"; exit "${FAKE_RC:-0}"\n')
        subprocess.run('sha256sum extra/extra-steps.sh > SHA256SUMS', shell=True, cwd=d / 'staging', check=True)
        run = lambda *a, **e: subprocess.run(['bash', str(HERE / 'extra-steps-host.sh'), str(d), *a], text=True,
                                             capture_output=True, env={**os.environ, 'SRV': str(self.work / 's'), **e})
        self.assertEqual(run('--check').returncode, 0)
        self.assertEqual(run().returncode, 0)
        self.assertEqual(run(FAKE_RC='5').returncode, 5)
        (d / 'extra').mkdir()
        (d / 'extra/extra-steps.sh').write_text('echo hand-placed\n')
        out = run()
        self.assertNotEqual(out.returncode, 0)
        self.assertIn('outside staging', out.stderr)

    def test_release_and_release_live_run_the_same_helper_as_the_rehearsal(self):
        self.assertIn('run_extra_steps', (HERE / 'rehearse-host.sh').read_text())
        self.assertIn('run_extra_steps', (HERE / 'extra-steps-host.sh').read_text())
        self.assertIn('extra-steps-host.sh', (HERE / 'release-live.sh').read_text())
        release = (HERE / 'release').read_text()
        self.assertIn('extra-steps-host.sh', release)
        self.assertLess(release.index('# ---- 2b.'), release.index('# ---- 3. client publish'))
        rehearse = (HERE / 'rehearse-host.sh').read_text()
        # release night's order: stop-backup, extra steps, apply-start
        self.assertLess(rehearse.index('--phase stop-backup'), rehearse.index('run_extra_steps "$D/staging/extra"'))
        self.assertLess(rehearse.index('run_extra_steps "$D/staging/extra"'), rehearse.index('--phase apply-start'))

    def test_stage_extras_copies_and_refuses(self):
        repo = self.work / 'repo'
        (repo / 'docs').mkdir(parents=True)
        (repo / 'tools').mkdir()
        (repo / 'docs/x.sh').write_text('echo "$WZ"\n')
        (repo / 'tools/helper.py').write_text('print(1)\n')
        (repo / 'tools/Map2.xml').write_text('<new/>')
        conf = self.work / 'r.conf'
        conf.write_text('EXTRA_STEPS=docs/x.sh\nEXTRA_FILES=(tools/helper.py)\nWZ_TARGETS=(Map.wz/a.xml)\n'
                        'WZ_REPLACE=("tools/Map2.xml=Map.wz/Map/Map2.xml")\n')
        st = self.work / 'st'
        st.mkdir()
        out = sh('stage_extras "$1" "$2" "$3"', conf, repo, st)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual((st / 'extra/extra-steps.sh').read_text(), 'echo "$WZ"\n')
        self.assertTrue((st / 'extra/helper.py').exists())
        self.assertEqual((st / 'wz/Map.wz/Map/Map2.xml').read_text(), '<new/>')
        self.assertEqual((st / 'wz-targets.txt').read_text().split(), ['Map.wz/Map/Map2.xml', 'Map.wz/a.xml'])
        # no extras, no WZ: nothing is staged
        conf.write_text('X=1\n')
        st2 = self.work / 'st2'
        st2.mkdir()
        self.assertEqual(sh('stage_extras "$1" "$2" "$3"', conf, repo, st2).returncode, 0)
        self.assertEqual(list(st2.iterdir()), [])
        # a live path in the script, a bad WZ target
        (repo / 'docs/x.sh').write_text('cd /opt/mapleroot/foo\n')
        conf.write_text('EXTRA_STEPS=docs/x.sh\n')
        self.assertNotEqual(sh('stage_extras "$1" "$2" "$3"', conf, repo, self.work / 'st3').returncode, 0)
        conf.write_text('WZ_TARGETS=(../etc/passwd)\n')
        st4 = self.work / 'st4'
        st4.mkdir()
        self.assertNotEqual(sh('stage_extras "$1" "$2" "$3"', conf, repo, st4).returncode, 0)


class WzCopy(TmpCase):
    def setUp(self):
        super().setUp()
        self.live = self.work / 'live/wz'
        files = {'Map.wz/Map/Map2/a.xml': 'A', 'Map.wz/Map/Map2/b.xml': 'B', 'Npc.wz/c.xml': 'C', 'String.wz/Npc.img.xml': 'S'}
        for rel, text in files.items():
            p = self.live / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text)
            p.chmod(0o444)
        self.addCleanup(lambda: [os.chmod(p, 0o755) for p in self.live.rglob('*') if p.is_dir()])
        self.before = {str(p): (sha(p), p.stat().st_ino, p.stat().st_mtime_ns, p.stat().st_nlink) for p in self.live.rglob('*') if p.is_file()}
        self.dst = self.work / 'scratch/wz'
        self.targets = self.work / 'targets.txt'

    def setup_wz(self, lines):
        self.targets.write_text('\n'.join(lines) + '\n')
        return sh('rehearsal_wz_setup "$1" "$2" "$3"', self.live, self.dst, self.targets)

    def test_only_listed_files_are_copied_the_rest_symlinked_and_live_never_written(self):
        out = self.setup_wz(['Map.wz/Map/Map2/a.xml', 'Npc.wz/new-npc.xml'])   # one existing target, one a step creates
        self.assertEqual(out.returncode, 0, out.stderr)
        a = self.dst / 'Map.wz/Map/Map2/a.xml'
        self.assertFalse(a.is_symlink())
        self.assertEqual(a.read_text(), 'A')
        self.assertNotEqual(a.stat().st_ino, (self.live / 'Map.wz/Map/Map2/a.xml').stat().st_ino)   # a copy, not a hard link
        self.assertEqual((self.live / 'Map.wz/Map/Map2/a.xml').stat().st_nlink, 1)
        for rel in ('Map.wz/Map/Map2/b.xml', 'Npc.wz/c.xml', 'String.wz/Npc.img.xml'):
            p = self.dst / rel
            self.assertTrue(p.is_symlink(), rel)
            self.assertEqual(os.path.realpath(p), os.path.realpath(self.live / rel))
        self.assertFalse((self.dst / 'Npc.wz/new-npc.xml').exists())
        self.assertFalse((self.dst / 'Npc.wz').is_symlink())   # directories are real, so a new file lands in the scratch copy
        # a step writing the target changes only the scratch copy
        a.write_text('A-patched')
        (self.dst / 'Npc.wz/new-npc.xml').write_text('N')
        after = {str(p): (sha(p), p.stat().st_ino, p.stat().st_mtime_ns, p.stat().st_nlink) for p in self.live.rglob('*') if p.is_file()}
        self.assertEqual(self.before, after)
        self.assertFalse((self.live / 'Npc.wz/new-npc.xml').exists())

    def test_bad_targets_and_symlinked_parents_are_refused(self):
        for bad in ('../x.xml', '/etc/passwd', 'a//b.xml', ''):
            if not bad:
                continue
            shutil.rmtree(self.dst.parent, ignore_errors=True)
            self.assertNotEqual(self.setup_wz([bad]).returncode, 0, bad)
        shutil.rmtree(self.dst.parent, ignore_errors=True)
        # a live wz whose sub folder is itself a symlink out of wz/: writing through it would hit another tree
        elsewhere = self.work / 'elsewhere'
        elsewhere.mkdir()
        (elsewhere / 'z.xml').write_text('Z')
        os.chmod(self.live, 0o755)
        (self.live / 'Linked.wz').symlink_to(elsewhere)
        out = self.setup_wz(['Linked.wz/z.xml'])
        self.assertNotEqual(out.returncode, 0)
        self.assertEqual((elsewhere / 'z.xml').read_text(), 'Z')

    def test_wz_hashes_show_what_changed(self):
        self.setup_wz(['Map.wz/Map/Map2/a.xml'])
        h0 = sh('wz_hashes "$1" "$2"', self.dst, self.targets).stdout
        (self.dst / 'Map.wz/Map/Map2/a.xml').write_text('other')
        h1 = sh('wz_hashes "$1" "$2"', self.dst, self.targets).stdout
        self.assertNotEqual(h0, h1)
        self.assertEqual(h0, sh('wz_hashes "$1" "$2"', self.live, self.targets).stdout)   # the copy started equal to live


class GeneratedScripts(TmpCase):
    def generate(self, conf):
        (self.work / 'release.conf').write_text(conf)
        (self.work / 'BUILD.txt').write_text('commit=fixture\nversion=1.5.0\njar_sha256=1234567890abcdef\n')
        return subprocess.run([str(HERE / 'gen-deploy'), str(self.work / 'release.conf'), str(self.work), str(self.work / 'out'), '1.5.0'],
                              text=True, capture_output=True)

    def test_wz_steps_are_backed_up_before_apply_and_restored_by_rollback(self):
        res = self.generate('LEDGER_BEFORE=""\n')
        self.assertEqual(res.returncode, 0, res.stderr)
        deploy = (self.work / 'out/deploy.sh').read_text()
        rollback = (self.work / 'out/rollback.sh').read_text()
        backup = deploy.index('mkdir -p "$B/wz-backup"')
        apply = deploy.index('touch "$B/.apply-start.started"')
        replace = deploy.index('cp -p "$ST/wz/$rel" "$SRV/wz/$rel"')
        start = deploy.index('step "start"', apply)
        self.assertLess(backup, apply)
        self.assertLess(apply, replace)
        self.assertLess(replace, start)
        self.assertLess(rollback.index('wz: $(wc -l < "$WZT") target file(s) restored'), rollback.index('step "start"'))
        self.assertIn('wz_restore', deploy[deploy.index('PHASE" = restart-old'):deploy.index('PHASE" = apply-start')])

    def test_unit_edits_go_through_unit_edit_and_flags_are_baked(self):
        res = self.generate('LEDGER_BEFORE=""\nUNIT_EDITS=("s# -Dslums.some.flag=true##g")\nUNIT_FLAGS_ABSENT=(-Dslums.some.flag)\n')
        self.assertEqual(res.returncode, 0, res.stderr)
        deploy = (self.work / 'out/deploy.sh').read_text()
        self.assertIn("UNIT_EDITS=('s# -Dslums.some.flag=true##g')", deploy)
        self.assertIn("UNIT_FLAGS_ABSENT=('-Dslums.some.flag')", deploy)
        self.assertIn('"$ST/unit-edit" --check "$UNIT_FILE"', deploy)
        self.assertIn('"$ST/unit-edit" --apply "$UNIT_FILE"', deploy)
        self.assertNotIn('sed -i -e "$e" "$UNIT_FILE"', deploy)
        self.assertLess(deploy.index('--check "$UNIT_FILE"'), deploy.index('touch "$B/.apply-start.started"'))   # before the stop
        self.assertIn('"$here/unit-edit"', (HERE / 'stage').read_text())

    def test_bad_unit_edit_config_is_refused_at_generation(self):
        self.assertNotEqual(self.generate('LEDGER_BEFORE=""\nUNIT_EDITS=("s#unterminated")\n').returncode, 0)
        self.assertNotEqual(self.generate('LEDGER_BEFORE=""\nUNIT_EDITS=("s#a#b#")\nUNIT_FLAGS_ABSENT=(notaflag)\n').returncode, 0)
        self.assertNotEqual(self.generate('LEDGER_BEFORE=""\nUNIT_FLAGS_ABSENT=(-Dslums.x)\n').returncode, 0)


class UnitEdit(TmpCase):
    UNIT = ('[Service]\nExecStart=/usr/bin/java -Xmx4G -Dslums.keep=1 -Dslums.some.flag=true '
            '-Dslums.exchange.tickers=NX,LEAF -jar target/Cosmic.jar\nTimeoutStopSec=300\n')

    def setUp(self):
        super().setUp()
        self.unit = self.work / 'slumsms.service'
        self.unit.write_text(self.UNIT)

    def run_edit(self, mode, *args):
        return subprocess.run([str(HERE / 'unit-edit'), mode, str(self.unit), *args], text=True, capture_output=True)

    def test_e_removal_removes_exactly_that_flag(self):
        out = self.run_edit('--apply', '--absent', '-Dslums.some.flag', '--', 's/ -Dslums.some.flag=true//')
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(self.unit.read_text(), self.UNIT.replace(' -Dslums.some.flag=true', ''))
        self.assertIn('-Dslums.keep=1', self.unit.read_text())
        self.assertIn('-Dslums.exchange.tickers=NX,LEAF', self.unit.read_text())

    def test_e_check_changes_nothing(self):
        out = self.run_edit('--check', '--', 's/ -Dslums.some.flag=true//')
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(self.unit.read_text(), self.UNIT)

    def test_e_no_op_edit_is_refused_and_leaves_the_unit(self):
        for mode in ('--check', '--apply'):
            out = self.run_edit(mode, '--', 's/ -Dslums.not.there=true//')
            self.assertNotEqual(out.returncode, 0)
            self.assertIn('changes nothing', out.stderr)
            self.assertEqual(self.unit.read_text(), self.UNIT)

    def test_e_a_no_op_after_an_earlier_edit_consumed_its_target_is_refused(self):
        out = self.run_edit('--check', '--', 's/ -Dslums.some.flag=true//', 's/ -Dslums.some.flag=true//')
        self.assertNotEqual(out.returncode, 0)
        self.assertIn('edit 2', out.stderr)

    def test_e_b328_a_removal_that_misses_the_flags_value_is_caught_by_flags_absent(self):
        # b328: the tickers flag stayed on live. Here the pattern names another value, so sed changes nothing for it; with an
        # earlier edit that does change something, "an edit changed something" would pass without UNIT_FLAGS_ABSENT.
        edits = ('s/-Xmx4G/-Xmx6G/', 's/ -Dslums.exchange.tickers=NX,LEAF,CHAOS//')
        self.assertNotEqual(self.run_edit('--check', '--', *edits).returncode, 0)   # second edit is a no-op: refused anyway
        edits = ('s/-Xmx4G/-Xmx6G/', 's/tickers=NX,LEAF/tickers=NX/')   # both change text, the flag is still there
        out = self.run_edit('--check', '--absent', '-Dslums.exchange.tickers', '--', *edits)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn('-Dslums.exchange.tickers is still on the unit', out.stderr)
        self.assertEqual(self.unit.read_text(), self.UNIT)

    def test_present_flag_must_be_there(self):
        out = self.run_edit('--check', '--present', '-Dslums.new', '--', 's/ -Dslums.some.flag=true//')
        self.assertNotEqual(out.returncode, 0)
        ok = self.run_edit('--check', '--present', '-Dslums.new', '--', 's# -jar # -Dslums.new=true -jar #')
        self.assertEqual(ok.returncode, 0, ok.stderr)

    def test_invalid_sed_and_flag_names_are_refused(self):
        self.assertNotEqual(self.run_edit('--check', '--', 's/unterminated').returncode, 0)
        self.assertNotEqual(self.run_edit('--check', '--absent', 'slums.x', '--', 's/a/b/').returncode, 0)

    def test_apply_keeps_the_inode_and_mode(self):
        self.unit.chmod(0o640)
        ino = self.unit.stat().st_ino
        self.assertEqual(self.run_edit('--apply', '--', 's/ -Dslums.some.flag=true//').returncode, 0)
        st = self.unit.stat()
        self.assertEqual(st.st_ino, ino)
        self.assertEqual(stat.S_IMODE(st.st_mode), 0o640)


class DeployDryRun(TmpCase):
    """The generated deploy.sh --dry-run on a scratch server with a stub SQL command: no host, no live path, nothing stopped."""

    def run_dry(self, conf, unit, wz_live=(), wz_staged=None, extra_env=None, args=('--dry-run',)):
        w = self.work
        (w / 'release.conf').write_text('LEDGER_BEFORE="a,b"\n' + conf)
        (w / 'BUILD.txt').write_text('commit=fixture\nversion=1.5.0\njar_sha256=1234567890abcdef\n')
        gen = subprocess.run([str(HERE / 'gen-deploy'), str(w / 'release.conf'), str(w), str(w / 'out'), '1.5.0'], text=True, capture_output=True)
        self.assertEqual(gen.returncode, 0, gen.stderr)
        srv, st = w / 'srv', w / 'out/staging'
        for d in ('target', 'scripts', 'src/main', 'wz'):
            (srv / d).mkdir(parents=True, exist_ok=True)
        (srv / 'config.yaml').write_text('server:\n  X: 1\n')
        (srv / 'target/Cosmic.jar').write_bytes(b'old')
        for rel, text in dict(wz_live).items():
            (srv / 'wz' / rel).parent.mkdir(parents=True, exist_ok=True)
            (srv / 'wz' / rel).write_text(text)
        st.mkdir(parents=True)
        shutil.copy(HERE / 'unit-edit', st / 'unit-edit')
        for name, text in (('Cosmic.jar', 'new'), ('scripts.list', ''), ('srcdata.list', '')):
            (st / name).write_text(text)
        if wz_staged is not None:
            (st / 'wz-targets.txt').write_text('\n'.join(wz_staged) + '\n')
        subprocess.run('find . -type f ! -name SHA256SUMS -printf "%P\\n" | LC_ALL=C sort | xargs -d "\\n" sha256sum > SHA256SUMS', shell=True, cwd=st, check=True)
        sql = w / 'sql.sh'
        sql.write_text('#!/bin/bash\ninput=$(cat); case "$input" in *GROUP_CONCAT*) echo a,b;; *) echo 0;; esac\n')
        sql.chmod(0o755)
        unit_file = w / 'slumsms.service'
        unit_file.write_text(unit)
        env = {**os.environ, 'SRV': str(srv), 'ST': str(st), 'B': str(w / 'backup'), 'LOG': str(w / 'dry.log'), 'RECEIPT': str(w / 'r.md'),
               'SQL_CMD': str(sql), 'IS_ACTIVE_CMD': 'true', 'STOP_CMD': 'false', 'START_CMD': 'false', 'DUMP_CMD': 'false',
               'DAEMON_RELOAD_CMD': 'true', 'MONITOR': '', 'UNIT_FILE': str(unit_file), **(extra_env or {})}
        return subprocess.run(['bash', str(w / 'out/deploy.sh'), *args], text=True, capture_output=True, env=env, cwd=w)

    UNIT = 'ExecStart=/usr/bin/java -Dslums.a=1 -Dslums.old=true -jar target/Cosmic.jar\n'

    def test_dry_run_accepts_a_removal_and_prints_it(self):
        out = self.run_dry('UNIT_EDITS=("s# -Dslums.old=true##g")\nUNIT_FLAGS_ABSENT=(-Dslums.old)\n', self.UNIT)
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
        self.assertIn("unit: sed 's# -Dslums.old=true##g'", out.stdout)
        self.assertIn('-Dslums.old must be gone afterwards', out.stdout)
        self.assertEqual((self.work / 'slumsms.service').read_text(), self.UNIT)   # a dry run edits nothing

    def test_dry_run_refuses_a_removal_that_changes_nothing(self):
        out = self.run_dry('UNIT_EDITS=("s# -Dslums.gone=true##g")\n', self.UNIT)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn('changes nothing', out.stdout + out.stderr)
        self.assertIn('unit edits do not apply cleanly', out.stdout)

    def test_dry_run_refuses_when_the_flag_is_still_there(self):
        out = self.run_dry('UNIT_EDITS=("s#-Dslums.a=1#-Dslums.a=2#")\nUNIT_FLAGS_ABSENT=(-Dslums.old)\n', self.UNIT)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn('-Dslums.old is still on the unit', out.stdout + out.stderr)

    def test_dry_run_with_wz_targets_reports_them_and_flags_a_missing_one(self):
        out = self.run_dry('', self.UNIT, wz_live={'Map.wz/a.xml': 'A'}, wz_staged=['Map.wz/a.xml', 'Npc.wz/new.xml'])
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
        self.assertIn('wz: back up 2 target file(s)', out.stdout)
        self.assertIn('wz target Npc.wz/new.xml is not in', out.stdout)

    def test_stop_backup_saves_the_wz_targets_and_restart_old_puts_back_what_a_step_half_wrote(self):
        out = self.run_dry('', self.UNIT, wz_live={'Map.wz/a.xml': 'A-live'}, wz_staged=['Map.wz/a.xml', 'Npc.wz/new.xml'],
                           args=('--phase', 'stop-backup'),
                           extra_env={'IS_ACTIVE_CMD': 'false', 'STOP_CMD': 'true', 'DUMP_CMD': 'echo "-- Dump completed"'})
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
        b, srv = self.work / 'backup', self.work / 'srv'
        self.assertEqual((b / 'wz-backup/Map.wz/a.xml').read_text(), 'A-live')
        self.assertEqual((b / 'wz-absent.txt').read_text().split(), ['Npc.wz/new.xml'])
        # the extra step half-wrote the declared files
        (srv / 'wz/Map.wz/a.xml').write_text('A-half-patched')
        (srv / 'wz/Npc.wz').mkdir()
        (srv / 'wz/Npc.wz/new.xml').write_text('N')
        env = {**os.environ, 'SRV': str(srv), 'ST': str(self.work / 'out/staging'), 'B': str(b), 'LOG': str(self.work / 'ro.log'),
               'IS_ACTIVE_CMD': 'false', 'START_CMD': 'true', 'ONLINE_TIMEOUT': '1', 'MONITOR': '', 'JOURNAL_UNIT': 'no-such-unit-p1r1'}
        subprocess.run(['bash', str(self.work / 'out/deploy.sh'), '--phase', 'restart-old'], text=True, capture_output=True, env=env, cwd=self.work)
        self.assertEqual((srv / 'wz/Map.wz/a.xml').read_text(), 'A-live')
        self.assertFalse((srv / 'wz/Npc.wz/new.xml').exists())


if __name__ == '__main__':
    unittest.main()
