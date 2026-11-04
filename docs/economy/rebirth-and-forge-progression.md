# Rebirth, equipment and Nimble's full forge progression

This document preserves the original offline audit. **Historical cost/input findings below are superseded by
the current1.6.4 candidate** in [release notes](../release/1.6.4-gear-progression.md),
[stage policy](forge-stages.json) and [exact recipe diff](forge-economy-diff.csv).
The owner selected normal gear through200, then8–12 hours per forge stage, retaining actual input HP/MP.
All237 main equipment recipes plus the existing Genesis Fragment refining recipe are simplified locally;
requirements, pirate inputs, sword RB parity and refunds are fixed. No deployment or combat result is claimed.

[Implementation ticket](../tickets/280-normal-200-to-forged-rebirth-progression.md).
The refreshed catalogue below describes current candidate recipes; the later analysis retains original audit
costs to explain the problem. Current cumulative Arcane-armour/Genesis-weapon fee including Fragment refining
is286M for each supported class weapon, before optional enhancements.

## The full lists

- [Every forge recipe, with exact quantities and named materials](forge-catalog/full-list.md): **357 recipes**,
  covering 125 armour pieces, 112 weapons, 44 accessories and 76 refining recipes. Includes every class,
  Sealed Genesis and Genesis, rather than stopping at Arcane.
- [Recipe spreadsheet](forge-catalog/recipes.csv): current equip rebirth restriction, equip level, declared craft
  and Maker levels, stat requirements, raw attack, slots, fees, optional stimulant and all direct inputs.
- [Equipment upgrade chains and cumulative fees](forge-catalog/chain-totals.csv): **281 equipment recipes**,
  each with its prior equipment recipes expanded, exact remaining inputs and total base forge fees.
- [Complete class loadouts](forge-catalog/complete-loadouts.csv): **112 five-armour-plus-weapon bundles**, one
  for every supported class weapon at every forge stage, with combined fees and full remaining material bills.
  Sealed/Genesis bundles retain Arcane armour. This removes the need to add six item chains by hand.
- [All materials](forge-catalog/materials.csv): **456 distinct input IDs**, named and linked to the recipes that
  use them, with planned-drop counts and literal script references as investigation leads.
- [Equipment inventory](forge-catalog/equipment.csv): **443 equipment candidates**, combining the previous
  ordinary gear selection, every equipment recipe and all explicit rebirth-restricted IDs. This is the full
  scoped progression inventory, not every cosmetic or equipment item in Character.wz.
- [Class weapon/armour ladder](new-area-equipment-ladder.md) and [monster balance plan](new-area-balance-plan.md).

CSV files can be opened in a spreadsheet and filtered by family, item, rebirths or recipe section. Source rows
come from the local repository and retained `/root/work/bake/wz` XML. Installed drops, reachable sources, client
compatibility, deployed settings and real acquisition times remain unverified. A script reference can be a
requirement or dialogue, not a reward. No planned drop row is not proof that an item has no source.

## Owner-approved progression direction

Owner follow-up: normal gear should carry the first run and scale into forged gear through later rebirths.
Forging becomes part of the expected later progression, with attainable acquisition costs; it is not permanently
optional prestige above a dropped-gear baseline. This approves the structure, not specific fees, quantities,
transfer percentages or combat numbers.

| Stage | Equipment progression | Combat calibration rule |
|---|---|---|
| RB0, levels 1–200 | A complete normal drop path, including meaningful 160–200 upgrades; the exact late item roster is still to be selected | Normal gear supports the entire first run and the clears needed for RB1; forging is not required |
| RB1 | Retain usable dropped gear; gradually earn Royal Von Leon crafts | First relevant clears work in prior gear; later RB1 targets can assume the measured attainable Royal profile |
| RB2 | Progress into the matching Lionheart / Dragon Tail / Falcon Wing / Raven Horn / Shark Tooth crafts | Earn initial materials in prior gear, then use the attainable RB2 crafted profile for its later content |
| RB3 | Japanese sets → AbsoLab → Arcane; weapons eventually Sealed Genesis → Genesis | Separate bands within RB3, each earned using the previous tier; respect actual equip requirements and intended craft milestones |

Do not make every map at the same rebirth count require its final crafted set. Entry content supplies the first
upgrades; later encounters can expect them once the source and acquisition budget are established. Ordinary
gear stays useful as forge input, as a bridge while pieces are earned, and for class changes. A milestone must
never require the very item its own first clear awards. Account for actual transferred stats when defining the
crafted reference loadout.

## Current restrictions and intended progression

**Correction to the earlier equipment ladder:** equip level alone does not unlock most forged gear.
`ItemConstants.rebirth1/2/3` contains **299 distinct equipment IDs**, and
`ItemInformationProvider.canWearEquipment` checks those restrictions before level/stat requirements.
Royal Von Leon is generally RB1; the level-140 class boss sets RB2; Japanese sets and every later forge weapon/
armour family RB3. The updated companion ladder now states this explicitly.

| Current rebirth count | Existing gear restriction | Sensible ordinary profile for calibration | Earn the next milestone using |
|---|---|---|---|
| 0 | Classic, Eclectic 120, Muspell/Jaihin 130, Pensalir/Utgard 140 candidates are absent from the explicit RB lists | Attainable classic gear first, then the dropped route only as its access is proven | A 200 character and Zakum/Horntail clears; no Royal gear required |
| 1 | Royal Von Leon 130; various accessory unlocks | Previous dropped gear; Royal as an earned optional upgrade | Pink Bean and Von Leon clears; do not demand RB2 boss gear |
| 2 | Lionheart / Dragon Tail / Falcon Wing / Raven Horn / Shark Tooth 140; further accessories | Previous tier first, then earned RB2 set; calibrate newly open Arcane entry against this profile | Cygnus clear and emblem; no RB3 Japanese/AbsoLab requirement |
| 3, below 200 | Japanese sets 150 and AbsoLab 160 become legal by RB; level/stat requirements still apply | RB2 set during first new clears, gradually upgraded RB3 gear | Supported normal/early bosses and earlier-area materials |
| 3, 200+ | Arcane Umbra has raw equip level 200 | Attainable AbsoLab or earlier measured profile while earning Arcane | Later boss rewards and supported material routes |
| 3, intended 225 then 250 forge goals | Sealed Genesis / Genesis recipes declare 225 / 250, but raw equip level is **200** for both | Prior-tier crafted or dropped profile, recorded separately | Endgame boss tokens, class cores and fragment/liberation materials; reliable source audit needed |

These are **current code restrictions plus proposed calibration profiles**, not newly selected gates. Arcane
maps currently open at RB2 at any level; deeper Grandis at RB1. Future/lower Grandis can open at level 120
without rebirth. An open entrance is not a recommendation that a level-1 reborn can fight there. Keep prior
owner gate decisions while publishing recommended training bands and measuring actual characters.

Among the 281 forge equipment outputs, the explicit RB lists yield 58 at RB1, 52 at RB2, 169 at RB3 and two
absent from the lists: **Lionheart Battle Scimitar 1402095** and **Exiled Spirit medal 1142582**. The sword's absence
is an apparent inconsistency relative to its family. Confirm intended policy before changing either exception.
“Absent from the list” does not mean its recipe/materials are available at RB0.

## What rebirth currently does

The player NPC path is `scripts/npc/rebirth.js` at Wyatt (1300009). It requires the fully advanced job, level
**exactly 200**, fewer than three rebirths and the next Essence Stone. The key is consumed after confirmation.
The player selects Explorer, Cygnus or Aran starter family. `executeRebornAs` changes job, sets level to zero
then calls `levelUp(true)`, producing level 1. The separate `executeRebornAfterFirstCerezeth` method uses
`REBIRTH_RESET_LEVEL`; it is not the method this player NPC calls. The class max becomes 255 at RB3, subject
to the separate configured job-range enforcement.

| Transition | Essence Stone | Ingredients to make it at Wyatt | Consumption |
|---|---|---|---|
| RB0 → RB1 | Blue 4033446 | 1 Zakum Diamond 4032133 + 1 Nine Spirit's Egg 4001094 | Both ingredients, then the stone |
| RB1 → RB2 | Red 4033442 | 1 Rock of Time 4021010 + 1 Von Leon's Seal 4001693 | Both ingredients, then the stone |
| RB2 → RB3 | Black 4033450 | 1 Cygnus Circlet 4000659 while holding 1 Cygnus Knight Emblem 4032922 | Circlet and stone; emblem is retained as a pass |

`EssenceStones` documents Zakum, Horntail, Pink Bean, Von Leon and Cygnus as the source clears; the emblem is
from stronghold guards or Chief Alex's Lost Emblem quest. Verify actual clear rewards, entry limits and party
capacity before pricing a milestone in days. Those same clears/materials also feed gear recipes, so the next
rebirth and next gear piece compete for some resources.

The retained code does **not** justify treating a reborn player as a fresh natural-stat character:

- The normal non-beginner level-up branch awards 5/4/3/2 AP for RB0/1/2/3 before level-200 overrides. At levels
  200–209 / 210–219 / 220–229 / 230–239 / 240+, that branch awards 10/20/30/40/50 instead. Beginners and job
  advancement have additional rules; these numbers are not a complete total-AP calculator.
- The NPC calls `resetStats()`, whose enabled path redistributes the existing sum of remaining AP and four base
  stats. It does not discard that sum. When `USE_AUTOASSIGN_STARTERS_AP` is off, this reset method returns.
- The inspected player rebirth path has no explicit clear of learned skills or HP/MP reset. Job change adds its
  HP/MP gains and recalculates stats; level-up adds its own gains. Measure before/after AP, HP/MP, skills, equipment
  and save/relogin behavior on TEST rather than relying on older comments describing “fresh base stats.”
- EXP is switched to configured `REBIRTH_FIRST_RATE`, `REBIRTH_SECOND_RATE` or `REBIRTH_THIRD_RATE`. Their deployed
  values were not read. Do not use remembered x3/x2/x1 values as current facts.

This means level, rebirth count, retained AP/HP/skills and gear are separate axes. Test same-job repeat rebirths
and class switches, including cross-class defensive skills and requirement re-equip behavior. A blanket
“RB1 mobs do N times damage” rule will not describe all those builds.

## The forge chain and its actual cost

The forge is **Nimble, NPC 9977779**, with Equipment Crafting and Refining Minerals menus. Equipment menus read
`gearSets.json`, `weapons.json` and `otherAccessories.json`; refining reads `refiningMaterials.json`.

The principal armour chain is:

**Legacy input → Royal Von Leon → class boss set → Japanese class set → AbsoLab → Arcane Umbra.**

Weapons continue **→ Sealed Genesis → Genesis**. There are 16 supported weapon types across the five families;
the complete list gives every item and exact input, including the legacy piece. Accessories have their own
chains and are not silently included in the five-piece armour total.

| Stage | Raw equip level / RB | Five armour pieces: fee for this stage | Five armour pieces: all prior forge fees | Warrior 1H sword chain: all prior forge fees |
|---|---|---:|---:|---:|
| Royal Von Leon | 130 / 1 | 1.055B | 1.055B | 120M |
| Class boss set | 140 / 2, with sword exception above | 1.725B | 2.780B | 320M |
| Japanese class set | 150 / 3 | 2.845B | 5.625B | 700M |
| AbsoLab | 160 / 3 | 2.400B | 8.025B | 1.180B |
| Arcane Umbra | 200 / 3 | 3.770B | 11.795B | 1.660B |
| Sealed Genesis weapon | 200 / 3; recipe declares 225 | — | — | 2.140B |
| Genesis weapon | 200 / 3; recipe declares 250 | — | — | 2.620B |

These are base fees, without raw materials, previous legacy items, refining, reagents, stimulants, scrolling,
Star Force, travel or potions. Chain totals recursively expand equipment recipes, **not mineral refining**.
Buying/dropping a previous piece bypasses its forge fees, so this is the all-forged route, not a universal bill.

For a warrior's five Arcane armour pieces plus Genesis 1H sword, the chain is **14.415B mesos in forge fees**
before those other costs. That is a much larger decision than “the final recipe costs 480M.” Calibrate those
fees against measured human **net** meso income; do not infer a practical playtime from gross fixed-DPS models.

The initial pirate recipes consumed warrior Amaterasu's Helm for all five AbsoLab slots, producing the
incorrect5.900B/9.670B cumulative totals. The local1.6.4 correction now consumes matching Susano-o pieces;
all five families have8.025B through AbsoLab and11.795B through Arcane before weapons/materials. The old
numbers remain in the original audit evidence, while this branch's refreshed catalogue reflects the fix.
Existing inventories are not altered, and no deployed correction is claimed.

## Why two copies of the same item can have very different power

A `processStarforce` input is consumed. It is not just a token for recipe eligibility. The craft path calls
`applyStarForceEquipCraftingBonus`, which adds the old item's attack and four stats into the new item's base.
For an attack stat, before overflow handling, the inspected formula is:

`new attack = new base attack + old actual attack + ceil(old actual attack × configured attack percentage) + 2×floor(old enhancement level/2) + floor(old enhancement level/4)`

For each STR/DEX/INT/LUK stat, it adds the old actual stat, its separately configured percentage (rounded up),
and `2×floor(old enhancement level/2)`. Percentages were not read from a running server. This includes the old
item's actual stats, not solely the improvement over its original base. An old item that already received
transferred stats can pass those through the next chain again. Reagents/stimulants add further variation.

For illustration only, if attack percentage is zero and the input has enhancement level zero, a Royal 1H sword
with base 207 transferring to the boss sword with base 242 yields **449 attack**, before any other differences.
A dropped boss sword's raw base is 242. This illustrates the formula; it is not a measured inventory item or a
claim about the active configured percentage.

The method also consumes the selected equipment and grants Spell Traces; its refund call receives the output
item. Inspect actual refund amount and slot validation in the same TEST check. Set bonuses and final character
DPS/HP then sit on top of this item result. Record provenance, actual stats and enhancement state in every
balance test. The raw attack columns in the catalogue are **base values**, not predicted crafted results.

Recommended policy for review: retain an explicit upgrade benefit, but budget it deliberately. Candidate policies
are current cumulative transfer, transfer only earned enhancement above the old base, or a bounded carryover.
Pick and simulate one before adjusting monsters; raising monster difficulty to fit the strongest cumulative
forge product would punish ordinary dropped gear and players switching classes. No transfer policy changed here.

## Materials, repeat clears and source gaps

The full materials sheet is the exact per-ID inventory. Important recurring burdens include:

- Star Rock 4021009 and Moon Rock 4011007 each appear in **205 recipes**. Weapon/armour stages repeatedly demand
  these alongside region ETC, boss rewards and the previous piece. No 050/055 row for them does not negate their
  older refining/crafting routes; trace those actual routes and costs.
- Class Weapon Cores 4036544/5/6/7/8 serve warrior/mage/bowman/thief/pirate respectively. For the sample warrior
  sword, the nominal fully forged chain needs **31 cores through Arcane**, or **66 through Genesis**.
  The same chain consumes 15 Star Rocks, 15 Moon Rocks and 210 Magic Powders in total through Arcane.
- The Arcane sword's recursive equipment chain alone has **54 distinct remaining leaf input IDs** and **4,680
  input units**, including one legacy weapon, before expanding mineral recipes. Genesis grows to 61 IDs and
  4,742 units. Those are literal inventory quantities, not equal-difficulty tasks; the source and time per unit
  matter far more than the total count.
- `scripts/npc/3004006.js` has cleared-instance reward paths for Lucid Matter 4033732, Stone Cobweb Droplet
  4001890, Spark of Determination 4001893, Shadow of Annihilation 4001894, class cores and later tokens. The
  script distinguishes normal/hard maps and random core outcomes. These are repository reward-path evidence;
  verify event reachability, claims, clear limits and actual drops before promising acquisition times.
- Correction: Genesis Fragment4036539 already has a refining recipe in `refiningMaterials.json`. The original
  source scan missed that recipe. The candidate simplifies it to four hard-boss tokens plus1M mesos; verify
  actual boss access, claims and refining in TEST before declaring the endgame reachable.
- Royal uses Von Leon's Seal; class boss recipes use Cygnus Circlet; later recipes require earlier and later boss
  items. A milestone boss must be beatable in its **previous** equipment tier before its reward can be used to
  justify the next tier. Verify this explicitly for a small friends party, including healer/support classes.

## Issues to resolve before choosing economy numbers

1. **Restriction parity:** verify the Lionheart sword exception, accessory exceptions and the rebirth requirement
   of rare dropped AbsoLab. A low-rebirth player can otherwise earn a high-level reward they cannot equip.
2. **Pirate recipe inputs:** all five AbsoLab slots currently point to the same warrior helmet. Correct intended
   family/slot inputs with migration/communication considerations if players already own resulting items.
3. **Declared versus enforced requirements:** the shared Maker menu displays recipe `requiredLevel`, but its
   low-level message is nested inside the missing-material branch. With materials present, the inspected path
   does not independently reject a low-level craft. `requiredMakerLevel` is stored in `CraftingRecipe` but is not
   consulted by these menu/crafter paths. No independent recipe-RB craft check was found there. Equip restrictions
   still apply. Treat these as static findings requiring a focused TEST reproduction; do not claim the declared
   225/250 Genesis craft levels currently enforce the intended endgame timing.
4. **Transfer power:** compare a dropped item, a fresh craft and a complete-chain craft at every stage. Decide which
   profile the ordinary maps and bosses target. Check enhancement transfer, slot selection and refund behavior.
5. **Acquisition coverage:** prove the class weapon, every boss token, every legacy input and the uncommon quest ETC
   are obtainable by the *prior* tier. Separate unavailable sources from expensive but available sources.
6. **Rebirth stat retention:** verify actual post-rebirth AP, HP/MP, skills, job changes, re-equipping and relogging.
   This report proposes no AP wipe, HP reset, rate change or gate change.

## Proposed balance structure

Keep three separate progress tracks with explicit handoffs:

- **Training gear:** class-correct dropped equipment, common scrolls, ammo/potions and affordable accessory choices.
  Enough to train ordinary on-level maps and participate in the first boss of the next track. Use a dependable
  weapon choice/exchange if the existing rare weapon path cannot meet the acquisition budget.
- **Rebirth milestones:** the existing three Essence Stones from a short, visible boss checklist. Do not stack a
  full expensive craft set on top as an unstated rebirth requirement. Balance each first clear against the prior
  obtainable set and a realistic two-to-five-person party, then check the class that has the least survivability.
- **Forged progression:** the forge chain and boss accessories become the later-rebirth gear path, with tested
  carryover power and visible stage goals. Attainable crafted profiles can become the baseline for later content
  after their prior-tier acquisition route is proven; reserve the final refinements for prestige.
  Reduce requirement variety before simply raising global drops: retain the previous item, one appropriate boss
  trophy, one region resource and a calibrated meso fee as a possible simplified recipe structure. Optional extra
  materials can buy enhancement rather than block the basic piece. This is a proposal, not an approved edit.

Set target playtimes per **stage**, including all five armour pieces, one correct weapon, boss clear limits and
required farming; then work backwards into fees/quantities/chances using measured net income. Compare all five
families, one-handed shields versus two-handed routes, mage solo progression, and a player switching class after
rebirth. Offer a way to obtain a new family weapon without replaying every prior long-term cost. Decide whether
items can be traded or caught up before counting friend assistance as guaranteed access.

For staged implementation: resolve data mistakes and craft checks; measure current rebirth/transfer outcomes;
prove every source; apply the approved normal-to-forged progression and choose acquisition budgets; simulate per-class chains;
then tune ordinary mobs and first-clear bosses to those actual profiles. Keep existing gates/rates until a
specific reviewed change replaces them. The owner still needs to select transfer policy and intended time to
complete each stage; no economy choice was applied by this audit.

## Validation and limits

The read-only generator is preserved in the evidence folder as `gear/full_forge.py`, with raw recipes, input
hashes and `full-forge-checks.json`. Every one of the 357 outputs and456 current input IDs has a name and data in the
retained server dump; all 281 recursive equipment recipe expansions are acyclic. The generator checks unique
recipe outputs, exact recipe counts, one-output equipment recipes and the observed sword restriction exception.
Additional catalogue consistency checks cover all direct quantities, CSV counts, chain fees and required family
core IDs. These are data/document checks, not gameplay tests or client validation.

No Java or runtime script changed. No Maven suite, game launch, TEST deployment, live read/write or SSH was run.
The repository snapshot may differ from the deployed forge, drops, client and configuration. Give the server
lane this catalogue plus the specific TEST reproductions above before turning the proposals into runtime edits.
