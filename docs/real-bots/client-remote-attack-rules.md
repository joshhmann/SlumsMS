# How the v83 client handles another character's attack

Ticket 32. Read out of MapleRoot's `MapleStory.exe` (md5 `89f69d20cc0457a2d7db5573082c13d7`, the test client's exe on 2026-10-21) with capstone and unicorn. The client's data came from the test client's `Data` folder.

What uses these rules:

- `tools/bot-attack-rules/client_attack_fixture.py` reads the client's `Skill/*.img` and `Character/Weapon/*.img`. It writes `src/test/resources/soloMapling/client-attack-data.tsv`, whose header carries a sha256 of the files it read.
- `tools/bot-attack-rules/exe_rules.py` writes `client-attack-exe.tsv`: the action names, the plain-pose tables, and per skill the hard-coded lists, the route, and the weapon types.
- `ClientAttackRules` (test) is the model of the rules on this page.
  - `BotAttackClientValidityTest` runs every bot attack through it.
  - `ClientAttackRulesTest` checks that it catches the known drops.
- The client's `attackdiag.cpp` (client repo, branch `bot-attack-diag`) logs the same drops live.

Confidence: **high** for every address and check below; each was read from the code, not inferred from behaviour. **Medium** is marked where it applies.

## Packet layout (server → viewer)

`CLOSE_RANGE_ATTACK 0xBA`, `RANGED_ATTACK 0xBB`, `MAGIC_ATTACK 0xBC` and `ENERGY_ATTACK 0xBD` all reach `CUserRemote::OnAttack(nType, iPacket)` at `0x009803AB`, after `CUserPool` has read the attacker id.

| field | read as | notes |
|---|---|---|
| mobs / lines | 1 byte: `mobs<<4 \| lines` | |
| attacker level | 1 byte | |
| skill level | 1 byte; `if > 0` then int skill id | clamped to 1..maxLevel (`0x00980426`) |
| option | 1 byte | bit `0x20`: no skill effect for a `skillType 4` skill (`0x009807A8`) |
| action | 2 bytes: `action & 0x7FFF`, bit 15 = facing left | the server's `direction` byte, then its `stance` byte |
| speed | 1 byte | animation speed (`0x009807F9`) |
| mastery | 1 byte | |
| bullet | int | the projectile item |
| per mob | int mob id (0 = empty slot, nothing more is read); 1 byte hit action; `lines` ints, bit 31 = crit | Meso Explosion has a count byte |
| ranged only | 2 shorts | |
| Big Bang, Evan breaths | int keydown time (`0x0098066E`) | |

A packet that ends early makes `CInPacket` throw, which is a client error. Mobs the viewer does not have are removed (`0x009806AA`).

## What can drop or alter a remote attack

### 1. Pose and weapon: `CUser::CheckAttackAction` `0x0092EDB2`

`OnAttack` calls it (`0x00980760`) unless the skill is Heal, Hurricane, Rapid Fire, Grenade, Spark (`15111006`), WA Hurricane or Poison Bomb (the list at `0x00980722`). A return of 0 drops the **whole** attack: no pose, no skill effect, no damage numbers.

```
CheckAttackAction(action, speed, pSkill, slv):
  if avatar.oneTimeAction > -1: return 0        // OnAttack resets it first, except for the keydown skills
  wt = get_weapon_type(weaponItem)              // 0x460aa0: item/10000%100 for 30..33, 37..49, else 0
  t  = weapon "attack" type (Character.wz info/attack, CUser+0x4F8): 1 1H, 2 spear/polearm, 3 bow,
       4 crossbow, 5 2H, 6 wand/staff, 7 claw, 8 knuckle, 9 gun
  ok = T1(t, wt, action, pSkill, slv) || T2(...) || T3(...)    // 0x7665b1 -> 0x766379 / 0x76649c, 0x766525
  if ok: play the pose; return 1
  if !pSkill: return (job/100 == 21 || job == 2000) && action in 122..124   // Aran's plain pole-arm swings
  if !pSkill.IsCorrectWeaponType(wt): chat "This skill cannot be used with the equipped item."  (0x1116)
  return 0

T<n>(t, wt, action, pSkill, slv):               // the same shape for all three tables
  if t not in 1..9: return false
  T1 only: if action == 32 (proneStab): return pSkill == null
  if pSkill:
    if !pSkill.IsCorrectWeaponType(wt): return false          // 0x766157
    if pSkill.HasOwnAction(slv) and pSkill.skillType != 3:    // 0x76611c
      return pSkill.IsValidAction(slv, action)                // 0x766418
  row = table<n>[t][pSkill && skillType == 3 ? "type3" : "norm"]
  return action in row
```

- `HasOwnAction` is true when the level's `action` is a pose the client knows, or the skill's `action` list holds at least one.
  - Unknown names are left out when the skill loads (`0x0075D254`). MapleRoot's `SMsolarPierce`, `SMsolLunaSlash` and `STtypoon` therefore count as no action.
- `IsValidAction` checks for the level's action if it has one, otherwise any action in the list. `assassinationS` is also accepted when the list has `assassination`.
- `IsCorrectWeaponType` (`0x00766157`) has hard-coded groups that win over Skill.wz `weapon`:
  - knuckle or gun: 5001001, 15001001;
  - knuckle: 5101002-4, 5111002, 5111006, 5121001-2, 5121004-5, 5121007, 15101003, 15101005, 15111003-4;
  - 1H or 2H sword: 11101004, 11111006;
  - wand or staff: 12101006, 12111005-6, 22101000, 22181000, 22181003.
  - Any other skill needs `weapon == wt`, or a Skill.wz `weapon` of 0.
- The plain-pose tables (code: name):

| attack type | T1 melee | T2 shoot | T3 magic | type3 rows |
|---|---|---|---|---|
| 1 one-handed | swingO1-3, stabO1-2 | none | 28/29 (wand swings) | swingOF, stabOF |
| 2 spear/polearm | swingT2, swingP1-2, stabT1-2 | none | none | swingPF, stabTF |
| 3 bow | swingT1, swingT3 | shoot1 | none | shootF |
| 4 crossbow | swingT1, stabT1 | shoot2 | none | shoot2 |
| 5 two-handed | swingT1-3, stabO1-2 | none | none | swingTF, stabOF |
| 6 wand/staff | swingO2 | none | 28/29 | none |
| 7 claw | stabO1-2 | 24-26 (claw throws) | none | 24-26 |
| 8 knuckle | stabO1-2 | none | none | none |
| 9 gun | swingT1, swingT2 | shot (93) | none | shot |

- A viewer accepts a plain pose from any of the three tables.
- A knuckle's swings (swingO1-3) are therefore **dropped**, and so is a gun shot in any pose but `shot`.

### 2. Per route (`0x009809C3` dispatch)

| route | function | drop |
|---|---|---|
| melee `0xBA` | `0x00980C90` | The weapon has no Character.wz `afterImage`: `0x00936F34` returns an empty path and no hit is drawn. The pose and skill effect were already shown. |
| shoot `0xBB` | `0x00981211` | A shot from a weapon that is not a bow, crossbow, claw or gun (`0x008C470D`), unless the skill is a pole-arm/knuckle/sword shot (`0x0076681D`: Taunts, Energy Orb, Soul Blade, Spark, Shark Wave, Combo Smash/Fenrir/Tempest). Nothing is drawn. |
| magic `0xBC` | `0x0098250D` | No skill, or a skill the client does not have. Nothing is drawn. |

- For all routes, the attacker's current pose must have frames (`+0x4F0`). After `CheckAttackAction` it always does.
- **Medium confidence:** counts are not checked. The shot drawing loops over the skill's `bulletCount` (×2 under Shadow Partner) and spreads the lines over the bullets. Extra lines are still drawn, so wrong counts change the look but are not dropped.

### 3. Keydown skills

- `is_keydown_skill` (`0x004FB08F`) is true for:
  - Monster Magnet 1121001/1221001/1321001;
  - Big Bang 2121001/2221001/2321001;
  - Hurricane 3121004, Piercing Arrow 3221001;
  - Corkscrew 5101004/15101003, Grenade 5201002, Rapid Fire 5221004;
  - WA Hurricane 13111002, Poison Bomb 14111006;
  - the Evan breaths 22121000/22151001.
- `CUserRemote::OnPrepare` (`0x00980A81`, the `SKILL_EFFECT` packet) reads:
  - skill int, level byte;
  - a short: the pose (`0x7FFF` = none) plus the facing bit;
  - a speed byte.
- **A skill the client does not have makes OnPrepare throw** (`0x00980BD8`). So a keydown packet must only ever name a skill the client has. MapleRoot has no Big Bang.
- For a keydown skill, OnPrepare stores the keydown state (`+0x37CC`). It plays the pose straight away, with no check, so the pose must be below 162.
- `OnAttack` leaves the attacker's pose alone for Hurricane, Rapid Fire, Spark and WA Hurricane (`0x00980452`). It also skips their pose check.
  - Without a keydown packet first, their arrows and bullets fly from a character standing in an idle pose.
- `OnPrepareCancel` (`0x00980BF5`, `CANCEL_SKILL_EFFECT`) ends the keydown for Hurricane, Rapid Fire and WA Hurricane.

### 4. Skill effect

- `0x00933990` (ShowSkillEffect) is skipped for the keydown/thrown skills (`0x0098076D`).
- It is also skipped for a `skillType 4` skill sent with option bit `0x20`.
- No drop.

## What a player's own client sends

These are the `CUserLocal` send paths. Bots copy them in `BotSwingPlan` and `BotAttackData.actionFor`.

### Route

`CUserLocal::DoActiveSkill` (`0x00966F7A`) has a per-skill switch from `0x009678E6`.

- `exe_rules.py` runs that switch in unicorn for every skill. It records the handler reached: melee `0x00969465`, shoot `0x009696A2`, magic `0x00969B10`, keydown `0x0096A86E`, buff `0x00969E21`, and so on.
- MapleRoot's custom skills are routed by its DLL instead (`mapleroot/ijl15/SkillEdits/Activeskill.h`, a code cave at `0x0096792A`). The tool reads that table too.
- A few of these routes are not obvious:
  - The Aran combo finishers, Shark Wave, Energy Orb and Soul Blade go out as **shots**.
  - MapleRoot's Night Walker Avenger (`14111002`) goes out as **melee**.
  - Heal is a `SPECIAL_MOVE` plus a spell.

### Pose

- Melee uses `0x00766031` with T1, shoot uses `0x00766272` with T2, and magic uses `0x007662F5` with T3.
- When the skill has its own pose, the client sends the level's `action`, else a random one of its `action`s.
- Otherwise, or for `skillType 3`, it sends a random pose from its route's row for the weapon's attack type.
- Special cases in the melee function (`0x00950DE5`):
  - Brandish/Raging Blow 1121008 and DW Brandish 11111004: brandish1 with a 1H sword, brandish2 with a 2H, any other weapon refused;
  - Blast 1221009: `blast`;
  - lying down: proneStab.

### Speed and the other fields

- **Speed:** `0x00765066`: weapon `attackSpeed` + booster + Speed Infusion, clamped to 2..10. MapleRoot has no boosters.
- **Option byte:** bit 5 is a "serial" flag, bits 4 and 7 come from temporary stats, and the low nibble is 1 for a skillType 3 skill after Slash Blast. A plain attack sends 0.
- **Counts:** a melee or magic skill sends `attackCount` lines per mob. A shot sends `bulletCount` lines, doubled under Shadow Partner. The client hits at most `mobCount` mobs.

### Keydown packets

- The local client sends `SKILL_EFFECT` (`0x0096B0BE`): skill, level, the prepare pose from `prepare/action` (`0x7FFF` when the client does not know the name) plus the facing bit, and the speed.
- Hurricane, Rapid Fire and WA Hurricane then send a stream of shots and a `CANCEL_BUFF` when released.
- On release (`0x0095BFEC`):
  - Corkscrew sends one melee attack;
  - Big Bang and the Evan breaths send one spell carrying the keydown time;
  - Piercing Arrow sends one shot;
  - Grenade and Poison Bomb send a `THROW_GRENADE`.

## What code alone cannot establish (needs eyes)

- **Timing.** The bots send the keydown packet and the first shot together. The rest of a Hurricane or Rapid Fire burst follows 120 ms apart, then the release. A player's prepare time (Skill.wz `prepare/time`, 960 ms for Hurricane) and the real shot interval have not been measured.
- **Attack cadence.** The bots' swing cooldowns are not derived from the pose's frame delays. A bot may start the next swing before the pose ends, or idle after it.
- **The option byte.** The temporary-stat bits a player's client sets (bits 4 and 7) are not modelled. Bots send 0.
- **What counts look like.** Whether a skill with wrong counts looks right. Bots now send what the level allows.
- **The attacker's name** in the diagnostic log (`CUser+0x11AC`) is a best guess from the chat code. The character id is exact.
