# 30: Bot rebirths and the 5th tier

**Area:** Real bots
**Depends on:** 06 (builds), the MapleRoot skill rework on `real-stats`
**Status:** open (owner to pick the approach below)

## How MapleRoot does it (for players)

- **Rebirth** (`scripts/npc/rebirth.js`, NPC 1300009, `Character.executeRebornAs`):
  - Needs max level: 200, or 255 after 3 rebirths (`getMaxClassLevel`).
  - The character restarts at level 1 as Beginner, Noblesse or Legend.
  - EXP rate is 3× after the 1st rebirth, 2× after the 2nd, 1× after the 3rd.
  - Past jobs are kept in `characters_rebirth`; their skills stay usable (the NPC keybinds them).
- **Map gates** (`HyperTPHandler`): Lion King needs rebirth 1, Twilight Perion rebirth 2, Grandis/Arcane rebirth 3.
- **5th tier** (job x13 via the Job Master NPC 9010003):
  - Needs a 4th-job character with rebirth ≥3 and **World's Engine** (4036541).
  - World's Engine comes only from quest 31167 "Magnus the Tyrant!": level 200+, after quest 31166, kill Magnus
    (8880002), then turn in at NPC 1300009.
  - The NPC takes the item and teaches the job's 4 x13 skills.
- **Super Beginner 700 / Synth Master 710:** needs Chain Attack at 120, then the 710 advance takes World's Engine.

## The problem

A rebirth sends the bot back to level 1. Bots level in real time, so reaching 200 three times takes a long time. A
bot that only earns it will reach the 5th tier after weeks or months, and only if it stays in the roster.

## Proposed approach

1. **Earned path:** a bot at its class's max level rebirths like a player does.
   - It rebirths at the NPC with the same EXP rates.
   - It keeps its past-life jobs and skills (`characters_rebirth` plus botstore), so its build and attacks can use them.
   - It then levels again, so high-rebirth bots also show up in low maps, as real MapleRoot players do.
2. **Veteran slice of the roster:** a small share of roster bots are created already rebirthed (1–3 rebirths, with
   past jobs), so rebirth-gated maps and 5th-tier bots exist from day one.
   - The share is set in config.
   - Fits the "random roster" decision.
3. **World's Engine:**
   - Veterans with rebirth 3 can start with it already used, holding their x13 skills.
   - Earned-path bots need Magnus: offscreen bots (LOD) roll a clear chance once at 200+ with rebirth 3. A real
     on-screen Magnus fight waits for a boss ticket.
4. Bots never take 700/710 unless the owner wants Super Beginner bots.

## Done when

- A bot at max level rebirths, keeps its past skills, and levels again; the change survives a restart.
- Veteran bots appear at the configured share, can use rebirth-gated maps, and use x13 skills where they have them.
- Players see nothing a real MapleRoot player couldn't have.
