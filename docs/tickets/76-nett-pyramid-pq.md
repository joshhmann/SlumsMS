# Ticket 76: Nett's Pyramid PQ (later; leave off for now)

Status: parked, owner 2026-10-25: "leave it off for now; I don't think it's been implemented. Someone implemented it
in a Cosmic issue/PR but it wasn't merged."

State (checked 2026-10-25): maps 926010100-926023500 load since 1.5.1's mob-exp fix and match Cosmic's v83 data, but
there is no way in: Duarte (2103013) says "currently unavailable", the Dimensional Mirror entry (9010022) is commented
out, no event script exists, and `createPyramid` is only called from commented-out code. A direct GM `!warp` into an
instance map crashes the client (the map type expects Pyramid setup packets from `server/partyquest/Pyramid.java`).

Source found 2026-10-25: P0nk/Cosmic PR #221 "Added Pyramid PQ" by Silwhoon (open since 2024-02-10, CONFLICTING;
31 files, +1428/-497: new `server/partyquest/pyramid/` package, `MapPyramidInfo`, rewritten `2103013.js`, touches
Character, MapleMap, ItemRewardHandler, MonsterStats; author's open bugs: the BERSERK buff stat (Pharaoh's Blessing)
and medal-quest progress saving). nutnnut/Cosmic does not have it.

When picked up: take PR #221, review it, port it
onto our tree with tests, and enable the entry NPCs. Optional small fix any time: make `!warp` refuse the Pyramid
instance maps with a message instead of crashing the GM's client.
