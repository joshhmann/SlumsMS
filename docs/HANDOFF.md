# HANDOFF: SlumsMS state after the 1.5.0 release (2026-10-24)

Read `AGENTS.md` first (the rules). This file is the current state, what's in flight, and what to do next. Written by
the coordinator (Claude) before handing work to Codex/DeepSeek until about Wed 2026-10-28. Update it when you finish
something.

Times: give the owner Pacific next to UTC (PDT = UTC-7).

**Historical work split (superseded by the current direction below):** this bug-fix pass is finished. The owner will hand continuing **1.5.1 bug
squashing to DeepSeek**; use `release-1.5.1`, the current TEST receipt below and section 3's remaining leads.
**Codex is moving to 1.6.0 feature work**, starting with Ticket 60's implementation plan on `160-bot-brain`
(`5ca02907f`, `/root/work/SlumsMS-bot-brain-160`). Runtime implementation has not started. The roadmap puts the bot
brain first. Fishing, medals, the daily dungeon and chaos bosses still need more detailed tickets and their listed
owner decisions. Ticket 61's "spec/to be built" header is stale: the client MCP already exists per section 5.
Keep the shared `slums-persistence-test` build for the bug pass; feature work must coordinate any test checkpoint
or use a separately isolated test instance within the host caps. Do not merge 1.6.0 features into `release-1.5.1`.

**Current owner direction:** 1.6.0 is paused. **DeepSeek owns tickets 68 and 69 and remains active.**
Codex keeps the wedding QA correction, extra salon coverage and bank-question fix on separate branches.
The owner authorized finishing this pass and merging its verified changes, then waiting for DeepSeek and Gemini.
Their worktrees and tickets 68/69 remain unchanged.

Codex candidate: `151-salon-restoration`, `/root/work/SlumsMS-151-salons`, source `fbc368f97`, contains
upstream `5b628dda9`, the wedding QA correction and extra salon coverage, and bank-question fix `4a244a37e`.
Full offline `mvn -q -o test`: **4,592 tests, 0 failures, 0 errors, 38 skipped**, 417 classes; package passes.
The core salon fix was already merged by Claude as `1fe365733`; our additional name/filter tests extend its
coverage. Seven corresponding styles also exist in the RC client's Face/Hair data. Read-only QA `sl-1` does
not change appearance or spend coupons and does not replace ticket 69's vb-1.

Wedding QA fix `f73bb543d` is on `151-qa-wedding-completion` and included in this candidate. The previous rm-2
log recorded a random runaway before its marriage/divorce assertions; the staged wedding now skips only that
random roll. A regression fails before and passes after; ordinary/forced runaway coverage remains green.

The earlier `3a6fa1abe` selected TEST run had **7 PASS / 1 FAIL**: cv-7 answered “what u doing” with “oh nice”.
Bank-question fix `4a244a37e` gives a factual “chatting with u rn” in that script fallback. Its conversation-flow
regression fails before and passes after. Final deployment and selected results are recorded below.

At task start TEST was `9d5a16def`, already including clearPQ and endmeso despite older notes below. Its prior
63-check rerun had 58 PASS / 5 FAIL. Actual mount and stock PQ completion remain unverified. The gu-8 failure
log says “nobody to kick”; its fixture stages a RIVAL leader while the one-leader kick rule requires NEMESIS.
That is a triage lead, not a fix. Footing/overlap and other health flags remain open.

## 1. What is live

**SlumsMS 1.5.1, live since 2026-10-25 07:38 UTC (12:38 AM PDT).** Branch `release-1.5.1` @ `6443043f9`, jar
`76fa91f3…`, downtime 31.7 s, smoke PASS, 0 errors (1.5.0 had ~34 silent NullPointerExceptions a minute; 1.5.1 has 0).
Receipt and backups: `/opt/mapleroot-deployments/v1.5.1/` on 164. Rollback: `bash
/opt/mapleroot-deployments/v1.5.1/rollback.sh` (~30 s, back to 1.5.0; rehearsed). Client 1.5.0 (build 10500),
Tauri launcher 1.5.2 and the Electron hand-over are unchanged. Patch notes: https://theslumsms.asslorde.com/patch-notes/1.5.1/
(site branch), Cody posted. Owner QA page: https://claude.ai/artifact/F3UNsKnpg1cmndLop3dVhZ.

Live config now: ENABLE_PIC true, USE_SUPPLY_RATE_COUPONS true (Cash Shop sells only 2x EXP 7 days / 3x EXP 1 day),
roster growth back to 0.5 / 300, 5 channels, 3,000 bots at peak, map cap 50, -Xms4G -Xmx6G.

What 1.5.1 fixed (details in the patch notes and tickets 68/75-80): autologin password bypass removed; salons and
face/eye coupons; PQ/event clears (clearPQ); 179 maps loading (mob exp default); quest meso prices; PIC/delete; venom,
1st-job HP, Silver Hawk, stock summon durations (`stock-summon-skills.yaml`); Spiegelmann, Mr. Do, Maple Administrator;
PQ reward amounts; chat gate on the real clock; chat hold for every bot; KS-war bots arrive by portal; coupons survive
death (re-applied on respawn); tiger banners clear after 10 s; @coupons; !maxskill; Amos guard; mount room 3 minutes.

## 1b. Next: 1.5.2 (bug-squash arc, ship when ready; owner 2026-10-25)

1.5.x is "make v83 work": jobs/skills, quests, NPCs, shops, maps, PQs/bosses, swept systematically. No release
schedule: when a batch is fixed and QA'd, release (live steps on the owner's go). 1.6.0 (bot brain) waits.
1.5.2 so far:
- `152-nutnnut-stability` (pushed, ticket 80): lock-order deadlocks, thread safety, disposed characters, warp latch,
  quest-row bloat, migration `023-character-lookup-indexes`, shutdown autosave, Warrior ids. Needs a 4 h+ 3,000-bot
  soak on TEST first (plan in the ticket 80 hand-back: jstack every 15 min, no deadlock/CME/new ERRORs, queststatus).
  Add 023 to `docs/release/1.5.2.conf` (LEDGER_BEFORE = 1.5.1's list).
- Ticket 75 Amoria PQ for 2-6 players: **implemented on `152-sweep-maps-pqs`** (`53bea0bc7`) — eligibility 2-6 with
  no marriage and no gender rule, bots never count; stages 2/3 scale the platform count to the party (capped at 5),
  stage 4 pools the 50 trial items over the party and scales the count (`max(1, ceil(50 * party / 6))`), both stage 1
  doors accept anyone; rewards unchanged; `AmoriaPqRulesTest` (14 cases, 6 of them fail on Cosmic's scripts). Left to
  confirm: a 2-player same-gender run on TEST with a real client (QA scenario `pq-1` stages it). Ticket 78 Hidden
  Street site still open.
- Map/PQ sweep on `152-sweep-maps-pqs`: new `tools/ops/map-integrity` (portals, placed mobs/NPCs/reactors, return
  maps, map names) and `tools/ops/pq-audit` (every PQ's maps and scripts diffed against Cosmic). Result: no v83 map
  is broken relative to Cosmic, but the server's `wz/` is 9,528 NPCs and 3,456 mobs short of what the client's patch
  layers ship (all of it Big-Bang-era maps, i.e. where the rebirth warp sends players) — needs a re-extraction and a
  test deploy, owner's call.
- Ticket 69 vb-* checks (Gemini) need the 9 reworks from the review before merging.
- Leftovers: Ninja Ambush DoT, Cygnus AP, free-teleport HyperTP item, GM "za warudo" skill, Pink Bean double clear,
  Papulatus exit NPC, MapleTV scroll generator, items with no slotMax stacking to 32000, Von Leon map potions, Duey
  (recommend on), GM-1 trade/drop (recommend back to 4), MapleRoot's summon damage buffs (owner leans to longer
  summon durations later: edit `stock-summon-skills.yaml`), Flame Thrower venom flag.
- Parked: ticket 76 Nett's Pyramid (Cosmic PR #221), ticket 77 Omok bots (part 1 in 1.5.x), ticket 79 Basil Market (1.6.x).

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
- **Technical source review:** tickets [62–65](tickets/README.md) target 1.5.1: active-player shutdown saves (P1),
  unsaved bot debits at the shutdown deadline (P1), Bera wealth-scroll bonus (P2), and save-failure reporting (P2).
  Findings are source-reviewed against `06a724dfc`; runtime reproduction and fixes are still pending.
- **A bot you're talking to walks away** mid-conversation (personal space or its wander habit pulls it away). A
  conversation partner should stay put ~30-60 s. Source audit: `BotConversation` already keeps a 60 s session,
  but the wanderer and fidget paths do not use it as a movement hold; personal space only recognises the pair.
  **Fixed on `151-convo-hold`:** `BotConversation.holdMs/isHoldingChat/heldByChat` holds a bot 45 s from the last
  exchange (a player's line, its reply, the typing time); the wander stops an in-flight stroll and the dwell runs
  to the end of the hold, and loiter, fidget, `SocialBot` relocate, `TownWandererBot`/`HenesysBot` roaming, the
  training bot's town dwells and `PersonalSpace.keepNow` consult it. Yields to a shutdown quiesce, the logout
  curve, an event instance and a drama scene; grinding combat is not held. QA `cv-9` / auto-QA `t-cv-8`.
  **Merged into `release-1.5.1` as `f13e16375`; `1c1e5dd9c` on TEST. `t-cv-8` PASS: the bot held 113-115 px
  across five samples over 33 s.** The real-client feel of the hold still needs the owner (`t-cv-9`).
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
- **KS war helpers grind on their own platform** (ticket 66, t-rv-2). **FIXED in `1fef2540e`, merged as
  `30648a5d2`:** `DramaKit.walkTo` walks a called helper in instead of teleporting it, bounded at 4.5 s (a helper
  with no walkable ledge stands and fights rather than popping in), and a couple chair now walks on the same map.
  It replaced the teleport `DramaKit.bringTo`, which only moved a helper on a different map: on a stacked map
  (104040000) the helpers ended up 300-500 px above the player and `ksPick`'s 350x90 px reach never touched the
  player's mobs. `t-rv-2` PASS with 3 of 3 called bots taking mobs on the merged build
  (`logs/qa/rv-2-20261024T211000Z.log`); the check is still flaky (it failed 1 of 3 helpers twice that evening on
  the pre-fix build and passed 3 of 3 four times after), so a single PASS is not yet trusted.
- **Full auto-QA baseline on the fixed build (2026-10-24 17:43-19:20 UTC, `all`, 75 checks): 56 PASS / 7 FAIL /
  12 SKIP** (was 54/9/11, then 57/7, then 60/4). Green again: rv-2, rv-4, pb-1, rm-1, rm-4, bb-2, ga-1. Also fixed
  since: cv-4 (the reply log printed each fragment's remaining wait instead of its offset from the player's line, so a
  split reply that took 1,005 ms read "+150 ms" and failed "planned faster than 900 ms"). Failures in that run (the fixed ones say so):
  gw-1 (the enemy guild killed nothing: `ZakumSlayerz killed nothing`, turf staging/time, not the harness), gw-4 +
  gu-5 (`at least 1 x BOT_GUILD BOSS RUN` / SKIP "a boss run is already on (SlumLords)"), **both FIXED in
  `b83aab5e6`** - a GM/`!qa` start now stops the run on first, bot runs still never preempt. `t-gw-4` PASS
  (`logs/qa/gw-4-20261024T210035Z.log`: `BOSS RUN Legendz -> papulatus ch1`, `BOSS RUN Legendz + MoonLiteX stopped
  by AutoQaOne`) and `t-gu-5` PASS in 198 s with the run cleared (`logs/qa/gu-5-20261024T211206Z.log`), cv-5 (thread routing fixture: `{thread}` is
  filled and the route still picks the staged speaker by reply words), cv-7 (judge BAD on a bank line - content
  quality), cb-2 (13 of 52 POSITION samples had a bot `INSIDE` a mob's body, dx 11-34: melee bots do clip into the
  mobs they hit - a real bot behaviour wart, not a metric artefact, so the ratio stays; **FIXED in `1204367b7`**,
  `t-cb-2` PASS at 5 of 52, 9.6% under the 10% bar) and rv-2 half the time (ticket 66).
- **Remaining auto-QA leads:** af-1/af-2 (flaky - both passed several runs on this build, fail when the staged bot
  greets through `CONVO SAID` instead of `AFFINITY ACT`), cv-2 (chat-content judgements); cv-5 and cv-7 are
  fixed below (cv-5's routing snapshot picked the staged speaker by reply words while the thread beside the player,
  a different PQ thread, was never scored). Original baseline was 54 PASS / 9 FAIL / 11 SKIP; the mixed report after the first pass
  was 55/9, then 57/7, then 60/4.
- **On the 2026-10-24 evening build (`1c1e5dd9c`) only five checks ran, all PASS:** `t-cv-8` (113-115 px over five
  samples, 33 s), `t-rv-2` (3 of 3 helpers on mobs), `t-cb-2` (9.6% inside a mob), `t-gw-4` and `t-gu-5` (198 s,
  run cleared). **No full auto-QA run of all 75 checks was made on this build.** Still open at that point: gw-1
  (the enemy guild killed nothing), cv-5 (thread routing picks the staged speaker), cv-7 (judge BAD on a bank line),
  rv-2's flakiness (ticket 66) and the overlap/foothold health flags. cv-5, cv-7 and cv-4 are fixed since; see the
  fixes bullet below.
- **The auto-QA failures from the 2026-10-24 evening run are fixed and merged into `release-1.5.1`; the TEST receipt
  for a build carrying all of them is still pending.** Branch and commit for each:
  - **cv-5, several threads per map (branch `151-thread-routing`, `6752cd72b` + `8372f9024`).** `6752cd72b` credits a
    live thread when the player names its subject instead of losing the line to reply-word matching. `8372f9024` is
    the shadowing itself: `BotThreads` kept one thread per channel:map, so on TEST a thread 4,964 px away held the
    slot while the Kerning PQ thread 129 px from the player was never scored. It keeps up to three live threads per
    map now (`MAX_LIVE_PER_MAP`, oldest gives way, spacing kept), ranks them by subject fit, then distance, then last
    line, and drops a thread with no speaker inside the hearing bubble (400 px either side, 150 px up or down) before
    ranking. `ChatRoute.inBubble` is the one place that rule lives. Both `ChatRouteTest`/`BotThreadsTest` cases failed
    before (`expected [10001, 10004]`, and an unheard thread returned where null was expected).
  - **cv-7, bank content (branch `151-thread-routing`, `4091383db`).** An invitation to grind or hunt is triaged as an
    invitation, so it answers `come_yes`/`come_no` instead of small talk, and the generic `talk`/`talk_q` key only
    accepts acknowledgements (`ConvoLines.ACK`, at most four words), never a claim about an unrelated topic.
  - **Scripted conversation honesty (branch `151-script-saydo`, `bdc1320c5`).** A script whose claim line the speaker
    cannot back is refused whole rather than cut, and logged as `SCRIPT SKIP <script>: <bot> would not back "<line>"
    (<claims>)` on the CONVO topic. Of the pack's 267 scripts (1,752 lines) only 14 lines carry a claim and only
    `item_price_haggle_2` names an item, so this refuses rare cases only.
  - **cv-4 say-do scope (branch `151-qa-harness`, `e80f71cb1`).** The check judges only the staged bots' claims, so a
    stray line from another bot no longer fails it.
  - **Mount quest price (branch `151-endmeso`, `9fa7d63f2`).** Quest 6007 declares its completion price as `endmeso`,
    which had no requirement mapping, so the price was never checked: a player holding 3M walked up to Tatamo, paid
    3M and still got the Dragon's Perfume Bottle. `endmeso` now maps to a requirement and completion is refused with
    "You don't have enough mesos to complete this quest." without it.
  - **QA fast lane (branch `151-qa-speed`, `8c5ad3554`, `440c966d4`, `c3ea6c1a8`, `6593ee722`).** A run-only time scale
    (`-Dslums.qa.timeScale`), rerun-only-what-failed, passed ids carried across reruns, and staged waits that end on
    the event they assert.
  - **Nav warm-up quiesce (branch `151-nav-quiesce`, `3652a3910`).** Shutdown stops the nav-graph warm-up, so the
    "footholds is null" noise while maps unload is gone.
  Each fix branch ran the full offline Maven suite green (0 failures, 0 errors, 38 skipped). **TEST receipt for the
  merged build is pending:** the marker sits at the end of `docs/release/1.5.1-qa.md`
  (`<!-- RECEIPT-PENDING: fill build hash, deploy stamp and check verdicts -->`). Player-facing wording is on
  `docs/site/releases/1.5.1.md` (still a draft).
- **Two QA leads still open, one branch each in flight (not pushed as of these notes).** `151-overlap`: bots placed on
  the exact same pixel, the health overlap flag counting 502 pairs at dx=dy=0. `151-flat-platforms`: filler spawns at
  `baseY` on sloped WZ ground, foothold health at 21 floating / 1 no-ground / 2 sunk of 2,502.
- **Partial-nightly lead:** af-2 also failed on the old build during the interrupted 3:00 AM PT (10:00 UTC) nightly (missing
  taunt/KS action). It needs reproduction on the latest build; it was not changed as part of these fixes.
  The account-cache exception found in the older logs is fixed (section 2 item 6).
- **Roster growth back to normal, in the 1.5.1 deploy** (owner OK'd 2026-10-24; held so tonight's 3k peak has enough
  bots): once the roster is past ~9,000 (`journalctl -u slumsms | grep "roster holds\|Roster:"`), set
  BOT_ROSTER_VETERAN_FILL_BELOW 0.5 and BOT_ROSTER_VETERANS_PER_DAY 300 in live `config.yaml` during the 1.5.1
  stop-backup/apply-start window, so it costs no extra restart.
- Watch live memory for a few days (the 3k soak crept ~80 MB/h in old-gen).
- Merge `release-1.5.0` back where needed: `test-1.5.0-rc6` is history; new work branches from `release-1.5.0`.

- **Rate coupons on sale (owner, 2026-10-24 22:51 UTC / 3:51 PM PDT):** live `config.yaml` now has
  `USE_SUPPLY_RATE_COUPONS: true` (backup `/root/config.yaml.before-rate-coupons-20261024T225139Z` on 164); the owner
  turned it on in game with `!supplyratecoupon yes`. The config makes it survive restarts. Keep it `true` in every
  deploy; prices and periods (`cashshop/catalog.tsv`, 5211xxx/5360xxx) are unchanged and the owner's call.
- **Monitor fix installed on live (2026-10-24):** `/usr/local/bin/slumsms-monitor` reads the 1.5.0 director start
  line (commit `402851969`); backup `/root/slumsms-monitor.before-director-fix`. The repo copy also has the newer
  `maintenance end --client` feature, not installed yet; install the whole file with the 1.5.1 release.

- **PQ/event clears crash (fixed on `151-clearpq-expedition`, merged into release-1.5.1 and present on TEST; no Live deploy by this pass):**
  `EventInstanceManager.clearPQ` wrote the boss log assuming an expedition, so every non-expedition instance that
  finishes through `clearPQ` (the Kenta mount room, KPQ/LPQ/other PQs, Cathedral/Chapel weddings: 57 scripts) threw a
  NullPointerException and the player was never sent out or rewarded. Seen on live 2026-10-24 (npc 9060000, a player
  doing quest 6002). Owner: ship with 1.5.1, no hotfix. **Next agent:** deploy release-1.5.1 to TEST and check the
  mount room end to end (quest 6002 hand-in at Kenta, 6007 Tatamo 20m, 6003 perfume, catch in map 922200000 where
  the hog must be under 40% HP) plus one PQ clear (e.g. KPQ), and add the mount path to `docs/release/1.5.1-qa.md`.

- **PIC on (owner, 2026-10-25):** live `config.yaml` now has `ENABLE_PIC: true` (Cosmic's default; MapleRoot had turned
  it off, which made deleting characters impossible). Takes effect at the 1.5.1 restart: players are asked to create
  a PIC at their next login. Backup `/root/config.yaml.before-pic-*` on 164. Mention it in the 1.5.1 patch notes.
- **Salons / face and eye coupons said "no options available" (fixed on release-1.5.1, `1fe365733`):** MapleRoot
  hard-coded faces as 50000-55000 and hair as 30000-35000 in the name lookup; MapleRoot data has faces 2xxxx+5xxxx and
  hair 3xxxx/4xxxx/6xxxx. All 368 salon style ids exist; every sold 5150-5153 coupon has an NPC; skin NPCs don't filter.
  In-game check for 1.5.1: a face coupon at Henesys plastic surgery and a lens coupon. Low priority: the Cotton
  Stylist (`scripts/npc/STYLIST.js`, GM/hidden maps only) calls setHair on its special-face pages. Ticket idea for 1.5.1/1.6.0: diff MapleRoot's changes against
  stock Cosmic (handlers, config, scripts) for regressions like clearPQ and PIC; old worktree `SlumsMS-cosmicdiff`.

- **Next two tasks for the bug-fix agent, after the current 1.5.1 items (owner, 2026-10-25):** ticket 68 (audit
  MapleRoot's changes to Cosmic for broken stock features) then ticket 69 (auto-QA "v83 basics" checks).

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

## Codex coordination note

Owner clarified that DeepSeek is actively working tickets 68 and 69. Changes drafted for those tickets and the
Cosmic classifier/inventory were removed from the tracked diff and preserved only under ignored
`dist/audit-68/` in `/root/work/SlumsMS-151-salons`. They are not a separate competing implementation and are
not published as completed audit work. DeepSeek's files/worktrees were never changed.

The earlier `3a6fa1abe` TEST run completed **7 PASS / 1 FAIL / 0 SKIP** of eight selected checks:
bg-1, rm-2, cv-4, cv-5, sl-1, vg-1 and ch-1 passed; cv-7 failed reply quality. Its log
`cv-7-20261025T024552Z.log` shows “what u doing” answered by “oh nice”. The invitation reply itself passed;
this is a remaining activity-question fallback bug, not evidence that all cv-7 behavior is fixed.
Receipt `/opt/mapleroot-dev/persistence-test/deploys/20261025T024019Z` holds the saved result, status and failing log.
Zero ERROR/Exception lines since this boot; overall health remains FAIL (footing, overlap and an invalid-map flag).
Those health leads were not fixed here. No real client, mount capture or full stock PQ completion was tested.

The narrow fix is `4a244a37e` on pushed branch `151-bank-question`, own worktree
`/root/work/SlumsMS-151-bank-question`: in script fallback only, direct activity questions get “chatting with u rn”.
No guessing from a roster role, new model call, action, rate or reward. The real conversation-flow regression
fails before and passes after; focused tests pass. Its standalone full run was superseded by the combined
candidate's full verification below. This fix is included in deployed source `fbc368f97`.

## Final combined bank-question candidate

Source `fbc368f97` contains the bank fix `4a244a37e` as well as the previously checked
wedding/salon coverage and upstream `5b628dda9`. Final full `mvn -q -o test`:
**4,592 tests, 0 failures, 0 errors, 38 skipped**, 417 classes. Log `/tmp/151-takeover-bank-combined-full.log`.
The bank-enabled conversation-flow regression failed before (expected an activity answer, got “oh nice”) and
passes after for three direct question forms. Additional positive/negative wording tests pass. Stories, past
tense, refusals and compound requests keep their previous paths. The fallback describes only the conversation
currently happening; it does not guess the bot's physical activity from a saved role. No model or rate changes.
Owner instruction is to finish and merge this pass, then wait for DeepSeek and Gemini. No further ticket work
is being started.

Final TEST receipt: `/opt/mapleroot-dev/persistence-test/deploys/20261025T031331Z`, deployed source `fbc368f97`.
Normal-timing run **8:15:25–8:17:53 PM PT Oct 24 (03:15:25–03:17:53 UTC Oct 25, 2026)**:
**6 PASS / 0 FAIL / 0 SKIP** — bg-1, rm-2, cv-7, sl-1, vg-1, ch-1. The saved `autoqa-result.json` records
this run's exact source and six results; `test-status.txt` and the three changed-area QA logs are beside it.
`cv-7-20261025T031633Z.log` records “what u doing” answered by “chatting with u rn”; its judge, script-only,
no-repeat and say-do assertions all pass. `rm-2-20261025T031527Z.log` passes marriage/divorce;
`sl-1-20261025T031742Z.log` passes seven real-data style lookups.
Zero ERROR/Exception lines since the 8:14 PM PT / 03:14 UTC boot. Health still FAIL: footing and overlap;
invalid maps and clumps are OK at this checkpoint. The saved daily mixed report has 63 PASS / 3 FAIL / 11 SKIP,
with older af-1, gu-8 and cb-2 leads, **not a fresh full-catalogue run on this build**.
No real-client salon preview/coupon use, mount capture or full stock PQ clear was performed.

Release integration: `origin/release-1.5.1` advanced to `625308df5` while the final selected TEST run completed.
Merged it cleanly as `466f82a18`, retaining the other agent's ticket-68 fixes and our wedding, salon coverage
and bank reply changes. The full suite is rerun on that combined source; the result is recorded below.
Shared TEST remains at the separately verified `fbc368f97`; the later ticket-68 merge was not redeployed by
Codex, so its new script/mount/map changes still need their own TEST verification.
After the full-suite result, push `151-salon-restoration` and fast-forward `origin/release-1.5.1` to the same
documentation commit. Leave shared TEST running; wait for DeepSeek and Gemini before further work.
No Live, Discord, private 1.6.0 server or client changes were made in this finishing pass.
