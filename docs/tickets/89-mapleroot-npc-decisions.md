# 89: MapleRoot NPCs: owner decisions from the catalogue (1.6.0)

Source: `docs/design/mapleroot-npcs.md` (catalogue of every NPC MapleRoot added, moved or re-scripted, 2026-10-25),
section 12. Owner asked for the catalogue after QA ("a lot of the npcs are borrowed from other maps... start mapping
out mapleroots npcs ... and what they do").

Done in 1.5.2 (809c5a3b0): Jump Quest chests pay once a day; the stylist's hidden catalogs are shut.

Open for the owner, then build in 1.6.0:
1. `USE_ENABLE_CUSTOM_NPC_SCRIPT` (off on TEST and live): turn on to restore Coco, Agent E, Dalair's merge, Asia,
   T-1337, the scroll generator and ore refining in ~20 towns, or accept greet-only NPCs.
2. Nimble only stands in Base of Roots while five retired refiners point to "Nimble in the Free Market": move Nimble
   or fix the texts. Arwen's level-3000 gate makes Moon Rock, Star Rock and Black Feather uncraftable.
3. Rebirth key items (4033446/4033442/4033450) are checked, never taken: consume them or confirm untradable.
4. Boss recruiters with scripts but no placement (Von Leon, Lucid, Cygnus, Castellan/Tengu, Murgoth, Singapore
   gachapon): place or delete (ties into tickets 81/82).
5. Maple TVs: restore the seven town TVs or retire mapleTV.js.
6. CPQ: keep solo to level 200, or Cosmic's 2+ players, levels 30-50.
7. Merge donorshop.js into rooty.js; Rooty's menu skips choices 3 and 5; delete stale scripts.
8. Pink Bean exit NPC (2141002.js:29) throws without an event instance and gives a flat extra reward outside the
   room check: fix.
9. Ellin Forest drop-to-Maple-Leaf trades and the Hallowed Ground free warp: policy.
10. ~2,500 inert NPCs on post-Big-Bang maps: with tickets 81/82.
Also: Glowing Treasure (starforce, 2616.js:75-79) leaves the dialog open after a success; Dalair is two NPCs
(9000066 medals, placed in 1.5.2; 9000040 merger), the owner noted the medal one usually has its donation box nearby.

## Track: put progression back (owner, 2026-10-25)

Owner: MapleRoot's NPC shortcuts aren't abuse; they were the quickest way to hand players content without building
the requirements in between. Goal: give each shortcut NPC the real quest/progression requirement it skips, instead of
removing it. Needs digging per NPC: what stock v83 (Cosmic, MapleLegends/Royals guides) requires for the same thing
(job tests, skill-book quests, Maker levels, boss prequests, travel/quest gates for warps), then a gate or a cost
that matches. Start with the catalogue's "shortcut" flags (Cotton Warper boss warps, 4th job skills NPC, Jump Quests
warp, Hallowed Ground warp, Ellin Forest leaf trades, CPQ entry) and the Job Master (fees done in 1.5.2).

## Sources by era

`docs/design/content-eras-and-sources.md` tags every catalogue NPC and the ticket 81/82 quest chains with the MapleStory era
it comes from (stock v83, Big Bang and after, modern 2016+, or MapleRoot custom), when it was introduced, what the official
requirement was where a source states it, and the best guide links. Use it as the lookup for the "put progression back" track.

That progression track is planned for 1.7.0. Quick decisions 1-8 above stay in 1.6.0. Many requirements in the doc are marked
`[mem]` (from memory, unverified): confirm them against the guides before building a gate.

Split out 2026-10-25: quick NPC fixes are now ticket 102.
