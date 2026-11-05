# Release pipeline

## Purpose

`tools/release/` turns one green commit plus one release conf into an immutable version folder on the live host, proves
that folder by deploying and rolling it back on a full copy of live, and then lets the owner run the deploy with one
owner-gated command (`tools/release/README.md:5-14`, `docs/tickets/44-release-pipeline.md:1-8`). The conf
`docs/release/<version>.conf` is the only per-release input: it lists migrations, config edits, unit edits, smoke lines
and the maintenance notice (`tools/release/templates/release.conf.example:1-3`). Everything a release does to live is
generated from that file, so a bad conf is caught by validation before the stop (`tools/release/gen-deploy:59-71`) or by
the rehearsal (`tools/release/rehearse-host.sh:225-232`).

## Entry points

| Command | Where | What it writes |
|---|---|---|
| `tools/release/hotfix-parity <live-branch> [release-ref]` | workstation | stdout list; exits 1 when a live fix is missing (`tools/release/hotfix-parity:4,12-18`) |
| `tools/release/build [--skip-tests] [--allow-dirty] [--dry-run]` | workstation | `dist/release-build/{Cosmic.jar,BUILD.txt,test.log}` (`tools/release/build:4,12,48-56`) |
| `tools/release/stage <version> [--conf F] [--replace] [--dry-run]` | workstation → 164 | `dist/release-stage/<v>/` then `$DEPLOY_ROOT/v<v>/` (`tools/release/stage:4,25-27,82-85`) |
| `tools/release/rehearse <version> [--source live\|nightly\|dump] [--soak N] [--keep]` | workstation → 164 | `REHEARSAL.md` in the version folder (`tools/release/rehearse:12-14`, `rehearse-host.sh:270-271`) |
| `tools/release/stage-client <client-version> --build-dir 'Z:\…\Release'` | workstation (Silvia → 164) | `/srv/slums-staging/client-<v>/` + `.STAGED.txt` (`tools/release/README.md:16-40`) |
| `tools/release/release <version> [--dry-run] [--yes]` | workstation → 164 | server + client in MMO order, one Cody post (`tools/release/release:6,171-213`) |
| `bash $DEPLOY_ROOT/v<v>/deploy.sh [--phase stop-backup\|apply-start\|smoke\|restart-old]` | 164, owner | `RECEIPT.md`, `backup/` (`templates/deploy.body.sh:8-17,301-323`) |
| `bash $DEPLOY_ROOT/v<v>/rollback.sh [--dry-run]` | 164, owner | restored jar/config/unit + `ROLLBACK-RECEIPT.md` (`templates/rollback.body.sh:6-10`) |
| `bash $DEPLOY_ROOT/v<v>/release-live.sh --check\|--go` | 164, owner | one run log in the version folder (`tools/release/release-live.sh:4-16`) |
| `tools/release/rehearse-release-flow [--only ok\|publish-fail\|start-fail]` | workstation → 164 | `docs/release/release-flow-rehearsal.md` (`tools/release/rehearse-release-flow:4,34`) |
| `tools/release/set-release-config <config.yaml> --channels N --set K=V --dry-run` | anywhere | edits `config.yaml` in place (`tools/release/set-release-config:2-12`) |

## How it works

1. **Conf written.** `docs/release/<version>.conf` is a bash file sourced by `stage` and `gen-deploy`
   (`tools/release/gen-deploy:23`); the template documents every variable and its default
   (`templates/release.conf.example:5-54`). `gen-deploy` defaults the unset ones at `:18-22`, so an absent variable is
   "no change", not an error.
2. **Hotfix parity checked.** `hotfix-parity` runs `git cherry "$rel" "$live"` and fails on any `+` commit not listed in
   `hotfix-ported.txt` (`tools/release/hotfix-parity:10-18`). Mandatory before staging (`AGENTS.md:244-245`,
   `docs/release/RUNBOOK.md:27`).
3. **Build.** `./mvnw -o -B clean package` in the checkout, the last `Tests run:` line parsed and re-checked for
   `Failures: 0, Errors: 0,` (`tools/release/build:31-43`); `BUILD.txt` records commit, branch, version, seconds, test
   summary and jar sha (`:48-56`). A dirty tree is refused (`:23-25`).
4. **Stage.** Checks the conf exists, the build commit still matches the checkout outside `tools/release` and `docs`
   (`tools/release/stage:36-37`), the jar sha equals `BUILD.txt` (`:39`) and the jar's `SLUMSMS_VERSION` equals the
   version (`:40`). It probes the host first and refuses a version folder that was already deployed or already staged
   (`:43-46`), assembles `staging/` (`:49-59`), calls `gen-deploy` (`:60`), copies `CHECKLIST.md` with `<version>`
   substituted (`:62-63`) and writes `DEPLOY-NOTES.md` (`:64-77`), then rsyncs and verifies `SHA256SUMS` on the host
   (`:82-84`).
5. **Deploy scripts generated.** `gen-deploy` reads each migration's INSERT to learn its ledger id and refuses one that
   writes no ledger row or whose rollback does not remove it (`tools/release/gen-deploy:29-34`), computes
   `LEDGER_AFTER`, validates `SERVER_SET` against `ServerConfig.java` (`:59-69`), then concatenates the fixed body
   under a settings header and `bash -n` checks both scripts (`:96-99`).
6. **Rehearsal.** `rehearse` execs the staged `rehearse-host.sh` on 164 (`tools/release/rehearse:12-14`), which builds a
   private copy: live dump into schema `rh_<tag>` on port 3307 (`rehearse-host.sh:101-105`), a copy of the server with
   `wz` symlinked and the LLM URLs blanked (`:120-134`), and the live `ExecStart` rewritten by `rehearsal_cmd`
   (`tools/release/lib.sh:28-45`, `rehearse-host.sh:136-149`). It then boots the old version, records `state-before.tsv`,
   runs `deploy.sh --dry-run`, deploys for real, soaks, counts ERROR lines, rolls back and compares
   `state-after-rollback.tsv` (`:180-232`).
7. **Client staged (only if it changed).** `stage-client` hashes `slums.dll`, `slums-build.txt` and `Custom.wz` on Silvia,
   copies them, re-hashes, overlays them on a copy of the published client layer and refuses a non-empty staging dir
   (`tools/release/README.md:25-33`; `tools/release/stage-client:40`).
8. **Owner pre-flight.** `release <version> --dry-run` changes nothing: it requires `deploy.sh` to carry `--phase`
   (`tools/release/release:110-112`), runs `deploy.sh --dry-run` against live (`:115`), `publish-client --dry-run`
   (`:125`) and a monitor probe (`:129-138`), prints the plan (`:145-154`) and exits (`:155`).
9. **Owner deploy.** Either `release <version>` (stop-backup → publish client while DOWN → apply-start → smoke →
   maintenance end, `tools/release/release:161-213`), or `release-live.sh --go` (`release-live.sh:36-63`), or the five
   hand-run `deploy.sh` phases in `docs/release/RUNBOOK.md:47-53`.
10. **Record and announce.** `CHECKLIST.md` §5: news entry within the publisher's limits, tags in all three repos, receipt
    summary into the release doc, no rehearsal scratch left (`templates/CHECKLIST.md:42-49`).

## Data

- `dist/release-build/` (jar, `BUILD.txt`, `test.log`), `dist/release-stage/<v>/` (workstation build output) and
  `dist/release-runs/release-<v>-<UTC>.log` (+`-dryrun`) for every `release` run (`tools/release/release:68-69`).
- `$DEPLOY_ROOT/v<v>/` on 164: `deploy.sh`, `rollback.sh`, `staging/` with `SHA256SUMS`, `tools/`, `CHECKLIST.md`,
  `DEPLOY-NOTES.md`, later `REHEARSAL.md`, `RECEIPT.md`, `backup/` and `release-live-<UTC>.log`.
- `backup/` holds `mapleroot.sql.gz` (gzip-tested, "Dump completed" checked, `deploy.body.sh:196`), `server-runtime.tgz`
  (jar, `config.yaml`, `scripts`, `src/main`, plus `cashshop/catalog.tsv` when present, `:198-200`), `config.yaml`,
  `slumsms.service`, `old-jar.sha256`, `ledger-before.txt` and the markers `.stop-backup.done`, `.apply-start.started`,
  `.migration-<id>.attempted`, `.apply-start.done`, `.start-ts` (`:17,202-207,212,218`).
- `/opt/mapleroot-dev/rehearsal-<v>/` (scratch server, `receipt/`, schema `rh_<tag>`, transient unit `slumsms-rh-<tag>`),
  cleaned by the trap unless `--keep` (`rehearse-host.sh:34-35,75-84`).
- `/srv/slums-staging/` client trees and `news-<v>.json`; `manifest-before-<version>.json` for the restore path
  (`tools/release/publish-lib.sh:3`, `tools/release/release:78`). Secrets live in files named by path only, e.g.
  `/opt/mapleroot-dev/solomapling-test/secrets/root.cnf` (`rehearse-host.sh:36`).

## Switches

| Variable | Default | Meaning |
|---|---|---|
| `RELEASE_HOST` | `root@192.168.0.164` | host every remote step reaches (`tools/release/lib.sh:4`) |
| `DEPLOY_ROOT` | `/opt/mapleroot-deployments` | version folders (`lib.sh:5`) |
| `HOST_NAME` | `maplestory` | hostname `on_host`/`require_host` accept (`lib.sh:6,24-25`) |
| `PATCH_DIR` | `/srv/slums-patch` | published feed (`publish-lib.sh:2`); a scratch value is how `--inject` stays rehearsal-only (`release:61-65`) |
| `STAGING_DIR`, `LAUNCHER_REPO`, `PUBLISHER`, `NODE`, `REFERENCE`, `REF_CACHE` | `/srv/slums-staging`, `/root/work/launcher`, `/opt/slums-tools/launcher`, host node, repack `Game Files` dir, `$STAGING_DIR/reference-client.hashes.json` | client publishing inputs (`publish-lib.sh:3-8`) |
| `LIVE_PATCH_DIR`, `ALLOW_LIVE_ENDPOINT_CHANGE` | `/srv/slums-patch`, `0` | live-feed detection and the endpoint-move override (`publish-client:108-112`) |
| `MONITOR`, `MONITOR_TARGET` | `/usr/local/bin/slumsms-monitor` on the host, `live` | maintenance posts; the rehearsal sets `MONITOR=""` (`release:75,60`, `rehearse-host.sh:159`) |
| conf values | see the schema below | per-release behaviour |

The conf schema (`docs/release/1.6.4.conf` line numbers; meaning from `templates/release.conf.example`):

| Variable | Meaning | 1.6.4 |
|---|---|---|
| `MIGRATIONS` | ordered migration stems this release applies | l.166 (059-065) |
| `LEDGER_NOT_LIVE` | repo migrations live must not have; consulted only for the auto ledger | absent (0 hits) |
| `LEDGER_BEFORE` | exact ledger live must show before the deploy | l.182 (001-058) |
| `CONFIG_APPEND` / `CONFIG_HEAD` / `CONFIG_HEAD_BEFORE` | `server:` keys appended; file replacing the block above `server:` plus its sha guard | l.188-190, all `""` |
| `MIN_CLIENT_BUILD` | client gate raised at apply-start, refuses a decrease | l.284 (10740) |
| `CHANNELS` / `CHANNEL_LOAD` | every `worlds[*].channels` / `server.CHANNEL_LOAD` | l.277-278 (5 / 125) |
| `SERVER_SET` | `server:` keys edited in place or added, checked against `ServerConfig.java` | l.201-214 |
| `UNIT_EDITS` | sed expressions on the unit, then daemon-reload; each must change something | l.271 |
| `PRECHECK_SQL` | `label\|SQL\|expected` abort-before-stop checks | l.285, empty |
| `NAV_CACHE_SRC`, `CASHSHOP_CATALOG` | nav cache dir; catalogue file that must ship with the jar | l.290, l.293, both `""` |
| `EXPECT_LOG` / `FORBID_LOG` / `SMOKE_SECONDS` | smoke assertions and soak length; the jar version and "is now online" are added by `gen-deploy:45` | l.294-296 |
| `IN_GAME_CHECK` | owner's in-game list, printed at the end of the deploy | l.297 |
| `MAINT_MINUTES` / `MAINT_REASON` | the public "back in about N min" notice | l.298-299 |

`SLUMS_TEST_HOST` is not read anywhere in `tools/release/` (grep: no hits); the release tools reach only
`RELEASE_HOST`.

## Invariants

- **Live is the owner's.** No release script stops or deploys to live on its own: `release` requires a tty and the literal
  answer `yes` unless `--yes` (`tools/release/release:156-159`), `release-live.sh` does nothing on `--check`
  (`release-live.sh:34`), and the repo rule is `AGENTS.md:16-17`.
- **Versions are immutable.** A folder with `deploy.log` or `backup/` is refused; `--replace` only re-stages an
  undeployed version (`tools/release/stage:13,43-46`).
- **A build matches a commit.** Dirty tree refused (`build:23-25`); the checkout may only drift inside `tools/release`
  and `docs` (`stage:36-37`); jar sha must equal `BUILD.txt` (`stage:39`).
- **Every pre-check fails before the stop.** All of `deploy.body.sh:117-158` runs while live is still up, and the last one
  refuses an existing backup so a backup is never overwritten (`:158`).
- **A migration is only rolled back if this deploy ran it.** `rollback.sh` reads `.migration-<id>.attempted`; a ledger row
  present in `LEDGER_BEFORE` is reported "already applied before this deploy - not rolled back" and an unexplained row
  aborts rather than guesses (`templates/rollback.body.sh:61-66`, fix `1bc04a574`).
- **The attempt marker precedes the SQL**, so a crash mid-DDL still leaves the migration reversible (`deploy.body.sh:218`,
  test `tools/release/test_release_recovery.py:273`).
- **Never new client with old server.** `release` publishes the client only while the server is DOWN and restores the
  manifest before restarting the old version (`release:97-103,171-183`; `docs/release/README.md:45`).
- **Routing cannot be silently swapped.** `gen-deploy` refuses a `UNIT_EDITS` entry mentioning
  `-Dslums.maps.routing.table` and refuses a table missing a map from `routing-required.txt`
  (`gen-deploy:46-52`); the smoke test re-reads the table from the running jar and checks the PID's cmdline
  (`deploy.body.sh:288-296`).
- **The server config loader is strict**, so every `SERVER_SET` key must resolve to a field of `ServerConfig.java`
  (`gen-deploy:64-65`).

### Owner-gated steps

Every point where a human must act, and what stops or implies the gate:

| # | Human action | Enforced or implied by |
|---|---|---|
| 1 | Answer for each switch in the conf; the conf is a proposal until then | `docs/release/1.6.4.conf:4-6` ("a proposal for the owner and Server Dev to strike or add lines to"), `:28-31` |
| 2 | Say go to the rehearsal (it stops live-independent but costs a dump and the box) | `docs/release/1.6.4-playbook.md:19` ("**GO rehearsal**") |
| 3 | Sign the go/no-go with an hour | `docs/release/1.6.4-playbook.md:21` (A10), `templates/CHECKLIST.md:24-27` |
| 4 | Type `yes` at the release prompt | `tools/release/release:156-159` |
| 5 | Run `deploy.sh` / `release-live.sh --go` on 164 | `AGENTS.md:16-17` and `:141-143`; `release-live.sh:21-22` refuses without a mode flag |
| 6 | Decide fix-forward vs rollback when smoke fails | `templates/deploy.body.sh:325`, `release-live.sh:56-58` (neither rolls back by itself) |
| 7 | Go for the client publish / launcher publish / patch notes post | `docs/release/RUNBOOK.md:49-59`, `AGENTS.md:144-146` |
| 8 | Log in on live and run the in-game list | `templates/deploy.body.sh:326` prints `IN_GAME_CHECK`; `templates/CHECKLIST.md:29-30` |

## Origin

All of it is custom: the baseline `03d13cb21` (2026-10-18, "Establish deployable MapleRoot server baseline") predates the
first pipeline commit, and 2519 commits separate `9d437b372` from HEAD.

| Path | First commit |
|---|---|
| `build`, `stage`, `gen-deploy`, `rehearse`, `rehearse-host.sh`, `lib.sh`, `templates/*` | `9d437b372` "Script the release pipeline… (ticket 44)" |
| `release`, `rehearse-release-flow`, `rehearse-release-flow-host.sh`, `docs/release/README.md` | `0ff53df38` "One release command: server deploy and client publish in MMO order" |
| `release-live.sh` | `758ff4a76` |
| `stage-client`, `tools/release/README.md` | `6a863e7be` |
| `hotfix-parity` | `5804ad9f8`; `hotfix-ported.txt` `a66f57ae7` |
| `set-release-config` | `5a14d2abc`; `set-min-client-build` `82d4831b4` |
| `notes-page/` | `522542154` |
| `routing-required.txt` | `3482b808c` |
| `docs/release/1.6.4.conf` | `e87e3d61d`; playbook `7dc2554d3`; `RUNBOOK.md` `afdb60988` |

## Tests and QA

`python3 -m unittest discover -s tools/release -p 'test_*.py'` (`tools/release/README.md:123-127`): offline, fixture config
only, no SSH and no deploy execution. 56 test methods in 6 files:

| File | Tests | Covers |
|---|---|---|
| `tools/release/test_release_config.py` | 26 | config edit semantics (CRLF, inode/mode, idempotence, kind mismatch), `SERVER_SET` validation against `ServerConfig.java` (`:186-215`), generated-phase ordering and the 1.5.0 bake (`:217`) |
| `tools/release/test_release_recovery.py` | 8 | forward/rollback idempotence, rollback after every partial prefix, stop-before-manifest-restore ordering (`:106`), stop failure withholding restore (`:135`), this-deploy-only rollback (`:237`) |
| `tools/release/test_live_endpoint.py` | 8 | live endpoint accepted, test host/port refused, published-layer manifest checked, `ALLOW_ENDPOINT_CHANGE` required for a move |
| `tools/release/test_publish_client_rerun.py` | 6 | 11-entry news refusal, same-verdict rerun, bad news stops before the layer, identical layer reused |
| `tools/release/test_stage_client_lib.py` | 5 | `slums-build.txt` parsing, client path and data-spec handling |
| `tools/release/test_rehearsal_cmd.py` | 3 | `rehearsal_cmd` leaves no live port or directory and adds each flag once |

Not covered: nothing executes `deploy.sh`, `rollback.sh`, `release`, `rehearse-host.sh` or any host call. The only
end-to-end proof is `rehearse-release-flow` (scratch feed, fake monitor, three scenarios:
`tools/release/rehearse-release-flow:97-142`), which is a manual script, not a test. `stage-client`'s Windows half and the
`extra/extra-steps.sh` path are untested and un-rehearsed.

## Known issues

- **b288** (S2, fixed `1bc04a574` "Rollback undoes only the migrations the deploy ran"): rollback used to undo every
  migration in the conf, including ones an earlier release applied; the conf was cut to 058 first (`9493a47d8`).
- **b052** (S4, branch `162-b052 fb8286344`, not merged): `publish-client` published the layer before validating the news
  list, so a bad news file left a published layer. The rerun/reuse tests exist but the guard lives on the branch.
- **L18** (doing): one migration list, one conf; **L19** (open): nothing from 1.6.4 has been deployed to TEST
  (`/root/work/STATUS-1.6.4.md:109-110`).
- **b300** wants a WZ data step on release night; the templates have had no WZ step since 1.4.1, so it is manual in
  `extra/extra-steps.sh`-style steps (`docs/release/1.6.4.conf:28-31`, `docs/release/1.6.4-playbook.md:30-42`).
  NEW-FINDING: `rehearse-host.sh` runs `deploy.sh` directly and never executes `extra/extra-steps.sh`, and
  `rehearse-release-flow` runs `release`, which also skips it — the one class of step 1.6.4 needs is the one never
  rehearsed.
- NEW-FINDING: `stage` copies `tools/` wholesale (`tools/release/stage:61`) but `release-live.sh` is not part of the
  version folder contract: `RUNBOOK.md:40` tells the operator to "stage `tools/release/release-live.sh` into the release
  folder" by hand, and nothing enforces it. `release-live.sh` also derives `VER="${HERE##*/v}"` (`:15`), so it only works
  from the version folder root.
- NEW-FINDING: `release` never checks `REHEARSAL.md`; only `release-live.sh:27` greps it for `rehearsal PASS|smoke PASS`.
  The five-phase hand route in `RUNBOOK.md:47-53` has no rehearsal check at all.
- NEW-FINDING: `docs/release/1.6.4.conf:10` still says "MIN_CLIENT_BUILD stays 10721" and `:297` says "build 10721 or
  later", while `:284` sets 10740 and `:279-283` explains why. Comment drift only, but the two lines an operator reads
  first disagree.

## State

Working, with one repeated operational gap. Evidence: the pipeline is complete and self-checking from `build` to
`rollback.sh` (all steps cite above), the offline suite passes 56 methods and was written alongside each stage
(`tools/release/test_*.py`), and the rehearsal's PASS criteria are mechanical — identical player-row snapshot, `deploy_rc=0`
and a `Result: **PASS**` receipt (`rehearse-host.sh:225-232`). The 1.6.4 conf is still marked DRAFT and still being edited
by the owner's decisions (`docs/release/1.6.4.conf:4-6`, commits `cb365cddb`, `3f0384633`, `a26fdb6ce`), and L19 says
nothing from 1.6.4 has run on TEST yet.

## Open questions

- **Client gate value.** `docs/release/1.6.4.conf:10,297` say 10721; `:279-284` say 10740 and the playbook's A6 also says
  10740 (`docs/release/1.6.4-playbook.md:17`). Which line is authoritative for the gate the deploy will write?
- **How the owner is expected to run the night.** `AGENTS.md:141-143` ends the pipeline at
  `bash /opt/mapleroot-deployments/v<version>/deploy.sh`, `docs/release/README.md:3-6` makes `release` the one command, and
  `RUNBOOK.md:40-43` recommends `release-live.sh --go`. Three routes, one of which (`release-live.sh`) is not staged
  automatically. Which is the sanctioned one for 1.6.4?
- **Rehearsal as a gate.** `docs/architecture/ops/README.md:74` states "`rehearse` must PASS before a release is staged for
  live", but `stage` does not read `REHEARSAL.md` (`tools/release/stage:31-46`) and the rehearsal writes into the already
  staged folder. Is the invariant meant to be enforced by tooling or by the checklist?
- **Posts during a rehearsal.** The rehearsal blanks `MONITOR` (`rehearse-host.sh:159`) and the flow rehearsal uses a fake
  monitor (`rehearse-release-flow-host.sh:101-126`). Nothing proves the real `slumsms-monitor maintenance start --minutes`
  arguments or the combined `end --client` post against the installed binary; `release:129-138` only probes it.
- **Rollback after a partial extra step.** `extra/extra-steps.sh` runs after `stop-backup` and before `apply-start`
  (`release-live.sh:43-49`); its failure restarts the old version, but a *partially* applied data change is not covered by
  `rollback.sh`. Whether such a step must ship its own inverse is written down nowhere.
