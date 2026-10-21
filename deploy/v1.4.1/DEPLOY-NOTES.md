# SlumsMS 1.4.1 deploy notes (live 1.4.0 -> 1.4.1)

Prepared 2026-10-21, **not run**. Server commit `b4c59e0ff` (branch `release-1.4.1`, from `test-1.4.1-rc1` tip
`1a7f00465` + the catalogue commit), jar `4f2d879c8e3dc179` (SLUMSMS_VERSION 1.4.1; suite 3,238 run, 0 failures,
0 errors, 20 skipped). Client branch `release-1.4.1` of SlumsMS-Client at `2e3d368`. Rehearsal: `REHEARSAL.md`
next to this file.

## Rehearsal

### 2026-10-21 19:31-19:36 UTC: PASS

`rehearse.sh --source live` on 164 ran the staged `deploy.sh` and `rollback.sh` on a copy of live: a fresh
`mysqldump --single-transaction` of live in scratch schema `rh_1_4_1` (test DB 3307), live's Server/ copied with its
own wz/ to `/opt/mapleroot-dev/release-1.4.1-rehearsal` (1.2 GB, left in place, unit stopped), transient unit on
8519/8885+ (Nice=19, CPUQuota=200%, MemoryMax=6G). Full receipt: `REHEARSAL.md` here. 27/27 checks PASS.

- deploy.sh PASS: downtime 21.6 s (stop 12.5, backup 3.2, ledger check 0.0, config+WZ+install 0.8, start 5.1);
  0 ERROR/Exception lines (old boot, 1.4.1 incl. a 60 s soak, after rollback); `SlumsMS v1.4.1`, world 1 Bera, no
  world 0, catalogue 10,363 rows incl. 5782000, SoloMapling data 24/24, both flags true and the rest of config.yaml
  unchanged (CHANNEL_LOAD 300, 8 channels), ledger 001-010, portal file differs from live's in exactly the one
  `west00 -> out00` line.
- All 7 live characters logged in end to end on 1.4.1 with the prism on (headless client to SET_FIELD; scratch
  passwords); no "Coloring Prism stays off" / table-check line after the logins.
- `!qa`: staged jar with live's unit `-D` flags -> qa=false, attackcapture=false, `qa`/`classtest`/`botshowcase`
  not registered; control with `-Dslums.qa=true` registers `qa`; the running unit has neither flag.
- WZ step re-run with `--apply`: 0 values, file hash unchanged.
- rollback.sh PASS (downtime 12.0 s): back on 1.4.0 (jar `218111b2bd784074` = live's), ledger 001-010, the portal
  file, config.yaml, catalogue and scripts equal live's.
- Player rows before vs after rollback: identical except the bot template SMBotBase (id 6) gained 7 skills and a
  storage row because the probe logged it in (same as in 1.4.0's rehearsal); not from the release.
- Against live (19:36 UTC): `deploy.sh --dry-run` all pre-checks pass (portal dry run 1 value / 0 skipped, ledger
  001-010, 0 players online, the installed monitor now has `maintenance`: the Cody post would go out);
  `rollback.sh --dry-run` correctly refuses (no backup yet).
- After the rehearsal only `MAINT_REASON` in deploy.sh was shortened (the monitor cuts reasons at 120 chars; the
  rehearsal runs with MONITOR=""), re-staged, and the live dry run repeated: PASS.

## Run (owner, on 164)

```
bash /opt/mapleroot-deployments/v1.4.1/deploy.sh --dry-run    # pre-checks against live, changes nothing
bash /opt/mapleroot-deployments/v1.4.1/deploy.sh
```

Pick a quiet moment (the script prints players online before it stops anything). Expected downtime well under a
minute (stop with the bots ~25 s, backup ~5 s, config + WZ + install ~2 s, start to online ~5-10 s). The
maintenance post as Cody uses: "SlumsMS 1.4.1: the Coloring Prism is back, Fredrick and Rosen fixed, guild creation fixed, smarter bots." (only if the installed `slumsms-monitor` has the `maintenance`
command; otherwise the script says so and carries on).

## What it changes

| What | Before (1.4.0) | After (1.4.1) |
|---|---|---|
| Jar | `218111b2bd784074` (v1.4.0 = `9a3c9d1e8`) | `4f2d879c8e3dc179` (`b4c59e0ff`) |
| DB | ledger 001-010 | unchanged: no migrations. deploy.sh refuses unless the ledger is exactly 001-010 and both prism tables exist |
| `config.yaml` under `server:` | `DAILY_CHECKIN_ENABLED: true`, no `COLOR_PRISM_ENABLED` | + `COLOR_PRISM_ENABLED: true` (added in place, CRLF kept, a parse checks nothing else changed). `CHANNEL_LOAD` 300 and the worlds block (8 channels) unchanged |
| Server WZ | 271010400 portal `north00` tn `west00` | `out00` (`tools/data-align/portal-fixes.tsv`, `wz_set_values.py --column new_value`: 1 value in 1 file; the dry run on live today: 1 value, 0 skipped) |
| `cashshop/catalog.tsv` | 10,362 rows (prism and 355 newer pet equips hidden) | 10,363 rows: the Coloring Prism 5782000 is sold again (3,000 NX); the newer pet equips stay hidden until the client is out a day |
| scripts, `src/main` data | 1.4.0 | 1.4.1 (restored from stock Cosmic: `scripts/npc/2110000.js` Rosen, Magatia's storage keeper, had no script; `9030000.js` Fredrick claims closed-merchant items again; `merchant.clearItems()` after a retrieval is in the jar) |
| Unit, ports, worlds block, template id | 8485, 7585-7592, Bera, 6 | unchanged. Live's unit has neither `-Dslums.qa` nor `-Dslums.attackcapture`, so `!qa`, `!classtest`, `!botshowcase` stay unregistered (checked on the staged jar with live's flags) |

1.4.1 also carries (jar only): Cosmic restores (Rosen, Fredrick), guild creation with bots around (50A), the bot
shop buy cap (56A), bots weigh 0.25 on the channel load bar, bots stop hanging on ladder/rope tops and ignore a GM in
/hide, 4th-job bot skills checked against the client's attack rules, the clientcheck tool, ticket statuses, the
release notes template, the `!qa` suite (test servers only), docs.

Aborts **before stopping live** if: a staged file's hash differs; the installed jar is not 1.4.0's; the ledger is
not 001-010 or a prism table is missing; the server WZ lacks the prism item 5782000; a flag has an odd value; the
portal row neither holds `west00` nor `out00`; the server folder or a tool is missing; a backup is already there;
less than 4 GB free. After the stop every abort says "server is DOWN - run rollback.sh".

Backups (`/opt/mapleroot-deployments/v1.4.1/backup/`): `mapleroot.sql.gz` (mysqldump `--lock-tables`, server
stopped), `server-runtime.tgz` (jar, config.yaml, scripts, src/main, cashshop), `wz-before.tgz` + `wz-before.list`
(the portal file as it was), `config.yaml`, `slumsms.service`, `old-jar.sha256`. `wz_set_values.py` also keeps its
own copy in `Server/wz-backup-<UTC>/`.

## Smoke test (scripted, 30 s after "is now online")

Log has `SlumsMS v1.4.1 (MapleStory v83) starting up`, `Starting world 1 (Bera)`, `Finished loading world 1`,
`is now online`, `SoloMapling data: 24/24 resolved`, `Cash Shop catalog: 10363 rows`; none of `Starting world 0`,
`failed to load`, `migration 010`, `Coloring Prism stays off`, `Could not check for the Coloring Prism tables`,
`built-in reward table`, `SoloMapling data file not found`, `Cash Shop catalog line`; 0 ERROR/Exception lines;
ports 8485 and 7585; ledger 001-010; `COLOR_PRISM_ENABLED` true. `RECEIPT.md` is written here.

In game (owner): log in, enter a character, change channel; buy a Coloring Prism and dye an equip, hair, eyes
(client 1.4.1); talk to Fredrick and Rosen; create a guild; watch a bot climb a ladder.

## Rollback

```
bash /opt/mapleroot-deployments/v1.4.1/rollback.sh --dry-run
bash /opt/mapleroot-deployments/v1.4.1/rollback.sh
```

Keeps player progress. No SQL: stops; restores the 1.4.0 jar + config.yaml together (the prism flag goes away),
scripts, src/main, cashshop and the portal WZ file from the backup; starts. The deploy's files go to
`backup/rolled-back-<time>/`. Dye rows made on 1.4.1 stay in `equip_tints` / `character_look_tints` (1.4.0 runs
with the prism off and does not read them). Prisms bought on 1.4.1 stay in inventories (unusable while off). Full
restore instead: `backup/mapleroot.sql.gz` (loses progress since the deploy).

## Client 1.4.1 publish (separate go from the owner; after the server is up)

Client build: SlumsMS-Client `release-1.4.1` = `2e3d368` ("Pick the eye colour variant from the absolute Prism
Tone"), built on Silvia in `Z:\MSv83\SlumsMS-dev\client-1.4.1-eyes` (clean checkout at `2e3d368`): `slums.dll`
`af22aeb136bc8235b2424d015c48a7795529e308c3a37162748e3b6b52a695d6`, `Custom.wz`
`806727bfecc973292e5432e6ab4aa5ade306019ff82a175ef2ff26a84a1d377f`.

Already done (2026-10-21): `tools/release/stage-client 1.4.1 --build-dir 'Z:\MSv83\SlumsMS-dev\client-1.4.1-eyes\src\build\Release'`
-> `/srv/slums-staging/client-1.4.1/` (+ `client-1.4.1.STAGED.txt`), copied from layer `client/1.4.0`, only
`slums.dll` and `Custom.wz` replaced; HASH-DIFF PASS (vs release 1.4.0: 2 changed, 0 added, 0 removed; vs the
reference client: 0 NOT allowed). News copied to `/srv/slums-staging/news-1.4.1.json`. Publish dry run: PASS.

Host publisher: `/opt/slums-tools/launcher` = launcher `ops-first-install` `b4b310e` (hash `7f7861277a1d4c3b`).
`/root/work/launcher` is on `ops-game-options` (`76527ee`, hash `da6c654825ee444f`), so the default check would
fail. A worktree `/root/work/launcher-publisher` (detached at `b4b310e`) matches the host; pass it as
`LAUNCHER_REPO` (no `--sync-publisher`, the host publisher is not changed).

`--no-packs` (new in `publish-client` on `release-1.4.1`; passed to `publish.mjs --release`): the 1.4.1 manifest
references no first-install packs; the launcher/packs release is separate.

Publish (from the workstation, in `/root/work/SlumsMS` on `release-1.4.1`):

```
LAUNCHER_REPO=/root/work/launcher-publisher tools/release/publish-client 1.4.1 --source /srv/slums-staging/client-1.4.1 --release 1.4.1 --news /srv/slums-staging/news-1.4.1.json --no-packs
```

It hash-diffs before and after, publishes layer `client/1.4.1`, backs up `manifest.json` to
`/srv/slums-staging/manifest-before-1.4.1.json`, writes the 1.4.1 manifest last, mirrors it to `/launcher-test/`
and posts Cody's client-ready line (`--no-announce` skips it).

## After

- Tag `v1.4.1` in SlumsMS (`b4c59e0ff`) and SlumsMS-Client (`2e3d368`); merge `release-1.4.1` to main.
- A day after the client is out: un-hide the newer pet equips (`tools/cashshop/overrides.yaml`), rebuild the
  catalogue, `!reloadcashshop`.
