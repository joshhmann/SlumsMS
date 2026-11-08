# alt-supplies-in-boss-fights (1.6.7): expected outcome

Card: alt-supplies-in-boss-fights. Feature switch `PartyCompanionPolicy.Feature.SUPPLIES` (key `supplies`):
owned alts ON by default, party regulars OFF unless `-Dslums.companion.supplies.regulars=true`.

Written before the code (repo rule).

## Why

TEST Horntail, 8 Nov 2026 04:09-04:13 UTC: the alts came in with almost no potions (HP pots 0-7, MP pots 0). Two
Hero alts swung Brandish 47 times a minute until the MP ran out (13/1200), then only basic attacks, and both died in
room 3. Nobody was told anything - not before the run and not while it happened.

Two causes in the code:

- `BotVitals` drinks HP pots only under `HP_POT_AT = 0.50` (a habit value on observed maps) and MP pots only under
  `MP_POT_AT = 0.30`, which is far too late for a Brandish Hero in a boss room.
- Nothing anywhere assesses or announces potions for a boss run. `ExpeditionBots.shortfall` names the people who
  are not joining and why, but says nothing about the ones who are joining empty-handed.

## Expected outcome

1. **Boss thresholds.** While a companion with the switch on is inside a boss run (`ExpeditionBots.inRun`), it
   drinks HP pots under `BOSS_HP_POT_AT = 0.70` of max HP and MP pots under `BOSS_MP_POT_AT = 0.50` of max MP (the
   nutnnut defaults, ported). Outside a run, and for anyone the switch is off for, `BotVitals` behaves exactly as
   today (habit / 0.50 and 0.30). The MP top-up of a single swing reaches the same thresholds: `SwingSupply.commit`
   calls `BotVitals.spend`, which already ends in `topUp`, so a Brandish alt at 45% MP drinks an MP potion instead
   of falling through to basic attacks.
2. **Out-of-pots warning.** A companion tells its owner (owned alt) or its leader (party regular) at most once per
   run at `WARN_AT = 10` potions of a kind and again once when that kind reaches 0. Wording is casual and never says
   "bot": "I'm down to 10 mana potions", "I'm out of HP potions". Owned alts speak through `OwnedAltOrders.say`
   (party chat when the owner is in the party, direct otherwise); a party regular speaks through
   `BotPartyChat.sayInParty` to its party. Same level is never said twice in one run; a new run starts clean.
3. **Pre-run check.** When the leader talks to the recruiter, `ExpeditionBots.shortfall` adds one line per member
   that will join but is short: fewer than `PRE_RUN_MIN = 50` HP potions, or fewer than 50 MP potions when the job
   uses MP (`BotSupplies.needs(c).usesMp()`), or no ammo for a ranged job (`BotSupplies.outOfAmmo`), or no usable
   weapon (the `OwnedAltCompanion.weaponProblem` rule). Joining is not blocked - the leader is only told.
4. A regular (not an owned alt) sees none of this with the default settings, and all of it with
   `-Dslums.companion.supplies.regulars=true`.

## Proof (each test fails before the change)

All in `src/test/java/soloMapling/ArtificialPlayer/BotSupplySystem/CompanionSuppliesTest.java` (that package owns
the `SupplyData` seam the potion data comes through), one boss run built for real through `ExpeditionHold`:

- `inABossRunAnAltPotsAtSeventyPercentButOutsideOneOnlyUnderHalf` - an owned alt at 65% HP with HP pots drinks in a
  boss run; the same alt outside the run does not drink at 65% (today's 0.50 line). (a)
- `aBossRunAltDrinksManaOnItsAttackPath` - an owned alt at 45% MP in a boss run gets an MP potion through
  `SwingSupply.prepare` + `commit` (the attack path), i.e. it never falls back to "no MP". (b)
- `theOwnerIsToldOnceAtTenPotionsAndOnceWhenTheyAreGone` - the warning is said once at `WARN_AT`, once at 0, and a
  second top-up at the same level says nothing. (c)
- `theRecruiterNamesTheMemberThatWillFightWithoutManaPotions` - the recruiter's text carries "Second has no mana
  potions." for a member with 0 MP pots and an MP job, and says nothing about a stocked one. (d)
- `aRegularKeepsTheOldLinesUntilTheRegularSwitchIsOn` - a regular is unchanged with the defaults and gets the boss
  threshold, the warning and the pre-run line with `-Dslums.companion.supplies.regulars=true`. (e)
- `aCompanionOutOfARunSaysNothing` - nothing is said outside a run.

The warning reads the stock before the top-up drinks, so it reports the count the companion actually has ("I'm down
to 10 HP potions"), and the level is only announced the first time it is reached in a run.

## Out of scope

Buying potions, town restock trips and pulling from the owner's storage: owned alts never buy (owner). The out-of-
pots warning does not trigger a restock; it only tells the person who can fix it.
