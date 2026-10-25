# Ticket 78: "Hidden Street" for SlumsMS: an item/mob/map/NPC/quest database site from our own data

Status: owner OK'd 2026-10-25 ("yep! perfect"). Built during the 1.5.x bug squash, because generating it is itself a
data sweep.

## What

Static pages on theslumsms.asslorde.com (same builder style as the patch-notes site: `tools/site/build.py`, nginx on
164 port 8092), generated from the server's own data so they never go stale:
- Items: name, icon, stats, who drops it and where (drop_data + the global drops), which NPC shops sell it and for how
  much, which quests reward it, `@price` 7-day history when there is one.
- Mobs: level, HP, EXP, maps they spawn on, drops.
- Maps: name, mobs, NPCs, portals and where they lead.
- NPCs: where they stand, what they sell, which quests they give.
- Quests: requirements, steps, rewards, NPCs (e.g. the mount path: Kenta 6002, Tatamo 6007, Kenta 6003, the Wild Hog).
- Search, and links between all of the above.

## The sweep it doubles as

The generator reports every broken reference as a build warning list: items with no name, drops or rewards pointing
at missing items, quests whose NPC/map/item doesn't exist, maps whose portals lead nowhere, mobs that fail to load,
shops selling missing items. That list feeds the 1.5.x fixes.

## Look (owner 2026-10-25): the old Hidden Street, not a modern site

Modern references for *features* only (search, filters, cross-links, price history): kerning.gg, meowdb.com. The
*look* is the original mid-2000s Hidden Street (hiddenstreet.net, ~2006-2009): fixed ~800 px centred layout, left nav
column of section links (Items, Monsters, Maps, NPCs, Quests, Guides), small Verdana/Tahoma text, blue/grey section
header bars, light bordered data tables with the item or mob icon in the first column and stats in rows, "Drops:" /
"Found at:" link lists, breadcrumbs, a plain search box at the top. Pixel icons straight from our WZ, no rounded cards,
no dark mode required (an optional dark toggle is fine). Pull reference screenshots from the Wayback Machine before
designing.

## Rules

Public: nothing private (no account names, no live player data beyond aggregated `@price`). Icons extracted from our
WZ data at build time. Keep the build reproducible from the repo plus the server's wz/.
