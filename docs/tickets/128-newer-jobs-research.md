# 128: Newer jobs and hyper skills: what it would take

**Area:** client + server + data
**Effort:** XL per job
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro research (owner request 2026-10-28), Z:\MSv83\SlumsMS-dev\research-kuro\NOTES.md, section "Their Windows client". Ideas only: build it our way on slums.dll and our server; never copy their binaries, scripts or custom WZ assets.

## What

Research ticket, nothing to build yet.

PlayKuro's Skill.wz (read-only copy, 2026-10-28) has jobs we don't: Mercedes (2300-2312), Phantom (2400-2412),
Shade (2500-2512), Mechanic (3500-3512, Resistance beginner 3000), Dual Blade (430-432, beginner 401), Cannoneer
(530-532, beginner 501), Pathfinder-style archer (330-332, beginner 301), and Evan beginner 2001. Their 4th-job files
also carry later-version hyper skills (e.g. Hero 112.img: 30 skills vs our 11, with x120043+ "Reinforce/Spread"
passives, Hyper Strength/Dex/Int/Luk and x121052-54 hyper actives).

**We already have our own 5th tier:** MapleRoot's client data has x13 job files (Hero → 113 "Herald", Night Lord →
413 "Void", and 213/223/233/313/323/513/523/1113-1513/2113) plus 700/710 "Super Beginner". Check what the server
supports before adding anything.

Each new job is a large project: v83 hard-codes job ids, skill-tab layout and advancement UI; new weapon types
(cane, dual bowgun, hand cannon, ...) need client support for their stances and afterimages; and every skill needs
server handling. Source data from the original game versions those jobs shipped in or from open repacks (as
MapleRoot is), not from PlayKuro's files.

## Done when

A per-job estimate (client hooks, data, server skills) and a recommendation on which, if any, to do first.
