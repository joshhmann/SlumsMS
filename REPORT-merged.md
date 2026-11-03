# Merge lane, 1.6.4 - everything in one tree (branch `164-all-merge`)

Worktree `/root/work/164-all`, branch `164-all-merge` off `release-1.6.4` @ `6d18f961e`, push URL disabled. Nothing
pushed, nothing deployed, live server untouched. This is the one branch that carries every 1.6.4 lane's content rows:
the base game's two, the boss lane's 27, and the party-quest lane's 17 - **46 rows**, which is exactly the number of
`*.yaml` files under `src/main/java/soloMapling/mechanics/content/`.

## What this branch is made of

| Step | Source | Result |
| --- | --- | --- |
| base | `release-1.6.4` @ `6d18f961e` | the deployable tree |
| 2 | `164-bossrows` @ `b258879dd` | fast-forward: that branch already carries the release tip. 27 boss rows, `Stage.spawnWaitSeconds` / `entryItems` / `hitReactors`, the `boss-phases` solver, `ContentRowWzIntegrityTest` |
| 3 | `164-opq` @ `90e40c1fd` | 17 PQ rows (Orbis, Ludi, Henesys, the six Cafe areas, LudiMaze, Pirate, Treasure, DelliBattle, Magatia, Ellin, Crimsonwood Keep), their solvers, `StageView.eventCleared()` / reactor and NPC state, `content/index.txt`, the one PQ lobby |
| 4 | `164-run-chatter` @ `05b7db0c4` | the run's win/loss chat line (`slums.mechanics.chatter`, default on) |
| 5 | `164-altinvite` @ `3b546e834` | an owned alt answers its owner's family and guild invites (e02) |
| 6 | `164-ex2` @ `0dd9dfc83` | the `ex-2` QA scenario, resolved to the later revision the tree already had (alt optional) |
| 7 | `164-pqpolish` @ `d30a22702` | the STALL/OCCUPY labels: what a stalled PQ bot is waiting for |

`164-bossfight`, `164-ex2qa` and `164-bossproof` are ancestors of `164-bossrows`/`164-opq`, so merging them would
have added nothing; `164-bossproof` in particular is already inside both lanes.

## What merged cleanly (no conflict, both sides kept)

`Orchestrator`, `StepExecutor`, `StageView` and `LiveStageView`, every solver file, `Mechanics`' per-row switches,
`BotNpcTalk.talkAt`, `MechanicsRuns`' instance gate, all 44 row yaml files, `docs/PROVENANCE.md`, the four auto-QA
wiring files (`AutoQaOne`/`AutoQaTwo`/`QaScenario` registry) and the whole test tree except the two files below. The
two branches had grown in different files, so the only textual collisions were data-layer ones.

## Every conflict and how it was resolved

| # | File | What clashed | Resolution |
| --- | --- | --- | --- |
| 1 | `src/main/java/soloMapling/mechanics/MechanicsModel.java` (record) | both sides appended components to `Stage`, and both had a spelling of "the reactor this stage is behind" | one order, both sides' components: `..., summonProperty, spawnWaitSeconds, timeLimitMinutes, hitReactors, entryItems, maps`. The list is the one spelling of the reactor fact (see the next table); `killTarget` was dropped as dead, and the convenience constructor was updated to match |
| 2 | `src/main/java/soloMapling/mechanics/MechanicsModel.java` (javadoc) | both sides documented their own fields | one `<li>` list with both sides' entries; the `hitReactors` bullet now says the party-quest rows name it with one entry |
| 3 | `src/main/java/soloMapling/mechanics/StageData.java` | the boss side parses `hitReactors`/`entryItems`; the PQ side parses `hitReactor`/`maps` | one `readStage` parses every field both sides had, in one `new Stage(...)`; the scalar `hitReactor` key is refused with the spelling to use instead, so an old row cannot be silently ignored |
| 4 | `src/main/java/soloMapling/mechanics/content/index.txt` | each branch appended its own rows | union in one order: 12 base rows, then the 27 boss rows in the boss branch's order, then the 7 PQ rows |
| 5 | `src/test/java/soloMapling/MechanicsDataFilesTest.java` | the pinned id list, both sides | the same 46 ids in the same order; the test already compares sorted |
| 6 | `src/main/resources/soloMapling/autoqa-coverage.tsv` | each side added its own rows | both lines kept (`t-ex-2` → the two Zakum rows, `t-mx-1..6` → `cwkpq.yaml`) |
| 7 | `src/main/java/soloMapling/mechanics/Mechanics.java` | the PQ side added six per-row switches where the chatter lane added `CHATTER_FLAG` | all seven kept |
| 8 | `REPORT.md` (first time) | the alt-invite lane wrote its own hand-back where the boss lane's lived | boss lane's kept at `REPORT.md` then, e02's kept as `REPORT-altinvite.md` |
| 9 | `REPORT.md` (ex-2) | same add/add | as above; `REPORT-ex2.md` merged byte-identical, so nothing was lost |
| 10 | `REPORT.md` (pqpolish) | same add/add | as above; the PQ diagnostics report kept as `REPORT-pqpolish.md` |
| 11 | `docs/ops/qa-suite.md` | the alt lane's `ex-2` text (alt mandatory) vs the tree's later text (alt optional, `EX2 CHECK` line) | kept the tree's, which is a superset: the `ex-2` section documents both settings |
| 12 | `src/main/java/soloMapling/qa/BossRunScenarios.java` | same scenario, two revisions | kept the tree's revision: alt optional, `EX2 CHECK` written in both switch settings |
| 13 | `src/main/java/soloMapling/qa/QaScenarios.java` and `src/main/java/soloMapling/menus/QaMenuCatalog.java` | the older `ex-2` registration | kept the tree's, so `ex-2` stays one scenario with one usage line (`[your alt's name]`) |
| 14 | `src/test/java/soloMapling/qa/BossRunScenariosTest.java` | assertions for the mandatory-alt revision | kept the tree's assertions (they cover the optional alt too) |

Test-side follow-ups for the widened record: `BossPhasesSolverTest`'s two `Stage` builders and `MagatiaBooksSolversTest`
now pass `maps`; `EllinPqSolversTest` asserts the reactor as `List.of(3002001)` instead of the scalar.

### The duplicate spellings, folded (the "one spelling per fact" rule)

The two lanes had each grown a spelling of the same stage fact. Both are now one field, with the other lane's consumers
migrated - and the one dead field the boss lane had already deleted is not resurrected:

| Fact | Before the merge | After | Consumers migrated |
| --- | --- | --- | --- |
| the reactor a stage is behind | boss rows: `hitReactors: [..]` (list); PQ rows: `hitReactor: <id>` (scalar) | `hitReactors` only | `EllinStoneSolver`, `ReactorCountSolver`, `ReactorStateSolver` read the list; `ellinpq.yaml` (two stages) and `magatiapq.yaml` (one) name `hitReactors: [<id>]` |
| the mob whose death ends a stage | `killTarget` on the PQ side, deleted as dead by the boss side | gone | nothing read it and no row set it; the loader no longer parses it |

Because the scalar key is refused rather than ignored, a row that still carries it fails the row load with
`hitReactor: the scalar spelling is gone; write hitReactors: [<id>]`, which is what `MechanicsDataFilesTest` asserts
about the whole content directory.

## The rows, and the switch that arms each one

46 rows. `slums.mechanics=true` is the master switch for every party-quest row; boss rows need `slums.mechanics.boss=true`
instead (they do not need `slums.mechanics`). A row with a `flag:` column also needs that flag. Nothing arms with
defaults: `-Dslums.mechanics` alone arms only Kerning PQ.

| Row | What | Kind | Stages | Switch | Players | Levels |
| --- | --- | --- | --- | --- | --- | --- |
| `kpq` | Kerning City Party Quest | PQ | 5 | `slums.mechanics` | 3-4 | 21-255 |
| `zakum` | Zakum (expedition) | BOSS | 1 | `slums.mechanics.boss` | 6-30 | 50-255 |
| `horntail` | Horntail (expedition) | BOSS | 3 | `slums.mechanics.boss` | 6-30 | 100-255 |
| `opq` | Orbis Party Quest | PQ | 8 | `slums.mechanics` + `slums.mechanics.opq` | 5-6 | 51-255 |
| `lpq` | Ludibrium Party Quest | PQ | 9 | `slums.mechanics` + `slums.mechanics.lpq` | 5-6 | 35-255 |
| `hpq` | Henesys Party Quest | PQ | 2 | `slums.mechanics` + `slums.mechanics.hpq` | 3-6 | 10-255 |
| `cafepq1`..`cafepq6` | Cafe PQ, areas 1-6 | PQ | 1 each | `slums.mechanics` + `slums.mechanics.cafepq` | 3-6 | 21-255 |
| `ludimazepq` | LudiMaze Party Quest | PQ | 1 | `slums.mechanics` + `slums.mechanics.ludimazepq` | 3-6 | 51-255 |
| `piratepq` | Pirate PQ | PQ | 1 | `slums.mechanics` + `slums.mechanics.piratepq` | 3-6 | 55-255 |
| `treasurepq` | Treasure PQ | PQ | 1 | `slums.mechanics` + `slums.mechanics.treasurepq` | 4-6 | 140-255 |
| `dellibattle` | DelliBattle ("Save Delli!") | PQ | 1 | `slums.mechanics` + `slums.mechanics.dellibattle` | 1-2 | 120-255 |
| `magatiapq` | Magatia PQ (Alcadno) | PQ | 6 | `slums.mechanics` + `slums.mechanics.magatiapq` | 4 | 71-255 |
| `ellinpq` | Ellin PQ (Forest of Poison Haze) | PQ | 6 | `slums.mechanics` + `slums.mechanics.ellinpq` | 4-6 | 44-255 |
| `cwkpq` | Crimsonwood Keep PQ | PQ | 4 | `slums.mechanics` + `slums.mechanics.cwkpq` | 6-30 | 90-255 |
| `dunas`, `aufheben`, `oberon`, `bergamot`, `nibelung` | Twin Dragon battles 1-5 (caves, 1-6 players, 70+) | BOSS | 1 each | `slums.mechanics.boss` | 1-6 | 70-255 |
| `maha` | Maha, the statue's own instance | BOSS | 1 | `slums.mechanics.boss` | 1 | 120-255 |
| `castellan`, `papulatus`, `krexel`, `latanica` | the expedition bosses that already exist | BOSS | 1 each | `slums.mechanics.boss` | 1-6 | 1-255 |
| `showa`, `balrog`, `scarga` | Showa, Balrog, Scarga | BOSS | 1 each | `slums.mechanics.boss` | 3-30 / 6-30 / 6-30 | 100-255 / 50-255 / 100-255 |
| `elemental` | Elemental Thanatos | BOSS | 1 | `slums.mechanics.boss` | 1-2 | 100-255 |
| `pinkbean` | Pink Bean | BOSS | 1 | `slums.mechanics.boss` | 6-30 | 120-255 |
| `magnus`, `nwill`, `nlucid`, `nverus`, `ndarknell` | the five 200+ expedition bosses | BOSS | 1 each | `slums.mechanics.boss` | 1-6 | 200-256 (Darknell 200-255) |
| `guardiannex1`..`guardiannex6` | GuardianNex caves 1-6 | BOSS | 1 each | `slums.mechanics.boss` | 1 | 70-90 |
| `zakum-chaos` | Chaos Zakum (same altar, one instance over) | BOSS | 1 | `slums.mechanics.boss` + `slums.chaos.zakum` | 6-30 | 120-255 |

Two extra switches sit on top of these and neither is a row gate: `slums.mechanics.chatter` (default **true**; the
run's win/loss line) and the per-row lobby, `slums.mechanics.<row>.lobby` (default **false**), with
`slums.mechanics.<row>.runs` (default 1) for how many lobbies a row may start.

## Nothing arms by itself (switch audit, defaults)

Every switch the rows read is a JVM property with a **false** default (`Boolean.getBoolean`), and a row is armed only
when its own gate is true, so a server started with none of them has no mechanics row at all - no lobby starts, no
boss room is taken over, no expedition is touched. Checked row by row against `MechanicsRuns.onInstanceStarted`
(`src/main/java/soloMapling/mechanics/MechanicsRuns.java:446-450`): a boss row is armed only when
`Mechanics.bossesEnabled() && Mechanics.rowFlagOn(content)`, a party-quest row only when
`Mechanics.enabled() && Mechanics.rowFlagOn(content)`.

| Switch | Default | What it arms |
| --- | --- | --- |
| `slums.mechanics` | off | the party-quest rows: Kerning PQ directly, the others only together with their own flag |
| `slums.mechanics.boss` | off | every boss row (all 29). Boss rows do **not** need `slums.mechanics`: the boss lane's own switch is enough, and that is deliberate - a server can let bots share boss fights without letting them run party quests |
| `slums.mechanics.opq` / `.lpq` / `.hpq` / `.cafepq` / `.ludimazepq` / `.piratepq` / `.treasurepq` / `.dellibattle` / `.magatiapq` / `.ellinpq` / `.cwkpq` | off | the named row (`.cafepq` covers areas 1-6) |
| `slums.chaos.zakum` | off | the `zakum-chaos` row, on top of `slums.mechanics.boss` |
| `slums.mechanics.<row>.lobby` | off | the bot lobby for that row (needs the row itself armed first) |
| `slums.mechanics.<row>.runs` | 1 | how many lobbies a row may run at once |
| `slums.mechanics.chatter` | **on** | the run's win/loss chat line - the only default that is on, and it is chat only; it does nothing unless a row is armed |
| `slums.mechanics.dir` | the packaged content directory | where rows load from (tests only) |

The one row with no `flag:` of its own is `kpq` (the 1.6.1 base row); its switch *is* `slums.mechanics`. No merged row
can arm on a default: with every switch unset the loader still reads all 46 rows (the shipped-directory load test,
`MechanicsDataFilesTest.theContentRowsLoadFromTheJarWithNoSourceTree`, asserts the load reports no problems), and
`MechanicsRuns` arms none of them.

## Reproduce the proof

```
cd /root/work/164-all
mvn -o test                                                        # the full suite, switches unset
mvn -o test -Dtest='MechanicsDataFilesTest,StageDataTest,RowRunSimulationTest' -DfailIfNoTests=false
mvn -o test -Dwz-path=/root/work/SlumsMS-152-maps/wz -Dtest=ContentRowWzIntegrityTest
python3 tools/ops/map-integrity --wz /root/work/SlumsMS-152-maps/wz            # the whole dump
python3 tools/ops/map-integrity --wz /root/work/SlumsMS-152-maps/wz --map "<the 46 rows' map ids>"
tools/ops/autoqa-proof 6d18f961e..HEAD
tools/ops/helper-checkout /root/work/164-all
```

The whole-dump sweep is not a gate for this branch (its report is mostly the dump's own gaps); the per-row invocation
is the meaningful one, and `ContentRowWzIntegrityTest` is the same check inside the suite.

## What auto-QA covers (from `tools/ops/autoqa-proof 6d18f961e..HEAD`)

`checks: every check (186) - a runner or platform file changed`, so the release run is `tools/ops/autoqa-run all`.
The rows themselves are covered by `t-mx-1`..`t-mx-6` (the Kerning PQ scenarios, need `-Dslums.mechanics=true`; they
refuse and SKIP without it) and `t-ex-2` (Zakum, needs `-Dslums.mechanics.boss=true`; it reads `EX2 BASELINE` and
SKIPs otherwise). `t-in-3` shares the `src/main/java/soloMapling/mechanics/**` glob. Everything else the rows add is
covered by unit tests only; `docs/ops/qa-suite.md` names the manual companion for each row.

## Verification run on this branch

| Check | Command | Result |
| --- | --- | --- |
| Full suite, every switch unset | `mvn -o test` | see "Suite totals" below |
| Row data + row simulation | `mvn -o test -Dtest='MechanicsDataFilesTest,StageDataTest,RowRunSimulationTest'` | green, 29 s |
| Every row against the WZ dump | `mvn -o test -Dwz-path=/root/work/SlumsMS-152-maps/wz -Dtest=ContentRowWzIntegrityTest` | green: 46 rows, 78 maps, 127 mob ids, 7 reactor ids, 1 door item |
| Whole-dump data sweep | `python3 tools/ops/map-integrity --wz /root/work/SlumsMS-152-maps/wz` | 11352 maps; 50 dead portals, 678 portal-name misses, 3363 mobs / 3930 NPCs / 143 reactors with no WZ entry, 3621 unnamed maps - all a property of the dump, not of this merge (exit 1) |
| The same sweep over just the rows' maps | `... --map "$(row maps)"` | 78 maps scanned; every row map, mob, reactor and NPC exists; **2** portal-name misses (`610030800`'s `out00`, `925010300`'s `in03`) |
| Paperwork | `tools/ops/helper-checkout /root/work/164-all` | see "Paperwork" below |

### Suite totals

`mvn -o test`, every switch unset, on the pinned merge (head `01d3c60a3`):

```
Tests run: 8758, Failures: 0, Errors: 0, Skipped: 138     BUILD SUCCESS, EXIT=0, 1833 s
```

That is above the boss branch's own 8575 (138 skipped) and the party-quest branch's 8573 (137 skipped): the extra
tests are the boss lane's row and solver tests arriving next to the party-quest lane's, which is what a union should
look like. No `<<< FAILURE` marker anywhere, no re-run needed, and the order-dependent flake
(`BotConversationFlowTest.aRefusedPartyAskIsPlannedFromTheGatesVerdict`) did not appear.

### Paperwork

`helper-checkout` fails this branch on the `Co-Authored-By` trailer: the branch contains the merged lanes' own commits,
which carry no trailer (the trailer convention starts with the release-1.6.4 tip, which does carry one). Rewriting
those 92 commits would invalidate every hash `REPORT-opq.md` and `REPORT-bossrows.md` cite, so they stay as they are;
this branch's own commits carry the trailer. The rest of the check is satisfied by `REPORT.md` (this branch's hand-back)
and this file.

## What is still unproven

1. **No client has seen the merged tree.** Every result above is the offline suite plus the WZ dump. The 46 rows have
   never been armed together on a server, and no bot has walked one of the 17 party quests from this branch.
2. **`t-ex-2` and `t-mx-*` have not been re-run from this branch.** They passed on their own lanes; a merged build is
   a different binary, so the release run has to re-prove them.
3. **Two portal-name misses on row maps** (`610030800` `out00` → `610030010 in00`, `925010300` `in03` → `925010200 out02`).
   They are in `Map.wz`, not in this merge, and neither is on a solver's path (the rows enter and leave those maps
   through other portals), but a player walking out of the Armory's back door or into Delli's room the wrong way gets
   nothing. Not fixed here: fixing them means editing the WZ dump, which is outside this branch.
4. **The folded reactor list is unproven in a client.** Three PQ solvers now read `hitReactors.get(0)` where they read
   a scalar; the simulation tests cover them, no client has clicked the Ellin spine, the Ellin stone or Magatia's beaker
   from this branch.
5. **The whole-dump `map-integrity` sweep is not gating anything.** Its 11352-map report is pre-existing noise over a
   partial dump (thousands of maps have no `String.wz` name in it); only the per-row view is meaningful today.

## Deploy note

**Branch:** `164-all-merge` (this worktree, `/root/work/164-all`). Nothing was pushed; the owner pushes and deploys.

The mechanics switches are **JVM flags**, not `config.yaml` keys - there is no `slums.mechanics*` key in any config
schema, so TEST's `config.yaml` needs **no change at all**. Add the flags to the java line in TEST's
`start-test.sh` (`/opt/mapleroot-dev/persistence-test/start-test.sh`), after the existing `-Dslums.*` ones:

```
-Dslums.mechanics=true \
-Dslums.mechanics.boss=true \
-Dslums.mechanics.opq=true -Dslums.mechanics.lpq=true -Dslums.mechanics.hpq=true \
-Dslums.mechanics.cafepq=true -Dslums.mechanics.ludimazepq=true -Dslums.mechanics.piratepq=true \
-Dslums.mechanics.treasurepq=true -Dslums.mechanics.dellibattle=true -Dslums.mechanics.magatiapq=true \
-Dslums.mechanics.ellinpq=true -Dslums.mechanics.cwkpq=true \
-Dslums.chaos.zakum=true
```

That is the full sweep: every one of the 46 rows armed, no lobby. Drop any line and its rows are inert; drop all of
them and the server behaves exactly as it does today (`release-1.6.4`), because every switch defaults off.

Two more flags belong with the sweep only if the auto-QA checks are wanted: `-Dslums.qa=true` (or
`-Dslums.attackcapture=true`) for `t-ex-2` / `t-mx-1..6`, which are exactly the rows' own checks (see
`docs/ops/qa-suite.md`).

**Keep off:**
- `slums.mechanics.<row>.lobby` - the bot lobbies take a whole party quest out of players' hands (one per row, and
  `slums.mechanics.<row>.runs` above 1 multiplies that); turn one on per row only when the owner wants bots to start
  that PQ by themselves.
- the fishing, bot-life and Chaos-boss flags `docs/HANDOFF.md` and `docs/release/1.6.0-qa.md` already say stay off on
  shared TEST; nothing in this branch needs them.
- `slums.mechanics.dir` - tests only; on TEST the rows come from the jar.

**What a player sees with the sweep on:** the Kerning, Orbis, Ludi, Henesys, Cafe, LudiMaze, Pirate, Treasure,
DelliBattle, Magatia, Ellin and Crimsonwood party quests run with bots inside them, and a bot party shares Zakum,
Horntail, Chaos Zakum, the five Twin Dragon caves, Maha, Castellan, Papulatus, Krexel, Showa, Balrog, Scarga,
Elemental Thanatos, Pink Bean, Captain Latanica, Magnus, Will, Lucid, Verus Hilla, Darknell and the six GuardianNex
caves instead of standing outside; every run that ends can leave one chat line (chatter is on by default, chat only).
