# Level-100+ imported-area balance: findings and implementation plan

Owner request: cover everything after the classic content, from level 100 upward, and distinguish excessive
monster difficulty from insufficient obtainable equipment. Work branch `area-balance-codex`, based on
`release-1.6.3` at `21e166260`. This is an offline audit and a proposed calibration plan. No gameplay value,
drop table, gate, global rate or running server was changed.

## What the current code establishes

1. **The existing rebalance covers HP and EXP, not the full combat curve.**
   `server.life.MobStatOverrides` has only HP and EXP fields. `LifeFactory` still reads physical/magic attack,
   accuracy, avoidance and defences from WZ. The level-120 access rules also span areas containing much higher
   level mobs. An open entrance does not establish that every subsequent map is appropriate for level 120.
2. **Humans and bots do not establish the same damage result.** `TakeDamageHandler` reads a damage integer
   from the client's packet, then applies server-side mechanics, reflection, defensive buffs and HP/MP loss.
   `MobContactDamage` gives bots approximately half server physical attack, with its own spread/miss model.
   Editing only server PA/MA is insufficient evidence of changed real-player damage. A successful bot run is
   useful for logistics, but cannot certify the human survivability curve.
3. **Client/server combat values already differ in retained evidence.** For Frozen Rose 8211000, the local
   server XML has PA/MA **2310/2255**, while the client IMG retained from the b251 investigation has **900/900**.
   The ordinary Rose Garden mobs 8211001/2/3/5/6/7 also have 900/900 in that client snapshot. These are named
   offline snapshots, not a claim about every friend's installed client or the running server.
4. **Loot work already exists.** Migration 050 contains **8,011 planned rows for 350 mobs**. Migration 055 adds
   **242 rows for eight further IDs**. Within the retained ticket-81 inventory, every positive-EXP mob lacking
   a historical full table has planned rows. Adding another broad table now could duplicate or inflate rewards.
   Their presence in the repository/release configuration is not proof that the active DB and drop cache contain
   them. Verify the installed migration and actual rows first.

Representative **raw server XML attack stats**, not real hit damage:

| Monster | Level | PA | MA | Meaning for the audit |
|---|---:|---:|---:|---|
| Chief Oblivion Guardian 8200012 | 131 | 645 | 725 | Existing classic reference |
| Sleepy Grobbler 2400000 | 113 | 925 | 1250 | Already has HP/EXP and planned loot treatment |
| Frozen Rose 8211000 | 128 | 2310 | 2255 | Client snapshot instead has 900/900 |
| Happy Erda 8641000 | 200 | 1200 | 1250 | Already has HP/EXP and planned loot treatment |
| Alloy Xenoroid DX 8250015 | 219 | 14963 | 14850 | Imported 310070xxx maps, outside that original region treatment |

The largest raw values are not automatically ordinary grinding enemies. Gupi/Dolpi 8240040/1 have PA/MA 99999
but `allyMob=1`, EXP 1 and special encounter placement. Pulverizer 8240004 has `fixedDamage=1` and
`fixedBodyAttackDamageR=50`. Preserve these distinctions during review; a missing boss flag is not a sufficient
reason to put an encounter actor onto the ordinary-monster curve.

## Scope and repeatable evidence

The audit tool `tools/economy/audit_new_areas.py` reads the existing inventory, current override resource, planned
loot CSVs and retained server XML under `/root/work/bake/wz`. With `--stock-map-ids` it discovers all maps outside
the supplied classic baseline rather than assuming that ticket 81 covers every newer area.

The supplied baseline is `/root/work/agent-runs/client/npc-noscript/data/stock_v83_map_ids.txt`, documented in
that investigation as extracted from stock Cosmic Map.wz. It contains **5,262 numeric map IDs** plus the
non-map `AreaCode` name. This is an explicit comparison baseline, not an assertion that every absent map is a
reachable modern grinding map.

The broad pass scanned **11,356 map XML files**, including **4,414 non-baseline maps with a life section**, and
identified **725 available level-100+ mob IDs**: 673 without a boss flag and 52 with one. Six also occur on stock
maps: Shark, Qualm Monk, Qualm Monk Trainee, Pianus, Dreamy Ghost and Black Crow. A global per-ID change to these
would also affect classic content; flag them for separate treatment.

There are **464 map/mob references whose mob XML is missing or unreadable** in this retained dump. The broad
audit intentionally exits nonzero and records them instead of claiming complete coverage. A valid monster on
the same map is still inspected. It includes Mirror World and encounter/event maps for inventory purposes;
reachability, client compatibility and encounter role still need validation. Script-only boss phases are not
discovered by map placements alone; the existing 055 manifest is loaded as additional loot evidence.

Artifacts: `/root/work/agent-runs/server/new-area-balance-codex/`, including
`all-imported-100plus-audit.json`, `ticket81-audit.json`, `current-xp-ladder.md`, input hashes and test output.
An optional `--drop-export` accepts a headered, six-column `drop_data` TSV supplied by the authorized lane. Without
it, active loot coverage is explicitly **unverified**. Card-only rows are not counted as equipment/item coverage.

```sh
python3 tools/economy/audit_new_areas.py \
  --wz /root/work/bake/wz \
  --stock-map-ids /root/work/agent-runs/client/npc-noscript/data/stock_v83_map_ids.txt \
  --minimum-level 100 --exp-rate 3 --meso-rate 10 --drop-rate 4 --boss-drop-rate 3 \
  --output /root/work/agent-runs/server/new-area-balance-codex/all-imported-100plus-audit.json
python3 -m unittest discover -s tools/economy -p 'test_audit_new_areas.py'
```

Rates here are explicit modelling assumptions taken from the existing ticket-81 rules, not a live rate read.
The fixed 141M damage/hour, 1,500-kill/hour cap is the project's existing comparison model. It omits job, defence,
movement, spawn supply, buff uptime, party effects and potion use. Do not present its outputs as actual play rates.

## Full rebirth and forge catalogue

The [rebirth/forge audit](rebirth-and-forge-progression.md) includes [every forge recipe](forge-catalog/full-list.md),
with 357 outputs, 581 material inputs, current rebirth restrictions and cumulative equipment-chain fees. It also
records transfer power, apparent recipe errors and declared-versus-enforced craft requirements. Consult it
before treating a raw item stat or equip level as a player’s obtainable progression profile.

## Class equipment progression

The companion [equipment ladder](new-area-equipment-ladder.md) specifies weapons for every supported class,
armour IDs for all five families, shields/ammo/accessories, cumulative set bonuses and acquisition checks.
It distinguishes the proposed ordinary dropped-gear path from expensive crafted sets, and records recipe
levels separately from actual equip requirements. The evidence includes 392 named items and 1,249 planned
source rows; active sources and client compatibility remain unverified.

## How to decide monster scaling versus equipment

Use fixed, reproducible reference characters at levels **100, 110, 120, 130, 150, 170, 190 and 200**. Include a
warrior, ranged attacker, pirate and mage. Use obtainable level-appropriate equipment with ordinary scrolling;
also run an undergeared profile and a modestly upgraded profile. Record actual gear, HP, MP, defence, accuracy,
weapon attack/magic attack and defensive buffs. Include relevant rebirth builds rather than assuming a rebirth
count describes a character's current durability.

For each intended grinding tier, compare a known classic reference and the imported map with the same character:

- Measure median and 95th-percentile contact and skill hits, successful hits survived, misses, time to kill,
  kills/minute, potion use/minute, EXP/hour and **net** mesos/hour. Check normal attacks and dangerous skills separately.
- Test Magic Guard with adequate MP and depleted MP, Meso Guard, Power Guard, Achilles and party HP buffs as
  applicable. Test both solo and the expected friend party. Avoid counting a buff twice when translating damage.
- If ordinary obtainable gear performs well in the classic reference and collapses in the imported map, tune
  that map's monster profile. If both are poor, examine the character's equipment, build and supply access.
- If ordinary gear survives but the next equipment tier cannot realistically be obtained, repair the loot/shop/
  crafting path. If health loss differs from the displayed hit or bots differ from humans, fix the data/damage
  path before attributing it to gear.

**Proposed acceptance targets, to calibrate against the classic references:** a normal on-level enemy should
allow roughly **3–5 ordinary landed hits** for the natural-HP ranged reference and die in a small number of main
skill attacks. Elite enemies can be tougher and clearly signposted. Bosses and deliberate encounter mechanics
have their own budget. These are design targets, not a universal per-hit percentage cap or measured results.
Armour, HP investment, class defences and buffs should retain their value.

## Proposed implementation

1. **Establish active inputs.** Record the running server commit, overrides flag, migration 050/055 receipts,
   actual per-mob non-card/meso rows, cache reload state, global rates and friends' installed data version. Collect
   a few real hit samples with map/mob/attack IDs and reference character stats. No credentials or personal account
   data are needed. This task performed no SSH or live reads.
2. **Create one versioned balance manifest.** Each pure imported mob/encounter gets an intended level band and role,
   HP/EXP, contact PA/MA, relevant attack overrides, accuracy/avoidance/defence and reward tier. Generate server
   overrides and client WZ leaf overrides from the same source, with a parity report. Shared classic IDs and
   script-only phases need explicit handling. Verify the real-client result; a server-only attack override does
   not prove that player hits changed. A server hit-normalization alternative would need careful placement before
   reflection/defensive splits and client display/HP synchronization tests; do not introduce a blanket damage clamp.
3. **Start with ordinary, accessible 100–140 and 140–170 maps**, then the 170–200 and rebirth tiers. The 31007xxxx
   and other uncovered imported families deserve early inspection. Keep minigames, friendly actors, bosses,
   invulnerable phases and blocked/crashing maps out of the normal training batch. Preserve the current access
   decisions while publishing recommended levels; propose any gate change separately.
4. **Complete loot coverage using the existing generator**, after the active-row audit identifies real gaps.
   Add useful local sellable/crafting ETC, appropriate potions, common gear, scrolls and supported mastery books.
   Use curated stronger equipment and boss rewards for upgrades. Preserve the existing exclusion of unsupported
   currencies/systems, and verify every item exists and equips correctly in this client. Ordinary grinding must
   work before a player obtains a rare endgame piece.
5. **Balance rewards by acquisition time and net income**, not just rows per monster. Cover each job's weapon and
   armour path; check that one area's accessible gear prepares the next tier. Preserve current global rates and
   existing EXP promises until the measured comparisons justify a specific change. Test all rewards with the real
   drop/meso rates and account for personal/family/card modifiers separately. Migration changes must be idempotent,
   reversible and preserve older/quest/card rows.

The current model already illustrates why reward-per-hour matters: Sleepy Grobbler's planned mesos are about
**13.55M/hour** versus Happy Erda's **4.75M/hour** for the same fixed-DPS model. A Happy Erda's selected rare-gear
row has an expected wait of about **44.9 hours** in that model. Neither is a measured player result, but both
deserve review before making rare gear a requirement for comfortable progression.

The recomputed existing EXP ladder remains approximately LKC 1.30x, Rose Garden 1.62x, lower Grandis 2.01x,
Leafre of the Past 2.12x, Future/Twilight 3.00x, upper Grandis 5.87x and Arcane 5.96x the buffed Road to Oblivion
under the old model. Keep the existing XP and loot generator as the foundation while adding the missing combat,
gear-access and live-coverage dimensions. No blanket global reward increase is proposed here.

## Verification and remaining work

Eight Python unit tests pass: root info versus nested attack fields; HP/EXP versus unchanged attack fields;
planned versus exported/card-only loot; rate saturation and exclusive quantity bounds; duplicate drop pairs;
shared-classic mob detection; level filtering; malformed export rejection; and continuation after a missing mob.
The ticket-81 pass covers 390 retained mobs. The broader pass is explicitly partial because of the 464 missing/
unreadable references. The old ladder tool was rerun against the retained WZ.

No Java runtime code, WZ, migration or gameplay table changed. Maven and in-game QA were not run for this
Python-only audit. No monster difficulty or loot change has been tested in a real client, deployed, merged or
pushed. The next implementation batch needs the active data comparison and reference-character results above;
the current findings support an uneven monster/data coverage problem but do not rule out undergeared players.
