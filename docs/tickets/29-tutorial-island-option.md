# 29: Optional Maple Island tutorial for new characters

**Area:** Gameplay
**Depends on:** -
**Status:** open, target 1.6.0. Owner decision 2026-10-25: yes, opt-in for new characters.

## What

New characters start in the Arcane hub (map 993910000). Offer an opt-in tutorial:
- An NPC or sign at the Arcane spawn asks level 1–9 characters "Do Maple Island?". Yes warps them to the island's
  start; no keeps them in Arcane.
- When the island ends, the normal boat goes to Lith Harbor, or the character is sent back to Arcane (owner's choice).
- Optional: SoloMapling's TutorialBot / Maple Island bots populate the island when bots are live (ticket 16).

Check the Maple Island maps and scripts exist in the server data and the client (they are stock v83, so they should).
The NPC script goes in `scripts/npc/`, and any new NPC placement needs matching client data.

## Done when

A new character can choose the tutorial or skip it, finishes the island without errors, and ends up in the right town.

## Decision (Owner decision 2026-10-25)

Yes: an opt-in Maple Island tutorial for new characters (never forced). Target: 1.6.0.
