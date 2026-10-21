# SlumsMS 1.4.0 deploy notes (live 1.3.1 -> 1.4.0)

Prepared 2026-10-21, **not run**. Server commit `6a863e7be` (branch `release-1.4.0`, from `test-rc5`), jar
`14fe528b54ea58e7` (SLUMSMS_VERSION 1.4.0; suite 3,155 run, 0 failures, 12 skipped). Client branch `release-1.4.0`
of SlumsMS-Client. Rehearsal: `REHEARSAL.md` next to this file.

## Rehearsal (2026-10-21 07:52-07:56 UTC): PASS

`rehearse.sh` on 164 ran the staged `deploy.sh` and `rollback.sh` on a copy of live: today's nightly dump
(`mapleroot-20261021T070834Z.sql.gz`, SHA256SUMS ok) in scratch schema `rh_1_4_0` (test DB 3307), live's Server/
copied with its own wz/ to `/opt/mapleroot-dev/release-1.4.0-rehearsal` (1.2 GB, left in place, unit stopped),
transient unit on 8519/8885+ (Nice=19, CPUQuota=200%, MemoryMax=6G). Full receipt: `REHEARSAL.md` here.

- deploy.sh PASS: downtime 22.2 s (stop 12.0, backup 3.0, migrations 0.5, config+WZ+install 1.1, start 5.1);
  0 ERROR/Exception lines; `SlumsMS v1.4.0`, world 1 Bera, catalogue 10,718 rows, SoloMapling data 24/24, both flags
  true, five tables, ledger 001-010, prism not disabled, WZ item present, 0 mob values pending.
- All six live characters logged in end to end on 1.4.0 (headless client to SET_FIELD; scratch passwords).
- Each migration run a second time: no error, no change. WZ prism step re-run: nothing to do.
- rollback.sh PASS (downtime 12.3 s): back on 1.3.1, ledger 001-003,005-008, tables gone, the 593 WZ files equal
  live's, prism image removed, config.yaml identical; each rollback SQL re-run: no error.
- Player rows before vs after rollback: identical except the bot template SMTemplate (id 6) gained 7 skills and a
  storage row because the probe logged it in; not from the release.
- `deploy.sh --dry-run` and `rollback.sh --dry-run` against live: deploy pre-checks all pass (2 players online at the
  time); rollback correctly refuses (no backup yet).

## Run (owner, on 164)

```
bash /opt/mapleroot-deployments/v1.4.0/deploy.sh --dry-run    # pre-checks against live, changes nothing
bash /opt/mapleroot-deployments/v1.4.0/deploy.sh
```

Pick a quiet moment (the script prints players online before it stops anything). Expected downtime well under a
minute: stop with the bots ~20 s, backup ~5 s (DB + runtime + 593 WZ files), migrations < 1 s, config + WZ + install
~5 s, start to online ~10 s. A maintenance post as Cody goes out before the stop and "back up" after, if the
installed `slumsms-monitor` has the `maintenance` command (today's installed copy does not, and live has no
`/etc/slumsms-monitor/live.conf`: the script then says so and carries on without a post).

## What it changes

| What | Before (1.3.1) | After (1.4.0) |
|---|---|---|
| Jar | `2234665001f815ba` | `14fe528b54ea58e7` |
| DB | ledger 001-003, 005-008 | + `004-daily-checkin` (daily_checkin), `009-gm-audit` (gm_command_audit, gm_level_changes), `010-color-prism` (equip_tints, character_look_tints); each only adds tables, ledger-checked, in that order |
| `config.yaml` under `server:` | no such keys | `DAILY_CHECKIN_ENABLED: true`, `COLOR_PRISM_ENABLED: true` (added in place, CRLF kept, verified by a parse that nothing else changed). Strict config: the 1.3.1 jar refuses these keys, so jar and config only ever move together |
| Server WZ | MapleRoot repack values | Mob PADamage/MADamage = the client values players already face (`wz_set_values.py --column client_value`, 1,141 values in 592 Mob.wz files); item 5782000 Coloring Prism (`install_wz.py`: new `Item.wz/Cash/0578.img.xml`, its name in `String.wz/Cash.img.xml`) |
| `cashshop/catalog.tsv` | old catalogue | generated catalogue, 10,718 rows |
| scripts, `src/main` data | 1.3.1 | 1.4.0 |
| Unit, ports, worlds block, template id | 8485, 7585-7592, Bera, 6 | unchanged |

Aborts **before stopping live** if: a staged file's hash differs; the ledger is not exactly 001-003,005-008; one of
the five new tables already exists; a flag has an odd value; the server folder or a tool is missing; a backup is
already there; less than 4 GB free. After the stop every abort says "server is DOWN - run rollback.sh".

Backups (`/opt/mapleroot-deployments/v1.4.0/backup/`): `mapleroot.sql.gz` (mysqldump `--lock-tables`, server
stopped), `server-runtime.tgz` (jar, config.yaml, scripts, src/main, cashshop), `wz-before.tgz` + `wz-before.list`
(every WZ file either WZ step can touch, as it was), `config.yaml`, `slumsms.service`, `old-jar.sha256`. The WZ tools
also keep their own copies in `Server/wz-backup-<UTC>/`.

## Smoke test (scripted, 30 s after "is now online")

Log has `SlumsMS v1.4.0 (MapleStory v83) starting up`, `Starting world 1 (Bera)`, `Finished loading world 1`,
`is now online`, `SoloMapling data: 24/24 resolved`, `Cash Shop catalog: 10718 rows`; none of `Starting world 0`,
`failed to load`, the prism's "migration 010 ... stays off", `built-in reward table`, `SoloMapling data file not
found`, `Cash Shop catalog line`; 0 ERROR/Exception lines; ports 8485 and 7585; ledger 001-010; all five tables.
`RECEIPT.md` is written here.

In game (owner): log in, enter a character, change channel; claim Daily Check-In; open the Cash Shop; with client
1.4.0, use a Coloring Prism (5782000 shows its name), open the Monster Book.

## Rollback

```
bash /opt/mapleroot-deployments/v1.4.0/rollback.sh --dry-run
bash /opt/mapleroot-deployments/v1.4.0/rollback.sh
```

Keeps player progress. Stops; saves the rows of the five 1.4.0 tables to `backup/rolled-back-<time>/new-tables.sql.gz`;
runs `010`, `009`, `004` rollbacks (only those in the ledger); restores the 1.3.1 jar + config.yaml together, scripts,
src/main, cashshop and every WZ file from `wz-before.tgz` (the new prism item image is moved aside); starts. The
deploy's files go to `backup/rolled-back-<time>/`. Full restore instead: `backup/mapleroot.sql.gz` (loses progress
since the deploy). Players on client 1.4.0 can stay on it against 1.3.1 (the new client windows just get no reply).

## Client 1.4.0 publish (separate go from the owner; after the server is up)

Client build: SlumsMS-Client `release-1.4.0`, built on Silvia in `Z:\MSv83\SlumsMS-dev\client-1.4.0` (ctest 8/8):
`slums.dll` `d0cef506ac5a1a1cbd2e104c5235329f1f0332e5f0ff8b1107761236255d70b4`, `Custom.wz`
`1708eee96f8e004d729265bfd81877e07e4be34a55e096d9ea533167f89f21ae`. Hash-diff against the test client: only these two
differ; `SlumsMS.exe` differs only in its linker timestamps (0x108, 0x3AC4) and the published one is kept.

1. From the workstation, stage the client tree on 164 (writes only `/srv/slums-staging/client-1.4.0/` and
   `client-1.4.0.STAGED.txt`; ends with the HASH-DIFF dry run):
   `tools/release/stage-client 1.4.0 --build-dir 'Z:\MSv83\SlumsMS-dev\client-1.4.0\src\build\Release'`
2. Copy the news file: `scp deploy/v1.4.0/news-1.4.0.json root@192.168.0.164:/srv/slums-staging/news-1.4.0.json`
   (it is also in `/opt/mapleroot-deployments/v1.4.0/news-1.4.0.json`).
3. Dry run: `tools/release/publish-client 1.4.0 --source /srv/slums-staging/client-1.4.0 --release 1.4.0 --news /srv/slums-staging/news-1.4.0.json --dry-run`
   (the host publisher differs from the launcher repo today: add `--sync-publisher` if the owner agrees).
4. Publish: the same without `--dry-run`. It hash-diffs before and after, publishes layer `client/1.4.0`, backs up
   `manifest.json` to `/srv/slums-staging/manifest-before-1.4.0.json` and writes the 1.4.0 manifest last.
5. Launcher: Press Play updates; check SlumsMS.exe starts, the Coloring Prism shows its name, the Monster Book opens.

## After

- Tag `v1.4.0` in SlumsMS and SlumsMS-Client; merge `release-1.4.0` to main.
- `/etc/slumsms-monitor/live.conf` does not exist on 164 (only `live.env`); `deploy/monitor/targets/live.conf` now says
  `VERSION=1.4.0` for when the monitor is installed for live (`deploy/monitor/install.sh live`).
