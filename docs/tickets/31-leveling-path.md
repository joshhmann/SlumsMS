# 31: A MapleRoot leveling path for bots

**Area:** Real bots
**Depends on:** 03 (cross-map travel), 30 (rebirth gates)
**Status:** open

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
