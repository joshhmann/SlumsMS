# HANDOFF: SlumsMS state after the 1.5.0 release (2026-10-24)

Read `AGENTS.md` first (the rules). This file is the current state, what's in flight, and what to do next. Written by
the coordinator (Claude) before handing work to Codex/DeepSeek until about Wed 2026-10-28. Update it when you finish
something.

Times: give the owner Pacific next to UTC (PDT = UTC-7).

**Owner's work split (2026-10-24):** this bug-fix pass is finished. The owner will hand continuing **1.5.1 bug
squashing to DeepSeek**; use `release-1.5.1`, the current TEST receipt below and section 3's remaining leads.
**Codex is working on 1.6.0**, Ticket 60 on `160-bot-brain`
(`/root/work/SlumsMS-bot-brain-160`; plans `5ca02907f`, `2c57baa41`). The owner approved provisional blends and
level/gear goal seeding for the first test version. Three local checkpoints now implement the pure scorer,
provisional profiles/goals, memory-only context and opt-in `!botwhy` shadow inspection. Automatic activity
selection and persistent goal/drift changes are not installed. Explicit restock requests now start one at a time
from the bot's normal update, behind the test-only flag. The roadmap puts the bot brain first.
Fishing, medals, the daily dungeon and chaos bosses still need more
detailed tickets and their listed owner decisions. Ticket 61's "spec/to be built" header is stale: the client MCP
already exists per section 5.
Keep the shared `slums-persistence-test` build for the bug pass; feature work must coordinate any test checkpoint
or use a separately isolated test instance within the host caps. Do not merge 1.6.0 features into `release-1.5.1`.

**1.6.0 checkpoint 2:** profiles/context `1fd3d5700`; acceptance/restock adapter and staged QA `97c74c095`.
Original scorer `ba7833379` and initial inspector `c0ce7dbe6` remain included, as do the 1.5.1 fixes through
`2c7e5bf22`. Seven-archetype blends are stable per character ID, with a 55-70% dominant share; durations are
20-60 min grind / 5-20 min town. Goals derive from job stage and cached weapon requirements without granting,
buying or forcing gear changes. These are provisional shadow aspirations, not persisted goals or roster writes.

GM 3 `!botwhy <name>` still requires QA plus `-Dslums.botlife.preview=true`, off by default. It now shows the
profile, goals, HP/bag/progress needs, known session fatigue/phase age, bounded local affinity, component scores,
selection/holds and unavailable options. Missing MP/ammo pressure, social timing, distant friends/destinations,
shop/selling and event/PQ data stay unknown. The new roster lookup is memory-only; cached weapon data is never
loaded on demand. Per-player drama opt-out suppresses hostile pull. Travel and commitments keep their owner.

`LifeAcceptance` returns action facts only for accepted work; refused or failed requests have none. The original
restock adapter was not connected to bot updates at checkpoint 2; checkpoint 3 below now supplies that connection.

`!qa run bl-1` and auto-QA `t-bl-1` run golden policy/refusal fixtures without touching the game world. The local
scenario test passes; it has not run on TEST. **23 new focused tests plus 10 existing catalogue checks pass**;
the prior 19 scorer/inspector tests also pass. Full `mvn -q -o test`: **4,532 tests, 0 failures, 0 errors,
38 skipped**, 404 report classes (`/tmp/160-life-v2-full.log`).
Local pure-policy benchmark: 60,000 evaluations in 365.6 ms wall / 363.1 ms thread CPU, average 18.3 ms wall per
3,000 after warm-up, prebuilt profiles/contexts. This excludes capture/scheduling/execution and is not a server
soak. Details/calibration: `docs/tickets/60-bot-brain.md`; runtime checklist: `docs/release/1.6.0-qa.md`.
No shared TEST deploy/restart, real-client check, live operation or Discord post for either feature checkpoint.
Next: finish supply/destination/event adapters, persistence/drift, serialised activity acceptance and truthful
chat integration; coordinate feature TEST use and real-load QA before enabling automatic decisions.

**Checkpoint 3: one request at a time.** Queue `0f9ed4de5`; bot update and staged QA `f51ad81f4`.
The previous code could check a request but could not stop the same request
from being started twice. Each TrainingBot now has one waiting slot and one running activity. A repeated request
cannot start another trip. Newer waiting plans replace older ones. Requests older than 30 seconds, plans made
before a phase change, and requests belonging to a stopped/different bot instance cannot run.

A worker or command may submit a request, but the bot's normal update starts it. At that moment it checks again
for a trade, invitation, conversation or other hold. The restock start leaves GRIND and enters GO_TOWN directly;
the new path does not use the old cross-thread restock flag. The running request ends when grinding resumes or
is cancelled when the bot dies, changes channel, stops or another event takes over. Returning to grinding does
not prove it reached a shop or bought anything. An old completion callback cannot clear a newer activity.

QA and `-Dslums.botlife.execute=true` are still required. That flag alone creates no requests, and automatic
activity choices and new chat lines are not connected. `!botwhy` remains read-only and now also shows waiting and
running request numbers. There is no extra scheduler or growing request history.

`!qa run bl-2` stages one temporary grinder. Once it is ready, `!qa do restock` submits the same request twice;
`!qa do repeat` retries it later. Exactly one `HABITS LIFE START activity=RESTOCK` must appear. Auto-QA `t-bl-2`
checks that count; with the execute flag off it skips. Stopping QA removes the fixture and closes its requests.
Local focused tests pass for duplicate/overlapping starts, real TrainingBot phase changes, conversation/trade holds,
flags and QA cleanup; existing combat/logout checks also pass. **20 new tests pass**. Full `mvn -q -o test`:
**4,552 tests, 0 failures, 0 errors, 38 skipped**, 407 report classes (`/tmp/160-life-serial-full.log`).
No shared TEST deploy/restart, live changes or in-game checks were made.
Next: coordinate the feature TEST check and watch the trip in a real client, then add completion checks and
automatic choices. Runtime QA checklist: `docs/release/1.6.0-qa.md`.

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

The test server now runs **1.5.1 integration commit `c2b1a7974`** (NPC spacing, synthetic login, reply routing and
guild-chat QA fixes), installed on 2026-10-24 at **5:57 AM PT (12:57 UTC)**. Its version banner still says 1.5.0;
this is a test integration build, not a live release. Source: `release-1.5.1` in `/root/work/SlumsMS-npc-space`.
The same test settings and QA flags remain. Live was not changed.

## 2. In flight right now

1. **Launcher 1.5.2: LIVE since 2026-10-24 ~08:50 UTC** (Test and Live; undo Live by copying
   `/srv/slums-staging/live-tauri-launcher.json.before-1.5.2` over `/srv/slums-patch/tauri/launcher.json`). Branch `launcher-ui-compact`
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
3. **Public website: deployed 2026-10-24** on 164 nginx **port 8092** (`/etc/nginx/sites-available/slums-site`, files
   `/srv/slums-site`; 8091 is the test admin dashboard, 8090 the patch site: don't touch either). Source: branch
   `site`, worktree `/root/work/SlumsMS-site`; `python3 tools/site/build.py` builds and checks `dist/site/`. Redeploy:
   `rsync -a --delete dist/site/ root@192.168.0.164:/srv/slums-site/`. The owner's Caddy points
   theslumsms.asslorde.com at 192.168.0.164:8092 (owner's side; first set to 8091 by mistake, being moved).
4. **Patch notes on Discord: DONE 2026-10-24** (Cody linked https://theslumsms.asslorde.com; the monitor's public filter
   blanks long URLs like `/patch-notes/1.5.0/`, so link the home page).

5. **NPC spacing fixed on TEST (2026-10-24):** branch `151-npc-space` commit `743f2373d`, merged as
   `3b0c51aa1`, now included in `c2b1a7974`. Personal space ignored NPCs in both destination selection and resting
   checks. NPC positions now count as immovable obstacles, using the existing spacing and floor rules. Two
   regression cases failed before the fix. Latest `t-ps-2` PASS: the staged bot walked from 0 px to 49 px off an NPC
   in 2 seconds, then despawned at cleanup (`logs/qa/ps-2-20261024T130238Z.log`). **Owner next:** in Henesys on TEST
   run `!qa run ps-2`, check the NPC name is readable and clickable, then `!qa stop`. Real-client appearance is
   unverified. Checklist: `docs/release/1.5.1-qa.md`. Draft notes: `docs/site/releases/1.5.1.md` (not published).
6. **Synthetic QA login/save fix on TEST:** branch `151-qa-account-cache` commit `15b2dada8`, merged as
   `eb7e1bd18`. Both fix branches and the release branch are pushed. Synthetic login skipped the normal
   character-list call to `Server.loadAccountCharacters`, leaving the world account-view cache absent. Saving
   then threw in `World.registerAccountCharacterView:462` during logout and autosave, and also blocked the old
   build's channel shutdown. Synthetic login now loads those views before its channel transition; regular-player
   login and save code are unchanged. Regression tests for both fixture slots failed before and pass after.
   Full offline Maven suites passed on each fix branch and after merging. **Combined suite at that checkpoint:
   4,486 tests, 0 failures, 0 errors, 38 skipped** (no local WZ data).
   Test jar `1eb53708272173b1…`; install backup `/opt/mapleroot-dev/persistence-test/deploys/20261024T100747Z/`.
   The old TEST process hung on the same cache exception during that deploy: zero connected clients and all
   1,169 bots had been saved with zero failures before it was ended with `systemctl kill --signal=SIGKILL
   slums-persistence-test`. The fixed build passed `t-ps-2` and smoke `t-bg-1`, `t-vg-1`, `t-ch-1`, with zero
   ERROR/Exception lines through both login/logout cycles. A controlled restart of the same jar then completed
   normally: shutdown about 14 s, 1,162 bots saved, zero failures, zero boot errors; backup
   `/opt/mapleroot-dev/persistence-test/deploys/20261024T101345Z/`. Hourly autosave was not separately waited for.
   The old build's scheduled 3:00 AM PT (10:00 UTC) nightly was interrupted by that fix deploy. The broader
   QA and health leads remain open; see item 8 for the latest verification and saved-report counts.
7. **Delayed replies stay in their original channel:** branch `151-chat-reply-routing` commit `8f11d3f8e`,
   merged into `release-1.5.1` as `c2b1a7974`. A bot/player pair shared a mutable reply callback: a guild message
   sent while a whisper reply waited on the model could redirect the whisper's reply into guild chat. Each
   request now captures its destination and priority before asynchronous work starts; history and the 60 s
   idle clock remain shared. A controlled delayed-future test failed before and passes after, checking both
   destinations and shared history. Full fix-branch suite: 4,480 tests, 0 failures/errors, 38 skipped. The real-client
   overlapping-channel case remains untested: use the `t-gu-1` manual checklist in `docs/release/1.5.1-qa.md`.
8. **Guild-chat QA checks real delivery:** branch `151-qa-guild-chat` commit `2b79bdbf8`, merged as `124e13aee`.
   The old check demanded an answer to "hi guys" despite the normal 60% greeting chance and cooldown, and accepted
   an "answers" hook without proving delivery. It now names the staged officer, waits up to 30 s for that bot's
   guild packet, and requires the packet before the next exchange. Normal guild-chat probabilities are unchanged.
   Two regression cases failed before; three tests now cover named staging, undelivered replies and successful
   receipt. Full fix-branch suite: 4,482 tests, 0 failures/errors, 38 skipped. Both new branches are pushed.
   **Combined suite after both merges: 4,490 tests, 0 failures, 0 errors, 38 skipped** (local WZ data absent).
   Test jar `ba4e2fb5bb790f98…`; backup `/opt/mapleroot-dev/persistence-test/deploys/20261024T125727Z/`.
   `t-gu-1` PASS twice, including actual `MULTICHAT guild <officer>:` receipt and clean cleanup:
   `logs/qa/gu-1-20261024T125935Z.log` and `gu-1-20261024T130034Z.log`. Smoke `t-bg-1`, `t-vg-1`, `t-ch-1` and
   NPC check `t-ps-2` PASS on this build; zero ERROR/Exception lines since deployment. The saved mixed report is
   now 55 PASS / 9 FAIL / 11 SKIP (75 checks); this is not a completed full run on the new build. Foothold and
   overlap health flags remain open. No real-client checks, live changes or Discord posts were made.

## 3. Backlog for 1.5.1 (hotfixes until 1.6.0)

Owner-reported or found, roughly by priority:
- **A bot you're talking to walks away** mid-conversation (personal space or its wander habit pulls it away). A
  conversation partner should stay put ~30-60 s. Source audit: `BotConversation` already keeps a 60 s session,
  but the wanderer and fidget paths do not use it as a movement hold; personal space only recognises the pair.
  This remains unfixed.
- **Guide pages too long** for the v83 NPC window (a scrollbar shows). Cut each page to about 6-7 short lines; check
  in a real client.
- **"overlap" health check** still flags some stacked town bots; `foothold` flags ~1% (27 of 2,713) off the ground.
- **PQ bots "not great"** (a friend's report; times/channel to come from the owner). Also tone down PQ lobby spam.
- **Shutdown noise:** the nav-graph warm-up thread logs "footholds is null" while maps unload at shutdown; skip
  warm-up once shutdown starts.
- **Open auto-QA leads** in the latest mixed report: af-1, af-2, rv-2, rm-1, rm-4, cv-2, cv-5, cv-7, cb-2.
  gu-1 is now a repaired QA check with two passing server runs (section 2 item 8). The original full-run baseline
  was 54 PASS / 9 FAIL / 11 SKIP; its QA logs and journal were read during the first bug-fix pass. In that older run:
  rm-4 explicitly logged "nobody to crash the reception (no ex or rival online)" after staging an ex (fixture issue).
  af-1 did greet the player, but its judge also graded ambient arrow-shopping chat as a greeting. cv-5's routing
  snapshot chose the staged Orbis-PQ speaker by reply words while its active thread candidate was a different
  Kerning-PQ thread; thread fixture isolation needs investigation. The missing KS/follow actions,
  chat-content judgements and combat positions still need separate reproduction and fixes.
- **Partial-nightly lead:** af-2 also failed on the old build during the interrupted 3:00 AM PT (10:00 UTC) nightly (missing
  taunt/KS action). It needs reproduction on the latest build; it was not changed as part of these fixes.
  The account-cache exception found in the older logs is fixed (section 2 item 6).
- **Roster growth back to normal, in the 1.5.1 deploy** (owner OK'd 2026-10-24; held so tonight's 3k peak has enough
  bots): once the roster is past ~9,000 (`journalctl -u slumsms | grep "roster holds\|Roster:"`), set
  BOT_ROSTER_VETERAN_FILL_BELOW 0.5 and BOT_ROSTER_VETERANS_PER_DAY 300 in live `config.yaml` during the 1.5.1
  stop-backup/apply-start window, so it costs no extra restart.
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

Anything on live, any publish to the Live launcher/client feed, nginx changes, Discord posts, economy changes,
or anything this file or AGENTS.md doesn't cover.
