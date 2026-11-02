# Normal equipment through 200: first implementation batch

Owner has assigned Codex to focus on this ticket and selected **existing equipment families, with late upgrades
from scrolls and accessories**, instead of inventing level-180/200 weapon tiers. Related exported card: e10;
its board state was not modified. Work branch `164-gear-progression-codex`, based on release-1.6.4 `c0e0a2cd7`,
with the prior audit/ticket commits cherry-picked. Current work includes a locally implemented loot migration/generator and forge fixes. Nothing is deployed;
real-client combat/source proof and release admission remain pending.

## Candidate normal path

| Player stage | Existing item path to evaluate | Source/acquisition requirement |
|---|---|---|
| 100–119 | Classic level-90/100 family gear and weapon | Preserve existing useful sources and verify each class |
| 120–139 | Eclectic → Muspell/Jaihin | Fix class-correct weapon access; current planned Jaihin rows are rare |
| 140–149 | Pensalir/Utgard, transitioning toward Commerci | Previous gear must support the first material/source fights |
| 150–159 | Commerci weapons and shared armour, selectively mixed with previous gear | Five RB0-accessible lower-Grandis source candidates below; no set bonus assumed |
| 160–179 | Sweetwater as the next existing-family candidate | Mutant-area sources in Future; compare actual cumulative set power and first-clear combat before committing the tier |
| 180–200 | Retain a useful weapon, improve its scrolling and compatible armour/accessories | Optional Alien earrings/belt/ring progression plus affordable class-appropriate 60% scrolls; no new base weapon tiers |

These rows are candidates, not promises of stronger gear in every slot. Commerci replacing a Pensalir piece can
lose a set bonus. Compare complete character loadouts, actual scrolling, attack speed and requirements rather
than forcing an upgrade by item level. The last row explicitly provides progression to level 200 while reusing
an existing weapon family. No new level-180/200 item or item-ID restriction change is proposed in this batch.

[late-item-options.csv](late-item-options.csv) lists all **42 Commerci/Sweetwater candidates**: each family has
16 supported weapon types and hat/overall/shoes/gloves/cape. All have server names/data, and none is a forge output
or present in the explicit RB restrictions. All are `equipTradeBlock`, a fact that must be preserved and handled
explicitly. Server presence is not proof of installed client art, equip animation, source reachability or supply.

## Concrete source proposal

[pilot-source-proposal.csv](pilot-source-proposal.csv) contains **136 proposed monster/item pairs** for **68
items**, with two different source mobs for each item. The source sheet preserves its original blank rate fields; the separate [policy](drop-policy.json) now supplies
1x base chances and [generator](../../../tools/economy/gen_normal_drops.py) emits migration059 and its rollback.
Quantities are one. SQL has not been applied to a project DB. The chosen budget is roughly1–4 hours at the
actual4x rate, balancing the full normal set around4–6 hours at1x, not baking4x into stored chances.

| Candidate reward stage | Proposed source mob IDs | Retained levels | Map evidence / area gate |
|---|---|---|---|
| Commerci | 2400013, 2400014, 2400309, 2400311, 2400312 | 135–142 | Onyx/Gravi Stonegar, Dumpy Hoodlum, Hazard's Rotten/Mean Crony; first maps listed in the lower-Grandis resource |
| Sweetwater | 8600000, 8600001, 8600002, 8600003, 8600004 | 162–168 | Mutant Snail/Mushroom/Slime/Pig/Tino; Future maps |
| Alien accessories | 8610010, 8610011, 8610012, 8610013, 8610014 | 177–184 | Advanced Knights A–E; Future maps |
| 60% scroll supply | 8610005, 8610006, 8610007, 8610008, 8610009 | 168–176 | Official Knights A–E; Future maps |

The builder verified all **20 candidate mobs** (19 appear in the final pair roster) is in its retained first map's life list, has a positive-EXP,
non-boss inventory entry and passes the existing RB0 area gate at level 120. It initially rejected Heretic Monk
sources because their first maps are outside the lower-Grandis list; those were removed. This illustrates why
region or raw mob level alone is not enough to establish an RB0 source. Portals, quests, spawn usability, client
stability, combat and the full route are still untested. The raw monster levels are not new recommended levels
or a new combat tuning policy.

Two proposed scroll/mob pairs already appear in 050/055; they are marked. Preserve installed older rows unless
an explicit update is reviewed. The other 134 pairs are not proof of absent installed/historical drops: the
active DB was not read. All source additions need a new tracked migration and rollback, not edits to shipped SQL.

The 68 items comprise 42 armour/weapons, three Alien accessories, and 23 scrolls. Weapon scroll coverage includes
all 16 supported types. Other scroll candidates are glove attack, four cape main stats, overall DEX and earring
INT. Every selected scroll exists in server data and has `success=60`. Appropriate ammo, shielding and other
consumables remain part of the whole progression ticket, not silently certified by this subset.

## Power and generator issues that need explicit treatment

- Sweetwater's server catalog tiers stack: two/three/four pieces yield cumulative **+195 all stats, +103 PAD and
  +103 MAD** at four pieces. Its five armour pieces separately total **+110 all stats, +22 PAD/MAD and +1,630 HP**,
  before a weapon and scrolling. Do not treat four-piece set power as a small generic level-160 increment or
  multiply the full-tier values again at five/six pieces. No set stats were edited.
- Alien earrings 1032191, belt 1132230 and ring 1113038 fit separate slots and equip at 180. Together their raw
  pieces give +45 all stats and +60 HP, plus a cumulative set bonus of **+20 all stats, +1,000 HP/MP and +50 PAD/MAD**.
  Two pieces already grant the HP bonus. Test the two-piece durability step and third-piece damage step, and make
  the source fights possible before receiving them. No need to replace a class-set helmet for this proposal.
- Existing `WzItems.droppable` rejects `equipTradeBlock` and `tradeBlock`. As a result **90 of the 136 proposed
  pairs** are excluded by the current generator's binding policy. That includes all proposed equipment pairs.
  Use a narrow reviewed item-ID allowlist in the new normal-gear generation path, retaining cash/quest/time/
  missing-item checks and the real binding flags. Do not remove the global binding exclusion or remove item
  restrictions merely to make a generator accept a row. Verify the actual drop/pickup/equip flow in the client.
- The current source/power plan must be checked against actual forged Royal/boss items. Those carry previous
  items' real stats and can differ from raw templates. RB0 gear should support the first run while preserving a
  meaningful forged upgrade later; raw item names or restriction lists do not prove that ordering.

## Repeatable coverage audit

```sh
python3 tools/economy/audit_normal_gear.py --wz /root/work/bake/wz \
  --output /root/work/agent-runs/server/164-gear-progression-codex/normal-audit
python3 -m unittest discover -s tools/economy -p 'test_*.py'
```

The audit reads all indexed server equipment, current code RB restrictions, all forge outputs and 050/055 plans.
It excludes cash, quest/time-limited items, unsupported weapon/job masks, unnamed items, explicit RB restrictions
and forge outputs (including the unlisted Lionheart sword exception). Bound/boss-reward flags remain review
flags rather than being hidden. Forge/RB exclusions are independent, so an accidental missing gate does not make
an intended forged item a normal candidate.

Current retained scan: **4,391 level-90–200 rows**, **2,972 policy candidates**, **296 at 150–200**, no XML/number/info
errors. “Candidate” means it passes the offline audit filters, not that it is an ordinary obtainable drop.
The coverage matrix separates lower-level templates still usable from new raw item-level options: the 180 band
has only four new supported weapon candidates (all mage level-163 elemental staffs); the 200 band has zero.
Counts include alternative IDs and review-only modern/boss items, not a hand-picked final roster. Coverage does
not certify an overall versus coat+pants combination, gender/stat requirements, DPS, set power or a whole loadout.

Artifacts in `/root/work/agent-runs/server/164-gear-progression-codex/`: complete inventory/candidate/coverage CSVs,
summary, fail-before/passing test output, graph update log, read-only pilot builder and checks. 17 Python tests
pass (nine new normal-gear policy tests and eight earlier area-audit tests). Before implementation, the new tests
failed because the audit module did not exist. That first audit was preparatory. The subsequent local implementation and full validation are tracked in the
[1.6.4 candidate notes](../../release/1.6.4-gear-progression.md). No game launch, TEST deployment, SSH or live
access has been performed.

## Local implementation and pacing

`gen_normal_drops.py` validates the complete allowlist, unchanged binding flags, forge/RB exclusions, monster
roles/levels, actual retained map placements and RB0 area gates. It leaves the global generator policy intact.
Migration059 preserves older pairs and its rollback is locally tested on disposable MySQL. The23 policy/area
tests plus four loadout-model tests currently pass. Forge checks and pirate inputs are fixed locally, with
seven script/data tests that failed before where appropriate and now pass. No transfer formula or item stat
was changed.

The updated1x chances are weapon2100, armour2400, optional accessories800 and60% scrolls2000 per million.
[Full-loadout model](loadout-acquisition-model.csv) covers32 five-armour-plus-weapon combinations at1x and4x,
2,000 seeded trials each. At300 eligible kills/hour, it estimates mean5.05–5.33 hours at1x and1.28–1.34 hours
at4x; the4x90% completion range is2.01–2.13 hours. At100 eligible kills/hour the latter times triple. These are
model outputs excluding travel, kill-rate differences, competition, pickup loss and installed row deviations;
they are not measured player times. The model changes source mob as items are collected and includes duplicate
drops and simultaneous rolls. It does not count two sources as two rolls on one kill.

## Remaining implementation and proof

1. Verify source routes/client support and actual loot before emitting SQL; preview the explicit bound-item
   allowlist and effective per-item acquisition times under actual rates. Choose stage budgets and chances from
   this concrete item/source roster; no values have been approved by this batch.
2. Measure Pensalir, Commerci, partial/full Sweetwater and two/three Alien accessories on reference jobs, against
   the proposed previous-tier source fights and the bosses that earn RB1. Record actual scrolled/crafted stats.
3. Implement the selected normal loot/scroll changes with new migration/rollback and cache proof, then fix the
   forge validation/data issues and calibrate the RB1 handoff against the measured late-RB0 profile.

CLOSER: Server Dev SlumsMS Main Session. Preparatory proof: `test_normal_gear.py`, `test_audit_new_areas.py` and
pilot data checks. Deployment and specific in-game proof IDs are not yet assigned or claimed complete. The
coordinator still owns the release-book ID, schedule and board state.
