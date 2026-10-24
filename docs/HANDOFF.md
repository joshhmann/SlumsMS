# HANDOFF: SlumsMS state after the 1.5.0 release (2026-10-24)

Read `AGENTS.md` first (the rules). This file is the current state, what's in flight, and what to do next. Written by
the coordinator (Claude) before handing work to Codex/DeepSeek until about Wed 2026-10-28. Update it when you finish
something.

Times: give the owner Pacific next to UTC (PDT = UTC-7).

**Owner's work split (2026-10-24):** this bug-fix pass is finished. The owner will hand continuing **1.5.1 bug
squashing to DeepSeek**; use `release-1.5.1`, the current TEST receipt below and section 3's remaining leads.
**Codex: Ticket 60 is implemented on `160-bot-brain`** (`/root/work/SlumsMS-bot-brain-160`). It now has weighted
choices, provisional blended profiles and level/gear goals, saved goals and bounded drift, one waiting/one active
activity, real restock trips, existing town/FM/help/PQ adapters and truthful pending/accepted replies. GM 3
`!botwhy` shows the last actual decision with its age and the current phase/request state. Both
`slums.botlife.enabled` and `slums.botlife.execute` must be true; behavior stays off by default. Preview-only QA
remains available. No combat tuning, new rewards or economy limits changed. SELL means existing hired-shop
listing; PQ means accepted party readiness, not quest entry. Fishing/medals/dungeons remain separate work.

**Forward merge completed:** `origin/release-1.5.1` at `1a3155d2e` merged into this branch as **`c37c9d528`**,
with no Git conflicts. Reviewed shared QA/chat/wedding code: the upstream timing/staging/ex-guest fixes and the
brain's pending-reply, marriage-event and bl-1/2/3 hooks remain. PersonalSpace/DramaKit match the upstream behavior;
the feature's QaWorld hooks remain. Nothing was merged back into 1.5.1.
Post-merge `mvn -q -o test`: **4,580 tests, 0 failures, 0 errors, 38 skipped**, 415 report classes
(`/tmp/160-brain-postmerge-full.log`). Pre-merge inspector/runtime build `ddfcaa886`: 4,570/0/0/38. Release tools:
35 Python tests pass. These are automated checks, not a real-client result.

**Latest isolated load evidence (`ddfcaa886`, before the forward merge):** 12:43:27-12:53:32 PM PT
(19:43:27-19:53:32 UTC), 605 s at **3,030-3,228 bots**, **0.722 mean CPU cores**, **3.311 GiB peak service memory**.
It recorded **11,052 decisions (1,103.9/min)** and **740 accepted starts** during the window. All **485 resolved
strong claims** since boot were backed; 0 were open at the last sample. This is total server CPU, not an
incremental brain-cost claim. A normal earlier stop wrote 3,419 bots with no failed/missed writes. The latest
copied DB holds **5,283 life profiles**, all with seven weights; 5,262 have one or two goals after final validation. JSON round-trip,
goal completion and drift-limit tests pass. Model endpoints/keys are cleared in this copy, so model voice remains
untested. Health still flags footing/overlap; an earlier sample found a training bot out of bounds on 230040300.
Those are open movement leads, not a clean overall health verdict.

**Role design confirmed by the owner:** keep the shared actor for rc1 and review the mix in game. Roster logins
use TrainingBot, then the brain chooses its work; fixed staff keep their jobs. Latest counters since boot:
**176 HANG_OUT, 210 SELL, 52 FREE_MARKET** starts. Henesys held **78 level 1-30 / 141 level 31-70 / 51 level 71+**
bots, including fixed staff and brain actors. An earlier run also recorded SHOW_OFF. Town and merchant activity
exists; this does not prove the desired old-school mix is tuned. Routing, weights and quotas are unchanged.

**Test copy / budget:** `/opt/mapleroot-dev/bot-life-160/Server`, unit `slums-life160`, login 8491,
channels 8185-8189, world 1, schema `mapleroot_life160` in the integration DB container. Unit limit 2 CPU cores,
Nice 19, 6 GiB total; Java heap max 5 GiB. Live/shared TEST heap limits are 6 GiB each; the host had 31,502 MiB
available at the budget check. The feature copy is **stopped/inactive**, and login 8491 has no listener. Do not leave it idling.
Use ops helpers with `--feature` only. Latest pre-merge load receipt: `/opt/mapleroot-dev/bot-life-160/deploys/20261024T193837Z`, jar `21fa9c40db93cc5a`.

**Merged TEST verification complete:** build `1bb349053` (code from merge `c37c9d528`) booted at
12:58:28 PM PT (19:58:28 UTC), 2026-10-24, with zero errors. Seven selected checks passed by 1:02:27 PM PT
(20:02:27 UTC): `bg-1,ps-2,vg-1,ch-1,bl-1,bl-2,bl-3`. This includes upstream NPC spacing plus policy safeguards,
one start for repeated requests and a trip chosen from a real potion shortage. It is not a full 78-check run.
Small-run health was WARN on footing/overlap; the earlier 3k run had FAILs, so movement health is not cleared.
Receipt `/opt/mapleroot-dev/bot-life-160/deploys/20261024T195826Z`, jar `b42e3988adb01de4`, contains
`autoqa-result.json` and `test-status.txt`. The pre-merge load receipt contains `bot-life-load.json` and raw samples.

The final small run used 40 roster / 400 total caps. It was stopped at **1:03:17 PM PT (20:03:17 UTC)**:
388 bots saved, 0 failed/missed writes, no connected clients; bot writes took 1.584 s. The unit is inactive.
No live operation, live DB read, shared TEST deployment/restart, desktop change or Discord post was made.

**Proposed rc1 next step:** a short owner in-game pass on a temporarily started small isolated copy: restock round
trip and purchases, friend help/PQ readiness, conversation/trade holds, actual `!botwhy` display, town/FM movement
and Henesys levels. Keep the approved shared actor; adjust the provisional mix only after that review. Real-client
appearance/pacing and real model voice remain unverified. Fishing, medals and dungeon content remain unavailable.
Before another large feature step, sync any newer 1.5.1 integration work and repeat the relevant checks.

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

The earlier Codex bug-pass receipt was **1.5.1 integration commit `c2b1a7974`** (NPC spacing, synthetic login, reply routing and
guild-chat QA fixes), installed on 2026-10-24 at **5:57 AM PT (12:57 UTC)**. Its version banner still says 1.5.0;
this is a test integration build, not a live release. Source: `release-1.5.1` in `/root/work/SlumsMS-npc-space`.
Later shared TEST was observed at `1204367b7`, running since 12:40:06 PM PT (19:40:06 UTC), from the parallel
bug pass. This feature task did not restart or deploy to it. The forward merge above is the tested integration
branch snapshot `1a3155d2e`; a TEST work branch can be ahead of that snapshot. Live was not changed.

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
