# Map routing: never land in a map the server or the client can't load

Owner, 2026-10-31 08:1x UTC, after the client crashed walking into Ristonia's Rampart Residences (410000350) on
TEST: "we should definitely fix the client side crashing though by actually adding correct routing for each map".
Ticket `s-maproute`.

Our server WZ carries maps from newer game versions. Two things can go wrong when a portal, a script, a GM warp or a
saved character points at one of them:

1. **missing** - the map id is not in the server's `Map.wz` at all (`MapFactory.loadMapFromWz` then throws: the image
   data is null). 32 such targets behind 50 unscripted portals today, e.g. Fountain Square (410000300) `in00` ->
   Storage Cellar (993162016), plus 16 more behind portal and NPC scripts (Evan's rooms, the dragon mounts).
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
| `src/main/java/server/maps/MapRouting.java` | loads the table, answers `plan(from, portal, target)`, `blocked(id)`, `fallback(id)`, and does the once-an-hour logging. |
| `src/main/java/client/Character.java` | the one enforcement point for movement: `getWarpMap`/`mapInstanceFor`, so every `changeMap*` obeys it. |
| `server/maps/MapManager.java` | the net under it: a map that is still absent after routing resolves to its fallback (once per hour log), so nothing that fetches a map straight from the factory can throw. |
| `soloMapling/ArtificialPlayer/GCMoveSystem/GCWorldGraph.java` | bots' world graph drops blocked targets, so bot route planning agrees with the table. |

## Table format

Two sections, `<TAB>` separated, `#` starts a comment, blank lines ignored.

```
# routes: from_map <TAB> portal_name|* <TAB> target_map <TAB> route_to_map <TAB> route_to_portal <TAB> reason
410000300	in00	993162016	410000300	sp	street-town
410000325	east00	410000350	410000300	sp	return

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
Plan plan = MapRouting.plan(fromMap, portalNameOrNull, targetMap); // null = the move is fine as it is
```

`Character.getWarpMap(int, String)` and `mapInstanceFor(int)` call it, so every `changeMap*`/`forceChangeMap` caller
(portals, script and NPC warps, `!warp`/`@goto`, event instances, saved-map login, bots' travel) lands on the route.
`MapManager.getMap` calls `MapRouting.fallback(id)` when a map is still absent after that, so a map that reaches the
factory directly cannot throw; it resolves to the fallback and logs.

Behaviour:

- a rerouted move is logged once per (from map, target map) per hour: `log.info` plus a `QaLog` line on the
  `MAP_CHANGE` topic, so QA runs and TEST logs show real traffic:
  `map routing: 410000300 in00 -> 993162016 rerouted to 410000300 (street-town)`.
- a route to the source map itself is a dead end: the character gets one short system message (the target's name from
  `String.wz` when it is known) and `enableActions`; it does not move and does not get stuck.
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

- `MapRoutingTest` - table parsing, resolution order, dead end, switch off, once-an-hour logging key.
- `MapRoutingWzTest` (skips without `wz/`) - the committed table against the real WZ: no route into a missing or
  blocked map, every blocked map has a loadable fallback, every portal row names a real portal; the generator's output
  for the same WZ equals the committed table (so a WZ update without a regeneration fails the build).
- `MapRoutingWarpTest` - Fountain Square's `in00` no longer throws and routes per the table; a script warp to a blocked
  map is rerouted; a character saved in 410000350 logs in at the fallback; the world graph has no edge into a blocked
  map.
- `!qa` scenario `route-1` (Fountain Square left portal, Rampart Residences from 410000325) plus the auto-QA check that
  the table is in the jar and every blocked map has a fallback.
