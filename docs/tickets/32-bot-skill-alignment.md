# 32: Align bot attacks with real players, in waves

**Area:** Real bots
**Depends on:** the bot attack fixes on `persistence-on-v120` (poses, weapons, combo orbs)
**Status:** open. The client's rules are now checked in code for every bot class (branch `bot-4th-job-skills`, 2026-10-21). What still needs a player's eyes is listed under "Checked in code" below.

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
