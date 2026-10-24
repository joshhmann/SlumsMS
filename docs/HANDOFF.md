# HANDOFF: SlumsMS state after the 1.5.0 release (2026-10-24)

Read `AGENTS.md` first (the rules). This file is the current state, what's in flight, and what to do next. Written by
the coordinator (Claude) before handing work to Codex/DeepSeek until about Wed 2026-10-28. Update it when you finish
something.

Times: give the owner Pacific next to UTC (PDT = UTC-7).

**Owner's work split (2026-10-24):** this bug-fix pass is finished. The owner will hand continuing **1.5.1 bug
squashing to DeepSeek**; use `release-1.5.1`, the current TEST receipt below and section 3's remaining leads.
**Codex: 1.6.0 Ticket 60 first-test implementation is complete** on `160-bot-brain`
(`/root/work/SlumsMS-bot-brain-160`). The owner approved provisional blends and goals seeded from level/gear.
The runtime now chooses activities, starts them one at a time from each bot's own update, saves goals and bounded
personality changes, and ties action replies to accepted work. Source implementation `4d78245d3`; isolated-test
helper support `e2d6ab70a`; latest source/deployed build `ddfcaa886`. Both `slums.botlife.enabled` and `slums.botlife.execute` must be true;
all behavior remains off by default. GM 3 `!botwhy` works with the runtime, or QA plus `slums.botlife.preview`.

Supported work uses existing grind/travel/restock/break/social/FM/party/logout systems. SELL means an existing
hired-shop listing, not new NPC-sale income. PQ acknowledges existing party readiness, not quest completion.
Guild departures stay with the director's batch plan. Fishing, medals, daily dungeons and chaos bosses remain
separate tickets. No combat tuning, rewards or economy limits changed.

Full `mvn -q -o test`: **4,570 tests, 0 failures, 0 errors, 38 skipped**, 413 report classes
(`/tmp/160-brain-final-full.log`). Release tools: **35 Python tests pass**. Queue tests cover duplicates,
expired/old plans, phase changes and commitments; new tests cover persisted goals/blends, drift limits, automatic
restock selection, town reservations and truthful pending replies. No real-client verification has been done.

A separate feature TEST runs on **8491**, channels **8185-8189**, world **1**, unit `slums-life160`, folder
`/opt/mapleroot-dev/bot-life-160/Server`, test-only schema `mapleroot_life160`. It uses TEST data and a 2-core CPU /
6 GiB memory limit, with chat-model URLs/keys cleared. `tools/ops/{deploy-test,test-status,autoqa-run} --feature`
target only this copy; default shared TEST targets are unchanged. Deployment receipt:
`/opt/mapleroot-dev/bot-life-160/deploys/20261024T193837Z`, jar prefix `21fa9c40db93cc5a`.
**Verified before the 1.5.1 forward merge:** build `b5608ef4c` passed `t-bl-1/2/3` and the guild/client-version/
channel smoke checks on the isolated copy. A completed 605 s window held **3,218-3,276 bots**, averaged **1.184 CPU
cores**, and peaked at **3.576 GiB**. It recorded **10,497 decisions** (1,048.9/min) and **680 accepted starts**;
394 resolved strong chat claims were all backed. This is scripted/test-only evidence, not real-client or model-voice QA.
A normal stop wrote **3,419 bots**, 0 failed/missed, in 22.6 s. The copied DB held **3,739 saved life profiles**,
all with seven weights; 3,655 had one or two goals. JSON round-trip and drift-limit tests also pass.

**Roles / owner decision:** with the feature on, roster logins use one TrainingBot actor; fixed staff keep their
jobs. The brain chooses the roles: the completed run counted **227 HANG_OUT**, **211 SELL**, **58 FREE_MARKET**
and **1 SHOW_OFF** starts. Henesys had **59 level 1-30 / 120 level 31-70 / 60 level 71+** bots. Activities are
present; the desired old-school level mix is not yet proven. The owner chose **keep the shared actor and review the
mix in game** on 2026-10-24. Do not change routing, weights or quotas as part of this merge.

`ddfcaa886` fixes `!botwhy` to show the actual recorded decision with its age, plus current phase/request state,
rather than recomputing the old shadow guess. Its full suite is green (counts above); isolated load verification
is in progress. Latest owner instruction: merge `origin/release-1.5.1` into this feature branch before the next
large step, preserve both behaviors, rerun the full suite and push. Record the merge result here when complete.

**Resource / remaining QA:** the feature unit is capped at 2 CPU cores, Nice 19, 6 GiB total, Java heap max 5 GiB.
Alongside live/shared TEST heap limits of 6 GiB each, the host had 31,502 MiB available at the budget check.
Stop `slums-life160` after testing; do not leave it idling. Health still flags footing/overlap (also seen on shared
TEST), and an earlier load found an out-of-bounds training position on map 230040300. These remain movement leads,
not a claimed clean health sweep. See `docs/release/1.6.0-qa.md` for receipts and the owner's visual checks.

**Bug-pass lead from the load test:** standalone fix `bc514fca3` replaces the channel-reservation intrinsic
monitor with a ReentrantLock. The first feature fill stalled around 1,108 bots with all four virtual-thread
workers pinned in reserve/release; the player-storage owner could not resume. Its two-worker child-JVM regression
fails before and passes after. DeepSeek can review/cherry-pick this small fix separately for 1.5.1; no feature merge
is needed. The stalled scratch run required systemd's normal stop timeout. Its partial smoke run is not a pass.

Keep DeepSeek's shared `slums-persistence-test` running for the bug pass. Do not merge this feature branch into
`release-1.5.1`. No live operation, live DB read, desktop change or Discord post was made.

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
- **Auto-QA harness fixes (branch `151-qa-chat-gate`, commits `79f30a3bb`, `3da97eee4`; on TEST 2026-10-24).**
  Five checks failed for reasons outside the game, and all five now pass on TEST: rv-2 (a spree helper credits a
  mob it is already fighting, and heads back to its rival instead of grinding another ledge; the pretend player
  walks to the mobs when its "fight" finds none), rv-4 (`!qa do revenge` was being dropped - see below), pb-1
  (staged bots are level 10-45, fighting ones included: a level-7 rival ignores party invites), rm-4 (two exes
  staged: the objection takes one, the other crashes; an ex is no longer recruited as a wedding guest, which made
  it "busy" and unable to crash) and rm-1 (starts off HHG2 so its follow warp is a real map change).
  **The dropped GM line was a server bug too:** the chat gate compares stamps against the tick clock
  (`UPDATE_INTERVAL`, 777 ms on both servers), so two lines typed ~400 ms apart can land in the same tick and the
  second is dropped silently. GM chat and whispers are now exempt; **real players still lose a line typed inside
  one tick** - a gameplay change to raise the resolution or the window is the owner's call.
- **Second/third auto-QA rounds (same branch, commits `55d628170`, `e69ec3318`, and the rally fix after it).**
  Full run on the fixes above: **60 PASS / 4 FAIL / 11 SKIP of 75**. Three of the four are now green on TEST:
  bb-2 (a no from an earlier run inside `refusalDays` swallowed the buddy request even with the fixture's
  `certainBuddies`; the QA answer's style was also being judged "too formal" - the judge prompt now says slang is
  fine but not required), ga-1 (nothing ever wrote a `NOTICE [11] ...` line for the check to see, and the jackpot
  rolled last after a rare had already used the character's one notice per 60 s), cb-2 (every POSITION sample with
  the nearest mob in the bot's back counted, so 22 of 52 were strollers 150-550 px from any mob; facing away now
  counts only at 45 px, standing inside a mob still always counts). The KS-war helper rule also no longer needs the
  check's mark: a nemesis that calls its own war 14 s in used to make it fail with "no KS WAR call after the mark".
- **Open: KS war helpers grind on their own platform** (ticket 66, t-rv-2, about half the runs). `DramaKit.bringTo`
  only moves a helper that is on a different map; on a stacked map (104040000) the helpers end up 300-500 px above
  the player and `ksPick`'s 350x90 px reach never touches the player's mobs. Fixed here: same-map helpers are moved
  too when a scene grinds, and a staged spot is used only on the player's own ledge (|dy| <= 30). Still open: the
  training brain picks its own grind spot after landing, so a scene cannot keep the helper beside the player. Needs
  a forced/pinned grind spot for a scene's duration (`WorldEventSteer.hold` pins only the map; `GCMovement.follow`
  made the helpers stop fighting).
- **Full auto-QA baseline on the fixed build (2026-10-24 17:43-19:20 UTC, `all`, 75 checks): 56 PASS / 7 FAIL /
  12 SKIP** (was 54/9/11, then 57/7, then 60/4). Green again: rv-2, rv-4, pb-1, rm-1, rm-4, bb-2, ga-1. Also fixed
  since: cv-4 (the reply log printed each fragment's remaining wait instead of its offset from the player's line, so a
  split reply that took 1,005 ms read "+150 ms" and failed "planned faster than 900 ms"). Failures now:
  gw-1 (the enemy guild killed nothing: `ZakumSlayerz killed nothing`, turf staging/time, not the harness), gw-4 +
  gu-5 (`at least 1 x BOT_GUILD BOSS RUN` / SKIP "a boss run is already on (SlumLords)": the alliance's joint boss
  run never started because a boss run was already up), cv-5 (thread routing fixture: `{thread}` is filled and the
  route still picks the staged speaker by reply words), cv-7 (judge BAD on a bank line - content quality), cb-2 (13 of
  52 POSITION samples had a bot `INSIDE` a mob's body, dx 11-34: melee bots do clip into the mobs they hit - a real
  bot behaviour wart, not a metric artefact, so the ratio stays) and rv-2 half the time (ticket 66).
- **Remaining auto-QA leads:** af-1/af-2 (flaky - both passed several runs on this build, fail when the staged bot
  greets through `CONVO SAID` instead of `AFFINITY ACT`), cv-2/cv-5/cv-7 (chat-content judgements and thread
  fixture isolation; cv-5's routing snapshot picked the staged speaker by reply words while its thread candidate was
  a different PQ thread). Original baseline was 54 PASS / 9 FAIL / 11 SKIP; the mixed report after the first pass
  was 55/9, then 57/7, then 60/4.
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
