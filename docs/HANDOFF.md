# HANDOFF: SlumsMS state after the 1.5.0 release (2026-10-24)

Read `AGENTS.md` first (the rules). This file is the current state, what's in flight, and what to do next. Written by
the coordinator (Claude) before handing work to Codex/DeepSeek until about Wed 2026-10-28. Update it when you finish
something.

Times: give the owner Pacific next to UTC (PDT = UTC-7).

## 1. What is live

| Thing | Version | Where it came from |
|---|---|---|
| Server | **1.5.0**, online since 2026-10-24 08:01 UTC (1:01 AM PDT) | branch `release-1.5.0`, commit `06a724dfc`, jar `824b250e…` |
| Client (Live feed) | **1.5.0**, build 10500, slums.dll `FFFFE143…` | `client-1.5.0-fixes3` @ `19d0d72` |
| Tauri launcher (Live `tauri/launcher.json`) | **1.5.1**, exe `4ff35e76…` | `launcher-ui-compact` @ `cbad0ea` |
| Electron hand-over (Live `launcher.json`) | **1.5.0** (friends key built in), zip `84929C86…` | `launcher-handover` @ `fa1cabe` |

Live settings now: 5 channels, CHANNEL_LOAD 125, MIN_CLIENT_BUILD 10500, 3,000 bots at peak (BOT_POPULATION_PEAK 3500),
BOT_MAP_CAP 50, `-Xms4G -Xmx6G`, temporary roster growth (BOT_ROSTER_VETERAN_FILL_BELOW 1.0, VETERANS_PER_DAY 7200).
Deploy receipt and backups: `/opt/mapleroot-deployments/v1.5.0/` on 164. Rollback: `bash
/opt/mapleroot-deployments/v1.5.0/rollback.sh` (server, ~30 s) and `tools/release/publish-client --restore-manifest
/srv/slums-staging/manifest-before-1.5.0.json` (client). **Live actions always need the owner's go** (AGENTS.md).

The test server runs the same 1.5.0 build (`06a724dfc`) with the same settings plus `-Dslums.qa`/`-Dslums.autoqa`.

## 2. In flight right now

1. **Launcher 1.5.2: on the Test channel since 2026-10-24 ~08:45 UTC; Live still 1.5.1.** Branch `launcher-ui-compact`
   @ `573d7a2`. Game Options now write every option into the launched folder's config.ini, doubletapjump defaults to
   false and is read the way the client reads it, and a friends build's built-in key survives self-updates. Exe
   `/root/work/launcher-tauri-release/1.5.2/SlumsMS Launcher.exe` sha256 `9719282c…5158`, staged on 164 in
   `/srv/slums-staging/tauri-1.5.2/` (ignore `1.5.2-old/`, it lacks the key carry). Not yet checked with a real game.
   **Next:** the owner updates through the Test channel and checks Game Options vs the game; then **GO** Live with the
   Tauri command in `docs/release/RUNBOOK.md` step 2.6 (`<lv>`=1.5.2), after backing up
   `/srv/slums-patch/tauri/launcher.json` to `/srv/slums-staging/live-tauri-launcher.json.before-1.5.2`. Undo Test:
   copy `/srv/slums-staging/launcher-test-tauri-launcher.json.before-1.5.2` back. The Electron hand-over's
   `game-config.mjs` still writes only changed keys (has the right fallbacks); fine until Electron is retired.
2. **Friends launcher with the key built in: BUILT 2026-10-24** (`Z:\MSv83\SlumsMS-dev\launcher-tauri-152-friends\out\SlumsMS Launcher.exe`,
   1.5.2, sha256 `BD5EFBE0…2DB2`, verifier OK). The owner shares that exe with new friends. To rebuild: the owner runs `Z:\MSv83\SlumsMS-dev\bake-tauri-key.ps1` (key piped from
   164, never seen by an agent); output in `Z:\MSv83\SlumsMS-dev\launcher-tauri-152-friends\out\`; check it with
   `Z:\MSv83\SlumsMS-dev\verify-tauri-key.ps1 -Exe <exe>`. Needs no signature; it self-updates to later signed builds
   and keeps the key. `launcher-tauri-152-bakedryrun\` holds only a fake key and can be deleted.
3. **Public website** (done, not deployed): branch `site` @ `74099f82c`, worktree `/root/work/SlumsMS-site`. Build
   `python3 tools/site/build.py` → `dist/site/` (checked: no private strings, links OK). **Plan agreed with the
   owner:** serve it from 164's existing nginx on a new **port 8091** (`root /srv/slums-site`), and the owner adds
   `theslumsms.asslorde.com { reverse_proxy 192.168.0.164:8091 }` on their Caddy LXC plus a DNS record. The nginx
   change needs the owner's go (same nginx as the patch site on 8090; don't touch 8090). Before deploy: set the
   1.5.0 `date:` in `docs/site/releases/1.5.0.md` to 2026-10-24.
4. **Patch notes on Discord:** Cody's hype post went out. The 5 patch-note parts were held by the monitor's rate
   cap (6/hour). Plan: once the site is up, Cody posts one message linking `theslumsms.asslorde.com/patch-notes/1.5.0/`
   (`slumsms-monitor announce live "<text>"` on 164). Drafts: the release branch has none; the text is in the site
   (`docs/site/releases/1.5.0.md`).

## 3. Backlog for 1.5.1 (hotfixes until 1.6.0)

Owner-reported or found, roughly by priority:
- **A bot you're talking to walks away** mid-conversation (personal space or its wander habit pulls it away). A
  conversation partner should stay put ~30-60 s.
- **Guide pages too long** for the v83 NPC window (a scrollbar shows). Cut each page to about 6-7 short lines; check
  in a real client.
- **Bots standing in front of NPCs** cover NPC name tags; personal space should keep clear of NPCs too.
- **"overlap" health check** still flags some stacked town bots; `foothold` flags ~1% (27 of 2,713) off the ground.
- **PQ bots "not great"** (a friend's report; times/channel to come from the owner). Also tone down PQ lobby spam.
- **Shutdown noise:** the nav-graph warm-up thread logs "footholds is null" while maps unload at shutdown; skip
  warm-up once shutdown starts.
- **Auto-QA flakes** still failing: rv-2, rv-4, gw-4, pb-1, cu-1, rm-1, rm-4, cv-4, cv-7, cb-2 (see the latest
  `data/autoqa/latest.json` on the test server; triage with `.claude/skills/qa-triage`).
- **After the roster reaches ~9,000** (about a day after release): set BOT_ROSTER_VETERAN_FILL_BELOW back to 0.5 and
  BOT_ROSTER_VETERANS_PER_DAY to 300 on live (config edit + restart = owner's go).
- Watch live memory for a few days (the 3k soak crept ~80 MB/h in old-gen).
- Merge `release-1.5.0` back where needed: `test-1.5.0-rc6` is history; new work branches from `release-1.5.0`.

## 4. Decisions the owner made (don't re-ask)

- 3,000 bots on live; reduce if needed. Per-map cap 50 to start (FM may need more).
- Gifts from town social bots stay **off** until the gift economy is tuned. Upkeep stops within 1% of the ceiling.
  Every bot guild keeps at least one member online.
- Chat routing order: name (anywhere) > facing a close bot > clear reply to a nearby line/thread > closest bot in the
  hearing bubble (±400 px x, ±150 px y) > nobody. Logged for tuning in 1.6.0.
- Bots must do what they say (say-do). Lines that promise actions are only used when the action happens.
- `@goto` is a player command, towns only. Double-tap jump off by default; Ninja Bounce air-only; thieves and Night
  Walkers keep the strong bounce.
- Romance with bots is opt-in (`@romance on`), PG-13. `@botdrama off` is always respected.
- 1.6.0 headline: **the bot brain** (`docs/tickets/60-bot-brain.md`), plus fishing, medals, a daily dungeon,
  chaos bosses, an Evan test, chat-routing tuning, the Parsec fix (dgVoodoo2), a real client settings window. 1.7.0:
  new regions, Evan or a skill cover, client QoL, a browser-client research spike. Full roadmap: `docs/ROADMAP.md`.

## 5. Tools that make this easier

- `tools/ops/deploy-test` (deploy to test), `tools/ops/test-status`, `tools/ops/autoqa-run all|smoke|<id>`.
  Auto-QA also runs nightly at 10:00 UTC and daily at 22:00 UTC (cron on 164).
- Release pipeline: `tools/release/` (README); the whole cycle step by step: `docs/release/RUNBOOK.md`.
- QA (auto-QA, client MCP via `tools/ops/client-mcp`, owner checklist): `docs/qa/QA.md`.
- Client visual QA on the owner's PC: `Z:\MSv83\SlumsMS-dev\client-mcp\` (Codex-built MCP; runs only in the owner's
  session, as admin, with the PC idle; see its README). Its test account is `autoqaclient` / character `AqClient`
  (GM 3 on the test server only).
- Owner's PC guide: `Z:\MSv83\SlumsMS-dev\AGENTS.md`.

## 6. Ask the owner when

Anything on live, any publish to the Live launcher/client feed, the nginx 8091 change, Discord posts, economy changes,
or anything this file or AGENTS.md doesn't cover.
