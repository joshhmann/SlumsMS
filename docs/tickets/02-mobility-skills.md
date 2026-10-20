# 02: Mobility skills per class

**Area:** Nav v2  
**Depends on:** 01  
**Status:** built on branch `mobility-skills` (not merged) 2026-10-20; waiting for the owner's in-game look

## What

Bots use Teleport, Flash Jump, Rush and similar as movement edges when their job has the skill. Per-class tables for stock classes only; refuse unknown MapleRoot custom classes.

## Done when

A mage or thief bot visibly teleports or flash-jumps on routes; other classes never take those edges; tests.

## What was built (2026-10-20)

The nav graph already had TELEPORT and FLASH_JUMP edges (v68, from nutnnut's v60-v67), planned by job alone and free
of cost. This ticket made them real; no new edge kind and no graph version bump.

- **Gate (`NavMovementSkills`):** a bot plans a skill edge only if its stock job learns the skill, it holds the skill
  at level 1 or more (its real build, `botskills`), the map allows movement skills, and it can pay for the cast.
  Custom classes, beginners and every other job plan walk-only. `-Dbot.nav.mobility=false` turns all skill edges off.
- **Cost (`NavSkillCast`):** each cast pays the skill's WZ `mpCon` at the bot's level through the real MP pool from
  ticket 08 (`BotVitals`), drinking an MP potion when short, like an auto-pot player. A bot with no MP potions keeps
  the last 30% of its MP for fighting and walks instead. A cooldown is honoured if the skill data has one (MapleRoot's
  Teleport and Flash Jump have none; the existing 490 ms recast floor stays). If a counted-on potion fails, the skill is
  backed off for 3 s so the planner does not replan into the same cast every tick.
- **Grind moves:** the grind blink/dash (`GCMovement.teleport/flashJump`) are gated and paid the same way;
  `MovementStylePolicy` gives the TELEPORT / FLASH_JUMP style only to a bot holding the skill (now including Blaze
  Wizards and Night Walkers); a Hermit still saving SP for Flash Jump fights as a haste thief. `FlashJumpTiers` uses
  the real Flash Jump level (short dash below 20).
- **Haste:** `BotMovementProfile` gives thief speed only once the bot holds Haste (Assassin, Bandit, and now Night
  Walker lines); a lineage bot with no build at all keeps the old job-based model.
- **Learning (ticket 04):** failures are classified against the skills the bot *holds* (not its MP), so a dry mage's
  failure on a teleport edge is still a genuine one, and a bot that lacks the skill is still ignored.
- **Flaws (ticket 33):** flash-jump edges now get hesitation, a false start and an overrun like plain jumps (never an
  early takeoff). Teleports never fail, as before. A committed skill edge stays usable mid-flight even after the cast
  spent the MP.
- **Read-out:** `!gcmove learned` adds `movement skills: N teleports, N flash jumps, N MP spent, N refused`.

### Per class (MapleRoot `Skill.wz`)

| Class | Skill | Used as |
| --- | --- | --- |
| F/P, I/L, Cleric lines (210-212, 220-222, 230-232) | Teleport 2101002 / 2201002 / 2301001 (mpCon 60 at lv 1 to 1 at lv 20) | TELEPORT edges, grind blink |
| Blaze Wizard 1210-1212 | Teleport 12101003 | same |
| Hermit, Night Lord (411-412) | Flash Jump 4111006 (mpCon 60 to 1) | FLASH_JUMP edges, grind dash |
| Night Walker 1410-1412 | Flash Jump 14101004 | same |
| Assassin, Bandit, Night Walker lines | Haste 4101004 / 4201003 / 14101003 | movement profile speed/jump |

Not modelled, on purpose:

- **Pirate Dash** (5001005, 15001003): a 4-20 s speed/jump buff on a double tap, not an edge; modelling it means a
  mid-route profile switch to another graph bucket.
- **Gunslinger Wings** (5201005): a slow-fall buff.
- **Aran Combat Step** (21001001, action `combatStep`) and **Dawn Warrior Soul Rush** (11101005): real dashes, but
  how a remote client draws them is unknown. They need a capture first (ticket 32 capture mode), then a new edge kind.
- **Warrior Rush** (1121006 and others) and Boomerang Step are attacks, not movement.
- MapleRoot's custom beginner **Ninja Bounce / Vertical Bounce** (1050, 1054) have no skill data and no grant path
  for bots.
- Shadowers have no Flash Jump in v83 data.

### Packets

For Teleport and Flash Jump the server broadcasts nothing but the movement: a real client's cast goes through
`SpecialMoveHandler`, which only takes the MP (`applyTo` has no buff and no effect broadcast for these). So the bots
keep sending the existing fragments: Teleport as cmd 4 at the origin, cmd 3 at the destination, then a cmd 0 settle;
Flash Jump as a real jump with a cmd 0 anchor plus a cmd 6 "fj" fragment at the apex. Nothing new goes on the wire.

## Evidence

- **Unit tests:** `NavMobilitySkillsTest` has 8 tests:
  - the job alone is not enough (fresh wizard, Hermit before Flash Jump);
  - a skill outside its stock job is never used (warrior, Shadower, Aran);
  - the switch;
  - MP cost, the dry reserve, potions, the refusal back-off;
  - cooldown;
  - learning classification by held skill;
  - grind style and dash tier follow the held skill;
  - Haste.

  `NavEdgeLearningTest` was updated so the hermit and wizard hold their skills.
- **Full suite:** `./mvnw -o test` ran 2,931 tests with 0 failures (6 skipped).
- **Nav lab** (`NavMobilityLab`, a dev tool in the test sources; dev-box WZ, cached v68 graphs; flaws off):
  - Maps: Ludibrium Lost Time <1> 220050000, Path of Time <1> 220040000, Kerning construction site 103010000,
    Sleepywood 105040300 and El Nath 211000000.
  - **Plan:** 400 seeded platform pairs per map and profile, walk-only against the navigator's own skill rule.
    Mage profile s145/j120, thief profile s155/j123.

| Map | Profile | Reached, walk / with skill | Mean planned time, walk to chosen | Routes using the skill | Casts per such route |
| --- | --- | --- | --- | --- | --- |
| Lost Time 220050000 | mage | 375 / 375 | 4.71 s to 2.91 s (-38%) | 289 | 5.3 |
| | thief | 370 / 370 | 4.31 s to 3.27 s (-24%) | 178 | 2.2 |
| Path of Time 220040000 | mage | 374 / 374 | 4.69 s to 3.92 s (-16%) | 161 | 3.9 |
| | thief | 378 / 378 | 4.39 s to 3.94 s (-10%) | 109 | 2.1 |
| KC construction 103010000 | mage | 384 / 384 | 10.94 s to 8.09 s (-26%) | 341 | 6.2 |
| | thief | 376 / 376 | 10.57 s to 7.11 s (-33%) | 314 | 4.0 |
| Sleepywood 105040300 | mage | 347 / 347 | 5.08 s to 3.98 s (-22%) | 197 | 3.9 |
| | thief | 334 / 334 | 4.99 s to 4.03 s (-19%) | 169 | 2.4 |
| El Nath 211000000 | mage | 284 / 284 | 7.25 s to 5.53 s (-24%) | 204 | 5.7 |
| | thief | **301 / 359** | 6.93 s to 5.16 s (-26%) | 257 | 3.3 |

  - **Live:** real-time headless bots with real builds (`BotProgression.buildNew`), 8 minutes, skills on against off.
    - There were 10 bots per map: F/P Wizard 38 (Teleport level 1), I/L Mage 85, Priest 95, Blaze Wizard 80,
      F/P Arch Mage 130, Hermit 78 (no Flash Jump yet), Night Lord 135, Night Walker 45 (no Flash Jump yet), Night
      Walker 90 and Hermit 110.
    - Abandoned trips are high on Sleepywood and El Nath in both modes (random targets behind one-way portals), so
      compare the modes with each other.

| Map | trips/bot/h off / on | mean trip off / on | abandoned off / on |
| --- | --- | --- | --- |
| Lost Time 220050000 | 684.8 / 795.8 (+16%) | 5.22 s / 4.44 s | 1 / 5 |
| Path of Time 220040000 | 663.0 / 702.8 (+6%) | 5.39 s / 5.08 s | 0 / 0 |
| KC construction 103010000 | 279.0 / 328.5 (+18%) | 11.98 s / 10.11 s | 15 / 16 |
| Sleepywood 105040300 | 297.8 / 313.5 (+5%) | 5.55 s / 4.86 s | 211 / 226 |
| El Nath 211000000 | 73.5 / 100.5 (+37%) | 9.80 s / 8.35 s | 234 / 235 |

  - **Totals:**
    - With skills on: 2,849 teleports, 1,069 flash jumps and 10,485 MP spent, with 0 casts refused.
    - Stuck-log events: 1,673 on against 1,601 off.
    - Penalised edges: 72 on against 64 off.
    - No failure was ignored as "skill unavailable".
  - **Per job:** trips summed over the five maps, off against on.
    - Arch Mage +12%, I/L +16%, Priest +14%, Blaze Wizard +16%, Night Lord +23%, Night Walker 90 +20%.
    - The bots without the skill were unchanged: Night Walker 45 had 253 trips off and 256 on.
    - The level 38 wizard (60 MP a blink, no potions) ended at 515/1715 MP, exactly its 30% reserve. It then walked,
      so its trip count is the same as with skills off (255 against 252).
- **No scratch server run.** The evidence is offline, as for ticket 33.

## What the owner should look at in game

- **Watch on a real client**, standing next to these bots (or a GM-spawned F/P Mage 2xx and Night Lord 412 on Ludi
  Lost Time, the KC construction site or El Nath):
  - A mage blinks between platforms. It should disappear at the origin and appear at the target with no lingering
    frame, no slide between the two points and no rubber-banding.
  - A downward teleport lands on the platform below in a prone-then-stand pose.
  - Flash Jump:
    - A thief jumps, then dashes at the apex. The dash animation and effect should play (not a plain glide).
    - The bot should land across the gap, not snap back.
    - Watch for chat errors such as "This skill cannot be used..." (the attack-check memory: nothing like that should
      show for movement).
  - The same for a Blaze Wizard and a Night Walker (2nd job and above).
- **Check that the gating holds:**
  - A level 70-80 Hermit that has not put SP into Flash Jump yet never dashes.
  - A Shadower, warrior, archer or pirate never teleports or flash-jumps.
  - Maps that forbid movement skills: none of it.
- **MP:** a young wizard (Teleport level 1) blinks a lot at first, then walks once MP is under about 30% with no blue
  potions. Its MP bar (party HP/MP, or `!bot` info) should go down with blinks.
- **Teleport range:** the graph uses GreenCatMS's captured 150 px. MapleRoot's Skill.wz lists a `range` of 205-300 by
  level. If a real mage's teleport visibly goes further than a bot's, say so; the graph constant is easy to change, but
  it changes every graph.
- **Read-out:** `!gcmove learned` shows the cast counts and MP spent.
