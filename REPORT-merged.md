# Merge lane, 1.6.4 - everything in one tree (branch `164-all-merge`)

Worktree `/root/work/164-all`, branch `164-all-merge` off `release-1.6.4` @ `6d18f961e`, push URL disabled, nothing
pushed, nothing deployed, live server untouched. **Merged head `39276532a`**, 163 commits over the release tip.

This is the one branch that carries every 1.6.4 lane: the base game's two rows, the boss lane's 27, the party-quest
lane's 17, the real-run lane's ten, and the engine-seam lane's Guild Quest - **56 rows, 29 `kind: BOSS` + 27 `kind: PQ`,
195 stages**, which is exactly the number of `*.yaml` files under `src/main/java/soloMapling/mechanics/content/` and the
number of lines in `content/index.txt`.

## What this branch is made of

| # | Source | Merge commit | Result |
| --- | --- | --- | --- |
| 1 | `release-1.6.4` @ `6d18f961e` | - | the deployable tree |
| 2 | `164-bossrows` @ `b258879dd` | fast-forward (`b258879dd`) | that branch already carries the release tip: 27 boss rows, `spawnWaitSeconds` / `entryItems` / `hitReactors`, the `boss-phases` solver, `ContentRowWzIntegrityTest` |
| 3 | `164-opq` @ `90e40c1fd` | `0cecfa06e` | 17 PQ rows (Orbis, Ludi, Henesys, the six Cafe areas, LudiMaze, Pirate, Treasure, DelliBattle, Magatia, Ellin, Crimsonwood Keep), their solvers, `StageView.eventCleared()`, the PQ lobby |
| 4 | `164-run-chatter` @ `05b7db0c4` | `bd83eed78` | the run's win/loss chat line (`slums.mechanics.chatter`, default on) |
| 5 | `164-altinvite` @ `3b546e834` | `8df00eaa8` | an owned alt answers its owner's family and guild invites (e02) |
| 6 | `164-ex2` @ `0dd9dfc83` | `141f7b77d` | the `ex-2` QA scenario, resolved to the later revision the tree already had (alt optional) |
| 7 | `164-pqpolish` @ `d30a22702` | `7bc095339` | the STALL/OCCUPY labels: what a stalled PQ bot waits for |
| 8 | `164-opq` catch-up @ `d92443ce2` | `1a574b84b` | the PQ lane's six mid-flight commits (Magatia's climb, the empty-map clear, Ellin/CWKPQ costing, the CWKPQ coverage row, `graceMs`, the flake fix) |
| 9 | `164-realruns` @ `4aaf42e58` | `9fd78762f` | `RealRunPool` and the five admission sites, `RunOutcome`/`RunOutcomes`, the rotation and sampling of simulated runs, BossRush's eight rows, the Zakum-mines row |
| 10 | `164-seams` @ `6d01def08` | `663d273ba` | the four engine seams (reactor by object id, the stage clock, `onTimeLimit`, `branches`), the bound-client fix, `EquipItem`/`UnequipItem`, queue staging, `content/guildquest.yaml` (7 stages, 26 maps) |
| 11 | `164-opq` final @ `96484cf9a` | `4e495c09b` | the lane's last 67 commits: the four Hard-boss rows, `Mechanics.bossEnabledFor`, tiers on four rows, `MechanicsRowWzIntegrityTest` |
| 12 | `164-seams` follow-up @ `6e8011250` | `39276532a` | one test line: the shipped-row list names `guildquest` |

`164-bossfight`, `164-ex2qa` and `164-bossproof` are ancestors of `164-bossrows`/`164-opq`, so merging them adds
nothing; every head was verified with `git for-each-ref` and `git merge-base --is-ancestor` before it was merged.

## What merged cleanly (no conflict, both sides kept)

`Orchestrator`'s stage walk, `StepExecutor`, `StageView`, `LiveStageView`, every solver file that only one lane touched,
the four auto-QA wiring files, `docs/PROVENANCE.md`, and 50 of the 56 row files. The collisions were almost all
data-layer or switch-layer: the record, its parser, `index.txt`, the pinned id list and the two test classes the two
test-creating lanes both wrote.

## Every conflict and how it was resolved

46 conflicts in all, across eleven merges (18 in steps 3-8, 10 in step 9, 5 in step 10, 12 in step 11, 1 in step 12).
Only the ones that needed a decision are listed; the rest were whole-block additions where both sides were kept
verbatim.

| # | File | What clashed | Resolution |
| --- | --- | --- | --- |
| 1 | `MechanicsModel.java` (record) | every lane appended components to `Stage`; two lanes had two spellings of "the reactor this stage is behind" | one order, every side's component: `..., summonProperty, spawnWaitSeconds, timeLimitMinutes, hitReactors, entryItems, maps, loopTo, onTimeLimit, branches`. The list is the one reactor spelling; `killTarget` and `graceMs` are gone (see the spelling table); the 12-arg convenience constructor is the only convenience constructor |
| 2 | `MechanicsModel.java` (javadoc) | four lanes documenting their own fields | one `<li>` list with all of them, including the `loopTo`, `onTimeLimit` and `branches` bullets |
| 3 | `StageData.java` | each lane parsing its own fields, each with its own `new Stage(...)` | one `readStage` parses everything, one `return new Stage(...)`; the scalar `hitReactor` key is refused with the spelling to use instead; `graceMs` and `killTarget` are not parsed at all |
| 4 | `StageData.java` (branches) | the seam lane's `readBranches`/`checkBranchTargets` arrived beside this tree's return | kept both, plus the "`onTimeLimit: advance` and `loopTo` both say where the run goes next" check |
| 5 | `MechanicsRuns.java` (start ladder) | the real-run lane added an admission overload, the seam lane a tier overload, both around the same `start(...)` | one sink `start(..., Tier, ..., Admission)`; the tier overload and the admission overload both delegate to it, and `finish` releases `a.admission` in a `finally` |
| 6 | `MechanicsRuns.java` (gate) | two spellings of "is this boss row armed", plus the PQ lane's new `bossEnabledFor` | `Mechanics.bossEnabledFor(content)` is the one call, and it now reads `bossesEnabled() && rowFlagOn(content)` |
| 7 | `MechanicsRuns.java` (finish) | the chatter lane wrote its lines from `RunState`; the real-run lane writes a `RunOutcome` | `recordOutcome(a)` (the `RunOutcome` path) is the only writer; `leaveOutcomeLines` and its `Random` field are gone |
| 8 | `KpqStaging.java` | the same start-ladder split for staged runs | one `stage(...)` sink taking tier and admission, `finally { if (!playing) admission.release(); }` |
| 9 | `Mechanics.java` | four lanes adding switches to one file | every switch kept in one file: the master flag, the boss flag, eleven PQ flags, `CHATTER_FLAG`, `REAL_RUNS_FLAG`, `SIM_RUNS_FLAG`, `SIM_FLOOR_FLAG` |
| 10 | `RunChatter.java` + `RunChatterTest.java` (add/add) | two independent chatter designs: `plan(RunState, ...)`/`Ending` vs `plan(RunOutcome, ...)` + an `onOutcome` listener | the listener design: one `RunChatter` registered with `RunOutcomes.listen`, so a run that ends is described once, whichever lane ended it |
| 11 | `TierScaling.java` (add/add) | two different classes of the same name (`enabled()` vs `on()`) | one class with one switch spelling (`on()`), one `requested(Content)`, one `rulesFor`, one `applyTo`, one `scale` |
| 12 | `TierScalingTest.java` (add/add) | two different test classes of the same name | one class holding both halves (the three rows' HARD table and the room-scaling tests) |
| 13 | `RowRunSimulationTest.java` | two late-spawn harnesses in the same fake `World` (`pendingSpawns`/`spawnAfterMs` vs `pendingParts`/`spawnAfter`/`tickSpawn`), plus both lanes' boss-room tests | one harness: `spawnAfter(ms)` + `pendingParts` + `tickSpawn()`, driven from the tick; ours' five hardcoded per-map spawn cases deleted because the `default ->` branch builds a one-BODY-part boss from `stage.mobs()` |
| 14 | `RowRunSimulationTest.java` (clear rule) | `killTarget()` no longer exists | the row lists a revive chain in order, so the **last** id in `stage.mobs()` is the clear (`if (!chain.isEmpty() && mobId == chain.get(chain.size() - 1) && clear().onInstance())`) |
| 15 | `content/index.txt` | five lanes each appending rows | one order, 56 rows: base, boss lane's 27, the PQ lane's 7, the real-run lane's 9 (`bossrush0..7`, `zakumpq`), Guild Quest |
| 16 | `MechanicsDataFilesTest.java` | the pinned id list, five lanes | the same 56 ids in the same order |
| 17 | `content/zakumpq.yaml` | two revisions of a row added by the real-run lane and rewritten by the PQ lane | the PQ lane's revision (59 stages, `maps:` on every stage, the reactor on the hub stage), with its 48 scalar `hitReactor:` keys migrated to `hitReactors: [id]`; the hunk-by-hunk attempt had produced a row with two `stage: 50` blocks and the loader said so |
| 18 | the four Hard-boss rows (`nwill`, `nlucid`, `nverus`, `ndarknell`, add/add) | these rows existed on the boss lane and again on the PQ lane's head | the PQ lane's bodies (they carry their own switch on top of `slums.mechanics.boss`), minus `graceMs` and `killTarget` |
| 19 | `LateSpawnGraceTest.java` | the PQ lane drove `graceMs: 240000` | rewritten to drive `spawnWaitSeconds: 240`, keeping its two real assertions and gaining one for the 30 s floor |
| 20 | `Orchestrator.java` (stage clock) | the seam lane's `onTimeLimit: advance` branch beside this tree's budget `fail(...)` | kept advance, routed through this tree's `fail(run, "time budget of N min ran out on stage S")` |
| 21 | `docs/tickets/130-bots-play-mechanics.md` | four lanes' chapters in one file | every chapter kept, in merge order (bossfight, bossrows, the expedition door, chatter, realruns, seams) |
| 22 | `REPORT.md` (add/add, three times) | four lanes each writing the hand-back page | boss lane's kept as `REPORT-bossfight.md`; the others kept as `REPORT-altinvite.md`, `REPORT-ex2.md`, `REPORT-pqpolish.md`; `REPORT.md` on this branch is this branch's own page |
| 23 | `src/main/java/soloMapling/qa/*` + `docs/ops/qa-suite.md` | the alt-invite lane's `ex-2` (alt mandatory) vs the later revision | the later revision (alt optional, `EX2 CHECK` written in both settings), which documents both |
| 24 | `autoqa-coverage.tsv` | both lanes adding rows | both lines kept (`t-ex-2` -> the two Zakum rows, `t-mx-1..6` -> `cwkpq.yaml`) |

Everything else that looked like a conflict was a whole-block addition: both blocks kept, nothing dropped.

### The duplicate spellings, folded (the "one spelling per fact" rule)

| Fact | Before the merges | After | Consumers migrated |
| --- | --- | --- | --- |
| the reactor a stage is behind | boss rows `hitReactors: [..]` (list); PQ rows `hitReactor: <id>` (scalar) - and the PQ lane's last 67 commits reintroduced the scalar | `hitReactors` only | `EllinStoneSolver`, `ReactorCountSolver`, `ReactorStateSolver`, `ZakumMinesSolver` read the list; `ellinpq` (2 stages), `magatiapq` (1) and `zakumpq` (48) name `hitReactors: [<id>]`; `ZakumPqSolversTest` migrated; the loader refuses the scalar key with `hitReactor: the scalar spelling is gone; write hitReactors: [<id>]` |
| how long an empty boss room may stay empty | boss lane `spawnWaitSeconds` (content fact, seconds) vs the PQ lane's `graceMs` (tolerance, ms) | `spawnWaitSeconds`; the 30 s floor lives in `BossPhasesSolver.allowedEmptyMs` | `LateSpawnGraceTest`; the eight `bossrush` rows and the four Hard-boss rows claim no `spawnWaitSeconds` of their own |
| the mob whose death ends a stage | `killTarget` (PQ lane), deleted as dead by the boss lane, reintroduced by two later lane heads | gone | nothing reads it, no row sets it, `StageData` does not parse it |
| the run's closing chat lines | `RunChatter.plan(RunState, ...)` + `leaveOutcomeLines` (chatter lane) vs `plan(RunOutcome, ...)` + `onOutcome` listener (real-run lane) | the listener | `MechanicsRuns.recordOutcome` is the only end-of-run writer, registered once at `MechanicsRuns.java:44-45` |
| the tier switch | `TierScaling.enabled()` vs `on()` | `on()` | `TierScalingTest` |
| "is this boss row armed" | `bossesEnabled() && rowFlagOn(content)` inline vs `Mechanics.bossEnabledFor(content)` | `bossEnabledFor` | `MechanicsRuns.onInstanceStarted` |

### Anything that came from two places

The owner's list was the run-chatter picks; the audit found those and a few more. Where a symbol came in twice, the
survivor is named with its file and line, and the copy that is *not* there is stated so a reader can tell it was
checked rather than assumed:

| Symbol | Copies that existed | Survivor (file:line) | Evidence there is one |
| --- | --- | --- | --- |
| `RunChatter` | the chatter lane's own commits (`9b4a8998e`, `b1f838865`, `05b7db0c4`) and `164-realruns`' cherry-picks of them under new hashes (`1ad08d024`, `5809710fd`, `c46af4203`) | `src/main/java/soloMapling/mechanics/RunChatter.java:46` (one `final class RunChatter`, with `nameFor(Content)`/`nameFor(String,String)`, `boss(Content)`/`boss(Kind)`, `speakers(...)`, `plan(RunOutcome, ...)`, `onOutcome(RunOutcome)`) | `grep -rl 'class\|record\|enum RunChatter' src/main/java` returns one file; both sides' members are present (the listener *and* the `String contentId, String contentName` lookup the outcome path needs) |
| `RunOutcome` | realruns' pick and the chatter lineage's copy | `src/main/java/soloMapling/mechanics/RunOutcome.java:36` (record) with `Participant` at `:57` | one declaration; `MechanicsRuns` builds both `RunMember`s (6 components, `level` included) and `RunOutcome.Participant`s from the same character |
| `BotRunOutcome` | same | `src/main/java/soloMapling/ArtificialPlayer/BotRunOutcome.java` | one file, one `Slot` type, reached from `BotSM.java:185` (`runOutcome()`) |
| `Mechanics` flags | the chatter lane's `CHATTER_FLAG`, realruns' quota flags, the PQ lane's eleven row flags | `src/main/java/soloMapling/mechanics/Mechanics.java:20,27,34,37,44,52,55,58,61,64,67,70,79,87,94,101` | all sixteen constants in one class; `realRunCapacity()`, `simulatedRunCapacity()`, `simulatedFloor()` and `chatterEnabled()` are the readers, one each |
| `MechanicsRuns` | realruns' admission-aware `start`/`begin`/`finish`, the seam lane's tier plumbing, ours' gate | `src/main/java/soloMapling/mechanics/MechanicsRuns.java` - one `start` sink, `finish` at `:685` releasing `a.admission` at `:694`, `recordOutcome` at `:721`, listeners at `:44-45`, `shortName` at `:343` | one class; `leaveOutcomeLines` is absent, so the chatter lane's writer really was replaced rather than left beside the new one |

Also from two places, at the data layer: the four Hard-boss rows, `zakumpq.yaml`, `TierScaling` (class and test), the
`RowRunSimulationTest` fake world's spawn harness, and the `Stage` record's components. All are in the conflict table
above with the side that survived.

## The rows, and the switch that arms each one

Counted on the merged head, not assumed: **56 rows = 29 `kind: BOSS` + 27 `kind: PQ`, 195 stages**, 56 `*.yaml` files
and 56 lines in `content/index.txt`. `slums.mechanics=true` is the master switch for every party-quest row; a boss row
needs `slums.mechanics.boss=true` instead (it does not need `slums.mechanics`). A row with a second switch in the
column also needs that one. Nothing arms with defaults.

| Row | What | Kind | Stages | Switch | Players | Levels |
| --- | --- | --- | --- | --- | --- | --- |
| `kpq` | Kerning City Party Quest | PQ | 5 | `slums.mechanics` | 3-4 | 21-255 |
| `zakum` | Zakum (expedition) | BOSS | 1 | `slums.mechanics.boss` | 6-30 | 50-255 |
| `horntail` | Horntail (expedition) | BOSS | 3 | `slums.mechanics.boss` | 6-30 | 100-255 |
| `opq` | Orbis Party Quest | PQ | 8 | `slums.mechanics` + `slums.mechanics.opq` | 5-6 | 51-255 |
| `lpq` | Ludibrium Party Quest | PQ | 9 | `slums.mechanics` + `slums.mechanics.lpq` | 5-6 | 35-255 |
| `hpq` | Henesys Party Quest | PQ | 2 | `slums.mechanics` + `slums.mechanics.hpq` | 3-6 | 10-255 |
| `cafepq1` | Cafe PQ, area 1 | PQ | 1 | `slums.mechanics` + `slums.mechanics.cafepq` | 3-6 | 21-255 |
| `cafepq2` | Cafe PQ, area 2 | PQ | 1 | `slums.mechanics` + `slums.mechanics.cafepq` | 3-6 | 21-255 |
| `cafepq3` | Cafe PQ, area 3 | PQ | 1 | `slums.mechanics` + `slums.mechanics.cafepq` | 3-6 | 21-255 |
| `cafepq4` | Cafe PQ, area 4 | PQ | 1 | `slums.mechanics` + `slums.mechanics.cafepq` | 3-6 | 21-255 |
| `cafepq5` | Cafe PQ, area 5 | PQ | 1 | `slums.mechanics` + `slums.mechanics.cafepq` | 3-6 | 21-255 |
| `cafepq6` | Cafe PQ, area 6 | PQ | 1 | `slums.mechanics` + `slums.mechanics.cafepq` | 3-6 | 21-255 |
| `dunas` | Dunas (TD Battle 2) | BOSS | 1 | `slums.mechanics.boss` | 1-6 | 70-255 |
| `aufheben` | Aufheben (TD Battle 3) | BOSS | 1 | `slums.mechanics.boss` | 1-6 | 70-255 |
| `oberon` | Oberon (TD Battle 4) | BOSS | 1 | `slums.mechanics.boss` | 1-6 | 70-255 |
| `bergamot` | Bergamot (TD Battle 1) | BOSS | 1 | `slums.mechanics.boss` | 1-6 | 70-255 |
| `nibelung` | Nibelung (TD Battle 5) | BOSS | 1 | `slums.mechanics.boss` | 1-6 | 70-255 |
| `maha` | Maha (quest instance) | BOSS | 1 | `slums.mechanics.boss` | 1-1 | 120-255 |
| `castellan` | Castellan (expedition) | BOSS | 1 | `slums.mechanics.boss` | 1-6 | 1-255 |
| `papulatus` | Papulatus (expedition) | BOSS | 1 | `slums.mechanics.boss` | 1-6 | 1-255 |
| `krexel` | Krexel (expedition) | BOSS | 1 | `slums.mechanics.boss` | 1-6 | 1-255 |
| `showa` | Showa (expedition) | BOSS | 1 | `slums.mechanics.boss` | 3-30 | 100-255 |
| `balrog` | Balrog (expedition) | BOSS | 1 | `slums.mechanics.boss` | 6-30 | 50-255 |
| `scarga` | Scarga (expedition) | BOSS | 1 | `slums.mechanics.boss` | 6-30 | 100-255 |
| `elemental` | Elemental Thanatos | BOSS | 1 | `slums.mechanics.boss` | 1-2 | 100-255 |
| `pinkbean` | Pink Bean (expedition) | BOSS | 1 | `slums.mechanics.boss` | 6-30 | 120-255 |
| `latanica` | Capt. Latanica | BOSS | 1 | `slums.mechanics.boss` | 1-6 | 1-255 |
| `magnus` | Magnus (expedition) | BOSS | 1 | `slums.mechanics.boss` | 1-6 | 200-256 |
| `nwill` | Hard Will (expedition) | BOSS | 1 | `slums.mechanics.boss` + `slums.mechanics.nwill` | 1-6 | 200-255 |
| `nlucid` | Hard Lucid (expedition) | BOSS | 1 | `slums.mechanics.boss` + `slums.mechanics.nlucid` | 1-6 | 200-255 |
| `nverus` | Hard Verus Hilla (expedition) | BOSS | 1 | `slums.mechanics.boss` + `slums.mechanics.nverus` | 1-6 | 200-255 |
| `ndarknell` | Hard Darknell (expedition) | BOSS | 1 | `slums.mechanics.boss` + `slums.mechanics.ndarknell` | 1-6 | 200-255 |
| `guardiannex1` | GuardianNex, cave 1 | BOSS | 1 | `slums.mechanics.boss` | 1-1 | 70-255 |
| `guardiannex2` | GuardianNex, cave 2 | BOSS | 1 | `slums.mechanics.boss` | 1-1 | 73-255 |
| `guardiannex3` | GuardianNex, cave 3 | BOSS | 1 | `slums.mechanics.boss` | 1-1 | 77-255 |
| `guardiannex4` | GuardianNex, cave 4 | BOSS | 1 | `slums.mechanics.boss` | 1-1 | 81-255 |
| `guardiannex5` | GuardianNex, cave 5 | BOSS | 1 | `slums.mechanics.boss` | 1-1 | 85-255 |
| `guardiannex6` | GuardianNex, cave 6 | BOSS | 1 | `slums.mechanics.boss` | 1-1 | 90-255 |
| `zakum-chaos` | Chaos Zakum (expedition) | BOSS | 1 | `slums.mechanics.boss` + `slums.chaos.zakum` | 6-30 | 120-255 |
| `ludimazepq` | LudiMaze Party Quest | PQ | 1 | `slums.mechanics` + `slums.mechanics.ludimazepq` | 3-6 | 51-255 |
| `piratepq` | Pirate PQ | PQ | 1 | `slums.mechanics` + `slums.mechanics.piratepq` | 3-6 | 55-255 |
| `treasurepq` | Treasure PQ | PQ | 1 | `slums.mechanics` + `slums.mechanics.treasurepq` | 4-6 | 140-255 |
| `dellibattle` | DelliBattle | PQ | 1 | `slums.mechanics` + `slums.mechanics.dellibattle` | 1-2 | 120-255 |
| `magatiapq` | Magatia PQ (Alcadno) | PQ | 6 | `slums.mechanics` + `slums.mechanics.magatiapq` | 4-4 | 71-255 |
| `ellinpq` | Ellin PQ (Forest of Poison Haze) | PQ | 6 | `slums.mechanics` + `slums.mechanics.ellinpq` | 4-6 | 44-255 |
| `cwkpq` | Crimsonwood Keep PQ | PQ | 4 | `slums.mechanics` + `slums.mechanics.cwkpq` | 6-30 | 90-255 |
| `bossrush0` | Boss Rush PQ (band 0) | PQ | 6 | `slums.mechanics` + `slums.mechanics.bossrush` | 1-6 | 1-255 |
| `bossrush1` | Boss Rush PQ (band 1) | PQ | 6 | `slums.mechanics` + `slums.mechanics.bossrush` | 1-6 | 1-255 |
| `bossrush2` | Boss Rush PQ (band 2) | PQ | 6 | `slums.mechanics` + `slums.mechanics.bossrush` | 1-6 | 1-255 |
| `bossrush3` | Boss Rush PQ (band 3) | PQ | 6 | `slums.mechanics` + `slums.mechanics.bossrush` | 1-6 | 1-255 |
| `bossrush4` | Boss Rush PQ (band 4) | PQ | 6 | `slums.mechanics` + `slums.mechanics.bossrush` | 1-6 | 1-255 |
| `bossrush5` | Boss Rush PQ (band 5) | PQ | 6 | `slums.mechanics` + `slums.mechanics.bossrush` | 1-6 | 1-255 |
| `bossrush6` | Boss Rush PQ (band 6) | PQ | 6 | `slums.mechanics` + `slums.mechanics.bossrush` | 1-6 | 1-255 |
| `bossrush7` | Boss Rush PQ (band 7) | PQ | 6 | `slums.mechanics` + `slums.mechanics.bossrush` | 1-6 | 1-255 |
| `zakumpq` | Pre-Zakum PQ | PQ | 59 | `slums.mechanics` + `slums.mechanics.zakumpq` | 1-6 | 50-255 |
| `guildquest` | Guild Quest (Sharenian Ruins) | PQ | 7 | `slums.mechanics` + `slums.mechanics.guildquest` | 6-30 | 10-255 |

Two extra switches sit on top of these and neither is a row gate: `slums.mechanics.chatter` (default **true**; the
run's win/loss line) and the per-row lobby, `slums.mechanics.<row>.lobby` (default **false**), with
`slums.mechanics.<row>.runs` (default 1).

## Nothing arms by itself (switch audit, defaults)

Every switch a row reads is a JVM property whose default disarms it, so a server started with none of them has no
mechanics row at all - no lobby starts, no boss room is taken over, no expedition is touched. Checked row by row
against `MechanicsRuns.onInstanceStarted`: a boss row is armed only when `Mechanics.bossEnabledFor(content)`, a
party-quest row only when `Mechanics.enabled() && Mechanics.rowFlagOn(content)`.

| Switch | Default | What it arms |
| --- | --- | --- |
| `slums.mechanics` | off | the party-quest rows: Kerning PQ directly, the others only with their own flag |
| `slums.mechanics.boss` | off | every boss row (all 29). Boss rows do **not** need `slums.mechanics` - a server can let bots share boss fights without letting them run party quests |
| `slums.mechanics.opq` / `.lpq` / `.hpq` / `.cafepq` / `.ludimazepq` / `.piratepq` / `.treasurepq` / `.dellibattle` / `.magatiapq` / `.ellinpq` / `.cwkpq` / `.bossrush` / `.zakumpq` / `.guildquest` | off | the named row (`.cafepq` covers areas 1-6, `.bossrush` all eight waves, `.guildquest` the seven-stage run) |
| `slums.mechanics.nwill` / `.nlucid` / `.nverus` / `.ndarknell` | off | the four Hard-boss rows, **on top of** `slums.mechanics.boss` (each is an expedition script that already exists, so the extra flag is how a server opts into the harder instance) |
| `slums.chaos.zakum` | off | the `zakum-chaos` row, on top of `slums.mechanics.boss` |
| `slums.mechanics.tiers` | off | reading a row's `tiers:` block at all; four rows (`kpq`, `hpq`, `lpq`, `opq`) carry a HARD tier, and with the switch off their rooms keep their WZ numbers |
| `slums.mechanics.tier` | unset (NORMAL) | which tier a run asks for; a tier the row does not name is NORMAL, not an error |
| `slums.mechanics.realruns` | unset = unlimited | how many bot-only runs may play for real at once. A run with a real player, or a GM/QA staged run, never counts |
| `slums.mechanics.simruns` | 8 | how many simulated runs may stand in for refused ones |
| `slums.mechanics.simfloor` | 5 | how many real runs of a content+tier a simulated one must be sampled from (it waits, never fakes) |
| `slums.mechanics.<row>.lobby` | off | the bot lobby for that row (needs the row armed first) |
| `slums.mechanics.<row>.runs` | 1 | how many lobbies a row may run at once |
| `slums.mechanics.lobbyRuns` | 2 | Kerning's own lobby (`KpqLobby`), which is a different mechanism |
| `slums.mechanics.chatter` | **on** | the run's win/loss chat line - the only default that is on, chat only, and it does nothing unless a row is armed |
| `slums.mechanics.dir` | the packaged content directory | where rows load from (tests only) |

The three boss-policy switches the boss lane settled earlier are still off and still pinned by their own tests
(`slums.altfight.instanceleash`, `slums.bossfight.reach`, `slums.bossfight.revivepause`; the revive-pause one is
turned **on** by the release conf line, not by a code default - `b8a91143b` set the default off and `333ddbda2`
corrected the ticket text to say so).

The one row with no `flag:` of its own is `kpq` (the 1.6.1 base row); its switch *is* `slums.mechanics`. No merged row
can arm on a default: with every switch unset the loader still reads all 56 rows and
`MechanicsDataFilesTest.theContentRowsLoadFromTheJarWithNoSourceTree` asserts the load reports no problems.

## Reproduce the proof

```
cd /root/work/164-all
mvn -o test                                                        # the full suite, switches unset
mvn -o test -Dtest='MechanicsDataFilesTest,StageDataTest,RowRunSimulationTest,TierScalingTest,LateSpawnGraceTest' -DfailIfNoTests=false
mvn -o test -Dwz-path=<wz> -Dtest='ContentRowWzIntegrityTest,MechanicsRowWzIntegrityTest'
python3 tools/ops/map-integrity --wz <wz> --map "<the rows' map ids>"
tools/ops/autoqa-proof 6d18f961e..HEAD
tools/ops/helper-checkout /root/work/164-all
```

## What auto-QA covers

`tools/ops/autoqa-proof 6d18f961e..HEAD` reports `checks: every check (186) - a runner or platform file changed`, so the
release run is `tools/ops/autoqa-run all`. The rows themselves are covered by `t-mx-1`..`t-mx-6` (Kerning PQ, need
`-Dslums.mechanics=true`; they SKIP without it) and `t-ex-2` (Zakum, needs `-Dslums.mechanics.boss=true`; it reads
`EX2 BASELINE` and SKIPs otherwise). `t-in-3` shares the `src/main/java/soloMapling/mechanics/**` glob. Everything
else the rows add is covered by unit tests only; `docs/ops/qa-suite.md` names the manual companion for each row.

## Verification run on this branch

| Check | Command | Result |
| --- | --- | --- |
| Full suite, every switch unset | `mvn -o test` | **`Tests run: 9068, Failures: 0, Errors: 0, Skipped: 139`**, BUILD SUCCESS, `EXIT=0`, 33:51 (log `/tmp/final2.log`) |
| Row data, row simulation, tiers, the late-spawn leash | `mvn -o test -Dtest='MechanicsDataFilesTest,StageDataTest,RowRunSimulationTest,TierScalingTest,LateSpawnGraceTest'` | green; `RowRunSimulationTest` 47, `TierScalingTest` 13, `LateSpawnGraceTest` 5 |
| The whole mechanics package | `mvn -o test -Dtest='soloMapling.mechanics.*Test,soloMapling.MechanicsDataFilesTest'` | **`Tests run: 725, Failures: 0, Errors: 0, Skipped: 2`** (log `/tmp/mech.log`) |
| Every row against the WZ dump | `mvn -o test -Dwz-path=/root/work/SlumsMS-170-evan/wz -Dtest='ContentRowWzIntegrityTest,MechanicsRowWzIntegrityTest'` | `Tests run: 2, Failures: 0, Errors: 0, Skipped: 0`, `EXIT=0`: **56 rows, 156 map ids, 127 mob ids, 14 reactor ids, 1 door item (`4000381`)** |

### Suite totals

`mvn -o test`, every switch unset, on the merged head:

```
Tests run: 9068, Failures: 0, Errors: 0, Skipped: 139     BUILD SUCCESS, EXIT=0, 2032 s
```

Run on `4e495c09b`; the only commit after it (`39276532a`, the seam lane's last test line) leaves the tree
byte-identical - `git diff 4e495c09b HEAD` is empty, because that line already read `"zakumpq", "guildquest"` here - so
the totals are the merged tip's totals, not a near-tip's.

For scale, the four source suites: `164-bossrows` 8575/138, `164-opq` 8635/138, `164-realruns` 8766/137, `164-seams`
8799/137 (all `EXIT=0` on their own heads). The merged tree is above all four because it holds all four lanes' tests.
The 139th skip is `MechanicsRowWzIntegrityTest` (it skips without `-Dwz-path`, which the row above supplies).

No `<<< FAILURE` marker anywhere, no surefire temp-directory collision, and the order-dependent flake
(`BotConversationFlowTest.aRefusedPartyAskIsPlannedFromTheGatesVerdict`) did not appear.

### Paperwork

`tools/ops/helper-checkout /root/work/164-all` ends `CHECKOUT FAIL (164-all-merge): 1 problem(s)` - **134 commit(s)
without a `Co-Authored-By` trailer**. Those are the merged lanes' own commits (bossrows, opq, ex2, altinvite, pqpolish,
realruns, seams); the convention arrives with `release-1.6.4`, whose tip `6d18f961e` does carry one, so the check is
right about the text and wrong about who owns it. They are not rewritten: rewriting them would invalidate every hash
`REPORT-opq.md`, `REPORT-bossrows.md` and this file cite. This branch's own merge and report commits do carry the
trailer. Every other check passes: tests changed with the code, `REPORT.md` is 24 lines, names the branch, has the suite
counts, names the fail-before breakage, and has an economy line; no secrets; nothing pushed. Warnings left standing and
reported as they are: files outside the usual folders (`REPORT-altinvite.md`, `REPORT-bossfight.md`,
`REPORT-bossrows.md`, `REPORT-ex2.md`, `REPORT-merged.md`) and added text that names MapleStory/Nexon (internal only).

## What is still unproven

1. **No client has seen the merged tree.** Every result above is the offline suite plus the WZ dump. The 56 rows have
   never been armed together on a server.
2. **Guild Quest has never been played from this branch.** Its 7 stages and 26 maps parse, its boxes and statues are
   covered by solver tests, and it is the row with the `onTimeLimit: advance` and `branches:` machinery - but no bot has
   walked the Sharenian ruins end to end, and no client has seen a stage-1 clock expiry change the phase.
3. **`t-ex-2` and `t-mx-*` have not been re-run from this branch.** They passed on their own lanes; a merged binary is a
   different binary, so the release run must re-prove them.
4. **The real-run quota is unproven under load.** `RealRunPool`'s admission, the rotation and the sampling are covered
   by unit tests (`RealRunPoolTest` 14 tests, `SimulatedRunsTest`), but nothing has run with `-Dslums.mechanics.realruns`
   set on a live world, and the sampling floor's "waits, never fakes" behaviour is unobserved.
5. **The block list is four, not eight.** Cygnus, Von Leon, Hard Lotus and `Balrog_Easy` remain blocked; the four Hard
   bosses (`nwill`, `nlucid`, `nverus`, `ndarknell`) are **not** blocked - each of their scripts spawns its own boss
   five seconds in, the mobs exist in `Mob.wz`, and the rooms' empty life blocks are deliberate. The older "eight
   blocked bosses" note was wrong and is not carried forward.
6. **The folded reactor list and the deleted tolerance field are unproven in a client.** Four solvers read
   `hitReactors` where they read a scalar two merges ago; no client has clicked the Ellin spine, Ellin's stone,
   Magatia's beaker or Zakum's chests from this branch.
7. **Two portal-name misses on row maps** (the Armory's `out00`, Delli's `in03`) are in `Map.wz`, not in this merge, and
   no solver path uses them; fixing them means editing the WZ dump, outside this branch.
8. **The whole-dump `map-integrity` sweep is not gating anything.** Its report over a partial dump is noise; only the
   per-row view is meaningful, and `ContentRowWzIntegrityTest` is that view inside the suite.

## Deploy note

**Branch:** `164-all-merge` at `39276532a` (worktree `/root/work/164-all`). Nothing was pushed; the owner pushes and
deploys.

The mechanics switches are **JVM flags**, not `config.yaml` keys - there is no `slums.mechanics*` key in any config
schema, so TEST's `config.yaml` needs **no change**. Add the flags to the java line in TEST's `start-test.sh`
(`/opt/mapleroot-dev/persistence-test/start-test.sh`), after the existing `-Dslums.*` ones:

```
-Dslums.mechanics=true -Dslums.mechanics.boss=true -Dslums.mechanics.opq=true -Dslums.mechanics.lpq=true -Dslums.mechanics.hpq=true -Dslums.mechanics.cafepq=true -Dslums.mechanics.ludimazepq=true -Dslums.mechanics.piratepq=true -Dslums.mechanics.treasurepq=true -Dslums.mechanics.dellibattle=true -Dslums.mechanics.magatiapq=true -Dslums.mechanics.ellinpq=true -Dslums.mechanics.cwkpq=true -Dslums.mechanics.bossrush=true -Dslums.mechanics.zakumpq=true -Dslums.mechanics.guildquest=true -Dslums.mechanics.nwill=true -Dslums.mechanics.nlucid=true -Dslums.mechanics.nverus=true -Dslums.mechanics.ndarknell=true -Dslums.chaos.zakum=true -Dslums.bossfight.revivepause=true
```

That is the full sweep: all 56 rows armed, no lobby. Drop a line and its rows are inert; drop all of them and the
server behaves exactly as `release-1.6.4` does, because every switch defaults off.

**Keep off:**
- `slums.mechanics.<row>.lobby` - the bot lobbies take a whole party quest out of players' hands (and
  `slums.mechanics.<row>.runs` above 1 multiplies that).
- `slums.mechanics.tiers` and `slums.mechanics.tier` - the HARD tier is a proposal (`DIFFICULTY-CHAOS-PLAN.md` section
  9) and has been played nowhere; turning it on hands four rows' rooms scaled mobs with no client ever having seen it.
- `slums.mechanics.realruns` / `.simruns` / `.simfloor` - leave the quota unset (unlimited = today's behaviour) until
  someone has watched it under load.
- the fishing, bot-life and Chaos-boss flags `docs/HANDOFF.md` and `docs/release/1.6.0-qa.md` already say stay off on
  shared TEST.

**What a player sees with the sweep on:** the Kerning, Orbis, Ludi, Henesys, Cafe, LudiMaze, Pirate, Treasure,
DelliBattle, Magatia, Ellin, Crimsonwood Keep and Sharenian Guild Quest party quests run with bots inside them; a bot
party shares Zakum, Horntail, Chaos Zakum, the five Twin Dragon caves, Maha, Castellan, Papulatus, Krexel, Showa,
Balrog, Scarga, Elemental Thanatos, Pink Bean, Captain Latanica, Magnus, the four Hard bosses (Will, Lucid, Verus Hilla,
Darknell), the six GuardianNex caves, the eight BossRush waves and the Zakum-mines chest hunt instead of standing
outside; and every run that ends can leave one chat line (chatter is on by default, chat only).
