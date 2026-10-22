"""Local recovery regressions; all shell commands use workspace fixtures and stubs."""
import hashlib
import os
from pathlib import Path
import re
import shlex
import sqlite3
import subprocess
import tarfile
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
MIGRATIONS = ROOT / 'database/migrations'
IDS = ['011-bot-affinity', '012-bot-couples', '013-gachapon-machines',
       '014-bot-guilds', '020-economy-trade', '021-cosmetics-merchant']


def statements(path):
    """Adapt MySQL DDL spelling for SQLite; execute the actual DML unmodified."""
    sql = re.sub(r'--[^\n]*', '', path.read_text())
    sql = re.sub(r'\) ENGINE\s*=\s*InnoDB', ')', sql)
    sql = re.sub(r'\bUNSIGNED\b|\bAUTO_INCREMENT\b', '', sql)
    sql = re.sub(r'UNIQUE KEY \w+ ', 'UNIQUE ', sql)
    sql = re.sub(r',\s*KEY \w+ \([^)]*\)', '', sql)
    sql = sql.replace('INSERT IGNORE', 'INSERT OR IGNORE')
    sql = sql.replace('START TRANSACTION', 'BEGIN').replace('DROP TEMPORARY TABLE', 'DROP TABLE')
    return [s.strip() for s in sql.split(';') if s.strip()]


def execute(db, stmts):
    apply = 0
    for stmt in stmts:
        if stmt.startswith('SET @apply = '):
            apply = db.execute('SELECT ' + stmt.removeprefix('SET @apply = ')).fetchone()[0]
        else:
            db.execute(stmt, {'apply': apply})


def database():
    db = sqlite3.connect(':memory:')
    db.executescript('''
        CREATE TABLE mapleroot_integration_migrations (
          migration_id varchar(100) PRIMARY KEY, applied_at timestamp DEFAULT CURRENT_TIMESTAMP);
        CREATE TABLE plife (id INTEGER PRIMARY KEY AUTOINCREMENT, world INT, map INT, life INT,
          type TEXT, cy INT, f INT, fh INT, rx0 INT, rx1 INT, x INT, y INT, hide INT, mobtime INT, team INT);
        CREATE TABLE characters (guildid INT, guildrank INT, allianceRank INT);
        CREATE TABLE bbs_threads (threadid INT, guildid INT);
        CREATE TABLE bbs_replies (threadid INT);
        CREATE TABLE guilds (guildid INT);
    ''')
    return db


class MigrationRecoveryTest(unittest.TestCase):
    def test_owned_rows_only_idempotent_forward_and_rollback(self):
        for name, map_id, npc, count in [('013-gachapon-machines', 101000000, 9100101, 11),
                                          ('021-cosmetics-merchant', 910000000, 9209006, 1)]:
            with self.subTest(name=name), database() as db:
                # Matching NPC before migration, plus an unrelated permanent NPC.
                db.execute('INSERT INTO plife (world,map,life,type) VALUES (0,?,?,\'n\')', (map_id, npc))
                db.execute("INSERT INTO plife (world,map,life,type) VALUES (1,100000000,9999999,'n')")
                db.commit()
                before = db.execute('SELECT * FROM plife').fetchall()
                forward = statements(MIGRATIONS / (name + '.sql'))
                rollback = statements(MIGRATIONS / (name + '.rollback.sql'))
                execute(db, forward)
                owned = db.execute('SELECT row_id FROM mapleroot_integration_migration_rows').fetchall()
                self.assertEqual(len(owned), count)
                self.assertNotIn((before[0][0],), owned)
                execute(db, forward)
                self.assertEqual(db.execute('SELECT COUNT(*) FROM plife').fetchone()[0], count + 2)
                execute(db, rollback)
                execute(db, rollback)
                self.assertEqual(db.execute('SELECT * FROM plife').fetchall(), before)
                execute(db, forward)
                self.assertEqual(db.execute('SELECT COUNT(*) FROM plife').fetchone()[0], count + 2)

    def test_rollback_after_every_partial_forward_prefix(self):
        for name in IDS:
            forward = statements(MIGRATIONS / (name + '.sql'))
            rollback = statements(MIGRATIONS / (name + '.rollback.sql'))
            for cut in range(len(forward) + 1):
                with self.subTest(name=name, cut=cut), database() as db:
                    execute(db, forward[:cut])
                    # MySQL DDL commits independently; a failed client connection
                    # rolls back its still-open DML transaction before recovery.
                    db.rollback()
                    execute(db, rollback)
                    execute(db, rollback)
                    self.assertEqual(db.execute('SELECT COUNT(*) FROM mapleroot_integration_migrations').fetchone()[0], 0)
                    self.assertEqual(db.execute('SELECT COUNT(*) FROM plife').fetchone()[0], 0)


class ShellRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=ROOT)
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)

    def shell(self, script, **extra):
        return subprocess.run(['bash', '-c', script], cwd=self.work, text=True,
                              capture_output=True, env={**os.environ, **extra})

    def test_stop_precedes_manifest_restore_even_when_restore_fails(self):
        source = (HERE / 'release').read_text()
        block = source[source.index('if [ "$ok" = 0 ]; then'):source.index('# ---- 5.')]
        for restore_ok in (True, False):
            trace = self.work / 'trace'
            script = '''
set -euo pipefail
ok=0 NOCLIENT=0 D=fixture
q() { printf '%q' "$1"; }
event() { :; }
summary() { :; }
hx() {
  if [[ $1 == *STOP_CMD* ]]; then
    echo stop >> "$TRACE"
    eval "$1"
  else
    echo rollback >> "$TRACE"
  fi
}
restore_manifest() { echo restore >> "$TRACE"; return "$RESTORE_STATUS"; }
stuck() { echo stuck >> "$TRACE"; exit 1; }
''' + block
            result = self.shell(script, TRACE=str(trace), STOP_CMD='true', IS_ACTIVE_CMD='false',
                                RESTORE_STATUS='0' if restore_ok else '1')
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertEqual(trace.read_text().splitlines(),
                             ['stop', 'restore', 'rollback' if restore_ok else 'stuck'])
            trace.unlink()

    def test_stop_failure_prevents_restore_and_reports_unknown_state(self):
        source = (HERE / 'release').read_text()
        block = source[source.index('if [ "$ok" = 0 ]; then'):source.index('# ---- 5.')]
        result = self.shell('''
set -euo pipefail
ok=0 NOCLIENT=0 D=fixture
hx() { return 1; }
event() { echo "$*"; }
summary() { echo "$*"; }
restore_manifest() { echo UNEXPECTED_RESTORE; }
''' + block)
        self.assertEqual(result.returncode, 1)
        self.assertIn('server state unknown', result.stdout)
        self.assertNotIn('UNEXPECTED_RESTORE', result.stdout)

    def rollback_fixture(self):
        srv, backup = self.work / 'server', self.work / 'backup'
        old = self.work / 'old'
        for base, value in [(srv, 'new'), (old, 'old')]:
            for path in ['target/Cosmic.jar', 'config.yaml', 'scripts/npc.js', 'src/main/data.yaml']:
                p = base / path
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(value)
        backup.mkdir()
        with tarfile.open(backup / 'server-runtime.tgz', 'w:gz') as archive:
            for path in ['target/Cosmic.jar', 'config.yaml', 'scripts', 'src/main']:
                archive.add(old / path, arcname=path)
        (backup / 'config.yaml').write_text('old')
        digest = hashlib.sha256(b'old').hexdigest()
        (backup / 'old-jar.sha256').write_text(f'{digest}  {srv}/target/Cosmic.jar\n')
        bin_dir = self.work / 'bin'
        bin_dir.mkdir()
        (bin_dir / 'journalctl').write_text('#!/bin/bash\necho "is now online"\n')
        (bin_dir / 'journalctl').chmod(0o755)
        header = f'''
D={shlex.quote(str(self.work))}
VERSION=fixture LEDGER_BEFORE=''
MIGRATION_IDS=() MIGRATION_FILES=() UNIT_EDITS=()
'''
        script = self.work / 'rollback.sh'
        script.write_text(header + (HERE / 'templates/rollback.body.sh').read_text())
        env = dict(SRV=str(srv), B=str(backup), MONITOR='', SQL_CMD="cat >/dev/null; echo ''",
                   STOP_CMD='true', START_CMD='true', IS_ACTIVE_CMD='false',
                   PATH=str(bin_dir) + os.pathsep + os.environ['PATH'])
        return script, srv, backup, env

    def test_interrupted_runtime_moves_can_resume_and_repeat(self):
        script, srv, backup, env = self.rollback_fixture()
        body = script.read_text()
        # Process death between moving scripts away and recording that move.
        script.write_text(body.replace('touch "$B/.rollback-$marker.saved"',
                                      'exit 76 # simulate death after the first move'))
        result = self.shell(f'bash {shlex.quote(str(script))}', **env)
        self.assertEqual(result.returncode, 76, result.stdout + result.stderr)
        self.assertFalse((srv / 'scripts').exists())
        self.assertEqual((backup / 'rolled-back-runtime/scripts/npc.js').read_text(), 'new')
        # Resume, then die after the rename into place but before its marker.
        script.write_text(body.replace('touch "$B/.rollback-$marker.restored"',
                                      'exit 77 # simulate death after rename'))
        result = self.shell(f'bash {shlex.quote(str(script))}', **env)
        self.assertEqual(result.returncode, 77, result.stdout + result.stderr)
        self.assertEqual((srv / 'scripts/npc.js').read_text(), 'old')
        script.write_text(body)
        for _ in range(2):
            result = self.shell(f'bash {shlex.quote(str(script))}', **env)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        for path in ['target/Cosmic.jar', 'config.yaml', 'scripts/npc.js', 'src/main/data.yaml']:
            self.assertEqual((srv / path).read_text(), 'old')
            self.assertEqual((backup / 'rolled-back-runtime' / path).read_text(), 'new')

    def test_attempted_unledgered_migrations_reverse_and_dump_guidance(self):
        script, _, backup, env = self.rollback_fixture()
        stage = self.work / 'staging/migrations'
        stage.mkdir(parents=True)
        names = IDS[:2]
        header = 'MIGRATION_IDS=(' + ' '.join(names) + ')\nMIGRATION_FILES=(' + ' '.join(names) + ')\n'
        script.write_text(script.read_text().replace('MIGRATION_IDS=() MIGRATION_FILES=() UNIT_EDITS=()',
                                                   header + 'UNIT_EDITS=()'))
        for name in names:
            (backup / f'.migration-{name}.attempted').touch()
            (stage / f'{name}.rollback.sql').write_text(f'ROLLBACK {name}\n')
        trace = self.work / 'sql-trace'
        sql_stub = self.work / 'sql.sh'
        sql_stub.write_text('''#!/bin/bash
read -r query
case $query in
  ROLLBACK*) echo "$query" >> "$TRACE"; [ "${FAIL_SQL:-0}" = 0 ];;
  *'COUNT(*)'*) echo 0;;
  *) echo '';;
esac
''')
        env.update(SQL_CMD='bash ' + shlex.quote(str(sql_stub)), TRACE=str(trace))
        result = self.shell(f'bash {shlex.quote(str(script))}', **env)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(trace.read_text().splitlines(), ['ROLLBACK ' + n for n in reversed(names)])
        result = self.shell(f'bash {shlex.quote(str(script))}', **env, FAIL_SQL='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('server stays DOWN', result.stdout)
        self.assertIn('Full DB dump is available', result.stdout)
        self.assertIn('Import on this host: gzip -dc', result.stdout)
        self.assertIn(str(backup / 'mapleroot.sql.gz'), result.stdout)

    def test_attempt_marker_precedes_forward_sql(self):
        deploy = (HERE / 'templates/deploy.body.sh').read_text()
        self.assertLess(deploy.index('touch "$B/.migration-$id.attempted"'),
                        deploy.index('sql < "$ST/migrations/$f.sql"'))


if __name__ == '__main__':
    unittest.main()
