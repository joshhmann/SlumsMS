# Cash Shop catalogue tools

`cashshop/catalog.tsv` is what the Cash Shop window sells (loader `CashShopCatalog`, purchase
`CashShopWindowPurchase` + `CashShopServices`). It is **generated**: do not edit it by hand.

| File | What it is |
|---|---|
| `build_catalog.py` | Rebuilds `catalog.tsv` from stock `Etc/Commodity.img`, every other cash item with server WZ data, and `overrides.yaml`. Deterministic. |
| `lint_catalog.py` | Checks a catalogue against the WZ, the window and the rules. Exits 1 on any error. |
| `overrides.yaml` | The human-edited part: prices, hiding, names, categories, featured, owner flags. Always wins. |
| `catalog_rules.py` | Facts about the server and the window: categories, exclusions, gender, name rules. |
| `catalog_header.txt` | The comment block at the top of the generated file. |
| `wzindex.py` | Reads the server WZ XML. Caches its index in `~/.cache/slumsms-cashshop/` (rebuilt when the WZ changes). |

Python 3 with PyYAML. The WZ is the server's XML dump: `--wz DIR`, `$WZ_PATH`,
`/root/work/bake/wz` or `./wz`, in that order.

## Workflow

```sh
$EDITOR tools/cashshop/overrides.yaml
python3 tools/cashshop/build_catalog.py           # writes cashshop/catalog.tsv, prints counts/prices
python3 tools/cashshop/lint_catalog.py --fresh    # 0 errors, and the file matches a fresh build
./mvnw -q test -Dtest='CashShop*Test'             # the real loader accepts every row
git add tools/cashshop/overrides.yaml cashshop/catalog.tsv && git commit
```

On a running server, `!reloadcashshop` (GM 3) re-reads the file without a restart. A missing
or unreadable file keeps the old catalogue. Open windows keep their category list until they
are reopened.

`build_catalog.py --report` lists every excluded id by reason. `--check` exits 1 when the file
on disk is stale.

## What gets sold

- **Stock rows**: every on-sale `Commodity.img` item at its stock price and bundle. When there
  are several serials, the biggest bundle wins, then the lowest serial. Pins in `overrides.yaml`
  keep the prices that were live before the tool. Only rate coupons keep a stock period;
  everything else is permanent (period 0).
- **Everything else with data**: newer and off-sale cash equips (`info/cash` = int 1), pets,
  501 effects, star skins, weathers, chairs (301), mounts and saddles (190/191), and the few
  newer 5xx items that have a working handler. They are priced by **band**: the median stock
  price of the same item type (`id // 10000`), otherwise of the shop category, otherwise
  `prices.band_defaults` or `prices.default`. Prices are rounded to `round_to`. An off-sale
  stock commodity keeps its own price when that price is between half and three times its band.
  `tier:` scales a band price by a factor from `tiers`.
- **Gender**: worked out from the item id. For an equip, the thousands digit 0 means male and
  1 means female; anything else is both. This is the client's rule and the server refusal's.
- **Names**: the String.wz name, cut to 40 characters. These names are rejected: blank,
  placeholder, Korean or other non-Latin; for non-stock rows also `GM ...`, `Test` and names
  with underscores.
- **Order**: by tab/category, then featured, then new, then the rest, then item id. File order
  within a category is the order the window shows.

### Never sold (rules in `catalog_rules.py`; the header of the generated file lists the stock rows they remove)

- Anything without WZ data, an icon, or stand/default frames. Exceptions: rings, pet utility
  equips and "Transparent" items.
- Equips that are not cash-flagged. A `cash` flag stored as a string counts as not cash-flagged.
  This matches what cashshop-fixes dropped.
- **Rings that need a partner ring record**: every ring in `Effect.wz/ItemEff`, which covers
  crush, friendship, wedding and couple rings. They stay out until the window has a partner flow.
- **Packages** (9xxxxxx, until unpacking exists) and storage +8. The service rows
  5430000/9111000-9114000 are kept.
- Items with no working handler:
  - Surprise, Name Change, Transfer
  - Pet Snack, Store Remote
  - 528 gas/scent
  - 519 pet skills
  - Master Keys, Sandglass, DS Egg, pendant slot
  - Cheap/Heart/Skull megaphones
  - newer pet skill equips 1812008+
- Newer variants of types that are sold only from stock (rate coupons, gacha, wedding...).
  Beauty coupons that no NPC script reads.
- Flag-gated, off by default (`flags:`):
  - mesobags 5200000-2 (`sell_mesobags`)
  - Hyper Teleport Rock 5590001 (`sell_hyper_teleport_rock`)

To sell one of these anyway, add `hide: false` for it in `overrides.yaml`. Overrides win.

The Coloring Prism (5782000, addon-colorprism) is not in stock v83 data. Its row is built from a WZ
that has had `tools/colorprism/install_wz.py` applied; lint it with `--wz` pointing at such a WZ, or
add the item to the default WZ the same way. A server WZ without it refuses the purchase
(`BUY_UNKNOWN_ITEM`) without charging.

### Categories the window does not have yet

The client's `kCats` (DLL `src/cashshopwnd.cpp`) has no Chair, Mount or Pendant/Belt/Medal
category. Those rows are **pending groups**: `pending_categories` in `overrides.yaml` sets the
existing tab/category they sit in for now (chairs 5/2 Game, mounts 5/6 Character, accessories
2/9 Ring). The header of the generated file says how many rows each group has. When the DLL
gains the category:

1. Add the new `{tab, sub, name}` to `kCats` (client).
2. Point the group's `target` at it.
3. Rebuild.

Nothing else moves. A whole item type can also be moved with `categories:`.

## Featured / New

The window has no New tab. Featured rows lead their category, then New rows, then the rest.
There are three ways to set them:

- Per item in `overrides.yaml`: `featured: true` or `new: true`.
- A shortlist file in `featured_files`, relative to this folder. The first column is the item
  id; any other column that says `new` marks the id New instead of Featured. Lines starting
  with `#` and non-numeric lines are ignored. The curated shortlist (`cs-curation/shortlist.tsv`)
  is copied here as `featured.tsv` and listed under `featured_files`.
- `build_catalog.py --featured FILE` for a one-off build.

An override in `items` beats the shortlist.

## Lint checks

- The Java loader's own rules: 8 columns, numeric fields, ranges, count 1 for equips, pets and
  services, no packages.
- No duplicate ids.
- WZ data, icon, frames and the cash flag.
- Names: present, at most 40 characters and 64 bytes, in the client charset.
- Gender matches the id rule.
- Price is above 0, and equal to the override when one is set.
- No never-sold, ring-record, flag-gated or hidden ids.
- Every `(tab, category)` is in the window's `kCats`. The list is read from
  `/root/work/client/src/src/cashshopwnd.cpp` or `--client-cpp` when available; otherwise the
  built-in copy is used.
- Per-category counts are within the client guard (65536). The server sends 400 per packet.

`CashShopCatalogShippedTest` runs the real loader over the shipped file and fails on any
rejected row.
