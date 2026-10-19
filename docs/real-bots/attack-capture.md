# Attack capture: copy a real client's attacks (ticket 32)

Guessing a class's attacks from skill data caused every bot attack bug so far. Instead, the owner plays each class on
a GM character on the **test server**. The server records every attack packet the real v83 client sends, and the
bots are set from that record.

## Turning it on

Capture is off unless the JVM is started with `-Dslums.attackcapture=true`. There is no `config.yaml` key, because
the YAML is strict and an unknown key stops boot. The test install's `start-test.sh` passes the flag. Live never does.

With the flag on:
- **What is captured:** only real characters with GM level 2 or more. Bots and normal players are never captured, so
  a friend on the test server is never logged.
- **Where it goes:** `Server/logs/attack-capture.tsv`, appended, with one header line when the file is new. On 164
  that is `/opt/mapleroot-dev/persistence-test/Server/logs/attack-capture.tsv`.
- **The GM command:** `!classtest <job id> [level]` is registered (GM level 2). It changes the job and sets a level:
  30, 70, 120 or 200 by job tier unless you give one. It maxes every skill of the job's line, including MapleRoot's
  NPC 2084000 package and hyper skills, which sit in the 4th-job image. It gives one weapon of each kind the bots of
  that job carry, plus two stacks of ammo (Ilbi stars, arrows, bolts or bullets). It sets stats to 999 so you can
  equip the weapons, and fills HP/MP to 30,000.

## Each line

One line per attack. The event column says which kind:

| Event | Packet |
| --- | --- |
| `close` | close-range attack |
| `ranged` | ranged attack |
| `magic` | magic attack |
| `touch` | Energy Charge / Body Pressure touch attack |
| `summon` | summon attack |
| `cast` | buff or skill cast (`SPECIAL_MOVE`) |
| `prepare` | keydown start (`SKILL_EFFECT`) |
| `cancel` | keydown release or buff cancel (`CANCEL_BUFF`) |

Each line also records:
- **Who and where:** the character, job, level and map.
- **The skill:** skill id, name and level.
- **The weapon:** item id, weapon class (30–49, as `BotSkillActions.weaponClass`) and weapon type.
- **Buffs that shape attacks:** combo orbs, Aran combo, Energy Charge, morph, Shadow Partner, Soul Arrow, Shadow
  Claw, booster, WK charge, and so on.
- **The raw packet as hex,** opcode first. A field decoded wrongly here can still be read again from the file.

The header columns use the bot side's names. A bot fills the attack header's `display`, `direction` and `stance`
bytes with `0`, its `bodyActionId` and its `facingMask` (`BotAttackEffects`). So the capture calls the client's
direction byte `bodyActionId`, named from the client's action table (`actionName`), and the stance byte
`facingMask`.

The other header fields are `speed`, `numAttacked`, `numDamage` and `charge`. Ranged attacks also record:
- `rangeDirection`;
- `bulletSlot`, which is read from the v83 tail of time, USE slot, CASH slot and one more byte, kept in
  `extra=rangedTail=...`;
- `projectile` and `cashProjectile`, the ammo found at those slots.

## A session, per class group

On a GM character on the test server (login port 8489):

```
!classtest 512          Buccaneer, level 200, all skills, a knuckle (equip it from the inventory)
!heal                   whenever needed
```

Then go to a map with packs of mobs. Play for a few minutes the way players do: single target, mobbing, buffs,
transformations, keydowns. Then run the next job:

| Wave | Jobs (`!classtest <id>`) |
| --- | --- |
| 1 Pirates, Aran | Buccaneer 512, Marauder 511, Corsair 522, Outlaw 521, Thunder Breaker 1512 (1511), Aran 2112 (2111) |
| 2 Warriors | Hero 112, Paladin 122, Dark Knight 132, Dawn Warrior 1112 |
| 3 Mages, archers | F/P 212, I/L 222, Bishop 232, Blaze Wizard 1212, Bowmaster 312, Marksman 322, Wind Archer 1312 |
| 4 Thieves | Night Lord 412, Shadower 422, Night Walker 1412 |

A level argument tests a lower tier with the same job, for example `!classtest 511 120`.

The stock commands still work for anything else:
- `!job <id>`, `!level <n>` and `!maxskill <job img>` (one Skill.wz image);
- `!item <id> [qty]`, `!maxstat` and `!heal`.

## Reading it

Copy the capture over, then summarize it per job:

```
scp root@192.168.0.164:/opt/mapleroot-dev/persistence-test/Server/logs/attack-capture.tsv /tmp/
python3 tools/attack-capture/attack_capture.py summary /tmp/attack-capture.tsv [--char NAME] [--job 512]
```

The summary shows, for each job:
- the skills used, with counts;
- the rotation, in attack order with repeats folded;
- the most common skill switches;
- for each skill, every value seen for `bodyActionId`, `facingMask`, `speed`, `display`, mobs and lines, charge,
  projectile, weapon class and buffs;
- the casts and keydowns.

To diff a capture against the bots, export what the bots send on the branch you are aligning, then diff:

```
./mvnw -q test -Dtest=BotAttackExportTest -Dbot.attack.export=target/bot-attacks.tsv
python3 tools/attack-capture/attack_capture.py diff /tmp/attack-capture.tsv --bots target/bot-attacks.tsv
```

The export has one row per job, weapon kind and role (single, aoe, ultimate), with the skill, the body actions the
bot picks from, facing masks, speed, mobs, lines, charge and projectile.

The diff marks each skill the player used:
- `MATCH`: the bots send it the same way;
- `DIFF`: the bots send it differently, with the fields that differ;
- `PLAYER ONLY`: the bots never use it.

It also lists `BOT ONLY` skills, which the player never touched. A claw `projectile` difference is often only the
bots' level-based star choice.
