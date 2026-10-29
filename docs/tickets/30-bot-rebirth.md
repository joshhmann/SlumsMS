# 30: Bot rebirths and the 5th tier

**Area:** Real bots
**Depends on:** 06 (builds), the MapleRoot skill rework on `real-stats`
**Status:** earned path done in 1.6.1 (branch 161-botreb; see "Built" at the end). Owner decision 2026-10-25: earned
path for all bots; 2026-10-28: "Earned, slowly". Veterans and World's Engine for bots: not built.

## How MapleRoot does it (for players)

- **Rebirth** (`scripts/npc/rebirth.js`, NPC 1300009, `Character.executeRebornAs`):
  - Needs max level: 200, or 255 after 3 rebirths (`getMaxClassLevel`).
  - The character restarts at level 1 as Beginner, Noblesse or Legend.
  - EXP rate is 3× after the 1st rebirth, 2× after the 2nd, 1× after the 3rd.
  - Past jobs are kept in `characters_rebirth`; their skills stay usable (the NPC keybinds them).
- **Map gates** (`HyperTPHandler`): Lion King needs rebirth 1, Twilight Perion rebirth 2, Grandis/Arcane rebirth 3.
- **5th tier** (job x13 via the Job Master NPC 9010003):
  - Needs a 4th-job character with rebirth ≥3 and **World's Engine** (4036541).
  - World's Engine comes only from quest 31167 "Magnus the Tyrant!": level 200+, after quest 31166, kill Magnus
    (8880002), then turn in at NPC 1300009.
  - The NPC takes the item and teaches the job's 4 x13 skills.
- **Super Beginner 700 / Synth Master 710:** needs Chain Attack at 120, then the 710 advance takes World's Engine.

## The problem

A rebirth sends the bot back to level 1. Bots level in real time, so reaching 200 three times takes a long time. A
bot that only earns it will reach the 5th tier after weeks or months, and only if it stays in the roster.

## Proposed approach

1. **Earned path:** a bot at its class's max level rebirths like a player does.
   - It rebirths at the NPC with the same EXP rates.
   - It keeps its past-life jobs and skills (`characters_rebirth` plus botstore), so its build and attacks can use them.
   - It then levels again, so high-rebirth bots also show up in low maps, as real MapleRoot players do.
2. **Veteran slice of the roster:** a small share of roster bots are created already rebirthed (1–3 rebirths, with
   past jobs), so rebirth-gated maps and 5th-tier bots exist from day one.
   - The share is set in config.
   - Fits the "random roster" decision.
3. **World's Engine:**
   - Veterans with rebirth 3 can start with it already used, holding their x13 skills.
   - Earned-path bots need Magnus: offscreen bots (LOD) roll a clear chance once at 200+ with rebirth 3. A real
     on-screen Magnus fight waits for a boss ticket.
4. Bots never take 700/710 unless the owner wants Super Beginner bots.

## Done when

- A bot at max level rebirths, keeps its past skills, and levels again; the change survives a restart.
- Veteran bots appear at the configured share, can use rebirth-gated maps, and use x13 skills where they have them.
- Players see nothing a real MapleRoot player couldn't have.

## Decision (Owner decision 2026-10-25)

Earned path for all bots: any bot that reaches level 200 and meets its goals can rebirth like a player does. No veteran-only slice, and rebirth-3 content is not players-only. Target: 1.6.0 (a goal for the bot brain, ticket 60).

## Built (1.6.1, owner 2026-10-28 "Earned, slowly")

`BotRebirth` (BotProgressionSystem), hooked into TrainingBot's DECIDE (after party and world-event picks):
- A bot at level 200, fully advanced (rebirth.js's list), under 3 rebirths, holding no key, and that *bothers* this
  week (`botherChance` from its Bartle blend: .05 + .75 Achiever + .5 Killer + .3 Explorer + .1 Socializer, capped
  .05-.9; re-rolled weekly) rolled an offscreen boss run at most every 6 h with 35% (`-Dslums.botRebirth.gatherHours`,
  `.gatherChance`); a win added one missing boss drop of craft.js's recipe through BotHoldings (one of each at most).
  **Replaced by the promise audit below: real boss runs and off-screen runs on the players' rules.**
- Both drops -> the key is crafted (drops taken). Key held + a pace slot left today -> the trip to Wyatt (993910000,
  GCTravel; a failed trip waits 1 h). At Wyatt rebirth.js's checks run again, a pace slot is taken, the key is taken,
  and the bot is reborn: rebirths +1, Beginner/Noblesse/Legend (6/3/1 in 10 by id), level 1, EXP 0, the level-1 build
  (fresh base stats), every skill it had kept, the past job in characters_rebirth, the REBIRTH_*_RATE EXP rate, gear
  left on (BotGear reviews as always). Website feed line as for players.
- Pace: `BOT_REBIRTH_PER_DAY` (default 2) rebirths a UTC day, server-wide, memory-only (a restart resets the day).
- Persistence: rebirths read back from characters_rebirth on load (BotRebirth.restore, one query per boot); no
  migration. Keys and boss drops are never sold as junk.
- Switch `BOT_REBIRTH_ENABLED` (default false; needs USE_REBIRTH_SYSTEM). Checks: `!qa run rb-1`, auto-QA `t-rb-1`
  (raise a bot to 200, force the pace, walk the path, judge, put the bot back). Tests: BotRebirthTest.
- Not built: the veteran slice, World's Engine / x13 skills for bots, a LifePolicy activity or goal of its own.

## Promise audit (1.6.1, branch 161-rebirthkeys, 2026-10-28)

The notes say regulars "earn the key item from boss drops". The first build gave a bot that bothered one boss item on
a flat 35% roll every 6 h, with no boss, no clear limit and no check that it could kill anything; and a reborn bot
stayed in its level-200 gear. Owner rule: implement it, don't reword it.

**Where the items come from (RebirthBosses, read off the scripts):**

| Key | Item | Source (player path) | Entry rules |
|---|---|---|---|
| 1st 4033446 | 4032133 | Zakum clear: Plunk (3004006) hands every member one; drop_data rows of the boss if any | lv 50, 6-30, 25 clears a day, 120 min |
| | 4001094 | Horntail clear: Plunk | lv 100, 6-30, 20 a day, 120 min |
| 2nd 4033442 | 4021010 | Pink Bean clear: the exit NPC 2141002 | lv 120, 6-30, 20 a day, 140 min |
| | 4001693 | Von Leon clear: Plunk | lv 120, 1-6, 18 a day, 120 min, Lion King's Castle (rebirth 1) |
| 3rd 4033450 | 4000659 | Cygnus clear: Plunk | lv 120, 1-6, 16 a day, 120 min, the stronghold (emblem) |
| | 4032922 | Cygnus Knight Emblem: no boss item; the stronghold guards drop it (quest 31125), Chief Alex gives one for quest 31126 (20 each of 8600000-8600003, lv 165) | - |

**How a regular earns them now:**
- Von Leon and Cygnus (one room, summoned by the room's NPC): the seeker forms a real run (RebirthBossRuns): it and
  up to five free strong regulars on its channel (other seekers first; lv 150+, no party, nothing in flight, the map's
  gate open, a clear left today) go to the recruiter; a real Expedition (the recruiter's quota check, members via
  addMember), `EventManager.startInstance` as "Start the fight!" does, the boss summoned as 2161008/3003903 do; the
  crew fights with the bot attack driver; the kill runs the event's own monsterKilled/clearPQ (and logs the clear in
  bosslog_daily); each bot is handed the clear item (RebirthBossRuns.onClear, from setEventCleared: a bot cannot talk
  to Plunk) and loots key items off the floor; then everyone leaves. A run is only formed when the group's expected
  kill time (OffscreenCombat's swing against the boss's real HP) fits 75% of the limit; at most 2 bot-formed runs at
  once; 40 minutes at most; a failed run waits 30 minutes. Cygnus also needs the emblem first (the door takes it).
- Any key boss: a seeker waits up to 2 hours to join a real player's expedition for its boss; when a player opens one,
  up to three seekers on that channel come to the recruiter, register (their quota), fight in the instance next to the
  players (following the leader between rooms) and leave after the clear with the item.
- Zakum, Horntail, Pink Bean (their stages need a player's hand: the Eye of Fire altar, two head rooms, the statue
  stage; ticket 130) and the emblem: an off-screen run at most every 6 h (OffscreenBossRun): the level floor, the
  daily clear limit from bosslog_daily, a pickup group of 6 at the bot's own single-target damage a second against
  every mob of the fight's HP inside the time limit (9 in 10 when it fits in half the limit, falling to 0 at the
  limit); a clear writes the bosslog row; the item from the boss's drop_data rows if one rolls, else the clear reward.
  The emblem: 30 minutes at its droppers' kill rate rolling their real rows, or quest 31126's 80 kills.
- Bot hits on Zakum (arms first), Von Leon (his last form), Cygnus (guards, second form) and Magnus now go through the
  map's boss rules (MapleMap.bossDamageBlocked / beforeBossDamage / afterBossKilled, split out of damageMonster): a
  bot's killing blow used to kill Von Leon or Cygnus outright, so the event never cleared, including for 1.6.0's
  party bots in a player's expedition. The boss reward loops (Plunk, the five battle scripts' leaf hand-outs) skip bots:
  they called gainItem through a bot's client, which has no player, and threw, stopping the loop for players after it.
- After the rebirth, BotGear.afterRebirth takes off every piece a level 1 of its new job cannot wear into its bag,
  wears the best it owns that fits (a starter sword if it has no weapon at all) and keeps the rest; the usual review
  on each level-up puts the pieces back on as level, job and stats allow.

**Cost at ~3,000 bots:** only seekers (level 200, under 3 rebirths, bothered this week, missing an item) do any of
this, from their own DECIDE (the existing hook; a bot below 200 now returns at once). A run beats every 250 ms while
it lives (at most 2 bot-formed runs, 6 bots each); picking helpers scans the online bots once per run formed; the
player-expedition hook reads only the waiting seekers; `holds()` is one map read on each trainer tick. Off-screen runs
are one bosslog count and one cached drop_data read per run.

**Checks:** RebirthKeysEarnedTest, BotRebirthTest (noBossItemFromARollAlone fails on the old build), BotGearTest
(rebirth gear), BotBossMechanicsTest. `!qa run rb-1`/`rb-2`, auto-QA t-rb-1 (off-screen Zakum/Horntail) and t-rb-2 (a
real Von Leon run, Von Leon staged at 1% of his HP so the check fits in minutes; the run's first note gives the real
fight's expected length).

**Not verified:** the real damage regulars do to these bosses. If a 6-bot group cannot kill Von Leon (7.5B HP) or
Cygnus (2 x 15B) in time, no run is formed and those keys come only from players' runs; the logs say so ("can kill
... in N min of 120: not tried").

**Gaps left for the owner:** Von Leon's recruiter asks whoever talks to it for quest 3171 (the end of the Lion King's
Castle story, 3163-3170); bots play no quest chains, so their runs and joins are not held to it (1.6.0's party-bot
joins are). The walk into the Cygnus stronghold is taken as made once the bot holds the emblem. Pink Bean, Zakum and
Horntail are run on screen only with a player.

**TEST b034 (2026-10-29), t-rb-2 FAIL "the crew is gone" — fixed on 161-rebirth2.** A bot never logs in, so
`Character.isLoggedinWorld()` is false for every bot. The run's crew check dropped the crew on its first beat (250 ms);
behind it, the event and expedition code asked the same and would have left the bots out too:
`EventInstanceManager.registerPlayer` (no bot could enter an instance, which also kept 1.6.0's party bots out of their
player's expedition), `exitPlayer`, and `Expedition.getActiveMembers` (the start's registration and the boss log's
clear rows). They now ask `Character.isPresentInWorld()`: logged in, or a bot standing on a map. Test:
RebirthRunCrewTest. Measured on TEST (ImNinja, Marksman 322 raised to 200): about 367,000 damage a second against
Zakum, Horntail and Von Leon; Von Leon alone 341 min (limit 120); Zakum as a group of 6 3.6 min, Horntail 20.8 min.
Cygnus' fight (2 x 15B + 5 guards x 0.6B = 33B HP) at that rate: about 250 min for six, so no bot-formed Cygnus run
until each bot does about 1.0M a second (6 bots inside 75% of 120 min). No helper joined: the Von Leon helpers need a
rebirth (Lion King's Castle), and TEST's regulars have none.
