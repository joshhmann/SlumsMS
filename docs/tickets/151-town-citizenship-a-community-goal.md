# 151: Town citizenship: a community goal

**Area:** client + server (NEEDS SERVER)
**Rank:** 23 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** sign an "Oath of Citizenship" for a town (meso fee). Your character info shows a town badge and a
  contribution gauge, and the server counts citizens toward a shared goal: "Every 100 citizens unlocks a celebration: +1
  all stat for every citizen, up to +100" ("%d / 10,000 citizens signed").
- **Evidence:** `Citizenship.img` (contract, badge 0-9), `CharacterInfo.img/Citizenship` (contribute gauge, gradeUpIcon),
  `CCitizenshipWindow`, `CCitizenshipContract`, the strings quoted.
- **How:** a server-side town membership with a fee, a contribution score and a global counter that grants a passive
  stat buff. The client shows a contract window and a badge.
- **Size:** L. Interesting social glue, but bots would inflate the counter: count real accounts only, or make bot
  citizens a deliberate part of the design.
