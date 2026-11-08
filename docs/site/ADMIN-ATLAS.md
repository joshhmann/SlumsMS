# The staff Atlas page (admin/atlas/)

Owner's ask, 2026-11-07. A staff-only page to browse a built content atlas (`docs/atlas/SCHEMA.md`): every quest, NPC,
item, mob, map, script, PQ and boss row, its requirements, rewards, drops, references and where the row came from. It is
for QA expected outcomes and the quest/reward audit. Branch `site-166-atlas-view`.

## The gate

`out/admin/atlas/` sits inside `admin/`, which nginx already serves only after the login service says yes (GM level 3+,
a fresh sign-in, `docs/site/LOGIN.md`). Nothing was added outside `admin/`: no nginx change, no login change, no public
link. The only way in is the Atlas chip in `ADMIN_TABS` (`tools/site/sections/admin.py`), next to Invites and Reports.
`check.py`'s rule that no public page links to `admin/` is what keeps it that way.

## Building it

```sh
python3 tools/site/build.py --private --atlas-dir /root/work/atlas-data/atlas-1.6.5
```

`--atlas-dir` takes the folder `tools/atlas/build_atlas.py` writes. It defaults to none, and then the page is still
built, saying "No atlas built for this site build", so a private build without an atlas is unchanged. The folder is
copied into `out/admin/atlas/data/<release>/` (release from `meta.json`) with a generated `search.json`:
`[kind, id, name, reach]` for every row, sorted by kind then id. Any filename in the folder that is not one of the
atlas's known ones refuses the build: a folder that is not an atlas build is a mistake, not data.

**The atlas data is not in git.** The built folder stays on disk (`/root/work/atlas-data/`), about 85 MB; the site copy
is about 12 MB. Rebuild the atlas, rebuild the site: nothing reads the atlas builders at site build time.

**The build check refuses the data as it stands.** `tools/site/check.py` scans every `.json` in the output and the WZ
paths and item names trip it: `items.json` alone holds about 6.6k `/etc/` (paths like `Item.wz/Etc/0400.img.xml`), 963
`maplestory`, 98 `nexon`, 43 `wizet`, 7 `mapleroot` and 7 `password`; `quests.json` has 513 `nexon` and 42 `password`.
The check is not to be weakened and the data is not to be masked (scrubbing would rewrite those paths and names), so a
build with `--atlas-dir` currently ends with "site check failed" and the old site is left as it was. That needs the
owner's decision before an atlas ships.

## What the page does

- **Header:** release, schema version, the server commit (short), when the atlas was built, and the row count per kind.
- **Search:** id exact, name as a case-insensitive substring, a kind filter and a reach filter; 200 hits at a time with
  Show more. It fetches `search.json` first and one kind's file only when a row of that kind is opened: `items.json` is
  50 MB and no phone should load it to read a name.
- **Kinds are named in the singular**, the way the atlas names them: a reference is `{"k":"item","id":2010007}`,
  `dangling.json` is keyed `item`, `map`, `mob`, `npc`, `quest`, and a row lives at `#item/2010007`. The data files
  and the header counts keep the plural (`items.json`, "60,167 items").
- **A row** lives at `#kind/id` (`#item/2010007`). It shows the name, the reach badge, the reach note, every other field
  and every `{"k","id"}` reference as a link to that row. A reference the atlas has no row for says "missing
  (dangling)". A `src` entry reads `path:line` (a null line reads "line unknown"); a database export reads its
  table, key and snapshot.
- **Referenced by** is a button, not a load: back references are not in the atlas, so the scan reads every kind once,
  in the browser.
- **Dangling** lists `dangling.json` by kind.
- Everything the data says goes in with `textContent`, never as HTML, so a name like `<img onerror=...>` shows as text.
  No font, CDN or other external request: the page, its script and its stylesheet load from our server only.

## The wording rule

Reach `unobtainable` is shown as **No known source**, with the note "no drop, shop, quest reward, script or cash-shop
source was found in the data; not proof it cannot be obtained". The word "impossible" never appears on the page. The
other values show as player, GM only and event. Owners approved 2026-11-07: the atlas says what the data shows, it does
not tell a player how to get an item.
