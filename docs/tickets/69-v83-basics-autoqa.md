# Ticket 69: auto-QA "v83 basics": the stock features a real player uses

Status: open, 2026-10-25. Owner OK'd. After ticket 68 (or alongside). TEST server only.

## Why

Our auto-QA covers the features we built (bots, chat, guilds, gacha). Nothing checks that a player can still do the
ordinary things v83 offers, and those broke silently (an NPC says "no options available", a delete fails, a PQ never
ends). Bots never exercise these paths.

## Do

Add an auto-QA family `vb-*` (pretend player, `!qa` staging, nightly with the rest), one check per basic feature,
each asserting the outcome, not just that nothing threw:
- `vb-1` hair, hair color, face, lens and skin at the Henesys salons with the right coupon (style actually changes).
- `vb-2` delete a character with PIC.
- `vb-3` clear one party quest (KPQ: stage the party, finish, reward given, warped out).
- `vb-4` the mount path: quest 6002 room and hand-in, 6007 Tatamo bottle, 6003 perfume, catch the hog in 922200000.
- `vb-5` buy and sell at an NPC shop, use a Cash Shop coupon (EXP card).
- `vb-6` a job advancement and a skill point spend.
- `vb-7` storage, Duey, trade between two pretend players.
Also: log every NPC dialog line the pretend player gets, and fail any check whose NPC says "no options",
"can't", "closed", "error" or similar when the check expects success.

## Done when

The `vb-*` checks pass nightly on TEST, each has failed at least once against a deliberately broken build (or the
pre-fix commit for vb-1/vb-3), and `docs/qa/QA.md` lists them.

## What was built (branch `151-v83-basics`)

- `src/main/java/soloMapling/qa/V83Scenarios.java` - scenarios `vb-1`..`vb-7`, registered in `QaScenarios.all()`.
- `src/main/java/soloMapling/qa/GameQaWorld.java` - `v83Step(Character, feature, step)`: the real paths (NPC scripts,
  shop `buy/sell`, `StorageProcessor`, `Trade`, `DueyProcessor`, `Quest`, `EventManager`/`EventInstanceManager`).
- `src/main/java/soloMapling/autoqa/AutoQaCatalog.java` - checks `t-vb-1`..`t-vb-7` (`shortId()` = `vb-N`).
- `src/main/java/soloMapling/autoqa/Expects.java` - `noRefusal()`: fails on any `NPC_REFUSAL` line. The scenario emits
  that token only for a refusal it did not expect, so the wrong-PIC and broke-Tatamo steps stay green.
- `src/main/java/client/SyntheticClient.java` - every NPC dialog is mirrored to `QaLog.Topic.NPC` as one line
  `NPC <npc id>: <text>`; shop and delete responses are decoded so the checks can assert the server's answer.
- `src/test/java/soloMapling/autoqa/V83ChecksTest.java` - each check passes on its scenario's outcome lines and fails
  on the broken outcome (no change, refusal, wrong response, no reward, no fee, empty transfer); it also asserts every
  `!qa do <step>` a check types is one the scenario's `usage()` offers, so a rename on either side cannot go quiet.
- `src/test/java/soloMapling/qa/QaScenarioStagingTest.java` - `everyVbScenarioStagesItsOwnFeatureId`: each scenario
  stages through `QaWorld.v83Step` with its own id, `!qa do bottle` reaches the world as `vb-4 bottle`, arguments are
  appended (`hog 1`), and the family registers nothing to undo so `!qa stop` is clean.
- `docs/release/1.5.1-qa.md` - the seven `t-vb-*` rows (what to type, what to look at, what auto-QA already asserts).

## Commands for TEST (not run: the deploy lane is paused)

```
tools/ops/autoqa-run vb-1,vb-2,vb-3,vb-4,vb-5,vb-6,vb-7
```
or in game: `!qa run vb-7`, `!qa do storage`, ...; per check `!qa run vb-N`, `!qa do <step>`.
Success lines are the ones in `docs/qa/QA.md` (the `VB ...` column). Without `wz/` the delete/mount steps cannot run.

## Failure proofs

- `vb-1`: reverting `1fe365733` and running `V83ChecksTest#salonStyleIdsAreRecognisedAsCosmetics` fails with
  `a cash face coupon (51520) is a face item ==> expected: <true> but was: <false>` (throwaway revert dropped).
- `vb-2`,`vb-3`,`vb-5`,`vb-6`,`vb-7`: the unit test feeds each check the outcome a broken feature would produce and
  asserts FAIL (no delete response, uncleared PQ, no reward, rate 1 -> 1, job 100 -> 100, empty transfer). The live
  end-to-end break was not run because it needs the TEST server, which the owner asked us not to touch.
- `vb-3`'s `039d13164` revert and the hog catch are **not** proven here: no headless seam.

## Suite

`mvn -o clean test` on 2026-10-24: `Tests run: 4595, Failures: 0, Errors: 0, Skipped: 38`.

## Not done

- The hog catch in `vb-4` is only logged; WZ item effect, no server hook.
- `vb-2` and `vb-4`'s 6002 room need WZ; `WzTestData.assumePresent` gating is only in the test suite, not the staging.
- No run against TEST was made; `mvn -q -o test` is the evidence available.
