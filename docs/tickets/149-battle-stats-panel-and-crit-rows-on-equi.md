# 149: Battle Stats panel and crit rows on equip tooltips

**Area:** client + server (NEEDS SERVER)
**Rank:** 21 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** a small panel (with a full and a mini view) of computed combat stats: crit chance and crit damage,
  weapon/magic attack, and so on. Equip tooltips also gain crit rows.
- **Evidence:** `New.img/battleStats` (main, mini), "Crit: awaiting server snapshot", "[EquipCritTooltip] appended native
  AddInfoEx rows".
- **How:** the server sends computed totals (the client doesn't know server-side bonuses). The client renders a panel and
  adds tooltip rows.
- **Size:** M. It overlaps the crit/range addon candidate in MR-015, so do it together.
