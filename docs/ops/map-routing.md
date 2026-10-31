# Map routing: never land in a map the server or the client can't load

Owner, 2026-10-31 08:1x UTC, after the client crashed walking into Ristonia's Rampart Residences (410000350) on
TEST: "we should definitely fix the client side crashing though by actually adding correct routing for each map".
Ticket `s-maproute`.

**Round 3 (2026-10-31 evening).** Client build 10721 (rc6) fixed Rampart Residences, so that map is ordinary again and
`MIN_CLIENT_BUILD` 10721 keeps older clients out; the table no longer blocks it. Two new sources for the client-crash
kind: the Client lane's WZ audit (`/root/work/agent-runs/client/client-crash-maps.txt`, 11352 maps scanned, 5940
findings - an image the client's tables lack is fatal) and the Rose Garden family (211080100-211080700), which the
client dies on for a reason that is still under investigation. Every way into either goes to a map that loads: the
Gollux legs to Memories of the Heart Tree (863010000), the Rose Garden to its entrance, Gardener's Spot (211080000).

**Round 4 (2026-10-31, QA).** t-mp-1 failed on TEST twice - the probe could not load two of the fallbacks the table
hands a character to, 867120100 and 867120900: `NoSuchElementException: No value present` out of
`LifeFactory.getMonsterStats`. Those two maps spawn mob 3401011 and 3501008, and both name mob skill 201, which this
server's `MobSkillType` does not carry (470 mobs of our `Mob.wz` name a skill id the enum lacks or a level
`Skill.wz/MobSkill.img` has no data for; 48 maps spawn one). The throw took the whole map with it because
`MapFactory.loadLife` cannot survive a mob that fails to load. Fixed at the cause: an unimplemented skill entry is
skipped with one warning per mob, and the mob and its map load. The same tolerance now applies to map 706030000's
reactor 7492024, whose type-100 item events never name their item (also fatal to the map load; 3109000 and 9260002 are
the only other reactors built like that). Third class found and left alone: 229 maps spawn a mob that has no `Mob.wz`
image at all - that is a data gap, and the generator now refuses such a map as a destination instead.

Our server WZ carries maps from newer game versions. Three things can go wrong when a portal, a script, a GM warp or a
saved character points at one of them:

1. **missing** - the map id is not in the server's `Map.wz` at all (`MapFactory.loadMapFromWz` then throws: the image
   data is null). 38 such targets today, e.g. Fountain Square (410000300) `in00` -> Storage Cellar (993162016), and a
   few of them are named by portal, NPC and event scripts too.
2. **client-crash** - the map loads on the server but the v83 client cannot render it, and the client dies on entry.
   11 today: Gollux: Abdomen (863010240), the seven Rose Garden maps past Gardener's Spot (211080100-211080700), two
   Star Planet maps (814040000, 814041900) and 402000324, an id `String.wz` does not name.
3. **mob without an image** - the map's image is there but a mob it spawns has none, and `MapFactory.loadLife` fails on
   the null life, so the map cannot load. 229 today (811000001, the four Gollux legs, ...). A map like that is never a
   destination: the generator's `Router.loadable` refuses it and the fallback/pair rules pick the next candidate.

Bots already skip (1) (`GCWorldGraph.dropMissingTargets`); players never did. This table is the one list of such
maps and of where a move into one has to go instead, and one resolver all warp paths go through.

## Files

| File | What |
|---|---|
| `src/main/resources/soloMapling/map-routing.tsv` | the table: per-move routes and the blocked maps with their kind and fallback. Generated, hand rows kept. |
| `tools/data-align/map_routing.py` | the generator (WZ -> table), re-runnable, validator included. |
| `tools/data-align/client-crash-maps.txt` | our client-crash seed: always read by the generator. A line `!<id>` clears an id another list still carries (410000350 after rc6). |
| `src/main/java/server/maps/MapRouting.java` | loads the table, answers `route(from, portal, target)` / `move(chr, target, portal)`, `blocked(id)`, `fallback(id)`, and does the once-an-hour logging. |
| `src/main/java/client/Character.java` | the one enforcement point for movement: `getWarpMap`/`mapInstanceFor`, so every `changeMap*` obeys it. |
| `server/maps/MapManager.java` | the net under it: a map that is still absent after routing resolves to its fallback (once per hour log), so nothing that fetches a map straight from the factory can throw. |
| `soloMapling/ArtificialPlayer/GCMoveSystem/GCWorldGraph.java` | bots' world graph drops blocked targets, so bot route planning agrees with the table. |

## Table format

Two sections, `<TAB>` separated, `#` starts a comment, blank lines ignored.

```
# routes: from_map <TAB> portal_name|* <TAB> target_map <TAB> route_to_map <TAB> route_to_portal <TAB> reason
410000300	in00	993162016	410000300	sp	source-return
211080000	east00	211080100	211080000	sp	return
863010230	Left2	863010240	863010000	sp	return
*	*	910060100	120000000	sp	street-town (script)

# blocked_maps: map <TAB> kind(missing|client-crash) <TAB> reason <TAB> fallback_map
211080100	client-crash	Rose Garden : Abandoned Garden (client list)	211080000
863010240	client-crash	Phantom Forest : Gollux: Abdomen (client list)	863010000
993162016	missing	Ristonia : Storage Cellar	410000300
```

- `from_map` is the map the move starts on, `*` for "any map" (a script or an NPC that warps from wherever).
- `portal_name` is the source portal's `pn`; `*` when the portal is not known (an NPC script, a GM warp).
- `route_to_map` is where the move lands instead. `route_to_map == from_map` means "stay where you are": the move is
  refused with a short system message, the character stays on its map with actions enabled.
- `route_to_portal` is a portal name on `route_to_map` (`sp` or `0` for its default spawn).
- `reason` is the rule that picked the row, so a human can audit every line (see below). The generator re-derives it
  from the data on every run (`check_reasons`), so a row whose route does not follow the rule it names stops the run
  instead of reaching the table.
- `blocked_maps` gives every blocked id a kind and a `fallback_map`: what a saved character and a warp whose source is
  unknown fall back to. The fallback is always a loadable, unblocked map.

## Rules (what "correct" means)

The generator walks the WZ once and applies these in order; the first that yields a loadable, unblocked map wins and
its name goes in `reason`:

1. `manual` - a row in the old table whose line ends with `# manual` is kept verbatim (operators keep the last word).
2. `return` - the target exists in `Map.wz` (client-crash list): its own `info/forcedReturn` (when it is not
   999999999), else its `info/returnMap`. A map always knows where it sends a character back to.
3. `portal-pair` - another loadable map has the counterpart portal: `pn` of ours is its `tn` and `tn` of ours is its
   `pn`. That is the map our door was meant to appear in (WZ renumbering), used when unambiguous.
4. `source-return` - the source map's own `info/returnMap`: where that map sends a character back to, so a door into
   a hole leaves the player through the map they are standing in. This is why `100 out03 70 66 sp source-return` is
   right even though `66 out03 70 65 sp source-return` exists too: 100 returns to 66 and 66 returns to 65, so the two
   rows are a chain out of the same hole rather than the same answer twice.
5. `street-town` - the nearest loadable, unblocked town (`info/town` = 1) on the target's street (`String.wz`
   `streetName`, else the source's street), nearest by map id.
6. `street-nearest` - the nearest loadable, unblocked map on that street.
7. `henesys` - 100000000 as the last resort.

`route_to_portal` prefers the source portal's `tn` (the arrival door the map expects) when the route map has it, else
`sp`, else the map's first portal.

The generator also reads scripted warps: `scripts/portal/*.js`, `scripts/npc/*.js` and `scripts/event/*.js` literal
`warp(<id>)` / `warp(<id>, ...)` calls, the `script` attribute of a map's portals (portal scripts), and
`BotScriptedWarp`'s edges. A blocked id any of them names gets a `blocked_maps` row (kind `missing` when the id is not
in `Map.wz`), so no script can warp a player into one either.

## Runtime

One resolver, `server.maps.MapRouting`:

```java
MapRouting.Route route = MapRouting.route(fromMap, portalNameOrNull, targetMap); // null = the move is fine as it is
MapRouting.Move  move  = MapRouting.move(chr, targetMap, portalNameOrNull);      // what changeMap does with it
```

`Character.getWarpMap(int, String)` and `mapInstanceFor(int)` call it, so every `changeMap*`/`forceChangeMap` caller
(portals, script and NPC warps, `!warp`/`@goto`, event instances, saved-map login, bots' travel) lands on the route.
`MapManager.getMap` calls `MapRouting.fallback(id)` when a map is still absent after that, so a map that reaches the
factory directly cannot throw; it resolves to the fallback and logs.

Behaviour:

- a rerouted move is logged once per target map per hour: `log.info` plus a `QaLog` line on the `MAP_CHANGE` topic,
  so QA runs and TEST logs show real traffic:
  `map routing: chr Name 410000300:in00 -> 993162016 rerouted to 410000300 (source-return)`.
- a route to the source map itself is a dead end: the character gets one short system message ("That way doesn't lead
  anywhere right now.") and `enableActions`; it does not move and does not get stuck. A rerouted move is told so
  ("That way is closed right now, so you were taken back.").
- saved-map login: `Character.loadCharFromDB` resolves the map through the factory, so a character saved in 211080100
  logs in at 211080000, and one saved in 863010240 logs in at 863010000, with its `mapid` corrected.

## Switch

`-Dslums.maps.routing=true` (default `true`; it only changes moves that would fail). `false` restores the old
behaviour for a bisect. TEST and the release conf carry the line so nobody has to remember it.
`-Dslums.maps.routing.table=<path>` reads another table (an operator's copy), else the classpath resource
`/soloMapling/map-routing.tsv`.

## Regenerating

```
python3 tools/data-align/map_routing.py --wz /root/slums-nav-v70/wz \
    --client-crash /root/work/agent-runs/client/client-crash-maps.txt
python3 -m unittest discover -s tools/data-align -p 'test_*.py'
```

The generator keeps `# manual` rows, rewrites everything else, and validates its own output: no `route_to_map` is
missing from `Map.wz`, blocked itself, or a map whose mobs cannot all load; every blocked map has a loadable fallback;
every row's portal name exists on its `from_map` when the row is not `*`. It exits non-zero with the offending rows
instead of writing a bad table. Loadable means `Router.loadable`: in `Map.wz`, not blocked, and every mob in the map's
`life` has a `Mob.wz` image (NPCs never fail; a mob skill the server lacks does not disqualify a map any more, the
loader tolerates it).

## Tests

- `MapRoutingTableTest` (no WZ) - the table is in the jar and loaded, the two owner maps are blocked with the right
  kind and fallback, Fountain Square's `in00` routes back to itself, the four Gollux legs that point at the abdomen
  route to Memories of the Heart Tree, all seven Rose Garden maps past the entrance land at Gardener's Spot while Roof
  of the Fifth Tower stays ordinary, 410000350 is no longer blocked, an unknown source still lands on the fallback, and
  no route in the committed table lands in a blocked map.
- `MapRoutingWzTest` (skips without `wz/`) - the committed table against the real `Map.wz`: every route lands on an
  image that exists, is not blocked, and has the arrival portal; every destination and fallback loads for real through
  `MapFactory.loadMapFromWz` (the round-4 guard: the probe found a crash here, this test refuses the next one); `missing` rows really have no image and `client-crash`
  rows do; every blocked map's fallback loads; the map manager hands out neither 993162016 (no image) nor 863010240 or
  211080100 (`client-crash`, image present) and substitutes only through `getMapOrFallback`; the data still says
  Fountain Square `in00` targets 993162016 and Gardener's Spot `east00` targets 211080100.
- `MapRoutingWarpTest` - Fountain Square's `in00` from a character standing there sends the message and
  `enableActions` and does not move; a script warp to 993162016 lands on the square; warps into 863010240 land on
  863010000; the Rose Garden's `east00` is a dead end while the way in from the tower is untouched; an ordinary portal
  sends no packet.
- `CharacterLoginMapRoutingTest` - a character saved in 863010240 or 211080100 logs in at the fallback and the next
  save writes the corrected id.
- `MobSkillToleranceTest` / `ReactorLoadTest` (skip without `wz/`) - mob 3501008's unimplemented skill 201 costs the
  skill and not the mob; mob 2600030 keeps the one skill of its six that this server can run; 9410204 keeps its FEAR;
  reactor 7492024's itemless state is inert through both load paths while 6938001 still waits for item 4034370.
- `tools/data-align/test_map_routing.py` - the crash lists' `!` clear, the committed table agreeing with the seed
  (every seeded id a `client-crash` row, the cleared one absent, no fallback blocked), and the loadability rule: a map
  whose mob has no image is not a destination, and with the two round-4 mobs' images removed the router moves both
  rows to `henesys` instead.
- `GCWorldGraphRoutingTest` - the bots' graph drops edges into blocked maps, on a hand graph and on the real one.
- Drift check: `python3 tools/data-align/map_routing.py --check --wz <wz>` regenerates into memory and compares, so a
  WZ update without a table regeneration fails that command (the Java tests assert the invariants, not equality, since
  they run without python).
- Auto-QA `t-mp-1` runs the same invariants on a live server (table in the jar, fallbacks load, an image-less map
  still loads nothing); the eyes part of it is the walk in a real client (HALF check). It passed here only once round 4
  fixed the two fallbacks; it re-runs on TEST after the next deploy.

## Status (2026-10-31, branch `162-maproute`)

Built and tested here; **not deployed to TEST yet** (no restart, no deploy in this task) and not merged into
`release-1.6.2` (Server Dev merges). After round 3 the table has 63 routes over 49 blocked maps (38 ids `Map.wz` has
no image for, plus 11 `client-crash`), 24 of them dead ends that keep the character where they are. Regenerate with
both lists so the lane's audit and our seed stay one list:

```
python3 tools/data-align/map_routing.py --wz /root/slums-nav-v70/wz --client-crash /root/work/agent-runs/client/client-crash-maps.txt
```

- Switch: `-Dslums.maps.routing=true` (code default true) in `docs/release/1.6.2.conf` UNIT_EDITS; TEST needs nothing
  because the default is on. Table override: `-Dslums.maps.routing.table=<path>`.
- No migration, no `config.yaml` key, no data file outside the jar: the table ships as
  `src/main/resources/soloMapling/map-routing.tsv`.
- Checks: auto-QA `t-mp-1` (`Probes164.mapRouting`, a HALF check: the probe plus the walk in a real client).
- Known consequence: the bots' graph drops the 11 client-crash maps, so they are no bot destination and every map that
  only led into one (410000325, the Gollux legs, the Rose Garden past the entrance) loses that edge for bots. 410000325
  is an ordinary map again since rc6.
- Round 4: the table is unchanged (63 routes, 49 blocked maps, 24 dead ends), `--check` is current, and the three
  load failures are fixed in the loader, not by moving rows. Nothing deployed: TEST still runs an older jar (60 routes,
  39 blocked), so `t-mp-1` there still reads FAIL until Server Dev deploys.
- Unverified: a real client walking the doors (the crash is client-side), the effect on a live world with moving bots
  (the graph change is visible at boot), and the 229 mob-image maps (a data gap reported, not fixed: only the fallbacks
  and destinations matter to this table and none of them is one).
