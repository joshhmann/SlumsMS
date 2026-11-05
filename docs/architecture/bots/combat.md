# Bot Combat (BotAttackSystem)

## Purpose

One attack pipeline for every AI player: pick a target, pick an attack, roll real damage, emit a packet that a
v83 viewer's client will actually draw. `BotAttackDriver` resolves a bot's attacks from class + weapon
(`BotAttackConfig`), then per tick picks AoE vs single-target, faces the nearest mob, rolls each line from the bot's
real numbers (`BotDamage`), and strikes through the profile's route — melee / ranged / magic
(src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:38-46).

Two failure modes drive the design:

1. **A pose/weapon the client does not expect drops the whole attack.** AGENTS.md:187 states it; the client's
   `CUserRemote::OnAttack` -> `CheckAttackAction` returns 0 and the attack is discarded
   (docs/real-bots/client-remote-attack-rules.md:1-63). `BotClientPoses` and `BotMeleeRange` encode the exe's own
   tables so bots only send what the client draws.
2. **A bot must look like a player.** Cadence comes from the client's pose lengths, not fixed cooldowns
   (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackTiming.java:26-35), and damage comes from
   the same formula a player's build would use (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotDamage.java:64-76).

Buffs, party heal/pots and death are adjacent combat systems living in the same packages and are documented here
because they gate the swing (`BotBuffDriver`, `BotPartySupport`, `BotDeaths`).

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| Shared 250 ms combat ticker | `TrainingBot.ensureCombatTicker` schedules `combatTickAll` at fixed rate 250 ms; registered with `BotShutdown.onQuiesce` | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:207-218, :227-241 |
| Per-bot combat tick | `combatTick`, gated on `phase == GRIND && !BotDeaths.isDead`; `BotPartySupport.tick` and `BotBuffDriver.selfBuff` called from the same tick | src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:262-268, :2187, :2618 |
| Swing clock | `BotSwingClock` arms one due tick at the exact ms the pose ends, instead of waiting for the next 250 ms sweep | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingClock.java:71-110 |
| Grind attack beat | `EngageBeat.engageAndSwing` (jump-attack roll, stop, `botAttack`, walk-lock from `swingEndsAt`) | src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/EngageBeat.java:242-277 |
| Other bot types | `FollowerFight` :242, `TestAttackBot` :27, `OwnedAltCompanion` :548, `StepExecutor` :453, `InvasionTownCrowd` :324, `RebirthBossRuns` :688 | src/main/java/soloMapling/ArtificialPlayer/... (see each) |
| GM `!bot attack` / `attackaoe` / `attackult` | `forceSingle` / `forceAoe` / `forceUltimate`, bypassing the cooldown gate | src/main/java/client/command/commands/gm4/ArtificialPlayerCommand.java:220-229; src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:146-158 |
| GM `!botshowcase` | `forceProfile` per showcased skill; command registered only when `AttackCapture.isEnabled()` | src/main/java/soloMapling/ArtificialPlayer/BotTypes/ShowcaseBot.java:117; src/main/java/client/command/CommandsExecutor.java:372-375 |
| GM `!botcombat stage|clear|spread|pets` | `CombatStaging.stage/unstage`; registered only when `QaSuite.isEnabled()` | src/main/java/client/command/commands/gm3/BotCombatCommand.java:63-101; src/main/java/client/command/CommandsExecutor.java:376-378 |
| Chat buff request | `BotBuffRequestHandler.tryHandle` from general chat and the bot-messaging dispatcher | src/main/java/soloMapling/ArtificialPlayer/BotBuffRequestSystem/BotBuffRequestHandler.java:120; src/main/java/net/server/channel/handlers/GeneralChatHandler.java:69 |
| Attack capture | `AttackCapture.attack` from the four real-player damage handlers (close/ranged/magic/touch) | src/main/java/soloMapling/server/AttackCapture.java:77-118 |
| QA scenarios | `!qa bk-1`, `bk-2` (showcase, ATTACK topic); `cb-1..cb-4`, `sp-1`, `at-1` stage via `CombatStaging` | src/main/java/soloMapling/qa/QaScenarios.java:374-382 |

## How it works

### One swing, in order (AUTO, human tuning on)

| # | Phase | Action | File:line |
|---|---|---|---|
| 1 | Gate | `attack()`: null bot/map -> miss "bot or map is null"; `!force && now < nextAttackByBot` -> miss "on cooldown" | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:278-286 |
| 2 | Weapon sanity | `BotWeapons.ensureFittingWeapon(bot)` hands a fitting weapon when a job changed or the bot holds an undrawable one | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:288-289; src/main/java/soloMapling/ArtificialPlayer/BotDecoratorSystem/BotWeapons.java:105-125 |
| 3 | Resolve kit | `BotAttackConfig.resolve(job, weapon, ownedSkills)` -> `JobAttacks(single, aoe, ultimate, extras)` | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:291; src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackConfig.java:296-308 |
| 4 | Slot check | Forced choice with no such slot -> "has no single-target attack" / "no AoE attack" / "no ultimate attack"; all null -> "no attack for job X / weapon Y" | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:300-311 |
| 5 | Target | `nearestMob`; null -> "no targetable mobs within 700px (none, or all on a separate ledge)" | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:313-316, :929 |
| 6 | Turn first | AUTO + human: `turnsFirst` returns a "turning to face 'X'" miss and sets `nextAttackByBot` now+120..300 ms, so the swing lands on the next tick after the turn packet | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:318-321, :789-804 |
| 7 | Face | `faceTarget` -> `GCMovement.face` when GC movement is on, else `MovementCommands.botFaceTowardsPoint`; 15 px deadzone | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:772-783 |
| 8 | Attack choice | Ultimate off its long cooldown -> `packAttack = ultimate`, else the sustained AoE; human path measures mobs in reach, boss flag, sloppiness, optional damage floors, then `BotSkillChoice.choose(...)` | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:340-367 |
| 9 | Extra rotation | `readyExtra` (WZ cooltime, `everyMs`, combo cost, MP affordable, mob in reach) overrides the choice for AUTO non-heal | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:383-390, :556-583 |
| 10 | Combo charge | `BotComboOrbs.mustCharge` -> charge with `comboCharger` (own single, or Slash Blast / Power Strike) | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:393-396, :499-517 |
| 11 | Supply | `SwingSupply.prepare` refuses unpayable MP / missing ammo and substitutes a plain swing with a `fallback` string | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:400-402; src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/SwingSupply.java:59-102 |
| 12 | Plan | `shadowDoubled` lines, then `BotSwingPlan.of(profile, level, weaponId, facingLeft, linesHint, speedInfusion)` | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:405-411, :514-517; src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingPlan.java:83-129 |
| 13 | Reach | `reachAs`: if the plan fell back to a plain swing, re-test reach with `basicSwing`; `cap(mobsInReach, plan.maxMobs())`; heal filters `undeadOnly`; empty -> `reachDiagnostic` + fallback | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:410-419, :637-708, :990 |
| 14 | Roll | `BotDamage.swing(...)` then `swing.roll(mob, lines)` per mob; crit lines encoded negative; `reported` sums decoded lines | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:432-442; src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotDamage.java:397-444 |
| 15 | Strike | `BotAttackEffects.strike(plan, hits, projectile, hitDelayMs, nextHits, onRelease)` sends the packet(s) and applies damage | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:444-452; src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackEffects.java:127-146 |
| 16 | Bookkeeping | `BotAranCombo.afterStrike`, QA `ATTACK` line, `supply.commit`, `BotComboOrbs.afterStrike`, `nextAttackByBot = now + plan.busyMs(profile.cooldownMs)`, `startCooltime` (WZ cooltime), extra interval, ultimate cooldown | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:453-491, :595 |
| 17 | Report | `AttackResult.hit(label, reported, killed, skillId)`; `botAttack` wraps it with `BotAttackStats.record` and `BotPerformanceMonitor.recordCombatDamage` | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:486-491, :134-143 |

### Skill and pose selection

| Attack kind | How the skill is chosen | How the pose/route is chosen | File:line |
|---|---|---|---|
| AUTO pack vs single | `BotSkillChoice.choose`: boss -> single; `inReach >= max(1, aoeMinMobs)` (default 3) -> AoE; sloppy roll -> the other; class without an AoE -> single; class without a single -> AoE | — | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSkillChoice.java:43-66; src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:355-367 |
| Floor variant (1.6.4 boss lane) | 9-arg `choose`: if the unchosen attack's guaranteed floor is higher, switch — always on a boss, on ordinary mobs only when the chosen floor <= `FLOOR_HIT` (1.0) | — | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSkillChoice.java:77-96, :17-29 |
| Per-job kit | `BotAttackConfig` static table (single/aoe/ultimate + extras) by lineage, or `resolveOwned` picking the highest owned skill per role | — | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackConfig.java:117-197, :285-343 |
| Melee swing pose | — | `BotAttackData.actionFor` -> `BotSkillActions.poseCodes` (skill's own pose); else `BotClientPoses.plainPoses(route, attackType, skillType3)`; special cases Brandish 63/64, Paladin Blast 71 | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackData.java:65-97; src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSkillActions.java:112-200 |
| Ranged / magic pose | — | `T2` / `T3` rows of `BotClientPoses`, indexed `[attackType-1][skillType3?1:0]`; a RANGED skill on a non-shooting weapon falls back to the plain pose unless the exe's `SHOOT_WITHOUT_WEAPON` allows it | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotClientPoses.java:39-60, :146-165; src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingPlan.java:93-99 |
| Non-drawable skill | — | `BotSwingPlan.of` rewrites to skillId 0 + level 0 + the weapon's plain pose when `BotSkillActions.weaponFits` is false or the pose lookup fails | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingPlan.java:91-99 |
| Melee range guard | — | `BotMeleeRange.hasRange(skill, level, weaponId, pose)`; a miss is rewritten to a plain pose (or dropped) and logged `GUARD melee without a range` | src/main/java/soloMapling/server/BotAttackPackets.java:35-57; src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotMeleeRange.java:55-78 |
| Thrown stars | — | `ThrowingStarSelector`: picked once from the bot's level band and cached on `BotSM.chosenStarId`; claw only | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/ThrowingStarSelector.java:37-84 |
| Aran combo | — | `BotAranCombo`: orbs from `Aran.COMBO_ABILITY`, 6 s decay, capped 30 000; `comboCharger` substitutes a charge swing when a finisher needs orbs | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAranCombo.java:19-59; src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:393-396 |
| Attack cadence | — | `BotAttackTiming.swingMs` = pose length from `bot-action-delays.tsv`, speed-scaled and rounded up to the 30 ms update step; keydown skills stream 25 shots at 120 ms | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackTiming.java:67-102; src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingPlan.java:56-71, :103-105 |
| Reach box | — | `reachBox`: WZ `StatEffect.getAttackBox` when available; RANGED/MAGIC always the profile box; surround test on symmetry; otherwise `clipForward` + `coverBody` (feet + 25 px back margin) | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:684-728 |
| Who may be hit | — | `mayTarget` = `botMayTarget` (not `TRANSPARENT_ITEM`, not friendly) && per-bot filter or `KsManners.mayHit` | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:826-845, :817-821 |

### Keydown / multi-shot path

`BotAttackEffects.strike` sends the prepare effect when `plan.prepare()`, then the first attack packet, then for
`shots > 1` reschedules `holdKeydown` every `KEYDOWN_SHOT_MS` (120 ms), re-rolling `nextHits` each shot. `holdKeydown`
releases early on map change, on `!bot.isAlive()` (1.6.2 bot audit B22: a hold outliving its bot dealt damage, EXP and
loot from a corpse) and when no mob is in reach (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackEffects.java:127-180).

### Buffs, party support, death

- `BotBuffDriver.maintain(bot, lateMaxMs, pay)` schedules each due buff `cast × 750 ms` apart, adds late jitter and an
  8 % chance to forget a buff for 15-45 s, pays only party buffs for population bots (`Pay.PARTY_BUFFS`) and all buffs
  for owned alts (`Pay.ALL`) (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotBuffDriver.java:54-113).
- `BotPartySupport.tick` fires only under `BotSupportSwitches.partySupport()` and when the bot is partied with a
  player; heals a member below 60 % (4 s gap) and drinks pots (3 s gap); a heal calls
  `BotAttackDriver.holdAttacks(now + 900 ms)` so the bot does not swing through the cast
  (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotPartySupport.java:47-58, :172-209, :383).
- `BotDeaths` rolls mortality per bot and per world; `IMMORTAL_SWITCH = "slums.bots.immortal"` restores the pre-1.6.4
  unconditional protection (src/main/java/soloMapling/ArtificialPlayer/BotCombatSystem/BotDeaths.java:38-44, :67-78).

## Data

| Store | Shape | Lifetime | File:line |
|---|---|---|---|
| `nextAttackByBot` | botId -> epoch-ms gate | until `clearBot` | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:50, :202-218 |
| `nextUltimateByBot` | botId -> epoch-ms, `FULL_MAP_ULTIMATE_COOLDOWN_MS = 25_000` | until `clearBot` | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:58-59 |
| `skillReadyByBot` | botId -> skillId -> ready epoch-ms (WZ `getCooldown`) | until `clearBot` | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:64, :595 |
| `extraUsedByBot` | botId -> skillId -> last-used epoch-ms | until `clearBot` | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:67, :477-484 |
| `lastSwingAtByBot`, `qaLastSwingByBot` | QA cadence + last swing record, ATTACK topic only | until `clearBot` | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:71-90 |
| `targetFilters` | botId -> `Predicate<Monster>` (owned alts, ticket 13) | until `setTargetFilter(id, null)` | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:92, :817-821 |
| `BotAttackStats` | 60 s per-bot tally: cooldown/swings/kills/bySkill/misses | per window | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackStats.java:24-95 |
| `OffscreenCombat` rates | per bot+map kill/EXP pace, `MAX_KILLS_PER_MIN = 30`, EXP at 1/3 pace (`solomapling.offscreenExpPace`) | cached | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/OffscreenCombat.java:39-50, :81-123 |
| `KillCalibration` | kill-rate calibration bands, `DECAY = 0.995`, `BAND = 10` | keyed | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/KillCalibration.java:40-49 |
| `BotAttackProfile` | immutable recipe: route, numAttacked, numDamage, reachX/Y, cooldownMs, hitDelayMs, speed, skillId/level, heldSkill | static table | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackProfile.java:12-31, :66-114 |
| `BotClientPoses` exe tables | T1/T2/T3 pose rows, KEYDOWN / KEEPS_KEYDOWN_POSE / NO_ACTION_CHECK / SHOOT_NO_BULLET / SHOOT_WITHOUT_WEAPON sets | static, read from `Character.wz` Weapon info | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotClientPoses.java:39-60, :130-209 |
| `bot-action-delays.tsv` | 162 client action codes -> total ms at speed 6 | resource | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackTiming.java:42-49, :120-148 |
| `bot-melee-ranges.tsv` | skill/level and afterimage -> allowed pose names | resource | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotMeleeRange.java:33, :137 |
| Attack capture TSV | `logs/attack-capture.tsv`, one row per real-player attack, 28 columns | append-only | src/main/java/soloMapling/server/AttackCapture.java:51-60, :315-330 |

## Switches

| Switch | Default | Meaning | Live (docs/release/1.6.4.conf:271) |
|---|---|---|---|
| `-Dslums.botattacktiming` | `true` (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackTiming.java:56-58) | pose-length cadence instead of fixed profile cooldowns | absent -> ON |
| `-Dslums.botswingclock` | `true` (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingClock.java:56-58) | arm a due tick exactly at swing end | absent -> ON |
| `-Dslums.bossfight.damagefloor` | `false` (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSkillChoice.java:17-29) | weigh guaranteed damage floors when choosing AoE vs single | absent -> OFF |
| `-Dslums.bots.immortal` | `false` (src/main/java/soloMapling/ArtificialPlayer/BotCombatSystem/BotDeaths.java:67-71) | TEST switch: bots never die | absent; conf:92 documents it as TEST-only |
| `-Dslums.bots.partysupport` | config `BOT_PARTY_SUPPORT` (src/main/java/soloMapling/ArtificialPlayer/BotSupportSwitches.java:28) | party heal / pot ticking | present -> ON |
| `-Dslums.bots.magicguard` | config (src/main/java/soloMapling/ArtificialPlayer/BotSupportSwitches.java:36) | Magic Guard handling | present -> ON |
| `-Dslums.killcalib` | `false` (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/KillCalibration.java:40, :107) | kill-rate calibration | present -> ON |
| `-Dslums.attackcapture` | `false` (src/main/java/soloMapling/server/AttackCapture.java:48, :59) | write real-player attacks to TSV; also gates `!botshowcase`, `!classtest`, `!botground` | absent -> OFF (TEST only by design, src/main/java/soloMapling/server/AttackCapture.java:34) |
| `BOT_COMBAT_HUMAN` | `true` (src/main/java/config/ServerConfig.java:266-276) | human choice rules vs the old "2+ mobs" rule | config default |
| `BOT_COMBAT_AOE_MIN_MOBS` | `3` | mobs in reach to pick AoE | config default |
| `BOT_COMBAT_SLOPPINESS` | `1.0` | scales `BotHabits.sloppyNow()` | config default |
| `BOT_COMBAT_DEATHS` | `true` | mortality (gated by `human()`) | config default |
| `BOT_KS_MANNERS` | `true` | `KsManners.mayHit` filter | config default |
| `BOT_SPREAD` | `true` | bots keep apart | config default |
| `-Dsolomapling.offscreenExpPace` | `1/3` (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/OffscreenCombat.java:49-50) | offscreen EXP pace (owner decision 2026-10-19) | absent -> 1/3 |
| `-Dslums.botcombat` overrides | `human|aoe|sloppy|deaths|manners|spread` (src/main/java/soloMapling/ArtificialPlayer/BotCombatSystem/BotCombatTuning.java:91-104) | live tuning via `!botcombat set` | runtime |

## Invariants

1. **Never send a melee packet the client finds no range for.** `BotAttackPackets.closeRangeAttack` re-checks
   `BotMeleeRange.hasRange` and rewrites to a plain pose (or drops the packet) — a viewer's client closes otherwise
   (src/main/java/soloMapling/server/BotAttackPackets.java:35-57).
2. **A skill the equipped weapon cannot draw is never sent as that skill.** `BotSwingPlan.of` demotes to skillId 0
   (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingPlan.java:91-99); `BotWeapons.UNDRAWABLE` keeps 13 weapon ids off bots (src/main/java/soloMapling/ArtificialPlayer/BotDecoratorSystem/BotWeapons.java:105-112).
3. **Lines never exceed 15 and mobs never exceed the skill's `mobCount`.** `MAX_LINES = 15`
   (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:521), `BotSwingPlan` caps `maxMobs`/`lines` from `BotClientPoses.counts`
   (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingPlan.java:110-121).
4. **Crit is encoded negative, never as a flag.** `encodeCritLine`/`decodeDamageLine` are exact inverses; the
   server's own parse decodes yellow crits (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackData.java:117-133).
5. **Big Bang must carry charge 1080.** `magicChargeFor` returns -1 for a Big Bang packet and the viewer over-reads
   and crashes (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackData.java:101-115).
6. **A bot's `getClient().getPlayer()` is null** — every system here works from the `Character`; bot ids are
   >= 1,000,000,000 and packets to bots go nowhere.
7. **A hold dies with its bot.** `holdKeydown` releases on `!bot.isAlive()` (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackEffects.java:148-175).
8. **Re-attack is throttled per bot and cleared on despawn.** `nextAttackByBot` + `clearBot` releasing 7 maps and
   5 sub-system caches (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:202-218).
9. **Party heal holds attacks.** `holdAttacks` merges with `Math::max` so a longer hold is never shortened
   (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:183-185); `releasedEarly` merges with `Math::min` (:191-193).

## Origin

`custom` throughout — no upstream lineage predates the MapleRoot baseline.

- BotAttackDriver: oldest commit `03d13cb21 2026-10-18 "Establish deployable MapleRoot server baseline"`, latest
  `1512c08a6 2026-10-19 "Merge the SoloMapling persistence fork onto the live baseline"`. Header credits the
  targeting/route-dispatch model to GreenCatMS bot combat (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:38-39); `BotAttackData` body-action ids
  are lifted from GreenCatMS `BotAttackDataProvider.BODY_ACTION_ID_OVERRIDES` (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackData.java:15-16);
  `BotAttackProfile` credits the attack-plan concept to GreenCatMS (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackProfile.java:4-6).
- BotClientPoses: `d1548912c 2026-10-21 "Check every bot attack against the client's own rules, and send it as a
  player would"`, then `6ca8830ab 2026-10-21 "Time bot attacks from the client's pose lengths, not fixed cooldowns"`.
- BotAttackPackets / BotMeleeRange: `999fe5cac 2026-10-26 "Stop gun bots' idle show-off swing from closing viewers'
  clients"` on top of the baseline.
- BotCombatTuning / BotSkillChoice / BotDeaths / CombatStaging: `97942a236 2026-10-21 "Make grinding bots fight like
  people and spread out (1.5.0 rc3 bots lane)"`.
- BotDamage: `91d16e8d6 2026-10-19 "Hit as hard as a player of the same build would"`.
- BotSwingClock: `7f6aa759a 2026-10-22`. AttackCapture: `911398693 2026-10-19 "Capture a real client's attacks on the
  test server for bot alignment"`.
- `ThrowingStarSelector` says so explicitly: "Ours (SoloMapling), not a GreenCatMS port"
  (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/ThrowingStarSelector.java:16-18).

## Tests and QA

Verified by unit tests under `src/test/java/soloMapling/ArtificialPlayer/BotAttackSystem/` (36 files) and
`BotCombatSystem/`:

| Test class | What it pins |
|---|---|
| `BotAttackClientValidityTest` | every bot attack and every showcase attack builds real bytes and passes `ClientAttackRules` (:48, :81); every weapon handed to a bot is drawn (:146); `BotClientPoses` tables equal the exe fixture (:167) |
| `BotAttacksDrawnTest` | every bot attack fits the weapon it is handed (:29); a melee box reaches a mob on the bot's own ground (:59) |
| `BotMeleeRangeTest` | runtime table == client fixture (:54); a gunslinger's Double Shot sent as melee would close the viewer (:73); the packet guard never sends it (:82); the guard agrees with the client for every weapon/pose/skill (:118) |
| `LiveMeleeCloseCasesTest` | the two 1.5.5 live close cases (Soul Blade as melee, Aran spear/polearm) stay fixed (:64-150) |
| `ClientAttackRulesTest` (9) | a skill sent in a pose it does not allow is dropped (:47-112) |
| `BotSkillChoiceTest` (7) | 3+ mobs -> AoE, pair -> single, boss -> single, no-AoE / no-single classes, sloppy, threshold configurable (:17-61) |
| `BotSkillChoiceFloorTest` (6) | floor switch flips boss choice, floor equals the range minimum, switch off keeps today's choice (:17-59) |
| `BotAttackTimingTest` (11) | delay table names the exe's actions; a beginner sword swing takes a player's time; keydown streams at the client cadence; speed follows MapleRoot's hook not the item; flag off keeps old cooldowns; every bot attack has a player's cadence (:47-209) |
| `BotDamageTest` (13) | weapon sets the attack, claw crits, Shadow Partner is half-again not double, lines stay in range and crits encode negative (:143-316) |
| `BotCombatFormulaTest` (15) | physical ceiling is the server's for every build, spells under the magic ceiling, Lucky Seven / Triple Throw use their own formula, Heal vs undead, ranged weapon swung in melee hits softly (:86-278) |
| `BotSwingClockTest` (5) | due tick at swing end; nothing armed for past/far-future/flag off; replaced/disarmed does nothing; sweep skips mid-pose (:49-105) |
| `BotSwingPacingPerfTest` | 40 grinders swing at a player's pace for no more combat ticks (:146); the clock's cost on a real pool (:168) |
| `BotPartySupportTest`, `BotPartySupportReachTest`, `PartySupportPotsTest`, `BotPartyHealCastTest`, `BotBuffHandOutTest` | heal for MP then waits out the gap; no MP -> no heal and no retry until the gap passes; reach and pot rules |
| `BotCombatTuningTest` | defaults are the owner's decisions (:18); overrides apply and clear (:26) |
| `BotDeathsTest` (6), `BotHabitsTest` (5), `CombatStagingDieTest`, `CombatStagingPositionTest` | mortality rolls, habit derivation, staging positions |
| `BotAttackTargetFilterTest`, `PickTargetTest` (6), `KsMannersAtHitTest` (3), `ExtraPayableTest` (2), `BotComboOrbsTest` (7), `BotAranComboTest` (3), `BotBossMechanicsTest` (3), `BossRotationDpsTest` (4), `KeydownBurstDeathTest` (1), `KillCalibrationTest` (9), `MapleRootAttackIdsTest`, `BotSkillActionsTest`, `BotAttackConfigOwnedSkillsTest`, `BotAttackServerDataTest` (needs WZ), `BotAttackExportTest` | filters, target ranking, KS manners, extra payability, orbs, boss mechanics, export shape |
| `src/test/java/soloMapling/qa/CombatScenariosTest` (13) | every combat QA scenario is registered and well-formed |
| `src/test/java/soloMapling/server/AttackCaptureTest` | capture column set and enable/disable rules |

Auto-QA checks (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java`): `t-cb-1` skill choice and buffs (:677-681),
`t-cb-2` facing and spacing (:682-687), `t-cb-3` pots/restock/deaths (:688-697), `t-cb-4` neutral bots give space
(:698-707), `t-sp-1` bots spread out (:710-712), `t-bk-1` attack pacing (:732-739), `t-at-1` (:740-746), `t-bk-3`
pirate bots never send a client-closing melee attack (:753-758), `t-su-1` party support (:1466). Rule helpers
`choicesSensible` (:1976-2008), `positionsMostlyGood` (:2021-2075), `pacingSteady` (:2107-2133), `meleeInRange`
(:2117-2133).

**Hand-verified only** (docs/ops/qa-suite.md:927 lists them as eye-checked): `cb-2` facing look, `bk-1` showcase
appearance, `at-1` attack visuals on a real client — i.e. *what a human sees* is never asserted by a test; the tests
assert what the packet would make the client do, from exe-derived tables.

**Not covered by any test:** the 250 ms `TrainingBot` ticker wiring itself; `BotBuffRequestHandler` chat matching
(only `BotBuffHandOutTest` covers grant shape); `OffscreenCombat` EXP pacing; `AttackCapture` end-to-end against a
live client (`BotAttackExportTest` only writes the export); the `!bot attack` GM command path.

## Known issues

- **b295** (S3, Server) — MP gaps: an attack lands at 0 MP; party heal applies when the caster is refused
  (/root/work/STATUS-1.6.4.md:45). Both paths sit in `SwingSupply.prepare` and `BotPartySupport.GameEffects.pay`.
- **b276** (S4, QA tooling) — a bot's map-scoped PLAN/ROUTE lines never reach the GM's QA feed; combat `ATTACK` lines
  are likewise map-scoped, so the same feed rule applies (/root/work/STATUS-1.6.4.md:78).
- **b287** (S4) — a PANIC-style scroll trader never lists a Chaos/White Scroll; bot economy, not the attack path
  (/root/work/STATUS-1.6.4.md:83). Listed for adjacency only.
- **e06** (later) — useful-DPS spot scoring is not built: bots still choose a spot without weighing their damage
  (/root/work/STATUS-1.6.4.md:116).
- **NEW-FINDING:** src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:456-458 — the QA
  swing record is keyed into `qaLastSwingByBot`, but `clearBot` (:202-218) never removes it, so a recycled bot id
  inherits the previous bot's `swings`/`damageTotal` counters.
- **NEW-FINDING:** src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:472 — the gate is
  set from `plan.busyMs(profile.cooldownMs)`, which for `plan.shots() > 1` is `holdMs()` = shots × 120 ms
  (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotSwingPlan.java:65-67) and so ignores `profile.hitDelayMs`; the hit application scheduled by
  `BotAttackEffects.strike` (:444-452) can still be pending when the next swing is allowed.
- **NEW-FINDING:** src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotMeleeRange.java:55-58 — `hasRange`
  returns true for any weapon whose WZ `afterImage` is empty, i.e. the guard silently trusts WZ data that a custom
  weapon may not carry; a custom melee weapon without an afterimage bypasses the client check entirely.
- **NEW-FINDING:** src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackTiming.java:41-49 — the delay
  table is a checked-in TSV of 162 actions; a client patch that renumbers actions silently falls back to profile
  cooldowns (the `swingMs` fallback at :98-102) with only a load-time warning, no QA failure.
- **NEW-FINDING:** src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotClientPoses.java:20 — the header
  names a `BotClientPosesTest` that does not exist in the tree; the tables are pinned by
  `BotAttackClientValidityTest.theExeTablesInBotClientPosesMatchTheExe` instead, so the comment is stale.

## State

**working.** Evidence: `BotAttackClientValidityTest` + `ClientAttackRulesTest` + `BotMeleeRangeTest` +
`LiveMeleeCloseCasesTest` pin the client-facing contract against an exe-derived fixture; `BotAttackTimingTest` and
`BotSwingPacingPerfTest` pin the cadence; `BotCombatFormulaTest`/`BotDamageTest` pin the numbers; auto-QA `t-bk-1`,
`t-bk-3`, `t-cb-1..4`, `t-sp-1` run it live on TEST. Residual gaps are the hand-checked visual checks
(docs/ops/qa-suite.md:927) and card b295 (MP gaps), which is open on the server side.

## Open questions

1. `BotClientPosesTest` is cited in a source comment (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotClientPoses.java:20) but absent — was it folded into
   `BotAttackClientValidityTest`, or is the exe-fixture test considered a superset by intent?
2. The 250 ms `TrainingBot` ticker and `BotSwingClock` coexist; `BotSwingPacingPerfTest` asserts no extra ticks, but
   nothing asserts that `pacedCombatTick`'s `GCMovement.isMapObserved` gate (src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:282-290) never starves
   an observed map.
3. `BotAttackConfig` holds ~80 hand-written job rows (src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackConfig.java:117-197); is there a fixture that asserts
   every job a bot can spawn with has a non-null kit, or is that only caught by the live showcase (`bk-1`)?
4. `AttackCapture` is TEST-only by design (src/main/java/soloMapling/server/AttackCapture.java:34) but gates the `!botshowcase` and `!classtest`
   commands (src/main/java/client/command/CommandsExecutor.java:372-375); should those commands be registered independently of capture?
