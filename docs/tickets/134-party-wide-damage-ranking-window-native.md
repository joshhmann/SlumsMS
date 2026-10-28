# 134: Party-wide damage ranking window (native style)

**Area:** client + server (NEEDS SERVER)
**Rank:** 6 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** a KMS-style "Damage Rank" window during expeditions shows each member's share of damage with
  bars, a skill breakdown with icons, Reset / Switch / Auto buttons, and "Showing previous expedition results" after the
  run.
- **Evidence:** `UIWindow.img/DamageRank` (backgrndmax/min/center/bottom, gauge self/common, iconCommonAtk,
  iconUnknownSkill, BtReset/BtSwitch/BtAuto, title1/2), "Open native DamageRank", "Expedition Rankings",
  "Join an expedition to see damage rankings here.", "Rankings will appear once damage is dealt.", config
  "Enable the F12 damage rank UI's native input hooks".
- **How:** ticket 118 measures your own damage on the client. A ranking needs the **server** to total damage per
  party member per boss and broadcast it every second ("Updates every second during battle"). Kuro rebuilt the KMS
  window with WZ art. We would draw our own art or use ImGui.
- **Size:** M on top of 118. Server: per-instance damage totals and a periodic packet. Bots in parties would show up
  too, which is fun.
