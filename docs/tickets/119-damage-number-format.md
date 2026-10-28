# 119: Short damage numbers (K/M/B) and a size slider

**Area:** client
**Effort:** S
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro research (owner request 2026-10-28), Z:\MSv83\SlumsMS-dev\research-kuro\NOTES.md, section "Their Windows client". Ideas only: build it our way on slums.dll and our server; never copy their binaries, scripts or custom WZ assets.

## What

Big hits fill the screen. Draw 1,234,567 as 1.23M (K/M/B) and add a damage-number size slider (60-150%) in the
Ctrl+F10 HUD window. Damage skins keep working: the formatting picks digit sprites, it doesn't replace the skin.

## Done when

Numbers over 9,999 shorten; the slider applies live; damage skins still render; a config.ini switch turns it off.
