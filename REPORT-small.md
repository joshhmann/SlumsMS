# 1.6.1 notes vs code: over-promises (branch 161-small, head of release-1.6.1 at ba0df462b plus this branch's fixes)

Checked line by line against the code (read-only audit, nothing run in a game). Each entry: the claim, what the code
does, the work to make the claim true. docs/release/1.6.1-notes.md was NOT edited. Items marked (fixed here) are
closed by the commit on this branch.

## A. Real over-promises (work needed)

1. Regulars rebirth: "they earn the key item from boss drops".
   Code: no bot kills a boss for it. `BotRebirth.gather()` (src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/BotRebirth.java ~255) is a simulated "offscreen boss run": at most once per 6 h (`slums.botRebirth.gatherHours`) a 35% roll (`gatherChance`) drops one of the two boss items straight into the bag (`BotHoldings.grantItem`), then `craft()` makes the key. A weekly "bothers" roll (5-90%, Bartle-weighted) gates it. Only `TrainingBot` runs the step.
   Work: make bots really fight the key bosses (expedition bots already exist, `ExpeditionBots.java`) and take the drop from the real kill, or have the simulated run go through the real boss drop table with the real boss-attempt limits.
   **Fixed on 161-rebirthkeys:** real Von Leon/Cygnus runs formed by bots, joins of players' expeditions for every key boss, off-screen Zakum/Horntail/Pink Bean runs on the players' clear limits, real HP vs real damage, drop rows and clear reward (docs/tickets/30-bot-rebirth.md, "Promise audit"). Checks t-rb-1, t-rb-2.
2. Regulars rebirth: "Regulars who reach level 200 can now be reborn" / "start again at level 1 ... keeping their gear".
   Code: true for rebirths 0-2. `BotRebirth.stage()` uses level >= 200, `rebirthHere()` uses level == getMaxClassLevel(); gear is kept in the bag but `BotGear.afterProgress` cannot wear level-gated gear at level 1.
   Work: none beyond making a level-1 bot wear what it can; or accept.
   **Fixed on 161-rebirthkeys:** `BotGear.afterRebirth` takes off what a level 1 cannot wear, wears the best it can, keeps the rest; reviews on level-up put it back on.
3. Family: "earn reputation when your juniors hunt and level" (bot juniors).
   Code: real kills (`Monster.java:1030`) and normal level-ups (`Character.java:7758`) pay the senior. Bots levelling off-screen (`Character.applyOffscreenExp` ~3892) never call levelUp and never kill a real mob, so a bot junior gives nothing while it trains. Bot juniors are also capped at 300 rep/day (`FamilyEntry.java:317`).
   Work: call the family rep hook from the off-screen level-up path (and from simulated kills), keep or document the cap.
4. Family: "Family Bonding ... for you and your juniors' line, with 6 of them online".
   Code: bot juniors count toward the 6 (`FamilyUseHandler.applyBuff` ~165) but are never buffed; a player with 6 bot juniors gets the 2x alone.
   Work: either buff bots too (cheap: a rate flag on the bot) or do not count them.
5. Family: bots "say yes to people they know, have partied or chatted with".
   Code: 80% roll (`slums.family.acceptChance`), 10-60 s delay, never when wary or already in a family.
   Work: none if "usually" is acceptable; otherwise accept always.
6. Bug reports (ticket 131): "@bug now tells you your report number and attaches ...".
   Code: the live switch `-Dsolomapling.reportsFile=...` is commented out in docs/release/1.6.1.conf UNIT_EDITS. Without it `ReportStore` (src/main/java/server/reports/ReportStore.java:115-120) writes `data/reports/reports.jsonl`, which the site does not read; the player still sees "Bug #B-12 sent." and the developers get nothing.
   Work: enable the line once the site report_service lock patch is live (the header of the notes already says so). Also limits 20/day per account and 60/h, 300/day server-wide are not in the notes.
7. Maple Island: "right after your first login you're asked".
   Code: `TutorialIslandMenu.shouldAsk` (soloMapling/menus/TutorialIslandMenu.java:54-58) asks only a real Beginner (job 0), level 1-9, still on map 993910000, 3 s after login. Noblesse and Legend starters are never asked; anyone who has already left Base of Roots is not.
   Work: ask the other starter jobs, and ask wherever a level 1-9 first login lands.
8. Maple Island: "Shanks sails you to Lith Harbor when you're ready".
   Code: scripts/npc/22000.js needs level 7 and 150 mesos (free with Lucas's letter 4031801), one way.
   Work: say so, or drop the cost.
9. Slums Stamps: "every Billion Coin you had (bags, storage, your shop, Duey) ... became 6 stamps".
   Code: migration 039 only touched inventoryitems types 1, 2, 6, 9. Missed: wedding gifts (type 8), MTS listings, and the Adventurer's Bag (`infinitystorage`, AdventurerBag.java:20,403). (fixed here: migration 052-stamps-gifts-mts, with rollback and an IT test.)
   Work: done in 052; 052 must run on live after 039 (listed in 1.6.1.conf).
10. Stamps: "the High-Five Stamps 40".
    Code: rooty.js:972 "QoL items" has 5590000 and 5590001 at 40 each; 5590001 is the Hyper Teleport Rock (HyperTPHandler.java:20). One High-Five Stamp and one Hyper Rock.
    Work: name the second item, or confirm both names against the client WZ.
11. Stamps: "1 stamp every day you claim ... 48 over the 28-day cycle".
    Code: arithmetic right. Not in the notes: 48 h gap restarts the cycle at day 1 (DailyCheckinStreak.normalized); check-in needs level 10 (DAILY_CHECKIN_MIN_LEVEL); accounts with a GM character or auto-QA earn 0.
    Work: add the conditions or relax them.
12. Stamps: "@points shows stamps instead of vote points".
    Code: ReadPointsCommand prints "RewardPoints: N | Slums Stamps: M" (reward points stay).
    Work: trivial, drop reward points or reword.
13. Slums-chan: "She welcomes you on your first login".
    Code: she frames the first-login guide ("Hi, I'm Slums-chan!") but there is no welcome from her at login.
    Work: a first-login greeting line from her, or accept.
14. Training after 120: "pays roughly twice what the Temple of Time does".
    Code: the model compares to the OLD Temple of Time (EXP 7,060); the same release gives it +40%. Lower Grandis ~34M/h and Lion King's ~34M vs buffed Temple 24-26M: about 1.3-1.4x (docs/economy/bb-drops/REVIEW.md says 1.4x). Rose Garden 42M, Future ~100M, upper Grandis/Arcane 153-156M. Modelled for 1,000-1,500 kills/h, not measured.
    Work: raise lower-Grandis EXP ~1.45x, or measure.
15. Training after 120: "deeper parts (Heliseum, the Fox Tree, Cernium, rougher Savage Terminal) still need 3 rebirths".
    Code: open at 0 rebirths (src/main/resources/soloMapling/lower-grandis-maps.txt): 410000002 Fox Tree, 400020300 Heliseum Hideout, 401000002 Heliseum Dimensional Door, 402000400-402000402 Back Alley/Black Market/Scrapyard. Closed: Heliseum HQ and beyond, Fox Tree Top, Seedy Scrapyard, Sketchy Back-Alley, Dingy Brawl club.
    Work: move those maps out of the open list.
16. Training after 120: "Reach them with ... the Gate of Time in the Temple of Time".
    Code: scripts/portal/270000000.10.js goes to the Future (271000000) only.
    Work: add a Grandis destination to the gate, or say Future only.
17. Training after 120: "Leafre and Minar Forest, the Temple of Time and Ulu City give 40% more EXP".
    Code: only 25 listed mobs of level 95+ get x1.4 (mob-stat-overrides.tsv); lower-level mobs in those maps unchanged; four Corrupted mobs get x4.66.
    Work: add rows for every mob on those maps.
18. Training after 120: "They now fight like level 117-134 monsters".
    Code: LKC mobs are 115-127, Rose Garden 128-134. Lion King's Castle still needs 1 rebirth (in_lkc.js, 211060000.4.js, HyperTPHandler), towers past Wall 1 need key items 4032832-4 or quests 3165-3167.
    Work: change gates (drop the LKC rebirth gate) or accept.
19. Training after 120: "Bots of level 120 and up can now train in the Future and lower Grandis".
    Code: gates admit them, but the leveling path only picks lower Grandis at 120-160 (7 entries) and the Future's weakest path maps have level 162 mobs with maxAbove 15 (LevelingPathChooser.java:160): bots pick the Future from about level 147. TrainingRegions.yaml has no Future/Grandis region, so the older fallback (TrainingMapFinder.java:107) rejects both.
    Work: add Future and lower-Grandis regions and 124-146 Future path entries.
20. Bots talk: "Friends hang out together: when one heads to a town, its friends follow".
    Code: only the crew leader's HANG_OUT/SHOW_OFF trip pulls others (BotCrews.java; TrainingBot.java:1002-1005); the leader is the lowest-id member online; followers nudged 2-6 s apart, max 4 move; crew max 6.
    Work: make any member's trip trigger the nudge.
21. Bots talk: "greet the bots they know when they run into each other".
    Code: only a bot that just arrived greets, one known bot within 600x250 px, 20-minute pair cooldown, 20 greets/min world cap (BotTalk.greetOnArrival).
    Work: let the bot already standing there greet too.
22. Bots talk (header): t-bt-1 FAILED on tick time (25-29 ms vs 20 ms budget) and once only 2 of 3 in a group spoke.
    Work: performance pass on the talk tick; group-turn fix.
23. Adventurer's Bag: "only takes items it can give back exactly as they were".
    Code: `AdventurerBag.isPlain` ignores ACCOUNT_SHARING: an account-shared stack on an item not normally account-bound comes back as a plain stack. All other cases hold.
    Work: add the flag to isPlain.
24. Lazy Penguins / fixes / NX note: verified true (46/048/049/051 migrations listed; PacketCreator no-expiry fix; RoamStrategy walk-up fix; NX hidden from @help and the "not open" message). Host WZ names/sprites for the penguins not verifiable here.
25. Family perk bullet "(a whisper) now and then one asks": narrow gates (3+ chat lines, level rule, <2 juniors, 10% roll, 1/day per player, 3/world/hour). Fair, not an over-promise.

## B. Items the owner named
- Medal "yellow line": FIXED on this branch. `GameHands.tell` (soloMapling/achievements/GameHands.java:41) now uses `chr.yellowMessage` (a real yellow tip), not type 6 (light blue). The notes claim is true now.
- Regulars "earning" rebirth keys: see 1 (fixed on 161-rebirthkeys).
- "Regulars understand chat better": not in docs/release/1.6.1-notes.md, roadmap/1.6.1.md or HANDOFF at this commit (grep for "understand"); nothing to verify against. If it is in the Discord/site text, the work is the chat-model split (BOT_DECIDE_URL, 1.6.1.conf SERVER_SET) and that is a config change, not code.
- Edit UI login tip: not in the 1.6.1 notes file. State: `GuideMenu.onLoginUiTip` (GuideMenu.java:158) shows it once, only to a real player whose client build stamp is >= UI_TIP_MIN_BUILD (10600), as `dropMessage(5, ...)` (notice colour, not yellow). It needed `ClientStampCarry` to work on the channel (before it the build read 0 and it never showed); untested on a real client.
- Adventurer's Bag claims: all verified except the ACCOUNT_SHARING caveat (23). Withdraw-all-ores, no-space and open-dialog loot paths are in the code.

## Not verified
Client rendering, host WZ data, any runtime behaviour. Live switches are as in docs/release/1.6.1.conf.
