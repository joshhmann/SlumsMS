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
