# 32: Align bot attacks with real players, in waves

**Area:** Real bots
**Depends on:** the bot attack fixes on `persistence-on-v120` (poses, weapons, combo orbs)
**Status:** open; done in waves. Wave 1 (poses, weapons, Combo orbs) shipped in v1.3.0. The 4th-job wave (every bot
class checked in code against the client's own rules, branch `bot-4th-job-skills`) is in test-1.4.1-rc1; what still
needs a player's eyes is listed under "Checked in code" below.

## Where it stands (2026-10-19 owner play-test)

- **Looks right:**
  - 3rd-job Hermit, F/P mage, I/L mage and Priest;
  - 4th-job Hero, Dawn Warrior and Night Walker (after the pose/weapon fix);
  - Crusader (after the combo-orb fix).
- **Looks bland:** Buccaneer (thunder54) and Aran (zakumninja). Their attacks draw but look plain.
  - **Buccaneer:** uses MapleRoot's Octopunch, Dragon Strike and Power Unity. Energy Charge, Barrage and Demolition are gone from MapleRoot, and the transformations aren't modelled.
  - **Aran:** only Final Blow at 4th job. The combo-gated finishers (Smash, Fenrir, Tempest) are left out, and there is no Aran combo counter.
- **Pauses:** some Aran pauses are not yet explained.

## Checked in code (2026-10-21)

No capture session was available: the test server's `attack-capture.tsv` only holds beginner double-jumps. So the client was read instead.

**What was read and built:**
- The client's rules and the player's own send paths, read from `MapleStory.exe` and written down in `docs/real-bots/client-remote-attack-rules.md`.
- `BotAttackClientValidityTest`, which builds the exact bytes every bot attack sends and checks them against the client's own data. It covers every job, weapon kind, attack, level, facing and pose (about 105,000 packets).
  - The client data is `client-attack-data.tsv` (its Skill.wz and Character.wz) and `client-attack-exe.tsv` (its exe tables), read from the test client.

**Dropped by the client before this change.** Run against test-rc5's driver, the test found these attacks dropped:

| Bot | What was dropped |
|---|---|
| Gunslinger | Invisible Shot, sent in one-handed swing poses with a gun |
| Thunder Breaker 4th job | Typhoon: 3 of its 5 swing poses (a knuckle only has stabO1 and stabO2) |
| Bowmaster | Hurricane: sent with no keydown packet, so it was drawn without its pose |
| Any bot | A melee or skill attack while holding one of 11 weapons whose client data has no afterImage or a wrong attack type. Bots of the kind could be handed these, for example 1302261, which has a claw's poses, and 1402256 (attack type 15). |

**Sent differently from a player before this change:**
- Shark Wave and Soul Blade went out as melee; the client sends them as shots, so the shark and the blade were never drawn.
- Night Walker Avenger went out as a shot; MapleRoot's client sends it as melee.
- The attack speed was always 4, not the weapon's.
- Mob and line counts did not match the skill's level.

**Changes:**
- **Every class:**
  - The pose is the one the player's client picks for the skill's route and the weapon's attack type.
  - The speed is the weapon's.
  - The counts are the level's.
  - Keydown skills send their keydown packet. Hurricane and Rapid Fire fire as a burst of shots, followed by a release.
  - Undrawable weapons are never handed out.
- **Buccaneer:** Double Blast and Energy Orb are woven in, on top of Octopunch, Dragon Strike and Power Unity.
- **Corsair:** Rapid Fire (as a burst), Eight Legs Easton and Aerial Strike are woven in.
- **Thunder Breaker:** Shark Wave goes out as a shot. Whale Rising, Shark Wave and Barrage are woven in at the 4th job.
- **Aran:**
  - The combo counter now works like the server's: it rises with every mob hit and resets after 6 s without one.
  - The finishers go out as shots and spend the combo: Combo Smash needs 10, Fenrir 30, Tempest 200.
  - The swings are Full Swing or Overswing double and triple. Final Toss and Rolling Spin are woven in.
- **Aran pauses:** no cause specific to Aran was found in the code.
  - The client drew all of the Aran's attacks before this change.
  - The 250 ms combat tick turns the 780 ms AoE cooldown into 1 s.
  - The turn and step-into-pack beats spend whole ticks without a swing.

**Needs a player's eyes.** The timing of a keydown burst, the attack cadence against the length of the pose, and whether each class looks like its players. To check:
1. Start the client with the diagnostic: `config.ini` `[debug]` `attackdiag=1`, from the `bot-attack-diag` client build.
2. Run `!botshowcase 4th` on the test server and go AFK next to the bots.
3. Read `attackdiag.log`.

## Attack timing without a capture (2026-10-21, branch `bots-attack-timing-150`)

The owner no longer has to play every job for timing. The timing is now read from the client's own data, and the
owner only spot-checks it. Details are in `docs/real-bots/client-remote-attack-rules.md`, "Attack timing".

**Derived from the client:**
- **Pose lengths:** from the client's `Character/00002000.img`, written by `tools/bot-attack-rules/action_delays.py`.
  - `bot-action-delays.tsv` holds all 161 poses that have frames.
  - `bot-skill-timing.tsv` holds all 253 client attack skills, per weapon class: 2,340 rows covering every job image from beginner to Aran 4th.
- **The attack speed MapleRoot's client sends:** a DLL hook that ignores the item's `attackSpeed`.
- **The stretch:** length x (speed + 10) / 16.

**Checked against the only capture there is.** A GM beginner sends a 1302000 sword at speed 3, and its swings follow each other 633-677 ms apart. The table says 650 ms.

**Mismatches it found (fixed behind `-Dslums.botattacktiming`, on by default):**
- **Speed byte:** bots sent the item's `attackSpeed`, which differs from MapleRoot's speed for nearly every weapon.
  - A staff went out at 8 instead of 3, a 2H sword or bow at 6 instead of 4, a claw at 4 instead of 3.
  - So the bots' poses were drawn up to about 40% slower than a player's.
- **Cadence:** a fixed 720-900 ms cooldown per route, whatever the pose. Of 229 job/skill pairs, 120 were slower than a player and 48 faster.
  - Long poses were cut off by the next swing: Thunder Breaker Barrage 2.1 s, Aran Tempest 2.4 s, Corsair Air Strike 2.3 s, Genesis 2.2 s, Blizzard 2.1 s, Dark Knight 1321011 1.5 s. This may be some of the "bland" and "pause" reports.
- **Walk lock:** a grinding bot could walk 500 ms into a 650+ ms swing. It now holds still until the pose ends.
- **Server vs client data:** the server's Character.wz XML differs from the client's in 17 poses. Bots use the client's.

**Still not exact (fixed below in "Attack pacing"):**
- The 250 ms combat ticker rounds each swing up to the next tick (650 ms becomes 750 ms).
- Keydown bursts are unchanged: 120 ms shots.
- Skill poses with negative frames count them at full length. This is read from the data and has not been seen on a client.

## Attack pacing (2026-10-22, branch `bots-attack-pacing-150`, for 1.5.0)

Details are in `docs/real-bots/client-remote-attack-rules.md`, "Attack timing".

**Swing at the exact moment.**
- A bot that swings on an observed map arms a one-shot tick (`BotSwingClock`) for when its swing ends. The swing after it goes out at that moment, not on the next 250 ms tick.
- While that tick is armed and the swing is still playing, the shared ticker skips the bot. Off-screen bots keep the coarse tick.
- The walk lock (still until the pose ends) is unchanged.
- The cadence is the pose rounded up to the client's 30 ms update step, read from the exe: the client's logic advances 30 ms per step. A 650 ms swing comes every 660 ms, the middle of the capture's 655-662 ms.
- Rollback: `-Dslums.botswingclock=false`.

**CPU, measured by `BotSwingPacingPerfTest`.** The test puts 40 grinders on one observed map, each with its real attack's cadence (240-1320 ms).

| | Coarse 250 ms ticker | Ticker + swing clock |
|---|---|---|
| Combat ticks/s | 160 | 61 |
| Swings/s | 54.4 | 60.9 |
| Swing late after its pose ends | 80 ms average, 240 max | 0 |
| Swing to swing, against a player's | 1.12x | 1.00x |

- A combat tick is the unit of combat work: the grind brain, the target scan and the attack call. The mid-pose ticks that did nothing are gone, so there are 62% fewer of them, though bots swing 12% more often.
- The clock's own cost on a real 10-thread pool with an empty tick body: 6.0 ms of CPU per second, against 1.9 ms for the ticker alone. That is about 0.1 ms per second per grinder.
- Due ticks land 0.1 ms after the pose ends.

**Keydown streams.**
- The client shoots a held Hurricane, WA Hurricane or Rapid Fire on the first 30 ms step that is more than 100 ms after its last shot. That is every 120 ms, from the first step after the keydown packet; nothing waits for `prepare/time`. The 120 ms shot interval was already right.
- The pacing was wrong:
  - Bots shot a fixed 6, then stood for the length of the keydown pose on top of it.
  - Now they hold while a mob is in reach (at most 25 shots, 3 s), release, and may press again at once.
- Bots use no release-type keydown skill (Monster Magnet, Big Bang, Piercing Arrow, Corkscrew, Grenade, Poison Bomb, the Evan breaths). Their client-side reuse delays are written down for whoever adds one.

**Flame Barrier 12121012 and Inferno Aura 2121054.**
- The data is right: both are cast in `alert2`, the client's 270 ms pose, which is 219 ms with a staff or wand. Neither has a cooltime in the server's or the client's Skill.wz, and MapleRoot's DLL adds no delay.
- A player holding the key casts them every 240 ms, and bots now do the same (250 ms before, on the ticker).
- They will look fast, because a player's would too. If they look wrong next to a real player, the next step is a capture, not a guess.

**Owner spot-check, updated (about 12 min).**
1. **Setup.** Use `[debug] attackdiag=1`. On a map with mobs, run `!botshowcase pacing 12` (or `!qa run bk-1 pacing 12` for the log).
   - It spawns Bowmaster, Corsair, F/P, Blaze Wizard, Bishop, Hero, Aran and Thunder Breaker.
   - Each bot's chat line lists its attacks with the cadence a player gets ("every 660 ms (pose 650 ms)", "held, a shot every 120 ms").
2. **Swing speed, 3 min.** Hero (2H sword), Bishop (staff) and Bowmaster should swing as fast as your own character with the same weapon, with no hitch every few swings.
3. **Keydown, 3 min.** Hurricane and Rapid Fire should be one keydown pose with a steady stream of shots, held while mobs are in reach (up to 3 s), then a release. There should be no pause of about half a second between bursts.
4. **Quick spells, 2 min.** Inferno Aura and Flame Barrier every 240 ms. Do they look like a player spamming them?
5. **Long poses and grinding, 2 min.** Thunder Breaker Barrage and Aran Tempest play to the end. On a training map, bots do not walk mid-swing.
6. **Afterwards.** The bk-1 log's `gap` field shows the time between a bot's swings. It should match the cadence on the chat line (±5 ms). `attackdiag.log` should list no drops.

**Automated client check: not done.**
- The owner's desktop has the owner's console session active, so a client launched there would take over their screen.
- The v83 client has no auto-login, so logging in needs synthetic keys and clicks into a DX8 window. That is not reliable, and it is intrusive.
- The capture-free path would need a client debug option `[debug] autologin=` (client repo). A Wine + Xvfb client on the build host would also need a 23 GB Data copy. Neither is in this branch.

**Owner spot-check (about 12 min, test server once the branch is on it):**
1. **Setup, 2 min.** Use a client with `[debug] attackdiag=1`. On a map with mobs, run `!botshowcase 4th 10`. Each attack now goes out 3 times back to back at a player's pace, then pauses.
2. **Long poses, 4 min.** Watch Thunder Breaker Barrage, Aran Tempest, Corsair Air Strike, Bishop Genesis, I/L Blizzard and Dark Knight. Each pose should play to its end before the next starts, with no idle gap between the 3 repeats.
3. **Swing speed, 2 min.** Bishop or F/P (staff), Bowmaster (bow) and Hero (2H sword) should swing as fast as your own character with the same weapon.
4. **Quick poses, 2 min.** Check Aran's double and triple swings, Blaze Wizard 12121012 and F/P 2121054 (a 219 ms `alert2` pose), and Dawn Warrior 11121101. Do they machine-gun?
5. **Grinding, 2 min.** On any bot training map, bots should not slide or walk while a swing is still playing.
6. Afterwards, `attackdiag.log` should list no drops.

Rollback without a code change: `-Dslums.botattacktiming=false` (needs a restart).

## Method: copy a real player

Guessing a class's attacks from skill data produced every bug so far. The reliable source is a real client.

1. **Capture mode** (test server only): log every attack packet a GM character sends. Record skill id, level, body action, stance or direction, attack speed, weapon, projectile, and display/charge fields.
2. **Owner session per class group:** the owner makes a test character of the class (GM job change plus a skill grant) and plays a few minutes the way players do.
3. **Copy it:** set the bots' attack profile from the capture, meaning which skills, in what order, and the exact packet fields.
4. **Check on a real client** next to the bot, as in the client remote-attack rule (bots must send a pose and weapon the skill allows).

## Waves

1. Pirates (Buccaneer, Corsair, Thunder Breaker) and Aran, the two reported as bland.
2. Warriors (Paladin, Dark Knight, Dawn Warrior combos).
3. Mages and archers, including Blaze Wizard and Wind Archer.
4. Thieves, then the custom hyper skills for all classes.
5. Later: rebirth 5th-tier skills (ticket 30).

## Done when

For each class group, the owner can't tell a bot's attacks from a player's.
