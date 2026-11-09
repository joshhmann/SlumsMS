# zpq-stage6-offgraph: no route verdict mid-air, and no count carried into a new target or map (S, 1.6.6)

Written before the code. Coordinator GO 2026-11-08 ~05:0x UTC. Base: release-1.6.6 @ 84a97be27.

## What happened

QA's ZPQ v72 proof on TEST e12c9041f (run zakumpq-full-run-20261108T042242Z, 5 LOD pins) cleared stages 1-5, then
ended 0.25 s into stage 6 (feed seq 720 -> 721): "TacoOrbis can't reach reactor 2112012 at (1874, 140) on 280010130:
the start (-262,78) is off the graph (no ground or rope)".

Two faults, and the run needed both to end:
1. **A check taken mid-float.** TacoOrbis came through hub in13 to 280010130 west00 at (-262,135); the floor there is
   y=138. On an observed map, `GCMovementDriver` lifts a bot that comes through a portal to floor - 60
   (`PORTAL_FLOAT_HEIGHT_PX`) and holds it there 1.5-2.1 s before the drop. That puts it at (-262,78). `NavProbe`
   finds no region under a floating bot and answers "off the graph".
2. **A count carried over.** `StepExecutor.checkRoute` gives the stage up on the second "no route" in a row. The
   count (`Beat.noRouteTimes`) is not reset when the target or the bot's map changes. TacoOrbis already held one
   "no route" from before stage 6 (it spent 103 s on the hub waiting for the door to the mine), so the first check
   of stage 6 made two.

Neither is a nav graph, v72 or coarse-snap fault, and 1.6.5 has the same code (it never hit this combination).

## Expected outcome

1. While the bot is in the air, or in its portal float (`inAir || portalDropAtMs > 0`, read through a new
   `GCMovement.isAirborneOrFloating`; the existing `isAirborne` leaves the float out and has other callers), `checkRoute` takes no verdict and does not use up its 10 s slot. The next check after
   it lands judges the route.
2. The "no route" count starts again from zero when the reactor point or the bot's map changes since the last check.
   This sits beside the distance mark, which already resets on a new target.
3. A real stall is still given up on: two grounded "no route" checks 10 s apart on the same map and target, with no
   progress, as before. The existing StageStallGuardTest cases still hold.

## Proof (StageStallGuardTest, each fails before the change and passes after)

- **A check while airborne doesn't count.** A float-time "no route" followed by one grounded "no route" 10 s later
  does not end the run.
- **A leftover count doesn't carry over.** One "no route" on the hub (another map), then the first "no route" on the
  mine map, does not end the run. A second grounded one 10 s later on the mine does.
