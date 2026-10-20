# 41: Run the live world as Bera

**Area:** Server / Client / Ops
**Depends on:** -
**Status:** open (owner request 2026-10-20: "I want the server to be Bera")

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
