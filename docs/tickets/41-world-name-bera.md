# 41: Run the live world as Bera

**Area:** Server / Client / Ops
**Depends on:** -
**Status:** closed 2026-10-25 (done in v1.3.1, branch world-bera).

## Today

Live runs one world, id 0. The server list packet sends `GameConstants.WORLD_NAMES[0]` = "Scania", and the v83 client
draws the world-select button and icon from the world **id** (Login.img WorldSelect art), so the name alone can't make
it look like Bera.

## Options

1. **Move the world to id 1 (Bera), recommended:** configure the live world as id 1 and leave id 0 unlisted.
   - Check how Cosmic builds worlds from `config.yaml` `worlds:` (index = id) and whether an empty/hidden world 0 is
     needed or can be skipped, then make world 1 the only listed world.
   - DB migration (ledger entry, rollback): `characters.world` 0 → 1 for every character (players and bots, the bot
     template), plus every table keyed by world (storages, rankings, guilds/alliances, families, merchants/FM, bot
     tables such as `botstore`, `botroster`), and anything in the bot code that assumes world 0 (template world,
     roster, director, BotClient login).
   - Rehearse on a copy of live like the 1.3.0 upgrade; short downtime.
2. **Client-only look:** keep id 0 and overlay the world-select art for id 0 with Bera's in `Custom.wz`, plus
   `WORLD_NAMES[0]` = "Bera" on the server. Less risky, but every other place that shows the world by id (e.g. rankings
   art, some UI) still says Scania.

## Done when

The login screen shows Bera with its own icon, all characters (players and bots) are there, and nothing still refers to
Scania.

## Design (built, option 1)

No magic index numbers: a `worlds:` entry in config.yaml may name its world with **`id`** (0 Scania, 1 Bera, ... as
in `GameConstants.WORLD_NAMES`). Without it (`id: -1`, the default) an entry's id is its place in the list, so every
older config boots unchanged. Live's config becomes a single entry, `id: 1`, with Scania's old rates and 8 channels;
nothing else is created, bound or listed.

- `config.WorldConfig.id` + `config.WorldConfigs` (id of an entry, entry for an id, boot validation). The boot stops
  with a clear message on a duplicate id, an id outside 0..20, a negative id other than -1, or no worlds.
- `Server` keeps worlds by id (`worldsById`, `channels` keyed by id); `getWorlds()` is the running worlds in start
  order. `getWorld(id)` is a lookup, not a list index. New: `getWorldIdBound()` (for tables indexed by id: MapleTV,
  player-NPC ranks, rankings) and `getHomeWorldId()` (the first world started).
- Channel ports: base + 100 per **start slot** (not per id) + channel - 1, so a lone Bera keeps 7585-7592 and the
  unit's flags do not change. With the old index-based configs slot = id, so nothing moves.
- Login: the server list sends only running worlds, with their own id and name ("Bera", id 1); the preselected
  world (`LAST_CONNECTED_WORLD`) is the home world, not a hard-coded 0; recommended worlds carry their ids. A char-list
  request for a world that is not running gets status 2, as before.
- Character views per account are loaded by world id (`loadAccountCharactersViewFromDb` returns a map), not by list
  position. Rankings, player NPCs, MapleTV, `!warpworld`, the cash shop world transfer and `RankingLoginTask` use ids.
- World-0 magic numbers replaced with the home world: `BotClient` (all bots), `SoloMaplingConstants.mainChannel()`,
  `SoloMaplingUtilities.channel()/world()`, the FM merchant owners (`ArtificialFreeMarket`, whose `Character.getDefault`
  left world 0), messenger/megaphone commands, conversation and equip checks, the `LoginPasswordHandler` unstuck path
  (now searches every world). Bots take the bot client's world, not the template row's.
- Tests: `WorldConfigsTest` (9, incl. the YAML key), `ServerWorldRegistryTest` (4), `ServerlistRequestHandlerTest`
  (decodes the packets: one world, id 1, "Bera", 8 channels "Bera-1"..., end marker, preselect 1, recommended 1),
  `ChannelPortTest` (2). Full suite: 2,710 run, 0 failures, 6 skipped (2,694 at main).

Left as is: `PacketCreator.getServerList` writes a constant `1` as each channel's world byte (it always did, and the
client ignores it; it now happens to match Bera).

## Data: migration 008

`database/migrations/008-world-bera.sql` (+ `.rollback.sql`), ledger id `008-world-bera`, gated on the ledger so a
second run changes nothing, in one transaction. Tables with a world column on live (information_schema of a fresh
copy, 2026-10-20): `characters`, `storages`, `playernpcs`, `playernpcs_field`, `plife`, `worldtransfers`
(`from`/`to`). On live: 4 characters (josh x2, jimbo, the template `SMTemplate` id 6), 2 storages, the rest empty.
Everything else (items, skills, quests, buddies, guilds, alliances, families, merchants/Fredrick, Duey, notes, gifts,
cash shop, MTS) is keyed by character or account id. Bots have no `characters` row: they live in the world the server
runs, their items are keyed by character id (>= 1000000000), and `botstore`/`botroster`/`botskills` have no world.
A storage that would collide with one the account already has in world 1 stays in world 0 (none on live).

## Client

Nothing to change. SlumsMS-Client (`/root/work/client/src`, f1b64a6) has no world-0 assumption: no auto-select
(`SendCharListRequestPacket` exists but is unused), no world name strings, Discord presence says "Playing Remedy",
`config.ini` holds only host/port 8485. Channel ports stay 7585-7592, so no router or launcher change. The world
button/icon art comes from the stock v83 `UI.wz` by id (Bera = 1). **Owner's in-game check** (no client was available
to the rehearsal): the login screen shows Bera with Bera's own art.

## Rehearsal (2026-10-20, copy of live, live untouched)

Receipt: `/opt/mapleroot-dev/rehearsal-bera/receipt/` on 164. Fresh `mysqldump --single-transaction` of live into
scratch schema `mapleroot_bera_rehearsal` (test DB, 3307), copy of the live `Server/` with live `wz/` bind-mounted
read-only, transient unit `slumsms-bera-rehearsal` (login 8529, channels 8705-8712, `CPUQuota=200%`, `Nice=19`,
`MemoryMax=6G`, LLM/Decide URLs blanked). The 1.3.0 copy booted first as the "before" state (651 bots), then the
**same `deploy.sh` that live will run** ran against the copy (paths and DB command overridden by environment).

Result: **PASS**, 0 ERROR/Exception lines over 16 minutes on 1.3.1.

| Step | Time |
|---|---|
| Stop 1.3.0 with ~1,100 bots | 22.0 s |
| `deploy.sh`: pre-checks / backup (DB with locks + runtime) / 008 + ledger checks / staging | <1 s / 3 s / <1 s / <1 s |
| Start -> `is now online` | 4.1 s (all 664 boot-wave bots initialized 14 s later) |
| Stop -> online inside the script | 7.7 s, so **about 30 s of live downtime** with the stop |
| `rollback.sh` (008 rollback, restore, start -> online) | 6.1 s (+ the stop) |

- Boot: `SlumsMS v1.3.1`, `Starting world 1 (Bera)`, channels on the base ports (8705-8712 on the copy = 7585-7592 on
  live), no world 0 started; FM buildout and Henesys market waves spawned; director `boot fill: target 444, roster
  2508`; bots fighting and saving (botstore 3400 -> 3430, bot items 52,338 -> 53,727 at the 1.3.1 shutdown).
- Players: josh's two characters, jimbo's one and the template (id 6) in world 1 with level, EXP, mesos, map, job,
  items (47), equips (33) and skills per character unchanged; both storages in world 1; ledger + `008-world-bera`.
- Rollback: ledger back to 001..007, all characters world 0, 1.3.0 boots with 642 bots and 0 errors, player rows
  identical to the before snapshot. 008 and its rollback are each idempotent; 008 re-applies after a rollback.
- Compatibility: the 1.3.1 jar boots the old index-based config as `Starting world 0 (Scania)` on the same ports with
  0 errors. The 1.3.0 jar refuses the new config (`Unable to find property 'id' on class: config.WorldConfig`), which
  is why jar and config always move together.
- Copy cleaned up (schema, user, folder, bind mount); receipt kept.

## Live deploy (prepared, not run)

On 164: `/opt/mapleroot-deployments/v1.3.1-bera/` (`deploy.sh`, `rollback.sh`, `DEPLOY-NOTES.md`, `staging/` with
`SHA256SUMS`). Repo copies: `deploy/v1.3.1-bera/`. The owner runs:

```
bash /opt/mapleroot-deployments/v1.3.1-bera/deploy.sh
```

1. Pre-checks while live is still up (any failure aborts before the stop): staged hashes, the config head hash
   (`9428c6c04531e04b`, so a hand edit to the worlds block is noticed), the ledger is exactly 001,002,003,005,006,007,
   every character is in world 0, no pending world transfer; prints players online.
2. Stop `slumsms`.
3. Backup: DB with `--lock-tables`, `server-runtime.tgz` (jar, config.yaml, scripts, src/main), the unit, old jar hash.
4. Migration 008; ledger must then be the six plus `008-world-bera` and no world-0 rows may remain, else it stops and
   says to run `rollback.sh`.
5. Stage jar 1.3.1, scripts, `src/main` data, and swap the config's worlds block for `staging/config-worlds-bera.yaml`
   (everything from `server:` on is kept byte for byte). Jar and config change together.
6. Start; prints `SlumsMS v1.3.1`, `Starting world 1 (Bera)`, the 8 channel ports, `is now online`, downtime, then
   after 30 s the count of `Starting world 0` (expect 0), ERROR/Exception lines (expect 0) and characters by world.

The unit is not changed (same flags, ports and `template-id=6`). Live has no GM account yet; the owner makes one after
the deploy (any character created now is created in Bera).

Rollback: `bash /opt/mapleroot-deployments/v1.3.1-bera/rollback.sh` (stop, 008 rollback 1 -> 0, restore 1.3.0 jar,
config, scripts and src/main from the backup, start). The 1.3.0 jar cannot read the `id:` key, so jar and config always
go back together. Restoring the dump instead loses progress since the deploy.
