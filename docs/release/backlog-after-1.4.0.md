# Backlog after 1.4.0 (owner QA and requests, 2026-10-21)

## For 1.4.1
- **Coloring Prism (held back from 1.4.0).** QA marked Issue on dye cash weapon, effects layer, hair/eyes, colours
  staying, others seeing them; Fail on reset ("don't see a reset button"). Get the owner's details, fix, re-test.
- **DONE (branch `botfixes-1.4.1`) - Bots stuck on top of ladders.** Owner: "quite funny, I remember doing this as a
  kid". Causes found with a headless lab on real maps (`RopeTopLab`):
  - a move whose target was the ground beside a rope head counted as *arrived* while the bot was still on the rope
    (within 30 px), so the bot was left hanging at the top until its next errand (33 of ~550 rope-head trips in 3 min;
    0 after the fix);
  - where the step-off foothold sits just below the rope head, the climb waypoint aimed at the landing (dy = 0), so the
    bot hung one pixel short of the top for good - and grind's ClimbRecovery left it alone because it had a nav edge;
  - the grounded stuck detector skips climbing bots, so nothing ever unhung them.
  Fixed: no arrival while hanging short of a ground target; the top step-off aims above the head; a rope-stall
  watchdog (4 s without vertical progress while going somewhere: step off at the head, else jump off toward the goal,
  logged as ROPE_HUNG). Kept as a rare fumble: about 3% of climbs pause 0.6-1.8 s at the top (`bot.flaws.topPause`,
  ticket 33 rules: observed maps only, never in events/following/rests). Tests: `RopeTopStallTest` (real map
  107000400), `NavMovementFlawsTest`.
- **4th-job bot attacks** (ticket 32, branch `bot-4th-job-skills` / client `bot-attack-diag`): AFK check with
  `!botshowcase 4th 10` + attackdiag log, then a day of watching bot EXP pace.
- **DONE (branch `botfixes-1.4.1`) - Channel load bars count bots in full.** ~1,000 bots after a restart filled
  every bar (CHANNEL_LOAD 100 x 8 channels), so the world looked full. Quick fix on live 2026-10-21: `CHANNEL_LOAD: 300`.
  Fixed: `Channel.getChannelCapacity` now counts a bot as 1/4 of a player (`-Dslumsms.channelLoad.botWeight=0.25`,
  clamped to 0..1, bad values fall back to 0.25), real players in full, bar capped at full (`ChannelLoadBarTest`).
  **Deploy note: when 1.4.1 goes live, set live's `CHANNEL_LOAD` back to `100` in config.yaml.** Login refusals and
  the high-population message still count real players only (getConnectedPlayerCount), unchanged.
- **Cash Shop slot expansion items have no icons** (9111000-9114000): add icons via Custom.wz.
- **Newer pet equips** (355 hidden in 1.4.0): the client's GetEquipItem null-reads drawing them. Find what their WZ
  needs (or keep them hidden).
- **Test notes page:** give set-bonus tests ready item commands (`!item` ids for one full set).
- **DONE (branch `botfixes-1.4.1`) - GM hide:** while hidden, bots still noticed the GM and monsters stayed frozen.
  - Bots: nothing bot-side checked `isHidden()`. Now a GM in /hide is skipped by the bots' "players near me" scan
    (greetings, stop-and-look attention, dialogue: `BotLogic.getRealPlayersInRange`, `waitForPlayerInRange`), public
    chat never reaches the bots (replies, buff asks, party asks, "follow me": `GeneralChatHandler`), no MAP_ENTERED
    (map-entry greetings, GameZoneHost welcome) or LEVEL_UP (congrats) events, and no nav/heat/portal learning from
    the hidden GM (so bots don't gather at its warm spots). Helper: `BotHelpers.isVisibleRealPlayer`. Kept on purpose:
    LOD still counts a hidden GM as an observer, so bots on its map move and fight properly while it watches;
    whispers, party invites and the bot menu the GM starts itself still work.
  - Mobs: no player was allowed to control a mob except visible non-bots, so with only a hidden GM (and bots) on the
    map nobody moved them. Now a hidden GM is the last-resort, passive (no aggro) controller; any visible player
    takes the mobs over as soon as it is there (`Monster.pickControllerCandidate` / `keepsCurrentController`).
    Hiding mid-fight hands control to a visible player first, as before.
  - Tests: `BotHiddenGmTest`, `MonsterControllerHiddenGmTest`.
- **Release tools:** a check that a stray copy like the 1.4.0 `/files` incident can't recur (q() fixed in 40479d0c6).

## Later (social systems, owner's list)
- **Friends with bots:** add bots as buddies, whisper/talk, meet up, invite to party.
- **Marriages.**
- **Guilds** (bots in guilds, guild chat, players joining bot guilds).
- **Family system.**

## Next release: compare against stock Cosmic (owner, 2026-10-21)
The MapleRoot repack shipped ScrollHandler with its body removed (scrolling silently did nothing on live until 1.4.0).
Diff every class against upstream Cosmic (`/root/workspace/cosmicms`) for other stripped or hollowed code: bodies that
only `return`, files much shorter than Cosmic's (first pass found only ScrollHandler; EnterCashShopHandler and
EnterMTSHandler are deliberate rewrites), missing handlers/scripts, empty script functions. Sort each into
deliberate (MapleRoot/SlumsMS feature) vs broken, and restore the broken ones with tests.

## From the owner's 1.4.1 test pass (2026-10-21)
- **Client: ropes drawn with a faint rectangle border** (screenshot: Henesys hunting ground, ropes on two levels).
  Bisect first: does the live 1.4.0 client show it too? If only 1.4.1 -> tonight's client changes; if both -> weather /
  lamp canvas layers, resolution scaling, or Parsec. Client lane.
- **Guilds (gd-1/gd-2):** owner wants guilds reworked together with buddies and marriage, "more robust and
  integrated" - plan as part of the social systems, not a one-off fix.
- **FM storage:** both FMs already have a storage keeper (Gerrard 2060008 in the MapleRoot FM, Scrooge 9030100 in
  910000000). Rosen (2110000) is Magatia + Adobis's Mission I; restored in 1.4.1 (his script was missing).
