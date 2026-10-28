# 124: WASD, mouse and controller input

**Area:** client
**Effort:** S-M
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro research (owner request 2026-10-28), Z:\MSv83\SlumsMS-dev\research-kuro\NOTES.md, section "Their Windows client". Ideas only: build it our way on slums.dll and our server; never copy their binaries, scripts or custom WZ assets.

## What

We already have movement keys (MAPLEROOT_MOVEMENT_KEYS). Add: mouse button remap (side buttons to skills) and a
controller mode (left stick moves, right stick moves the cursor, buttons map to key slots) via XInput.

## Done when

A controller plays a TEST session end to end: move, attack, loot, open windows.
