# 31: A MapleRoot leveling path for bots

**Area:** Real bots
**Depends on:** 03 (cross-map travel), 30 (rebirth gates)
**Status:** built on branch `leveling-path` (2026-10-20), not merged; see "Built" below

## Today

Bots pick training maps by level (`BotGrindSystem/TrainingMapChooser`):
- They choose within a band around their level, spread by crowding, and walk to the map from their home town.
- `TrainingRegions` limits them to Victoria Island, Orbis, El Nath and the Ludibrium continent.

So bots never go to Aqua Road, Leafre, Mu Lung, Temple of Time, or MapleRoot's own content (Lion King, Twilight
Perion, Grandis/Arcane, Magnus). Above about level 120 they run out of fitting maps, and no bot reaches the
rebirth-gated areas.

## What

A leveling path: an ordered list of level bands. Each band names the regions and hubs where bots train at that
level, plus the travel needed to get there.
- **Shape:** for example, 1–30 Victoria, 30–70 Victoria/Orbis/El Nath/Ludi, 70–120 Ludi/Aqua/Leafre/Mu Lung,
  120–200 Leafre/Temple of Time/MapleRoot zones, and rebirth-gated zones by rebirth count.
- **Bands and maps:** derived from map/mob data (mob levels per map) and checked by the owner against where
  MapleRoot players really train.
- **Choosing a map:** the existing chooser still picks inside a band, so bots spread out and don't stack on one map.
- **Gates:** respects MapleRoot's (`HyperTPHandler`): Lion King needs rebirth 1, Twilight Perion rebirth 2,
  Grandis/Arcane rebirth 3.
- **By job as well as level:** each band can name different spots per job group, the way players train.
  - **Melee or ranged:** melee bots prefer flat maps with dense mobs; ranged and magic bots prefer maps with
    platforms or long lines of mobs.
  - **Element weakness:** mages favour mobs weak to their element, for example Fire/Poison on ice mobs and Ice/Lightning on fire mobs.
  - **Job-specific spots:** the classic one is clerics and priests on undead.
  - **Solo or party:** some spots are for party bots, others for solo.
  - **Fallback:** a job with no specific entry uses the band's general list.
- **Variety:** each persona gets some, such as a favourite continent, sometimes training below level, or sometimes
  skipping a band.
- **Storage:** data, not code, so the owner can retune it without a rebuild.

## Done when

- At every level from 1 to 255, and every rebirth count, bots have fitting maps and travel to them.
- No bot enters a map it couldn't legally enter.
- A level sweep, as in nav lab ticket 04a, shows where bots train at each level.

## Built (branch `leveling-path`, 2026-10-20)

### Design

- **Data:** `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/LevelingPath.yaml`, read relative to the
  working directory like `TrainingRegions.yaml` (stage it with the other `src/main` data, release step 9). It is
  the research draft (15 bands, job groups, Cygnus/beginner inherits) with invalid entries commented out.
  Reload live with `!env path reload`.
- **`LevelingPath`** parses it. A band is `[lo, hi)`, and the last band includes 255. Job id ranges map to groups.
  A job gets:
  - its group's entries at full weight;
  - the band's `general` entries at 0.35 x their weight;
  - `general` alone when its group has nothing in the band.
- **`LevelingPathChooser`** is asked first by `TrainingMapChooser.choose`:
  - **When:** on 75% of normal decisions, and always when nothing around the bot's home fits its level. Chill visits
    don't ask it.
  - **Entry filters:** an entry counts only if all of these hold:
    - its map has 2 or more mob spawns in this WZ;
    - it is not a mini-dungeon;
    - it is not in a `TrainingRegions` region with `enabled: false`;
    - `BotMapGates` lets the bot's rebirths in;
    - its median mob level is no more than 15 above the bot;
    - it is not on the bot's crowd cooldown, and not at spot capacity;
    - it is walkable from the bot's home (20 hops), or a `GCMovement.route` from home reaches it with fares the bot
      can pay.
  - **Weighting:** the draw weights each entry by:
    - `w`;
    - a stable per-bot favourite-continent bias (0.5-2x);
    - the free room on the map;
    - the ticket-15 yield headroom: `BotYieldCaps.mapSaturation`, a snapshot of the per-map meters, cutting the
      weight by up to 75%;
    - 0.5 for party spots;
    - 3x for maps around home.
  - **Variety:** 8% of draws use the band below, and 10% the band ahead when the bot is within 3 levels of it.
  - **Trips abroad:** they use the same per-minute world cap as `relocations_per_minute` (counted separately).
    On arrival the bot's home becomes, in order of preference:
    1. the hub of the map's own region;
    2. else the map's WZ `returnMap` town;
    3. else the nearest hub of any enabled region.
  - **Fallback:** a pick that loses the capacity race, or a null answer, runs the old search unchanged (nearby maps
    by level, then `TrainingTravel` region relocation).
- **Tunables:** `-Dsolomapling.levelPath` (`false` turns it off), plus `.follow`, `.generalMix`, `.maxAbove`,
  `.homeBias`, `.belowChance`, `.aheadChance` and `.partyWeight`. There are no new `config.yaml` keys.
- **Read-outs:**
  - `!env path dist` (`LevelingPathCensus`): where bots chose to train, by band x group x map, path vs old search,
    and how many are grinding on their target.
  - `!env path sweep`: the per-band, per-rebirth count of usable, reachable path maps.
  - Headless: `-Dsolomapling.levelPath.reportSeconds=N` and `-Dsolomapling.levelPath.sweep=true` log both.

### WZ validation (Map.wz/Mob.wz from the 1.3.0 test install)

- **Checked:** all 509 drafted entries, covering 269 distinct maps. Every map exists and has 2 or more mob spawns.
- **Disabled with `# INVALID`, 5 entries:**
  - 4 Knight Stronghold maps (`271030102/320/530/600`), which are HyperTP-restricted, so `BotMapGates` says never;
  - Long Kiss Duku `541020620`, a mini-dungeon instance.
  - 504 entries remain.
- **Kept with a note:** 2 maps that WZ flags `town=1` but that have mobs (`140020200`, `104030001`).
- **Not in any `TrainingRegions` region:** 109 entries. The path may use them when a route exists.
- **Live sweep from Henesys (`[LevelPathSweep]`):**
  - Bands 1-120 have 6-23 usable, reachable maps each.
  - 120-140 has 4.
  - **140-255 has 0 at every rebirth count.**
  - 15 maps are unusable: Ereve, and the Temple of Time roads, because their regions are disabled.
  - 108 maps have no route even at rebirth 3, including:
    - Lion King's Castle;
    - Twilight Perion;
    - Black Heaven;
    - Mirror World;
    - Grandis/Arcane;
    - Malaysia;
    - most of Masteria/CWK;
    - some Zipangu and Aran maps.

  Above 120 the bots therefore still run on the old closest-level fallback. The missing piece is
  `BotTransit`/nav edges into those areas (ticket 03 follow-up), not path data.

### Headless check (scratch copy on 192.168.0.164, cleaned up)

- **Setup:**
  - `slums_release_test` was copied into the scratch schema `slums_levelpath_scratch`, with migrations
    003/005/006/007 applied and template 5.
  - The server was a copy of the persistence-test runtime, with `wz` linked read-only.
  - It ran as the transient unit `slums-levelpath-scratch` (CPUQuota=200%, Nice=19, MemoryMax=6G), on login port
    8519 and channels 8605+, with `BOT_ROSTER_PEAK_ONLINE: 600`.
- **Runs:** the DB was reset before each run, and each ran about 15 minutes:
  - **before:** `-Dsolomapling.levelPath=false`;
  - **after:** default settings, run twice; the second run includes the home-hub fix.
- **Cleanup:** the unit was stopped, and the schema, the user and the folder were removed. Live and
  `slums-persistence-test` were not touched.

| | before | after (run 2) |
|---|---|---|
| training bots with a decision | 588 | 591 |
| picked by the path | 0 | 290 (49%) |
| relocating abroad | 8 | 46 |
| `sets off ... for map` / `arrived ...; home is now` | 11 / 7 | 62 / 23 (run 1: 118 / 61) |
| grinding on their chosen map | 560 | 544 |

Per band (bots / distinct maps / most bots on one map):

| Band | before | after |
|---|---|---|
| 10-21 | 51 / 42 / 3 | 38 / 32 / 4 |
| 30-40 | 49 / 46 / 2 | 57 / 47 / 3 |
| 40-50 | 69 / 66 / 2 | 74 / 48 / 6 |
| 60-70 | 102 / 91 / 2 | 103 / 85 / 4 |
| 70-85 | 132 / 110 / 2 | 129 / 98 / 4 |

- **Where bots go:** after the change they concentrate somewhat on the era spots and stay spread (at most 4-6 bots on
  one map). Examples:
  - Kerning subway `103000105` for 40-50 warriors and thieves;
  - Wolf Spiders `600020300` for 70-85 warriors;
  - Ludi clocktower `220060100`/`220070xxx`;
  - Ghost Ship `541010050` for clerics;
  - Ice Valley and Sharp Cliff for 60-70 warriors and mages.

  Before, they spread over any in-band Victoria map, towns included.
- **Trips abroad (run 2):** to Singapore, Masteria, Leafre, Ludibrium and Magatia. The traces show set-off, then
  arrival, then the new home.
- **Bug found and fixed:** run 1 gave El Nath maps an Aqua Road home. It was the nearest hub by walk through the Orbis
  tower; the hub of the map's own region now comes first.
- **Not fixed (old relocation):** run 2 still shows 3 El Nath and 2 Ludi picks with home `230000000`. Those come from
  the old `TrainingTravel` region relocation, which tags every in-band map around the hub it picked. That predates
  this ticket.
- **Errors:** 0 exceptions in any run log.

### Open

- **Above 120:** routes into the high-level and MapleRoot areas are on branch `high-level-routes` (ticket 03
  follow-up, not merged).
  - **Unreachable entries:** the 43 path entries no player can reach are disabled as `# UNREACHABLE`. They cover
    Mirror World, Kritias and Umbra (CANNOTVIPROCK, no entry), LKC past Walls 1 (keys), Temple of Time lanes past
    Memory Lane 1 (quests), the Ninja Castle corridors, Mushroom Castle and Aran Rien.
  - **Replacements:** 28 reachable entries fill 120-200 at rebirth 0-2:
    - Ulu City, the Skele nests and Crimsonwood Keep;
    - LKC Walls 1 at rebirth 1;
    - Gate to the Future and Twilight Perion at rebirth 2;
    - the Scrapyard by hyper rock.
  - **Rebirth-3 zones:** they stay as bonuses.
  - **Level-checked hops:** the planner, `LevelingPathChooser` and `TrainingTravel` now plan for the bot's own
    level, so a level 45-49 bot is never sent past Jeff.

  Offline sweep from Henesys (`LevelPathSweepCheck`, bake WZ, at each band's lowest level). Usable, reachable path
  maps for r0 / r1 / r2 / r3:

  | Band | before | after |
  |---|---|---|
  | 100-120 | 16 / 16 / 16 / 16 | 18 / 18 / 18 / 22 |
  | 120-140 | 4 / 4 / 4 / 4 | 8 / 9 / 9 / 14 |
  | 140-160 | 0 / 0 / 0 / 0 | 5 / 6 / 9 / 11 |
  | 160-180 | 0 / 0 / 0 / 0 | 4 / 5 / 13 / 15 |
  | 180-200 | 0 / 0 / 0 / 0 | 4 / 4 / 13 / 18 |
  | 200-255 | 0 / 0 / 0 / 0 | 6 / 6 / 7 / 22 |

  - **No route at rebirth 3:** 108 maps -> 0.
  - **Unusable:** 15 -> 0.
  - **Limit at rebirth 0-1:** nothing open there has mobs above about 120 on this server. From 140 up those bots
    grind the strongest classic maps, and Black Heaven opens to them at 185.

- **Path share:** about 50% of decisions, against a 75% target. The rest are capacity races, unaffordable fares
  (most bots have few mesos) and the trip permit cap. Tune with `-Dsolomapling.levelPath.follow` and
  `relocations_per_minute` once supplies (ticket 08) gives bots mesos.
- **Old relocation homes:** the region-relocation home bug described above, in `TrainingTravel.RegionTravel`.
- **Not yet consulted:** resumed grinders, party/station-here picks, and downward-only deep hubs (only their mob
  floor is applied).
- **Owner check:** the data needs the owner's review against where MapleRoot players really train.
