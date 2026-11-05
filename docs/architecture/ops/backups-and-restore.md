# Backups and restore

## Purpose

Three shell scripts and six systemd units take a nightly database dump plus a tar of the bots' learned navigation
data, rotate old deploy material behind a protected-path guard, and monthly restore the newest dump into a scratch
schema and boot a throwaway copy of the live server on spare ports to prove it still loads. Everything is plain bash
under `deploy/backup/`; nothing there stops, restarts or reconfigures the live server or its database
(`deploy/backup/README.md:3-4`). The drill is the evidence mechanism — a dump nobody has restored is not a backup —
and the guard exists because rotation is the one operation that can destroy the only good copy.

## Entry points

| Operator action | Command | Cite |
|---|---|---|
| Install/upgrade the kit on the live host | `bash install.sh [--with-drill] [--backup-now] [--undo <dir>]` | `deploy/backup/install.sh:13-15` |
| Take a backup now | `systemctl start slumsms-backup` | `deploy/backup/install.sh:75-78` |
| Run the nightly by hand | `slumsms-nightly-backup` (sources `$SLUMSMS_BACKUP_CONF`) | `deploy/backup/slumsms-nightly-backup:25-26` |
| See what rotation would delete | `slumsms-rotate-backups` (dry run is the default) | `deploy/backup/slumsms-rotate-backups:34,45-54` |
| Actually rotate | `slumsms-rotate-backups --apply` | `deploy/backup/slumsms-rotate-backups:34,206` |
| Prove the guard still holds | `bash deploy/backup/test-rotate-protect.sh` | `deploy/backup/README.md:65` |
| Restore drill by hand | `slumsms-restore-drill [--dump F] [--nav F\|--nav-from-live] [--minutes N] [--keep]` | `deploy/backup/slumsms-restore-drill:44-53` |
| Move the TEST DB datadir | `bash move-test-db-datadir.sh [--go\|--rollback]` (preflight default) | `deploy/backup/move-test-db-datadir.sh:10-12` |
| Test-server deploy (its own backup) | `tools/ops/deploy-test <commit>` | `tools/ops/deploy-test:13-14` |

Units: backup daily 11:00 UTC, `RandomizedDelaySec=5min`, `Persistent=true`, unit `Nice=10`, `IOSchedulingClass=idle`,
`TimeoutStartSec=30min` (`deploy/backup/slumsms-backup.timer:5-7`, `slumsms-backup.service:7-11`); rotation 11:30 UTC
at `Nice=19` (`slumsms-backup-rotate.timer:5-6`); drill monthly `*-*-01 12:00:00 UTC`, `TimeoutStartSec=45min`
(`slumsms-restore-drill.timer:5-6`, `slumsms-restore-drill.service:9-10`), enabled only with `install.sh --with-drill`
(`deploy/backup/README.md:14`).

## How it works

**Nightly backup** (`deploy/backup/slumsms-nightly-backup`, 137 lines):

1. **Stamp.** `TS` is UTC (`:41`); `DUMP=$BACKUP_DIR/$DB_SCHEMA-$TS.sql.gz`, `NAV=bot-nav-$TS.tar.gz` (`:42-43`); a
   `trap` removes the temp dir and any `*.part` files on any exit (`:44-45`).
2. **Dump.** `docker exec "$DB_CONTAINER" mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" $DUMP_LOCK --routines --triggers
   --databases $DB_SCHEMA | gzip > "$DUMP.part"` (`:50`), with `DUMP_LOCK=--lock-all-tables` "blocking writes for the
   ~0.5 s dump" (`:31-32`).
3. **Verify, then promote.** `zcat … | tail -1 | grep -q "Dump completed"` or `exit 1` (`:52`), then `mv` the `.part`
   into place and `chmod 600` (`:53-54`), so a truncated dump never becomes the newest file.
4. **Archive nav.** `cp -a` of `cache/bot-nav/{learned,stuck,heat,observed,players}`, skipping missing dirs (`:58-65`);
   every `*.json` parsed with `python3 -I … json.load`, re-copied up to 5 times, then "does not parse after 5 copies,
   kept as is" or "vanished, dropped" (`:66-80`); `tar --owner=0 --group=0` (`:82`), `chmod 600` (`:84`). The only
   non-database state the nightly saves.
5. **Checksums and prune.** Both files appended to `SHA256SUMS` (`:88`); newest-first, keep at least `KEEP_MIN=3`,
   delete only past `KEEP_DAYS=14` (`:33-34,90-100`); `awk` drops `SHA256SUMS` lines whose file is gone (`:102`).
6. **Off-box (optional).** `OFFBOX_DEST` empty → "off-box: disabled", exit 0 (`:36,106-109`). Otherwise gpg symmetric
   AES256 with the passphrase file `OFFBOX_KEY` (`:112-120`), a `SHA256SUMS-$TS` sidecar carrying plaintext and
   ciphertext hashes (`:122-123`), then `rsync -t --partial-dir=.partial` or `scp -q` with `BatchMode=yes`,
   `ConnectTimeout=20` (`:125-129`). A push failure exits 3 — "the local backup is fine" (`:133-135`), distinct from a
   local failure's exit 1 (`:3-19`).

**Rotation** (`deploy/backup/slumsms-rotate-backups`, 206 lines):

7. **Three sources.** A: `pre-*` under `/opt/mapleroot/backups` (`:39,104-116`). B: deploy records under
   `/opt/mapleroot-deployments/*/*` (`:40,118-145`), where only dirs holding `deploy.log`, `DEPLOYMENT.md`,
   `deployment.json` or `backup/` count as finished (`:139`) — anything else is "not a finished deploy, left alone"
   (`:142`). C: test leftovers `backup-*` / `Cosmic.jar.pre-*` at depth 2-4 under `/opt/mapleroot-dev`, `*/mysql` and
   `integration-db` pruned, past `TEST_KEEP_DAYS=14` (`:41,147-154`).
8. **Candidates.** The newest `KEEP_DEPLOYS=3` survive (`:35`); older deploys contribute only
   `backup|staging|PreviousServer|*.bundle|*.tgz|*.tar|*.tar.gz|*.sql|*.sql.gz|*.jar` (`:130-131`), so a deploy's code
   tree can never be a rotation target.
9. **Protect.** `PROTECT_PATHS` defaults to the bare name `PreviousServer` (`:43`); a bare name protects that basename
   anywhere and any directory containing one, a path with `/` is a glob on the full path (`:62-79`); `add()` routes
   protected paths to `$prot` and the rest to `$cand` (`:82-88`).
10. **Two-run grace.** The plan lives in `$STATE_DIR/rotate-plan.tsv`, merged with earlier runs' first-seen timestamps
    (`:158-162`); an entry becomes deletable only after being listed for `GRACE_HOURS=20` (`:37,189-190`).
11. **Refuse, then delete.** `KEEP_DEPLOYS must be >= 1` up front (`:55`); each candidate must match
    `"$PRE_ROOT"/pre-*|"$DEPLOY_ROOT"/*/*|"$TEST_ROOT"/*/*` or it is "refused: outside the rotation roots" (`:194-195`);
    `/opt/mapleroot/backups/nightly` is skipped (`:197`); protected paths get "refused: protected (PROTECT_PATHS)"
    (`:198`); only then `rm -rf --one-file-system -- "$p"` (`:199`), replaced by a message in dry run (`:203`).

**Restore drill** (`deploy/backup/slumsms-restore-drill`, 457 lines):

12. **Pick the dump.** Newest `mapleroot-*.sql.gz` in `NIGHTLY_DIR` (`:94`); nav from the same stamp or `--nav-from-live`
    (`:96-99`); `gpg` decrypt if needed (`:100-103`); `SHA256SUMS` or an off-box `SHA256SUMS-*` checked, warning only
    when no entry exists (`:105-115`); `gzip -t` (`:118`) and the "Dump completed" trailer (`:119`).
13. **Restore into a scratch schema.** `mapleroot_drill_<YYYYMMDD>` (`:57`) on `127.0.0.1:3307` (`:73`), guarded by
    `[ "$SCHEMA" != mapleroot ] || die "refusing schema name mapleroot"` (`:137`); a `CREATE DATABASE` line is required
    in the first 60 lines and renamed (`:139-144`); the stream strips `CREATE DATABASE`/`USE` (`:146`); a throwaway
    `slums_drill` user gets GRANT on that schema only (`:148`).
14. **Catch up migrations.** Ledger from the restored `mapleroot_integration_migrations` (`:151`); the target is read
    SELECT-only from the live container, or, if unavailable, every migration a completed deploy staged (`:154-162`);
    missing ids are applied and the two ledgers must match (`:172`).
15. **Copy the server, not the world.** `rsync -a` excluding `wz`, `wz.tar.gz`, `logs`, `.git`, `BotLog.txt`, `reports`
    and the five `cache/bot-nav` dirs (`:203-205`); `wz` symlinked from live (`:207`); an inline `python3` points
    `config.yaml` `DB_URL_FORMAT`/`DB_USER`/`DB_PASS` at the scratch schema on 3307, blanks
    `BOT_LLM_BASE_URL`/`BOT_DECIDE_URL`, requires each pattern once and aborts if `:3309` survives (`:210-227`).
    `NavStuckReport` reads the restored files (`:232,253`), then `DrillLearnedCheck.java` compiles against
    `Cosmic.jar` (`:264-336`).
16. **Boot the copy.** `systemd-run --unit=slumsms-restore-drill` with `ReadOnlyPaths` for the live tree,
    `CPUQuota=200%`, `Nice=19`, `MemoryMax=6G` (`:368,251-255`); login 8539, channels 8805+ (`:38-39`); waits for "is
    now online" (`:374-380`), asserts the `v83` handshake (`:391`), runs `MINUTES=7` (`:393-395`).
17. **Check, then stop.** Graceful stop with the journal in the receipt (`:401-403`); required log lines for world load,
    SoloMapling, bot store, roster and director boot fill (`:406-411`); roster count equals restored `botroster` rows
    (`:412-415`); zero `ERROR|Exception` (`:416-417`); `state-before/after.tsv` and `players.diff` identical
    (`:418-419`); learned books reported loaded/untouched/lost (`:430-446`).
18. **Cleanup.** `trap cleanup EXIT` (`:88`) stops and `reset-failed`s the unit and, unless `--keep`, drops the schema,
    user and run dir (`:74-87`); the receipt stays. Exit 0 PASS / 1 a check failed / 2 could not run (`:22,455-457`).

**Test-server backups** (`tools/ops/deploy-test`):

19. `$BASE/deploys/<stamp>/` gets the new jar, `data.tgz`, migrations, `deleted.txt`, `DEPLOY.txt` (`:73-81`), the
    replaced `Cosmic.jar.before` (`:98`), `data.before.tgz` (`:99-102`) and deleted files under `gone/` (`:103`); only
    when migrations exist is there also `mysqldump --single-transaction "$DBN" | gzip > $D/db-before.sql.gz`
    (`:105-106`). Rollback is manual — "roll back with the files in $D" (`:123`). `slumsms-rotate-backups` does not
    manage these directories; see Known issues.

## Data

| Path | Written by | When | Retention |
|---|---|---|---|
| `/opt/mapleroot/backups/nightly/mapleroot-<ts>.sql.gz` | nightly `:50-54` | daily 11:00 UTC | 14 days, min 3 (`:33-34`) |
| `/opt/mapleroot/backups/nightly/bot-nav-<ts>.tar.gz` | nightly `:82-84` | daily | same prune (`:100`) |
| `/opt/mapleroot/backups/nightly/SHA256SUMS[-<ts>]` | nightly `:88,102,122-123` | daily; `-<ts>` only with off-box | stale lines dropped, never age-pruned |
| `/opt/mapleroot-deployments/<version>/backup/` | release deploy `deploy.body.sh:30,194-207` | each release | newest 3 deploys (`slumsms-rotate-backups:35`) |
| `/opt/mapleroot-dev/*/deploys/<stamp>/` | `tools/ops/deploy-test:73-106` | each test deploy | **none** — see Known issues |
| `/var/lib/slumsms-backup/rotate-plan.tsv`, `prev-<ts>/` | rotate `:158-162,206`; `install.sh:50-55` | each run / each install | last plan; kept for `--undo` |
| `/opt/mapleroot-dev/restore-drill/receipts/<ts>/` | drill `:61-64,85` | each drill | kept (`--keep` keeps the run dir too) |
| `/etc/slumsms-backup.conf` | `install.sh:60` (mode 600) | first install only | never overwritten |

Not in the nightly, and whether that is deliberate:

| Not backed up | Where it lives | Deliberate? |
|---|---|---|
| WZ data | live Server `wz/`, `wz.tar.gz` | Deliberate in nightly and drill — excluded (`slumsms-restore-drill:203-205`), symlinked from live (`:207`); WZ edits keep their own timestamped sibling copy (`tools/data-align/wz_set_values.py:13,66,100`). Release-time snapshot regressed, see Known issues |
| Graph caches `v68`, `deep` | live Server `cache/` | Deliberate: "rebuilt from wz" (`deploy/backup/README.md:39`) |
| Bot state outside the five `NAV_DIRS` | live Server | Gap: only `learned stuck heat observed players` are archived (`slumsms-nightly-backup:35`) |
| Key files (`config.yaml`, `/etc/slumsms-backup.conf`, the off-box passphrase) | live Server, `/etc`, `/root/.config/slumsms-backup/offbox.key` | Gap for the nightly — `config.yaml` is captured only at release time (`deploy.body.sh:202`); the passphrase is deliberately out of band (`install.sh:61-67`, `slumsms-backup.conf.example:20`), which is a promise, not a check |
| Patch feed (`manifest.json` + blobs) | live `/srv/slums-patch` | Gap: the nightly never touches it; the publisher keeps only `manifest-replaced-<ts>.json` (`tools/release/publish-client:14,60-61`) |
| `scripts/`, `cashshop/catalog.tsv`, `src/main` | git | Not needed: in the repo and re-deployed (`deploy.body.sh:198-200`) |
| Logs, `BotLog.txt`, `reports/` | live Server | Deliberate: excluded (`slumsms-restore-drill:203-205`) |

## Switches

| Knob | Default | Where |
|---|---|---|
| `SLUMSMS_BACKUP_CONF` | `/etc/slumsms-backup.conf` | `slumsms-rotate-backups:30`; nightly sources it at `slumsms-nightly-backup:25-26` |
| `BACKUP_DIR` / `SERVER_DIR` | `/opt/mapleroot/backups/nightly` / the live Server folder | `slumsms-nightly-backup:27-28` |
| `DB_CONTAINER` / `DB_SCHEMA` / `DUMP_LOCK` | `buns-clean-db-1` / `mapleroot` / `--lock-all-tables` | `slumsms-nightly-backup:29-32` |
| `KEEP_DAYS` / `KEEP_MIN` / `NAV_DIRS` | 14 / 3 / `learned stuck heat observed players` | `slumsms-nightly-backup:33-35` |
| `OFFBOX_DEST` | empty = off-box disabled | `slumsms-nightly-backup:36` |
| `OFFBOX_METHOD` / `OFFBOX_SSH_OPTS` | `rsync` / `BatchMode=yes`, `ConnectTimeout=20` | `slumsms-nightly-backup:37-39` |
| `OFFBOX_KEY` | `/root/.config/slumsms-backup/offbox.key` | `slumsms-nightly-backup:38` |
| `ROTATE_MODE` | `dry-run` — deletes nothing until set to `apply` | `slumsms-rotate-backups:34`; `slumsms-backup.conf.example:23` |
| `KEEP_DEPLOYS` / `TEST_KEEP_DAYS` / `GRACE_HOURS` | 3 (floor 1) / 14 / 20 | `slumsms-rotate-backups:35-37,55` |
| `PROTECT_PATHS` | `PreviousServer`; setting it **replaces** the default | `slumsms-rotate-backups:43`; `slumsms-backup.conf.example:28-31` |
| `STATE_DIR` | `/var/lib/slumsms-backup` | `slumsms-rotate-backups:38` |
| `TEST_DB_CNF` / `TEST_DB_PORT` | test DB client config / 3307 | `slumsms-restore-drill:34-35` |
| `DRILL_LOGIN_PORT` / `DRILL_CHANNEL_BASE` / `--minutes` | 8539 / 8805 / 7 | `slumsms-restore-drill:38-39,43` |
| `SLUMS_TEST_HOST` | `192.168.0.111` (test host since the 1.6.3 cutover) | `tools/ops/lib.sh:3-10`; the drill ignores it and hardcodes `127.0.0.1` (`slumsms-restore-drill:73`) |

## Invariants

1. **A dump is promoted only after its trailer checks out** — `slumsms-nightly-backup:52` precedes the `mv` at `:53`.
2. **The only-good copy is never rotated.** Protected paths never reach the candidate list (`:82-88`), are refused again
   at delete time (`:198`), and the default `PROTECT_PATHS=PreviousServer` (`:43`) encodes the owner decision of
   2026-10-20 (`slumsms-backup.conf.example:28-31`). Proof: `deploy/backup/test-rotate-protect.sh` (`:28-34` dry-run
   assertions, `:36-40` `--apply --grace-hours 0`, `:42-47` glob and empty-list cases) — I ran it on 2026-11-05, exit 0,
   "PASS: PreviousServer protected in dry run and apply; other bulk still rotates", with `d1/PreviousServer` and
   `d1/backup` protected and only `d1/repo.bundle` + `d1/staging` candidates.
3. **Two-run grace before any delete** — `slumsms-rotate-backups:189-190` against `$STATE_DIR/rotate-plan.tsv`.
4. **Rotation stays inside its roots** — allowlist `:194-195`; the nightly directory is skipped (`:197`).
5. **Rotation cannot be configured to keep nothing** — `KEEP_DEPLOYS must be >= 1` (`:55`).
6. **The drill cannot overwrite the live schema** — `refusing schema name mapleroot` (`slumsms-restore-drill:137`) plus
   the abort if `:3309` survives in the rewritten config (`:225-226`).
7. **The drill never writes to live** — `ReadOnlyPaths=/opt/mapleroot` and `/opt/mapleroot-deployments` (`:251-255`),
   one-way `rsync`, `wz` as a symlink (`:207`).
8. **Restored player state must be identical** — `players.diff` empty (`:418-419`).

## Origin

- **Custom**, all of it. `e533edec8` (2026-10-20, "Back up bot learning data, rotate old backups, add a restore drill")
  created every file under `deploy/backup/`; `9df927f97` (2026-10-20) also touched `slumsms-restore-drill`.
- The guard is a same-day follow-up: `e78339861` (2026-10-20, "Never rotate away PreviousServer: protected paths in the
  backup kit") is the origin of `test-rotate-protect.sh`.
- `tools/ops/deploy-test` is `383b0df55` (2026-10-23, "Agent handbook (AGENTS.md), test deploy and status scripts"),
  retargeted by `243fd5591` (2026-11-01, "Point every TEST tool at mapletest behind SLUMS_TEST_HOST").
- `deploy/monitor/targets/live.conf` — the only monitoring that watches the backup timer — is `eb9e092b1` (2026-10-20).
- Baseline `03d13cb21` (2026-10-18, "Establish deployable … server baseline"); nothing here predates it, and
  `deploy/backup/` has had no commit since 2026-10-25.

## Tests and QA

| Test | Covers | Not covered |
|---|---|---|
| `deploy/backup/test-rotate-protect.sh` (48 lines, manual) | Protection by bare name, path glob and inside a subdirectory, plus that unprotected bulk still rotates, in dry run and `--apply` (`:28-47`); entirely in `mktemp -d` (`:2-4`) | Grace window, root allowlist, section A/C matching. Not in CI — `.github/workflows/build.yml` only runs `./mvnw --batch-mode test package` |
| The drill itself | End to end: dump restores, migrations catch up, server boots, learned data loads, player rows unchanged (`:406-446`) | Nothing about tomorrow: a green drill proves one file. Two receipts exist, both 2026-10-20 (`docs/tickets/45-backups-and-rotation.md:43-48`) |
| `install.sh:48` `bash -n` | Syntax of the three scripts | Any behaviour |
| `tools/ops/deploy-test:120-123` | Boot-error count after a test deploy | Its own backups are never proven restorable |

`slumsms-nightly-backup` has no test at all (dump, nav JSON validation, prune, off-box); it is exercised only by running
it for real.

## Known issues

- Ticket 45 is closed (`docs/tickets/45-backups-and-rotation.md:5`) but its "Still open" list is not: "owner installs
  the kit, picks an off-box target, and a drill runs from an off-box copy" (`:50`).
- Ticket 109 (in progress) plans a `maplebackup` container with 14 daily / 8 weekly / 6 monthly copies behind a push-only
  forced-command key; unbuilt, and "Live has no backups off the box" (`docs/tickets/109-test-backup-infra.md:9,38-45,68,86`).
- `docs/ops/lxc125-cleanup-review.md:112-113` risk 6/7: pre-deploy backups no rotation (manual prune to ~3) and
  `Cosmic.jar.pre-*` sprawl (7 in `persistence-test`).
- NEW-FINDING: test-deploy backups are never rotated. `tools/ops/deploy-test:73` writes `$BASE/deploys/<stamp>/`, while
  the rotator's section C only matches `backup-*` and `Cosmic.jar.pre-*` at depth 2-4
  (`deploy/backup/slumsms-rotate-backups:147-154`), so every test deploy leaves `Cosmic.jar.before`, `data.before.tgz`,
  `gone/` and sometimes `db-before.sql.gz` forever. The cleanup review saw the symptom as jar sprawl without connecting
  it to the rotator.
- NEW-FINDING: the rotate and drill timers are unmonitored. `deploy/monitor/targets/live.conf:9` lists only
  `slumsms-backup slumsms-nav-bake`; `deploy/monitor/slumsms-monitor:978-1005` checks active state, last result and
  `TIMER_MAX_AGE_H` (26h, `:80`) for listed timers only, so a rotation silent for a month or a failed monthly drill
  raises no alert; only disk use is watched (`live.conf:8`).
- NEW-FINDING: the release template lost the WZ snapshot. `deploy/v1.4.1/deploy.sh:14,193,199-204` captured
  `wz-before.tgz`; the generated `tools/release/templates/deploy.body.sh:194-207` captures the DB, `server-runtime.tgz`,
  `config.yaml`, the unit, the old jar hash and the ledger, and greps for `wz` there return nothing — so a release no
  longer snapshots WZ, and the nightly never did.
- NEW-FINDING: the drill's TEST-DB coordinates no longer match the documented host split. It uses `127.0.0.1:3307` and
  `/opt/mapleroot-dev/solomapling-test/secrets/root.cnf` (`deploy/backup/slumsms-restore-drill:34-35,73`), i.e. it must
  run where the test datadir is, which `docs/architecture/ops/hosts.md:27,49` places on mapletest while the drill unit is
  listed under 164 (`hosts.md:48`). No host access this review — also an open question.
- `docs/ops/lxc125-architecture.md:60,64-66` still says "v1 only" and that the ticket-45 kit "is not installed yet",
  contradicting `deploy/backup/README.md:22` and the two drill receipts; that page was last revised 2026-10-20 (`:3`).
- The `pre-*` directories section A rotates are written by nothing in the repo (grep for `backups/pre-` hits only prose,
  e.g. `docs/PROJECT-STATUS.md:70`); current releases write `<version>/backup/`
  (`tools/release/templates/deploy.body.sh:30`). Harmless, but docs naming `pre-*` as the rollback point are stale.

## State

**Partial.** The scripts are complete and self-consistent, the guard is proven by a test I ran successfully on
2026-11-05 in a temp dir, and the drill produced two PASS receipts on the live host
(`docs/tickets/45-backups-and-rotation.md:43-48`). It is not *working* as a backup system because off-box is disabled by
default with no target (`slumsms-nightly-backup:36,106-109`, `docs/tickets/109-test-backup-infra.md:9`), rotation
defaults to `dry-run` so nothing is ever deleted until an operator sets `ROTATE_MODE=apply`
(`slumsms-rotate-backups:34`, `install.sh:84`), and installation state cannot be verified from this repo.

## Open questions

1. Is the kit installed, and is `ROTATE_MODE=apply` set? `install.sh:68-71` enables only the rotation timer by default
   and `README.md:14` needs `--with-drill`; `docs/architecture/ops/hosts.md:48` implies the units exist while
   `docs/ops/lxc125-architecture.md:64-66` says not installed.
2. Which host runs the monthly drill after the 1.6.3 cutover? The paths and `-h127.0.0.1 -P3307`
   (`slumsms-restore-drill:34-35,73`) imply the test box, but `hosts.md:48` lists the unit on 164. Unverifiable here.
3. How would a human do a real restore? The only procedure is manual and partial — "with the server stopped,
   `zcat mapleroot-<ts>.sql.gz | mysql …` and `tar -C <Server>/cache/bot-nav -xzf bot-nav-<ts>.tar.gz`"
   (`deploy/backup/README.md:42-44`). `docs/PROJECT-STATUS.md:64-75` states an exact recovery "has not been rehearsed"
   and that these are application backups, not OS snapshots.
4. Is the off-box passphrase really out of the failure domain? `install.sh:61-67` and `slumsms-backup.conf.example:20`
   tell the operator to keep it off the host, and nothing verifies that happened; if the host dies with the key on it,
   every off-box copy is unreadable.
5. Should the nightly also take the patch feed and `config.yaml`? Today only a release backup holds a config
   (`deploy.body.sh:202`) and nothing holds the published feed (`tools/release/publish-client:60-61`).
6. Who owns the test-deploy backups — should the rotator learn `deploys/<stamp>`? Neither `tools/ops/deploy-test:73` nor
   the cleanup review assigns an owner, and `slumsms-rotate-backups:147-154` excludes them by name and depth.
