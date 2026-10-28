# 126: Mid-air casting for mages and archers

**Area:** client + server movement checks
**Effort:** M
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro research (owner request 2026-10-28), Z:\MSv83\SlumsMS-dev\research-kuro\NOTES.md, section "Their Windows client". Ideas only: build it our way on slums.dll and our server; never copy their binaries, scripts or custom WZ assets.

## What

Next step after attack-while-moving (client-moveattack-split, 1.5.0): let mage and archer skills fire while
jumping. Client: lift the in-air gate for those skill types. Server: accept the attack packets from the air and
keep the movement/anti-cheat checks consistent.

## Done when

Every Explorer and Cygnus mage/archer attack skill casts mid-air on TEST without a disconnect or a server warning.
