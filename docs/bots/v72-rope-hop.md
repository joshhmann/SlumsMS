# v72: rope-to-rope hops with the client's kick (b356/b360, 1.6.6)

Written before the code. Owner-approved shape (Coordinator, 2026-11-07): add-only edges with the client kick, the
executor flies the same kick, GRAPH_VERSION 72. Client's measurement came in at 20:5x UTC on 2026-11-07.

## The measurement (Client, real v83 client, TEST ch3, AqClient speed 100 / jump 100, no buffs)

Evidence: /root/work/agent-runs/client/evidence/b360-reach/ (README.md, pos-lines.txt).

| gap px | map | ropes | real client |
|---|---|---|---|
| 95, 98 | 280010110 (ZPQ) | 454→359, 359→261 | caught |
| 107, 108, 108 | 240010901 | -644→-751, 806→914, 1399→1507 | caught |
| 109 | 680000000 | -614→-505 | caught |
| 117 | 324010000 | 1347→1464 | missed |
| 104 | 240010901 | -161→-57 | missed twice (layout anomaly, below) |

Reach: at least 109 px, under 117 px.

## Expected outcome

1. **One named constant fed by the measurement.** `BotPhysicsEngine.CLIENT_ROPE_HOP_REACH_PX = 109` (the widest catch
   measured). The graph adds a hop only up to `ROPE_HOP_MAX_GAP_PX = CLIENT_ROPE_HOP_REACH_PX - ROPE_HOP_MARGIN_PX`,
   margin 9 px, so 100 px. The margin is under 10% of the reach, covers the sim's 2 px rounding (8 px/tick flies 160
   px/s, not 162), and still lets in ZPQ's 98 px hop. There is no literal 104 anywhere.
2. **The client kick is the measured one**, (±162, −277) px/s (packets, already noted in BotPhysicsEngine). It is used
   by every profile: speed and jump above 100% only lengthen a real player's hop, so a bot never out-jumps a player.
3. **The calibrated sim reproduces the trials.** Client kick, 5 ms sub-steps, exact 162 px/s, the same gravity, fall
   cap and collisions. The air-grab half-width is fitted to the data: catching 109 on 680000000 needs at least about
   9 px, and missing 117 on 324010000 needs under about 20 px. The engine's 8 px misses the 109 catch by under a
   pixel. RopeHopReachTest pins every counted pair above: catches as catches, 117 as a miss.
4. **The 104 anomaly** (240010901 −161→−57): explained from the layout if the sim shows what blocks it, otherwise
   reported as not explained. It is not used to set the reach.
5. **Add-only graph.** A rope-to-rope pair with no transfer edge today gets one when all of these hold: the gap is at
   most `ROPE_HOP_MAX_GAP_PX`; the executor-faithful sim (8 px/tick, −277 px/s, the engine's 8 px grab) catches the
   target from a climb-height window; and the calibrated sim also catches it from the same height. Every edge that
   exists today is unchanged. GRAPH_VERSION 72.
6. **Executor.** A rope-to-rope jump flies the kick its edge was built with. If the old rope jump catches the target
   from where the bot hangs, it flies exactly as before; otherwise it flies the client kick. So every existing edge
   runs exactly as before.
7. **ZPQ 280010110:** the 359→261 hop (98 px) gets an edge. A base (speed 100, no Haste) regular can route from the
   floor to reactor 2112004 (−511, 142), and the room's cage lines in nav-reach-cages.txt come off.
8. **OPQ6 920010700 (r81 lip to the x −43 rope foot):** not a rope-to-rope hop. It is a ground jump off a lip, which
   Client's trials did not measure. The engine's ground jump catches that rope only from x ≥ −104, and r81 ends at
   −107. So v72 does not open it. The Coordinator gets this as a finding needing its own client trial.
9. **Diff:** across every map with WZ data, base profile, v72 against v71 only adds edges (count and list of maps),
   and removes none. nav-reach-cages.txt only loses lines.

## Coordinator's scope and guards (2026-11-07 22:3x UTC), added before the guard code

Scope (a): every map that gains hops. The "16" in the approved shape was the size of a comparison set, not a target.
1. **Fallback switch.** `-Dslums.nav.ropehop=false` (default on; `NavRopeHop.set` at runtime) makes the planner skip
   the hop edges (`Edge.clientKickHop`, set by the hop pass) and the executor fly only the old rope jump. No graph
   rebuild: the edges stay in the v72 graphs, unused.
2. **Per-edge miss log.** A rope-to-rope jump that lands, or grabs a rope other than its target, records
   `NavStuckLog.Kind.ROPE_HOP_MISS` with edge `ROPE_HOP x<from>>x<to> gap <px> client-kick|old-jump`. This covers old
   and new jumps alike, so the TEST soak can compare.
3. **Load failures.** The all-maps diff's 155 failures must not hide a v72-only break.

## Results (2026-11-07)

- All-maps diff, base profile: 11,356 WZ maps; 2,107 have a rope pair within 100 px; **243 maps gain edges, 0 edges
  lost**. The 155 failures are all `ClassCastException` in `loadMapGeometry`, before either graph is built, so they
  are identical for v71 and v72 by construction. Report: RopeHopDiffReportTest (-Dslums.v72diff=true).
- nav-reach-cages.txt 512 -> 505: the 7 ZPQ 280010110 lines opened, none added.
- Calibration bracket: a grab half-width of 9-20 px reproduces every trial; 8 misses the 109 catch, 21 catches the
  117 miss; 10 chosen.
- The 104 px anomaly is not explained (the sim catches it). It is above the 100 px graph limit, so it never gets an
  edge.
