# Normal equipment through 200, then forged rebirth progression

Status: draft for coordinator triage; no release-book number assigned or board state changed.
Requested by the owner in the area/gear balance discussion, 2026-11-02.
Lanes: Server (lead: drops, crafting, rebirth, data and QA), Client (combat/equipment parity and real-client QA).
Target release: coordinator to schedule; this ticket does not add work to the current release freeze.
Related: [81: imported content](81-post-big-bang-content.md), [30: rebirth](30-bot-rebirth.md).
Audit branch: `area-balance-codex`; supporting commits `0bf622cf9`, `7a84c2191`, `77ca3526e`, `7e2f5a7da`.

## Problem and intended result

Friends report dying quickly in imported level-100+ areas. Existing treatment changes HP/EXP but not the full
combat curve; gear access, client/server attack parity and crafting power are not yet established. Common armour
is easier to acquire than some class weapons, and the forge has long material chains and very large cumulative
fees. Raw base item stats do not describe a crafted item that inherited previous items' stats.

**Owner-approved direction:** normal dropped equipment must support a complete, viable RB0 path from level 1
through 200, including the bosses needed to earn RB1. Forging then becomes a gradual part of rebirth progression,
and attainable forged profiles become the expected equipment for later rebirth content. Normal progression must
not stop at 140 or require a rebirth-locked forged item to finish the first run.

The 1–99 route should receive a regression check; the substantive audit/tuning scope is imported content at 100+,
the missing high-level normal equipment path, and the transition into forging. “Normal through 200” means useful,
obtainable progression and viable combat, not merely permission to grind to 200 in an old set.

## Agreed structure

| Stage | Equipment and encounter rule |
|---|---|
| RB0, 1–200 | A full class-correct normal drop route, with useful upgrades through the 150/160–200 bands; no mandatory forging or rebirth-locked item |
| RB1 | Normal gear bridges into affordable, attainable Royal Von Leon; first material clears work in previous-tier gear |
| RB2 | Progress into Lionheart / Dragon Tail / Falcon Wing / Raven Horn / Shark Tooth; later encounters may expect its measured crafted profile |
| RB3 | Japanese class sets → AbsoLab → Arcane; weapons continue Sealed Genesis → Genesis, with separate progression bands within RB3 |

All equip level/stat requirements remain explicit. The same rebirth count does not make every map equally hard.
Existing area gates are not changed by this design. Each milestone must be beatable in gear available **before**
its first reward, with a realistic friends party and no unstated carry from stronger players/bots.

## Evidence and known issues

See [combat/loot audit](../economy/new-area-balance-plan.md), [class ladder](../economy/new-area-equipment-ladder.md),
[rebirth/forge analysis](../economy/rebirth-and-forge-progression.md) and [complete forge catalogue](../economy/forge-catalog/full-list.md).
The catalogue covers 357 recipes, 581 distinct inputs, 443 scoped equipment candidates and 112 complete class
loadouts. [Loadout CSV](../economy/forge-catalog/complete-loadouts.csv) includes cumulative fees and rolled-up inputs.
These are retained repository/XML facts, not deployed-state receipts.

- Current explicit equip lists generally require RB1 for Royal, RB2 for level-140 class boss sets, and RB3 for
  Japanese/AbsoLab/Arcane/Genesis. Normal high-level loot cannot simply depend on those locked IDs.
- Lionheart Battle Scimitar 1402095 is absent from the RB2 list. Verify intended policy before correcting it.
- Every pirate AbsoLab armour recipe consumes warrior Amaterasu's Helm 1003601 instead of its matching pirate
  piece. Preserve existing inventory and review compensation/transition needs before changing recipes.
- Craft/Maker levels are declared but not consistently enforced in the inspected shared script path. Sealed
  Genesis/Genesis declare craft levels 225/250 while both have raw equip level 200. Reproduce the intended gates.
- Transfer adds old actual attack/stats, configured percentage bonuses and enhancement bonuses to the new base.
  A dropped copy and a fully forged copy of the same ID can differ greatly. Five warrior Arcane armour pieces
  plus a Genesis 1H sword have 14.415B cumulative base forge fees before materials/refining/enhancements.
- Player rebirth redistributes retained AP in the enabled reset path; the inspected path does not explicitly clear
  skills or reset HP/MP. Verify actual retention and re-equip behavior before defining rebirth combat profiles.
- Frozen Rose's retained server PA/MA is 2310/2255 versus 900/900 in the retained client IMG. Human incoming
  damage is read from a client packet. Server-only attack edits do not prove a change in real-player damage.
- The broad imported map audit has 464 missing/unreadable map/mob references. Complete affected release scope
  coverage; do not call the current retained inventory exhaustive or treat encounter actors as ordinary mobs.

## Work packages

1. **Establish active inputs and reference characters.** Record installed server/client data versions, migration
   050/055 state, active drops/cache, actual rates, rebirth/transfer configuration and relevant source reachability.
   Use permitted privacy-preserving reads under the assigned lane's rules. Collect class, job, level, RB, AP,
   HP/MP, skills, actual item stats/provenance, buffs, hit samples and net income. Avoid a universal RB multiplier.
2. **Design the normal 100–200 loot path.** Publish a per-family/per-weapon/slot matrix at 100, 120, 140, 160, 180
   and 200 reference bands, allowing justified intermediate tiers. Show a useful late normal progression, safe
   source map, effective acquisition time, equip requirements and first-clear loadout. Reuse compatible existing
   items where suitable. If distinct normal versus forged variants are needed, select a supported representation
   deliberately: the current RB restriction is global per item ID, so changing it can unlock all copies.
3. **Make the rebirth forge handoff attainable.** Set reviewed stage acquisition budgets; work backwards into
   fees, quantities, supported sources and clear limits. Prefer a visible previous piece + relevant boss trophy +
   region resource + fee structure over unrelated material lists where appropriate. Audit every legacy input and
   late token, including Genesis Fragment. Provide weapon/ammunition access and a viable class-switch path.
4. **Resolve crafting/restriction issues.** Reproduce the exceptions above, choose intended policies, then add
   fail-before tests and fixes. Choose and simulate transfer behavior before balancing encounters around it.
   Validate input identity/slot, consumption/refunds, optional enhancements, quantities and duplicate attempts.
5. **Calibrate mobs/rewards together.** Start with a small representative imported 100–140 pilot, then 140–170,
   170–200 RB0 and staged rebirth content. Measure damage/misses, hits survived, time to kill, potions, EXP and
   net mesos for previous-tier, normal baseline and crafted profiles. Preserve shared classic IDs, encounters,
   friendly actors and boss mechanics. Synchronize client/server leaves or implement a separately reviewed damage
   path with real-client proof; HP/EXP alone is insufficient.
6. **Generate and ship reviewed changes.** Use deterministic manifests and diffs; new tracked/idempotent SQL and
   rollback for loot changes, matching server/client artifacts where required, release switches/notes and targeted
   auto-QA. The assigned lanes own TEST deployment and client testing; live remains a separate owner action.

## Making future adjustments easy

Start with versioned CSV/JSON source data and an offline validator/generator, building on `tools/economy` and the
existing recipe JSON. Provide one editable progression manifest keyed by item/mob ID, region/role, level/RB band,
class/weapon/slot, source, stats, chance/quantity or recipe, and acquisition budget. Use references to recipes and
set bonuses rather than keeping conflicting duplicate copies of the same values.

A `validate → preview → export` workflow should show affected mobs/items/NPC recipes, before/after values,
class coverage, missing/circular sources, global item-ID consequences, client/server differences, expected waits
under explicitly selected rates and rollback ownership. Keep installation/deployment separate from preview.
No admin dashboard is required for the first implementation; a UI can be considered after the source model is
stable. Do not use the generator to overwrite already shipped migration 050/055 as a new release fix.

| Change | Existing implementation surface | Work needed |
|---|---|---|
| Loot items/chances/quantities | `gen_bb_drops.py`, planned CSVs, `drop_data` migrations | Extend curated tiers/coverage, emit a new migration/rollback, verify rate semantics and cache state |
| Nimble recipes and dialogue | `scripts/json/{gearSets,weapons,otherAccessories,refiningMaterials}.json`, `9977779.js`, shared crafting scripts | Curate requirements, fix validation, preview full chain cost, test the actual NPC flow |
| Rebirth equip restrictions | `ItemConstants.java`, `ItemInformationProvider` | Review per-ID exceptions and semantics; code change, tests and server build |
| Monster HP/EXP | `mob-stat-overrides.tsv`, `MobStatOverrides` | Data edits plus rebuild because the override is a jar resource; test final effective values |
| Incoming combat damage/accuracy/defence | `LifeFactory`, client Mob data, human packet handling | Audit source of each stat; paired data/code changes and real-client combat checks |
| New normal items, slots or graphics | Retained item data, server item reader and client assets | Prefer supported slots/types; verify animation/art/equip behavior and package required client changes |

Drops have a cache invalidation command in the current code; the lane must verify reload scope and results.
Do not assume every script, set catalog, jar resource or client WZ hot-reloads the same way. The lowest-effort
safe pilot reuses supported items/NPCs, edits reviewed data and ships a small reversible batch. End-to-end combat
and forge validation require measured TEST sessions, not just editing numbers.

## Acceptance criteria

- [ ] Every supported class/weapon has a documented obtainable normal path through level 200, including useful
      160–200 upgrades, consumables/ammo, appropriate armour/accessories and a way to meet stat requirements.
- [ ] RB0 can train the intended route and earn RB1 in normal gear. Each later first material/boss clear succeeds
      in prior-tier obtainable gear; no circular reward requirement or mandatory unannounced carry.
- [ ] Every selected item/material source is verified against the installed data or exercised reward path, with
      active migrations/cache state recorded; missing catalogue sources are resolved or explicitly excluded.
- [ ] Actual acquisition times/clear limits and net income support approved stage budgets for all five families.
      Fees/quantities/chances are reviewed; a single rare weapon cannot indefinitely block basic progression.
- [ ] Craft and equip requirements are clearly shown, consistently enforced and tested at boundaries. Pirate
      inputs and restriction exceptions match reviewed intent; inventory ownership/consumption remains correct.
- [ ] Dropped/fresh-crafted/full-chain-crafted power is measured, and a versioned transfer policy defines the
      reference profiles. Player rebirth AP/HP/MP/skills/equipment persistence is verified through relog.
- [ ] Normal 100–200 and rebirth combat passes class-aware survivability/DPS/potion targets, with defensive buffs
      and mage MP states tested. Client/server parity and real-client results are recorded; bot success alone is
      insufficient. The proposed 3–5 ordinary-hit target remains a calibration proposal until reviewed/measured.
- [ ] Fail-before regression tests for confirmed fixes; migration apply-twice/rollback-twice tests; required server
      checks and targeted auto-QA; real-client forge/equip/drop/combat checks with exact build/data versions.
- [ ] No unintended classic-map/shared-ID, quest/card drop, set, starter 1–99, boss mechanic or gate regression.
      Rollback, release configuration, status and notes accompany the implementation.

## Decisions still needed

- Intended hours/clear counts for each normal and forged stage, and whether boss trophies have a choice/catch-up
  mechanism. Normal gear through 200 and the normal-to-forged structure are already approved.
- Transfer policy: current cumulative actual-stat carryover, enhancement-only carryover or a bounded alternative.
- Exact normal 160–200 item roster/representation, recipe simplification, numerical fees/rates/quantities,
  intended craft milestones and handling of already-crafted items. No numbers were approved by this ticket.

Draft patch-note intent for the eventual release: “Build your equipment through level 200 from monster drops,
then strengthen it through rebirth crafting with a clearer upgrade path.” Publish only after implementation.

Ticket preparation is documentation only. No gameplay changes, remote actions, deployment, board publication
or in-game validation were performed. The coordinator assigns the tracking ID, target version and child work;
the lane can split data coverage, crafting fixes and paired combat work without losing this end-to-end acceptance.
