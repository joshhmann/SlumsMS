# Ticket 62: save active player sessions before the bot shutdown phase

**Target:** 1.5.1

**Priority:** P1 — player persistence and shutdown ordering

**Status:** closed 2026-10-25 (done in 1.5.1, commit 4d08a0cba).

**Area:** Server / shutdown

**Related:** ticket 63 (bot durability), ticket 65 (save reporting)

## Problem and evidence

`Server.shutdownInternal()` takes the player-save worklist from `connectedClients.values()` and runs
`Client.forceDisconnect()` on it before starting the bot phase. However, `Server.registerClient()` is called by
`LoginPasswordHandler.login()` for the login connection. `Client.createLoginClient()` and
`Client.createChannelClient()` construct separate objects; `PlayerLoggedinHandler.handlePacket()` attaches the
active character to the channel client without updating that registry.

The new player-first phase therefore selects login clients rather than the active game clients. A completed
disconnect of an old login client does not establish that the active character was saved. Actual channel players
are still disconnected later through `World.shutdown()` → `Channel.shutdown()` → `PlayerStorage.disconnectAll()`,
after the bot phase. This defeats the intended player-first order and leaves those later saves outside the
player-save time budget. It does not establish that every shutdown loses data.

Source locations (method names are authoritative if line numbers move):

- `src/main/java/net/server/Server.java`: `registerClient`, `shutdownInternal` (1.5.0 lines 214, 2098).
- `src/main/java/net/server/handlers/login/LoginPasswordHandler.java`: `login` (line 237).
- `src/main/java/client/Client.java`: client factories, `forceDisconnect`, `canDisconnect`, `disconnectInternal`.
- `src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java`: character load and `c.setPlayer(player)`.
- `src/main/java/net/server/channel/Channel.java`: `shutdown`; `net/server/PlayerStorage.java`: `disconnectAll`.

## Scope

Build the shutdown save worklist from the current real-player sessions, including cash-shop/transition cases as
appropriate. Exclude shared `BotClient` and QA `SyntheticClient` objects, deduplicate sessions, and account for
disconnects already in progress: a second `forceDisconnect()` can return immediately through `canDisconnect()`.
Do not treat that return alone as proof the original save finished. Keep the shutdown bounded and leave unrelated
login behavior unchanged unless the fix requires it.

## Acceptance and regression checks

- An integration test models separate login and channel connections, then proves the active character's final
  save finishes before bot persistence begins. A helper-only ordering test is insufficient.
- Cover channel changes, cash shop, and a disconnect already in progress; each active character is accounted for.
- A timed-out or failed save is identified and not logged as successful (coordinate with ticket 65).
- Verify bounded behavior with a stalled save and with busy bot database work.
- On an isolated test server, record shutdown/save order and reconnect persistence for a dedicated test player.
  No live shutdown or live DB access is authorized by this ticket.

No implementation or runtime reproduction was performed when filing this ticket.
