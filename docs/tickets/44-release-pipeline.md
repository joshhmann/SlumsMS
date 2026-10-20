# 44: One-command release pipeline

**Area:** Ops
**Depends on:** -
**Status:** open

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
