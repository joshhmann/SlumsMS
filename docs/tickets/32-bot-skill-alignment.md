# 32: Align bot attacks with real players, in waves

**Area:** Real bots
**Depends on:** the bot attack fixes on `persistence-on-v120` (poses, weapons, combo orbs)
**Status:** open; done in waves, one class group at a time

## Where it stands (2026-10-19 owner play-test)

- **Looks right:**
  - 3rd-job Hermit, F/P mage, I/L mage and Priest;
  - 4th-job Hero, Dawn Warrior and Night Walker (after the pose/weapon fix);
  - Crusader (after the combo-orb fix).
- **Looks bland:** Buccaneer (thunder54) and Aran (zakumninja). Their attacks draw but look plain.
  - **Buccaneer:** uses MapleRoot's Octopunch, Dragon Strike and Power Unity. Energy Charge, Barrage and Demolition are gone from MapleRoot, and the transformations aren't modelled.
  - **Aran:** only Final Blow at 4th job. The combo-gated finishers (Smash, Fenrir, Tempest) are left out, and there is no Aran combo counter.
- **Pauses:** some Aran pauses are not yet explained.

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
