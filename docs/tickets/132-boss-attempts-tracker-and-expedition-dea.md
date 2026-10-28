# 132: Boss attempts tracker and expedition death counter in the HUD

**Area:** client
**Rank:** 4 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** a HUD panel lists bosses with today's or this week's remaining entries (Normal/Easy Balrog, Showa
  Boss, Scarga & Targa, Zakum, Horntail, Chaos Horntail, Pink Bean, Papulatus, Von Leon, ...). During a boss run, the
  HUD counts party deaths, with an opt-in "Show death count to others".
- **Evidence:** "Boss Attempts", "##Bosses", that boss list next to it; "[ExpeditionDeathCounter]", "%d Deaths:%d",
  "Show death count to others"; "Daily reset in %s", "Weekly reset in %s".
- **How:** the server pushes the boss-log snapshot (we already have `@bosslog`) on login and after each entry. The death
  counter needs a server broadcast per death in the boss map.
- **Size:** S-M. Client: one HUD module. Server: a snapshot packet built from the existing boss log, plus a death event.
