# 121: Plain-language crash report

**Area:** client + launcher
**Effort:** S-M
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro research (owner request 2026-10-28), Z:\MSv83\SlumsMS-dev\research-kuro\NOTES.md, section "Their Windows client". Ideas only: build it our way on slums.dll and our server; never copy their binaries, scripts or custom WZ assets.

## What

Our crash.log is mostly first-chance C++ exception noise. On a real crash, also write crash_reports/LATEST_CRASH.txt
with: what happened in words ("the client ran out of memory", "a map file is missing", "graphics driver error"),
the module and offset, build stamp, map id, and the last 50 integration.log lines. The launcher offers to send it
(the player sees it first). Also: a named error when a Custom/add-on WZ file is missing (their pattern), instead of
a silent stock fallback or a crash.

## Done when

A forced crash on TEST writes LATEST_CRASH.txt with a readable cause; nothing is sent without the player's OK.
