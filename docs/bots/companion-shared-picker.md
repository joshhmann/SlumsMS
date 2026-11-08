# companion-shared-picker: in-run regulars go through the one shared target picker (1.6.7)

Card: companion-shared-picker. Switch: `-Dslums.companion.picker.regulars` (regulars off by default; owned alts on,
which this card does not use). Written before code, on branch `167-companion-shared-picker`
@ be70c0559 (`release-1.6.6` 84a97be27 + PartyCompanionPolicy).

## What was true before this card

Two pickers decide what a companion attacks:

| Path | Who | Rule |
|---|---|---|
| `OwnedAltCombat.target(owner, alt, party, now, skip)` | owned alts, and a party regular **outside** a run (`FollowerFight.target` line "in its leader's party") | engaged by the party (`ENGAGED_MS`), inside the owner leash (or the instance leash switch), never a stranger's (KS manners), attackable surface, boss-part reach gate, `skip` |
| `FollowerFight.nearest(bot, map, at)` | party regulars **inside a run**: a dojo floor, `ExpeditionHold.inRun`, the leader's party quest | nearest alive targetable mob, attackable surface, boss-part reach gate — no engaged rule, no leash, no KS rule |

So the two later cards (assist, boss kill order) that add sort keys to the shared picker could not reach a regular
inside a boss run or a party quest without rewriting the in-run path. That is exactly the 1.7.1 switch-over this
overhaul is built for.

## What is true after this card

1. `OwnedAltCombat` has an explicit **in-run mode**, `targetInRun(bot, skip)`, that keeps every filter `nearest()`
   and the shared picker both apply — alive, `BotAttackDriver.botMayTarget`, `BotAttackDriver.onAttackableSurface`,
   the boss-part reach gate when `FollowerFight.reachGateOn()`, and the caller's `skip` predicate — and drops the
   three that only make sense for an owned alt beside its owner: the leash, the "engaged" rule and the KS-manners
   ("never a stranger's") rule. Inside a run the whole instance is the party's fight.
2. Both pickers rank through **one** method, `OwnedAltCombat.ranksBefore(Monster a, Monster b, Point at)`. It asks
   the new sort-key hook `OwnedAltCombat.priority` (a `Comparator<Monster>`, returning 0 today) first and falls back
   to straight distance, ties going to the earlier candidate in map order. Assist and kill order plug in there.
3. `FollowerFight.target` uses the shared in-run picker only when
   `PartyCompanionPolicy.on(Feature.SHARED_PICKER, bot)`; that is ON for an owned alt (which never reaches
   `FollowerFight`) and OFF by default for a regular, so **with the defaults a regular's in-run pick is
   `nearest()`, bit for bit as before**.
4. Owned alts' own path is unchanged: `OwnedAltCombat.target` keeps the leash, engaged, strangers, surface, gate and
   skip in the same order, and only routes its final ranking through `ranksBefore` (identical result: distance, ties
   to the first candidate).

No assist and no kill order in this card. Nothing is ported from another repository (no `Ported-From` trailer).

## How the tests prove it

New class `src/test/java/soloMapling/ArtificialPlayer/BotTypes/FollowerFightSharedPickerTest.java`, a regular
(`BotClient`, not an owned alt) in its leader's party quest instance so `instanceFight(...)` holds:

| Test | Defaults | `-Dslums.companion.picker.regulars=true` |
|---|---|---|
| `aRegularInARunTakesTheNearestMobByDefault` | picks the nearest — **passes before and after** (the no-change proof) | — |
| `withTheSwitchOnAJobTheSharedPickerRejectsIsNotPicked` | — | the nearest mob is on a surface `onAttackableSurface` refuses (`BotAttackDriver.ledges` stubbed) and is **not** picked; the farther mob is — **fails before, passes after** |
| `withTheSwitchOnThePriorityHookRanksTheFarMobFirst` | — | `OwnedAltCombat.priority` set to rank the farther mob first and the far mob is picked — **fails before, passes after** |
| `withTheSwitchOnAMobBotMayTargetRefusesIsNeverPicked` | — | a friendly (HPQ Moon Bunny style) mob nearer than the real one is never picked |

Then the existing proofs stay untouched and green: `OwnedAltCombatTest`, `OwnedAltInstanceFightTest`,
`OwnedAltBossReachTest`, `FollowerFightTest`, `FollowerFightExpeditionTest`, `FollowerFightPqTest`,
`FollowerFightReachTest`, `FollowerPartyFightTest`, `PartyCompanionPolicyTest`.
