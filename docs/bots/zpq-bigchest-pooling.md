# zpq-stage33-bigchest: pool the keys, and stop waiting for keys nobody will bring (1.6.6)

Base: release-1.6.6 59081d6fb. Card: zpq-stage33-bigchest (S). Closer: Bots. Proof: ZakumPqSolversTest (fail-first)
plus QA's next ZPQ run on TEST.

## What went wrong

The big chest 2112014 on 280011005 takes ONE drop of exactly 7 keys (4001016): `MapleMap.activateItemReactors`
compares the drop's quantity with Reactor.wz's `1=7`. The maze has exactly 7 key rocks, and the regulars mine them
between them. On TEST the 7 keys ended up split 3/2/2/0/0, so no bot ever held 7. Each one said "this chest wants
seven keys and I have N" for good.
- The b344c no-progress guard does not count a waiting bot as stalled, so only the PQ's 30-minute timer ended the run.
- Server Dev confirmed it was stuck, not slow.

## Expected outcome (written before the code)

On the big chest's map, while the chest is closed (state 0):
1. **A bot with 7 or more keys** places 7 on the chest, as today.
2. **Pooling.**
   - The carrier is the regular on this map holding the most keys (ties: the lowest character id).
   - Every other regular on this map that holds keys drops them all for the carrier. It uses the existing hand-over,
     `SolverStep.DropFor` (the same one KPQ passes and the GQ marks use).
   - `PassHandover.mayTake` already keeps a hand-over for its owner, so no other bot picks the keys up. The
     carrier's existing Loot step takes them.
   - A partial drop can never open the chest by accident, because the chest wants a drop of exactly 7.
   - Real players' keys are never asked for and never counted as the carrier's.
3. **No endless wait.**
   - The first time a member waits for keys, a stage timer starts. It is shared by the party, in stageMemory.
   - If the chest is still closed `KEY_WAIT_MAX_MS` later (3 min, the same as Orchestrator.NO_PROGRESS_MS), the
     waiting member gives the stage up with `Abandon("... the party has N of the 7 keys the big chest wants")`.
   - Like the b344c guard, that leaves the PQ to the players. No crash, nothing lost.
4. **Two observe-only feed lines** (MECHANICS feed), in the wording of Server Dev's boss-feed-lines plan
   (agent-runs/serverdev/plans/boss-feed-lines-167.md), so there is one line per event and work-62's stage-33
   checker matches it:
   - `RUN zakumpq big chest 2112014 opened on 280011005 by <placer> after <S> s into the run [zakumpq, leader X]`: once per stage,
     when the chest leaves state 0. S is seconds since the run started. Emitted from StepExecutor.execute through
     ZakumMinesSolver.chestOpenedLine.
   - `RUN zakumpq fire ore 4001018 looted by <bot> [zakumpq, leader X]`: once, when a run bot's Loot step really picks
     it up (StepExecutor.fireOreLine).
   Names are character names, as in every other MECHANICS line (PLACE-ON, DROP) and the leader tag.
   The giving-up line is the existing run-end line (MechanicsRuns): `RUN end zakumpq in <instance>: the big chest on
   280011005 still wants keys after 180 s: the party has N of the 7 keys the big chest wants`. The Abandon ends the
   bots' run (Orchestrator.fail), as any solver Abandon does.
5. Unchanged:
   - an opened chest (state ≥ 1) is looted and left;
   - keys lying in front of a closed chest get the 10 s take window;
   - the hand-in.

## What existing characters keep

Nothing is taken from anyone:
- keys move only between regulars in the same run;
- a real player's inventory is never read for pooling, nor written;
- no data or schema changes, so no migration is needed.

## Tests (ZakumPqSolversTest, each fails on 59081d6fb)

- `keysSplitAcrossRegularsArePooledOnTheOneHoldingMost`: 3/2/2/0. The 2s drop for the 3. The 0 waits. The 3, still
  short, waits for them.
- `aTieForCarrierGoesToTheLowestId`.
- `aRealPlayersKeysAreNeverAskedForNorMadeCarrier`.
- `waitingForKeysNobodyBringsGivesTheStageUpAfterTheLimit`: 6 keys in the party. Wait before 3 min, Abandon after.
- `theChestOpeningAndTheFireOrePickupAreFedOnce`: the two feed lines, once each, and only for these events.
