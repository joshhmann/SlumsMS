# Handlers: movement and map transfer

## Purpose

Every character, monster, summon, pet and dragon in the game moves by telling the server, not by asking it: the
client sends a stream of small movement fragments and the server parses only the few fields it needs — final
position, stance, foothold — stores them, and rebroadcasts the original bytes to everyone in range
(`src/main/java/net/server/channel/handlers/AbstractMovementPacketHandler.java:167-277`). Map transfer is the second
half: entering a door or a portal asks the server to run a script or a fixed target, and the server moves the
character between `MapleMap` objects under a warp latch, consulting the map-routing table whenever the destination
is a map the server cannot load or the client crashes on
(`src/main/java/client/Character.java:1723-1762`, `src/main/java/server/maps/MapRouting.java:233-251`).

## Entry points

| Opcode | Registration | Handler | What arrives |
|---|---|---|---|
| `MOVE_PLAYER` | `src/main/java/net/PacketProcessor.java:163` | `src/main/java/net/server/channel/handlers/MovePlayerHandler.java:31` | `skip(9)`, then a fragment list |
| `MOVE_LIFE` | `src/main/java/net/PacketProcessor.java:158` | `src/main/java/net/server/channel/handlers/MoveLifeHandler.java:50` | objectid, moveid, activity nibbles, fragment list |
| `MOVE_SUMMON` | `src/main/java/net/PacketProcessor.java:197` | `src/main/java/net/server/channel/handlers/MoveSummonHandler.java:36` | oid, start pos, fragment list |
| `MOVE_PET` | `src/main/java/net/PacketProcessor.java:214` | `src/main/java/net/server/channel/handlers/MovePetHandler.java:24` | petId, a long, fragment list |
| `MOVE_DRAGON` | `src/main/java/net/PacketProcessor.java:290` | `src/main/java/net/server/channel/handlers/MoveDragonHandler.java:36` | start pos, fragment list |
| `CHANGE_MAP` | `src/main/java/net/PacketProcessor.java:157` | `src/main/java/net/server/channel/handlers/ChangeMapHandler.java:48` | died flag, target map id, portal name, wheel byte |
| `CHANGE_MAP_SPECIAL` | `src/main/java/net/PacketProcessor.java:181` | `src/main/java/net/server/channel/handlers/ChangeMapSpecialHandler.java:38` | portal name (scripted/range portals) |
| `PLAYER_MAP_TRANSFER` | `src/main/java/net/PacketProcessor.java:268` | `src/main/java/net/server/channel/handlers/PlayerMapTransitionHandler.java:42` | nothing: the client finished loading the map |

Portals themselves are built per map load: `src/main/java/server/maps/MapFactory.java:194-196` calls
`PortalFactory.makePortal(type, mapId, node)` (`src/main/java/server/maps/PortalFactory.java:37`), which fills a
`GenericPortal`/`MapPortal` from `pn`/`tn`/`tm`/`x`/`y`/`script` (`src/main/java/server/maps/PortalFactory.java:48-80`).

## How it works

1. **Player movement is parsed for position only.** `MovePlayerHandler.handlePacket` skips the 9-byte header
   (`src/main/java/net/server/channel/handlers/MovePlayerHandler.java:38`), then calls
   `AbstractMovementPacketHandler.updatePosition(p, chr, 0)` (`:41`), which records where the fragment list started
   (`:40`). A byte count below 1 throws `EmptyMovementException` and the whole handler body unwinds into an empty
   catch (`:59-60`) — a malformed or empty movement packet is dropped silently.
2. **`updatePosition` reads the fragment list three ways.** Byte count first
   (`src/main/java/net/server/channel/handlers/AbstractMovementPacketHandler.java:169-172`), then per command:
   absolute moves (0/5/17) set the position with the y offset, skip the wobble pair, read the foothold and set the
   stance (`:176-198`); relative moves (1/2/6/12/13/16/18/19/20/22) only set the stance (`:210-218`); teleport-like
   moves (3/4/7/8/9/11) skip 8 bytes and set the stance (`:220-236`); 14 skips 9 (`:237-239`); 10 reads and discards
   one byte (`:240-243`); 15 skips 12 and reports a down-jump (`:255-261`); 21 skips 3 (`:264-271`). Anything else
   logs `Unhandled Case` and throws (`:272-274`), which is caught by the caller as above.
3. **The second parser, `parseMovement`, exists for consumers that need the full fragment list.**
   `src/main/java/net/server/channel/handlers/AbstractMovementPacketHandler.java:39-151` builds
   `AbsoluteLifeMovement`/`RelativeLifeMovement`/`TeleportMovement`/`JumpDownMovement`/`ChangeEquip` objects with the
   same command table but full fields (foothold, wobble, duration, origin foothold). It throws on an empty list
   (`:147-149`). Its only callers are `MovePetHandler` (`:30`) and the `!qa mv-1` re-parse
   (`src/main/java/net/server/channel/handlers/MovePlayerHandler.java:46`).
4. **Bot learning rides on real-player movement.** In the absolute case only, and only when
   `MapleRootSolo.isEnabled()` and `BotHelpers.isVisibleRealPlayer` holds, `updatePosition` reports the step to
   `NavObservedEdges.onPlayerMove` and `PlayerHeat.onPlayerMove` inside `MapleRootSolo.runHook("movement learning")`
   (`src/main/java/net/server/channel/handlers/AbstractMovementPacketHandler.java:189-196`); command 6 reports a
   flash jump (`:215-217`), 3/4 a teleport (`:232-234`), 15 a down-jump (`observeDownJump`, `:279-285`). A GM in
   `/hide` is excluded by `isVisibleRealPlayer`, and every one of these is off in single-player-less deployments
   because `MapleRootSolo.isEnabled()` gates it.
5. **Validation is structural, nothing more.** There is no speed, distance or foothold-consistency check anywhere on
   the player movement path: `AutobanFactory` is not referenced by `MovePlayerHandler`,
   `AbstractMovementPacketHandler`, `MoveLifeHandler`, `server/movement/*`, `GenericPortal` or `MapRouting` (grep over
   those files for `Autoban` returns nothing). `DISTANCE_HACK` is only raised from the damage handler
   (`src/main/java/net/server/channel/handlers/AbstractDealDamageHandler.java:243`), `PORTAL_DISTANCE` is declared
   (`src/main/java/client/autoban/AutobanFactory.java:48`) and never raised by any caller.
6. **The map is told, then the packet is rebroadcast verbatim.** `map.movePlayer(chr, chr.getPosition())`
   (`src/main/java/net/server/channel/handlers/MovePlayerHandler.java:53`) refreshes visible objects around the new
   position and spawns objects that just came into range
   (`src/main/java/server/maps/MapleMap.java:4015-4041`). Then the original bytes are re-emitted with
   `PacketCreator.movePlayer(chrId, p, movementDataLength)` from `movementDataStart` onward — the handler seeks back
   before building it (`src/main/java/net/server/channel/handlers/MovePlayerHandler.java:51`, `:57`). A hidden GM's
   move goes only to GMs at or above their level (`:55` → `src/main/java/server/maps/MapleMap.java:4581-4583`,
   `:214-216`).
7. **Monster movement is authoritative on the monster, gated on control.** `MoveLifeHandler` returns early while a
   map transition is in flight (`src/main/java/net/server/channel/handlers/MoveLifeHandler.java:54-56`), resolves the
   object and requires a `Monster` (`:58-63`), decodes the activity byte and one int carrying skill id/level/option
   (`:68-77`). Skill activities (42..59) run `canUseSkill` and either schedule a delayed effect or apply it at once,
   collecting banished players (`:89-106`); attack activities (24..41) ask `monster.canUseAttack(castPos, isSkill)`
   and blank the activity when it says no (`:107-114`). It then gates the next movement on `hasAnySkill` and MP/HP
   (`:116-134`), and — the actual authority check — calls `monster.aggroMoveLifeUpdate(player)`, returning as soon as
   the sender is not this monster's controller (`:143-146` → `src/main/java/server/life/Monster.java:2226-2238`).
8. **Monster movement is rebroadcast from the server's position, not the client's.** `updatePosition(p, monster, -2)`
   stores the reported position (`:157`), the buffer rewinds (`:159`), `PacketCreator.moveMonster2` is broadcast
   ranged from the monster's server-side start position (`:167` →
   `src/main/java/tools/PacketCreator.java:2320`), then `map.moveMonster` applies per-map side effects — orb spawning
   on 401060100, killing bosses that leave their box, 420-440 mobs below y 500, visibility refresh
   (`src/main/java/server/maps/MapleMap.java:3997-4013`). Banished players are moved out afterwards
   (`:173-177` → `src/main/java/client/Character.java:1625`).
9. **Summon, pet and dragon movement are thin.** Each finds the owned object, calls the same `updatePosition`, rewinds
   and rebroadcasts (`src/main/java/net/server/channel/handlers/MoveSummonHandler.java:36-59`,
   `src/main/java/net/server/channel/handlers/MoveDragonHandler.java:36-55`,
   `src/main/java/net/server/channel/handlers/MovePetHandler.java:24-41`). Pet movement additionally drives pet loot:
   unless the owner is a hidden GM (`:43-45`), every non-player-drop item within 60000 units of the first fragment —
   60000×20 when the player holds `AUTOLOOT_ITEM_ID` — is picked up on the pet's slot (`:49-66`), filtered by the pet
   ignore list, `canBePickedBy`, and a hard-coded exclusion of 4036553/4036550 (`:19-21`, `:51-61`).
10. **Portal entry is a lock around a script.** `GenericPortal.enterPortal`
    (`src/main/java/server/maps/GenericPortal.java:133-178`) branches: a GM of level ≥2 bypasses scripts when the
    portal has a fixed target, else runs the script (`:140-154`); every other character runs the script when one is
    named (`:155-165`) or takes the fixed target (`:166-167`). Scripts run under a fair `ReentrantLock` per portal
    (`:48`, `:124-125`, `:145-150`), and the outcome is logged once a minute per distinct line via
    `firstInAWhile` (`:181-190`, `:192-209`, `:212-214`). When nothing moved, the client gets `enableActions`
    (`:170-171`); when a real player did move, the traversal feeds `NavPlayerLearning.onPortal` (`:172-177`).
11. **An unscripted portal teleports through the routing table.** `attemptTeleportForUnscriptedPortal` applies the
    area gate first (`:217-221`), refuses a chalkboard-holding entry into a free-market room (`:222`), asks
    `MapRouting.move(chr, target, portalName)` for a route (`:226`), returns on a stay (`:227-229`), resolves the
    destination map through `chr.getWarpMap(id, name)` (`:230`), picks the route's arrival portal, else the named one,
    else `getPortal(0)` (`:234-237`), and warps (`:238`).
12. **`CHANGE_MAP` is the client's own "I walked into a door" request.** After refusing while already transitioning
    or banned (`src/main/java/net/server/channel/handlers/ChangeMapHandler.java:51-58`) and cancelling trade
    (`:59-61`), an empty packet body means "returning from the cash shop" (`:63-67` → `:182-201`), and an open cash
    shop means a disconnect (`:69-72`). Otherwise it reads the died flag, target id, portal name, wheel byte and the
    optional GM chase position (`:75-86`).
13. **Death, GM warps and the intro-map allowlist are separate branches.** A dead character with the wheel item is
    revived at 50 HP on a nearby spawn point (`:89-98`); otherwise the event instance gets `revivePlayer`, falling
    back to `respawn(returnMap)` (`:99-107`). A GM warps to `getWarpMap(targetMapId)` and must tolerate null
    (`:109-113`). A non-GM may only take the hard-coded introduction exits — Maple Island 10000, Lith Harbour,
    Ereve/Cygnus, Aran tutorial, the 1020000 movie, the Witch Tower entrance (`:115-149`).
14. **The portal status and distance are the last checks before `enterPortal`.** `getPortalStatus() == false` sends
    `blockedMessage(1)` and stops (`:154-158`); the fitness/OLA maps reset their timers on the way out (`:160-164`);
    a portal more than `distanceSq 400000` from the character re-enables actions instead of warping (`:167-170`);
    otherwise the portal is entered (`:172`). The whole body is wrapped in a catch that prints the stack trace and
    returns (`:176-178`).
15. **`CHANGE_MAP_SPECIAL` is the scripted/range-trigger path.** It reads only the portal name, builds a refusal
    reason from portal existence, portal delay, the per-character block list, an in-flight transition and a ban
    (`src/main/java/net/server/channel/handlers/ChangeMapSpecialHandler.java:43-45`, rule at `:60-77`), logs the
    refusal as a portal line (`:46-52`), cancels trade (`:53-55`), else enters the portal (`:56`).
16. **The warp itself is one latch and one lock.** Every `changeMap` overload funnels into
    `changeMap(MapleMap, Portal)` (`src/main/java/client/Character.java:1723-1762`) or
    `changeMap(MapleMap, Point)` (`:1794-1825`) or `forceChangeMap` (`:1860-1904`). They increment
    `canWarpCounter`, take the per-character `warpLock` only for a `BotClient` (`:1852-1858`), resolve routing under
    that lock (`:1731-1732`, `:1799-1800`), skip routing for an event instance map (`:1769-1771`), let the event
    revise the target (`eventChangedMap`, `:1736`), look the map up (`:1738` → `lookupWarpMap`, `:1573-1584`), pick
    the arrival portal (`:1743-1749` → `arrivalPortal`, `:1778-1792`), then call `changeMapInternal` and set
    `canWarpMap = false` (`:1750-1751`). `releaseWarpLatch` in a `finally` decrements the counter and re-arms the
    latch at zero (`:1845-1850`) — a thrown warp used to leave it stuck forever.
17. **`changeMapInternal` is the only place map membership changes.** It re-checks `canWarpMap` (`:2148-2150`), sets
    `mapTransitioning` (`:2153`), drops chair buffs, banish data, trade and open interactions (`:2155-2158`),
    clears summons (`:2165`), starts the QA map-entry meter for a GM (`:2166`), sends the warp packet, removes the
    player from the old map and adds them to the new one (`:2167-2172`), unwinds if the player logged out in between
    (`:2173-2181`), then updates trail, achievements, party and partner (`:2182-2210`) and finally either recurses
    into a `warpAhead` target (`:2213-2218`) or recovers the event gate and sends the environment list (`:2219-2228`).
18. **The client's `PLAYER_MAP_TRANSFER` closes the transition.** `setMapTransitionComplete` clears
    `mapTransitioning` (`src/main/java/net/server/channel/handlers/PlayerMapTransitionHandler.java:44` →
    `src/main/java/client/Character.java:2235`), cancels the homing beacon buff (`:46-52`), and for a non-hidden
    character rebuilds every nearby monster's controller relationship (`:54-70`).
19. **Map ownership is a lease on a map, swept by a world task, and it is checked on damage, not on movement.**
    `claimOwnership` stores the owner, their owned map and the activity stamp and registers the map on its channel
    (`src/main/java/server/maps/MapleMap.java:4962-4974`); the channel keeps those maps in a synchronized weak set
    (`src/main/java/net/server/channel/Channel.java:89`, `:1107-1113`);
    `MapOwnershipTask` runs every 20 s over every channel (`src/main/java/net/server/world/World.java:210`,
    `src/main/java/net/server/task/MapOwnershipTask.java:31-35`), and a map idle for more than 60 s is released with
    "This lawn is now free real estate." (`src/main/java/server/maps/MapleMap.java:5014-5021`). A boss spawn also
    releases the lease (`:3353-3366`). Movement and map entry never consult ownership; only the two damage handlers do
    (`src/main/java/net/server/channel/handlers/AbstractDealDamageHandler.java:109`,
    `src/main/java/net/server/channel/handlers/SummonDamageHandler.java:103`).

## Data

| Type | Where | Shape / content |
|---|---|---|
| `LifeMovementFragment` | `src/main/java/server/movement/LifeMovementFragment.java:29` | `serialize(OutPacket)`, `getPosition()`; implemented by `AbsoluteLifeMovement`, `RelativeLifeMovement`, `TeleportMovement`, `JumpDownMovement`, `ChairMovement`, `ChangeEquip` |
| `LifeMovement` | `src/main/java/server/movement/LifeMovement.java:25-31` | adds `getNewstate()`, `getDuration()`, `getType()` |
| `AbstractLifeMovement` | `src/main/java/server/movement/AbstractLifeMovement.java:26-41` | command type, position, duration, stance; foothold and wobble pixels per second on the subclasses |
| `MapRouting.Move` | `src/main/java/server/maps/MapRouting.java:59` | record `(map, portal, reason, stay)` — the resolved destination, arrival door, table row rule, and "do not move at all" |
| `MapRouting.Route` | `src/main/java/server/maps/MapRouting.java:63` | record `(map, portal, reason)` — one loaded table row |
| Routing table | `src/main/resources/soloMapling/map-routing.tsv` | tab-separated; `# routes:` section then `# blocked_maps:`; 63 route rows and 49 blocked rows today (`:1-3`, counted from the file) |
| `GenericPortal` state | `src/main/java/server/maps/GenericPortal.java:37-48` | `name`, `target`, `position`, `targetmap`, `type` (1 teleport, 2 map, 6 door), `status`, `id`, `scriptName`, `portalState`, `scriptLock` |
| Opened-portal flag | `src/main/java/server/maps/GenericPortal.java:84-86` | `status` is what `CHANGE_MAP` reads as `getPortalStatus()` |
| Warp latch | `src/main/java/client/Character.java:180`, `:268`, `:297-299` | `mapTransitioning` (AtomicBoolean, starts true), `warpLock`, `newWarpMap`, `stuckWarpWarned`, `canWarpMap` |
| Owned maps | `src/main/java/net/server/channel/Channel.java:89` | `synchronizedSet(newSetFromMap(new WeakHashMap<>()))` — a dropped map does not keep the lease alive |
| Ownership stamp | `src/main/java/server/maps/MapleMap.java:139` | `mapOwnerLastActivityTime`, `Long.MAX_VALUE` while unowned |

## Switches

| Switch | Where | Default | Live vs TEST |
|---|---|---|---|
| `-Dslums.maps.routing` | `src/main/java/server/maps/MapRouting.java:50`, read at `:66` | `true` | live: set explicitly in `docs/release/1.6.4.conf:271` (`UNIT_EDITS`, `-Dslums.maps.routing=true`); TEST: unknown (start-test.sh is on mapletest and was not read) |
| `-Dslums.maps.routing.table` | `src/main/java/server/maps/MapRouting.java:49`, read in `load()` (`:102`) | unset → classpath `/soloMapling/map-routing.tsv` (`:48`) | live: unset; TEST: unknown |
| `USE_DEBUG_SHOW_RCVD_MVLIFE` | `src/main/java/config/ServerConfig.java:296`; used at `src/main/java/net/server/channel/handlers/MoveLifeHandler.java:161` | `false` (`config.example.yaml:203`) | value in live's `config.yaml` not read (secrets path); TEST unknown |
| `USE_MAXRANGE` | `src/main/java/config/ServerConfig.java:302`; used for movement/broadcast range at `src/main/java/server/maps/MapleMap.java:233-235` | `true` (`config.example.yaml:208`), i.e. every packet reaches everyone on the map | live/TEST unknown |
| `USE_AUTOBAN` | `src/main/java/config/ServerConfig.java:325`; `src/main/java/client/autoban/AutobanFactory.java:96`, `:109` | `false` (`config.example.yaml:230`) | irrelevant to movement: no movement path raises an autoban (see step 5) |
| `USE_AUTOBAN_LOG` | `src/main/java/config/ServerConfig.java:326`; `src/main/java/client/autoban/AutobanFactory.java:102` | `true` (`config.example.yaml:231`) | same |
| `AUTOLOOT_ITEM_ID` | `src/main/java/config/ServerConfig.java:479`; used at `src/main/java/net/server/channel/handlers/MovePetHandler.java:76` | `5590000` (`config.example.yaml:398`) — the VIP teleport rock id, so holding one turns pet movement into whole-map looting | live/TEST unknown |
| `USE_SPIKES_AVOID_BANISH` | `src/main/java/config/ServerConfig.java:515`; used at `src/main/java/client/Character.java:1626` | `false` (`config.example.yaml:434`) | live/TEST unknown |
| `USE_MAP_OWNERSHIP_SYSTEM` | `src/main/java/config/ServerConfig.java:354`; read only by `src/main/java/client/command/commands/gm0/MapOwnerClaimCommand.java:43` | `false` (`config.example.yaml:262`) | live/TEST unknown |
| bot movement recording | `src/main/java/soloMapling/ArtificialPlayer/BotMovementSystem/InPacketReader.java:273-279`; gate at `src/main/java/net/server/channel/handlers/MovePlayerHandler.java:32-33` | off; only a GM level 4 `!botmove` toggles it (`src/main/java/client/command/commands/gm4/BotMoveCommand.java:343`, `:105`) | off in both |

## Invariants

- A movement packet with fewer than one fragment throws `EmptyMovementException`, is not applied and is not
  rebroadcast (`src/main/java/net/server/channel/handlers/AbstractMovementPacketHandler.java:41-44`, `:169-172`;
  `src/main/java/net/server/channel/handlers/MovePlayerHandler.java:59-60`).
- `updatePosition` never trusts a client-reported foothold for physics; only the position and the stance survive, and
  for monsters the broadcast is ranged from the server's own `startPos`
  (`src/main/java/net/server/channel/handlers/MoveLifeHandler.java:141`, `:167`).
- A monster move is only honoured from that monster's current controller; anyone else's packet returns before any
  state change (`src/main/java/net/server/channel/handlers/MoveLifeHandler.java:143-146`).
- No movement except a monster's can change another player's map position; `CHANGE_MAP` refuses while
  `isChangingMaps()` (`src/main/java/net/server/channel/handlers/ChangeMapHandler.java:51-58`), which is cleared only
  by the client's `PLAYER_MAP_TRANSFER`.
- `changeMapInternal` is the only mutation of map membership, and it refuses to run when `canWarpMap` is false
  (`src/main/java/client/Character.java:2148-2150`); the latch is always released in a `finally`
  (`:1845-1850`, `:1753`, `:1900`).
- The routing table never sends anyone into a blocked map, and every blocked map has a fallback that loads
  (`src/main/java/server/maps/MapRouting.java:203-226`, enforced by
  `src/test/java/server/maps/MapRoutingTableTest.java:120-133`, `:135`).
- A blocked map is not loaded at all: `MapFactory.loadMapFromWz` returns null for it
  (`src/main/java/server/maps/MapManager.java:72-75`), so a script that asks for it cannot act on the wrong map.
- `MapRouting.move` messages the player exactly once per move and only when it changes the outcome
  (`src/main/java/server/maps/MapRouting.java:244-249`).
- Ownership is released by time, not by movement: 60 s of no lease refresh (`src/main/java/server/maps/MapleMap.java:5014-5021`),
  swept every 20 s (`src/main/java/net/server/world/World.java:210`).

## Origin

| File | Class | Evidence |
|---|---|---|
| `src/main/java/net/server/channel/handlers/MovePlayerHandler.java` | modified | differs from `/root/workspace/cosmicms/src/main/java/net/server/channel/handlers/MovePlayerHandler.java`; oldest commits `03d13cb21` (2026-10-18 baseline) then `4b56e34f5` (2026-10-21, `!qa mv-1`) |
| `src/main/java/net/server/channel/handlers/AbstractMovementPacketHandler.java` | modified | differs from upstream; oldest `03d13cb21`, then `1512c08a6`, `95f9bd50b`, `9df927f97` (2026-10-19/20, bot learning). Our `updatePosition` reads the foothold and skips 4 wobble bytes (`:183-184`) where upstream skipped 6 |
| `src/main/java/net/server/channel/handlers/MoveLifeHandler.java` | modified | differs from upstream; reads one activity byte plus one int (`:68-76`) and broadcasts `moveMonster2` (`:167`) where upstream read three fields and used `moveMonster`; banish uses the `(map, portal, msg)` overload (`:175`) |
| `src/main/java/net/server/channel/handlers/MovePetHandler.java` | modified | differs from upstream; the whole loot block (`:47-66`, `:75-79`) is ours — upstream ended after the broadcast (`/root/workspace/cosmicms/src/main/java/net/server/channel/handlers/MovePetHandler.java:52-53`). Only commit `03d13cb21` |
| `src/main/java/net/server/channel/handlers/MoveSummonHandler.java` | base | byte-identical to upstream |
| `src/main/java/net/server/channel/handlers/MoveDragonHandler.java` | base | byte-identical to upstream |
| `src/main/java/net/server/channel/handlers/PlayerMapTransitionHandler.java` | base | byte-identical to upstream |
| `src/main/java/net/server/channel/handlers/ChangeMapHandler.java` | modified | differs from upstream; `getWarpMap` null guards at `:110-113`, `:145-148`. Oldest `82466ebe6` (2023-08-16, cash-shop exit fix), then `03d13cb21`, `acd96c234` (2026-10-25) |
| `src/main/java/net/server/channel/handlers/ChangeMapSpecialHandler.java` | modified | differs from upstream; the `refusal` rule (`:60-77`) and the refusal log (`:46-52`) are ours. Commits `03d13cb21`, `9355a15dc` (2026-10-29) |
| `src/main/java/server/maps/GenericPortal.java` | modified | differs from upstream: script outcome logging (`:181-190`), GM script bypass (`:140-154`), routing (`:226-239`), portal learning (`:172-177`). Commits `03d13cb21`, `9df927f97`, `b1e1b6fb7` (2026-10-20/21) |
| `src/main/java/server/maps/PortalFactory.java` | modified | differs from upstream: `makePortal` takes `mapId` (`:37`) and adds the `<mapId>.<portalName>` script fallback (`:72-79`) |
| `src/main/java/server/maps/Portal.java`, `src/main/java/server/maps/MapPortal.java` | base | byte-identical to upstream |
| `src/main/java/server/maps/MapRouting.java` | custom | absent from `/root/workspace/cosmicms`; commits `6aadd9c81`, `7841609a6`, `13a5c1498` (all 2026-10-31) |
| `src/main/java/server/maps/MapleMap.java` | modified | differs from upstream (movePlayer visibility block `:4015-4041`, ownership); `tools/cosmicdiff/classifications.json` records the boss-victory broadcasts as `DELIBERATE` |
| `src/main/java/client/Character.java` | modified | differs from upstream (routing in every warp entry point, `releaseWarpLatch`); two `DELIBERATE` entries in `tools/cosmicdiff/classifications.json` cover only `pickupItem` and the EXP clamp |
| `src/main/java/net/server/task/MapOwnershipTask.java` | base | byte-identical to upstream |

## Tests and QA

| Test | Covers |
|---|---|
| `src/test/java/server/maps/MapRoutingTableTest.java` (11 tests) | the table is on the classpath (`:39`), Gollux/Rampart/Fountain Square rows (`:47-75`), no route lands in a blocked map (`:120`), every blocked map has a fallback (`:135`), the Rose Garden family (`:103-118`) |
| `src/test/java/server/maps/MapRoutingWzTest.java` (8 tests) | against real WZ: a missing image is no map (`:70`), a direct `getMap` of a blocked map is null and only the explicit fallback substitutes (`:76`), every route and fallback target loads (`:107-179`), the named doors exist in the data (`:190`, `:201`) |
| `src/test/java/server/maps/MapRoutingWarpTest.java` (8 tests) | end to end through `changeMap`: a stay leaves the player put (`:72`), a scripted warp into a missing map reroutes (`:83`), into the crash map lands on its return map (`:93`), side doors (`:100`), Rose Garden (`:107`, `:117`) |
| `src/test/java/client/ChangeMapWithoutChannelTest.java:22` | warping when the channel has no maps left does nothing and throws nothing |
| `src/test/java/scripting/portal/PortalScriptLoggingTest.java` | the log line shape (`:27`), every refusal reason (`:33`), a missing script is reported (`:44`), Rose Garden portals name scripts that exist (`:52`) |
| `src/test/java/server/maps/PortalTriggerQuietTest.java:16`, `:24` | the Marketplace range trigger refuses quietly; the same line is logged once a minute |
| `src/test/java/net/InnerPortalRegistrationTest.java:21` | the inner-portal opcode resolves to no handler |
| `src/test/java/server/maps/CrackInTimePortalsTest.java`, `LowerGrandisPortalsWzTest.java`, `ClassicFreeMarketRouteTest.java`, `AreaGatesTest.java`, `MapEntryPlacementTest.java`, `MapEntryPerfHarnessTest.java` | neighbouring portal/routing data checks and the map-entry harness |

Auto-QA ids (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java`): `t-nv-1` runs the nav probe over the Victoria town
maps and fails on `NV1 ERROR` (`:1406-1409`); `t-mp-1` is a half check — it walks Fountain Square's `in00`, a Gollux
leg and the Rose Garden's `east00` in a real client and expects the `mapRouting` probe (`:1552-1556`); `t-mv-1` is
manual and explicitly notes that a headless player sends no movement packets (`:766-767`).

`!qa` scenarios: `mv-1` (`src/main/java/soloMapling/qa/QaScenarios.java:1088`) measures how far a GM's own client
slides during an attack, fed by the re-parse in `MovePlayerHandler` through `MoveTrace.move`
(`src/main/java/soloMapling/qa/MoveTrace.java:37`, `:67`) and logged under `QaLog.Topic.SLIDE`
(`src/main/java/soloMapling/qa/QaLog.java:42`); `pe-1` (`QaScenarios.java:883`) times map entries through
`MapEntryMeter.begin`/`placed` (`src/main/java/soloMapling/qa/MapEntryMeter.java:111`, `:127`, topic `MAP_ENTRY`,
`QaLog.java:43`). Routing reroutes are logged as `MAPROUTE` under `Topic.MAP_CHANGE`
(`src/main/java/server/maps/MapRouting.java:262`, `QaLog.java:69`).

Not covered: nothing tests `MovePlayerHandler`, `MoveLifeHandler`, `AbstractMovementPacketHandler`,
`MoveSummonHandler`, `MoveDragonHandler`, `ChangeMapSpecialHandler` or `PlayerMapTransitionHandler` directly; the only
handler-level accessor in the tree is `src/test/java/net/server/channel/handlers/ChangeMapSpecialRefusalAccess.java`,
which exists for `PortalScriptLoggingTest` but asserts no handler behaviour of its own. There is no test for monster
controller gating, for the down-jump/flash-jump/teleport learning hooks, for pet auto-loot, or for the ownership
sweep. `t-mv-1` cannot be automated at all (manual by design).

## Known issues

| Ref | What |
|---|---|
| b251 (S2) | Client crash entering Rose Garden: Abandoned Garden (211080100) and the maps past it. Routing keeps the family blocked and sends every entry back to Gardener's Spot (`src/main/resources/soloMapling/map-routing.tsv`, verified by `src/test/java/server/maps/MapRoutingTableTest.java:103-118`) |
| b274 (S3) | Will's and Magnus's boss portals never send a portal-enter, so the server never sees the request (Darknell's does); classification inconclusive, ticket 171 |
| b300 (S2) | Map 272000000 "Crack in Time" traps characters; mitigated live by moving the six affected characters, no code change found in this path (the id appears only in `src/main/java/soloMapling/autoqa/Probes160.java:2146`) |
| b232 (S3) | A bot inside an event instance does not follow its leader out on warp; the instance path deliberately skips routing (`src/main/java/client/Character.java:1731-1732`, `:1769-1771`) |
| NEW-FINDING | `src/main/java/net/server/channel/handlers/MoveLifeHandler.java:181` — `inRangeInclusive` is `return !(pVal < pMin) \|\| (pVal > pMax);`, which reduces to `pVal >= pMin` — the upper bound `pMax` is dead. So `isAttack` (24..41, `:83`) is true for every activity byte >= 24 and `isSkill` (42..59, `:84`) for every byte >= 42; from 42 upward both are true at once. Copied verbatim from upstream, so it predates the fork; still wrong: a monster move is treated as an attack and as a skill at the same time |
| NEW-FINDING | `src/main/java/net/server/channel/handlers/MovePetHandler.java:49-66` — pet movement is used as an auto-loot trigger with a 60000 (1.2M with the VIP rock) unit radius and no ownership check (`isOwnershipRestricted` is only consulted from the two damage handlers), so a pet can strip a leased map's drops without ever attacking |
| NEW-FINDING | `src/main/java/net/server/channel/handlers/MovePetHandler.java:59` — `EXCLUDED_ITEM_IDS` is a hard-coded pair with the comment "change this to map later"; it is the only protection against a pet picking up quest items |
| NEW-FINDING | no anti-cheat on `MOVE_PLAYER`: `AutobanFactory.PORTAL_DISTANCE` (`src/main/java/client/autoban/AutobanFactory.java:48`) and `DISTANCE_HACK` (`:47`) exist but no movement handler calls `addPoint`/`alert`, so a client can report any position and only `MovePlayerHandler`'s silence difference is observable |
| NEW-FINDING | `src/main/java/net/server/channel/handlers/AbstractMovementPacketHandler.java:153-155` — `generateFloatMovementCommand` is an empty stub with no callers; dead API surface |
| NEW-FINDING | `src/main/java/net/server/channel/handlers/AbstractMovementPacketHandler.java:183` vs `/root/workspace/cosmicms/...:174` — our parser skips 4 bytes (wobble) then reads a foothold; upstream skipped 6. Both agree on the total stride only if the foothold is genuinely present in this client build; a wrong stride desynchronises every following fragment in the list and is silently tolerated by `parseMovement`'s `EmptyMovementException` path |
| NEW-FINDING | `src/main/java/net/server/channel/handlers/HyperTPHandler.java:46` — the VIP rock resolves its target with `getMapFactory().getMap(map_id)` and never calls `getWarpMap`/`MapRouting`, so a blocked map id that the table would reroute is instead refused as "Map is invalid." (safe, but inconsistent with every other warp path) |
| NEW-FINDING | `src/main/java/net/server/channel/handlers/ChangeMapHandler.java:176-178` — the whole body catches `Exception` and prints a stack trace, so a malformed `CHANGE_MAP` leaves the client with no `enableActions` and a player can appear frozen until the client times out |

## State

**partial.** The transport half is plainly working: `MOVE_PLAYER`/`MOVE_LIFE`/`MOVE_SUMMON`/`MOVE_PET`/`MOVE_DRAGON`
parse, store and rebroadcast, monsters are controller-gated (`src/main/java/net/server/channel/handlers/MoveLifeHandler.java:143-146`),
and the map-change latch is exception-safe (`src/main/java/client/Character.java:1845-1850`). Evidence for "working"
on the routing half is a covered test suite plus a live switch (`src/test/java/server/maps/MapRoutingWarpTest.java`,
`docs/release/1.6.4.conf:271`). It is **partial**, not working, because: (a) `isAttack`/`isSkill` are always true
(`MoveLifeHandler.java:181`), so monster skill/attack gating is decorative; (b) there is no movement anti-cheat at all
yet the autoban enums for it exist; (c) the Rose Garden crash (b251) is contained by routing, not fixed, and boss
portals (b274) still do not reach the server; (d) no test exercises any movement handler. Nothing here was run or
deployed for this review — no build, no server, no repro.

## Open questions

- Whether the client actually emits fragment commands 2, 14, 18-20 and 22 on this build, and whether the 4-byte
  wobble + 2-byte foothold layout in `updatePosition` (`AbstractMovementPacketHandler.java:183-184`) matches it; only
  a packet capture would settle the divergence from upstream's 6-byte skip.
- Whether `USE_MAXRANGE: true` (broadcasting movement, damage and spawns to the whole map) is deliberate on live or a
  leftover default; it directly sets how much movement traffic each client receives.
- When `portal.getPortalStatus() == false` matters in practice (`ChangeMapHandler.java:154-158` sends
  `blockedMessage(1)`): the flag is set false only for an event's `join00` while the event has not started
  (`src/main/java/server/maps/MapleMap.java:3031-3033`) and back to true by the event itself (`src/main/java/server/events/gm/Fitness.java:58`,
  `src/main/java/server/events/gm/Ola.java:56`, `src/main/java/server/events/gm/OxQuiz.java:95`) or by `MapMonitor`
  (`src/main/java/server/maps/MapMonitor.java:52`); whether event maps are reached through `CHANGE_MAP` often enough
  for the branch to fire was not measured.
- Whether a rented map (`USE_MAP_OWNERSHIP_SYSTEM`) is ever claimed on live: the switch has exactly one reader, the GM
  command, and the default is false — so either the feature is off or it is claimed through a path not found here.
- TEST's `start-test.sh` was not readable, so every "TEST: unknown" switch value above stays unknown; likewise live's
  `config.yaml` values were not read (secrets file).
