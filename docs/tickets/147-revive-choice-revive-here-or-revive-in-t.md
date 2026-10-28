# 147: Revive choice: "revive here" or "revive in town"

**Area:** client + server (NEEDS SERVER)
**Rank:** 19 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** on death, choose to revive **at this map** (costs something or uses a charm) or in town.
- **Evidence:** "Would you like to revive at this map?", "Would you like to revive in town?" (also listed in NOTES as
  "revive in place").
- **How:** hook the native death dialog (`CUIRevive`) to add a second button, and handle the revive-in-place request
  on the server.
- **Size:** S-M. Needs the owner's call on cost.
