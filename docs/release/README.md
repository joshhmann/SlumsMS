# Releasing SlumsMS: one command, MMO order

From 1.4.2 on, the server deploy and the client publish are one run: `tools/release/release <version>` on the
workstation. Like any MMO patch day, the client is published while the server is down for maintenance, players
update during the downtime, and the server comes back on the new version with the new client already in the
launcher. Cody posts twice: once for "going down" and once for "back up, press Play to update if you haven't".

The older way (1.4.0, 1.4.1) still works: `deploy.sh` on 164, then `publish-client` from the workstation.
`deploy.sh` with no flags behaves exactly as before.

## What the owner runs (1.4.2)

Prepare as usual: `tools/release/build`, `tools/release/stage 1.4.2`, `tools/release/rehearse 1.4.2`,
`tools/release/stage-client 1.4.2 --build-dir 'Z:\...\build\Release'`, and put the news in
`/srv/slums-staging/news-1.4.2.json`. Then, from the workstation in the SlumsMS checkout:

```
LAUNCHER_REPO=/root/work/launcher-publisher tools/release/release 1.4.2 --no-packs --dry-run   # changes nothing
LAUNCHER_REPO=/root/work/launcher-publisher tools/release/release 1.4.2 --no-packs             # asks for "yes"
```

`LAUNCHER_REPO` must be a launcher checkout whose `tools/`, `src/` and `package.json` match the host publisher
(`/opt/slums-tools/launcher`; see the 1.4.1 DEPLOY-NOTES). Drop `--no-packs` for a release that ships
first-install packs. The defaults are `--client-version 1.4.2`, `--source /srv/slums-staging/client-1.4.2` and
`--news /srv/slums-staging/news-1.4.2.json` (when it exists). `--no-client` releases the server alone.

Once before 1.4.2: install the updated monitor (`deploy/monitor/install.sh`) so that
`slumsms-monitor maintenance live end --client 1.4.2` exists. Without it the release still runs, but Cody posts
"back up" and "client ready" as two posts (the pre-flight says so).

Every run logs to `dist/release-runs/release-<version>-<UTC>.log` and ends with a timeline.

## Order

| # | Step | Where | Notes |
|---|---|---|---|
| 1 | Pre-flight, changes nothing | workstation -> 164 | `deploy.sh --dry-run` against live; `deploy.sh` has `--phase`; `rollback.sh` parses; client staging tree present; `publish-client --dry-run` (hash-diff vs the reference client and the current release, host publisher matches `LAUNCHER_REPO`); `manifest-before-<version>.json` is free; monitor installed and answering (`maintenance start --dry-run`), with `--client` or not. Prints the plan. `--dry-run` stops here |
| 2 | `deploy.sh --phase stop-backup` | 164 | pre-checks again, maintenance on + ONE "going down" post, stop, backup (DB with table locks, runtime, unit) |
| 3 | `publish-client ... --no-announce` | workstation -> 164 | server DOWN. Hash-diff, layer `client/<version>`, `manifest.json` saved as `/srv/slums-staging/manifest-before-<version>.json`, new `manifest.json` (last), `/launcher-test/` mirror |
| 4 | `deploy.sh --phase apply-start`, then `--phase smoke` | 164 | migrations, config, install, unit, start, "is now online"; maintenance stays on through the smoke test; receipt `RECEIPT.md` |
| 5 | `slumsms-monitor maintenance live end --client <version>` | 164 | maintenance off + ONE post: back up, press Play to update |

## When something fails

The rule: players end up with old server + old client, or new server + new client. Never new client + old server.

| Fails in | What the command does | Result |
|---|---|---|
| 1 pre-flight | nothing | nothing changed; fix and rerun |
| 2 stop/backup | `deploy.sh --phase restart-old` (the untouched old jar starts again, maintenance off, "back up" post) | old server, nothing published |
| 3 publish | if `manifest.json` changed: `publish-client --restore-manifest /srv/slums-staging/manifest-before-<version>.json`; then `deploy.sh --phase restart-old` | old server + old client. The new server is never started. A layer that did get published stays (immutable, unreferenced): use a new client version next attempt |
| 4 apply/start/smoke | `publish-client --restore-manifest ...` FIRST, then `rollback.sh` (old jar/config/scripts back, migration rollbacks, start, "back up" post) | old server + old client |
| restore impossible (3 or 4) | stops: the server stays DOWN in maintenance and the two commands to finish by hand are printed | never new client + old server |
| 5 post | warning only | end it by hand: `slumsms-monitor maintenance live end --client <version>` |

By hand, the same pieces:

```
tools/release/publish-client --restore-manifest /srv/slums-staging/manifest-before-1.4.2.json [--dry-run]
ssh root@192.168.0.164 bash /opt/mapleroot-deployments/v1.4.2/deploy.sh --phase restart-old   # nothing applied yet
ssh root@192.168.0.164 bash /opt/mapleroot-deployments/v1.4.2/rollback.sh                    # after apply-start began
```

`--restore-manifest` checks every layer the saved manifest names is still published, keeps the manifest it replaces
as `/srv/slums-staging/manifest-replaced-<UTC>.json`, writes `manifest.json` atomically and mirrors
`/launcher-test/`. It posts nothing.

## deploy.sh phases (generated from tools/release/templates/deploy.body.sh)

```
deploy.sh                       # unchanged: everything, maintenance end right after "is now online"
deploy.sh --dry-run             # unchanged: pre-checks only
deploy.sh --phase stop-backup   # pre-checks, maintenance on + post, stop, backup; server left DOWN, old version
deploy.sh --phase apply-start   # migrations, install, start, online; maintenance stays ON
deploy.sh --phase smoke         # smoke test + receipt (exit 1 on FAIL)
deploy.sh --phase restart-old   # before apply-start only: start the old version again, maintenance off
```

State between phases lives in the backup folder: `.stop-backup.done`, `.apply-start.started`, `.apply-start.done`,
`.start-ts`, `.timings`. `apply-start` refuses without a completed stop-backup or with the server running, and runs
only once per backup; `restart-old` refuses once `apply-start` began (that needs `rollback.sh`). After a stop, every
abort names the right command (`restart-old` or `rollback.sh`). A hand-written `deploy.sh` (like 1.4.1's) has no
`--phase`, and `release` refuses it; restage through `tools/release/stage`, or deploy the old way.

The monitor's `maintenance <target> end --client <version>` posts one Cody line from `CODY["maintenance_end_client"]`
in place of the "back up" line (and posts it even if maintenance was not on). `client-ready` still exists for a
client-only update.

## Rehearsal

`tools/release/rehearse-release-flow` (workstation; host side `rehearse-release-flow-host.sh`) runs `release`
end to end on scratch copies only: a copy of live's Server/ under `/opt/mapleroot-dev/release-flow-rehearsal`
put back to 1.4.0, a scratch schema from the newest nightly dump in the test DB (3307), a transient unit on
8519/8885+, a scratch patch feed (live's manifest and layer manifests copied as real directories), a scratch
`STAGING_DIR`, and a fake monitor that records the posts. The new version is the 1.4.1 jar staged through
`gen-deploy`, the client the real `client-1.4.1` staging tree published as a new layer with the host publisher.
It runs `release --dry-run`, then three releases: `--inject publish-fail` (publish fails after writing
`manifest.json`), `--inject start-fail` (new version never comes online) and a clean one, and checks server
state, jar, feed, mirror, posts and order. Everything is deleted afterwards except the receipt
([release-flow-rehearsal.md](release-flow-rehearsal.md)). `--inject` and `--host-env` are refused against the live
feed and the live deploy root.
