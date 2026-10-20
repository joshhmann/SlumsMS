# 03: Cross-map world graph (boats, taxis, ferries)

**Area:** Nav v2  
**Depends on:** 01  
**Status:** done on branch `travel` (2026-10-19), awaiting merge and an in-game look

## What

Port or extend GCWorldGraph and GCTaxi so bots travel between towns and continents; offscreen travel goes through the coarse planner.

## Done when

A bot sent from Henesys to Orbis or Ludibrium arrives via the ship; offscreen travel time is plausible.

## What landed

- **Transit table** `BotTravelSystem/BotTransit.yaml` (replaces GCTaxi): every hop mirrors a server script, with its
  fare and timing. The tests check each fare, boarding NPC and event map against `scripts/npc` and `scripts/event`.
  - Victoria cabs (Beginners pay a tenth), the Ellinia/Orbis boat (`Boats`), Orbis/Ludibrium train (`Trains`),
    Orbis/Leafre cabin (`Cabin`), Orbis/Ariant genie (`Genie`), Isa's free platform warps.
  - Hak the crane (Orbis/Mu Lung, Mu Lung/Herb Town), the dolphin taxi (Aquarium/Herb Town).
  - Kerning/Singapore airplane (`AirPlane`), Kerning/NLC subway (`Subway`).
  - Ereve sky ferries (timed by `MapleMap.addPlayer`), the Leafre/Temple of Time dragon, the World Tour to Zipangu
    (it only takes a bot home to the town it left from, as the script does).
  - Aqua Road and Magatia are walkable from Orbis/El Nath and Ariant.
- **Routing** `GCWorldGraph`: cheapest route by time over portals, scripted warps and transit; cached; never passes
  through a map behind a rebirth gate the bot hasn't passed (`BotMapGates`, from `HyperTPHandler` plus Warrior
  Grounds).
- **Riding** `GCTravel`: walk past the ticket seller to the boarding NPC (GCMovement, so on-screen bots visibly walk
  and off-screen ones use the coarse planner), queue until the event opens boarding, pay from the bot's own mesos
  (`BotHoldings.spendMeso`), then ride. The event's `warpEveryone` (or the ferry timer) moves the bot; a fallback
  moves it after the ride's length so it can't be stranded. A bot that can't pay replans over free hops, else the
  trip fails (no free warp). Ride times follow the world `travel_rate`.
- **Regions** `BotGrindSystem/TrainingRegions.yaml`: the original four windows unchanged; Aqua Road, Leafre, Mu Lung,
  Nihal Desert, Singapore, Masteria and Zipangu added with hubs and travel levels; Temple of Time and Ereve listed but
  disabled. Rebirth-gated maps are never grind candidates (this closes the LKC hole from the research).
- **Relocation** `TrainingTravel`: on a decision a bot may move to another region (5% while its region fits, 80% once
  outgrown), to a region with in-band maps that it can reach and afford, capped at 20 departures a minute. The hub
  becomes its home on arrival. **Ticket 31 hook:** `TrainingTravel.install(Policy)`.
- `!env travel` (readout / `reload` / `route <map>`); `TransitRouteCheck` dev tool prints routes against a WZ tree.

## Evidence

- Suite: 2,551 tests, 0 failures (bots-integration was 2,512).
- Headless on 192.168.0.164, `/opt/mapleroot-dev/real-stats-test/travel-run` (schema `mapleroot_realstats`, restored
  afterwards from `realstats-before-travel.sql.gz`; `travel_rate` 10 as in that config; `wander_chance` raised to
  0.5 for the run). In about 20 minutes: 16 bots set off, 16 arrived, 70 paid transit hops, 0 fallback warps,
  0 errors. Log: `travel-run/travel-evidence.txt`. One Victoria to Orbis to Ludibrium trip:

```
17:47:29 CWKPQpro (lv 37) sets off from map 105040300 for map 221024200 (new home 220000000)
17:48:15 CWKPQpro took 'cab' 100000000 -> 101000000 for 800 mesos
17:48:24 CWKPQpro queued for 'boat to Orbis' on map 101000300 (boarding closed)
17:49:22 CWKPQpro took 'boat to Orbis' 101000300 -> 101000301 for 5000 mesos
17:50:56 CWKPQpro took 'platform' 200000100 -> 200000120
17:51:00 CWKPQpro took 'train to Ludibrium' 200000121 -> 200000122 for 6000 mesos
17:52:32 CWKPQpro arrived on map 221024200; home is now 220000000 (was 105040300)
```

  Other trips: Ludibrium to Leafre (train, cabin), El Nath to Singapore (dolphin, crane, Ereve ferries, cab, plane),
  Sleepywood to Magatia (boat, genie), Ludibrium to Aqua Road (train, crane, dolphin).

## Open

- **Version gate:** `portal_versions.yaml` (bot version 55) still gates Ereve town, Temple of Time town, Nautilus and
  Malaysia. Their transit rows exist, but bots only reach the docks. The Temple of Time roads are also quest-scripted
  portals. Lifting this is a call for ticket 31.
- **Edelstein / Black Heaven:** no script warps there (Hyper Teleport Rock only), so there is no route.
- **Route costs:** a walked hop costs 20 s, and that ignores `travel_rate`. On a rate-10 world, El Nath to Orbis rides
  the dolphin and crane, and Orbis to Victoria takes the Ereve ferries, rather than the tower and boat. These are
  legal and paid, but unusual.
- A relocating bot's map pick around a hub can spill into a neighbouring region. For example, an Aqua Road hub reached
  a Korean Folk Town map.
- Not yet seen with a real client watching: the on-screen walk to the dock, the queue at the NPC and riding the deck.
- A broke follower bot now fails its trip instead of warping across water; it retries each follow poll.

## Follow-up: high-level and MapleRoot areas (branch `high-level-routes`, 2026-10-20)

**Status:** built, not merged. It covers the ticket 31 gap: no route from Victoria reached the 140+ path maps or
the rebirth zones.

- **New `BotTransit.yaml` rows.** Each row mirrors a script, and `BotTransitHighLevelTest` checks it against
  `scripts/npc` and `scripts/portal`.
  - **Jeff (2030000):** Ice Valley II to Sharp Cliff I. Free, level 50+ (the new `min_level` field). MapleRoot's
    Ice Valley II portal up has no target.
  - **`in_lkc.js`:** Desolate Moor to the LKC entrance. Rebirth 1, enforced by `BotMapGates`.
  - **`arcaneriver.js`:** Three Doors to Vanishing Journey. Rebirth 3.
  - **Henesys teleporter 9010002:** its Grandis and Arcane menus, 23 destinations, free, `min_rebirths: 3`. It
    leaves out Knight Stronghold and the boss menu.
  - **Puro's whale:** Lith Harbor to and from Rien, 800 mesos, the 1-minute `MapleMap` timer.
  - **Florina Beach:** Pison, 1500 mesos.
  - **NLC taxi:** to and from the Haunted House, 15000 mesos each way, plus the `halloween_enter` door.
  - **Malaysia:**
    - Audrey from CBD, 42000 mesos;
    - Spinel from Boat Quay, 10000 mesos;
    - the Kampung buses, 10000 mesos;
    - the free way home to the saved WORLDTOUR town.
  - **Showa hideout:** Konpei and the guard.
  - **Fox Tree lift:** 3002015, 1000 mesos.
  - **Ereve:** `enterDisguise0-5`.
- **Hyper Teleport Rock.** `hyper_rock` block, Via `HYPER_ROCK`, following `HyperTPHandler`'s rules: 5-minute
  cooldown, rebirth gates, no restricted or CANNOTVIPROCK maps.
  - Bots may only jump into Black Heaven (Haven, BH Entrance), and out of Edelstein/BH/Grandis/Arcane to the
    Victoria towns.
  - One jump per route, so the rock never replaces the boats.
  - Black Heaven needs it because the Edelstein road there (`level700BH`) and the deck portals have no script on
    this server.
- **Planner.**
  - The hop cap is now 160 (`GCWorldGraph.MAX_ROUTE_HOPS`). Omega Sector was 61 hops, so the old cap of 60 hid it.
  - Edges with `min_rebirths` are skipped for bots below it.
  - The rock is modelled as virtual nodes that are stripped from returned routes.
- **Version gate lifted** (bot-only, `portal_versions.yaml`): Three Doors, Ereve, Crossroads of Ereve, Hibiscus Road 1.
- **Regions:** Temple of Time and Ereve are enabled in `TrainingRegions.yaml`.
- **Tools:** the offline sweep is `LevelPathSweepCheck`, and `TransitRouteCheck` takes `from:to[:rebirths]`.

### Owner follow-up (same branch)

- **Hyper rock:** confirmed for non-holders on the cooldown, and stays enabled.
- **Level-aware planning:**
  - `GCWorldGraph` skips hops whose `min_level` the bot fails. The route cache is keyed by the highest level
    check passed.
  - `GCTravel` and `GCMovement.route(bot, ...)` pass the bot's level.
  - `LevelingPathChooser` and `TrainingTravel` filter destinations with the bot's level.
- **Leveling path:** unreachable path entries are swapped for reachable ones. See ticket 31.

### Unreachable for players too (now dropped from the path)

- **LKC towers past Walls 1:** the keys are items 4032832-4, or quests 3165-3167.
- **ToT lanes past Memory Lane 1:** `timeQuest.js` needs the quests done.
- **Mirror World, Kritias and Umbra fields:** CANNOTVIPROCK, and no portal or script reaches them.
- **Ninja Castle corridors:** no entry.
- **Mushroom Castle:** quests.
- **Rien Dangerous Forest for job-2000 Arans:** quest 21014.
- **Teleporter boss menu:** not added (a free shortcut no path map needs).
