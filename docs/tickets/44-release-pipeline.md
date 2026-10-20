# 44: One-command release pipeline

**Area:** Ops
**Depends on:** -
**Status:** built on branch `release-pipeline` (2026-10-20), not merged. `tools/release/`: build, stage, rehearse,
gen-deploy (deploy.sh/rollback.sh with `--dry-run`), publish-client/publish-launcher, checklist template. Dry run
1.3.2-dryrun (main, 2,710 tests) built, staged and rehearsed twice on copies of live: PASS (live dump, no migrations;
nightly dump, migration 008 + its rollback), ~29 s downtime per deploy. Not yet used for a real release.

## Today

1.3.0 went out by hand: build on the workstation, scp, a hand-written deploy script, a rehearsal by an agent, then the
owner ran it. Live deploys can't be run by the agent (blocked), so the owner runs one command; that part stays.

## What

- `tools/release/` scripts in the SlumsMS repo:
  - `build` (suite + jar + sha), `stage <version>` (jar, scripts, non-Java `src/main` data, migrations to
    `/opt/mapleroot-deployments/<version>/staging`);
  - `rehearse <version>` (fresh live copy, migrations, boot, checks, rollback, cleanup, receipt);
  - `deploy.sh` generated from the release doc (stop, backup, ledger-checked migrations, stage, config keys, unit,
    start, smoke test, receipt) and `rollback.sh` next to it;
  - `publish-client` / `publish-launcher` wrappers around `tools/publish.mjs` with the hash-diff rule built in.
- A release checklist template (news, guide/wiki update, tags in all three repos).
- Learn from 1.3.0: the ledger column is `migration_id`; check scripts with a dry run against the rehearsal copy first.

## Done when

The next release goes from "tests pass" to "owner runs one deploy command" with every step scripted and rehearsed.

## Built (2026-10-20, branch `release-pipeline`)

All bash (plus one small Python helper for the JSON hash diff); usage in each script's header.

| Script | Where | What |
|---|---|---|
| `build` | workstation | clean tree check, `./mvnw -o clean package` (suite + jar), `dist/release-build/BUILD.txt` (commit, version, test counts, sha256) |
| `stage <version>` | workstation -> 164 | `docs/release/<version>.conf` + build -> `/opt/mapleroot-deployments/v<version>/`: `staging/` (jar, scripts.tar, srcdata.tar = every non-.java file git tracks under src/main, all migrations, config files, SHA256SUMS), generated `deploy.sh`/`rollback.sh`, DEPLOY-NOTES.md, CHECKLIST.md, `tools/`. Refuses a version that has a deploy.log or backup. |
| `gen-deploy` | (called by stage) | settings header from the conf (ledger ids read from each migration's INSERT; expected ledger = every repo migration not in this release unless pinned) + the fixed bodies in `templates/` |
| `deploy.sh [--dry-run]` | 164 | pre-checks that abort before the stop (staged hashes, ledger exactly as expected, config worlds-block hash, config keys not already present, unit edits apply, extra SQL checks, no earlier backup, disk), stop, backup (DB `--lock-tables`, jar/config/scripts/src/main, unit), ledger-checked migrations, stage jar+scripts+data+config together, unit edits, start, smoke test (expected/forbidden log lines, 0 ERROR/Exception, ports, ledger), `RECEIPT.md` with timings |
| `rollback.sh [--dry-run]` | 164 | migration rollbacks (newest first, only those in the ledger), the deploy's files moved aside, jar/config/scripts/src/main and unit restored, start, receipt |
| `rehearse <version>` | workstation -> 164 | fresh copy of live (`--source live|nightly|<dump>`), catch-up migrations for an older dump, old version booted, staged deploy.sh and rollback.sh run (each `--dry-run` first), player rows compared, cleanup, `REHEARSAL.md` |
| `publish-client`, `publish-launcher` | workstation -> 164 | host publisher checked against the launcher repo; client: hash diff (base + client vs reference client `client-allow.txt`, and vs the release players have now, so a dropped overlay fails) before and after the layer publish, manifest last; launcher: `--dry-run` publishes into a throwaway folder |
| `templates/CHECKLIST.md` | | release checklist: news, guide/wiki, tags in all three repos |

Dry run (main @ e0c3edfc3 + tools, labelled 1.3.2-dryrun; suite 2,710 run, 0 failures, 6 skipped; build 180 s):
- Rehearsal 1 (live dump, no migrations): PASS, 324 s wall. deploy.sh: stop with bots 20.9 s, backup 2.6 s,
  stage 0.4 s, start -> online 5.1 s, **downtime 29.1 s**; 0 ERROR lines; rollback 27.9 s downtime; player rows
  identical after rollback. Receipt `/opt/mapleroot-dev/rehearsal-1.3.2-dryrun/receipt/REHEARSAL.md` on 164.
- Rehearsal 2 (nightly dump from before 1.3.1, conf `examples/1.3.2-dryrun-008.conf` re-applying 008): PASS, 321 s;
  008 applied through the ledger check, rolled back, ledger back to 001-007. Receipt
  `/opt/mapleroot-dev/rehearsal-1.3.2-dryrun2/receipt/REHEARSAL.md`.
- `deploy.sh --dry-run` against live: the no-migration release passes every pre-check; the 008 one aborts on the
  ledger before anything is stopped.
- The first rehearsal found a real bug (`grep -q` behind `pipefail` gives SIGPIPE false negatives on long journals
  and tar listings); fixed with a `has` helper; its receipt is kept as `receipt-20261020T141424Z`.

Open:
- The host publisher (`/opt/slums-tools/launcher`) differs from the launcher repo (48decf8); the wrappers refuse to
  publish until `--sync-publisher` (owner's call). publish-client ran only as `--dry-run` (HASH-DIFF PASS on the current
  client staging tree); publish-launcher was not exercised (no launcher build on 164).
- The reference client on 164 is the original MapleRoot client, so `client-allow.txt` lists the SlumsMS overlay
  (captured from the published 1.3.0 set); the desktop's standalone `standalone-files.json` is not used.
- One-off steps like 1.3.0's template character belong in a migration; `NAV_CACHE_SRC` covers a nav-cache copy.
