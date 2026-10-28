# 29: Optional Maple Island tutorial for new characters

**Area:** Gameplay
**Depends on:** -
**Status:** done in 1.6.1 (branch 161-tutor). Owner 2026-10-25: opt-in for new characters; 2026-10-28: ask at creation, yes/no.

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

## Built (1.6.1)

A new real Beginner (level 1-9, on the start map, never asked) gets a yes/no in Cody's NPC window at the first login:
`TutorialIslandMenu`. Yes warps to Mushroom Town (10000); the island's own portals lead to Southperry (2000000), where
Shanks (npc 22000, level 7, 150 mesos) ships them to Lith Harbor. No keeps the Base of Roots start. Once per character
(area info 29152); Cygnus/Aran/bots never; switch `TUTORIAL_ISLAND_PROMPT` (default on). Island quests 1000-1049 are not
hidden. Checks: auto-QA `t-ti-1`, `!qa run ti-1`. Not yet seen in a real client.
