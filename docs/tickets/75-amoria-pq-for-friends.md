# Ticket 75: Amoria PQ playable for a friends server (1.5.2)

Status: open, owner decided 2026-10-25 ("loosen that"). Bug-squash arc, 1.5.2.

## Why

Amoria PQ works as Cosmic ships it (event `scripts/event/AmoriaPQ.js`, NPCs 9201042-9201050, maps 670010000-670011000,
all identical to Cosmic, checked 2026-10-25), but it needs exactly 6 players, all married, of both genders
(`minPlayers = maxPlayers = 6`, `onlyMarriedPlayers = true`). With 5-15 friends that never happens.

## Do

1. Read every APQ stage (event script, NPC, portal `gendergo`/`apqDoor`/`stageBogo`, reactor scripts) and list what
   assumes 6 players or both genders (gender-split doors, platform/rope puzzles, per-player item counts).
2. Loosen the entry: 2-6 players, marriage not required (owner). Make each stage completable at the low end: scale
   counts by party size, and let a gender door accept a party of one gender. Bots in the party count only if they can
   actually do the stage's job (otherwise they don't count toward the minimum).
3. Rewards: keep Cosmic's (ticket 68 restored PQ reward amounts); no new rewards without the owner.
4. Tests for the stage maths (2, 3, 6 players; one gender) and an auto-QA or `!qa` staging to run a 2-player APQ on TEST.

## Done when

A 2-player same-gender party can finish APQ on TEST start to end, and a 6-player mixed party still can.
