# Map routing: never land in a map the server or the client can't load

Owner, 2026-10-31 08:1x UTC, after the client crashed walking into Ristonia's Rampart Residences (410000350) on
TEST: "we should definitely fix the client side crashing though by actually adding correct routing for each map".
Ticket `s-maproute`.

Our server WZ carries maps from newer game versions. Two things can go wrong when a portal, a script, a GM warp or a
saved character points at one of them:

1. **missing** - the map id is not in the server's `Map.wz` at all (`MapFactory.loadMapFromWz` then throws: the image
   data is null). 38 such targets today, e.g. Fountain Square (410000300) `in00` -> Storage Cellar (993162016), and a
   few of them are named by portal, NPC and event scripts too.
2. **client-crash** - the map loads on the server but the v83 client cannot render it, and the client dies on entry
   (410000350 today; the Client lane sends the rest of the list).

Bots already skip (1) (`GCWorldGraph.dropMissingTargets`); players never did. This table is the one list of such
maps and of where a move into one has to go instead, and one resolver all warp paths go through.

## Files

| File | What |
|---|---|
| `src/main/resources/soloMapling/map-routing.tsv` | the table: per-move routes and the blocked maps with their kind and fallback. Generated, hand rows kept. |
| `tools/data-align/map_routing.py` | the generator (WZ -> table), re-runnable, validator included. |
| `tools/data-align/client-crash-maps.txt` | the client-crash list, until the Client lane's file replaces it (seed: 410000350). |
| `src/main/java/server/maps/MapRouting.java` | loads the table, answers `route(from, portal, target)` / `move(chr, target, portal)`, `blocked(id)`, `fallback(id)`, and does the once-an-hour logging. |
| `src/main/java/client/Character.java` | the one enforcement point for movement: `getWarpMap`/`mapInstanceFor`, so every `changeMap*` obeys it. |
| `server/maps/MapManager.java` | the net under it: a map that is still absent after routing resolves to its fallback (once per hour log), so nothing that fetches a map straight from the factory can throw. |
| `soloMapling/ArtificialPlayer/GCMoveSystem/GCWorldGraph.java` | bots' world graph drops blocked targets, so bot route planning agrees with the table. |

## Table format

Two sections, `<TAB>` separated, `#` starts a comment, blank lines ignored.

```
# routes: from_map <TAB> portal_name|* <TAB> target_map <TAB> route_to_map <TAB> route_to_portal <TAB> reason
410000300	in00	993162016	410000300	sp	source-return
410000325	east00	410000350	410000300	sp	return
*	*	910060100	120000000	sp	street-town (script)

# blocked_maps: map <TAB> kind(missing|client-crash) <TAB> reason <TAB> fallback_map
410000350	client-crash	Ristonia : Rampart Residences (client list)	410000300
993162016	missing	Ristonia : Storage Cellar	410000300
```

- `from_map` is the map the move starts on, `*` for "any map" (a script or an NPC that warps from wherever).
- `portal_name` is the source portal's `pn`; `*` when the portal is not known (an NPC script, a GM warp).
- `route_to_map` is where the move lands instead. `route_to_map == from_map` means "stay where you are": the move is
  refused with a short system message, the character stays on its map with actions enabled.
- `route_to_portal` is a portal name on `route_to_map` (`sp` or `0` for its default spawn).
- `reason` is the rule that picked the row, so a human can audit every line (see below).
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
4. `source-return` - the source map's own `info/returnMap`: the way back out of the dead end.
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
- saved-map login: `Character.loadCharFromDB` resolves the map through the factory, so a character saved in 410000350
  logs in at 410000300 and its `mapid` is corrected.

## Switch

`-Dslums.maps.routing=true` (default `true`; it only changes moves that would fail). `false` restores the old
behaviour for a bisect. TEST and the release conf carry the line so nobody has to remember it.
`-Dslums.maps.routing.table=<path>` reads another table (an operator's copy), else the classpath resource
`/soloMapling/map-routing.tsv`.

## Regenerating

```
python3 tools/data-align/map_routing.py --wz /root/slums-nav-v70/wz --client-crash tools/data-align/client-crash-maps.txt
python3 -m unittest discover -s tools/data-align -p 'test_*.py'
```

The generator keeps `# manual` rows, rewrites everything else, and validates its own output: no `route_to_map` is
missing from `Map.wz` or blocked itself, every blocked map has a loadable fallback, every row's portal name exists on
its `from_map` when the row is not `*`. It exits non-zero with the offending rows instead of writing a bad table.

## Tests

- `MapRoutingTableTest` (no WZ) - the table is in the jar and loaded, the two owner maps are blocked with the right
  kind and fallback, Fountain Square's `in00` routes back to itself, 410000325/410000351 `east00` route to the square,
  an unknown source still lands on the fallback, and no route in the committed table lands in a blocked map.
- `MapRoutingWzTest` (skips without `wz/`) - the committed table against the real `Map.wz`: every route lands on an
  image that exists, is not blocked, and has the arrival portal; `missing` rows really have no image and `client-crash`
  rows do; every blocked map's fallback loads; the map manager turns 993162016 and 410000350 into 410000300 while
  ordinary maps pass through; the data still says Fountain Square `in00` targets 993162016.
- `MapRoutingWarpTest` - Fountain Square's `in00` from a character standing there sends the message and
  `enableActions` and does not move; a script warp to 993162016 lands on the square; saved-map moves to 410000350 land
  on the square; an ordinary portal is untouched and sends no packet.
- `GCWorldGraphRoutingTest` - the bots' graph drops edges into blocked maps, on a hand graph and on the real one.
- Drift check: `python3 tools/data-align/map_routing.py --check --wz <wz>` regenerates into memory and compares, so a
  WZ update without a table regeneration fails that command (the Java tests assert the invariants, not equality, since
  they run without python).
- Auto-QA `t-mp-1` runs the same invariants on a live server (table in the jar, fallbacks load, an image-less map
  still loads nothing); the eyes part of it is the walk in a real client (HALF check).

## Status (2026-10-31, branch `162-maproute`)

Built and tested here; **not deployed to TEST yet** (no restart, no deploy in this task) and not merged into
`release-1.6.2` (Server Dev merges). Today the table has 60 routes over 39 blocked maps (38 ids `Map.wz` has no image
for, plus 410000350 from the client list), 18 of them dead ends that keep the character where they are.

- Switch: `-Dslums.maps.routing=true` (code default true) in `docs/release/1.6.2.conf` UNIT_EDITS; TEST needs nothing
  because the default is on. Table override: `-Dslums.maps.routing.table=<path>`.
- No migration, no `config.yaml` key, no data file outside the jar: the table ships as
  `src/main/resources/soloMapling/map-routing.tsv`.
- Checks: auto-QA `t-mp-1` (`Probes164.mapRouting`, a HALF check: the probe plus the walk in a real client).
- Known consequence: 410000325 (Ristonia, the map whose only door leads into Rampart Residences) is now a dead end for
  bots, since the graph drops the edge. Players walking it are taken back.
- Unverified: a real client walking the doors (the crash is client-side), and the effect on a live world with moving
  bots (the graph change is visible at boot).
