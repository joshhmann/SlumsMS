# Track D discovery: admin dashboard, economy data, website, Hidden Street, Basil Market

This was read-only. I edited, committed, deployed and restarted nothing. I did not read anything on live: no live DB, live logs or live folder.

What I did read: both repos, TEST files and logs on 164, SELECT counts on the TEST DB (`mapleroot_persist`), the nginx site files on 164, and the public site over curl.

One small probe: I copied two client `.img` files from the RC base layer into my scratchpad and decoded their icons. That worked.

---

## 1. The admin dashboard today (ticket 35)

### What it is
- **Server exporter.** `soloMapling.dashboard.DashboardExporter` is started from `Server.java:1023` and is off unless `-Dsolomapling.dashboard=true` is set.
  - It writes `live.json` every 60 s, plus `series/<day>.json`, `daily/<day>.json` and `days.json`, using atomic renames (`AtomicFiles`).
  - The JSON covers:
    - bots: online, roster, target, director, by level/job/map/activity;
    - economy: `DerivedEconomy`, an estimate built from bot meso deltas, not real counters;
    - health: heap, CPU, tick, DB pool, `BotDbGate`;
    - chat tier stats, and `PlayerWealthIndex.snapshot()`.
  - Nothing reads back from these files, and there are no config.yaml keys.
- **Map export.** `DashboardMapExport` is a separate JVM job. It reads the WZ files and the nav cache and writes `nav/names.json`, `index.json`, `maps/<id>.json` (footholds, ropes, portals, stuck spots), `graphs/*.json` and `learning*.json`. `NavDashboardData` supplies the graph and learning overlays.
- **Web app.** `tools/dashboard/web` is static, with no build step. Tabs: Map, Learning, Bots, Economy, Health, GM audit, plus a monitor status strip.
- **Extras.** `tools/dashboard/extras/slums-dashboard-extras` runs from a systemd timer every minute. It copies the monitor's `status.json` and, every 5 min, writes `gm.json` from `slumsms-gm-audit` via `docker exec`.
- **Deploy files.** `deploy/dashboard/` has the nginx site (`:8091`, basic auth, `/data/` alias), the map-export service and an hourly timer, the extras units, and `extras-{dashboard-test,live}.conf`.

### Where it runs on 164
| Piece | State |
|---|---|
| nginx `slums-dashboard-test` on `:8091` | Up. Basic auth with its own htpasswd (not printed). Plain HTTP on the LAN. |
| Web files `/srv/slums-dashboard-test/web` | **Stale.** Copied Oct 20. `app.js` and `index.html` differ from release-1.6.0 (`charts.js`, `mapview.js` and `style.css` match). |
| Data served at `/data/` | Comes from the **`slums-dashboard-test` server**: a separate 2 GB-heap JVM with `-Dsolomapling.enabled=false` (bots off) and a jar from Oct 20. So the page shows **0 bots, 0 mesos, no chat, no wealth index**. |
| The real TEST server (`slums-persistence-test`, about 2,259 bots) | Has the exporter on, but writes to `/opt/mapleroot-dev/soak-rc150e/dashboard`, a leftover soak folder that **nginx does not serve**. That data is good: 2,259 online, 733m mesos today, chat and wealth index present. |
| Map export | Ran once on Oct 20 (385 maps). **No map-export timer is installed**, so learning and stuck overlays are 6 days old. |
| Extras timer `slums-dashboard-extras@dashboard-test` | Runs. The status strip works. GM audit shows the error "apply migration 009" on `slums_release_test`. |
| Live | **Nothing installed.** The live java line has no dashboard flag, and there is no live nginx site. Caddy is on the owner's side, not on 164. |

### What works, and what's stale or broken
- **Works:**
  - the exporter itself (tested, low priority, never throws into the game);
  - the map viewer, using the Oct 20 data;
  - the status strip and the auth gate.
- **Stale or broken:**
  - The test install is wired to the wrong server. Fix: point `persistence-test`'s `-Dsolomapling.dashboard.dir` at `/srv/slums-dashboard-test/data`, then stop the bots-off `slums-dashboard-test` JVM. That frees 2 GB on the shared host.
  - The web copy is stale.
  - The map-export timer was never installed.
  - Migration 009 is missing on `slums_release_test`.
- **Not connected:**
  - The dashboard's Economy tab still uses the derived estimate. It does not read the new 1.6.0 `EconomyCounters` or `data/econ/*.json`; the `EconomyFeed` adapter exists but nothing feeds it.
  - "Shop and trade flows" were never built.

### What the "live, read-only, behind auth" decision needs
Each step needs the owner's go:
1. A 1.6.0 jar on live, with `-Dsolomapling.dashboard=true -Dsolomapling.dashboard.dir=/srv/slums-dashboard/data` added to the live java line. Takes effect on the next restart.
2. A **new port.** `8091` is taken by the test dashboard, so use 8094, for example, or retire the test site.
3. A new htpasswd, separate from the patch site's.
4. The map-export and extras units installed for live.
5. Migration 009 on live, for the GM page.
6. A Caddy hostname for TLS. Until then it is HTTP basic auth on the LAN only.
7. Optionally, an Economy tab that reads `latest-private.json` (it allows character ids; it is not public).

The app already has no write actions.

---

## 2. Economy data

### 1.6.0 only (release-1.6.0; not in 1.5.5 / `155-dojo`)
- **`EconomyCounters`.** In-memory `LongAdder`s; recording an event does no DB write. They count:
  - sources: mob meso drops (human / bot), NPC sales, quest mesos;
  - sinks: NPC buys, Job Master fees, bot upkeep, FM tax;
  - transfers: trades, FM sales;
  - Maple Leaf gained/spent, NX earned/spent.
  - Hooks are in `Character`, `Shop`, `Trade`, `MesoAction`, `JobMasterFees`, `CashShop`, `NPCConversationManager`, `AbstractPlayerInteraction`, `MesoSinks`, `BotWealth`, `BotHoldings`, `BotBagRoom` and `TrainingBot`.
  - **Persisted (owner 2026-10-26):** migration 032 `economycounters`, one row per (UTC day, counter key). Today's rows are added back at boot, the totals are written every 5 min (`-Dslums.econ.counterSaveSeconds`) on the economy-snapshot thread and at shutdown, and the nightly snapshot writes the closed day's final totals. Rows older than 90 days are deleted.
  - Script meso changes (`cm`/`qm`/`im.gainMeso`, quest meso actions): a gain is a quest reward (source), a loss is `npc_fee` (sink, `flows.sinks.npcFees`). The Job Master skip fee stays in `jobMasterFees` only.
  - I found no hook in `BotShopping` (bot NPC pot buys). Worth verifying.
- **`EconomySnapshotService`.**
  - Runs nightly at UTC midnight and labels the file with the day just ended. `!econstats` (GM 4) writes `ondemand-*` files without resetting the counters.
  - Output folder: `-Dslums.econ.dir`, default `Server/data/econ`.
    - `<date>-public.json` and `latest-public.json` (about 116 KB): meso supply by level band (humans vs bots), wealth stats (mean, median, p25/p75, top 1% / 10% share, Gini), flows, and `itemPrices` (count, median, p25/p75, min, max per item).
    - `<date>-private.json` adds `topHolders` (25 rows: id, level, meso, isBot).
    - Files are mode 600.
  - Dated `<date>-public/private.json` files are kept 90 days (owner 2026-10-26), pruned at each nightly. The website's 365-day price history (`PublicMarketExport` `prices/<day>.json`) is separate.
  - Data comes from `characters` (world 1, gm<2), `botstore`, the active hired merchants and player shops (bot shops are `HiredMerchantArtificial`, so they are included), and PriceBook's recent samples.
- **`PlayerWealthIndex`.** The median of the ratio "player mesos ÷ bot meso ceiling for that level" across players. It scales the bot ceiling between 1x and 10x, is kept in `bot-wealth-index.properties`, and logs `[Wealth]`.

### Already on live, since 1.5.0
- **PriceBook / `@price`.** Every shop, merchant, trade, bot-shop and bot-seed sale leaves a sample in DB table `pricesamples` (migration 020): item, unit price, normalised price, quantity, source, player side, time.
  - **No parties are stored**, which makes it ideal for a public "recent sales" feed.
  - The median uses a 7-day window. Rows are pruned after `keepDays: 14`, so **real history is capped at 14 days** unless we add rollups.
  - It has anti-pumping rules, and uses player-only samples once there are 5 or more.
- **`EconomyMeters`.** Daily meters in `economyday` (botbuy, gifts, tax, godlies, botshopsales, sinkshop).
- **`BotWealth`.** The meso ceiling per bot, with an upkeep burn.
- **Trade log.** `logs/trades.log` holds raw trade-window lines with character names. Only for audits; never public.
- **FM listings.** These live in memory only (`World.getActiveMerchants()` and `getActivePlayerShops()`). `fredstorage` is empty on TEST. A public listing page needs a server-side exporter.

### On TEST now (counts only)
- `data/econ`: one nightly pair for 2026-10-25, public and private.
  - Bots: 10,156 rows in `botstore`, holding about 73.5bn mesos, Gini 0.46. Humans: 19 rows (mostly QA characters), about 1m mesos.
  - Flows for the day: bot mob drops 1.17bn, upkeep 443m, FM tax 12.9m, FM sales 429m. Every human flow is 0.
  - `itemPrices`: 1,073 items.
- `pricesamples`: **14,335 rows over 899 items, Oct 22 to Oct 26. 14,334 of them are `bot_seed`**, so TEST prices are almost entirely bot-made.
- Game DB tables: `drop_data` 22,225 rows (1,006 droppers), `drop_data_global` 6, `reactordrops` 1,106, `shops` 110, `shopitems` 3,879.

---

## 3. The website today, and the owner's question: do Hidden Street and the site go together?

### How it's built
- Source: the `site` branch, worktree `/root/work/SlumsMS-site`.
- `tools/site/build.py` is about 440 lines of standard-library Python. A single `layout()` wraps every page; navigation is Home, Patch notes, Guide, Report, Play.
  - Release pages are rendered from a small markdown dialect in `docs/site/releases/<v>.md`.
  - The guide is a restyled copy of the player wiki: `docs/wiki/*.html`, made by `docs/wiki/build.py` from hand-written `src/` pages plus generated pages. `leveling.html` comes from `LevelingPath.yaml`, `travel.html` from `BotTransit.yaml`, and names from `data/names.json`.
  - On top of that copy, `guide-fixes.json` applies exact find/replace fixes, and the build stops if a fix no longer matches. Whole pages can be replaced from `guide-overrides/*.html`.
- `check.py` fails the build on:
  - forbidden strings, including **"164"**, "192.168.", "/srv/" and "test server";
  - **any http(s) URL**, so no CDNs or remote fonts;
  - broken links.
- It is deployed with `rsync -a --delete dist/site/ → /srv/slums-site`. nginx serves it on `:8092` and the owner's Caddy fronts it.
- **Report form.** `report_service.py` listens on 127.0.0.1:8093 behind `/api/report`.
  - Each report is appended as one JSON line to `/var/lib/slums-feedback/reports.jsonl`. The IP is stored only as a salted hash, there is a honeypot field, and limits are 5/hour and 20/day per IP, 200/day overall.
  - `report_export.py` pulls new reports.
  - The public `/api/report/health` returns 403.
- **Style.** `site.css` is about 15.6 KB of tokens, light plus `prefers-color-scheme` dark, with **no manual toggle**.
  - Light is warm cream with maple orange; dark is navy.
  - System and `ui-rounded` fonts, 14 px corner radius, pill buttons, hero and tiles, 16 px/1.6 body text, a 1,120 px wide container.

### Visiting the live site (all 200, fast, nginx)
- Home is a friendly hero ("Bera is busy, and it remembers you"), a latest-patch card, three tiles and an "at a glance" box.
- Patch notes are short and warm ("See you on the top floor~ 🍁").
- The guide has 14 pages with a left nav; shops and leveling already have tables.
- The report form is clean, with a no-JS fallback.
- `/hidden-street/` and `/market/` return 404.

The voice is chatty, plain and small-world. The look is soft and modern-friendly, built for prose rather than data.

### Verdict on structure: (a) same site, shared nav and theme, with a dense "data" layout for the database and market sections
Dense Hidden Street tables dropped into the *current* rounded-card style would feel bolted on (16 px/1.6 text, pills, 14 px radius). But the owner is overhauling the theme anyway, so design one theme with two densities from the start:
- **"read" density** for home, patch notes, guides and the report form;
- **"data" density** for `/database/` (Hidden Street) and `/market/` (Basil): 13–14 px text, tight rows, slot-icon first column, sticky headers.

Why not the other options:
- **(b), a sub-site with its own style**, is what you'd get if the current theme stayed. With a site-wide overhaul it adds a second style to maintain.
- **(c), a separate subdomain**, costs a new Caddy host and a second nav and build, and splits a five-to-fifteen-friend audience across two links.

Under (a), friends keep one link, one checker/build pipeline and one header, and items cross-link with guides for free.

### The existing guide pages stay as friendly guides
- They are the "Guides" section of the new nav. The original Hidden Street had a Guides section too.
- Their tables should link into the database: leveling.html map and mob rows to map and mob pages, shops.html items to item pages.
- Later, `leveling.html` and `travel.html` could read from the same dataset.
- Proposed nav: Home · News (patch notes) · Guides · Database (Items, Monsters, Maps, NPCs, Quests) · Market (Prices, FM now, Recent sales, Economy) · Report · Play.

### What can be reused
- **Hidden Street dataset generator.** `tools/hiddenstreet/build_data.py` (commit 190f9a66f, from branch `152-hidden-street`) is **already in release-1.6.0**, with tests and a sweep report. It reads WZ XML plus DB TSVs and writes `items/mobs/maps/npcs/quests.json` with cross-links (drops, shops, quest rewards, spawn maps, portals).
- **Icons work.** `/root/work/client/src/tools/wz/native_wz.py` reads v83 `.img` files and decodes canvas formats 1, 2, 513 and so on.
  - The client ships an **unpacked `Data/` tree** (about 85,865 `.img` files), not packed `.wz` archives, so icons can be read per file.
  - I decoded the Red Potion icon (`Data/Item/Consume/0200.img` → `02000000/info/icon`) and the Snail (`Data/Mob/0100100.img` → `stand/0`) to PNG from the **RC base layer** `/srv/slums-patch/launcher-rc/layers/base/v83-2026.10.19/files/Data/…`. That is the RC channel, which agents may use.
  - It needs pycryptodome and Pillow; both are present on 187.
- **Map pages.** `DashboardMapExport`'s `maps/<id>.json` (footholds, ropes, portals) could draw a small SVG map on Hidden Street map pages.
- **Mob placement.** `MapMobIndex` and the dataset's spawn lists.
- **Name tables.** `docs/wiki/data/names.json`.

---

## 4. Hidden Street data sources
| Data | Source | Notes |
|---|---|---|
| Items, mobs, maps, NPCs, quests | The server's `wz/` XML (471 MB on TEST: Item, Character, Mob, Map, Npc, Quest, String, …) | XML has canvas sizes but **no pixels** |
| Drops | `drop_data` and `drop_data_global` (TEST DB); `reactordrops` | Drop changes reach the DB through migrations, so TEST matches the release |
| Shops | `shops` and `shopitems` (DB); plus Hanako and other custom prices already in the guide | |
| Gachapon / cash shop | `server/gachapon/*` (Java); `cashshop/catalog.tsv` | Later |
| Icons, mob/NPC sprites, minimaps | Client `Data/*.img` from the RC base layer (Item: 947 files, 571 MB; Mob: 10.3 GB, but only `stand/0` is needed) | Use a recorded layer version so builds are reproducible |

**Size, from my count on the existing dataset.** It has 60,398 items, 11,024 mobs, 23,052 maps, 18,501 NPCs and 2,912 quests, with duplicate rows and 2,264 unnamed items. The "reachable" v1 set is:
- about **5,321 items** (named and dropped, sold or rewarded);
- **1,973 mobs** placed (1,000 with drops);
- **4,893 maps** with mobs or NPCs;
- **3,692 NPCs** placed;
- **2,905 quests**.

That is about 19k pages, roughly 100–150 MB, plus about 11k icons at 20–30 MB. Everything else goes only into the search index.

**Known gap.** Per HANDOFF, the server's `wz/` has 9,528 fewer NPCs and 3,456 fewer mobs than the client ships (Big Bang-era maps).

---

## 5. Plan

### Architecture
```
server (1.6.0 jar, on 164)                              164 timers (idle priority)                         /srv/slums-site
  EconomySnapshotService  -> data/econ/latest-public.json
  NEW PublicMarketExport  -> data/public/listings.json   (every 15 min)
                          -> data/public/sales.json      (pricesamples, >=1 h delay, no parties)
                          -> data/public/prices/<day>.json (nightly daily rollup: OHLC, median, count)
                                              \--> slums-site-data.timer (15 min): validate (schema + privacy
                                                   lint) -> build.py --section market -> staging dir -> atomic
                                                   swap of /srv/slums-site/market/
release time (on 187): tools/hiddenstreet/build_data.py (wz XML + TEST DB TSVs) + icons.py (RC client Data/*.img)
  -> build.py --section database -> rsync (must EXCLUDE market/, since today's rsync uses --delete)
```
- Everything stays static. Charts are **inline SVG generated at build time** and coloured with CSS variables, so they follow light and dark mode with no JS library (check.py bans CDNs).
- Search is one small same-origin JS file plus a compact `search-index.json`.
- Fonts are self-hosted woff2 under OFL.

### What the server must export
| File | How often | Contents |
|---|---|---|
| `listings.json` | every 15 min | Each live FM shop item: item id, quantity, price per unit, channel and room, seller kind (bot or player), shop title and **seller name only for bots** |
| `sales.json` | every 15 min | The last 500 `pricesamples`: item, unit price, quantity, source (shop, merchant, trade, bot), player side, time rounded to the hour. **No names or ids** |
| `prices/<day>.json` | nightly | Per item: open, high, low, close, median, sample count, share of samples with a player side. Kept as files for 365 days. This works around `keepDays: 14` without an economy config change |
| `latest-public.json` | nightly (exists) | Public economy numbers, after the privacy floor below |

### Privacy rules (proposed defaults)
- **Bots: names are fine.** Real players: **no character names anywhere** on the market pages. Shops show as "a player's shop · ch2". The exception is a player's own `@basil` posts in 1.6.x (ticket 79 already says posts show character names, so that is opt-in).
- **Human aggregates only when a bucket has at least 5 people.** With 5–15 players, a level band with count 1 reveals one person's mesos; the current public file already exposes that. Publish:
  - bot statistics and total supply;
  - "players hold X% of all mesos";
  - flows as daily totals.
  - Drop human Gini, top 1% share and per-band rows from the public page.
- **Prices:** show a median only with at least 3 samples. Label prices "mostly bot-made" when the player share is under 50% (on TEST it is about 0%).
- **Delay:** sales are delayed at least 1 h and rounded, so a sale can't be matched to who was online.
- **Never public:** `trades.log`, `latest-private.json`, account or IP data.
- Add a privacy lint to `check.py` for these.

### 1.6.0 vs later
- **1.6.0:**
  - the theme overhaul with a light/dark toggle;
  - Database v1 (reachable entities, search, icons, cross-links);
  - Market v1, read-only: price index with up to 30 days of history, FM listings now, recent sales, a public economy panel. Built on TEST behind auth and only public once 1.6.0 is live;
  - fixing the dashboard's TEST wiring, the dashboard on live (owner go), and an Economy tab that reads the snapshots.
- **1.6.x and later:**
  - `@basil` sell/buy posts and bot posts (ticket 79);
  - candles and history beyond 30 days, item stat tooltips on listings;
  - dashboard alerts, the balancing report after two weeks of data, gachapon and cash pages, the Big Bang WZ re-extraction.

### Scaffolding steps, in order
| # | Step | Size |
|---|---|---|
| 0 | Owner picks a style direction and answers the open questions | – |
| 1 | Theme: `tokens.css` with designed light and dark palettes, a `prefers-color-scheme` default, a manual toggle (stored, with try/catch; works without JS), self-hosted fonts, read/data densities; restyle home, patch notes, guide and report | M (1–2 days) |
| 2 | `build.py` split into `sections/` (notes, guide, database, market); scope check.py's "164" and number rules to prose pages, or use word-boundary matching, so item ids and levels pass | S |
| 3 | Clean the dataset: dedupe, reachability filter, name fallbacks; a sweep report per release | S–M |
| 4 | `icons.py`: native_wz over RC `Data/Item`, `Mob` and `Npc` `.img` files to per-id PNGs (or sprite sheets) plus a manifest; pin the layer version | M |
| 5 | Database pages: list pages with filters, entity pages, breadcrumbs, search index, guide cross-links | M–L |
| 6 | Server `PublicMarketExport` plus nightly price rollups, with tests and a `!qa` scenario | M |
| 7 | Market pages plus the 164 timer, atomic swap, privacy lint, and an rsync exclude | M |
| 8 | Public economy panel built from `latest-public.json` with the privacy floor | S |
| 9 | Dashboard: TEST wiring fix, map-export timer, Economy tab reads `data/econ`; then the live install (owner go) | S–M |

### Risks
- **`rsync --delete` from 187 would wipe the market pages generated on 164.** It needs an exclude, or the whole site build moves to 164.
- **check.py's "164" ban** will fail on item ids and levels. Its URL ban means fonts and libraries must be local.
- **Privacy at tiny player counts** (see above). The current `latest-public.json` is not safe to publish as it is.
- **TEST prices are about 100% bot seeds.** The market shows bot opinion until players trade. History is capped at 14 days without rollups. Counters are lost on restart.
- **The server's WZ is short of Big Bang NPCs and mobs,** so the database will be incomplete there.
- **Publishing:** a timer that updates a public page automatically is an outward-facing path and needs the owner's go. Market data from live needs 1.6.0 on live; drop tables from live would need a live DB go, so use TEST, which carries the same migrations.
- **Load on shared 164:** builds and icon extraction run at idle priority and only once per release. The stale bots-off dashboard JVM should go.
- **Look vs access:** a period look (small Verdana, low-contrast blue bars) has to pass contrast in both modes.

### Style directions (site-wide, light and dark both designed, tokens defined once)

Both directions share:
- `prefers-color-scheme` sets the default, and a toggle in the header overrides it (`[data-theme]`).
- Pixel icons render at 1x or 2x integer scale with `image-rendering: pixelated`, on an **inventory-slot tile** so the dark-outlined sprites read on either background.
- Charts are build-time SVG using `var(--chart-*)`. Up and down use shape as well as colour (hollow vs filled candles) for colour-blind readers.

**A: Bold modern ("Megaphone")**
- **Mood:** a confident current product site, its own identity, editorial and loud.
- **Type:** Bricolage Grotesque for display, Inter for text, JetBrains Mono with tabular numbers for prices.
- **Palette:**
  - Light: bone paper `#F6F3EC`, ink `#15120F`, electric maple `#FF5A1F`, ultramarine `#3D4BFF`, lime up `#9BD600`, coral down `#E5484D`.
  - Dark: designed separately rather than inverted. `#0D0E12` / `#16181E`, text `#F1ECE3`, maple `#FF7A3D`, periwinkle `#8A93FF`.
- **Tables:** 14 px, 36 px rows, hairline rules, no zebra stripes, hover highlight, sortable headers, sticky icon and name column on phones.
- **Signature:** a thin **megaphone rail** under the header, scrolling the latest sales and FM shouts like an in-game megaphone. It respects reduced motion.
- **Database and Market:** bento-grid item pages with a big icon, a 30-day price area chart and stat chips; the market is a bold ticker table.
- **Patch notes:** a huge version number as a headline, chip tags.
- **Launcher:** the tokens port straight to the Tauri UI.

**B: Modern craft, 2009 Maple ("UI Window")** — **recommended**
- **Mood:** hiddenstreet.net and basilmarket.com nostalgia, rebuilt properly: responsive (the old 800 px is a max width, not fixed), accessible, crisp.
- **Type:** headings in Pixelify Sans (OFL, self-hosted) **only on window title bars**; body in a Verdana / Tahoma / DejaVu Sans stack (period-correct and on every Windows PC); numbers tabular.
- **Palette:**
  - Light: page `#E8EEF6`, cream panels `#FFF9E8`, Hidden Street blue title bars `#5B7DB4`→`#3E5E94`, Basil orange `#EE8622` for market bars, borders `#9FB3CF`, text `#1E2430`, links `#1F4FA0`.
  - Dark ("Ellinia at night"): page `#0F1626`, panels `#1A2336` and `#222D44`, bars `#2E4A7A`→`#213860`, Basil `#FF9E4A`, text `#E6ECF5`, borders `#34425F`, links `#8FB6FF`.
- **Tables:** 13 px, 26 px rows, zebra stripes, a 32 px slot icon in the first column, "Drops:" / "Found at:" link lists, breadcrumbs. On phones the table scrolls inside its frame, with the icon and name pinned.
- **Signature:** every panel is a **v83 UI window** (bevelled frame, title bar). Hovering or focusing an item shows the **in-game item tooltip**: a dark translucent box with white stats, and requirements in red when unmet. It looks the same in both modes, which is true to the game.
- **Database and Market:** Hidden Street windows in blue; Basil Market windows in orange, as a forum-style board with S>/B> tags and a price-check page with a candle chart in a window.
- **Patch notes:** a "Notice" window like the in-game event board, `[New]` / `[Fix]` labels.
- **Launcher:** the same window frames suit the launcher.
- **Why recommended:** for a friends' v83 server, nostalgia is the pitch. The owner's tickets 78 and 79 already asked for this look; B keeps it while meeting the new light/dark and responsive requirements. Dense tables are native to it.

**Middle option:** A's layout and type, with B's slot icons and in-game tooltip as the only period touches.

**Tension to flag:** tickets 78 and 79 said "fixed 800 px, no dark mode required". B honours their *look* through materials (frames, bars, slots, Verdana), not a fixed width, and adds a designed dark mode.

---

## 6. Open questions for the owner (each changes the build)
1. **Style:** A, B (recommended) or the middle option? Should a new visitor default to the system light/dark setting (proposed) or to light?
2. **Player names on the Market:** keep the default of no player names (shops show as "a player's shop", trades anonymous), or show names for player shops?
3. **Before 1.6.0 is live,** keep the Market private (TEST data behind the dashboard's auth), or show TEST data publicly marked as a preview?
4. **Where the timed site build runs:** on 164 (recommended; needs a site checkout there and an rsync exclude), or on 187?
5. **Price history:** OK to keep a 365-day daily rollup in files (no change to `keepDays`)? And decide counter persistence plus 90-day snapshots, which is already pending.
6. **Database scope for v1:**
   - Re-extract the server WZ first, to close the Big Bang gap?
   - Include MapleRoot custom content (Arcane River, Grandis) and cash items?
   - Show custom NPC prices before ticket 89's review?
7. **Live dashboard:** which port and Caddy hostname? And may the bots-off `slums-dashboard-test` JVM be stopped, with TEST's dashboard pointed at the real test server?

Files I made (scratchpad only, not in any repo): the icon probe `/tmp/claude-0/-root-SoloMapling/10510037-ad22-4e82-9350-105aa68cb653/scratchpad/scripts/icon_probe.py`, and its output `/tmp/claude-0/-root-SoloMapling/10510037-ad22-4e82-9350-105aa68cb653/scratchpad/out/item2000000.png` and `/tmp/claude-0/-root-SoloMapling/10510037-ad22-4e82-9350-105aa68cb653/scratchpad/out/mob100100.png`.

## Addendum (owner, 2026-10-26): NX rate
MapleStory Classic players watch the NX:meso rate (meowdb's NX:Meso Indicator). Market v1 gets an "NX rate" headline:
mesos per 1,000 NX, today plus a 30-day sparkline plus daily history.
- Server export: in the nightly rollup, `nxRate` = the median over that day's sales of (unit meso price ÷ the item's
  Cash Shop NX price, from cashshop/catalog.tsv), using cash items with a fixed NX price plus NX-code items. Sample
  count included; published only with at least 5 samples. Bot-made share flagged, as for prices.

---

## Market export contract (server → site), schema 1

Written by `soloMapling.Economy.market.PublicMarketExport` (1.6.0, branch `160-market`). It matches the shape the
site session built the Market pages against; the deviations are listed at the end.

### Switches, place, cadence
- **Off by default.** `-Dslums.market.export=true` turns it on (TEST will; live only after 1.6.0 and the owner's go).
- **Directory:** `-Dslums.market.dir`, default `Server/data/market` (or `data/market` when there is no `./Server`).
- **Writes:** atomic rename, mode 644, one minimum-priority daemon thread (`market-export`). Nothing throws into the
  game. A failed file is logged at most every 15 min and does not stop the other files.
- **Cadence:**
  - every 15 min (first run 90 s after boot): `listings.json`, `sales.json`, `nx-rate.json`, `economy-public.json`;
    it also back-fills any missing `prices/<day>.json` for the last 13 days that had sales, and rewrites
    `prices/index.json` when it did;
  - nightly at 00:05 UTC: `prices/<yesterday>.json` (always written, even with no sales), pruning of day files older
    than 365 days, `prices/index.json`, `nx-rate.json`.
- `!qa mk-1` forces a full export and checks every file (auto-QA `t-mk-1`).
- **Every file** has `"schema": 1` and never has a `"sample"` key. Times are UTC ISO-8601 (`2026-10-26T07:15:00.123Z`);
  dates are UTC `YYYY-MM-DD`. Prices are mesos (integers). Shares are 0..1, rounded to 3 decimals (`players_share`: 6, since bots hold almost everything).
- **Item names** come from the server's WZ strings; `""` when the item has none.

### `listings.json` (every 15 min)
Every item on sale in an open Free Market shop right now: hired merchants and player shops, bot shops included.
Seller IGNs are shown for bots and players alike (owner, 2026-10-26).

| Field | Type | Meaning |
|---|---|---|
| `schema` | int | 1 |
| `generated_at` | string | when the file was made |
| `shops`, `bot_shops`, `player_shops` | int | shops with at least one listing |
| `count` | int | rows in `listings` |
| `listings[]` | array | sorted by `item_id`, then `price` |
| ` .item_id` | int | |
| ` .name` | string | extra |
| ` .qty` | int | units per bundle |
| ` .bundles` | int | bundles left (extra) |
| ` .price` | int | mesos per unit: `bundle_price / qty`, floored. A star or bullet stack is one unit (as on the price board), so its `price` is the stack's price |
| ` .bundle_price` | int | mesos per bundle (extra) |
| ` .channel` | int | |
| ` .map_id` | int | (extra) |
| ` .room` | int or null | FM room 1..22 (maps 910000001..910000022); null outside the FM rooms |
| ` .shop_title` | string | the shop's sign |
| ` .seller_name` | string | the owner's IGN |
| ` .seller_kind` | "bot" or "player" | |
| ` .shop_type` | "merchant" or "player_shop" | (extra) |

### `sales.json` (every 15 min)
The newest 500 price-board samples (table `pricesamples`), newest first.

| Field | Type | Meaning |
|---|---|---|
| `schema`, `generated_at`, `count` | int / string / int | |
| `sales[].item_id` | int | |
| `sales[].name` | string | extra |
| `sales[].price` | int | mesos per unit as paid |
| `sales[].qty` | int | units in the sale |
| `sales[].source` | string | `shop`, `merchant`, `trade`, `bot_shop`, `bot_trade`, `bot_seed` (a bot buying from a bot shop) |
| `sales[].player_side` | bool | a real player was one side |
| `sales[].hour` | string | the start of the UTC hour of the sale (`2026-10-26T07:00:00Z`) |

No party names: `pricesamples` has never stored them (only in-memory account ids, for the anti-pumping rules), and
no new capture was added.

### `prices/<YYYY-MM-DD>.json` (nightly, kept 365 days) and `prices/index.json`
One UTC day of samples rolled up per item. `pricesamples` keeps 14 days; these files are the 365-day history.

| Field | Type | Meaning |
|---|---|---|
| `schema`, `date`, `generated_at` | | |
| `samples`, `item_count` | int | samples that day, items with samples |
| `items` | object | keyed by item id as a string |
| ` .item_id`, ` .name` | int, string | (extras) |
| ` .open`, ` .close` | int | the day's first and last sale (mesos per unit) |
| ` .high`, ` .low` | int | |
| ` .median` | int | median per-unit price; with an even count, the mean of the middle two, floored |
| ` .norm_median` | int | extra: median of the clean-equivalent price (differs from `median` for equips only) |
| ` .count` | int | samples |
| ` .units` | int | extra: units sold in all |
| ` .player_share` | number | share of samples with a real player on one side |
| `nx_rate` | object | that day's NX rate row (same fields as `nx-rate.json` `days[]`); the history is rebuilt from it |

`prices/index.json`: `{schema, generated_at, keep_days: 365, dates: ["2026-10-22", ...]}`, oldest first. A day
missing from `dates` had no file (no sales before the exporter ran).

### `nx-rate.json` (every 15 min; history updated nightly)
Mesos per 1,000 NX, like meowdb's NX:Meso indicator.

| Field | Type | Meaning |
|---|---|---|
| `schema`, `generated_at` | | |
| `min_trades` | int | 5 (extra) |
| `days[]` | array | up to 365 rows, oldest first, ending yesterday |
| ` .date` | string | UTC day |
| ` .mesos_per_1000nx` | int or null | median over the day's readings of (unit price ÷ NX per unit) × 1000, rounded; **null when `n_trades` < 5** |
| ` .n_trades` | int | readings = `n_cash_resales + n_code_trades` |
| ` .n_cash_resales` | int | sales of a Cash Shop item with a fixed price in `cashshop/catalog.tsv` (NX per unit = price ÷ count; rows priced 0 are left out) |
| ` .n_code_trades` | int | sales of NX code items: Nexon Game Card 100 (4031865, 100 NX) and 250 (4031866, 250 NX), from `ItemId.nxCardValue` |
| ` .bot_share` | number | share of readings with no real player on either side; **0 when `n_trades` is 0** |
| `today` | object | the same fields for today so far, plus `as_of` (UTC ISO), recomputed every 15 min |

Caveats: NX cards drop from every mob (`drop_data_global`) but are credited on pickup, so they almost never reach a
sale. On TEST there are no cash-item samples yet, so the rate is null until players (or bot shops) sell cash items.

### `economy-public.json` (every 15 min)

| Field | Type | Meaning |
|---|---|---|
| `schema` | int | 1 |
| `as_of` | string | when the file was made |
| `snapshot_date` | string or null | extra: the day of the nightly economy snapshot used |
| `mesos_total` | int or null | all mesos held by characters and bots (nightly snapshot); null before the first snapshot |
| `players_share` | number or null | real players' share of `mesos_total`; null when fewer than 5 players (privacy floor) or no snapshot |
| `trades_24h` | int or null | price-board samples in the last 24 h (all sources, bot seeds included); null if the DB could not be read |
| `shops_open` | int | FM shops with at least one listing now |
| `mesos_removed_7d` | int or null | sum of `flows.totalSinks` over the nightly snapshots of the 7 days before today; null when there are none |
| `bots_online_peak` | int or null | most bots online at any 15-minute run in the last 24 h (since the server started) |
| `detail` | object or null | extra: `latest-public.json` with the human-bucket floor: any human bucket (level band, wealth stats, human total) covering fewer than 5 players has its numbers set to null and `"suppressed": true`; `topHolders` is always removed; `detail.privacy = {human_bucket_floor: 5, suppressed: [paths]}`. Its inner keys keep the snapshot's camelCase |

### Example (shortened)
```json
// listings.json
{"schema":1,"generated_at":"2026-10-26T07:15:00.120Z","shops":212,"bot_shops":211,"player_shops":1,"count":2,
 "listings":[{"item_id":2000000,"name":"Red Potion","qty":100,"bundles":2,"price":50,"bundle_price":5000,
   "channel":3,"map_id":910000007,"room":7,"shop_title":"cheap pots","seller_name":"Bobbins","seller_kind":"bot",
   "shop_type":"merchant"}]}
// sales.json
{"schema":1,"generated_at":"2026-10-26T07:15:00.200Z","count":1,"sales":[{"item_id":4010001,"name":"Steel Ore",
 "price":2015,"qty":3,"source":"bot_seed","player_side":false,"hour":"2026-10-26T07:00:00Z"}]}
// prices/2026-10-25.json
{"schema":1,"date":"2026-10-25","generated_at":"2026-10-26T00:05:00.010Z","samples":4,"item_count":1,
 "items":{"2000000":{"item_id":2000000,"name":"Red Potion","open":40,"close":50,"high":90,"low":40,"median":55,
   "norm_median":55,"count":4,"units":4,"player_share":0.5}},
 "nx_rate":{"date":"2026-10-25","mesos_per_1000nx":null,"n_trades":0,"n_cash_resales":0,"n_code_trades":0,"bot_share":0.0}}
// nx-rate.json
{"schema":1,"generated_at":"2026-10-26T07:15:00.300Z","min_trades":5,
 "days":[{"date":"2026-10-25","mesos_per_1000nx":1900000,"n_trades":5,"n_cash_resales":4,"n_code_trades":1,"bot_share":0.4}],
 "today":{"date":"2026-10-26","mesos_per_1000nx":null,"n_trades":2,"n_cash_resales":2,"n_code_trades":0,
   "bot_share":0.5,"as_of":"2026-10-26T07:15:00.300Z"}}
// economy-public.json
{"schema":1,"as_of":"2026-10-26T07:15:00.400Z","snapshot_date":"2026-10-25","mesos_total":73509278408,
 "players_share":0.000014,"trades_24h":3610,"shops_open":212,"mesos_removed_7d":455507387,"bots_online_peak":2259,
 "detail":{"date":"2026-10-25","mesoSupply":{"totalMeso":73509278408,"humans":{"count":19,"totalMeso":1008320,
   "levelBands":{"31-70":{"count":null,"totalMeso":null,"suppressed":true}}}},
   "privacy":{"human_bucket_floor":5,"suppressed":["mesoSupply.humans.levelBands.31-70"]}}}
```

### Deviations from the site's draft, and why
- **Extra fields** (`name`, `bundles`, `bundle_price`, `map_id`, `shop_type`, `count`, the shop counts, `norm_median`,
  `units`, `item_id` inside price rows, `nx_rate` inside day files, `min_trades`, `snapshot_date`, `detail`): the
  site can ignore them.
- **`listings[].price` is per unit.** A bundle's price is `bundle_price`.
- **No `seller_name`-like parties in `sales.json`:** `pricesamples` stores none (see above).
- **`economy-public.json` fields can be null** (`mesos_total`, `players_share`, `trades_24h`, `mesos_removed_7d`,
  `bots_online_peak`) when there is no nightly snapshot yet, the privacy floor applies, or the DB is busy; the site
  should show "-" rather than 0.
- **`nx-rate.json` `bot_share` is 0 when `n_trades` is 0** (the agreed type is a number 0..1, never null).
