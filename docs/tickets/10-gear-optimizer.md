# 10: Equip the best owned gear

**Area:** Real bots  
**Depends on:** 05  
**Status:** done 2026-10-19 on branch `gear` (from `combat`), not yet merged

## What

Port cosmic_agents' gear optimizer (stat-requirement chaining) behind a gateway; respect MapleRoot custom equips by refusing unknown ones.

## Done when

Bots wear their best eligible gear after loot; tests.

## Result

- **Planner (`BotGearPlanner`, pure):** after cosmic_agents' `AgentEquipmentOptimizer`, cut down for bots that carry a
  handful of equips. It climbs from what the bot wears, best move first, until nothing improves (4 passes at most):
  - one piece into one slot;
  - a top with pants (the only way out of an overall);
  - a piece together with one other piece that lifts the stat it needs (requirement chaining).
  - Up to 6 candidates per slot (highest level first).
- **Rules:**
  - Every new piece must go on in some order from the base stats, each one's STR/DEX/INT/LUK met by the base plus
    the pieces before it, as `canWearEquipment` checks one equip at a time.
  - Level, `reqJob` (cosmic_agents' job-niche bits) and fame as the server checks them.
  - What the bot already wears counts as wearable: the server never strips a player whose stats fell, and the
    decorator's gear stays on until something better comes.
  - Slots: cap, face, eye, earring, top, pants, shoes, gloves, cape, shield, weapon. Not rings, pendants, belts or
    medals, and never the cash overlay (-1xx), so an NX outfit stays as it is.
  - Nothing is emptied except pants under an overall and a shield under a two-handed weapon, and only when the equip
    tab has a free slot. A top never goes on alone where an overall was. A shield only with a sword, dagger, wand
    or staff (`BotWeapons.takesShield`).
  - A change must be strictly better: damage first, then defence, accuracy, avoid, HP/MP, speed, jump and stats.
    No churn on ties.
- **Weapons:**
  - Only the job's kinds (`BotWeapons.fits`), and the attacks the bot draws (`BotAttackConfig.resolve` with its
    skills) must all fit the new weapon (`BotSkillActions.weaponFits`) and be the same attacks as with the old one.
  - A 1st-job bot keeps its weapon kind (its weapon picks its 2nd-job branch). A beginner keeps its kind, and with
    no weapon takes a melee one.
  - A weapon the job cannot use loses to any it can.
- **Refused equips:** not in `EquipMetadataCache` (no WZ data, or outside the stock ranges: MapleRoot's custom
  equips), cash, the other gender's, on `EquipOmitList` (broken sprites), rebirth-locked.
- **Value (`BotDamage.gearValue`):** the average line of the bot's main attack (single, else mob, else ultimate;
  a plain swing where the weapon cannot draw it, as the driver does) with crits, from the bot's totals moved by
  the loadout's gear delta. Mastery, ammo and attacks come from the weapon weighed. Magic attack counts INT as
  the server's totals do.
- **Gateway (`BotGear`):**
  - `afterLoot` runs on an equip picked up on screen (`DropCommands`, after the drop's lock is released) or granted
    by the offscreen accrual (`TrainingBot`).
  - `afterProgress` runs after every level-up, on screen or offscreen, and job advance (`BotProgression`).
  - Nothing runs per tick. A bot carrying no wearable equip costs one pass over its equip tab.
  - Moves happen under the bot's monitor: the new piece takes its body slot and the old one takes the new piece's
    bag slot; a displaced piece goes to the next free slot.
  - Every piece is checked to still be where the review saw it, so a trade or sale in between stops the moves.
  - Then `equipChanged` shows the look and re-derives the totals, so the offscreen combat cache picks up the new
    gear.
  - Untradeable-on-equip items get their flag, as for players. `BotGear.ENABLED` turns the optimizer off.
- **Persistence:** the moves are ordinary moves between the EQUIP and EQUIPPED tabs. The bot store's next sweep
  writes them with the rest of the holdings, and the ledger is untouched because no value changes hands.
- `EquipMetadataCache.find(id)` added; `BotWeapons.kindOf` and `takesShield` made public.
- **Tests:** 2555 run, 0 failures (17 new):
  - `BotGearPlannerTest`: stronger weapon, no churn on ties, sturdier on a damage tie, level/job/unknown refused,
    weapon rule, requirement chaining and mutual requirements refused, overall and pants with and without a free
    slot, out of an overall only into top+pants, 2H weapon and shield.
  - `BotGearTest`, on a real character's inventories: the swap and its bag slots, beginner and 1st-job weapon kinds,
    overall, unknown gear, a piece sold mid-review.
  - `BotDamageTest`: the yardstick equals the swing once the gear is worn.
- **Not done:** the headless check on 164, because `real-stats-test` was busy with the supplies and travel runs.
- **Open issues:**
  - Rings, pendants, belts and medals are not optimised. The cache does not scan pendants, belts or medals, and
    rings may be friendship or wedding rings.
  - No accuracy term: Cosmic does not load mob avoid (see 07), so accuracy only breaks ties.
  - The climb finds a local best. A chain needing more than one helper piece is missed.
  - A new weapon kind and a cash weapon overlay are not matched to each other: a 2H swap keeps a 1H NX sticker.
  - Shown buffs are taken as always up (as in 07).
