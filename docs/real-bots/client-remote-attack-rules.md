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

- **Speed:** the stock exe's `0x00765066` is weapon `attackSpeed` + booster + Speed Infusion, clamped to 2..10 (Double Stab -2). **MapleRoot hooks it** (client repo `mapleroot/ijl15/SkillEdits/ActiveSkill.h` `GetAttackSpeedDegree`, set in `MapleHook.cpp`): the weapon class alone sets it, the booster and the item's `attackSpeed` are ignored, and only Speed Infusion's x is added, clamped at 0:
  - 3 for 1H swords, axes and blunts, wands, staves, pole-arms, claws and guns (classes 30-32, 37, 38, 44, 47, 49);
  - 2 for daggers (33);
  - 4 for everything else (2H weapons, spears, bows, crossbows, knuckles).
  - Seen on a real client: the test server's `attack-capture.tsv` has a GM beginner's 1302000 sword (item `attackSpeed` 4) sent at 3, 117 times.
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

## Attack timing

How long an attack keeps a player busy, which bots now copy (`BotAttackTiming`, on unless `-Dslums.botattacktiming=false`).

- **A pose's length** is the sum of its frame delays in the client's `Character/00002000.img`.
  - A negative delay counts by its size. The skill poses write the frames before the hit negative (brandish1: 3 x -120, then 4 x 120).
  - A skill pose's frame that names another action (`action`, `frame`) shows that frame's picture for its own delay.
  - `tools/bot-attack-rules/action_delays.py` writes the lengths to `src/main/resources/soloMapling/bot-action-delays.tsv` (on the classpath, so a jar-only install has it). It also writes `src/test/resources/soloMapling/bot-skill-timing.tsv`: every client attack skill (253) and weapon class it takes, with its poses and their length at that class's speed.
  - The client's `00002000.img` is MapleRoot's and differs from the server's XML in 17 actions (alert2, meteor, blizzard, blast, showdown, pyramid, the Aran combo poses, the Evan dragon poses ...). The table is the client's.
- **Speed stretches it:** length x (speed + 10) / 16 (`0x009525ED`). A beginner's 800 ms sword swing at speed 3 is 650 ms. In the capture, the same player's swings follow each other 633-677 ms apart (most 655-662).
- **The client thinks in 30 ms steps.** `CWvsApp`'s update loop adds 30 to its update time once per step (`0x009F85BA`), and `get_update_time` (`0x00987257`) reads that time. A held key therefore attacks again on the first step after the pose ends: the cadence is the pose's length rounded up to 30 ms. The 650 ms swing comes out every 660 ms, the middle of the capture's 655-662.
- **Keydown streams** (Hurricane, WA Hurricane, Rapid Fire). CUserLocal's keydown update shoots when more than 100 ms have passed since the last shot (`0x0094BA7E`-`0x0094BAB0`, the last-shot times at `+0x3144` and `+0x3148`). On 30 ms steps that is a shot every 120 ms, from the first step after the keydown packet, for as long as the key is held. The shot loop (`0x0094B9FA`-`0x0094BC2E`) does not check Skill.wz `prepare/time` (960 ms for Hurricane), so the first shot does not wait for it. **Medium confidence:** that the prepare time plays no other part. Its only use found is a comparison of the hold time with a level field at `0x0096CFAD`.
  - The keydown packet's pose plays once, and the shots keep it (`OnAttack` leaves the pose alone for these skills). There is no pose per shot.
  - Releasing the key sends `CANCEL_BUFF`, which the server relays as `CANCEL_SKILL_EFFECT`.
- **Client-side reuse delays** of the release-type keydown skills (`0x009535E3`): Monster Magnet 2000 ms, Grenade 1000 ms, Poison Bomb 1500 ms. No bot uses a release-type keydown skill (Monster Magnet, Big Bang, Piercing Arrow, Corkscrew, Grenade, Poison Bomb, the Evan breaths). Anyone adding one needs these delays, plus a hold between the keydown packet and the release attack.
- **Prepare-only skills** (Explosion 2111002, Chakra): the client knows no `explosion` pose, so the keydown packet carries `0x7FFF` and the spell follows at once. Bots send it the same way.
- **Confidence:**
  - High for the speed rule (code and capture agree) and for plain swings (capture).
  - Medium for skill poses with negative frames: that they count by their size is read from the data, not yet seen on a client.

What the bots do with it:
- They send MapleRoot's speed (above) instead of the item's `attackSpeed`. Before, almost every weapon went out slower than a player's, for example a staff at 8 instead of 3.
- A bot swings again on the first 30 ms step after its pose has played out, instead of after a fixed 720-900 ms. A long pose (Barrage 2.1 s, Tempest 2.4 s, Genesis 2.2 s) is no longer cut off by the next swing, and short ones (Aran's double swing, 341 ms, every 360 ms) come faster.
- A grinding bot holds still until its pose ends, never less than 500 ms.
- **Swing clock (1.5.0, `BotSwingClock`).** The shared combat ticker still visits every grinder every 250 ms. A bot that swings on an observed map also arms a one-shot tick for the moment its swing ends, so the next swing goes out then, not on the next visit. Before, 650 ms became 750 ms.
  - While that tick is armed and the swing is still playing, the ticker skips the bot.
  - Unobserved bots arm nothing and keep the coarse tick.
  - Rollback: `-Dslums.botswingclock=false`.
- **Keydown streams (1.5.0).** Hurricane and Rapid Fire send the keydown packet, then shoot every 120 ms while a mob is in reach, then release. A hold lasts at most 25 shots (3 s), and the bot may press again as soon as it has released.
  - Before, the burst was a fixed 6 shots, and the bot then stood for the pose's length on top of it.
  - With `-Dslums.botattacktiming=false`, the old 6-shot burst comes back.
- **MapleRoot's alert2 spells.** Flame Barrier 12121012 (Blaze Wizard) and Inferno Aura 2121054 (F/P) really are cast in `alert2`.
  - Both the client's and the server's Skill.wz name it as their only action, neither has a cooltime at any level, and the client's DLL routes both through Fire Arrow's spell path without a delay of its own.
  - The client's `alert2` is 270 ms, so 219 ms with a staff or wand. A player holding the key casts every 240 ms, and so do the bots.
  - The effect animations last longer than that (720 ms and 1140 ms), so a viewer sees them overlap.

## What code alone cannot establish (needs eyes)

- **Keydown timing on a screen.** The 120 ms shot interval and the absence of a prepare wait are read from the exe (above), not yet seen next to a real player's Hurricane.
- **The option byte.** The temporary-stat bits a player's client sets (bits 4 and 7) are not modelled. Bots send 0.
- **What counts look like.** Whether a skill with wrong counts looks right. Bots now send what the level allows.
- **The attacker's name** in the diagnostic log (`CUser+0x11AC`) is a best guess from the chat code. The character id is exact.
