# 120: Display and performance toggles

**Area:** client
**Effort:** S each
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro research (owner request 2026-10-28), Z:\MSv83\SlumsMS-dev\research-kuro\NOTES.md, section "Their Windows client". Ideas only: build it our way on slums.dll and our server; never copy their binaries, scripts or custom WZ assets.

## What

HUD window switches that make busy maps lighter (also helps the Parsec/low-end case):
hide map background / tiles / objects; opacity for skill effects, pets and summons (own vs others); hide other
players' cash effects; cap how many other players' animated items animate at once. Saved per PC like the HUD.

## Done when

Each toggle applies live without a map change; FPS measured on a crowded FM channel before/after; defaults are stock.
