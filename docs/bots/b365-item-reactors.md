# b365: bots place the item instead of hitting an item reactor (1.6.6)

Card: b365 — Mechanics rows send bots to HIT item reactors (type 100), a server-side change no client could make.
Status: expected outcomes written before code (1.6.6 pilot). Research on `release-1.6.5` @ c785fe504, 7 Nov 2026.

## What a hit does today

`Reactor.hitReactor` on a type-100 state moves to the next state. For a single-state item reactor, the next state's
`getNextState` is −1, so the hit counts as the end state and `act()` runs. The script fires without the item.
A player's client never sends a hit on a type-100 reactor. Only the item drop opens it
(`MapleMap.activateItemReactors`, 5 s after a matching stack lands inside the reactor's box; mob drops count too).
Every resulting state exists in the WZ data, so this is not b331's crash class.

## Row by row

The item source columns come from TEST `drop_data` / `reactordrops` (SELECT only) and the scripts.

| Row (stage) | Reactor → item | Where the item comes from | Today | Expected after b365 |
|---|---|---|---|---|
| lpq 9 | 2201003 ← 4001022 pass | shelf mob 9300006 drops it on the reactor | hit | **b359**: no hit; the pass from 9300006 summons Alishar on its own |
| lpq 7 | 2201002 ← 4001156 | Ratz from Another Dimension 9300169-71 (`drop_data`) | hit (`boxes`) | **b363**: farm 4001156, place 1 on 2201002 → "Rombard has been summoned somewhere in the map." → his pass |
| ellinpq 2 | 3009000 ← 4001162 Diluted Poison ×1 per state, 4 states | reactor 3002000 (`reactordrops`) | hit 4× (ReactorStateSolver) | hit 3002000 for the poison, place 1 per state (4 placements), each with its 5 s wait; the feed shows 3009000 reaching state 4 by placements; never "hit reactor 3009000" |
| magatiapq / magatiapqz 3 | 2618000 ← 4001132 Suspicious Liquid, state by state | Homunculus 9300147 | hit (ReactorCountSolver) | farm 4001132, place one per state; the stage's count rises per placement as the script expects; never "hit reactor 2618000" |
| cwkpq 3 | 6109005-9 ← 4001256-60, one per sigil (per job) | stage NPCs 9201109/9201110… give them (`gainItem`); 4001260 also from reactor 6102001 | hit (CwkpqSigilSolver) | each sigil's member places its own job item; glpq4 still counts to 5. **Open question:** do bots already collect these items in the earlier CWK stage? If not, this row needs that step first, or stays an `opensByHit` exception |
| latanica 1 (boss) | 5411000 ← 4000381 White Essence | the entry item: `captinsg00.js` won't let anyone in without it, and `missingEntryItems` already waits for it | hit (BossPhasesSolver "open the room") | the holder places 1 × 4000381; Krexel's script runs from the drop. Cheapest row: the item is already in hand |
| papulatus 1 (boss) | 2201004 ← 4031179 Piece of Cracked Dimension | quest 7103 (`qm.gainItem`) | hit | `opensByHit: true` unless the bots do quest 7103. A real player drops the piece; bots can't get it today |
| scarga 1 (boss) | 5511000/5511001 ← 4032246 Spirit of Fantasy Theme Park | NPC 9270047 (expedition item) | hit both | if the expedition NPC gives the bots the item, place 1 on each; otherwise `opensByHit: true` |
| showa 1 (boss) | 8001000 ← 4000138 Lady Boss's Comb | Yakuza Underboss 9400121 | hit | farm or hold 4000138 and place it; otherwise `opensByHit: true` |
| zakumpq 33 | 2112014 ← 4001016 ×7 | rocks in the mines | **places** (PlaceItemOn) | unchanged: the model to copy |

## The guard (last, fail-before)

`StepExecutor.clientCanHit` refuses a hit when the reactor's current state is type 100, unless the stage row says
`opensByHit: true`. With that flag, the feed line says so ("opens by hit: no item for it", in the mechanics feed
only; players never see it). Fail-before: a HitReactor on a state-0 type-100 reactor is refused and logged, and the
script does not run. Rerun ZPQ's tests and the boss-phase tests to show the 1.6.5 proofs still hold.

## Order

1. latanica: the item is already in hand.
2. ellinpq and magatiapq(z): farmable items, existing PlaceItemOn.
3. lpq 7 (b363).
4. cwkpq: needs the item-collection question answered first.
5. papulatus, scarga, showa: decide `opensByHit` with the Coordinator. These are player-visible bot boss runs.
6. Guard.

Every row gets its own fail-before test and its own feed expectation, and none of it goes into release-1.6.5.

## Follow-up (not built): the reverse static check

ellin-altar (7 Nov) was the opposite of a hit. The stage needed an item placed on an item reactor, and nothing in
the row placed it, so the room stayed empty until the clock ran out. `ItemReactorHitScanTest` cannot see that, because
it looks for hits.

A reverse check is feasible as a WZ test like the scan:
1. For each stage, list the reactors its map places (Map.wz `reactor`) whose state 0 is type 100 and whose
   `scripts/reactor/<id>.js` has an `act()` that does something: spawns, sets a property, drops or clears.
2. Fail when the row has no PLACE_ITEM objective naming it, and no solver constant or row field names it as a
   placement (PlaceItemOn/PlaceItem by id, name or position).
3. Keep a written allowlist for reactors that are deliberately not the bots' job, e.g. OPQ's 2002003 Grass pot before
   b365, or decorative ones. Each allowlist entry needs a reason.

Expected noise: maps with item reactors the stage never needs, such as LPQ stage 7's 2201003 on 922010700. That's
what the allowlist is for. Size: about one test class plus the allowlist; the first run tells us how many entries it
needs.
