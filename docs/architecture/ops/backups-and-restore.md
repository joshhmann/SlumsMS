# Backups and restore

## Purpose

Three shell scripts plus six systemd units take a nightly database dump and a tar of the bots' learned navigation
data, rotate old deploy material with a protected-path guard, and monthly restore the newest dump into a scratch
schema and boot a throwaway copy of the live server on spare ports to prove the dump still loads. Everything is
plain bash under `deploy/backup/`; nothing there stops, restarts or reconfigures the live server or its database
(`deploy/backup/README.md:3-4`). The kit exists because a dump nobody has restored is not a backup — the drill
(`slumsms-restore-drill`) is the evidence mechanism, and the rotation guard (`PROTECT_PATHS`) exists because
rotation is the one operation that can destroy the only good copy.

## Entry points

| Operator action | Command | Cite |
|---|---|---|
| Install/upgrade the kit on the live host | `bash install.sh [--with-drill] [--backup-now] [--undo <dir>]` | `deploy/backup/install.sh:13-15` |
| Take a backup now | `systemctl start slumsms-backup` (or `install.sh --backup-now`) | `deploy/backup/install.sh:75-78` |
| Run the nightly by hand | `slumsms-nightly-backup` (reads `/etc/slumsms-backup.conf` via `$SLUMSMS_BACKUP_CONF`) | `deploy/backup/slumsms-nightly-backup:20-25` |
| See what rotation would delete | `slumsms-rotate-backups` (dry run is the default) | `deploy/backup/slumsms-rotate-backups:34,45-54` |
| Actually rotate | `slumsms-rotate-backups --apply` (`ROTATE_MODE=apply`) | `deploy/backup/slumsms-rotate-backups:34,55,206` |
| Prove the guard still holds | `bash deploy/backup/test-rotate-protect.sh` | `deploy/backup/README.md:65` |
| Restore drill by hand | `slumsms-restore-drill [--dump FILE] [--nav FILE\|--nav-from-live] [--minutes N] [--keep]` | `deploy/backup/slumsms-restore-drill:44-53` |
| Move the TEST DB datadir | `bash move-test-db-datadir.sh [--go\|--rollback]` (preflight default) | `deploy/backup/move-test-db-datadir.sh:10-12` |
| Test-server deploy (its own backup) | `tools/ops/deploy-test <commit>` | `tools/ops/deploy-test:13-14` |

Units: `slumsms-backup.timer` daily 11:00 UTC, `RandomizedDelaySec=5min`, `Persistent=true`
(`deploy/backup/slumsms-backup.timer:5-7`) → `slumsms-backup.service` `Nice=10`, `IOSchedulingClass=idle`,
`TimeoutStartSec=30min` (`deploy/backup/slumsms-backup.service:7-11`). `slumsms-backup-rotate.timer` daily 11:30 UTC
(`slumsms-backup-rotate.timer:5-6`) → `slumsms-rotate-backups` `Nice=19`. `slumsms-restore-drill.timer` monthly,
`*-*-01 12:00:00 UTC` (`slumsms-restore-drill.timer:5-6`) → drill service with `TimeoutStartSec=45min`
(`slumsms-restore-drill.service:9-10`); the drill timer is only enabled with `install.sh --with-drill`
(`deploy/backup/README.md:14`).

## How it works

### Nightly backup (`deploy/backup/slumsms-nightly-backup`, 137 lines)

1. **Stamp and temp dir.** `TS` is UTC (`:41`); `DUMP=$BACKUP_DIR/$DB_SCHEMA-$TS.sql.gz`, `NAV=bot-nav-$TS.tar.gz`
   (`:42-43`); a `trap` removes the temp dir and any `*.part` files on any exit (`:44-45`).
2. **Dump the database.** `docker exec "$DB_CONTAINER" mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" $DUMP_LOCK
   --routines --triggers --databases $DB_SCHEMA | gzip > "$DUMP.part"` (`:50`) with
   `DUMP_LOCK=--lock-all-tables`, which "blocks writes for the ~0.5 s dump" (`:31-32`). Routines and triggers are
   included; data only for the one schema.
3. **Verify before promoting.** `zcat … | tail -1 | grep -q "Dump completed"` or `exit 1` (`:52`), then `mv` the
   `.part` into place and `chmod 600` (`:53-54`). A truncated dump never becomes the newest file.
4. **Archive learned bot nav.** `cp -a` of `cache/bot-nav/{learned,stuck,heat,observed,players}` into a temp tree,
   skipping dirs that do not exist (`:58-65`); every `*.json` is parsed with `python3 -I … json.load`, re-copied up
   to 5 times on failure, then either "does not parse after 5 copies, kept as is" or "vanished, dropped" (`:66-80`);
   `tar --owner=0 --group=0` (`:82`), `chmod 600` (`:84`). This is the only non-database state the nightly saves.
5. **Checksums.** Both files appended to `SHA256SUMS` (`:88`).
6. **Prune.** `prune()` sorts `*.sql.gz` / `bot-nav-*.tar.gz` newest-first, keeps at least the newest `KEEP_MIN=3`,
   and only deletes anything older than `KEEP_DAYS=14` (`:33-34,90-100`). `awk` then drops `SHA256SUMS` lines whose
   file no longer exists (`:102`), so the checksum list cannot drift.
7. **Off-box push (optional).** With `OFFBOX_DEST` empty the script prints "off-box: disabled" and exits 0
   (`:36,106-109`). Otherwise it gpg-symmetric-encrypts AES256 with the passphrase file `OFFBOX_KEY` (`:112-120`),
   writes a sidecar `SHA256SUMS-$TS` carrying plaintext and ciphertext hashes (`:122-123`), then `rsync -t
   --partial-dir=.partial` (or `scp -q`) with `BatchMode=yes`, `ConnectTimeout=20` (`:125-129`). A failed push exits
   3 — "the local backup is fine" (`:133-135`) — which is why exit 3 is distinct from exit 1 (`:3-19`).

### Rotation (`deploy/backup/slumsms-rotate-backups`, 206 lines)

8. **Three sources.** Section A: `pre-*` dirs under `/opt/mapleroot/backups` (`:39,104-116`). Section B: deploy
   records under `/opt/mapleroot-deployments/*/*` (`:40,118-145`) — only dirs holding `deploy.log`,
   `DEPLOYMENT.md`, `deployment.json` or `backup/` count as finished (`:139`), anything else is "not a finished
   deploy, left alone" (`:142`). Section C: test-server leftovers `backup-*` / `Cosmic.jar.pre-*` at depth 2-4 under
   `/opt/mapleroot-dev`, with `*/mysql` and `integration-db` pruned, older than `TEST_KEEP_DAYS=14` (`:41,147-154`).
9. **Collect candidates.** Section B keeps the newest `KEEP_DEPLOYS=3` (`:35`); older ones contribute only
   `backup|staging|PreviousServer|*.bundle|*.tgz|*.tar|*.tar.gz|*.sql|*.sql.gz|*.jar` (`:130-131`), so a deploy's
   code tree is never a rotation target.
10. **Protect.** `PROTECT_PATHS` defaults to the bare name `PreviousServer` (`:43`); a bare name protects any
    matching basename anywhere and also any directory *containing* one, a path with `/` is a glob matched on the full
    path (`:62-79`). `add()` routes protected paths to `$prot`, everything else to `$cand` (`:82-88`).
11. **Two-run grace.** The plan is written to `$STATE_DIR/rotate-plan.tsv` and merged with an earlier run's
    first-seen timestamps (`:158-162`). A candidate only becomes deletable once it was listed `GRACE_HOURS=20` earlier
    (`:37,189-190`), so a bad rule has one night to be noticed and nothing is deleted blind.
12. **Refuse and delete.** For each candidate: `KEEP_DEPLOYS must be >= 1` is checked up front (`:55`); the path must
    match the root allowlist `"$PRE_ROOT"/pre-*|"$DEPLOY_ROOT"/*/*|"$TEST_ROOT"/*/*` or it is "refused: outside the
    rotation roots" (`:194-195`); `/opt/mapleroot/backups/nightly` is skipped outright (`:197`); protected paths are
    "refused: protected (PROTECT_PATHS)" (`:198`); only then `rm -rf --one-file-system -- "$p"` (`:199`). In dry run
    the message replaces the `rm` (`:203`).

### Restore drill (`deploy/backup/slumsms-restore-drill`, 457 lines)

13. **Pick and authenticate the dump.** Newest `mapleroot-*.sql.gz` in `NIGHTLY_DIR` (`:94`), nav from the same stamp
    or `--nav-from-live` (`:96-99`); `gpg` decrypt if needed (`:100-103`); `SHA256SUMS` (or `SHA256SUMS-*`, i.e. an
    off-box sidecar) checked, warning only when no entry exists (`:105-115`); `gzip -t` (`:118`) and the
    "Dump completed" trailer (`:119`).
14. **Restore into a scratch schema.** Schema `mapleroot_drill_<YYYYMMDD>` (`:57`) on `127.0.0.1:3307` (`:73`), with
    `[ "$SCHEMA" != mapleroot ] || die "refusing schema name mapleroot"` (`:137`) and a required `CREATE DATABASE`
    line in the first 60 lines that is renamed (`:139-144`). The streamed restore strips `CREATE DATABASE`/`USE` and
    replay-pipes into the scratch schema (`:146`), then a throwaway `slums_drill` user gets GRANT on that schema only
    (`:148`).
15. **Catch up migrations.** `mapleroot_integration_migrations` is read from the restored dump (`:151`); the target
    ledger is read SELECT-only from the live container, or, if that is unavailable, every migration a completed deploy
    staged (`:154-162`); missing ids are applied and the ledger must match (`:172`).
16. **Copy the server, not the world.** `rsync -a` of the live Server dir excluding `wz`, `wz.tar.gz`, `logs`, `.git`,
    `BotLog.txt`, `reports` and the five `cache/bot-nav` dirs (`:203-205`); `wz` is symlinked from live (`:207`); an
    inline `python3` rewrites `config.yaml` `DB_URL_FORMAT`/`DB_USER`/`DB_PASS` to the scratch schema on 3307 and
    blanks `BOT_LLM_BASE_URL`/`BOT_DECIDE_URL`, requiring each pattern to match exactly once and aborting if `:3309`
    survives (`:210-227`).
17. **Prove the learned data loads.** The live jar's `NavStuckReport` reads the restored nav files (`:232,253`), then
    `DrillLearnedCheck.java` is compiled against `Cosmic.jar` (`:264-336`).
18. **Boot the copy.** `systemd-run --unit=slumsms-restore-drill` with `ReadOnlyPaths` for the live tree, `CPUQuota=200%`,
    `Nice=19`, `MemoryMax=6G` (`:368,251-255`); login 8539, channels 8805+ (`:38-39`); waits for "is now online"
    (`:374-380`), asserts the `v83` login handshake (`:391`), then runs `MINUTES=7` (`:393-395`).
19. **Check, then stop.** Graceful stop, journal to the receipt (`:401-403`); required log lines for world load,
    SoloMapling, bot store, roster and director boot fill (`:406-411`); roster count equals restored `botroster` rows
    (`:412-415`); zero `ERROR|Exception` (`:416-417`); player rows identical before and after via
    `state-before/after.tsv` and `players.diff` (`:418-419`); learned books reported loaded/untouched/lost (`:430-446`).
20. **Cleanup.** `trap cleanup EXIT` (`:88`): stop and `reset-failed` the unit, and unless `--keep`, drop the schema,
    the user and the run dir; the receipt stays (`:74-87`). Exit 0 PASS / 1 a check failed / 2 could not run
    (`:22,455-457`).

### Test-server backups (`tools/ops/deploy-test`)

21. A test deploy with no migration writes only files: `$BASE/deploys/<stamp>/` receives the new jar, `data.tgz`,
    migrations, `deleted.txt`, `DEPLOY.txt` (`:73-81`), the replaced `Cosmic.jar.before` (`:98`), `data.before.tgz`
    (`:99-102`) and deleted files under `gone/` (`:103`). Only when migrations exist does it also dump
    `mysqldump --single-transaction "$DBN" | gzip > $D/db-before.sql.gz` (`:105-106`). Rollback is manual —
    "roll back with the files in $D" (`:123`). These directories are not managed by `slumsms-rotate-backups`; see
    Known issues.

## Data

| Path | Written by | When | Retention |
|---|---|---|---|
| `/opt/mapleroot/backups/nightly/mapleroot-<ts>.sql.gz` | nightly `:50-54` | daily 11:00 UTC | 14 days, min 3 (`:33-34`) |
| `/opt/mapleroot/backups/nightly/bot-nav-<ts>.tar.gz` | nightly `:82-84` | daily | same prune (`:100`) |
| `/opt/mapleroot/backups/nightly/SHA256SUMS` | nightly `:88,102` | daily, rewritten in place | unbounded lines, stale ones dropped |
| `/opt/mapleroot/backups/nightly/SHA256SUMS-<ts>` | nightly `:122-123` | only when off-box is on | n/a (off-box sidecar) |
| `/opt/mapleroot-deployments/<version>/backup/` | release deploy `deploy.body.sh:30,194-207` | each release | newest 3 deploys (`slumsms-rotate-backups:35`) |
| `/opt/mapleroot-dev/persistence-test/deploys/<stamp>/` | `tools/ops/deploy-test:73-106` | each test deploy | none (see Known issues) |
| `/var/lib/slumsms-backup/rotate-plan.tsv` | rotate `:158-162,206` | each rotation run | last accepted plan |
| `/var/lib/slumsms-backup/prev-<ts>/` | `install.sh:50-55` | each install/upgrade | kept for `--undo` |
| `/opt/mapleroot-dev/restore-drill/receipts/<ts>/` | drill `:61-64,85` | each drill | kept (`--keep` keeps the whole run) |
| `/etc/slumsms-backup.conf` | `install.sh:60` (0600) | first install only | not overwritten |

What is **not** in the nightly, and whether that is deliberate:

| Not backed up | Where it lives | Deliberate? |
|---|---|---|
| WZ data | live Server `wz/`, `wz.tar.gz` | Deliberate: excluded from the drill copy (`slumsms-restore-drill:203-205`) and symlinked from live (`:207`). WZ edits make their own timestamped sibling copy (`tools/data-align/wz_set_values.py:13,66,100`). |
| Graph caches `v68`, `deep` | live Server `cache/` | Deliberate: "rebuilt from wz" (`deploy/backup/README.md:39`). |
| Learned/stuck world state outside `cache/bot-nav` | live Server | Deliberate/gap: only the five `NAV_DIRS` are archived (`slumsms-nightly-backup:35`). |
| Patch feed (`manifest.json` and blobs) | live `/srv/slums-patch` | Gap: the nightly does not touch it; only `manifest-replaced-<ts>.json` is kept by the publisher (`tools/release/publish-client:60-61,14`). |
| `config.yaml` | live Server | Gap for nightly; captured only at release time (`deploy.body.sh:202`). |
| `/etc/slumsms-backup.conf`, the off-box gpg passphrase | `/etc`, `/root/.config/slumsms-backup/offbox.key` | Deliberate but sharp-edged: the key is the decryption key for off-box copies, so it is told to the operator to move into a password manager (`install.sh:61-67`, `slumsms-backup.conf.example:20`). |
| `scripts/`, `cashshop/catalog.tsv`, `src/main` | git | Not needed: in the repo, and re-deployed from the release (`deploy.body.sh:198-200`). |
| Logs, `BotLog.txt`, `reports/` | live Server | Deliberate: excluded (`slumsms-restore-drill:203-205`). |

## Switches

| Knob | Default | Where |
|---|---|---|
| `SLUMSMS_BACKUP_CONF` | `/etc/slumsms-backup.conf` | `slumsms-rotate-backups:30`; nightly sources it at `slumsms-nightly-backup:25` |
| `BACKUP_DIR` | `/opt/mapleroot/backups/nightly` | `slumsms-nightly-backup:27` |
| `SERVER_DIR` | the live Server folder | `slumsms-nightly-backup:28` |
| `DB_CONTAINER` / `DB_SCHEMA` | `buns-clean-db-1` / `mapleroot` | `slumsms-nightly-backup:29-30` |
| `DUMP_LOCK` | `--lock-all-tables` | `slumsms-nightly-backup:32` |
| `KEEP_DAYS` / `KEEP_MIN` | 14 / 3 | `slumsms-nightly-backup:33-34` |
| `NAV_DIRS` | `learned stuck heat observed players` | `slumsms-nightly-backup:35` |
| `OFFBOX_DEST` | empty = off-box disabled | `slumsms-nightly-backup:36` |
| `OFFBOX_METHOD` / `OFFBOX_SSH_OPTS` | `rsync` / `BatchMode=yes, ConnectTimeout=20` | `slumsms-nightly-backup:37-39` |
| `OFFBOX_KEY` | `/root/.config/slumsms-backup/offbox.key` | `slumsms-nightly-backup:38` |
| `ROTATE_MODE` | `dry-run` (must be set to `apply` to delete) | `slumsms-rotate-backups:34`; `slumsms-backup.conf.example:23` |
| `KEEP_DEPLOYS` | 3, floor 1 | `slumsms-rotate-backups:35,55` |
| `TEST_KEEP_DAYS` / `GRACE_HOURS` | 14 / 20 | `slumsms-rotate-backups:36-37` |
| `PROTECT_PATHS` | `PreviousServer` (setting it replaces the default) | `slumsms-rotate-backups:43`; `slumsms-backup.conf.example:28-31` |
| `STATE_DIR` | `/var/lib/slumsms-backup` | `slumsms-rotate-backups:38` |
| `TEST_DB_CNF` / `TEST_DB_PORT` | test DB client config / 3307 | `slumsms-restore-drill:34-35` |
| `DRILL_LOGIN_PORT` / `DRILL_CHANNEL_BASE` | 8539 / 8805 | `slumsms-restore-drill:38-39` |
| `--minutes` | 7 | `slumsms-restore-drill:43` |
| `SLUMS_TEST_HOST` | `192.168.0.111` (test host, since the 1.6.3 cutover) | `tools/ops/lib.sh:3-10`; unrelated to the drill, which hardcodes `127.0.0.1` |

## Invariants

1. **A dump is only promoted after its trailer checks out** — `slumsms-nightly-backup:52` before `:53`.
2. **The only-good copy is never rotated.** Protected paths are excluded from the candidate list (`:82-88`), refused
   again at delete time (`:198`), and `PROTECT_PATHS` defaults to `PreviousServer` (`:43`), per the owner decision of
   2026-10-20 recorded in `slumsms-backup.conf.example:28-31`. The proving test is `deploy/backup/test-rotate-protect.sh`
   (assertions `:28-34`, apply-mode `:36-40`, glob and empty-list cases `:42-47`); I ran it locally on 2026-11-05, it
   exited 0 and printed "PASS: PreviousServer protected in dry run and apply; other bulk still rotates", listing
   `d1/PreviousServer` and `d1/backup` as protected and only `d1/repo.bundle` + `d1/staging` as candidates.
3. **Two-run grace before any delete** — `slumsms-rotate-backups:189-190`; a candidate must have been listed in
   `$STATE_DIR/rotate-plan.tsv` for `GRACE_HOURS`.
4. **Rotation stays inside its roots** — the allowlist at `:194-195` refuses anything not under `$PRE_ROOT/pre-*`,
   `$DEPLOY_ROOT/*/*`, `$TEST_ROOT/*/*`; the nightly directory is skipped explicitly (`:197`).
5. **Rotation can never be configured to keep nothing** — `KEEP_DEPLOYS must be >= 1` (`:55`).
6. **The drill can never overwrite the live schema** — `refusing schema name mapleroot` (`slumsms-restore-drill:137`)
   plus the abort if `:3309` remains in the rewritten config (`:225-226`).
7. **The drill never writes to live** — `ReadOnlyPaths=/opt/mapleroot` and `/opt/mapleroot-deployments` (`:251-255`),
   `rsync` is one-way, and `wz` is a symlink, not a copy (`:207`).
8. **Restored player state must be byte-identical** — `players.diff` must be empty (`:418-419`).

## Origin

- The whole kit is **custom**, added in one commit: `e533edec8` (2026-10-20, "Back up bot learning data, rotate old
  backups, add a restore drill") for every file under `deploy/backup/`, with `9df927f97` (2026-10-20) also touching
  `slumsms-restore-drill`.
- The protection guard is a follow-up: `e78339861` (2026-10-20, "Never rotate away PreviousServer: protected paths in
  the backup kit") is the origin of `test-rotate-protect.sh`.
- `tools/ops/deploy-test` is custom, `383b0df55` (2026-10-23, "Agent handbook (AGENTS.md), test deploy and status
  scripts"), later retargeted by `243fd5591` (2026-11-01, "Point every TEST tool at mapletest behind SLUMS_TEST_HOST").
- `deploy/monitor/targets/live.conf` (the only monitoring that watches the backup timer) is `eb9e092b1` (2026-10-20).
- Baseline for the whole repo is `03d13cb21` (2026-10-18, "Establish deployable … server baseline"); no file here
  predates it. `deploy/backup/` has had no commits since 2026-10-25.

## Tests and QA

| Test | Covers | Not covered |
|---|---|---|
| `deploy/backup/test-rotate-protect.sh` (48 lines, manual) | Protection by bare name, by path glob, inside a subdirectory, and that unprotected bulk still rotates; dry run and `--apply --grace-hours 0` (`:28-47`). Entirely in `mktemp -d` (`:2-4`) | Nothing else: not the grace window, not the root allowlist, not section A/C matching. Not in CI — `.github/workflows/build.yml` runs only `./mvnw --batch-mode test package` |
| `slumsms-restore-drill` itself | The end-to-end claim: dump restores, migrations catch up, server boots, learned data loads, player rows unchanged (`:406-446`) | Does not test that the *nightly* produces a good dump tomorrow; a green drill only proves yesterday's file. Two receipts exist from 2026-10-20 (`docs/tickets/45-backups-and-rotation.md:43-48`) |
| `install.sh:48` `bash -n` | Shell syntax of the three scripts | Behaviour |
| `tools/ops/deploy-test:120-123` | Boot-error count after a test deploy | Its own backups are never verified restorable |

No test exists for `slumsms-nightly-backup` (dump, nav JSON validation, prune, off-box) — the nightly is exercised
only by running it for real.

## Known issues

- Ticket 45 items still open: "owner installs the kit, picks an off-box target, and a drill runs from an off-box copy"
  (`docs/tickets/45-backups-and-rotation.md:50`), even though the ticket itself is closed (`:5`).
- Ticket 109 (in progress) plans a separate backup container with 14 daily / 8 weekly / 6 monthly copies and a
  push-only forced-command key; it is not built, and "Live has no backups off the box"
  (`docs/tickets/109-test-backup-infra.md:9,38-45,68,86`).
- `docs/ops/lxc125-cleanup-review.md:112-113` risk 6/7: pre-deploy backups had no rotation (manual prune to the last
  ~3) and `Cosmic.jar.pre-*` jar sprawl (7 in `persistence-test`).
- NEW-FINDING: test-deploy backups are never rotated. `tools/ops/deploy-test:73` writes
  `$BASE/deploys/<stamp>/`, while section C of the rotator only matches `backup-*` and `Cosmic.jar.pre-*` at depth
  2-4 (`deploy/backup/slumsms-rotate-backups:147-154`), so every test deploy leaves `Cosmic.jar.before`,
  `data.before.tgz`, `gone/` and sometimes `db-before.sql.gz` behind forever. The cleanup review noticed the symptom
  as "jar backup sprawl" without connecting it to the rotator.
- NEW-FINDING: the rotate and drill timers are unmonitored. `deploy/monitor/targets/live.conf:9` lists only
  `slumsms-backup slumsms-nav-bake`, and `deploy/monitor/slumsms-monitor:978-1005` checks active state, last result
  and `TIMER_MAX_AGE_H` (26h, `:80`) only for listed timers — so a rotation that has not run for a month, or a failed
  monthly drill, raises no alert. Disks are still watched (`live.conf:8`).
- NEW-FINDING: the release template lost the WZ snapshot. `deploy/v1.4.1/deploy.sh:193,199-204` captured
  `wz-before.tgz` at deploy time; the generated `tools/release/templates/deploy.body.sh:194-207` captures the DB,
  `server-runtime.tgz` (target jar, config, `scripts`, `src/main`), `config.yaml`, the unit, the old jar hash and the
  ledger, and contains no `wz` at all, so a release no longer snapshots WZ while the nightly never did.
- NEW-FINDING: the drill's hard-coded TEST-DB coordinates no longer match the documented host split. The drill talks
  to `127.0.0.1:3307` and reads `/opt/mapleroot-dev/solomapling-test/secrets/root.cnf`
  (`deploy/backup/slumsms-restore-drill:34-35,73`), i.e. it must run on the box holding the test datadir, which
  `docs/architecture/ops/hosts.md:33,59` places on mapletest while the drill timer is documented on 164
  (`hosts.md:51`). No host access this review: unverified, recorded as an open question too.
- `docs/ops/lxc125-architecture.md:60,64-66` still describes "v1 only" and says the ticket-45 kit "is not installed
  yet", which contradicts `deploy/backup/README.md:22` (install is a single command) and the two drill receipts from
  2026-10-20; the architecture page was last updated 2026-10-20 (`:3`) and was not revised with the kit.
- The `pre-*` directories rotated by section A are written by nothing in the repo (grep for `backups/pre-` finds only
  prose, e.g. `docs/PROJECT-STATUS.md:70`); current releases write `<version>/backup/`
  (`tools/release/templates/deploy.body.sh:30`). Section A is legacy-tolerant, not dead code that misbehaves — but the
  docs that describe pre-deploy copies as the live rollback point are stale.

## State

**Partial.** The scripts are complete and self-consistent (rotation guard proven by a test that I ran successfully
on 2026-11-05 in a temp dir), and the drill has produced two PASS receipts on the live host
(`docs/tickets/45-backups-and-rotation.md:43-48`). It is not "working" as a backup system because: off-box is
disabled by default and no off-box target exists (`slumsms-nightly-backup:36,106-109`,
`docs/tickets/109-test-backup-infra.md:9`); rotation defaults to `dry-run`, so nothing is actually deleted until an
operator sets `ROTATE_MODE=apply` (`slumsms-rotate-backups:34`, `install.sh:84`); and installation status cannot be
verified from this repo (no host access this review).

## Open questions

1. Is the kit installed on the live host, and is `ROTATE_MODE=apply` set? `install.sh` enables only the rotation timer
   (`install.sh:68-71`) and `README.md:14` says the drill timer needs `--with-drill`; the docs claim installation
   happened (`docs/architecture/ops/hosts.md:51`) while `docs/ops/lxc125-architecture.md:64-66` says it has not.
2. Does the monthly drill timer run on 164 or on the test host? The script's paths and `-h127.0.0.1 -P3307`
   (`slumsms-restore-drill:34-35,73`) imply the test box; `hosts.md:51` lists the unit on 164. Which host actually has
   `/opt/mapleroot-dev/restore-drill/receipts/` after the 1.6.3 cutover is unknown.
3. How would a human perform a real restore? The only documented procedure is manual and partial — "with the server
   stopped, `zcat mapleroot-<ts>.sql.gz | mysql …` and `tar -C <Server>/cache/bot-nav -xzf bot-nav-<ts>.tar.gz`"
   (`deploy/backup/README.md:42-44`) — there is no rehearsed whole-host recovery, and `docs/PROJECT-STATUS.md:64-75`
   states an exact recovery "has not been rehearsed" and that these are application backups, not OS snapshots.
4. Is the off-box passphrase file definitely out of the failure domain? `install.sh:61-67` tells the operator to copy
   it into a password manager, and `slumsms-backup.conf.example:20` says "KEEP A COPY OFF THIS HOST", but nothing
   verifies that anyone did; if the host dies with the key on it, every off-box copy is unreadable.
5. Should the nightly also archive the patch feed and `config.yaml`? Today only the release backup holds a config
   (`deploy.body.sh:202`) and nothing holds the published feed (`tools/release/publish-client:60-61`).
6. Are the test-deploy backups (`tools/ops/deploy-test:73`) supposed to be rotated by the kit? Neither the script nor
   the cleanup review assigns an owner, and the depth/name filter in `slumsms-rotate-backups:147-154` excludes them.
