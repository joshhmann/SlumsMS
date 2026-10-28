# 146: Theoretical DPM calculator ("Analytical DPM")

**Area:** client + server (NEEDS SERVER)
**Rank:** 18 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** pick a learned skill and see its **modelled damage per minute** from your real stats, crit, booster,
  Speed Infusion and dice, plus cast cadence and effective targets.
- **Evidence:** "Analytical DPM", "Choose a learned skill, then run the server model.", "Snapshot sent; waiting for model
  response", "Attack: WATK %d MATK %d Weapon %s", "Crit: %d%% chance / %d%% damage", "+ Spell Booster", "+ Dice",
  "Cadence: %.2f casts/min  Effective targets: %d/%d", "1-minute DPM / %d targets".
- **How:** the client sends a stat snapshot and skill id, and the server runs the same damage formula it uses for
  validation and returns DPM. That is a nice way to reuse server code.
- **Size:** M-L. A theorycrafting toy. Do it after ticket 118.
