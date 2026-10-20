# 04: Carry nav learning onto the new edge types

**Area:** Nav v2  
**Depends on:** 01  
**Status:** done 2026-10-19 on branch `nav-learning` (includes `nav-ops`), not yet merged

## What

NavEdgeLearning keys and NavObservedEdges overlays must cover the new edge types (teleport and flash-jump windows, rope Y windows).

## Done when

Failure penalties and player-learned jumps work on v68 graphs; tests.

## Result

### Failure learning (`NavEdgeLearning`)

- **Keys:** one key format for every v68 edge type: type, regions, X and Y launch windows, launch step, start and
  landing.
  - Left and up teleports out of one window get separate keys.
  - So do a running and a standing jump, a flash jump and a plain jump, and rope exits that differ only in their
    climb-height window.
  - Files carry `"format": 2`. A format-1 file (old v68 keys) is still read on its own graph.
- **Merged walk edges:** a plan that starts with zero-length walks is committed as one merged edge
  (`collapseLeadingWalkEdges`). Its outcomes now count against the graph's own edge, the one the planner asks about.
  Before this they went to a key nobody read.
- **New outcomes:**
  - **Teleports** count when they land. They used to clear the plan and count nothing.
  - **Launched moves** (jump, flash jump, drop, rope jump) that come down in a third region count as a miss.
  - A mob hit during the flight voids that verdict (the i-frame countdown going up marks the hit).
  - `NavEdgeLearning.observe` now owns the success/miss check that `resolveTarget` used to do inline.
- **Only genuine failures count** (`Cause`):
  - A skill edge the bot's job (or the map's MOVEMENTSKILLS limit) does not allow is `SKILL_UNAVAILABLE` and is
    ignored.
  - A deliberate flaw is `DELIBERATE_FLAW` and is ignored. Both are counted in the GM read-out.
- **Graph version bumps no longer wipe learning:**
  - A file from an older version is migrated: each learned move goes to the new graph's edge with the same type and
    direction, overlapping launch window (within 12 px; for rope moves, the same rope and overlapping climb heights)
    and a landing within 40 x 16 px.
  - Region ids may be renumbered.
  - Moves with no match are dropped. Two moves that land on one new edge are added up.
- **Scale fix:** each graph's book is now cached on the graph (`BotNavigationGraph.learningBook`, transient). Before
  this every planner edge relaxation went through one global `synchronizedMap`. Behaviour is the same.
  - Measured with 32 threads calling `factor()` on a 1,000-edge graph: 15 M lookups/s before, 720 M–1.5 G after.

### Ticket 33 hook

- `NavEdgeLearning.tagDeliberateFlaw(entry, edge)`: call this before executing a fumble. The edge's next failure is
  ignored, whether it is a give-up, an unstuck hop or a landing in the wrong place. The tag is spent by that edge's
  next outcome.
- `NavEdgeLearning.deliberateFailure(graph, edge)`: reports a flaw after the fact.
- Test: `NavEdgeLearningTest.aDeliberateFlawIsNeverLearnedAndOnlyGenuineFailuresRaiseTheCost`.

### Learning from players (`NavObservedEdges`)

- **Capture:**
  - Absolute moves now pass their stance, so a rope or ladder stance counts as climbing.
  - Teleport fragments (move types 3 and 4) and flash-jump fragments (type 6) are reported through
    `onPlayerSkillMove`. They are counted only from a player whose job has that skill: teleport for F/P, I/L and
    Cleric lines and Blaze Wizard; flash jump for Hermit and Night Walker.
- **What is learned:**
  - Plain jumps, as before.
  - Flash jumps and teleports between platforms.
  - Rope moves: jump-grabs, jump-offs and rope-to-rope transfers.
- **Proof before adoption:** each move is re-simulated with the bots' own physics and skills before adoption:
  - `simulateFlashJumpLanding` for flash jumps;
  - `teleportLanding` with the graph's range and snap for teleports;
  - the rope grab and rope jump sims for rope moves.
  - Every move gets the v67 phantom-landing guard.
- **Edges:** adopted edges have the graph's own shapes (a rope launch is a one-height Y window).
  - A flash jump that a plain jump also makes is adopted as a plain jump, so every bot can use it.
  - Skill edges are planned only for jobs that have the skill, through the same `isEdgeUsable` gate as the graph's
    own.
- **Version bumps:** the overlay file now stores the type and which ends are ropes. On a newer graph, each learned
  move whose ends still lie on a platform or rope is kept, re-keyed to the new region ids; one the new graph already
  makes is dropped.
- **Cache directory:** both learned directories now honour `bot.nav.cacheDir`.

### Tests

- `NavEdgeLearningTest`: 10 tests.
  - Every v68 type keyed apart.
  - A merged walk edge counts against the real move.
  - Job-gated failures ignored.
  - A miss in a third region counts, unless a hit caused it.
  - Migration across a renumbering version bump, and dropped when the landing moved.
  - A format-1 file is still read.
  - Deliberate flaws.
- `NavObservedEdgesTest`: 7 tests. The physics tests need `wz/` (`-Dwz-path=`).
  - Capture tells apart flash jumps, teleports, jump-grabs, jump-offs and transfers, and applies job gating.
  - On real town maps, a teleport, a flash jump, a rope jump-off and a jump-grab each:
    - are removed from the graph and re-learned from two sightings with their own type;
    - are planned only for the right jobs;
    - carry over a version bump;
    - are dropped when the new graph has the move itself.
- Full suite: 2,572 tests, 0 failures. With `wz/`, all 73 GCMoveSystem tests pass.

### Not done

- No headless soak on 164. It is covered by the physics tests above.
- Swim edges (ticket 34) will plug into the same `Cause` / `observe` path when they exist.
