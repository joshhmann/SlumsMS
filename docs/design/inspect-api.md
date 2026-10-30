# Bot inspect API and kill calibration (ticket 157, items 2 and 4)

Server side of ticket 157's "live bot inspect" and "kill calibration", for the website's staff admin (GM 3+) and the
admin dashboard. Ideas from nutnnut/Cosmic dev `77e0c5bc9` (`/api/botdebug`, `/api/mapinfo`, the world map's bot dots,
`BotKillCalibration`); their server is not ported (it listened on every interface with no login, NN-020).
Code: `soloMapling.inspect.BotInspectApi`, `soloMapling.inspect.InspectViews`,
`soloMapling.ArtificialPlayer.BotAttackSystem.KillCalibration`, `client.command.commands.gm3.KillCalibCommand`.

## Switches

| Switch | Default | What it gates |
|---|---|---|
| `-Dslums.killcalib=true\|false` | follows `DebugTools`: on where `-Dslums.qa` / auto-QA runs (TEST), off by default; **1.6.2 ships it on for live** (`docs/release/1.6.2.conf`, owner 2026-10-30) | kill sampling, `calibration.json`, `!killcalib` |
| `-Dslums.inspect=true\|false` | same rule: on on TEST, off by default; **1.6.2 ships it on for live** | the inspect endpoint (it also needs the port and the secret) |
| `-Dslums.inspect.port=<n>` | unset = endpoint off everywhere | the loopback port to listen on |
| `-Dslums.inspect.secretFile=<path>` | unset = endpoint off | a file holding the shared secret (at least 16 characters; whitespace trimmed). Read once, never logged or printed. |
| `-Dslums.inspect.bind=<addr>` | `127.0.0.1` | only a loopback address is accepted (`127.x`, `::1`, `localhost`); anything else (0.0.0.0, a LAN address, a host name) keeps the endpoint **off** and logs a warning |

So the endpoint starts only when all of: port set, switch on, secret file readable and long enough, bind loopback.
On live, 1.6.2 sets `-Dslums.inspect=true`, the port and the secret file in `1.6.2.conf` UNIT_EDITS (owner 2026-10-30); the secret file must exist first (deploy steps).

## Inspect endpoint

- Listens on `127.0.0.1:<slums.inspect.port>` (two daemon threads, nothing on a game thread).
- Every request must carry the secret in the header **`X-Slums-Inspect`**; a missing or wrong secret answers
  **403** `{"ok":false,"error":"FORBIDDEN"}` before anything else is looked at. A request from a non-loopback peer
  also answers 403.
- **GET only**: any other method answers 405 `METHOD_NOT_ALLOWED`. Nothing changes game state; there are no controls.
- Unknown path or unknown id: 404 `NOT_FOUND`. A failed read: 500 `UNAVAILABLE`.
- Each path's answer is **cached for 2 s** (`Cache-Control: no-store` toward the proxy; the cache is in the server).
- Content type `application/json; charset=utf-8`. Every document has `ok` (true) and `at` (server epoch ms).
- A field the server has no data for is `null` (a bot without a brain, a system that is off). Treat every field
  except `ok`, `at` and the ids as nullable.

The site proxies it server-side only: its staff admin checks GM 3+ on its own login (SiteAuthApi's `gm_level`),
then calls this endpoint from the site host with the header. The secret never reaches a browser.

### `GET /bots/{cid}`

One bot character (roster bots and owned alts alike; real players answer 404).

| Field | Type | Meaning |
|---|---|---|
| `cid`, `name`, `level`, `job`, `jobId` | int, string, int, string, int | who |
| `kind` | string | `alt` for an owned alt, else the bot brain's kind (`training`, `social`, `townwanderer`, ...), `bot` without a brain |
| `ownedAlt` | bool | |
| `doing` | string | a training bot's macro phase (`grind`, `travel`, ...), lower case |
| `lifePhase` | string | a training bot's daily-life phase |
| `map`, `channel`, `x`, `y` | int | where it stands |
| `lod` | string | the map's simulation tier: `pinned`, `full`, `halo`, `dwell`, `coarse` |
| `tick` | object | `lastMs`, `avgMs`: this bot's macro tick cost (last, fading average), ms |
| `nav` | string | the movement planner's last decision (`off` when not on dynamic movement) |
| `grinding` | bool | marked as grinding by its brain |
| `gear` | string | the last gear pick and why (TEST only, `DebugTools`), or null |
| `bartle` | object | `leading` (type), `flipped` (learning moved it off its seed), `x`, `y`, `seedX`, `seedY`, `plasticity`, `rivalPull`, `socialPull`, `blend` (type to share), `channels` (seven life channels, archetype to bias) |
| `goals` | array | `{kind, description, pressure, targetLevel}` per goal, or null without a life brain |
| `plan` | array of string | the goals' plans and the next step, as `!botwhy` shows them |
| `memory` | object | `notesCached` and `playersCached` (notes about players in memory now; no database read), `met` (bots/players it remembers meeting) |
| `learning` | array of string | what it learned (quadrant, crossings, today's budget, fads), as `!botwhy` shows it |
| `why` | array of string | the full `!botwhy` text, when `!botwhy` is enabled on this server, else null |

### `GET /maps/{id}`

One map over every channel; 404 when it is loaded nowhere and no character stands on it. Never loads a map.

| Field | Type | Meaning |
|---|---|---|
| `map`, `lod` | int, string | |
| `players` | int | real players on it (counted, never listed) |
| `botCount` | int | bots on it |
| `bots` | array | first 300 by cid: `{cid, name, level, job, channel, x, y, nav}` |
| `mobCount` | int | live mobs over its loaded instances |
| `mobs` | array | first 200: `{id, channel, x, y, hp, maxHp}` |
| `chatLines` | int | public chat lines said there in the last three minutes (`MapChatLog`) |

### `GET /positions`

Every online bot, compact, for a world map:
`{"ok":true,"at":..,"fields":["cid","map","x","y"],"count":N,"bots":[[cid,map,x,y],...],"maps":{"<mapId>":n,...}}`.
Rows are sorted by cid; about 30 bytes a bot (3,000 bots under 100 KB).

## Kill calibration (report-only)

While a solo bot marked as grinding is on a full-simulation map (a player is there, or `!env lod pin`), each real
kill credited to it is billed against the time since its previous kill on that map and level, and set beside what
`OffscreenCombat.rate` models for the same bot and map over the same time. Sums are kept per (job, level band of 10)
and fade per kill (x0.995, a half-life of about 140 kills). A gap over 90 s, a map change or a level change starts a
new stretch without billing the idle time; party kills are skipped (the model is a solo grind). A bucket shows a ratio
after 60 s of billed time. Nothing tunes itself from it.

- `calibration.json` in the dashboard export folder (next to `live.json`), once per export (a minute), only while the
  switch is on: `{version: 1, at, enabled, reportOnly: true, rules: {bandSize, maxGapMs, minActiveMs, decayPerKill},
  totals: {killsSeen, billed, stretchStarts, skippedNotGrinding, skippedParty, bots}, buckets: [{jobId, job, band
  ("40-49"), samples, kills, activeMinutes, realKpm, modelKpm, ratio (real / modelled kills, null until enough
  time), unmodelled, lastAt}]}`, buckets with the most samples first.
- `!killcalib` (GM 3, audited like every GM command, only registered while the switch is on): the totals and the 12
  busiest buckets; `!killcalib all` for up to 40.

Reading it: a ratio near 1 means OffscreenCombat's kill rate matches what that kind of bot really does on screen; well
above 1 means the offscreen grind under-pays (EXP, mesos, drops and the site's Most hunted all follow kills), well
below means it over-pays. Real kills include the walking between packs but not breaks, as the model does.

## Dashboard: `nav/pits.json`

Written by the nav export (`DashboardMapExport`) with the other nav files, rewritten only when its content changes:
`{"version":1,"maps":{"<mapId>":[regionId,...]}}`, every region `NavPits` treats as a pit (a ground region whose top
is below the map's view bottom; the planner never routes into one from outside, but the exported graphs still carry
those edges). Only maps with a pit are listed; the region ids are those of `nav/graphs/<key>.json` (union over the
map's profiles). The Website lane's dead-end check uses it to tell already-handled pits from real traps.
