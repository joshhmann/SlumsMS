# 34: Swim navigation (Aqua Road)

**Area:** Nav v2
**Depends on:** 01 (graph v68), 03 (travel to Aqua Road), 04 (learning)
**Status:** open

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
