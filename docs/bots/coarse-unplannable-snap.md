# b370 coarse-unplannable-snap, 1.6.6 part: count it (XS)

Written before the code. Coordinator, 2026-11-08 ~02:2x UTC: "in 1.6.6 add only the counter (per caller, per map),
decide the behaviour change in 1.6.7 from TEST counts". Base: release-1.6.6 @ 7f8d4e1d5.

## What happens today

A regular nobody is watching moves in COARSE mode (`GCMovementDriver.tryCoarseAdvance0`). It plans with
`MovementPlan.inMap`; when that returns null the driver calls `arriveCoarse(target)`, which puts the bot on the ground
at its goal. `inMap` returns null in three cases:
- the bot and the goal are in the same graph region (no edges to walk): a real arrival, fine;
- the bot's or the goal's point is in no region of the graph (`findRegionId < 0`);
- the two regions have no route between them (`findPath` empty).
The last two are the snap: the bot reaches a goal it has no way to walk to. The b344 proof run reached the 11-1 key
this way (280010110, east00 -> reactor 2112004).

## Expected outcome

1. Every snap is counted in the nav stuck log as a new kind, `NavStuckLog.Kind.COARSE_SNAP`, at the goal's 50 px spot
   on the bot's map, with the edge label `COARSE_SNAP <why> <caller>`:
   - `<why>` is `no-path`, `no-region-start` or `no-region-goal`;
   - `<caller>` is the class and method that last set the bot's move target through `GCMovement.move` (for example
     `StepExecutor.moveNear`), else `follow`, `farm` or `other` from the movement state.
2. A same-region arrival is not counted.
3. Behaviour does not change: the bot still arrives exactly where it does today. The caller is captured only when
   `GCMovement.move` gets a new target (not on a re-issue of the same point), so the cost is one short stack walk per new
   target.
4. The counts land where the other stuck kinds do: `<cache>/stuck/<day>.json` every five minutes, and
   `NavStuckReport`. The TEST soak reads COARSE_SNAP per map and caller from there.

## Proof

- A unit test drives `tryCoarseAdvance0` through the snap (a goal in a region with no route) and through a same-region
  arrival: before, nothing is recorded for either; after, exactly one COARSE_SNAP with `no-path` and the move's caller,
  and none for the same-region arrival. The bot's end position is the same before and after.
