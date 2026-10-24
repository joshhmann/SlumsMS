# AGENTS.md: how to work on SlumsMS

Read this whole file before changing anything. It applies to every agent (Claude, Codex, DeepSeek or a person) and to
every part of the project: server, client, launcher, patch site, test server and live. If something here conflicts
with a ticket or a comment, this file wins; if it conflicts with the owner's latest instruction, the owner wins.

SlumsMS is a friends-only MapleStory v83 private server (5 to 15 real players) with thousands of AI bot players. The
server is a fork of MapleRoot/Cosmic (Java 21, Maven). The owner decides; you build, test and propose.

## 1. Hard rules (break one of these and you can do real damage)

1. **Live is the owner's.** Never stop, restart, deploy to, edit, or write to the live server, its database, its unit
   or the Live patch feed without the owner's explicit "go" for that exact action in the current conversation.
   Approval for one thing never covers another. Live = unit `slumsms`, folder `/opt/mapleroot/MapleRoot Full
   Repack/Server`, DB container `buns-clean-db-1` (port 3309, schema `mapleroot`), patch files
   `/srv/slums-patch/manifest.json`, `/srv/slums-patch/layers`, `/srv/slums-patch/tauri/`.
   Reading live logs, files and the unit is fine. Reading the live DB needs the owner's go too.
2. **Test freely.** The test server, the RC and Test patch channels and anything under `/opt/mapleroot-dev` are yours
   to change, deploy and restart, but tell the owner before a restart if they might be playing or running auto-QA
   (a restart stops a running `!autoqa`).
3. **Secrets never pass through you.** Never print, copy, log, commit, paste into a prompt or send anywhere: the
   patch access key (`/root/slums-patch-access-key.txt` on 164), the dev key, the Tauri signing key
   (`/root/.config/slumsms-launcher/tauri-signing.key`), cloud LLM keys (`/root/.config/slumsms/cloud-llm.env`,
   `/etc/slums-persistence-test/cloud-llm.env`), webhook URLs (`/etc/slumsms-monitor/live.env`), DB passwords.
   Reference them by path; load env files with `set -a; . file; set +a` in the same command that uses them. If a
   build needs a key baked in, the owner runs that command.
4. **If a permission check or the owner refuses an action, stop.** Don't find another route to the same result and
   don't ask another agent to do it. Report what was blocked and why it was needed, then let the owner decide.
5. **The owner's desktop ("Silvia", Windows, `crims@192.168.0.186`, `ssh -i ~/.ssh/id_rsprox_desktop`) is
   read-only except `Z:\MSv83\SlumsMS-dev\`.** Make a new folder there for your work. Never touch
   `Z:\MSv83\SlumsMS-dev\test-client`, the owner's installs, `%APPDATA%` or running games, and never kill processes.
   GUI programs started over ssh have no desktop; use a scheduled task if you really need one.
6. **Publishing is outward-facing.** Discord posts (Cody), the Live patch feed and anything players see need the
   owner's go. The RC and Test channels don't.
7. **No surprise scope.** Do what was asked. Economy changes in particular need the owner's say-so.
8. **Report honestly.** Say what you ran and what you didn't. "Tests pass" means you ran them and they passed. Say
   when something is untested in a real client. Never claim a fix works in game unless the owner confirmed it.

## 2. Map

### Machines
| Name | What | Notes |
|---|---|---|
| 187 (this workstation, "openclaw") | where agents run; all git checkouts under `/root/work` | Codex CLI, Maven, node |
| 164 (`root@192.168.0.164`, LXC "maplestory", 12 cores, 48 GB) | live server, test server, patch site, release staging | shared by live and test: keep test inside its CPU/memory caps |
| Silvia (`crims@192.168.0.186`, Windows) | builds the client DLL and launcher exe; the owner plays here | see rule 5; Parsec is often running |
| 192.168.0.17 | local LLM (Qwen `qwen9b`, `:8095/v1`, 2 slots) and GLiNER Decide (`:8900`) | also serves live chat: keep load light |

### Servers on 164
| | Live | Test | Soak |
|---|---|---|---|
| Unit | `slumsms` | `slums-persistence-test` (transient, started by `/opt/mapleroot-dev/persistence-test/start-test.sh`) | `slums-soak3k` (stopped) |
| Folder | `/opt/mapleroot/MapleRoot Full Repack/Server` | `/opt/mapleroot-dev/persistence-test/Server` | `/opt/mapleroot-dev/soak3k` |
| Ports | login 8485, channels 7585+ | login 8489, channels 7985-7989 | 8490 / 7990+ |
| DB | `buns-clean-db-1`:3309 `mapleroot` | `mapleroot-integration-db`:3307 `mapleroot_persist` | same container, `mapleroot_soak3k` |
| World | id 1 (Bera) | id 1 | id 1 |

Both servers run world id **1**, never 0. Code that assumes world 0 is a bug (see the guild-rank bug fixed in rc6).

### Repositories (all under `/root/work`, GitHub `joshhmann/*`)
| Repo | Checkout | What |
|---|---|---|
| `SlumsMS` (server) | `/root/work/SlumsMS` (main checkout) plus one worktree per task | Java server, scripts, data, migrations, release tools |
| `SlumsMS-Client` | `/root/work/client/src` (+ worktrees) | `slums.dll` (C++ hooks into the v83 client) and `Custom.wz`; built on Silvia |
| `slumsms-launcher` | `/root/work/launcher-tauri` (branch `launcher-tauri`), `/root/work/launcher-handover` | Tauri launcher 2.0 (Rust + HTML UI) and the Electron hand-over build |
| `SoloMapling` | `/root/SoloMapling` | the bot project's history and notes; not deployed |

### Branches
- **Live runs 1.5.0** (released 2026-10-24 08:01 UTC) from branch `release-1.5.0` (commit 06a724dfc). That branch
  is now the integration branch: make fix branches from `origin/release-1.5.0` (`git worktree add ... -b
  151-<topic> origin/release-1.5.0`), and the coordinator merges them into a `release-1.5.1` branch for the next patch.
  `test-1.5.0-rc6` is the history of 1.5.0; don't build on it.
- Previous releases: `hotfix-1.4.2-haggle` (1.4.2), `release-1.4.1`.
- Client: `client-1.5.0-fixes3` is the current client line (live DLL FFFFE143, build 10500). Launcher:
  `launcher-ui-compact` (Tauri; 1.5.1 on Live, 1.5.2 in progress), `launcher-handover` (Electron hand-over, must include
  `7f385b0`; on Live as 1.5.0).
- **Current state, open work and decisions: `docs/HANDOFF.md`.** Read it after this file.

## 3. Working on the server

### Start a task
```
git -C /root/work/SlumsMS fetch -q origin
git -C /root/work/SlumsMS worktree add /root/work/SlumsMS-<topic> -b 151-<topic> origin/release-1.5.0
cd /root/work/SlumsMS-<topic> && cp config.example.yaml config.yaml
```
Never work in someone else's worktree. Check `git worktree list` first.

### Build and test
- Full suite: `mvn -q -o test` (about 10 minutes, 4,000+ tests). Must be green before you hand back.
- One test: `mvn -q -o test -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false`.
- Jar: `mvn -q -o -DskipTests package` → `target/Cosmic.jar`.
- Release tools: `python3 -m unittest discover -s tools/release -p 'test_*.py'`.
- GitHub CI has no `wz/` game data. A test that reads WZ must call `soloMapling.WzTestData.assumePresent("Item.wz")`
  (or similar) so it skips there. To check a class exists without running its static init, use
  `Class.forName(name, false, loader)`. Timing tests need generous margins; CI machines are slow.
- Add a test for every fix. A fix without a test that fails before and passes after is not done.

### Code facts that bite
- **Config is strict:** an unknown key in `config.yaml` aborts boot. New settings are `ServerConfig` fields with safe
  defaults, or `-D` system properties. Never add a key to a server's config before that server runs a build that
  knows it.
- **Bots** are `Character`s whose client is `BotClient` (character ids >= 1,000,000,000). A bot's
  `getClient().getPlayer()` is **null** (shared headless client). Use the `Character` you already have. Packets sent
  to a bot go nowhere. Check `instanceof BotClient` before player-only logic.
- **Auto-QA pretend players** use `SyntheticClient`: real players to bots, but never counted as connected players.
- **Migrations** live in `database/migrations/NNN-name.sql` with a matching `.rollback.sql`, are idempotent
  (`IF NOT EXISTS`, information_schema guards, ledger insert into `mapleroot_integration_migrations`), and are listed
  in `docs/release/<version>.conf`. Test apply-twice and rollback-twice on a throwaway MySQL 8.0 container.
- **Data files** the server reads at runtime (YAML packs, NPC/event scripts, `cashshop/catalog.tsv`) live in the repo
  and must be deployed with the jar; `tools/ops/deploy-test` and `tools/release/stage` handle that.
- The QA suite (`!qa`) and auto-QA (`!autoqa`) only exist with `-Dslums.qa=true` / `-Dslums.autoqa=true`; live has
  neither. Every new player-visible feature gets a `!qa` scenario (see `docs/ops/qa-suite.md`, "Adding a scenario")
  and, where it can be judged from logs, an auto-QA check.
- Logs: `journalctl -u <unit>`, `Server/logs/`, QA run logs in `Server/logs/qa/<id>-<UTC>.log`, auto-QA results in
  `Server/data/autoqa/latest.json`, health sweeps in `Server/data/autoqa/health-latest.json`.

### Deploy to the test server
```
mvn -q -o test                      # green first
tools/ops/deploy-test --dry-run      # shows the data files and migrations that will go out
tools/ops/deploy-test                # build, back up, copy, migrate, restart, check the boot
tools/ops/test-status                # version, errors, auto-QA and health at a glance
tools/ops/autoqa-run smoke|all|<id>   # start auto-QA without logging in (picked up within 30 s)
```
`deploy-test` diffs against `Server/DEPLOYED_COMMIT`, keeps backups in
`/opt/mapleroot-dev/persistence-test/deploys/<stamp>/`, and fails if the boot logs an error. The test server's
`config.yaml` and `start-test.sh` are edited by hand when a release needs it; back them up first (`cp -p f f.pre-X`).

### Release to live (owner's go at each marked step)
The pipeline is `tools/release/` (read its README):
`build` → `stage <version>` (needs `docs/release/<version>.conf`) → `rehearse <version>` (full copy of live, deploy +
rollback, must PASS) → **owner** runs `bash /opt/mapleroot-deployments/v<version>/deploy.sh` on 164.
Client and launcher go out in MMO order, client first: `stage-client`, then `publish-client` with the hash-diff rule.
A client published to the Live feed must point at `slumsms.asslorde.com:8485` (the tools refuse otherwise). Never
promote an RC client layer to Live: those point at the test server.

### Full release cycle, Discord, notes and QA

**`docs/AGENT-PROMPTS.md`** has starter prompts per kind of work. **`docs/ROADMAP.md`** is what comes next (1.5.x hotfixes, 1.6.0, 1.7.0). **`docs/qa/QA.md`** is how to QA: auto-QA,
client visual QA through the client MCP (`tools/ops/client-mcp`), and the owner's checklist.
**`docs/release/RUNBOOK.md`** has every step: development, test deploys, auto-QA and the player QA checklist, patch notes,
pre-checks, the live sequence with the owner's go points, rollback, and Cody's Discord posts through `slumsms-monitor`.
Release notes and QA checklists live in the repo (`docs/site/releases/<v>.md`, `docs/release/<v>-qa.md`); the older
claude.ai pages are only readable by Claude.

### Which agent can do what

Claude, Codex and DeepSeek agents on 187 can all use ssh to 164 (`root@192.168.0.164`) and to the owner's PC
(`crims@192.168.0.186`, key `~/.ssh/id_rsprox_desktop`), git and the scripts here. Claude-only tools (claude.ai
artifact pages, their databases) are not required for any step; use the repo files instead.

## 4. Working on the client (`slums.dll`)

- Source: `/root/work/client/src` (+ worktrees such as `src-150fixes`). Make a branch from the current client line.
- Build on Silvia in a NEW folder under `Z:\MSv83\SlumsMS-dev\` (copy how `client-1.5.0-fixes3` was built: a clone,
  `cmake` + MSVC Release, native tests with `ctest`). Record the DLL's sha256.
- Test with a COPY of the RC client under `SlumsMS-dev`, never the owner's installs. If the owner's client has the DLL
  locked, put yours next to it as `slums.dll.new` and say so.
- Patches are byte patches, code caves and hooks at fixed v83 addresses. Read the stock bytes from an exe copy
  (`Z:\MSv83\SlumsMS-dev\client-1.5.0-fixes2\exe-copy\MapleStory.exe`, file offset = VA - 0x400000) before you
  change anything. Keep every patch switchable from `config.ini` when you can.
- "error code -2147467259 (Unspecified error)" at startup on Silvia means the main display is a virtual one (Parsec):
  not a client bug. Make the real monitor the main display.
- Bot attacks with a pose or weapon the client doesn't expect are dropped by the client: check new bot skills on a
  real client.
- Publish an RC client with `tools/release/stage-client` + `publish-client` to `/srv/slums-patch/launcher-rc` (RC
  channel). Before publishing, hash-diff against the previous client: only the files you meant to change may differ.

## 5. Working on the launcher

- Tauri 2.0: `/root/work/launcher-tauri` (Rust core in `tauri/core`, app in `tauri/app`, UI in `tauri/ui`). Tests:
  `npm test` at the repo root and `cargo test -p slums-core`. The Windows exe is built on Silvia.
- Signing happens on 187 only: `cargo tauri signer sign -f /root/.config/slumsms-launcher/tauri-signing.key -p ""
  --app-version <v> <exe>`. The key never leaves 187.
- Publish to the Test channel with the publisher in `/opt/slums-tools/launcher-tauri-publisher` (see
  `/root/work/launcher-tauri-release/RUNBOOK.md`). Channels: Live `/srv/slums-patch/` (player key), Test
  `/launcher-test/` and RC `/launcher-rc/` (dev key only).
- The Electron hand-over build (`launcher-handover`) must contain the pure-JS BLAKE2b fix (`7f385b0`); Electron's
  crypto has no blake2b. Any hand-over build from before that commit is broken and must never be published.

## 6. QA

- The owner's QA page lists every check with an id (`t-af-1` ...). The owner marks Pass / Issue / Fail with notes.
- `!qa run <id>` stages a scenario and logs to `Server/logs/qa/`; `!qa` with no arguments opens a menu.
- Auto-QA (`!autoqa run all|<id>|smoke`, nightly at 10:00 UTC) runs most checks with two pretend players and writes
  `data/autoqa/latest.json`. A FAIL is a lead: read the run's QA log before fixing anything.
- When a check fails, read the QA log of that run first. Most "bugs" in QA so far were staging problems.

## 7. Conventions

- **Times** for the owner: Pacific next to UTC, e.g. "6:36 AM PT (13:36 UTC)". PDT = UTC-7 until Nov 1 2026.
- **Commit messages:** an imperative title that says what changed for the player or operator, a body that explains
  why and how it was found, and these two trailer lines (Claude agents; other agents use their own attribution):
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: <session link>
  ```
- **Writing for the owner:** plain words, what it means for players, numbers with units, no jargon dumps. Say what you
  could not verify.
- **Bot content:** PG-13. Romance only with `@romance on` (off by default). Insults are about the game only (gear,
  level, skill, KS). Bots never say they are AI. Respect `@botdrama off`.
- **Codex CLI** on 187 or Silvia for bounded jobs and reviews:
  `codex --yolo exec -m gpt-6.1-sol -c model_reasoning_effort='"medium"' -C <dir> -o <out.md> - < prompt.txt`.
  Always feed the prompt on stdin, or it waits forever. Use `-s read-only` for reviews. Verify everything it reports.

## 8. Handing back

End every task with: what changed (files, branch, commit hashes), what you tested and how (suite counts, in-game or
not), what you did NOT do or could not verify, what needs the owner's decision, and exact next steps. Push your
branch; don't merge into the rc branch yourself unless you were asked to.
