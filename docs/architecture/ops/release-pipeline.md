# Release pipeline

## Purpose

`tools/release/` turns one green commit plus one release conf into an immutable version folder on the live host, proves
that folder by deploying and rolling it back on a copy of live, then lets the owner run the deploy with one owner-gated
command (`tools/release/README.md:5-14`, `docs/tickets/44-release-pipeline.md:1-8`). The conf
`docs/release/<version>.conf` is the only per-release input: migrations, config edits, unit edits, smoke lines, the
maintenance notice (`tools/release/templates/release.conf.example:1-3`). Everything live does is generated from that
file, so a bad conf is caught by validation before the stop (`tools/release/gen-deploy:59-71`) or by the rehearsal
(`tools/release/rehearse-host.sh:225-232`).

## Entry points

| Command | Where | What it writes |
|---|---|---|
| `tools/release/hotfix-parity <live-branch> [release-ref]` | workstation | stdout list; exit 1 when a live fix is missing (`tools/release/hotfix-parity:4,12-18`) |
| `tools/release/build [--skip-tests] [--allow-dirty] [--dry-run]` | workstation | `dist/release-build/{Cosmic.jar,BUILD.txt,test.log}` (`tools/release/build:4,12,48-56`) |
| `tools/release/stage <version> [--conf F] [--replace] [--dry-run]` | workstation → 164 | `dist/release-stage/<v>/` then `$DEPLOY_ROOT/v<v>/` (`tools/release/stage:4,25-27,82-85`) |
| `tools/release/rehearse <version> [--source live\|nightly\|dump] [--soak N] [--keep]` | workstation → 164 | `REHEARSAL.md` in the version folder (`tools/release/rehearse:12-14`, `rehearse-host.sh:270-271`) |
| `tools/release/stage-client <client-version> --build-dir 'Z:\…\Release'` | workstation (Silvia → 164) | `/srv/slums-staging/client-<v>/` + `.STAGED.txt` (`tools/release/README.md:16-40`, `stage-client:40`) |
| `tools/release/release <version> [--dry-run] [--yes]` | workstation → 164 | server + client in MMO order, one Cody post (`tools/release/release:6,171-213`) |
| `bash $DEPLOY_ROOT/v<v>/{deploy.sh,rollback.sh,release-live.sh} [--phase …\|--check\|--go]` | 164, owner | `RECEIPT.md`, `ROLLBACK-RECEIPT.md`, `backup/`, run log (`templates/deploy.body.sh:8-17`, `rollback.body.sh:6-10`, `release-live.sh:4-16`) |
| `tools/release/rehearse-release-flow [--only ok\|publish-fail\|start-fail]` | workstation → 164 | `docs/release/release-flow-rehearsal.md` (`tools/release/rehearse-release-flow:4,34`) |

## How it works

1. **Conf written.** `docs/release/<version>.conf` is bash, sourced by `stage` and `gen-deploy` (`gen-deploy:23`); the
   template documents every variable (`templates/release.conf.example:5-54`) and `gen-deploy` defaults the unset ones
   (`:18-22`), so an absent variable means "no change", not an error.
2. **Hotfix parity checked.** `git cherry "$rel" "$live"`, failing on any `+` commit not listed in `hotfix-ported.txt`
   (`hotfix-parity:10-18`); mandatory before staging (`AGENTS.md:244-245`).
3. **Build.** `./mvnw -o -B clean package`; the last `Tests run:` line is re-checked for `Failures: 0, Errors: 0,`
   (`build:31-43`); `BUILD.txt` records commit, branch, version, seconds, tests, jar sha (`:48-56`); a dirty tree is
   refused (`:23-25`).
4. **Stage.** Requires the conf (`stage:31`), a build commit still matching the checkout outside `tools/release` and
   `docs` (`:36-37`), a jar sha equal to `BUILD.txt` (`:39`) and a jar `SLUMSMS_VERSION` equal to the version (`:40`);
   refuses a folder already deployed or staged (`:43-46`); assembles `staging/` (`:49-59`), calls `gen-deploy` (`:60`),
   writes `CHECKLIST.md` (`:62-63`) and `DEPLOY-NOTES.md` (`:64-77`), rsyncs and verifies `SHA256SUMS` on the host
   (`:82-84`). `gen-deploy` reads each migration's INSERT for its ledger id, refusing one that writes no ledger row or
   whose rollback keeps it (`gen-deploy:29-34`), computes `LEDGER_AFTER`, validates `SERVER_SET` against
   `ServerConfig.java` (`:59-69`), then wraps the fixed body and `bash -n`s both scripts (`:96-99`).
5. **Rehearsal.** `rehearse` execs the staged `rehearse-host.sh` on 164 (`rehearse:12-14`) — see below.
6. **Client staged, only if it changed.** `stage-client` hashes `slums.dll`, `slums-build.txt` and `Custom.wz` on Silvia,
   copies and re-hashes, overlays them on a copy of the published layer, refuses a non-empty staging dir
   (`tools/release/README.md:25-33`).
7. **Owner pre-flight.** `release <version> --dry-run` changes nothing: requires `deploy.sh` to carry `--phase`
   (`release:110-112`), runs `deploy.sh --dry-run` against live (`:115`), `publish-client --dry-run` (`:125`) and a
   monitor probe (`:129-138`), prints the plan (`:145-154`), exits (`:155`).
8. **Owner deploy.** Either `release <version>` (stop-backup → publish client while DOWN → apply-start → smoke →
   maintenance end, `release:161-213`), or `release-live.sh --go` (`release-live.sh:36-63`), or the five hand-run
   `deploy.sh` phases (`RUNBOOK.md:47-53`).
9. **Record and announce.** News entry, tags in all three repos, receipt summary into the release doc, no rehearsal
   scratch left (`templates/CHECKLIST.md:42-49`).

### What the rehearsal proves

The exact staged scripts run against a copy of live, on a host that is not live. `--source live` is a
`docker exec buns-clean-db-1 mysqldump --single-transaction --routines --triggers` (`rehearse-host.sh:89`) loaded into
schema `rh_<tag>` on the test DB at 3307 (`:101-105`); `--source nightly` takes the newest nightly dump after its
`SHA256SUMS` check (`:91-93`). The server is rsynced minus `wz`, `logs`, `.git`, `reports`, with `wz` symlinked and
`BOT_LLM_BASE_URL`/`BOT_DECIDE_URL` blanked (`:120-134`); ledger catch-up applies migrations the dump lacks and aborts if
the dump is ahead of the release (`:107-118`). It starts from live's `ExecStart`, rewritten by `rehearsal_cmd`
(`lib.sh:28-45`), and refuses a unit lacking `-Dmapleroot.login-port=`/`-Dmapleroot.channel-base-port=` (`:140-141`).
Then `deploy.sh --dry-run` (`:192`), `deploy.sh` (`:196`), a soak with ERROR counting (`:203-210`), `rollback.sh
--dry-run` and real (`:212-223`), with `MONITOR=""` so nothing is posted (`:159`).

**PASS is three conditions**: identical `state-before.tsv`/`state-after-rollback.tsv` (characters, items, equips, skills,
storages, accounts, ledger), `deploy_rc=0`, and receipt `Result: **PASS**` (`:225-232`). It cannot prove the real
`slumsms-monitor` posts (`:159`), the client publish (never invoked), `extra/extra-steps.sh`, a player's in-game list
(`IN_GAME_CHECK` is only printed), live downtime (the copy runs under `systemd-run` with
`CPUQuota=200% Nice=19 MemoryMax=6G`, `:147-148`), or any WZ-data step — live's `wz` is only symlinked.

## Data

- `dist/release-build/` (jar, `BUILD.txt`, `test.log`), `dist/release-stage/<v>/`, and
  `dist/release-runs/release-<v>-<UTC>.log` (+`-dryrun`) per `release` run (`release:68-69`). `$DEPLOY_ROOT/v<v>/` on
  164 gets `deploy.sh`, `rollback.sh`, `staging/` + `SHA256SUMS`, `tools/`, `CHECKLIST.md`, `DEPLOY-NOTES.md`, later
  `REHEARSAL.md`, `RECEIPT.md`, `backup/`, `release-live-<UTC>.log`.
- `backup/`: `mapleroot.sql.gz` (gzip-tested, "Dump completed", `deploy.body.sh:196`), `server-runtime.tgz` (jar,
  `config.yaml`, `scripts`, `src/main`, plus `cashshop/catalog.tsv` when present, `:198-200`), `config.yaml`,
  `slumsms.service`, `old-jar.sha256`, `ledger-before.txt`, and markers `.stop-backup.done`, `.apply-start.started`,
  `.migration-<id>.attempted`, `.apply-start.done`, `.start-ts` (`:17,202-207,212,218`).
- `/opt/mapleroot-dev/rehearsal-<v>/`: scratch server, `receipt/`, schema `rh_<tag>`, transient unit `slumsms-rh-<tag>`;
  dropped by the trap unless `--keep` (`rehearse-host.sh:34-35,75-84`).
- `/srv/slums-staging/`: client trees, `news-<v>.json`, `manifest-before-<version>.json` (`publish-lib.sh:3`,
  `release:78`). Secrets are file paths only, e.g. `/opt/mapleroot-dev/solomapling-test/secrets/root.cnf`
  (`rehearse-host.sh:36`).

## Switches

| Variable | Default | Meaning |
|---|---|---|
| `RELEASE_HOST`, `DEPLOY_ROOT`, `HOST_NAME` | `root@192.168.0.164`, `/opt/mapleroot-deployments`, `maplestory` | remote target, version folders, the hostname `require_host` accepts (`lib.sh:4-6,24-25`) |
| `PATCH_DIR`, `STAGING_DIR`, `LAUNCHER_REPO`, `PUBLISHER`, `NODE`, `REFERENCE`, `REF_CACHE` | `/srv/slums-patch`, `/srv/slums-staging`, `/root/work/launcher`, `/opt/slums-tools/launcher`, host node, repack `Game Files`, `$STAGING_DIR/reference-client.hashes.json` | publishing inputs (`publish-lib.sh:2-8`); a scratch `PATCH_DIR` is what keeps `--inject` rehearsal-only (`release:61-65`) |
| `LIVE_PATCH_DIR`, `ALLOW_LIVE_ENDPOINT_CHANGE` | `/srv/slums-patch`, `0` | live-feed detection, endpoint-move override (`publish-client:108-112`) |
| `MONITOR`, `MONITOR_TARGET` | `/usr/local/bin/slumsms-monitor` (host), `live` | maintenance posts; the rehearsal sets `MONITOR=""` (`release:75,60`) |

Conf schema (`docs/release/1.6.4.conf` line numbers; meaning from `templates/release.conf.example`):

| Variable | Meaning | 1.6.4 |
|---|---|---|
| `MIGRATIONS` | ordered migration stems this release applies | l.166 (059-065) |
| `LEDGER_NOT_LIVE` | repo migrations live must not have; only for the auto ledger | absent (0 hits) |
| `LEDGER_BEFORE` | exact ledger live must show before the deploy | l.182 (001-058) |
| `CONFIG_APPEND` / `CONFIG_HEAD` / `CONFIG_HEAD_BEFORE` | `server:` keys appended; file replacing the block above `server:` plus its sha guard | l.188-190, all `""` |
| `MIN_CLIENT_BUILD` | client gate at apply-start; refuses a decrease | l.284 (10740) |
| `CHANNELS` / `CHANNEL_LOAD` | every `worlds[*].channels` / `server.CHANNEL_LOAD` | l.277-278 (5 / 125) |
| `SERVER_SET` | `server:` keys edited in place or added, checked against `ServerConfig.java` | l.201-214 |
| `UNIT_EDITS` | sed expressions on the unit then daemon-reload; each must change something | l.271 |
| `PRECHECK_SQL` | `label\|SQL\|expected`, aborting before the stop | l.285, empty |
| `NAV_CACHE_SRC`, `CASHSHOP_CATALOG` | nav cache dir; catalogue that must ship with the jar | l.290, l.293, both `""` |
| `EXPECT_LOG` / `FORBID_LOG` / `SMOKE_SECONDS` | smoke assertions and soak; jar version and "is now online" prepended (`gen-deploy:45`) | l.294-296 |
| `IN_GAME_CHECK` | owner's in-game list, printed at the end of the deploy | l.297 |
| `MAINT_MINUTES` / `MAINT_REASON` | the public "back in about N min" notice | l.298-299 |

`SLUMS_TEST_HOST` is not read anywhere in `tools/release/` (no grep hit); the release tools reach only `RELEASE_HOST`.

## Invariants

- **Live is the owner's.** No script stops or deploys to live by itself: `release` needs a tty and the literal `yes`
  unless `--yes` (`release:156-159`), `release-live.sh --check` exits before touching anything (`release-live.sh:34`),
  repo rule `AGENTS.md:16-17`.
- **Versions are immutable and match a commit.** A folder with `deploy.log` or `backup/` is refused; `--replace` only
  re-stages an undeployed version (`stage:13,43-46`). Dirty tree refused (`build:23-25`); drift allowed only in
  `tools/release` and `docs` (`stage:36-37`); jar sha must equal `BUILD.txt` (`stage:39`).
- **Every pre-check fails before the stop.** All of `deploy.body.sh:117-158` runs while live is up, and the last one
  refuses an existing backup so a backup is never overwritten (`:158`).
- **A migration is rolled back only if this deploy ran it.** `rollback.sh` reads `.migration-<id>.attempted`; a ledger row
  already in `LEDGER_BEFORE` is "already applied before this deploy - not rolled back", an unexplained row aborts rather
  than guesses (`rollback.body.sh:61-66`, fix `1bc04a574`). The marker precedes the SQL, so a crash mid-DDL stays
  reversible (`deploy.body.sh:218`, test `test_release_recovery.py:273`).
- **Never new client with old server.** `release` publishes only while the server is DOWN and restores the manifest
  before restarting the old version (`release:97-103,171-183`; `docs/release/README.md:45`).
- **Routing cannot be swapped silently.** `gen-deploy` refuses a `UNIT_EDITS` entry naming
  `-Dslums.maps.routing.table` and a table missing a map from `routing-required.txt` (`gen-deploy:46-52`); smoke re-reads
  the table from the running jar and checks the PID cmdline (`deploy.body.sh:288-296`).
- **The config loader is strict**, so every `SERVER_SET` key must resolve to a `ServerConfig.java` field (`:64-65`).

### Owner-gated steps

| # | Human action | Enforced or implied by |
|---|---|---|
| 1 | Answer each conf switch; the conf is a proposal until then | `docs/release/1.6.4.conf:4-6`, `:28-31` |
| 2 | Say go to the rehearsal | `docs/release/1.6.4-playbook.md:19` ("**GO rehearsal**") |
| 3 | Sign the go/no-go with an hour | `docs/release/1.6.4-playbook.md:21`, `templates/CHECKLIST.md:24-27` |
| 4 | Type `yes` at the release prompt | `release:156-159` |
| 5 | Run `deploy.sh` / `release-live.sh --go` on 164 | `AGENTS.md:16-17,141-143`; `release-live.sh:21-22` refuses without a mode |
| 6 | Decide fix-forward vs rollback when smoke fails | `deploy.body.sh:325`, `release-live.sh:56-58` (neither rolls back alone) |
| 7 | Go for client/launcher publish and the notes post | `RUNBOOK.md:49-59`, `AGENTS.md:144-146` |
| 8 | Log in on live and run the in-game list | `deploy.body.sh:326`, `templates/CHECKLIST.md:29-30` |

## Origin

Entirely custom: baseline `03d13cb21` (2026-10-18) predates the first pipeline commit, and 2519 commits separate
`9d437b372` from HEAD. `build`, `stage`, `gen-deploy`, `rehearse`, `rehearse-host.sh`, `lib.sh` and `templates/*` start at
`9d437b372` ("Script the release pipeline… ticket 44"); `release`, `rehearse-release-flow`, `rehearse-release-flow-host.sh`
and `docs/release/README.md` at `0ff53df38` ("One release command…"); `release-live.sh` `758ff4a76`; `stage-client` and
`tools/release/README.md` `6a863e7be`; `notes-page/` `522542154`; `hotfix-parity` `5804ad9f8`; `hotfix-ported.txt`
`a66f57ae7`; `routing-required.txt` `3482b808c`; `set-release-config` `5a14d2abc`; `set-min-client-build` `82d4831b4`;
`docs/release/1.6.4.conf` `e87e3d61d`; playbook `7dc2554d3`; `RUNBOOK.md` `afdb60988`.

## Tests and QA

`python3 -m unittest discover -s tools/release -p 'test_*.py'` (`tools/release/README.md:123-127`): offline, fixture
config only, no SSH, no deploy execution. 56 methods in 6 files:

| File | Tests | Covers |
|---|---|---|
| `test_release_config.py` | 26 | config edit semantics (CRLF, inode/mode, idempotence, kind mismatch), `SERVER_SET` validation against `ServerConfig.java` (`:186-215`), generated-phase ordering and the 1.5.0 bake (`:217`) |
| `test_release_recovery.py` | 8 | forward/rollback idempotence, rollback after every partial prefix, stop-before-manifest-restore ordering (`:106`), stop failure withholding restore (`:135`), this-deploy-only rollback (`:237`) |
| `test_live_endpoint.py` | 8 | live endpoint accepted, test host/port refused, published-layer manifest checked, `ALLOW_ENDPOINT_CHANGE` needed for a move |
| `test_publish_client_rerun.py` | 6 | 11-entry news refusal, same verdict on rerun, bad news stops before the layer, identical layer reused |
| `test_stage_client_lib.py` | 5 | `slums-build.txt` parsing, client path and data-spec handling |
| `test_rehearsal_cmd.py` | 3 | `rehearsal_cmd` leaves no live port or directory, adds each flag once |

(all under `tools/release/`). Not covered: nothing executes `deploy.sh`, `rollback.sh`, `release`, `rehearse-host.sh` or
any host call. The only end-to-end proof is `rehearse-release-flow` (scratch feed, fake monitor, three scenarios,
`rehearse-release-flow:97-142`) — a manual script, not a test. `stage-client`'s Windows half is untested.

## Known issues

- **b288** (S2, fixed `1bc04a574`): rollback used to undo every migration in the conf, including ones an earlier release
  applied; the conf was cut to 058 first (`9493a47d8`).
- **b052** (S4, branch `162-b052 fb8286344`, unmerged): `publish-client` published the layer before validating the news
  list; the guard lives on the branch, only the rerun/reuse tests are here.
- **L18** (doing: one migration list, one 1.6.4 conf) and **L19** (open: nothing from 1.6.4 on TEST yet)
  (`/root/work/STATUS-1.6.4.md:109-110`).
- NEW-FINDING: no template has a WZ step since 1.4.1, so the b300 portal fix is a hand step on release night
  (`docs/release/1.6.4.conf:263-266`), and nothing rehearses it — `rehearse-host.sh` runs `deploy.sh` directly and
  `release` never runs `extra/extra-steps.sh`, so the one class of step 1.6.4 needs is never exercised.
- NEW-FINDING: `stage` copies `tools/` wholesale (`stage:61`), yet `RUNBOOK.md:40` tells the operator to "stage
  `tools/release/release-live.sh` into the release folder" by hand; nothing enforces it, and `VER="${HERE##*/v}"`
  (`release-live.sh:15`) works only from the version folder root.
- NEW-FINDING: `release` never checks `REHEARSAL.md` — only `release-live.sh:27` greps it for `rehearsal PASS|smoke PASS`;
  the five-phase hand route (`RUNBOOK.md:47-53`) has no rehearsal check at all.
- NEW-FINDING: `docs/release/1.6.4.conf:10` says "MIN_CLIENT_BUILD stays 10721" and `:297` says "build 10721 or later",
  while `:279-284` sets 10740. Comment drift, but the lines an operator reads first disagree.

## State

Working, with one repeated operational gap. The pipeline self-checks from `build` to `rollback.sh` (cites above), the
offline suite covers 56 cases written alongside each stage, and the rehearsal's PASS test is mechanical
(`rehearse-host.sh:225-232`). Not yet exercised for 1.6.4: the conf is still DRAFT and still being edited by owner
decisions (`docs/release/1.6.4.conf:4-6`; commits `cb365cddb`, `3f0384633`, `a26fdb6ce`), and L19 says nothing from 1.6.4
has run on TEST.

## Open questions

- **Client gate value.** `docs/release/1.6.4.conf:10,297` say 10721; `:279-284` and the playbook A6
  (`docs/release/1.6.4-playbook.md:17`) say 10740. Which is authoritative for the gate the deploy writes?
- **Which route is sanctioned for the night.** `AGENTS.md:141-143` ends at `deploy.sh`, `docs/release/README.md:3-6` makes
  `release` the one command, `RUNBOOK.md:40-43` recommends `release-live.sh --go` (not staged automatically). One of the
  three should be named the route for 1.6.4.
- **Rehearsal as a gate.** `docs/architecture/ops/README.md:74` says "`rehearse` must PASS before a release is staged for
  live", but `stage` never reads `REHEARSAL.md` (`stage:31-46`) and the rehearsal writes into the already staged folder.
  Tooling invariant or checklist convention?
- **Monitor posts unproven.** `release:129-138` only probes the installed binary; no rehearsal runs the real
  `maintenance start --minutes` or combined `end --client` post (`rehearse-host.sh:159`,
  `rehearse-release-flow-host.sh:101-126` use a blank/fake monitor).
- **Partial extra step.** `extra/extra-steps.sh` runs after `stop-backup` and before `apply-start`
  (`release-live.sh:43-49`); a failure restarts the old version, but a *partially* applied data change is not covered by
  `rollback.sh`. Whether such a step must ship an inverse is written down nowhere.
