# 118: EXP tracker and DPS meter (HUD modules)

**Area:** client (+ small server for party ranks)
**Effort:** S-M
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro research (owner request 2026-10-28), Z:\MSv83\SlumsMS-dev\research-kuro\NOTES.md, section "Their Windows client". Ideas only: build it our way on slums.dll and our server; never copy their binaries, scripts or custom WZ assets.

## What

Two new modules for the 1.6.0 ImGui HUD (overlay.cpp g_widgets, next to HP/MP/EXP), movable and resizable in Edit UI:
- **EXP tracker:** EXP per hour over a rolling window (5/15/60 min), time to next level, EXP gained this session.
  Client only: read the EXP stat the client already holds each tick.
- **DPS meter:** your damage per second from the damage numbers the client already receives (own attacks), a session
  total, and the top skills. Party/expedition ranks need a small server packet with each member's damage on the
  current boss (the server already sums damage per attacker for drops).

## Done when

Both modules show in game, survive a resolution change and Edit UI, and cost < 0.2 ms per frame. Party ranks on a TEST boss with two characters.
