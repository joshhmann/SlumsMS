# Handlers: combat and damage

Every packet that ends in a monster losing HP (or a player losing HP to a monster) passes through one shared funnel:
`AbstractDealDamageHandler` for outgoing attacks, `TakeDamageHandler` for incoming ones, and `MapleMap.damageMonster`
→ `Monster.damage` → `Monster.killBy` for the HP write and the death. This file documents that funnel, the anti-cheat
ceilings around it, and the twelve odd combat routes (summons, mob-vs-mob, field obstacles, bombs, heal-over-time,
combo counter) that bolt onto it. Bot-side combat AI is in [../bots/](../bots/README.md); buff/stat application is in
`server/life/StatEffect` territory (see `handlers-inventory-items.md` for the item side).

## Purpose

The server never trusts a damage number a client sends. Each attack packet is re-encoded into an `AttackInfo`, the
number of hit mobs and the per-hit ceiling are recomputed from the attacker's own stats, every damage line is decoded
(a negative line is a miss, a sign-bit line is a crit), and only then is the hit written to the monster's HP. The
monster's HP, the kill, the EXP split and the drops are all owned by `server.life.Monster` and `server/maps/MapleMap`,
not by the handler.

The incoming direction is the mirror image: `TakeDamageHandler` re-derives the player's damage reductions
(Magic Guard, Meso Guard, Combo Barrier, Achilles, Power Guard, Mana Reflection) server-side before the HP write, and
the HP write itself (`Character.hpChangeAction`) is what decides death.

## Entry points

| Packet | Opcode | Registered | Handler | `handlePacket` |
|---|---|---|---|---|
| `CLOSE_RANGE_ATTACK` | `0x2C` | `src/main/java/net/PacketProcessor.java:159` | `CloseRangeDamageHandler` | `src/main/java/net/server/channel/handlers/CloseRangeDamageHandler.java:46` |
| `RANGED_ATTACK` | `0x2D` | `src/main/java/net/PacketProcessor.java:160` | `RangedAttackHandler` | `src/main/java/net/server/channel/handlers/RangedAttackHandler.java:53` |
| `MAGIC_ATTACK` | `0x2E` | `src/main/java/net/PacketProcessor.java:161` | `MagicDamageHandler` | `src/main/java/net/server/channel/handlers/MagicDamageHandler.java:42` |
| `TOUCH_MONSTER_ATTACK` | `0x2F` | `src/main/java/net/PacketProcessor.java:234` | `TouchMonsterDamageHandler` | `src/main/java/net/server/channel/handlers/TouchMonsterDamageHandler.java:32` |
| `TAKE_DAMAGE` | `0x30` | `src/main/java/net/PacketProcessor.java:162` | `TakeDamageHandler` | `src/main/java/net/server/channel/handlers/TakeDamageHandler.java:63` |
| `MOB_BANISH_PLAYER` | `0x38` | `src/main/java/net/PacketProcessor.java:237` | `MobBanishPlayerHandler` | `src/main/java/net/server/channel/handlers/MobBanishPlayerHandler.java:32` |
| `HEAL_OVER_TIME` | `0x59` | `src/main/java/net/PacketProcessor.java:170` | `HealOvertimeHandler` | `src/main/java/net/server/channel/handlers/HealOvertimeHandler.java:36` |
| `SPECIAL_MOVE` | `0x5B` | `src/main/java/net/PacketProcessor.java:173` | `SpecialMoveHandler` | `src/main/java/net/server/channel/handlers/SpecialMoveHandler.java:69` |
| `ARAN_COMBO_COUNTER` | `0xA3` | `src/main/java/net/PacketProcessor.java:280` | `AranComboHandler` | `src/main/java/net/server/channel/handlers/AranComboHandler.java:35` |
| `SUMMON_ATTACK` | `0xB0` | `src/main/java/net/PacketProcessor.java:198` | `SummonDamageHandler` | `src/main/java/net/server/channel/handlers/SummonDamageHandler.java:72` |
| `DAMAGE_SUMMON` | `0xB1` | `src/main/java/net/PacketProcessor.java:196` | `DamageSummonHandler` | `src/main/java/net/server/channel/handlers/DamageSummonHandler.java:35` |
| `BEHOLDER` | `0xB2` | `src/main/java/net/PacketProcessor.java:254` | `BeholderHandler` | `src/main/java/net/server/channel/handlers/BeholderHandler.java:38` |
| `AUTO_AGGRO` | `0xBD` | `src/main/java/net/PacketProcessor.java:219` | `AutoAggroHandler` | `src/main/java/net/server/channel/handlers/AutoAggroHandler.java:34` |
| `FIELD_DAMAGE_MOB` | `0xBF` | `src/main/java/net/PacketProcessor.java:271` | `FieldDamageMobHandler` | `src/main/java/net/server/channel/handlers/FieldDamageMobHandler.java:38` |
| `MOB_DAMAGE_MOB_FRIENDLY` | `0xC0` | `src/main/java/net/PacketProcessor.java:272` | `MobDamageMobFriendlyHandler` | `src/main/java/net/server/channel/handlers/MobDamageMobFriendlyHandler.java:40` |
| `MONSTER_BOMB` | `0xC1` | `src/main/java/net/PacketProcessor.java:220` | `MonsterBombHandler` | `src/main/java/net/server/channel/handlers/MonsterBombHandler.java:41` |
| `MOB_DAMAGE_MOB` | `0xC2` | `src/main/java/net/PacketProcessor.java:238` | `MobDamageMobHandler` | `src/main/java/net/server/channel/handlers/MobDamageMobHandler.java:48` |
| `LEFT_KNOCKBACK` | `0xD4` | `src/main/java/net/PacketProcessor.java:277` | `LeftKnockbackHandler` | `src/main/java/net/server/channel/handlers/LeftKnockbackHandler.java:34` |

Opcode values: `src/main/java/net/opcodes/RecvOpcode.java:69-73` (`0x2C`-`0x30`), `:79` (`0x38`), `:110` (`0x59`),
`:112` (`0x5B`), `:171` (`0xA3`), `:179-181`, `:185-189`, `:197`.

`AUTO_DISTRIBUTE_AP` (`0x58`, `src/main/java/net/opcodes/RecvOpcode.java:109`) is registered to `AutoAssignHandler`
(`src/main/java/net/PacketProcessor.java:241`), which calls `AssignAPProcessor.APAutoAssignAction`
(`src/main/java/net/server/channel/handlers/AutoAssignHandler.java:36`) — AP allocation, *not* aggro. It appears in
combat-adjacent scope hints only because of the name.

### What each attack handler validates

| Handler | parse call | Gates before `applyAttack` | `applyAttack` argument |
|---|---|---|---|
| `CloseRangeDamageHandler` | `parseDamage(p, chr, false, false)` `:55` | morph-without-attack → `getClient().disconnect(false, false)` `:57-61`; dojo secret skill `1009/10001009/20001009` needs 10000 energy `:65`; dojo energy gain `:70-71`; finisher/orb bookkeeping `:82-131`; `SACRIFICE` self-damage `:145`; bamboo dojo reset `:165-170`; cooldown `:176-180`; Darksight/Wind Walk cancel `:184-189` | `attackCount` from `getAttackEffect(chr,null).getAttackCount()` `:192` |
| `RangedAttackHandler` | `parseDamage(p, chr, true, false)` `:62` | morph `:65-68`; dojo energy `:74-75`; `WeaponType.NOT_A_WEAPON` return `:106-107`; bullet/soul-arrow/shadow-claw accounting `:112-185`; projectile level gates `:148-161`; visible-projectile (cash `5021`) `:191-204`; cooldown `:226-230`; Darksight cancel `:235-237` | `bulletCount` `:243`; energy-orb/spark/taunt and Shark Wave short-circuit with `1` `:82`, `:86`; Aran combos `2/3/5` `:96`, `:99`, `:102` |
| `MagicDamageHandler` | `parseDamage(p, chr, false, true)` `:51` | morph `:54-56`; dojo energy `:63-64`; cooldown `:77-83` | `effect.getAttackCount()` `:86`; MP Eater passive after the hit `:87-91` |
| `TouchMonsterDamageHandler` | `parseDamage(p, chr, false, false)` `:35` | player must be at energy-bar 15000 **or** have `BODY_PRESSURE` `:34` | `1` `:37` |
| `SummonDamageHandler` | hand-rolled (no `parseDamage`) `:88-100` | player alive `:74`; summon OID must belong to the player `:78-86`; ownership-restricted map `:104` | per-hit `capSummonDamage` `:119-121` |

`AttackCapture` runs in every attack handler *after* parsing and before any gate
(`src/main/java/net/server/channel/handlers/CloseRangeDamageHandler.java:56`,
`RangedAttackHandler.java:63`, `MagicDamageHandler.java:52`, `TouchMonsterDamageHandler.java:36`,
`SummonDamageHandler.java:101`, `SpecialMoveHandler.java:77`); it is a no-op unless `-Dslums.attackcapture=true` and
the character is a non-bot GM of level ≥ 2 (`src/main/java/soloMapling/server/AttackCapture.java:48-49`, `:78`).

## How it works

### A. Player attack → monster HP → death → EXP/drops

1. **Decode the packet.** `parseDamage` (`src/main/java/net/server/channel/handlers/AbstractDealDamageHandler.java:534`)
   reads one filler byte, then `numAttackedAndDamage` split into `numAttacked = >>>4 & 0xF` and `numDamage = & 0xF`
   (`:538-540`), the skill id `:542`, the skill level from the *server's* skill table (with a level-1 fallback for PQ
   skills `:545-550`), `charge` only for the skills that carry it `:552-556`, `skip(8)`, then display/direction/stance
   `:559-561`. Meso Explosion has its own layout and returns early (`:563-598`). Ranged attacks read speed,
   `rangedirection` and a tail position `:601-604`.
2. **Recompute the ceiling.** `calcDmgMax` (`:617`) picks a formula per skill: magic
   `ceil((MATK*ceil(MATK/1000)+MATK)/30)+ceil(INT/200)` `:620`, Lucky Seven/Triple Throw
   `(LUK*5)*ceil(WATK/100)` `:622`, Dragon Roar `(STR*4+DEX)*ceil(WATK/100)` `:624`, otherwise
   `chr.calculateMaxBaseDamage(chr.getTotalWatk())` `:628`. Element Amplification `:636-658`, spell MAD
   `calcDmgMax *= effect.getMatk()` `:660`, Heal `:663-667`, Shadow Meso `:670-672`, and finally the skill's damage
   percentage `:675`. Post-fixes: energy bar 15000 `:714`, bonus-damage buffs `:720-728`, Aran tutorial `+80000` `:730`,
   `canCrit` job list `:735`, Sharp Eyes `canCrit = true; calcDmgMax *= 1.4` `:739-742`, Shadow Partner `:745`, fixed
   damage `:747-751`.
3. **Decode each damage line.** Inside the per-hit loop the raw int goes through
   `clientDamage(p.readInt(), chr, ret.skill)` (`:829`, defined `:886`): a negative line is logged as
   `"negative damage line"` and becomes `0` `:888-892`. Elemental multipliers (WK Charge `:753-800`, skill-element
   WEAK `×1.5`, Shadow Web, Shadow Meso's `debuffMob`, Body Pressure) are applied, then the line is compared with
   `maxWithCrit` (doubled when `canCrit` `:844-847`): above `1.5×` → `DAMAGE_HACK.alert` `:851-852`, above `5×` →
   `addPoint` `:856-857`. Snipe, or any crit-capable class above the non-crit estimate, is re-flagged as a crit with
   `markCritical` `:864` (`:896` sets the sign bit).
4. **Handler-specific side work.** The concrete handler broadcasts the attack
   (`chr.broadcastAttackMessage(pkt, true)`, e.g. `src/main/java/net/server/channel/handlers/CloseRangeDamageHandler.java:77`,
   `RangedAttackHandler.java:220`, `MagicDamageHandler.java:73`) *before* the hit lands, so the animation shows even if
   `applyAttack` then refuses.
5. **`applyAttack` gates** (`src/main/java/net/server/channel/handlers/AbstractDealDamageHandler.java:107`):
   ownership-restricted map `:109`; banned `:117`; skill effect null → `enableActions` + return `:122-126`;
   **MP gate `:128-132`** — if `player.getMp() < attackEffect.getMpCon()` the server adds an `MPCON` autoban point,
   sends `enableActions` and returns without damaging anything (this is b295); mob-count cap → `MOB_COUNT.autoban`
   `:160-163`; dead player `:166`. The old `attackCount != attack.numDamage` consistency check is commented out
   `:169-173`.
6. **Per-monster resolution** (`:188-282`): the monster is looked up by OID `:213`; `distanceToDetect` starts at
   `200000.0` `:216` and grows for ranged `:219`, magic `:223`, Aran `:227`, combo/body-pressure `:231`,
   Genesis/Blizzard/Meteor `:233`, Brandish/crushers `:235`, Dragon Roar `:237`, Boomerang Step `:239`. A monster
   beyond it is logged with `DISTANCE_HACK.alert` and its position is refreshed `:242-243`. Magic/weapon immunity
   collapses every line to `1` (`Collections.fill(onedList, 1)` `:251`, `:255`). Dojo boss secret skills are capped
   at `ceil(0.3 * maxHp)` `:260`. Crit lines are decoded back with `eachd += Integer.MAX_VALUE` `:273`, the total is
   clamped to `Integer.MAX_VALUE` `:277-278`, and aggro is recorded with
   `monster.aggroMonsterDamage(player, totDamageToOneMonster)` `:282`.
7. **Post-hit skill chain** (`:290-461`): pickpocket meso drops `:290-303`, drain/vampire heal `:306`, steal `:307`,
   Fire Demon `:331`, Homing Beacon/Bullseye `:338`, Flame Thrower `:340`, Snow Charge `:349`, Hamstring `:357`,
   Evan slow `:365`, Marksman Blind `:378`, Venom stacks `:394`, mortal blow (instant `Integer.MAX_VALUE` damage when
   the mob is under the HP threshold and the dice land) `:400-411`, fixed-damage verification `:417-420`, Three Snails
   `:422-425`, `HogTaming.totalDamage` remap for the mount hog `:450`, the skill's own statuses `applyStatus`
   `:452-458`.
8. **Damage application.** Heaven's Hammer `:459-461` and Aran Combo Tempest `:462-464` go through
   `damageMonsterWithSkill` `:514` (delayed by the animation time). Everything else falls into
   `map.damageMonster(player, monster, totDamageToOneMonster)` `:470`. Reflect buffs then bounce the hit back at the
   attacker `:472-489`, and the optional DPS meter accumulates `player.damageCheck` `:498-506`.
9. **`MapleMap.damageMonster`** (`src/main/java/server/maps/MapleMap.java:1633`): Zakum-style arm shield
   `if (bossDamageBlocked(monster)) return true;` `:1635-1637`; boss HP thresholds
   `beforeBossDamage(chr, monster)` `:1639`; only `if (monster.isAlive())` `:1642`; affinity hooks `:1643-1648`;
   `boolean killed = monster.damage(chr, damage, false)` **`:1645`**; self-destruct threshold `:1650-1655`; on a kill:
   `afterBossKilled` phase handoff `:1659-1662`, else `killMonster(monster, chr, true)` `:1666`.
10. **The HP write.** `Monster.damage` (`src/main/java/server/life/Monster.java:412`) takes the monster lock, returns
    `false` when already dead, and calls `applyDamage` `:461`, which calls
    `applyAndGetHpDamage(damage, stayAlive)` `:360` — `trueDamage = Math.min(curHp, delta)`, so overkill is discarded
    and HP never goes negative `:368-372`. `applyDamage` then dispatches the damage packet `:472`, adds to
    `takenDamage[chrId]` `:476-479`, updates hit streaks `:480-484`, refreshes the boss HP bar `:486` and notifies
    `DamageRank` `:487`.
11. **Death.** `MapleMap.killMonster` (`:1738`) → `removeKilledMonsterObject` (`:1710`, refuses when `hp < 0`, i.e.
    already removed `:1712-1714`) → over-30-levels-higher autoban alert `:1758-1759` → CPQ credit `:1770` → map-wide
    buff-to-give `:1773-1785` → `wakeBodyIfLastArm` `:1787` → `Character dropOwner = monster.killBy(chr)` `:1789`
    (EXP) → boss megaphone `:1790-1796` → achievements `:1799` → kill calibration `:1800` → site kill counter
    `:1801-1804` → world events `:1807` → `dropFromMonster(dropOwner, monster, false)` `:1808-1813` → boss-HP-bar
    aggro reset `:1815-1821` → `dispatchMonsterKilled(true)` and the kill broadcast in `finally` `:1825-1826`.
12. **EXP.** `Monster.killBy` (`src/main/java/server/life/Monster.java:916`) first calls
    `distributeExperience(killerId)` `:917`; that method (`:714`) walks `takenDamage` `:727-749`, computes
    `totalDamage = maxHpPlusHeal.get()` `:750` and `expPerDmg = mobExp / totalDamage` `:752`, splits per solo player
    `:781-787` (with `getExpRateMultiplier()` `:784`) and per party `:788-790`, then hands the expedition its kill and
    boss damage `:792-801`. `killBy` continues with aggro removal, the revive timer `:938-996` (a `revive` id missing
    from `Mob.wz` is skipped and logged, not an NPE — `warnMissingRevive` `:907-914`), the Horntail corpse sweep
    `:965-992`, and finally picks the looter as the highest damager `:1001`.

### B. Incoming damage → player HP → death

1. **Read the packet** (`src/main/java/net/server/channel/handlers/TakeDamageHandler.java:63`): trailing int `:67`,
   `damagefrom` byte `:68`, element byte `:69`, damage int `:70`. For normal mob attacks the mob id and OID follow
   `:73-75`.
2. **Resolve the attacker** `:77-89`: the OID must map to a `Monster` *and* its id must equal the id the client sent
   `:80-84`, otherwise `attacker = null`. `NEUTRALISE`-buffed attackers deal no damage at all `:90-92`. Alt-combat
   ownership is noted `:95`. `loseItem` drops roll per item `:97-142`; if the attacker is gone and this is not a
   self-destruct, the packet is dropped `:143-145`.
3. **Mob attack info** `:154-187`: `MobAttackInfoFactory.getMobAttackInfo(attacker, damagefrom)` `:155`; deadly attacks
   burn all MP `:157-160`; disease `12/124` forces `damage = 100000` `:161-163`; MP burn `:164`; the mob skill (if
   any) is applied `:166-170`; the mob pays its own MP cost `:172`; and **Mana Reflection** bounces
   `manaReflectDamage(damage, X, attacker.getMaxHp())` back through `map.damageMonster` `:173-186` — job ids
   212/222/232 only, never on bosses `:173`.
4. **Miss accounting and the fake-death packet** `:189-198`: `damage == -1` becomes the job-specific fake id
   `4020002 + (jobId/10 - 40) * 100000` `:190-192`; real damage resets the autoban miss counter, a miss adds one
   `:194-198`.
5. **Reductions, in order** `:201-277`: Dojo deadly-attack suppression under `USE_DEADLY_DOJO` `:201-204`; Power Guard
   bounce (half rate on bosses, capped at `maxHp/10`) `:209-216`; Body Pressure neutralise `:217-227`; Combo Barrier
   `:228-231`; Achilles `:233-243`; Aran High Defense `:245-249`; Magic Guard `:252-262`; Meso Guard `:263-273`
   (`damage = Math.round(damage / 2)` `:265`, meso loss `:266-271`, unbuffed when broke `:268`); otherwise the raw
   `chr.addMPHP(-damage, -mpattack)` `:277`. The Battleship HP call is commented out `:274-276`.
6. **Broadcast and after-effects** `:280-295`: `PacketCreator.damagePlayer` `:281` (GM-message variant when hidden
   `:286`), Magnus-orb ids `8880003-8880014` kill the attacker `:282-284`, Dojo energy `:288-291`, banished players
   moved out `:293-295`.
7. **The HP write and death.** `addMPHP` (`src/main/java/client/AbstractCharacterObject.java:539`) →
   `updateHpMp` `:440` → `changeHpMp` `:444` → the stat-pool change notifies the listener registered in
   `src/main/java/client/Character.java:373`, which calls `hpChangeAction(oldHp)` `:10585`. That method decides
   `playerDied = hp <= 0 && oldHp > hp` unless `isBuybackInvincible()` (then `hp = 1`) `:10586-10594`, and schedules
   the death on the map's stat-update queue so it does not run inside the stat lock `:10597-10611`. `playerDead`
   (`:8998`) runs the CPQ branch `:8999-9006`, `cancelAllBuffsOnDeath()` `:9009`, debuff dispel, the death timestamp,
   the bot-drama hook `:9010-9015`, `eim.playerKilled` `:9017-9019` and the safety-charm revival `:9020+`.

### C. The other damage routes

| Route | Flow |
|---|---|
| Summon hit | `SummonDamageHandler` `:88-100` reads per-mob damage, recomputes `calcMaxDamage` `:143` (magic vs weapon branch chosen by `summonEffect.getWatk() == 0` `:107`), clamps with `capSummonDamage` `:139`, applies the summon's statuses `:124-128`, then `map.damageMonster` `:129` |
| Mob vs mob (hypnotise) | `MobDamageMobHandler` `:48` recomputes `calcMaxDamage(attacker, damaged, magic)` `:77` and clamps above it with a `DAMAGE_HACK` alert `:63-71`, then `map.damageMonster(chr, damaged, dmg)` `:72` |
| Mob vs friendly NPC | `MobDamageMobFriendlyHandler` `:40` rolls the damage **server-side** `:52`, announces the friendly's death message per mob id `:54-81`, `killFriendlies` `:83`, then `applyAndGetHpDamage` `:87` |
| Field obstacle | `FieldDamageMobHandler` `:38` requires the map to have an active environment `:45-48` and `0 <= dmg <= GameConstants.MAX_FIELD_MOB_DAMAGE` (`src/main/java/constants/game/GameConstants.java:50`) `:52-56`, then `map.damageMonster` `:59` |
| Bomb mob fizzle | `MonsterBombHandler` `:41` only accepts `HIGH_DARKSTAR`, `LOW_DARKSTAR` and `9600069` `:32-37`, and removes the object without any damage roll `:48-52` |
| Own summon taking damage | `DamageSummonHandler` `:35` → `summon.addHP(-damage)` `:47`, `PUPPET` cancelled at 0 HP `:49` |
| Heal-over-time | `HealOvertimeHandler` `:36` validates `healHP <= 77 * map.getRecovery() * 1.5` `:54-58` and `0 < healMP < 1000` `:65-69` before `addHP`/`addMP` |
| Aran combo | `AranComboHandler` `:35` reads **no** packet bytes: the combo is server-owned, reset after a 6000 ms gap `:41-43`, buffed at every tenth `:45-56` |
| Damage-to-summon / rage / knockback | `SpecialMoveHandler` `:69` covers Monster Magnet (aggro, no damage) `:114-134`, MP Recovery (self HP→MP) `:136-141`, and every ordinary buff `:154-158` |

## Data

| Item | Where | Shape |
|---|---|---|
| `AttackInfo` | `src/main/java/net/server/channel/handlers/AbstractDealDamageHandler.java:58-65` | `numAttacked`, `numDamage`, `numAttackedAndDamage`, `skill`, `skilllevel`, `stance`, `direction`, `rangedirection`, `charge`, `display`, `allDamage`, `ranged`, `magic`, `speed = 4`, `position`, `rangedTailPos = -1` |
| `allDamage` | same, `:60` | `Map<Integer, List<Integer>>`: monster OID → decoded damage lines (meso OIDs map to `null` for Meso Explosion, `:583`) |
| Monster HP | `src/main/java/server/life/Monster.java` `hp` (AtomicLong) | clamped at 0 while alive; `-1` is the "removed from map" marker (`disposeMapObject` `:387-390`) |
| `takenDamage` | `src/main/java/server/life/Monster.java:95` | `Map<Integer, AtomicLong>` keyed by **character id**; fake damage counts as `1` (`:477-479`). This map is the EXP ledger |
| `maxHpPlusHeal` | `src/main/java/server/life/Monster.java:74`, `:140`, `:510` | denominator for damage share; healing a mob raises it so healers cannot be gamed |
| Hit streaks | `src/main/java/server/life/Monster.java:103-105` | `hitStreakAt`/`lastHitAt`, `HIT_STREAK_GAP_MS = 15_000` |
| Expedition damage ledger | `src/main/java/scripting/event/EventInstanceManager.java:204-214` | merges `takenDamage` per character, adds `mobHp` to `totalDmg` |
| DPS meter | `src/main/java/client/Character.java:13979-13981` | `damageCheckinProgress`, `damageChecks`, `damageCheck` |
| Dojo energy | `src/main/java/client/Character.java:5782` (get), `:10421` (set) | int, 0-15000; secret skills cost 10000 |
| Combat packets | `src/main/java/tools/PacketCreator.java` | `closeRangeAttack` `:2369`, `rangedAttack` `:2375`, `magicAttack` `:2382`, `summonAttack` `:2333`/`:2351`, `damageMonster` `:4113`, `damagePlayer` `:2644`, `damageSummon` `:4102`, `killMonster` `:1776`/`:1787`, `showHpHealed` `:115`, `MobDamageMobFriendly` `:6801`, `getEnergy` `:6772`, `enableActions` `:969` |
| WZ-derived caps | `src/main/java/constants/game/GameConstants.java:50` | `MAX_FIELD_MOB_DAMAGE = getMaxObstacleMobDamageFromWz() * 2` |

## Switches

| Switch | Type | Default | Read at | Effect here |
|---|---|---|---|---|
| `USE_AUTOBAN` | `config.yaml` (`src/main/java/config/ServerConfig.java:325`) | `false` (`config.example.yaml:230`) | `src/main/java/client/autoban/AutobanFactory.java:96`, `:109` | master switch for the `DAMAGE_HACK`/`MPCON`/`MOB_COUNT`/`DISTANCE_HACK` points and alerts raised throughout this subsystem. Off ⇒ those are log/point only |
| `USE_AUTOBAN_LOG` | `ServerConfig.java:326` | `true` (`config.example.yaml:231`) | `src/main/java/client/autoban/AutobanFactory.java:102` | writes the autoban log lines |
| `USE_DEBUG` | `ServerConfig.java:293` | `false` (`config.example.yaml:200`) | `src/main/java/server/life/Monster.java:467` | prints `Hitted MOB <id>, OID <oid>` per hit |
| `USE_DEADLY_DOJO` | `ServerConfig.java:554` | `true` (`config.example.yaml:473`) | `TakeDamageHandler.java:201` | when false, deadly attacks inside Dojo deal 0 damage and burn no MP |
| `DOJO_ENERGY_ATK` | `ServerConfig.java:555` | `100` (`config.example.yaml:474`) | `CloseRangeDamageHandler.java:70`, `RangedAttackHandler.java:74`, `MagicDamageHandler.java:63` | energy gained per attack that lands |
| `DOJO_ENERGY_DMG` | `ServerConfig.java:556` | `20` (`config.example.yaml:475`) | `TakeDamageHandler.java:289` | energy gained per hit taken |
| `USE_ULTRA_THREE_SNAILS` | `ServerConfig.java:454` | `false` (`config.example.yaml:371`) | `AbstractDealDamageHandler.java:424` | lets Three Snails damage scale with shells instead of a fixed line |
| `USE_FAST_REUSE_HERO_WILL` | `ServerConfig.java:458` | `false` (`config.example.yaml:375`) | `SpecialMoveHandler.java:103` | divides Hero's Will cooldown by 60 |
| `-Dslums.attackcapture` | system property | off | `src/main/java/soloMapling/server/AttackCapture.java:48`, `:59` | logs GM attack/cast/summon packets to `logs/attack-capture.tsv`; also the flag that registers the `!qa` command family (`docs/architecture/server/commands.md:149`). Live's unit passes neither this nor `-Dslums.qa` |
| `-Dslums.chaos.zakum` | system property | off | `src/main/java/constants/id/MobId.java:40` | chaos Zakum altar ids; feeds `shieldingArms` and therefore `bossDamageBlocked` |

`docs/release/1.6.4.conf` (live's `SERVER_SET`) contains **none** of the `config.yaml` keys above, so live runs each
key's own default: autoban **off**, autoban logging on, deadly Dojo on, Dojo energy 100/20, Three Snails and fast Hero's
Will off, `USE_DEBUG` off. TEST's `start-test.sh` lives on another host and is not reachable from this tree, so TEST's
values are unknown.

## Invariants

- **A monster's HP never goes below zero while it is alive.** `applyAndGetHpDamage` applies
  `Math.min(curHp, delta)` (`src/main/java/server/life/Monster.java:368-372`), so overkill is dropped and
  `takenDamage` records real damage only. `hp == -1` is reserved for "removed from the map"
  (`Monster.java:387-390`), which is why `removeKilledMonsterObject` treats `hp < 0` as already-removed
  (`src/main/java/server/maps/MapleMap.java:1712-1714`).
- **A monster dies once.** `Monster.damage` returns early when `!isAlive()` (`Monster.java:419-421`) and
  `killMonster` is idempotent through `removeKilledMonsterObject` (`MapleMap.java:1712`).
- **Client damage numbers are advisory.** Every line is re-clamped against a server-side estimate
  (`AbstractDealDamageHandler.java:844-857`, `:886-892` for negatives; `SummonDamageHandler.java:119`;
  `MobDamageMobHandler.java:63-71`), and a hit on an immune mob is `1` per line
  (`AbstractDealDamageHandler.java:251`, `:255`).
- **A negative damage line is a miss, never a crit.** `clientDamage` maps any negative input to `0`
  (`AbstractDealDamageHandler.java:886-892`); the crit encoding (`markCritical` `:896`) is applied only to positive
  lines, so decoding with `eachd += Integer.MAX_VALUE` (`:273`) can only ever recover a number the server itself
  flagged.
- **No MP, no damage.** `applyAttack` refuses before any HP write when `getMp() < getMpCon()`
  (`AbstractDealDamageHandler.java:128-132`), and a party Heal the caster cannot pay for heals nobody
  (`src/main/java/server/StatEffect.java:896-903`). This is b295's invariant and is covered by
  `src/test/java/net/server/channel/handlers/AttackWithoutMpTest.java` and
  `src/test/java/server/StatEffectHealAffordTest.java`.
- **EXP is a share of recorded damage, not of the killing blow.** `expPerDmg = mobExp / maxHpPlusHeal`
  (`Monster.java:750-752`), split over `takenDamage`; the "killer" only decides the event credit and the looter
  (`MapleMap.java:1789`, `Monster.java:1001`).
- **A player's HP can only be zeroed through the stat pool**, never by direct field writes from a handler;
  `safeAddHP` clamps to `1` (`src/main/java/client/AbstractCharacterObject.java:501-507`) while `addMPHP` /
  `addHP` can kill. Death is decided in one place, `hpChangeAction` (`src/main/java/client/Character.java:10585-10594`).
- **All monster death work runs under the monster lock** (`Monster.damage` `:414-451`,
  `removeKilledMonsterObject` `src/main/java/server/maps/MapleMap.java:1711`).

## Origin

| File | Origin | Evidence |
|---|---|---|
| `AbstractDealDamageHandler.java` | modified | differs from `/root/workspace/cosmicms/src/main/java/...`; oldest commits `03d13cb21` (repack baseline, 2026-10-18), `911398693` (AttackCapture, 2026-10-19), `d4bdf2ebb` (crit broadcast, 2026-10-21) |
| `CloseRangeDamageHandler.java`, `RangedAttackHandler.java`, `MagicDamageHandler.java`, `SummonDamageHandler.java`, `TouchMonsterDamageHandler.java`, `SpecialMoveHandler.java` | modified | `03d13cb21` + `911398693` only |
| `TakeDamageHandler.java` | modified | `03d13cb21`, `601e13125` (Mana Reflection, 2026-10-21), `7e791455a` (Meso Guard cost, 2026-10-25) |
| `MobDamageMobHandler.java`, `MobDamageMobFriendlyHandler.java`, `AranComboHandler.java`, `MonsterBombHandler.java`, `MobBanishPlayerHandler.java` | modified | `03d13cb21` only — the differences from Cosmic came in with the baseline repack |
| `DamageSummonHandler.java`, `HealOvertimeHandler.java`, `BeholderHandler.java`, `FieldDamageMobHandler.java`, `LeftKnockbackHandler.java`, `AutoAggroHandler.java`, `AutoAssignHandler.java` | base | byte-identical to the Cosmic tree (`diff -q` clean) |
| `src/main/java/server/life/Monster.java` | modified | `03d13cb21`, `88afaed00` (world events, 2026-10-20), `b1e1b6fb7` (bots ignore hidden GM, 2026-10-21) |
| `src/main/java/server/maps/MapleMap.java` | modified | `03d13cb21`, `765f8fc7b` (2024-08-28), `1512c08a6` (SoloMapling persistence merge, 2026-10-19) |
| `src/main/java/client/Character.java`, `src/main/java/server/StatEffect.java`, `src/main/java/net/PacketProcessor.java`, `src/main/java/net/opcodes/RecvOpcode.java` | modified | b295 fix `a5a30faef` (2026-11-03) touches `AbstractDealDamageHandler`, `StatEffect` and adds two tests; `a5a30faef` is an ancestor of this worktree's HEAD |

`tools/cosmicdiff/classifications.json` entries that name these files:

- `java-commented:…/AbstractDealDamageHandler.java::L666` = DELIBERATE — "Combo-orb term removed from the max-damage
  estimate. That estimate only feeds DAMAGE_HACK autoban alerts/points (USE_AUTOBAN is false in the bake test
  config); no player-visible effect."
- `java-method-missing:…/AbstractDealDamageHandler.java::*` = UPSTREAM-NEW — Cosmic split Meso Explosion into
  `parseMesoExplosion`/`removeExplodedMesos` on 2024-08-20, after the fork; ours keeps the older inline parse
  (`AbstractDealDamageHandler.java:177`, `:563`) "which is functional".
- `manual:…/AbstractDealDamageHandler.java::parseDamage` = RESTORED — "parseDamage had lost Cosmic's crit flag (sign
  bit on a damage line judged a crit…). Restored the flag only: Cosmic's fixed 195,000-200,000 Snipe damage stays
  dropped (MapleRoot uses the client's Snipe damage). A miss is never flagged."
- `java-commented-lines:…/MagicDamageHandler.java` and `…/RangedAttackHandler.java` = DELIBERATE — the attack
  broadcast goes through MapleRoot's `chr.broadcastAttackMessage` instead of a map broadcast
  (`MagicDamageHandler.java:73`, `RangedAttackHandler.java:220`).
- `java-commented:…/SummonDamageHandler.java::L113` = RESTORED — summon hits above `calcMaxDamage` are clamped again
  with the `DAMAGE_HACK` alert and the "Possible exploit" log line (`SummonDamageHandler.java:113-121`); risk noted:
  "a MapleRoot custom summon or stat bonus the formula does not know about would see its hits cut".
- `java-commented:…/TakeDamageHandler.java::L172` = RESTORED — Mana Reflection bounces `x%` again with the cap of a
  fifth of the mob's max HP, computed in longs (`TakeDamageHandler.java:303-308`); bosses immune `:173`.
- `java-commented:…/TakeDamageHandler.java::L258` = DELIBERATE — "Meso Guard costs no mesos… Owner decision
  2026-10-21: KEEP the repack's way." The cost is back in the tree (`:263-272`), so this classification is stale
  relative to `7e791455a`.
- `java-commented-lines:…/TakeDamageHandler.java` = DELIBERATE — the `decreaseBattleshipHp` call stays out
  (`TakeDamageHandler.java:274-276`; `docs/tickets/68-mapleroot-vs-cosmic-audit.md` records the owner decision).

## Tests and QA

Unit tests that pin this subsystem's behaviour:

| Test | Pins |
|---|---|
| `src/test/java/net/server/channel/handlers/AttackWithoutMpTest.java` | b295 fail-before: MP 0 vs `mpCon` 10 ⇒ `damageMonster` never called, one `MPCON` point, exactly one `enableActions` packet |
| `src/test/java/server/StatEffectHealAffordTest.java` | b295 party Heal: an unaffordable caster heals nobody and pays no MP; an affordable one heals member 2 and charges once |
| `src/test/java/net/server/channel/handlers/CriticalFlagTest.java` | `markCritical`/`lineDamage` round-trip, sign bit, largest hit, miss never flagged |
| `src/test/java/net/server/channel/handlers/NegativeDamageTest.java` | `clientDamage` on `-1`/`Integer.MIN_VALUE`/`0`/`1234`; a 3-line parse drops the negative, keeps 500, marks 1500 as a crit |
| `src/test/java/net/server/channel/handlers/SummonDamageCapTest.java` | `capSummonDamage` clamp, legit hit untouched, and `calcMaxDamage` = 1000 × 0.054 × 100 = 5400 |
| `src/test/java/net/server/channel/handlers/VenomStatusTest.java` | venom goes through the 6-arg `applyStatus(..., venom=true, ...)` after `setVenomMulti(1)` |
| `src/test/java/net/server/channel/handlers/ManaReflectionTest.java` | bounce maths and the `mobMaxHp/5` cap in longs |
| `src/test/java/net/server/channel/handlers/TakeDamageHandlerMesoGuardTest.java` | `mesoGuardLoss(1000,50)==500`, `(0,50)==0`, `(100,33)==33` |
| `src/test/java/server/maps/ZakumArmShieldTest.java` | `MobId.shieldingArms` for normal and chaos Zakum; `bossDamageBlocked`'s data |
| `src/test/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotCombatFormulaTest.java` | the bots' damage formula stays under `parseDamage`'s ceilings |

`!qa` / auto-QA coverage is behavioural, not per-handler: `src/main/java/soloMapling/autoqa/AutoQaCatalog.java` ids
`cb-1` "COMBAT (CHOICE|BUFF)" `:678`, `cb-2` "POSITION " `:683`, `cb-3` "HABITS POT/RESTOCK" `:691`, `cb-4`
"MANNERS (YIELD|CROWDED)" `:704`, `sp-1` "TALLY SPREAD map" `:711`, `t-bk-1` "Attack pacing: exact swing timing, held
Hurricane and Rapid Fire" `:732`, `t-at-1` "Bots attack at a player's pace" `:740`, `t-id-1` "Idle bots step out of
mob packs" `:762`. The scenarios live in `src/main/java/soloMapling/qa/CombatScenarios.java` (jobs CRUSADER, FP_MAGE,
RANGER, HERMIT; stages `Cb1Skills`…`Sp1Spread`, `Pt1Pets`; 15 s ticks over 15 min) and check bot pacing, not damage
arithmetic.

**Not covered by any test:** `Monster.damage`/`applyDamage`/`distributeExperience`/`killBy`,
`MapleMap.damageMonster` and its boss phases, `MobDamageMobHandler.calcMaxDamage`, `MobDamageMobFriendlyHandler`,
`FieldDamageMobHandler`, `MonsterBombHandler`, `HealOvertimeHandler`, `AranComboHandler`, `SpecialMoveHandler`,
`DamageSummonHandler`, the `applyAttack` post-hit skill chain, and the entire incoming-damage reduction ladder apart
from `mesoGuardLoss` and `manaReflectDamage`. There is also no test that a killed monster's EXP split sums to the
mob's EXP.

## Known issues

- **b295** (`/root/work/STATUS-1.6.4.md:45`, S3 Server, "MP gaps: an attack lands at 0 MP; party heal applies when the
  caster is refused", CLOSER: Server Dev / external worker). Fixed in the tree by `a5a30faef` (2026-11-03), which is an
  ancestor of HEAD, with both fail-before tests present; the status file (exported 2026-11-04 17:26 UTC) still lists it
  as triaged/unclaimed, so the card is open administratively, not technically.
- **Cosmic-diff classification drift** — `tools/cosmicdiff/classifications.json` marks `TakeDamageHandler.java::L258`
  (Meso Guard) DELIBERATE "costs no mesos", but `7e791455a` restored the cost (`TakeDamageHandler.java:263-272`). The
  classification no longer describes the code.
- **Battleship HP is deliberately gone** — `TakeDamageHandler.java:274-276` keeps `decreaseBattleshipHp` commented and
  `Character.decreaseBattleshipHp` no longer exists; recorded in `docs/tickets/68-mapleroot-vs-cosmic-audit.md` and
  `docs/release/cosmic-diff-1.4.1.md:99`.
- **Adjacent, not this subsystem** — ticket 68's open issue #1: the `inExpedition` flag shared by
  `UseItemHandler.java:55`, `:106-118` and `ChangeMapHandler.java:146-153` makes one player's potion heal the whole
  Zakum map and never resets on death. It makes incoming damage survivable in a way `TakeDamageHandler`'s maths does
  not account for.
- **NEW-FINDING: `MobDamageMobFriendlyHandler.java:83-92` kills the friendly twice.** `map.killFriendlies(monster)`
  runs `killMonster` → `removeKilledMonsterObject` → `disposeMapObject` (`MapleMap.java:1710-1727`,
  `Monster.java:387-390`), which sets `hp = -1` and already broadcasts the kill; `applyAndGetHpDamage(damage, false)`
  then runs at `:87` on an object whose HP is `-1`, returns `0`, and the following `getHp() <= 0` branch calls
  `map.removeMapObject(monster)` a second time `:88-91`.
- **NEW-FINDING: `MonsterBombHandler.java:41-52` never checks HP or ownership.** Any client can send `MONSTER_BOMB`
  with the OID of a Darkstar bomb mob and the mob is removed from the map (`dispatchMonsterKilled(false)` `:49`,
  `removeMapObject` `:51`) without damage, EXP or drops. There is no position, distance or "the bomb actually went
  off" check, unlike `FieldDamageMobHandler`'s cap.
- **NEW-FINDING: the damage-line-count check is dead.**
  `AbstractDealDamageHandler.java:169-173` keeps the `attackCount != attack.numDamage` guard commented out under
  `//WTF IS THIS F3,1`, so nothing verifies that a skill declaring N attack lines actually delivered N — a client can
  send fewer or more lines within the ceiling and the missing lines simply count as misses.
- **NEW-FINDING: `MagicDamageHandler.java:63` reads `+ +YamlConfig.config.server.DOJO_ENERGY_ATK`** — a doubled unary
  plus (harmless to the compiler, but it is the only place in the codebase where it appears and hides a careless
  edit).

## State

**working** for the two main paths, **partial** for the satellite routes.

Evidence: the attack funnel is wired end to end in code (`AbstractDealDamageHandler.java:534` → `:107` → `:470` →
`MapleMap.java:1633` → `Monster.java:412` → `:461` → `:360`), the incoming path likewise
(`TakeDamageHandler.java:63` → `:277` → `AbstractCharacterObject.java:539` → `Character.java:10585` → `:8998`), and
seven unit tests pin the four behaviours the repack had broken (MP gate, crit flag, negative lines, summon clamp,
venom, Mana Reflection, Meso Guard). The mob-death NPE seen in 1.6.4 round 11 is fixed in `Monster.java:899-945`
(`warnMissingRevive`). **Partial** because: nothing in `src/test/java` exercises `Monster.distributeExperience`,
`killBy`, `MapleMap.damageMonster`'s boss branches, or any of the satellite handlers; the satellite handlers
`FieldDamageMobHandler`, `LeftKnockbackHandler`, `BeholderHandler`, `HealOvertimeHandler` are untouched Cosmic code; and
the known issues above include two trust holes (`MonsterBombHandler`, the dead line-count check).

## Open questions

- Does TEST run with `USE_AUTOBAN` on? `start-test.sh` is on another host and unreachable; live's `1.6.4.conf` has no
  such key, so any `DAMAGE_HACK`/`MPCON` point raised in this subsystem is inert there.
- Was the `attackCount != numDamage` guard removed by MapleRoot or by Cosmic? The comment `//WTF IS THIS F3,1` is not
  enough to attribute it, and `tools/cosmicdiff` has no entry for it.
- Is `MobDamageMobFriendlyHandler`'s double-removal benign in practice, or does the second `removeMapObject` decrement
  `spawnedMonstersOnMap`/event counters twice? Determining this needs the PQ scripts (`HenesysPQ`, `AmoriaPQ`) that
  spawn those friendlies.
- Why does `STATUS-1.6.4.md:45` still show b295 as unclaimed when `a5a30faef` and both fail-before tests are in
  `origin/release-1.6.4`? Either the status export predates the merge or the fix was never merged into the branch the
  export reads.
- `Monster.damage`'s `stayAlive` flag: `MapleMap.damageMonster` always passes `false`
  (`src/main/java/server/maps/MapleMap.java:1645`), and only `applyAndGetHpDamage(Integer.MAX_VALUE, false)` /
  `heal` reach it otherwise. No caller passes `true`, so the `curHp--` branch at `Monster.java:369-371` looks
  unreachable; whether it is intended for a future "leave at 1 HP" mechanic is undetermined.
