# 1.6.6 nav-cages-triage: Zakum, Kerning, Ludi and Orbis PQ

Made on 7 Nov 2026 (UTC) on `release-1.6.5` @ 202bbafe4 (graph v71), branch `166-bots-nav-cages`.
Source: `PqBossReachTest` (`target/nav-reach-report.txt`) plus a per-cage look at the regions and edges around each
target. A cage is a (map, start portal, reactor/NPC) pair that a plain walker (no teleport, no flash jump) can't reach.

## Step 1: the probe takes scripted in-map doors (done on this branch)

The maze rooms are joined only by portals whose script warps within the map (Ellin PQ 930000300: 46
`party6_stage5xx` doors; OPQ 920010700: 64 `party3_r6pt` doors; Treasure PQ 674030000: the `guyfawkes0_floor` fall
trap). The plain graph has no edge for them, so every room past the first counted as a cage.
`PqBossReachTest.MazeWalk` reads each door's script (`warp(<this map>, <portal>)`; a computed portal means "any door of
the same script or any in-map landing") and accepts a target that a walk-door-walk chain reaches.

Listed cages: **1,846 → 512, none added.** Ellin PQ 1,007 → 15, OPQ 427 → 87.
Test `roomsBehindScriptedInMapDoorsAreNotCages` failed before the change and passes after.

## Step 2: the cages left in the four PQs

"Needed" means the stage's solver or row uses that reactor or NPC. "TEST" is what the QA feeds show. No feed under
`/root/work/qa-runs` has ever recorded an OPQ or LPQ run, so those rows are probe-only.

| # | Card | Map / stage | Cages | Needed? | Cause | TEST | Weight |
|---|---|---|---|---|---|---|---|
| 1 | **nav-opq6-rope** | OPQ 920010700, stage 6 "The five levers" | 14: from `sp`/`st00` to Eak 2013001, stone6 and all five levers | **Yes, the whole stage** | The only way up from the entry floors (y −846 and below) to Eak's floor (y −1625) is the rope at x −43 (y −1623 to −878). Nothing in the graph grabs its bottom: the nearest floor, r81 x[−310,−107] y −846, ends 64 px left of the rope, 32 px below its foot. Not even teleport or flash jump reaches it. | never run | **High**: bots can't start the lever stage |
| 2 | **nav-opq8-scar1** | OPQ 920010100, stage 8 "The statue and the garden" | 11: every start to scar1 2008000 (−164,−1064); plus 48 from the six `hiden0x` portals | **Yes, scar1 takes a statue piece** | The scar1 shelf r14 (y −1040) has only teleport/flash-jump edges in. Below it, r17 at y −897 is 143 px down; r11 above is reached. The `hiden0x` starts are the hidden way back from the garden. They are one-way landings, not a real player start: no card. | never run | **High** if the piece must be dropped from on the shelf. Check `PlaceItemOn` reach first. |
| 3 | **lpq9-alishar-reactor** | LPQ 922010900, stage 9 "The key" | 2: `sp`/`st00` to reactor 2201003 (599,0) | **Yes** | Two faults. (a) The shelf r3 x[554,636] y 0 has no edge in at all, even with skills; the floor below is 184 px down. (b) Separately, `lpq.yaml` says `HIT_REACTOR 2201003`, but Reactor.wz 2201003 state 0 is an **item reactor** (type 100, takes 1 × 4001022). Its script summons Alishar (9300012) on 922010900 and Rombard on 922010700. Hitting it does nothing. | never run | **High**: the stage can't summon its boss. (b) is a row/solver bug, not nav. |
| 4 | **nav-lpq7-boxes** | LPQ 922010700, stage 7 "The three bosses' passes" | 9: every start to the three boxes 2201002 at y −1037, −1263, −1535 | **Yes** (`boxes` HIT_REACTOR, role all) | The box shelves have only flash-jump edges in. The floor r9 (y −774) is 263 px below the first one. The same map's 2201003 item reactor (Rombard) is not in the cage list. | never run | **Medium**: three passes also come from the bosses, so the stage may clear without the boxes. The row counts both. |
| 5 | **nav-opq-lobby-lp** | OPQ 920010400, stage 3 "The music player" | 6: `sp`/`st00` to lp boxes 2002004, 2002006, 2002007 | **Yes**: the solver hits every lp box because which one drops today's pass is unknown | Box shelves r6 and r1 (y −907/−924) are reached only by the rope at x −762 (y −905 to −751). The rope's foot sits on r14 x[−847,−771] y −751, and nothing walking reaches r14 either. | never run | **Medium**: on days the pass is in one of these three boxes, the stage stalls |
| 6 | **nav-zpq-110-shelves** | ZPQ 280010110 (stage 2) | 7: `sp`/`east00`/`west00` to chest 2112004, rock 2111000, 2118001 | Yes for the ore chest 2112004 | The y 138 shelves are reached only by ropes. Their feet (x −332, −38, 178, y 119–126) hang 132–139 px above the floor at y 258, and the graph has no jump-grab that high. **But TEST did it:** in run 060003Z, AqClientAlt1 hit 2112004 four times (b344d's note in `StepExecutor.checkRoute`: "reached it … by a way the graph does not hold"). So the planner misses a real way, not the other way round. | **cleared** (stage 2, 44 s) | **Low**: b344d already copes. Fix the graph so the planner agrees (needs the client's real jump-grab height first). |
| 7 | (no card) | ZPQ 280010011 | 4: to 2112009 (−308,440) | No: the row's chest is 2112012 | Shelf with only teleport/flash-jump edges in | — | Note only |
| 8 | (no card) | KPQ 103000804, stage 5 | 4: from the `in00`/`in01` warps to Nella 9020002 (−2,−2715) | No: stage 5 uses Cloto 9020001 at (305,−2715), which is reached; the exit NPC is on 103000805 | The top-left shelf is cut off by a 114 px gap at the same height. `sp` stands on it, so only a bot that has gone down can't come back. | KPQ clears on TEST | Note only |

OPQ 920010300, the storage room (8 cages, reactors 2001005/8/11/14 on the tower shelves), is not in the table. Its
`opq-sweep` solver never hits those boxes: it kills and loots. It only matters if a drop lands on one of those shelves,
which is possible. No card unless a TEST run shows the sweep stalling there.

## Order I'd take them in (1.6.6)

1. **lpq9-alishar-reactor (b)**: a row/solver fix (place 4001022 on 2201003 instead of hitting it), fail-before in
   `LpqStageDataTest`. Small, and the stage is dead without it.
2. **nav-opq6-rope**: graph edge from r81's lip onto the x −43 rope foot (64 px across, 32 px up). This is rope
   physics, so it needs a check against the client's grab reach before any v72 change.
3. **nav-opq8-scar1**: first see how close `PlaceItemOn` must stand. If a drop from r17 below lands in the reactor's
   area, it isn't a nav fix at all.
4. **nav-opq-lobby-lp** and **nav-lpq7-boxes**: graph edges, after 2.
5. **nav-zpq-110-shelves**: graph only, after the grab height is measured (TEST already proves the way exists).
6. lpq9-alishar-reactor (a): the shelf with no edge at all. If players drop the pass from the floor below, then (b)
   alone fixes it.

Every graph change bumps `GRAPH_VERSION`, regenerates `nav-reach-cages.txt` on the release head, and should only
remove lines.
