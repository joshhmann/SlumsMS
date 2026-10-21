# 21: Scripted world events with bot reactions

**Area:** Later  
**Depends on:** 16  
**Status:** built on branch `world-events` (not merged, not deployed). The automatic scheduler is **off** by default;
GM commands start events by hand. Owner decisions are listed below.

## What

Spec in the SoloMapling repo `.pi-flow/world-events/spec.md`: templates and a scheduler, never an LLM GM.

## Done when

See that spec.

## Built (branch `world-events`, 2026-10-20)

Code: `src/main/java/soloMapling/worldevents/` (templates in `WorldEvents.yaml` next to it), the NPC script
`scripts/npc/worldEventNpc.js`, bot lines in `BotDialoguePack/WorldEventDialogue.yaml`, and megaphone nodes
`WorldEvent*` in `MegaphoneDialogue.yaml`.

- **Templates (`WorldEvents.yaml`, owner-controlled):** id, kind, weight, minutes (at most 120), cooldown, bot
  level band, bot crowd cap, maps or spots, and a fixed reward table. A broken template is skipped and listed
  (`!event list`); the others still load. `!event reload` re-reads the file.
- **Scheduler (`EventScheduler`):** weighted pick, never a model. Each 30 s tick has a small chance to start an event,
  scaled by the population director's daily curve (weekend lift included). Nothing starts while the curve is below
  20% of peak (about 05:00-07:00 on the default curve). At most 1 event at once (`maxActive`, hard cap 2, GM starts
  included). Starts are at least 30 min apart, a template rests for its cooldown, and the same template never runs
  twice at once. Every event ends at its end time; a shutdown ends them all.
- **The five kinds** (all on channel 1, where the bots live):
  - **Invasion:** mob waves on a field map; the next wave comes when one is cleared, then the boss. The real player
    who kills the boss wins.
  - **Boss sighting:** a field boss on a named map; the real player who kills it wins.
  - **Treasure hunt:** a Treasure Chest NPC hidden at a mob spawn point on a random map. The opening notice names the
    region; at 1/3 of the time a notice gives the map's first letter, and at 2/3 it names the map. The first real
    player to click the chest wins and the event ends.
  - **Hot spot:** +50% EXP for real players on one training map for 30 min. Applied in `Monster.giveExpToCharacter`
    to that map only. Server rates are untouched, and bots do not get it.
  - **Wandering NPC:** Taeng the Explorer appears in a town and asks for N of a local etc item. Each real player can
    turn it in once, for the reward.
  - The event's mobs and NPC leave when it ends: no drops, no EXP. Its mobs drop normally while it runs (drop
    tables and rates are not touched).
- **Bot reactions (`WorldEventReactions`):**
  - **Gather:** training bots in the event's region (`TrainingRegions`) whose level fits and whose personality leans
    to grinding drop their outing and go: they leave the grind or town and their next decision trains on the event
    map. The crowd cap is per template, also capped by `-Dsolomapling.worldEvents.botCap`. Bots are recruited again
    every 2 min as new ones log in. Committed bots stay put, by the same test the population director uses before a
    logout (open trade or invite, a conversation, a party with a real player, following someone).
  - A bot that cannot reach the map gives up after 3 decisions. Bots stay on the event map while it runs (no crowd
    bail, no session walk-off). When it ends they are released and carry on with their own session.
  - Treasure hunts: bots only go once the last hint names the map, and they cannot open the chest.
  - Wandering NPC: bots only talk about it.
  - **Chatter:** about every 90 s per event, one bot on a map a real player is on says a line about it (on the event
    map first). For 20 min after it ends, bots talk about it (who won, where it was) every ~4.5 min. Lines are in
    `WorldEventDialogue.yaml`, written in old-GMS chat style. It is bot map chat, so `@quiet bots` hides it.
  - **Megaphones:** 60% of events get one bot megaphone at the start, and a real player's win gets a
    congratulation. Both go through `WorldEventMegaphones.offerEvent`, so through `BotMegaphoneGate`, its pending
    slot and cooldown, and `@quiet megaphones`.
  - **Memory:** the real player who wins becomes a `BotMemory` note for up to 8 bots on the map (e.g. "killed Faust
    at The Forest of Evil I when it showed up"), and `BotGossip` spreads it as hearsay.
- **Notices:** event notices are blue server notices to the world (system messages, never filtered by `@quiet`).
  Bot lines and bot megaphones go through the bot speech primitives and `@quiet`.
- **Untouched:** rates, drop tables, shops and the population director (events never log bots in or out).
  `BotYieldCaps` still meters everything bots loot from event mobs. Bots never receive event rewards.
- **GM commands** (GM level 4; every use goes to `gm_command_audit` through `GmAudit`):
  - `!event` or `!event list`: templates, rests, what is running, and skipped templates.
  - `!event preview`: the chance per tick now and the share of each eligible template.
  - `!event preview <id>`: its maps, the bot levels that would react, and its rewards.
  - `!event start <id> [mapId]`: start one now (the map must be one of the template's).
  - `!event stop <n|all>`: end one now.
  - `!event auto on|off`: switch the scheduler until the next restart.
  - `!event reload`: re-read `WorldEvents.yaml`.
  - `!worldevent` is an alias for `!event`.

### First template set (`WorldEvents.yaml`)

| id | kind | where | runs | bots |
| --- | --- | --- | --- | --- |
| `mushroom_invasion` | invasion | Ant Tunnel I/II/Park: Horny then Zombie Mushrooms, then Mushmom | 25 min, rests 4 h | lv 17-70, 8 |
| `boar_stampede` | invasion | Land of Wild Boar I/II: Wild then Fire Boars, then Stumpy | 25 min, rests 4 h | lv 20-45, 8 |
| `field_boss` | boss sighting | Mano, Stumpy, Faust or Dyle on one of their maps | 30 min, rests 90 min | boss lv -15/+25, 6 |
| `hot_spot` | hot spot | one of 6 Victoria training maps, +50% EXP | 30 min, rests 2 h | map lv -8/+15, 6 |
| `treasure_hunt` | treasure hunt | one of 8 Victoria fields | 20 min, rests 3 h | lv 10-200, 4 |
| `wandering_explorer` | wandering NPC | Henesys, Ellinia, Perion or Lith Harbor | 30 min, rests 3 h | chatter only |

### Default schedule

- **Off.** Nothing starts by itself unless the server runs with `-Dsolomapling.worldEvents=true` or a GM says
  `!event auto on`.
- **When on (gentle):** about 1 event per 2 h at peak hours (`meanGapMinutes` 120), fewer off-peak, none while the
  curve is under 20% of peak. On the default curve that is about 4-5 events a day; the scheduler test simulates a
  month and checks 2-10 a day, evening-heavy, none at 05:00-07:00.

### New `-D` tunables (config.yaml is strict)

| Property | Default | What it does |
| --- | --- | --- |
| `solomapling.worldEvents` | `false` | Automatic scheduler on/off. GM commands work either way. |
| `solomapling.worldEvents.meanGapMinutes` | `120` | Average gap between events at peak. |
| `solomapling.worldEvents.maxActive` | `1` | Events at once (never above 2). |
| `solomapling.worldEvents.minGapMinutes` | `30` | Least time between two starts. |
| `solomapling.worldEvents.quietBelow` | `0.2` | No scheduled events below this fraction of peak. |
| `solomapling.worldEvents.tickSeconds` | `30` | Scheduler tick. |
| `solomapling.worldEvents.botCap` | `10` | Global cap on bots per event. |
| `solomapling.worldEvents.chatterSeconds` | `90` | Gap between bot lines per event. |
| `solomapling.worldEvents.rewards` | `true` | `false` switches every reward table off. |
| `solomapling.worldEvents.file` | `src/main/java/soloMapling/worldevents/WorldEvents.yaml` | The template file. |

### Owner decisions (defaults chosen; change any)

1. **Rewards (flagged):** potions only, to the winner. Invasion: 5 or 4 Elixirs, plus a 30% or 25% chance of
   Power Elixirs. Boss sighting: 3 Elixirs. Treasure: 3 Elixirs, plus a 50% chance of 1 Power Elixir. Explorer
   quest: 2 Elixirs per player. No mesos, equipment or scrolls. Edit `rewards:` in `WorldEvents.yaml`, or
   `-Dsolomapling.worldEvents.rewards=false`.
2. **Scheduler off by default.** Turning it on is the owner's call (gentle rate above).
3. **One event at a time** (the spec allows 1-2).
4. **Channel 1 only**, where the bots are. Notices say "Ch 1".
5. **Hot spot bonus for real players only.** Bots still come and train there for the crowd.
6. **Victoria Island only** for the first set: regions, maps and bosses that fit the population. King Clang (Florina
   Beach) was left out because bots do not train there.
7. **The winner is the real player who lands the killing blow** (the drop owner). When a bot kills the boss, nobody
   wins; the ticket 47 boss megaphone lets the bot brag.
8. **LLM flavor text (spec ticket 6) is not wired.** All notices and lines are scripted, and no model touches events.
9. **Only training bots gather.** Town and social bots talk about events but are not converted to another activity.
10. **Invasions use field maps, not towns**, so bots can fight there with the grind system and new players in towns
    are not swarmed.

### Core hooks (for ticket 25's `SOLOMAPLING_HOOKS.md`)

- `server/maps/MapleMap.killMonster`: `WorldEvents.onMonsterKilled` (an event mob died), through
  `MapleRootSolo.runHook`.
- `server/life/Monster.giveExpToCharacter`: `WorldEvents.expMultiplier` on personal and party EXP (hot spot). It is 1
  unless a hot spot is running.
- `net/server/channel/handlers/NPCTalkHandler`: event NPCs open `worldEventNpc.js`.
- `client/command/CommandsExecutor`: `!event` / `!worldevent` (GM 4).
- SoloMapling side:
  - `TrainingBot`: `callToEvent`, the event branch in DECIDE, and no crowd bail or walk-off on the event map.
  - `BotPopulationDirector.mayLogOut` is now public.
  - `WorldEventMegaphones.offerEvent`.
  - `EnvironmentManager` starts `WorldEvents`.

### Verified

- `./mvnw -o test`: 2946 tests, 0 failures, 6 skipped (as before). No scratch server run: a permission check stopped the DB-credential step, so this rests on the tests. The new tests are `EventSchedulerTest`,
  `EventTemplatesTest` and `WorldEventLogicTest`:
  - The scheduler: off by default, the curve and quiet hours, the cap, gap and cooldown, no duplicates, every event
    ending, the weighted pick, and a simulated month.
  - The templates: the shipped file loads clean, covers all five kinds and gives potions only; every validation
    rule.
  - The logic: level bands, reward rolls, steering give-up and release, the personality join chance, notice tokens,
    and that every kind has chatter and megaphone lines.

### What to try in game (GM 4, channel 1)

1. `!event list`, then `!event preview field_boss`.
2. `!event start field_boss 100040105`: Faust appears at The Forest of Evil I and a blue notice goes out. Training
   bots of level 35-75 on Victoria head there over the next minutes. Kill Faust: a win notice, Elixirs, and likely a
   bot megaphone "gg <you>". Bots talk about it in town for 20 min.
3. `!event start treasure_hunt`: watch the hints, then find the chest and click it.
4. `!event start hot_spot 104040001`: kill mobs on HHG II and compare the EXP numbers.
5. `!event start wandering_explorer 100000000`: talk to Taeng in Henesys, accept, and bring 20 Orange Mushroom Caps.
6. `!event start mushroom_invasion`: clear two waves in the Ant Tunnel, then Mushmom.
7. `!event stop all`: the mobs and NPC vanish.
8. `@quiet bots` and `@quiet megaphones` hide the bot chatter and megaphones, but not the blue notices.

### Public announcements feed (branch `public-announce`)

`-Dslumsms.announceFile=PATH` (default unset = off) makes `WorldEventFeed` append one JSON line when a boss
sighting, treasure hunt or hot spot starts, and when a real player wins a boss sighting or treasure hunt. Written on
its own daemon thread with a 64-line queue (never blocks the game; a full queue or write error drops the line and
logs it). Invasions, the wandering NPC, bot chatter and megaphones are never written; a treasure hunt's start names
the region only. `slumsms-monitor announce-feed` posts the lines to the public Discord channel in Cody's voice
(`deploy/monitor/README.md`). Tests: `WorldEventFeedTest`.
