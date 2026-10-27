# Release runbook: the full cycle, as done for 1.5.0 (2026-10-24)

Every step that touches live is marked **GO**: stop and get the owner's explicit go for that step first. Run from a
checkout of the release branch on 187 (for 1.5.0: `/root/work/SlumsMS-150`). Hosts, rules: `AGENTS.md`.

## 0. During development (test server, no go needed)

1. Work on a branch from the current release (`151-<topic>` from `origin/release-1.5.0`), one worktree per task.
2. `mvn -q -o test` green; add tests for every change.
3. Merge into the next release branch (`release-1.5.1` from `release-1.5.0`), run the suite again.
4. `tools/ops/deploy-test --dry-run`, then `tools/ops/deploy-test`. Tell the owner first if they may be playing or a
   run is going (`tools/ops/test-status`).
5. QA:
   - `tools/ops/autoqa-run all` (or wait for 10:00 / 22:00 UTC), then read `tools/ops/test-status` and triage per
     `.claude/skills/qa-triage/SKILL.md`.
   - Write the release's player QA checklist in `docs/release/<version>-qa.md` (one line per check: id, what to do, what
     to expect; reuse the `t-xx-n` ids). The owner tests what auto-QA can't (looks, feel, client).
   - Client visual checks: the client MCP on the owner's PC, driven with `tools/ops/client-mcp` (`docs/qa/QA.md`).
6. Patch notes: write `docs/site/releases/<version>.md` (the public site's format, see the 1.5.0 file) and keep a
   Discord version under 2,000 characters per message.

## 1. Pre-checks (no go needed)

```
tools/release/hotfix-parity <live-branch> # every live hotfix is in this release (e.g. 156-guard); must say ok
tools/release/build                       # full suite + jar + BUILD.txt
tools/release/stage <v>                   # needs docs/release/<v>.conf (copy 1.5.0.conf and edit)
tools/release/rehearse <v>                # copy of live: deploy + smoke + rollback; must say PASS
tools/release/stage-client <v> --build-dir 'Z:\...\build\Release'   # only if the client changed
LAUNCHER_REPO=/root/work/launcher-publisher tools/release/publish-client <v> --source /srv/slums-staging/client-<v> --dry-run
LAUNCHER_REPO=/root/work/launcher-publisher tools/release/release <v> --no-packs --dry-run      # pre-flight
```
The rehearsal checks the stop time too: a 1.5.x stop should take well under a minute. The client must pass LIVE
ENDPOINT (slumsms.asslorde.com:8485) and HASH-DIFF.

## 2. Live, in MMO order (owner runs these; they need a real terminal or `!` in Claude)

**One command (1.5.2+):** stage `tools/release/release-live.sh` into the release folder, then
`bash /opt/mapleroot-deployments/v<v>/release-live.sh --check` (pre-flight only) and
`bash /opt/mapleroot-deployments/v<v>/release-live.sh --go`. It runs the server steps below in order (stop-backup,
`extra/extra-steps.sh` if present, apply-start, smoke, maintenance end), checks before each one and stops at the first
problem with the next action. Client publishes (step 2) and the patch notes stay separate.


1. **GO** `ssh root@192.168.0.164 bash /opt/mapleroot-deployments/v<v>/deploy.sh --phase stop-backup`
   (maintenance on, Cody's "going down" post, stop, DB + runtime backup)
2. **GO** `LAUNCHER_REPO=/root/work/launcher-publisher tools/release/publish-client <v> --source /srv/slums-staging/client-<v> --release <v> --no-packs --news /srv/slums-staging/news-<v>.json --no-announce`
   (skip if no client change)
3. **GO** `ssh root@192.168.0.164 'bash /opt/mapleroot-deployments/v<v>/deploy.sh --phase apply-start && bash /opt/mapleroot-deployments/v<v>/deploy.sh --phase smoke'`
4. **GO** `ssh root@192.168.0.164 /usr/local/bin/slumsms-monitor maintenance live end` (Cody's "back up" post)
5. Owner logs in on live through the launcher and checks the receipt's in-game list.
6. Launcher, only when a new one ships (Tauri first, then the Electron hand-over):
   **GO** Tauri: `ssh root@192.168.0.164 '/opt/slums-tools/node/bin/node /opt/slums-tools/launcher-tauri-publisher/tools/publish.mjs --tauri-launcher "/srv/slums-staging/tauri-<lv>/SlumsMS Launcher.exe" --signature "/srv/slums-staging/tauri-<lv>/SlumsMS Launcher.exe.sig" --version <lv> --base-url https://slumspatch.asslorde.com/ --out /srv/slums-patch --notes "SlumsMS Launcher <lv>"'`
   (use `--base-url https://slumspatch.asslorde.com/launcher-test/ --out /srv/slums-patch/launcher-test` for the Test
   channel, which needs no go). Check: every Live URL still answers 401 without a key.
7. **GO** Patch notes: build and deploy the site (`python3 tools/site/build.py`, rsync `dist/site/` to the site
   folder), then one Cody post linking it (see Discord below).

On failure: step 1 → `deploy.sh --phase restart-old`; step 2 → `tools/release/publish-client --restore-manifest
/srv/slums-staging/manifest-before-<v>.json` + restart-old; step 3 → `rollback.sh` + restore the manifest. Tell the
owner what happened before anything else.

## Discord (Cody), all **GO**

All posts go through the monitor on 164; the webhook URLs live only in `/etc/slumsms-monitor/live.env` (0600). Never
read, print or copy them.
- `slumsms-monitor announce live "text"`: one public post (add `--cody` to wrap it in a Cody line). **Capped at about
  6 per hour**; a capped post prints `limited` and is not sent. Prefer one post with a link to the site's patch notes.
- `slumsms-monitor maintenance live start --minutes N --reason "..."` / `... end`: deploy.sh does these.
- `slumsms-monitor client-ready live <v>`: "a client update is in the launcher".
- `--dry-run` on any of them prints instead of posting.
- Voice: Cody from Wizet, cheerful, game-only, no real-world claims ("I'm telling you, I really AM from WIZET").

## After a release

- Update `docs/HANDOFF.md` (what's live, backlog) and the `AGENTS.md` branch section.
- Watch live for an hour: `ssh root@192.168.0.164 'journalctl -u slumsms --since "-1 hour" | grep -cE "ERROR|Exception"'`.
- Leave `/opt/mapleroot-deployments/v<v>/` and `/srv/slums-staging/` in place (rollback needs them).
