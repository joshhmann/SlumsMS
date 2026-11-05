# TEST deploy

## Purpose

`tools/ops/deploy-test` is how a branch reaches the TEST box (`mapletest`, 192.168.0.111) without anyone hand-copying a
file: it builds the jar, works out which data files and migrations changed since the commit TEST last ran, backs up what
it will overwrite, copies across one ssh hop, migrates, restarts and reads the boot log. The rest of the folder is the
reading side of that loop: `test-status` reports what TEST runs, `lib.sh` is the shared host guard, `sql/` holds two
hand-run repairs. Live is not this pipeline — live is the owner running `tools/release` on 164.

## Entry points

| Command | Arguments | Reaches |
|---|---|---|
| `tools/ops/deploy-test` | `--feature`, `--skip-build`, `--dry-run`, `--since <commit>`, `--no-migrations` (`tools/ops/deploy-test:5,27-39`) | TEST only (`:42`); `--dry-run` still reads `DEPLOYED_COMMIT` unless `--since` is passed (`:47-50`) |
| `tools/ops/test-status` | `--feature`, `--since "30 min ago"`, `--checks` (`tools/ops/test-status:12-23`) | TEST, read-only (`:25`) |
| `tools/ops/helper-checkout [worktree]` | one path, default `.` (`tools/ops/helper-checkout:6`) | local git; `git ls-remote` at `:30` is its one network call |
| `tools/ops/test_testhost.sh` | none (`:4`) | nothing — ssh is stubbed (`:11-17`) |
| `tools/ops/sql/*.sql` | pasted into the TEST MySQL by hand | `mapleroot_persist`; the exchange one is FOR REVIEW/NOT RUN (`tools/ops/sql/exchange-test-cleanup.sql:2-4`) |

`-h`/`--help` prints the header block (`tools/ops/deploy-test:35`, text `:2-17`); an unknown flag exits 2 (`:36`). No
argument reaches a second host: the one ssh hop is `lib.sh:23`.

## How it works

One local half, one remote half: nothing is decided on the box that the workstation could have refused first. Line
numbers are `tools/ops/deploy-test` unless the cite names another file.

1. **Resolve the box.** `lib.sh` runs `testhost.py --shell`; a refused value returns 1 before any ssh
   (`tools/ops/lib.sh:12-15`). `TEST_HOST` and key mode return on one line (`:16`), with `BatchMode=yes`,
   `ConnectTimeout=15` and `-i ~/.ssh/id_slums_lxc` when the mode is `test` (`:17-20`).
2. **Fix the target and refuse live.** `BASE=/opt/mapleroot-dev/persistence-test`, `SRV=$BASE/Server`, unit
   `slums-persistence-test`, container `mapleroot-integration-db`, schema `mapleroot_persist`
   (`tools/ops/deploy-test:21-25`); `--feature` rewrites all four to the bot-life copy (`:30`). Then
   `case "$SRV" in *"/opt/mapleroot/"*|*"Full Repack"*) ... exit 1` (`:42`).
3. **Name the build and its baseline.** `HEAD` = `git rev-parse --short=9`; `VERSION` scraped from
   `src/main/java/constants/net/ServerConstants.java` (`:43-44`; literal `1.6.4` at that file's `:10`). `--since`, else
   `cat $SRV/DEPLOYED_COMMIT` over ssh — neither is a hard stop (`:47-50`) — and the commit must exist here (`:51`).
4. **Diff three sets, then stop if dry.** `DATA` added/modified, `.java` excluded, under `scripts src/main cashshop`
   (`:55-56`, so `src/main/resources` and the non-`.java` SoloMapling data ride along); `GONE` deleted, same pathspec
   (`:57-58`); `MIGS` added `database/migrations/*.sql` minus `*.rollback.sql`, sorted (`:59-60`). Counts and every path
   print, then `[ $DRY = 1 ] && { echo "dry run: nothing changed"; exit 0; }` (`:61-63`) — before the build `:65` and the
   first `mkdir` `:76`, the only branch that writes nothing.
5. **Build.** Under `if [ $SKIP_BUILD = 0 ]` (`:65`): `cp config.example.yaml config.yaml` only when absent, then
   `mvn -q -o -DskipTests package` (`:66-68`) — offline, suite not run here (the banner at `:67` says run it first), so
   `--skip-build` also skips the config copy. Then `target/Cosmic.jar` (assembly `finalName`, `pom.xml:317`), first 16
   hex of sha256, UTC stamp, `D=$BASE/deploys/$STAMP` (`:70-74`).
6. **Copy up, then re-check remotely.** `mkdir -p $D/files`, scp jar, tar data to `/tmp/deploy-test-$$.tgz` and scp
   it, scp each migration, stream `deleted.txt`/`DEPLOY.txt` over ssh stdin (`:76-81`); the remote half then requires
   `case "$BASE:$UNIT:$DBN"` to match one of two triples, else "refusing unknown test target" (`:88-91`). Note the
   upload happens *before* that check.
7. **Stop, then back up.** `systemctl stop`, poll `is-active` 60×5 s, `reset-failed` (`:94-96`). `cp -p` the running jar
   to `$D/Cosmic.jar.before` (`:98`); with data going out, re-tar the current copies into `$D/data.before.tgz`
   (`:100`), then `tar xzf -C $SRV` (`:101`); each deleted path moves to `$D/gone/<path>` (`:103`).
8. **Migrate.** Only with `MIGRATE=1` and a `$D/files/*.sql`: `mysqldump --single-transaction | gzip` to
    `$D/db-before.sql.gz` (`:106`), then per file in name order, id = basename, a `SELECT COUNT(*) FROM
    mapleroot_integration_migrations` decides skip vs apply, file piped in via `docker exec -i` (`:107-111`). The
    password is read inside the container, never printed (`:92`, `:106`).
9. **Install.** `cp $D/Cosmic.jar $SRV/target/Cosmic.jar`, then `echo "$HEAD" > $SRV/DEPLOYED_COMMIT` (`:114-115`) —
    both before the restart.
10. **Restart and watch.** `S=$(date -u +%H:%M:%S)`, `bash $BASE/start-test.sh`, then 90×5 s polling
    `journalctl --since $S` for `Auto-QA on|is now online` (`:116-118`) — markers that do exist
    (`src/main/java/net/server/Server.java:925,1025`, `.../BotPopulationDirector.java:427`, `.../AutoQa.java:103`).
11. **Gate.** Grep the four banners for the operator, count `ERROR|Exception`, print `is-active`, and
    `[ "$E" = 0 ] || { ... BOOT HAS ERRORS ... exit 1; }` (`:119-123`); the local half then prints "done: test server
    runs ..." (`:125`).

**Failure gates** — what trips, and what is already written when it does:

| Gate | Line | On trip | Already on the box |
|---|---|---|---|
| boot `ERROR`/`Exception` | `:120,123` | "BOOT HAS ERRORS", exit 1 | new jar, new data, migrations, marker |
| never online | `:118` | **nothing** — no post-loop check; deploy reports success. `bash $BASE/start-test.sh >/dev/null 2>&1` (`:117`) also drops a non-zero exit, so a start script that fails looks the same as a slow boot | everything |
| unit not `active` | `:122` | `is-active` exits 3 before the gate; no message | everything |
| missing/unknown `config.yaml` key | none | server aborts at startup, so it surfaces as a boot `Exception` (`AGENTS.md:110-112`: config is strict). The script never validates keys — `:65-66` only copies `config.example.yaml` when the file is absent | new jar |
| migration error | `:110` | `set -euo pipefail` (`:84`) aborts silently; no dump restore, no `rollback.sql` | DB dump, old jar, **not** the new one (`:114` is later) |
| no old jar / unresolvable `--since` | `:98`, `:47-51` | `cp: cannot stat` abort after the stop `:94`; `--since` fails earlier with a message | jar case: unit down; `--since` case: nothing |

Nothing rolls back: `:123` only prints "roll back with the files in $D". Recovery is manual — `cp` the `.before` jar,
untar `data.before.tgz` (spaced paths missing), `zcat db-before.sql.gz`.

`start-test.sh` is not in this repo, so TEST's `-D` set is **unknown** (`docs/architecture/README.md:56-57`). **Live**
is the same mechanics through `tools/release`, with more checks: `SHA256SUMS` verify and a config dry-run
(`tools/release/templates/deploy.body.sh:118,166`), no overwriting an existing backup (`:158`), a free-space floor
(`:161`), a ledger pre-check (`:151`), a `wait_online` that fails the deploy through `down()` (`:78-81,270`), and a
generated `rollback.sh` (`:57`). No `tools/ops` script may be used for live.

The read side, same host guard: `tools/ops/test-status` sends one `bash -s` (`:25`, no remote `set -e`, `:26`) reading
`ActiveEnterTimestamp`, `cat $S/DEPLOYED_COMMIT` (`:29`), the banner and an `ERROR` count with the top five clusters
(`:28-32`), then parses `data/autoqa/{health-latest,latest,bot-life}.json` locally (`:33-82`). `tools/ops/helper-checkout`
is the pre-handoff lint: `^164-` branch (`:13`), clean tree ignoring `REPORT*.md`/`config.yaml` (`:25-26`), `pushurl` =
`DISABLED` (`:28-29`), `Co-Authored-By` (`:34-37`), `src/test` when `src/main` changed (`:40-43`), FAIL on a committed
`config.yaml` (`:47`) or a secret pattern (`:49-50`).

## Data

| Written | Where | When |
|---|---|---|
| `target/Cosmic.jar` | workstation, then `$SRV/target/Cosmic.jar` | build `:68`, install `:114` |
| `deploys/<stamp>/{Cosmic.jar,data.tgz,files/*.sql,deleted.txt,DEPLOY.txt}` | TEST | upload `:76-81`; `DEPLOY.txt` holds `HEAD VERSION SHA` |
| `deploys/<stamp>/{Cosmic.jar.before,data.before.tgz,gone/**,db-before.sql.gz}` | TEST | backups `:98-106`; the dump only when a migration file is present |
| `$SRV/DEPLOYED_COMMIT` | TEST | after the jar copy, before the restart `:115` |

`deploys/<stamp>/` is the durable record and **nothing prunes it**: rotation clears only `backup-*` and
`Cosmic.jar.pre-*` under `/opt/mapleroot-dev` older than `TEST_KEEP_DAYS=14`
(`deploy/backup/slumsms-rotate-backups:14,36,150-153`). `DEPLOYED_COMMIT` is read by the QA runner
(`tools/qa-runner/qarunner.py:50,135`), so it becomes the server commit in every run manifest. TEST's `config.yaml` and
`start-test.sh` are never touched and nothing backs them up — `AGENTS.md:138` and
`.claude/skills/deploy-test/SKILL.md:12-13` say `cp -p` them by hand. Secrets are only ever paths
(`docs/qa/social-test-runbook.md:14`); the DB password is read inside the container (`tools/ops/deploy-test:92`).

## Switches

| Switch | Home | Default | Notes |
|---|---|---|---|
| `SLUMS_TEST_HOST` | env, read only by `tools/ops/client-mcp-win/src/testhost.py:21,48-58` | `192.168.0.111` (`:24`) | allowlist is exactly `192.168.0.164` and `192.168.0.111` (`:23`); empty/unknown refused, exits 2 (`:38-45,79-80`) |
| ssh key mode | same file | `id_slums_lxc` for 111, ssh default for 164 (`:25,70-72`; `tools/ops/lib.sh:18-20`) | |
| `--feature` | flag | off | `/opt/mapleroot-dev/bot-life-160`, `slums-life160`, `mapleroot_life160` (`tools/ops/deploy-test:30`) |
| `--skip-build`, `--no-migrations`, `--since`, `mvn -o` | flags / hard-coded `:68` | build on, migrations on, `--since` from `DEPLOYED_COMMIT` (`:27,47-48`); Maven offline, needs a warm `~/.m2` | |
| TEST `-D` set | `start-test.sh` on the box | **unknown** | not in this repo |
| `TEST_KEEP_DAYS` | `/etc/slumsms-backup.conf` | `14` (`deploy/backup/slumsms-rotate-backups:36`) | does not cover `deploys/` |
| push url | `git config --worktree` | must be `DISABLED` | `tools/ops/helper-checkout:28-29` |

## Invariants

- **TEST is reached only through one allowlist, and no live path starts a deploy.** `tools/ops/lib.sh:12-15` refuses
  and `return 1`s before any ssh; `testhost.py:56-58` is the only validator, and `tools/ops/test_testhost.sh:49-52`
  asserts every TEST tool sources `lib.sh` and none hard-codes `root@192.168.0.164`. `tools/ops/deploy-test:42` then
  refuses a live path, repeated remotely as a target triple check (`:88-91`).
- **Back up before writing.** Old jar `:98` and `data.before.tgz` `:100` precede the extraction `:101`; the DB dump
  `:106` precedes the first `db < "$m"` `:110`; deleted files are archived by `mv`, never `rm` (`:103`).
- **Nothing secret is printed.** `mysql`/`mysqldump` read `$MYSQL_ROOT_PASSWORD` in the container and drop stderr
  (`:92`, `:106`).
- **`--dry-run` writes nothing.** `:63` exits before the build and the first `mkdir`; its only side effect is the ssh
  read at `:48`.
- **A migration applies at most once.** The ledger row is the check, not the filename (`:109-110`), and every migration
  inserts its own id (`database/migrations/065-lkc-quest-drops.sql:73`; all 61 forward ones do).
- **Spaced filenames survive the forward copy only.** `tar czf "${DATA[@]}"` / `tar xzf -C $SRV` keeps them (`:78`,
  `:101`) — four files under `scripts/` have spaces today — but the `data.before.tgz` backup does not (see Known issues).

## Origin

All **custom**: `git cat-file -e 03d13cb21:tools/ops/deploy-test` and `:tools/ops/lib.sh` both fail, so none of this
exists at the baseline. Oldest commits — `deploy-test`/`test-status` `383b0df55` (2026-10-23); `lib.sh`/`test_testhost.sh`
`243fd5591`, `client-mcp-win/src/testhost.py` `ad8a48f58` (2026-11-01); `helper-checkout` `6d18f961e` (2026-11-03);
`sql/exchange-test-cleanup.sql` `612786171`, `sql/family-orphans.sql` `04d3b8eb8`; `test_autoqa_run.sh` `027460e8d`,
`test_autoqa_proof.py` `af7a68986`, `test_feature_health.py` `00a2131bb` (2026-10-30/11-04). `5ee366e1c` then flipped the
default host to 111 after `243fd5591` introduced `SLUMS_TEST_HOST`.

## Tests and QA

| Test | Covers | Does not cover |
|---|---|---|
| `tools/ops/test_testhost.sh:19-74` and `tools/ops/client-mcp-win/tests/test_qatools.py:243-295` | the allowlist from both sides: default host+key, override, four refused values, 164 keeping the ssh default key, `8485` refused, `deploy-test --dry-run` naming its host and refusing the live name | `deploy-test`'s remote half |
| `tools/qa-runner/test_qarunner.py:274-291` | the runner reads `.../Server/DEPLOYED_COMMIT` | that a deploy wrote a truthful one |
| `tools/ops/test_autoqa_run.sh:7-15`, `test_autoqa_proof.py:30-197`, `test_feature_health.py:22-71` | queue order, globbing and change→check mapping, feature rules on fixture JSON (incl. a live host refused) | deploys |

Nothing automated runs `deploy-test`'s remote block, `test-status`, or either SQL file; `.github/workflows/build.yml`
runs `./mvnw --batch-mode test package` only, so these shell/python tests are run by hand
(`docs/architecture/testing.md`), and no test asserts the box's state after a failed boot.

## Known issues

- **L19** (`/root/work/STATUS-1.6.4.md:110`, open): nothing from 1.6.4 has reached TEST — this pipeline is the gate.
- **b288** (`:84`, S2) and **L18** (`:109`, doing) are both about migrations: release rollback can replay one an earlier
  release applied, and one list serves both sides (`helper-checkout:46` only **warns** when a new migration is unnamed).
  TEST's shape is weaker still — one id checked (`tools/ops/deploy-test:109-110`) vs a whole-ledger pin
  (`deploy.body.sh:151`). **Ticket 109** (`docs/tickets/109-test-backup-infra.md:26-36`) is the LXC move whose phase 3
  these scripts implement.
- **NEW-FINDING:** jar and `DEPLOYED_COMMIT` are written before the restart (`:114-115`), so a failed boot still
  advances the marker; the next `--dry-run` diffs from a build that never ran and QA manifests name it
  (`tools/qa-runner/qarunner.py:50,135`). Reproduced.
- **NEW-FINDING:** the boot wait cannot fail (gate table); success needs only an `active` unit and no
  `ERROR`/`Exception` where the release side checks (`deploy.body.sh:270`). Reproduced: exit 0, "done: test server
  runs ..." on a quiet journal that never came online.
- **NEW-FINDING:** `journalctl | grep -q` under `pipefail` (`:118`) is the SIGPIPE false negative the release template
  fixed with `has()` (`deploy.body.sh:53`); measured locally 5/5 false negatives with `grep -q`, 0/5 with `grep -c`.
- **NEW-FINDING:** `data.before.tgz` pipes `tar tzf` into `xargs` (`:100`), which splits on whitespace, so the backup
  silently omits spaced paths (`scripts/NPC Base.js` and three siblings) — the `.before` archive held only the space-free
  member while the restore archive held both.
- **NEW-FINDING:** `deploys/<stamp>/` is never pruned (Data) and can hold a DB dump per deploy; `S=$(date -u ...)`
  (`:116`) hands `journalctl --since` a UTC clock while journalctl reads local time, so the window is wrong if TEST is
  not UTC (unverified — the box was not read); `tools/ops/client-mcp-win/src/testhost.py:4` still says DEFAULT_HOST is
  164 "today" while `:24` defaults to 111.

## State

**Working on the happy path, partial on failure handling.** The guard chain is real and tested
(`tools/ops/test_testhost.sh` asserts the default and every refusal); the live-path refusal is `:42`, the remote target
check `:88-91`, the ledger check `:109-110`. Failure paths were **reproduced** against a stubbed remote block cut from
`:84-123` (`systemctl`, `journalctl`, `docker`, `sleep` replaced) — nothing reached any host. Not checkable here: whether
TEST's `start-test.sh` sets what a 1.6.4 build needs, whether the box clock is UTC, whether any real `deploys/<stamp>/`
is complete. **L19** says 1.6.4 has never been through this pipeline, so end-to-end state on the 1.6.4 tree is **unknown**.

## Open questions

1. Does `start-test.sh` on mapletest pass the 1.6.4 `-D` set (`slums.qa`, `slums.autoqa`, the bot-life flags)? Not in
   this repo, so every switch table here says "TEST: unknown" (`docs/architecture/README.md:56-57`).
2. `tools/ops/deploy-test:2-3`, `AGENTS.md:135-138` and `docs/architecture/ops/hosts.md:97` name
   `/opt/mapleroot-dev/persistence-test/deploys/<stamp>/`, but `--feature` puts the stamp under
   `/opt/mapleroot-dev/bot-life-160/deploys/` (`:30,73`). Which does an operator read after a feature deploy?
3. The header `:8` and `testhost.py:24` say 111 is the default; `testhost.py:4` says 164 still is. The code wins.
4. Is 450 s enough for a cold start with 3,000 bots? No timing evidence, and the release side allows
   `ONLINE_TIMEOUT=120` for a smaller process (`deploy.body.sh:43`). Should TEST adopt the whole-ledger pin (`:151`) and a
   failing `wait_online`, or is best-effort deliberate so a slow boot never blocks QA?
