# ex-2: a GM, party bots and his alt fight Zakum in the real expedition (1.6.4)

Worktree `/root/work/164-ex2`, branch `164-ex2` off `origin/release-1.6.4` (`c0e0a2cd7`). Nothing pushed, nothing
merged, no deploy, no server touched. `remote.origin.pushurl` is `DISABLED` in this worktree.

`ex-1` proves bots join an expedition and stops at "the expedition exists": nobody enters, nobody clicks the Eye of
Fire, no bot swings. `ex-2` is the scenario that shows a fight happening, or shows exactly where it stops, and it is
what the boss lane is judged on from the outside.

## What it drives

The real path, not a shortcut:

1. `211042400` (Adobis's map), the GM as party leader, four follower bots staged into his party, and one of his own
   alts summoned beside him (`OwnedAltService.summon`, the `@botme` path).
2. The channel expedition the recruiter makes, then `EventManager.startInstance(exped)` on `ZakumBattle`
   (`scripting/event/EventManager.java:442` → `:451`), with `leader`/`channel` set exactly as `scripts/npc/2030013.js`
   status 4 does.
3. That registration is what walks them in: `registerExpedition` → `registerExpeditionTeam(exped,
   exped.getRecruitingMap().getId())` registers only characters standing on `211042400`, and `registerPlayer` invokes
   the script's `playerEntry`, which warps to the entry map (`scripting/event/EventInstanceManager.java:443-455`,
   `:308-327`). The scenario never warps anyone into the room itself.
4. The GM opens the Eye of Fire with the same call `StepExecutor` makes for a reactor step: `map.getReactorById(2111001)`
   then `reactor.hitReactor(gm.getClient())` (`server/maps/Reactor.java:242`). No item is needed: `scripts/reactor/
   2111001.js` has no guard, it sets `summoned`/`canEnter` and spawns the fake body `8800000` and the eight arms
   `8800003`-`8810010`.
5. One sample a second for 90 s (until 150 s) per member, plus the run's own words.

## What it logs

| Line | Once per | Meaning |
|---|---|---|
| `EX2 START members=<n> bots=<n> alt=<name> mechanics=<b> slums.mechanics.boss=<v> slums.bossfight.damagefloor=<v> slums.bossfight.deathcount=<v>` | run | the run labels itself; a switch this build does not know reads `unset` |
| `EX2 SEEN t=<s> <name> <bot\|alt\|gm> map=<id> held=<yes/no> run=<yes/no> target=<oid>/<mob> <mob name> reach=<yes/no> swings=<n> dmg=<n> doing=<run line or ->` | member, 1 s | `held` = `MechanicsHold.committed`, `run` = the member is in the mechanics run, `target` = the mob its last committed swing was aimed at, `reach` = `BotAttackDriver.canSwingFrom` from where it stands now, `swings`/`dmg` counts since the run started |
| `EX2 ALTAR t=<s> hit reactor 2111001 as <name>, <n> mob(s) on the map now` | once | or `no reactor 2111001 on <map>` / `the GM is on <id>, not 280030000` |
| `EX2 PARTS t=<s> <id:hp/maxHp ...>` | 10 s | what is standing on the altar map, or `empty` |
| `EX2 RUN t=<s> stage=<n> cleared=<b> ended=<reason\|-> events=<k> \| <last lines>` | 10 s | the mechanics run's stage, clear, end reason and its last event lines (the `Abandon`, `gave up` and `could not be put in map` ones) |
| `EX2 PASS cleared ...` / `EX2 FAIL <reason> ...` / `EX2 END still fighting ...` / `EX2 BASELINE boss=off ...` | once at 90 s | the verdict; `BASELINE` is the switches-off comparison run, not a verdict |

Every fact in a `SEEN` line comes from the server, never from the scenario's intent: `target` is
`BotAttackDriver.qaLastSwing`, recorded in the same block that writes the `ATTACK` QA line
(`src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:449-456`), so it costs nothing unless
the `ATTACK` topic is on - and ex-2 asks for it.

## The two runs

Same build, same command `!qa run ex-2 <your alt>`, same window:

```
java -Dslums.mechanics.boss=true -Dslums.bossfight.damagefloor=true -Dslums.bossfight.deathcount=true ...
java ...
```

Compare the `EX2 SEEN swings/dmg` totals and the verdict line. On this branch (off `release-1.6.4`) the boss lane is not
merged, so the three boss switches do not exist yet: `EX2 START` reads `unset` for them and `slums.mechanics.boss` never
equals `true`, which makes every run here `EX2 BASELINE`. That is deliberate - the scenario must not claim a verdict for
a build that has nothing to switch on. It becomes a two-run comparison the moment `164-bossfight` lands.

## What is NOT proven

- **Nothing here has run on a server.** There is no `logs/qa/ex-2-*.log`: the numbers above are line shapes and unit
  tests, not a measured run. The first `!qa run ex-2` is the proof.
- **The alt's arrival is not asserted.** `ex2StageAlt` returns -1 with the service's own refusal when the name is not
  the GM's alt, but a summoned alt that then fails to stay in the party is only visible in `EX2 SEEN`.
- **A summon that takes longer than the tick.** `ex2StageAlt` is synchronous (it loads the character); staging blocks
  the QA thread for as long as that takes, and the first samples can therefore be late.
- **No auto-QA check (`t-ex-2`).** A check needs a character name that differs per account, and auto-QA has no way to
  create an alt; the operator path is `!qa run ex-2 <alt>`. `autoqa-proof` maps the changed files either way.
- **The recruit map is assumed to be `211042400`** (both `ZakumBattle.js:33` and `ExpeditionType.ZAKUM`); a different
  server conf would make `registerExpeditionTeam` register nobody and the run would fail with
  `nobody reached 280030000`.

## Risks

- `tickSeconds() == 1` means one `EX2 SEEN` line per member per second; a six-member run for 150 s is about 900 lines
  in the QA log. That is the point of the scenario, but it is not a nightly check.
- `ex2EndExpedition` disposes the instance and ends the channel expedition at `!qa stop`. If the script has already
  disposed the instance, `isEventDisposed()` guards it; a failure there is a yellow message, never a crash.
- The verdict is written once, at 90 s. A run that clears after 90 s still reads `EX2 END still fighting` - read the
  `EX2 RUN` lines for what happened after the verdict.
- `EX2 FAIL` blames the first thing missing, in order: abandoned, altar never opened, nobody in the room, no swings, no
  damage, run ended, still fighting. A reader who wants the whole picture reads the `SEEN` lines, not the verdict.

## What a human has to do at the keyboard

1. On TEST, create or have an alt on your account (in game: `!qa do alts` makes up to two, `oa-2`), note its name.
2. Stand anywhere; the scenario warps you to `211042400`. Be level 50+, with no party or leading one.
3. `!qa run ex-2 <altName>`; wait for the yellow lines, then about 150 s.
4. Watch for `EX2 ALTAR ... hit reactor 2111001` - the click is the scenario's, against your client, exactly as a
   player's click would be. If it prints `no reactor 2111001 on 280030000`, the room was not the altar room and
   everything after it is meaningless.
5. `!qa stop` - it ends the expedition, disposes the instance, sends the alt home and takes the bots out.

## Files changed

| File | Change |
|---|---|
| `src/main/java/soloMapling/qa/BossRunScenarios.java` | new: the `ex-2` scenario, its sampling state and the pure `verdict(...)` |
| `src/main/java/soloMapling/qa/QaWorld.java` | new `ex-2` face: `Ex2Seen`, `ex2See`, `ex2Parts`, `ex2Altar`, `ex2StartZakum`, `ex2StageAlt`, `ex2DismissAlt`, `ex2EndExpedition`, `ex2Run`, `ex2Switches` |
| `src/main/java/soloMapling/qa/GameQaWorld.java` | the server side of those ten, plus the imports they need |
| `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java` | `QaSwing` + `qaLastSwing`, recorded inside the `ATTACK` QA block (no cost when QA is off) |
| `src/main/java/soloMapling/qa/QaScenarios.java` | registers the namespace |
| `src/main/java/soloMapling/menus/QaMenuCatalog.java` | `ex-2` beside `ex-1` |
| `docs/ops/qa-suite.md` | the `ex-2` section and its two-run procedure |
| `src/test/java/soloMapling/qa/BossRunScenariosTest.java` | 12 tests: every verdict branch, `endedBecause`, the switch parsing, and that `ex-2` is registered and asks for a tick |

## Reproducing the checks

```
cd /root/work/164-ex2
mvn -o -q test -Dtest=BossRunScenariosTest        # 14 / 0 / 0
mvn -o -q test -Dtest=QaMenuTest,AutoQaCatalogTest
mvn -o test                                        # full suite
```

Full suite result: `Tests run: 8375, Failures: 0, Errors: 0, Skipped: 137`, BUILD SUCCESS, 26:23, on the committed
sources (`mvn -o test`, Java 21, this worktree).

## Commits

| Commit | What |
|---|---|
| `1fd2f786c` | the scenario, its world face, the swing record, the doc row, the 14 tests and this report's first version |
| this commit | the report: the full-suite result above and the commit list itself |

