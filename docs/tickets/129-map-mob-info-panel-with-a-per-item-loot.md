# 129: Map "Mob Info" panel with a per-item loot filter

**Area:** client + server (NEEDS SERVER)
**Rank:** 1 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** one window lists every monster in the **current map** (level, HP, elemental weak/strong), and clicking
  one shows its drops. Each drop has a toggle: **ignore this item for auto-loot/pet pickup**, plus IGNORE ALL / ENABLE ALL.
  Mesos can't be ignored. No more pets filling the bag with junk Etc.
- **Evidence:** "List the monsters of your current map", "Loading map monsters...", "Filter targets...", "Strong/resists",
  "Lightning / Poison / Darkness", "No target selected. Drop intel will appear here once you choose a mob.", "IGNORE ALL",
  "ENABLE ALL", "Click to DISABLE auto-loot", "Mesos auto-loot cannot be disabled", "Sending TOGGLE_IGNORE_ITEM (0x92)",
  "Received IGNORE_LIST_UPDATE (0x92)". Widget combat tab icons `pickOn/pickOff` ("Auto Pickup", "Pick ON/OFF").
- **How:** ImGui window. The map mob list and drop list come from the server (we already have the Monster Book drop
  data). The ignore list is **stored and enforced on the server** in the pet/auto-loot pickup handler, and synced back to
  the client so the toggles show their state. A global "pickup on/off" switch goes in the HUD.
- **Size for us:** M. Client: one ImGui window that reuses Monster Book search and drop rendering. Server: a per-character
  ignore set (DB table), checked in pet pickup, plus two packets. Data: none.
