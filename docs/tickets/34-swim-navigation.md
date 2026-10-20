# 34: Swim navigation (Aqua Road)

**Area:** Nav v2
**Depends on:** 01 (graph v68), 03 (travel to Aqua Road), 04 (learning)
**Status:** done 2026-10-20 on branch `swim-nav` (from `test-rc1`), not yet merged; needs an in-game look

## Today

- **Swim physics exist:** `BotPhysicsEngine`'s swim integrator is fitted from captured swim packets and the
  client's CalcFloat/JustJump.
- **No swim nav graph:**
  - On a swim map (`MapleMap.isSwim()`), `BotNavigationManager` skips the nav graph.
  - `BotFallbackMovementManager` steers straight at the target, with a leap upward into the water when the target
    is above.
  - That is enough to follow a player. It gets stuck behind walls, rocks and overhangs when a bot grinds Aqua Road
    on its own.

## What

- **Swim graph:**
  - Sample the water volume of each swim map on a grid (a few dozen pixels apart), skipping points inside
    terrain/foothold walls.
  - Connect neighbours with SWIM edges when a straight swim between them is clear. Use the swim physics to set the
    cost (rising is slower than sinking).
  - Link the graph to the map's platforms with land/take-off edges.
- **Planning:** A* over swim points plus platform edges, with path smoothing so bots swim in curves, not grid steps.
- **Execution:**
  - The existing swim integrator follows the waypoints, using the same key states a player would (UP, DOWN, burst).
  - Re-plan if a bot drifts off the path.
- **Learning:** swim edges join `NavEdgeLearning` (failures raise the cost), and players' swim paths can confirm
  shortcuts, like the jump learning in ticket 04.
- **Nav lab (04a):** survey Aqua Road before opening it in the leveling path (ticket 31).
- **Flaws (ticket 33):** swimming wobble and overshoot instead of missed jumps.

## Done when

Bots grind Aqua Road maps (e.g. the 50–110 spots in `docs/real-bots/leveling-path-research.md`): they reach mobs,
use platforms, and return to town, without wall-hugging or getting stuck. The stuck report (04c) shows no swim
hotspots.

## Result

### What changed

- **Swim graph (`SwimNavGraph`, new):** built per swim map in the background on first use (0.15-1 s per Aqua Road
  map; 1,400-3,700 water nodes, 160-610 platform nodes, 22-59k edges, kept compact: per-node int arrays).
  - Water nodes on a 40 px grid, 12 px clear of every foothold, with ground somewhere below, and no platform less than
    36 px above (that is inside a slope or under a thin ledge: rock in the map's art).
  - Platform nodes every 40 px on each floor plus both ends, skipped where another floor is less than 50 px above.
  - Edges: `SWIM` water to water (16 directions) when the swim integrator's own wall / ceiling / floor sweep is clear
    on the centre line and 8 px to each side; `SWIM` take-off (platform to water above) and landing (water to
    the platform a sink lands on); `WALK` along a foothold chain.
  - Costs in ms from the swim physics (`speeds`, the integrator's constants run tick by tick): sideways 140 px/s,
    sinking 210 px/s (DOWN held), rising 312 px/s (a burst every 500 ms with UP held; rising is faster than
    sinking in v83, not slower as the ticket guessed), walking 120 px/s. Rising up through a platform costs x1.6.
    A take-off costs a fixed 400 ms so a bot walks a short way along the sea floor instead of hopping in for every
    errand.
- **Navigator (`SwimNavigator`, new):** `BotNavigationManager.resolveTarget` hands swim maps to it.
  - A* over the swim graph with learned costs; a straight swim when the target is close and reachable.
  - Path smoothing: while swimming the bot steers at the furthest of the next 8 points it can reach. "Reach" is the
    straight line plus the path the controls really take (height first, since vertical speed beats the sideways
    cap), so it does not cut onto a ledge corner.
  - Execution uses the existing controls: `computeSwimIntents` (left/right, UP/DOWN held, bursts) in the water, the
    ground walk on platforms, and a normal ground jump to take off.
  - Re-plans when the target moves 48 px, after a teleport or knock-back, after landing on the wrong platform, and
    after 1.2 s out of sight of its point. Backs off to the last point if it cut a corner behind a rock.
  - Stuck: 2.5 s without getting closer to its point, or a goal taking more than 3x its planned time + 5 s (going in
    circles). Counted as `SWIM_STUCK` in the stuck log (04c), a failure of that edge in learning, and the edge is
    banned for that bot for 20 s.
  - Tells the driver's no-progress give-up when the route is moving (a detour over a rock moves away from the
    target in a straight line and used to be abandoned).
  - Ticket 33: watched bots wobble up to 8 px per waypoint (scaled by their flaw skill).
  - Unwatched bots: the coarse planner uses the swim route on swim maps.
  - `-Dbot.swimnav.enabled=false` turns it all off (the old straight-at-the-target fallback).
- **Learning (`NavEdgeLearning`):** new edge type `SWIM` (`BotNavigationGraph.EdgeType`, never in a ground graph).
  A swim section with its own book per map (`<mapId>-swim.json`), same Stats, factor curve, Cause filter (a tagged
  flaw is not counted), file format and flusher. Dropped when `SwimNavGraph.VERSION` changes (node ids move).
- **Players (`SwimNavObserved`, new):** `NavObservedEdges.onPlayerMove` forwards swim maps. A real player's swim trail
  confirms known edges (a make that pays off bots' failures) and, after two sightings, adopts a clear gap the graph
  did not join (kept in `observed/<mapId>-swim.json`).
- **Physics (`BotPhysicsEngine`):** two read-only queries, `swimSegment` and `swimLandingFoothold`, over the existing
  collision sweep. The swim integrator and the movement packets are unchanged.

### Movement packets

Not changed. A swimming bot still sends one absolute fragment (type 0) per tick with the swim stance (12/13) and its
swim velocity, as fitted from the captured swim packets the integrator was tuned on. No new swim captures exist on the
164 test install (attack-capture has no Aqua Road rows and monitored-packets.log is empty), so nothing new to compare.

### Evidence: SwimNavLab (offline, real physics)

`SwimNavLab` (test sources) runs bots headless on swim maps with the full driver, forced FULL. 21 swim maps (Aquarium
and all of Aqua Road), 4 bots each, 8 minutes, the same seeds and errands in both modes: half platform points, half
open water, all reachable in the swim graph.

| | before (straight steering) | after (swim graph) |
|---|---|---|
| attempts | 4,111 | 4,299 |
| reached | 88.8 % | 99.4 % |
| abandoned (driver gave up, stuck) | 9.5 % | 0 % |
| not there after 60 s | 1.7 % | 0.6 % |
| mean time per reached trip | 8.2 s | 8.9 s (more of the hard ones reached) |
| worst map | 230020101: 71 % reached | 230030000: 96 % |
| stuck log | (the fallback logs nothing) | 60 SWIM_STUCK in 11 bot-hours, no spot over 2 |

The deep gorges (230040000-400, the 90+ grinding maps) went from 83-90 % to 99-100 %.

### Tests

- `SwimNavGraphTest` (7, synthetic swim map, no `wz/` needed): costs from the swim constants; the rise model matches
  the real integrator within 15 %; the route goes over a rock pillar, takes off and lands, and walks along the floor;
  no water node under a ledge; failures raise a swim edge's cost, a tagged flaw does not, and the book survives a
  restart; a player's swim adopts a gap after two sightings and pays off failures; a bot swims over the pillar end
  to end with real physics.
- Full suite: 2,930 tests, 0 failures (6 skipped). With `-Dwz-path`, all 113 GCMoveSystem tests pass.

### Not done

- No in-game look and no scratch server run. Grinding itself was not run on Aqua Road: the grind brain picks ground
  points (`groundPointBelow`) and leash segments on platforms, which the swim navigator routes to, but whether bots
  hit swimming mobs from there is combat's business (and see the remote-attack check: attacks while swimming need a
  real-client look).
- Not opened in the leveling path (ticket 31): that is the owner's call after the look below.

### What to watch for in game (Aqua Road, e.g. 230040000 Deep Sea Gorge I)

- Bots swim in smooth curves around rocks and over the gorge walls, not grid steps, and do not grind against a
  wall or the underside of a ledge.
- Swim animation (swim stance, facing the way they move) while in the water; a small jump when leaving the sea
  floor; walking (not swimming) for short trips along a floor; landing cleanly on platforms.
- Rising looks like a player pressing jump repeatedly (bursts), sinking is smooth; no teleport-like snaps.
- Bots never sit inside rock art (a node under a slope or ledge was the old way in).
- `!gcmove stuck` / the daily stuck report: no SWIM_STUCK hotspot repeating at one spot.
- Bots attacking while swimming: does the real client show the attack, or drop it (wrong pose)?
