# regular-buffs-real (1.6.7): expected outcome and balance note

Written by Bots, 2026-11-08 08:05 UTC (00:05 PST). Base read: 167-companion-policy @ be70c0559. Copied into this
branch (167-alt-real-buffs, base c2994bd54 = release-1.6.7 c44d1b7c8 + 167-alt-settings 6ddda61ef) with the line
numbers re-read against that base and nothing else changed.
Owner decision (8 Nov, about 08:0x UTC): "Really work." Alts' party buffs get real effects at our skill-data values,
behind PartyCompanionPolicy (alts on, regulars off until 1.7.1).

## 1. What happens today (read from code)

| Who casts | Who receives | Today |
|---|---|---|
| any bot (alt or regular) | a real player in the party, in range | REAL: `effect.applyToTarget` at the bot's learned level, MP paid (BotBuffEffects.applyToTargets :248-258, castBuff :193-210). |
| any bot | another bot (alt or regular) | COSMETIC: `showBuff(target)`, nothing registered. |
| any bot | itself | COSMETIC, not registered. BotDamage.shownBuffs (:178-187) adds its own buffs to its OWN damage maths only. HP, defence, speed, EXP and drops get nothing. |
| a bot Cleric/Priest/Bishop | any party member, Heal | REAL already: players through applyToTarget, bots through BotVitals.heal (BotPartySupport.castHeal :366-392). |
| — | Dispel | MISSING: no bot casts Priest.DISPEL 2311001. |

So the owner's ask comes down to three gaps:
1. a companion receiving a party buff gets the real stat;
2. the caster of a party buff gets it on itself too, as a player's cast does;
3. a priest companion uses Dispel.

Real players see no change: they already get the real buff.

## 2. Expected outcome (1.6.7, switch `slums.companion.buffs.*`, alts on, regulars off)

A party buff cast by any bot reaches each party member in the buff box (BotBuffEffects.nearbyPartyMembers, ±700/±350
— PARTY_BUFF_RANGE_X/Y at :171-172, the selection at :359):
- **A real player:** unchanged.
- **A companion the policy allows** (`PartyCompanionPolicy.on(REAL_BUFFS, target)`, an owned alt by default): the
  real StatEffect is registered on it at the CASTER's learned level (`handOutEffect`), for the skill's own duration.
  Examples:
  - Hyper Body raises its max HP/MP;
  - Holy Symbol raises its EXP (Monster.getStatusExpMultiplier reads HOLY_SYMBOL);
  - Sharp Eyes raises its crits (BotDamage :217 reads SHARP_EYES);
  - Bless/Rage/Meditation raise its watk/matk (getTotalWatk);
  - Haste raises its speed;
  - Maple Warrior raises its stats;
  - Holy Shield blocks debuffs;
  - Meso Up raises its meso drops.

  It also gets the "received a party buff" particle, as a player does.
- **A companion the policy does not allow** (a regular by default): unchanged, cosmetic.
- **The caster:** if the policy allows the caster, its own party buff is registered on itself, same level and
  duration.
  - BotDamage.shownBuffs already skips a buff `hasBuffFromSourceid`, so its damage is not counted twice. A test
    proves this.
  - Self-only buffs (Combo, Shadow Partner, Infinity, Berserk, Enrage, charges, Mana Reflection, Concentrate, Shadow
    Stars, Magic Guard) are NOT registered: unchanged, out of scope. (Combo's aura in particular breaks if
    registered; see BotBuffEffects :119-124.)
- **MP:** a cast that will register on any target (a real player OR a policy-allowed companion) is paid at the
  learned level. Today only a real-player target triggers payment (anyRealPlayer :212). An alt already pays every cast
  (Pay.ALL). No free real buff, ever.
- **No cast:** if the caster can't pay, nothing is registered on anyone. The aura shows as today.
- **Durations and cooldowns:** our Skill.wz values, unchanged. work-62 confirmed that the 3600 s durations are our own
  data, not a bake artefact. The existing recast cadence (BotBuffDriver.maintain) is unchanged.
  - The recast-on-lack rule (partyMemberLacks :345) also counts policy-allowed companions as members that can lack a
    buff. Otherwise an alt never gets a recast after a dispel.
- **Dispel (new, shared, in BotPartySupport):**
  - Who casts: a companion with Priest.DISPEL learned (Priest, Bishop), the policy on for the CASTER.
  - When: a party member in reach (player or policy-allowed companion) carries a dispellable disease (seal,
    darkness, weakness, curse, poison, slow, seduce, confusion; whatever Character.dispelDebuffs clears).
  - How: it casts Dispel through the real StatEffect path, paying MP, at most once per DISPEL_GAP_MS (a constant,
    e.g. 4 s, like HEAL_GAP_MS at BotPartySupport :48). The dispel is the stock one: party members in the box.
  - Heal keeps priority when someone is under HEAL_BELOW_PERCENT (BotPartySupport :47).
- **A mob's dispel still works on companions.** Dark Wyvern 8810021's attack2 carries DISPEL (mob skill 127). When it
  hits an alt holding a real Hyper Body, the buff is removed, exactly as on a player. The bots then recast it through
  the lack rule. The owner's "recast Hyper Body" advice holds.
- **Switch off for everyone** (`-Dslums.companion.buffs.alts=false`): byte-for-byte today's behaviour.

## 3. Balance note

- **Parity, not a new power.** Every value a companion now gets, a real player in the same party has been getting
  since 1.6.2 (b110/161). The change only stops companions from being the one party member a buff skips.
- **The biggest effects:**
  - **Holy Symbol, our data x=50, 3600 s.** An alt partied with a priest companion gets +50% EXP from party kills
    (USE_FULL_HOLY_SYMBOL decides the /500 small-party rule, the same as for players). Alts level faster in priest
    parties. The owner chose this; it is a 1.6.7 notes line.
  - **Hyper Body, y=60.** +60% max HP/MP. Alts survive boss hits much better, so fewer deaths and fewer instant-revive
    fees.
  - **Sharp Eyes, our data x=20 crit / y=130 crit damage** (higher than the old tables; work-62 report §2). Alt damage
    in bosses goes up noticeably with a bowman companion.
  - **Holy Shield.** Debuff immunity in bosses for its 5 s.
  - **Meso Up, x=150.** An alt's own meso drops go up while it holds it. This is the only economy effect; it is the
    same value real players already get from a bot Hermit.
- **Limits that stay in place:**
  - learned level only (no max-level hand-outs outside the GM command);
  - MP paid every time;
  - box range;
  - same party;
  - stock durations;
  - no stacking beyond what StatEffect already does (a re-cast refreshes).
- **Regulars (1.7.1).** With `-Dslums.companion.buffs.regulars=true`, party regulars also receive the real stats. That
  raises regular survival and EXP in mixed parties. Leave it off until 1.7.1 as decided.
- **Not changed:**
  - the chat-request grant (BotBuffRequestHandler, its 10 min / 3 min cooldown);
  - Heal (already real);
  - prices;
  - rates.

## 4. Tests the change must bring (each fails on be70c0559 or the wave base)

1. An alt in a party with a bot Spearman: Hyper Body cast → the alt has HYPER_BODY_HP registered, at the caster's
   learned level, with the WZ duration. A regular with default switches → nothing registered (cosmetic, as today).
2. The caster alt has its own party buff registered. BotDamage.attacker on it counts Rage's watk ONCE (not shown + registered).
3. A self-only buff (Combo, Shadow Partner) is never registered.
4. MP: a regular caster with only an alt in reach (no real player) pays MP when the alt will receive the real buff.
   If it can't pay, nothing is registered.
5. Holy Symbol on an alt: Monster EXP multiplier for that alt > 1.0. Without the buff: 1.0.
6. A mob DISPEL (mob skill 127, Dark Wyvern 8810021 attack2 data) on an alt holding real Hyper Body removes it, and
   partyMemberLacks then reports the lack, so the buff is recast.
7. Dispel: a priest alt, a party member with SEAL → Dispel cast, the SEAL cleared, MP paid. No second cast within
   DISPEL_GAP_MS. Under the heal line, Heal goes first.
8. The switch off for alts → every test above shows today's behaviour.

## What existing characters keep

Nothing is taken away. The change only ever adds a real StatEffect to a bot that previously got a picture, and only
for a companion the policy allows (owned alts by default, regulars off). It changes no skill number, duration, MP
cost, range, rate or price: the WZ values the bot already cast at are the ones handed out (`handOutEffect`, the
bot's learned level). Real players get exactly what they got before. A companion keeps every buff it holds today as
a display: with `-Dslums.companion.buffs.alts=false`, or for a regular with the defaults, the code path is the old
cosmetic one. No skill level, stat, item, meso, quest, rebirth or saved setting is read, moved or rewritten, and no
`-D` property changes a character's stored data: the switch only decides whether a received buff is registered.

One visible difference for an alt: a registered buff now expires on the server (Character.removeExpiredEffect) where
the aura used to stay up until the next recast. That is what a real player has always had, and the lack rule
(`BotBuffDriver.maintain` → `BotBuffEffects.partyMemberLacks`) recasts it within its 30 s gap.

Migration: none needed, because no table, column, row or item is touched. The switch defaults to on for owned alts
only, which is the state this branch ships.

## Out of scope

Self-only buffs (Combo, Shadow Partner, Infinity, Berserk, Enrage, charges, Mana Reflection, Concentrate, Shadow
Stars, Magic Guard) stay cosmetic on the caster. The chat-request grant (`BotBuffRequestHandler`), its cooldowns and
the GM commands are untouched. `BotDamage` is not edited: only tested. Per-alt toggles beyond the existing
`CompanionSettings` heals/support are not added.

## Test list (filled in by the implementation, see REPORT-deepseek.md)

- `RealBuffsPolicyTest` (party companion policy): REAL_BUFFS("buffs") is on for an alt and off for a regular by
  default; `-Dslums.companion.buffs.regulars=true` turns it on for a regular and `-Dslums.companion.buffs.alts=false`
  off for an alt.
- `BotBuffRealTargetTest` (BotBuffEffects.applyToTargets): an alt receives the real Hyper Body at the caster's level
  and WZ duration; a regular with the defaults stays cosmetic; switching `-Dslums.companion.buffs.alts=false` puts the
  alt back on the cosmetic path.
- `BotBuffCasterSelfTest`: the caster's own party buff is registered only when paid and only for a party buff; a
  self-only buff (Combo, Shadow Partner) is never registered; `BotDamage.attacker` counts a registered Rage once.
- `BotBuffPaymentTest`: a regular caster with only an alt in reach pays MP; with no MP nothing is registered and the
  alt stays cosmetic.
- `BotBuffHolySymbolTest`: a registered Holy Symbol on an alt raises its EXP multiplier above 1.0; without the buff it
  is 1.0.
- `BotBuffMobDispelTest`: the mob DISPEL path (MobSkillType.DISPEL, as Dark Wyvern 8810021's attack2 carries) removes
  the alt's real Hyper Body and `partyMemberLacks` then reports the lack.
- `BotPartyDispelTest`: a priest alt dispels a SEALed member, MP paid, at most once per `DISPEL_GAP_MS`; a member
  under `HEAL_BELOW_PERCENT` gets Heal first; a regular priest with the defaults does nothing; `heals off` in
  `CompanionSettings` stops it.
