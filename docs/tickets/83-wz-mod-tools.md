# 83: Set up the WZ Mod Tool Suite for client+server content (target 1.7.0)

Status: planned for 1.7.0 (owner, 2026-10-25).

## What
MadaraGameDev's SoloMapling WZ Mod Tool Suite (Builder + Manager;
https://github.com/MadaraGameDev/SoloMapling-wisteria-Wz-Mod-Tool-Suite, copies on 164 in /root/maplestory_dump:
SoloMapling-wisteria-Wz-Mod-Tool-Suite-main.zip, WzModTools-1.0.0-win-x64-with-java.zip). It bakes modern art to
v83, compiles the matching server XML (our wz/ tree), injects the client .wz, and builds Cash Shop entries for both
sides from the same numbers; every Apply rebuilds from a baseline, so it is reversible.

## Why
Content that needs client and server data changed together:
- restoring a removed skill on request (owner keeps MapleRoot's skill set, restores single skills only if asked);
- finishing Grandis / Arcane River (ticket 81): NPCs, mobs, drops consistent on both sides;
- new hair, faces, Cash Shop items.

## Plan
1. Install on the owner's desktop under Z:\MSv83\SlumsMS-dev (Manager Quickstart), `standaloneMode: true`: files
   only, the tool never touches the DB. Our fork does not have SoloMapling's ownership guard / quarantine / Record
   Keeper / Stylist hooks; port them only if we start disabling packs players own.
2. Baseline: our current server wz (MapleRoot's set, not pristine v83) and a pristine copy of our client wz
   (`clientWzBaselineDir`) for true removal. Always apply on top of that baseline.
3. Widen the four id-range gates in our server code (the guide's §8 "Id-range gates", e.g. chairs) so modded ids
   above the stock ranges work.
4. Client changes ship through the launcher (client build bump, hash-diff against the standalone client before
   publishing, per the publish rule).
5. A pilot: one small pack on TEST end to end (server XML + client .wz + launcher RC), then decide per content item.

## Notes
- /root/maplestory_dump/WZ-BUILD-PLAN.md (2026-10-18) was written for the old Buns server (server XML a superset of
  the client). On MapleRoot it is the other way round (client has ~9.5k NPC / 3.4k mob placements the server lacks,
  all post-Big-Bang): re-measure before planning.
