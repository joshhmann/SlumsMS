# Owned alts (companions from the owner's own account)

## Purpose

An **owned alt** is a *real* character row from the *same account and world* as the player, loaded from the DB into a private headless `BotClient` and driven by the server as a companion: it follows its owner, fights the party's fight, loots, takes hits, dies, is saved back. It is **not a roster bot**: no population row, no affinity record, no bot-persistence save (`src/main/java/soloMapling/companions/OwnedAltBackend.java:39-58`). The whole feature exists so one player can be a party without a second client (`src/main/java/soloMapling/companions/OwnedAltCompanion.java:30-58`).

## Entry points

| Entry | What it does | File:line |
|---|---|---|
| `@botme` list / `@botme <name>` / `@botme here` | the only player-facing summon surface | `src/main/java/client/command/commands/gm0/BotMeCommand.java:9-40` |
| `@dismiss` / `@dismiss all` | send alts home (saved) | `src/main/java/client/command/commands/gm0/DismissCommand.java:18-48` |
| `!alts clear <id>` | drop a `FAILED` save mark without restarting | `src/main/java/client/command/commands/gm0/AltsCommand.java:54-72` |
| `!companions` (gm3) | dump every active owner/alt pair | `src/main/java/client/command/commands/gm3/CompanionsCommand.java:40-48` |
| `OwnedAltService.summon` | the one place an alt enters the world | `src/main/java/soloMapling/companions/OwnedAltService.java:197` |
| `OwnedAltService.beginLogin` / `finish` | login into a character that is out as someone's alt | `src/main/java/soloMapling/companions/OwnedAltService.java:306`, `:315` |
| `PlayerLoggedinHandler` | `beginLogin` before accept (`:150`), `ownerArrived` after (`:425`) | `src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:150,425` |
| `OwnedAltCompanion.start()` | registers the 200 ms `TimerManager` task | `src/main/java/soloMapling/companions/OwnedAltCompanion.java:132-145` |
| `Client.disconnectInternal` / channel change | `dismissAll(player,true)` `:1063-1073`; `ownerChangingChannel` `:1681` | `src/main/java/client/Client.java:1063,1681` |
| `WarpWorldCommand` | gm6 warp also sends alts home | `src/main/java/client/command/commands/gm6/WarpWorldCommand.java:53` |
| `Server` shutdown | saves alts still out in the players' phase | `src/main/java/net/server/Server.java:2164` |
| Party/buddy/messenger invite naming an offline alt | summons it, behind `OWNED_ALTS_INVITE_SUMMON_ENABLED` | `src/main/java/net/server/channel/handlers/PartyOperationHandler.java:84`, `src/main/java/soloMapling/companions/OwnedAltInvite.java:28` |
| `RingActionHandler` (proposal) | an owned alt answers its owner's own proposal, no packet | `src/main/java/net/server/channel/handlers/RingActionHandler.java:106-155` |
| `scripts/npc/9201002.js` (John, Amoria) | "Marry my alt now" branch, `OwnedAltWedding.marry` | `scripts/npc/9201002.js:200-215` |
| `FamilyAddHandler` / `Guild` invite | alt answers its owner's family/guild invite behind `-Dslums.ownedalts.acceptownerinvite` | `src/main/java/net/server/channel/handlers/FamilyAddHandler.java:78-80`, `src/main/java/net/server/guild/Guild.java:792-797` |
| `TakeDamageHandler` | `OwnedAltCombat.noteHit` records who a mob hit | `src/main/java/net/server/channel/handlers/TakeDamageHandler.java:95` |
| `World.partyChange` | `OwnedAltService.onPartyChange` | `src/main/java/net/server/world/World.java:1083` |
| `Trade` / `PartyOperationHandler` / `BuddylistModifyHandler` / `MessengerHandler` | `interactionRefusal` / `inviteRefusalByName` answer for an alt | `src/main/java/server/Trade.java:634`, `:79`, `:119`, `:90` |

Auto-QA and `!qa` entries are in **Tests and QA** below.

## How it works

### How an alt differs from a bot

| | Roster bot | Owned alt |
|---|---|---|
| Character id | ≥ `1_000_000_000` (`src/main/java/soloMapling/ArtificialPlayer/BotStore.java:71`) | ordinary DB id, `< 1_000_000_000` (enforced `src/main/java/client/Character.java:743-750`) |
| Client | shared headless `BotClient`, `getPlayer()==null` | private `BotClient` with `setPlayer(alt)` so inventory paths work (`src/main/java/soloMapling/companions/OwnedAltBackend.java:40`) |
| Identity test | `isBot()` | `isBot()` **and** `!isOwnedAlt()` (`src/main/java/client/Character.java:713-714`); `isOwnedAlt()` = `ownedAltOwner != null` (`:740`) |
| Persistence | population/roster save | `saveOwnedAltToDB` after `Trade.cancelTrade` (`src/main/java/soloMapling/companions/OwnedAltBackend.java:107-111`) |
| Roster membership | in `BotRoster` | skipped: `bot.isOwnedAlt() || bot.getId() < BotStore.BOT_ID_BASE` (`src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:337`) |
| Population inventory / affinity | yes | no (`src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/CharacterStorage.java:20-22`) |
| Exclusive login | n/a | `markOwnedAlt` refuses unless same account + world, `owner.getId()!=id`, else `IllegalArgumentException("Invalid alt identity")` (`src/main/java/client/Character.java:743-750`) |

So: **an alt is a real account's character**, loaded headless and temporarily bound to its owner. 88 `isOwnedAlt()` call sites under `src/main/java` keep it off the population paths.

### The 200 ms tick, in order

`TICK_MS = 200` (`src/main/java/soloMapling/companions/OwnedAltCompanion.java:69`). `start()` registers `OwnedAltCompanion::tickAll` once (`:132-145`, register at `:139`), catching `RuntimeException|LinkageError` so a driver failure never kills boot.

| # | Phase | Action | File:line |
|---|---|---|---|
| 1 | quiesce | `BotShutdown.isQuiesced()` → return (no tick during shutdown save) | `src/main/java/soloMapling/companions/OwnedAltCompanion.java:219-221` |
| 2 | refresh | rebuild `ownerIds`/`altIds` from `OwnedAltService.getInstance().active()`, `owners.retainAll`, prune `states` | `:222-232` |
| 3 | touch GC | `OwnedAltCombat.retainOwners(ownerIds, altIds)` — an owner whose alts went home keeps no hit memory | `:234` |
| 4 | formation | if `CompanionSwitches.orders()`, per owner `PartyBotOrders.slots(owner)` then `GCMovement.setFollowOffset(alt, CompanionStance.formation(ownerId).offsetFor(idx))` | `:237-247` |
| 5 | seat order | `driveAll`: alts already `fighting` on a map are ticked **first**, each seeing seats taken so far (audit #11/b116) | `:263-299` |
| 6 | guard | `gone` / `isOwnedAltClosing()` / `getMap()==null` → return | `:308-310` |
| 7 | death | `BotDeaths.isDead` → `readyToRespawn` ? `afterDeath` : return | `:313-319` |
| 8 | hp | `hp <= 0` → return (map stat update is coming) | `:320-322` |
| 9 | vitals | `BotVitals.regen(alt, min(elapsed, 2_000))` | `:323-325` |
| 10 | party support | `BotPartySupport.tick(alt)` (heal, buff pots — same call a population grinder makes) | `:326` |
| 11 | PQ handover | `MechanicsHold.committed(alt)` → `doing="playing the party quest"`, return | `:327-333` |
| 12 | catch-up | `catchUp`: if apart ≥ `CATCHUP_MAX_MS` 45 s, or ≥ `CATCHUP_STUCK_MS` 8 s and not travelling → `backend.bringHere` | `:335`, `:623-646` |
| 13 | gate | `OwnedAltGate.check(...)`; on non-OPEN: `standDown`, tell once per gate, `keepFollowing`, return | `:338-354` |
| 14 | reopen | gate OPEN: `openSince=0` → now; after `TOLD_RESET_MS` 5 s re-arm the line | `:356-362` |
| 15 | buffs | `BotBuffDriver.selfBuff(alt, now, nextBuffMs, () -> 0L, Pay.ALL, BUFF_RNG)` — class buffs, paid from its own MP/pots | `:363-366` |
| 16 | target filter | `BotAttackDriver.setTargetFilter(altId, m -> OwnedAltCombat.driverMayFace(...))` | `:367-369` |
| 17 | weapon | `weaponProblem` → say once, cannot fight | `:371`, `:506-514` |
| 18 | target | `OwnedAltCombat.target(owner, alt, party, now, skip)` ∩ `CompanionStance.mayChase(stance, pos)` | `:373-378` |
| 19 | fight | `fight`: walk via `Approach`, else `BotAttackDriver.botAttack`; `STALL_MS` 10 s → `skip` that oid for `GIVE_UP_MS` 15 s | `:383`, `:517-563` |
| 20 | loot | `loot`: `OwnedAltLoot.pick`, walk to the drop, `alt.pickupItem`; `LOOT_GAP_MS` 250, stall 8 s → skip 120 s | `:391`, `:561-621` |
| 21 | supplies | every `SHARE_CHECK_MS` 15 s `askSiblings` (needs `CompanionSwitches.supplies()`) | `:394-397`, `:461-496` |
| 22 | follow | `keepFollowing` → `holdStance` (stance told) or `GCMovement.follow`; `doing="following"` | `:398-409`, `:423-457` |

`afterDeath` (`:650-671`): gone, stop, `leaveInstanceQuietly`, `alt.respawn(deathTown)`, `sendHomeAfterDeath` — a dead alt is saved, never a ghost. `say` (`:701-713`) talks in party chat via `BotPartyChat.sayInParty`, else `multiChat` to the owner.

### Gate order (`OwnedAltGate.check`)

First failing reason wins (`src/main/java/soloMapling/companions/OwnedAltGate.java:89-114`):

| Order | `Why` | Condition | line |
|---|---|---|---|
| 1 | `OWNER_GONE` | owner null or `!isLoggedinWorld()` | `:90-92` |
| 2 | `OTHER_MAP` | alt map null or `owner.getMap() != map` | `:94-96` |
| 3 | `TOWN` | `map.isTown()` | `:97-99` |
| 4 | `TOO_FAR` | `|dx|>900` or `|dy|>500`, **skipped** when `CompanionStance.lanesWholeMap` | `:100-105` |
| 5 | `OWNER_IDLE` | `Client.getLastPlayerAction()` older than `idleMs()` (default 180 s, `-Dslums.alts.idleMs`) | `:106-108`, `:81-83`, `:117-123` |
| 6 | `MAP_FULL` | `otherAltsFightingHere >= FIGHTING_PER_MAP` (6) | `:109-111` |
| — | `OPEN` | otherwise | `:112` |

### Orders and gates

`OwnedAltOrders.order` (`src/main/java/soloMapling/companions/OwnedAltOrders.java:53-84`): requires `CompanionSwitches.orders()`, the giver must be the alt's own owner, `LANES` is **refused** here (lanes are handed out by `PartyBotOrders`), `GRIND` is remapped to `SENTRY` — "an alt never goes training on its own" (`:62-68`). The verb map is `CompanionStance.Mode{FOLLOW,STAY,MOVE_HERE,SENTRY,PATROL,LANES}` (`src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionStance.java:35`).

| Order | Stance set | Reply pool | line |
|---|---|---|---|
| follow | `FOLLOW` | "ok", "omw" | `src/main/java/soloMapling/companions/OwnedAltOrders.java:25,70-78` |
| stay | `STAY` | "staying put" | `:26` |
| move here | `MOVE_HERE` | "ok heading over" | `:27` |
| sentry / grind | `SENTRY` | "k, holding here" | `:28,66` |
| patrol | `PATROL` | "ok patrolling" | `:29-30` |
| lanes | refused (`PartyBotOrders.lanes` hands them out) | — | `:62-64` |

Chat commands reach this only through the shared parser: `BotCommandParser` verb sets (`src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotCommandParser.java:70-80`), `PartyBotOrders.read` (`:90`), carry-out to `OwnedAltOrders.order` (`:144`), `CompanionItems` (`:135`), `LANES` (`:166-190`), hooked from `BotPartyChat.onPartyChat` (`:66-70`).

### Summon, save, and the out-of-band endings (`OwnedAltService`)

1. `summon` (`:197-254`): `requireSummoning`, owner must not be an alt/bot, resolve the name in `backend.list(owner)` ∩ `eligible`, then refusals — closing session, already in `entries` or `backend.online`, `transitioning(ownerId)` (`TRANSITION_TTL_MS` 60 s), `occupied(ownerId).size() >= CAP` (5, shared with bot friends) (`:205-213`).
2. `backend.load` builds a `BotClient`, `Character.loadCharFromDB`, `setPlayer`, `markOwnedAlt`, resets party/MPC, `setWorldRates`/`updateCouponRates` (`src/main/java/soloMapling/companions/OwnedAltBackend.java:39-58`).
3. `backend.attach` (`:60-83`): refuses a channel change or an instance owner, forces/creates a party with the owner as leader and < 6 members, `setMap`+`groundedSpawn`+`standingStance`, adds to channel/world/map, `Party.joinParty`, then `GCMovement.follow(alt, owner)`.
4. `entry.state = ACTIVE` (`:245`), then `backend.started` → `OwnedAltCompanion.start()` (`src/main/java/soloMapling/companions/OwnedAltBackend.java:104-106`).
5. `finish(id, login)` (`:315`): `closing` (holds the driver, no tick inside the save window — `src/main/java/soloMapling/companions/OwnedAltBackend.java:112-114`), `leaveEvent`, `ExpeditionDoor.leaveExpedition`, `save`, `detach`. Failure → `SAVING`→`FAILED`, `SAVE_TRIES` 3, self-retry `RETRY_MS` 30 s, then `giveUp` (`:386`); a `FAILED` mark blocks login until `!alts clear <id>` (`clearFailed` `:407`).
6. `detach` (`:116-130`): `forget` → `GCMovement.disable` → `setDisconnectedFromChannelWorld` → `leaveInstanceQuietly` → `leaveParty` → `map.removePlayer` → channel/world `removePlayer` → `logOff` → `empty(true)`.
7. Channel change `ownerChangingChannel` (`:467`), arrival `ownerArrived` (`:501`), party changes `onPartyChange` (`:518`), `@botme here` `bringHere` (`:555`).

### Marriage (ticket 189)

- **Engage:** `RingActionHandler` diverts an owned-alt target: `OwnedAltWedding.accepts(source, target)` must pass (switch on, owner = a real player, alt is theirs, same map, both level ≥ 50 — `src/main/java/soloMapling/companions/OwnedAltWedding.java:43-56`), then `answerProposal(source, target, true)` answers with no packet (`src/main/java/net/server/channel/handlers/RingActionHandler.java:152-156`).
- **Marry:** John's NPC script `OwnedAltWedding.marry(cm.getPlayer())` (`scripts/npc/9201002.js:211-215`). Guards then in order: engaged to that alt, neither married, both in Amoria, both alive, engagement tokens on both, ring room in both equips, one `NORMAL_WEDDING_TICKET_CATHEDRAL` (5251000) fee, ring item data, `createRings`, `deliver` to owner then alt with rollback, remove tokens+fee, `bond` (`src/main/java/soloMapling/companions/OwnedAltWedding.java:74-133`). Ring slot from `{-12,-13,-15,-16}`, item map from token (`:26`, `:58-67`).
- **Exp bonus:** `MarriageExpBonus.bonusFor` returns 0 unless `base>0`, switch on, married, on a map, spouse present and distinct; owned-alt spouse not closing → +5 %, roster bot spouse → +10 %, real player → 0 (`src/main/java/soloMapling/companions/MarriageExpBonus.java:35-42`). Added outside the multiplier chain at `src/main/java/client/Character.java:3995`.

## Data

| Data | Where | Notes |
|---|---|---|
| Alt rows | `SELECT id, accountid, world, name FROM characters WHERE accountid=? AND world=? ORDER BY name` | `src/main/java/soloMapling/companions/OwnedAltBackend.java:19-31` |
| Service entries | `Map<Integer,Entry>`, `State{LOADING,ACTIVE,SAVING,FAILED,LOGIN}` | `src/main/java/soloMapling/companions/OwnedAltService.java:71-102` |
| Active view | `record Active(Character owner, Character alt)`, `active()` returns only `ACTIVE` with non-null owner+alt | `:614-624` |
| Per-alt tick state | `State` (targetOid, skips, gate, `told`, `holdX`, counters) keyed by alt id | `src/main/java/soloMapling/companions/OwnedAltCompanion.java:86-127` |
| Hit memory | `Map<ownerId, Map<mobOid, ts>>`, `HIT_MEMORY_MS` 6 s, `ENGAGED_MS` 20 s | `src/main/java/soloMapling/companions/OwnedAltCombat.java:41,60,195` |
| Stance | `CompanionStance` per (altId, mapId) | `src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionStance.java:35` |
| Tag on the character | `ownedAltOwner` field, `markOwnedAlt`/`isOwnedAlt` | `src/main/java/client/Character.java:740-762` |
| Marriage bond | `Character.setPartnerId`/`getMarriageRing` written by `OwnedAltWedding.Ops.bond` | `src/main/java/soloMapling/companions/OwnedAltWedding.java:135-204` |

## Switches

| Switch | Default | Live 1.6.4 (`docs/release/1.6.4.conf`) |
|---|---|---|
| `OWNED_ALTS_ENABLED` (config.yaml) | `false` (`src/main/java/config/ServerConfig.java:103`) | **not in `SERVER_SET`** (`docs/release/1.6.4.conf:207-214`); set by live `config.yaml` (`docs/release/1.6.0.conf:34`) — see NEW-FINDING |
| `OWNED_ALTS_SUMMON_ENABLED` | `true` (`src/main/java/config/ServerConfig.java:107`) | `true` (`docs/release/1.6.4.conf:208`) |
| `COMPANION_ORDERS_ENABLED` | `false` (`:111`) | `true` (`:209`) |
| `COMPANION_TRADES_ENABLED` | `false` (`:113`) | `true` (`:210`) |
| `COMPANION_UPGRADES_ENABLED` | `false` (`:115`) | `true` (`:211`) — broken for sibling-only offers, b285 |
| `COMPANION_SUPPLIES_ENABLED` | `false` (`:117`) | `true` (`:212`) |
| `OWNED_ALTS_INVITE_SUMMON_ENABLED` | `false` (`:119`) | `true` (`:213`) |
| `-Dslums.alts.lanes` | off (`src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionSwitches.java:43-49`) | on: `docs/release/1.6.4.conf:145` inside `UNIT_EDITS` (`:271`) |
| `-Dslums.bonds` | off | on: `docs/release/1.6.4.conf:171` |
| `-Dslums.ownedalts.marry` | off (`src/main/java/soloMapling/companions/OwnedAltWedding.java:23`) | on: `docs/release/1.6.4.conf:172` |
| `-Dslums.marriage.expbonus` | off (`src/main/java/soloMapling/companions/MarriageExpBonus.java:13`) | on: `docs/release/1.6.4.conf:173` |
| `-Dslums.alts.idleMs` | 180000 (`src/main/java/soloMapling/companions/OwnedAltGate.java:44,81`) | not set |
| `-Dslums.altfight.instanceleash` | off (`src/main/java/soloMapling/companions/OwnedAltCombat.java:53-58`) | not set |
| `-Dslums.ownedalts.acceptownerinvite` | off (`src/main/java/soloMapling/companions/OwnedAltInvite.java:60-69`) | not set (e02 unshipped) |
| `-Dslums.exped.altfollow` | true (`src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:66`) | not in the unit's line |

All six `ServerConfig` companion switches are read on use through `CompanionSwitches` (`src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionSwitches.java:18-59`), so a TEST config edit takes effect on the next order. `Server` logs `ownedAltSwitchesLine()` at boot (`src/main/java/net/server/Server.java:1026-1031`).

`-D` rather than config keys is deliberate: an unknown `config.yaml` key stops the server booting (`src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionSwitches.java:38-42`, same note `src/main/java/soloMapling/companions/OwnedAltInvite.java:53-59`).

**LIVE verdict: ON, with the master switch sourced from `config.yaml` and not from the 1.6.4 conf — the conf's own commentary is stale (`docs/release/1.6.4.conf:253-255` says marry/expbonus are "NOT in UNIT_EDITS") against the owner note at `:268` ("bonds, alt weddings and the marriage EXP bonus ON") and the unit line at `:271`/`:172-173`.**

TEST: unknown — `start-test.sh` is not in this repo.

## Invariants

1. One instance per character: `entries.containsKey(id)` or `backend.online(id)` refuses the summon (`src/main/java/soloMapling/companions/OwnedAltService.java:207-208`).
2. An alt belongs to exactly one owner on one account: `markOwnedAlt` throws `IllegalArgumentException("Invalid alt identity")` otherwise (`src/main/java/client/Character.java:743-750`).
3. `CAP = 5` companions per owner, alts and bot friends counted together (`src/main/java/soloMapling/companions/OwnedAltService.java:17`, `:212-213`).
4. An alt never leaves its owner's map, never fights in a town, never fights more than 6 alts per map (`src/main/java/soloMapling/companions/OwnedAltGate.java:94-111`).
5. An alt never hits a stranger's mob: `driverMayFace` = `withinLeash && mayHit && engaged` (`src/main/java/soloMapling/companions/OwnedAltCombat.java:211-215`).
6. No tick inside the save window: `closing` calls `OwnedAltCompanion.hold` (`src/main/java/soloMapling/companions/OwnedAltBackend.java:112-114`).
7. A closing or failed alt earns no marriage bonus and takes no proposal (`src/main/java/soloMapling/companions/MarriageExpBonus.java:39-41`, `src/main/java/soloMapling/companions/OwnedAltWedding.java:48-50`).
8. Attach/save/detach run outside the service monitor so the 200 ms driver never waits on I/O (`src/main/java/soloMapling/companions/OwnedAltService.java:233-236` comment, `:245`).
9. A save that fails 3 times frees the slot instead of blocking it forever, and login clears a stale `FAILED` mark (`:323-334`, `:386`).
10. An alt is never in the population registry (`src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/CharacterStorage.java:20-22`; `src/main/java/soloMapling/ArtificialPlayer/BotRoster.java:337`).

## Origin

**custom.** The whole package is ours — every file's oldest commit is far later than the base-v83 repack baseline `03d13cb21`:

| File | Oldest commit (`git log --follow … | tail -3`) |
|---|---|
| `OwnedAltService.java` | `cc5058459` 2026-10-25 "Persist owned-alt companions without population inventory or save races" |
| `OwnedAltCompanion.java` | `524e420ed` 2026-10-29 "Owned alts fight, loot, take hits and can die, behind an activity gate" |
| `OwnedAltGate.java` | `524e420ed` 2026-10-29 |
| `OwnedAltWedding.java` | `21ec85c67` 2026-11-04 "Ticket 189 stage 2" |
| `MarriageExpBonus.java` | `5a76873a5` 2026-11-04 "Ticket 189 stage 3" |

Base files this subsystem **modified**: `client/Character.java`, `client/Client.java`, `config/ServerConfig.java`, `server/Trade.java`, `net/server/channel/handlers/{PlayerLoggedinHandler,RingActionHandler,FamilyAddHandler,PartyOperationHandler,TakeDamageHandler,BuddylistModifyHandler,MessengerHandler}.java`, `net/server/guild/Guild.java`, `net/server/world/World.java`, `scripts/npc/9201002.js`.

## Tests and QA

| Layer | Content | Where |
|---|---|---|
| Unit tests | 21 classes / 157 `@Test` in the package's own dir: `OwnedAltCompanionTest` (21), `OwnedAltCombatTest` (17), `OwnedAltServiceTest` (13), `OwnedAltWeddingTest` (11), `OwnedAltLifecycleAuditTest` (11), `OwnedAltHotfixTest` (11), `MarriageExpBonusTest` (8), `OwnedAltGateTest` (8), `OwnedAltGateReasonTest` (7), `OwnedAltInstanceFightTest` (7), `OwnedAltFailedSaveLoginTest` (6), `OwnedAltOwnerInviteTest` (5), `OwnedAltBackendFollowTest` (4), `OwnedAltSummonSwitchTest` (4), `OwnedAltSpawnPacketTest` (3), `OwnedAltLootTest` (3), `OwnedAltLoginTest` (3), `OwnedAltExclusionTest` (3), `OwnedAltInviteTest` (2), `OwnedAltSpawnGroundTest` (2) | `src/test/java/soloMapling/companions/` |
| Unit tests outside | further `@Test`s for the modified call sites under `src/test/java/client/`, `net/server/`, `config/`, `qa/`, `soloMapling/ArtificialPlayer/BotTypes/GCMoveSystem/`, `scripting/` | — |
| Auto-QA | exactly **one** check: `t-oa-1` "Call an alt as a companion: party, cap 5, saved on dismiss", lane only, 3 min | `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1231-1232` |
| Auto-QA probe | `Probes160.ownedAlts`: SKIP when summoning is off, `@botme` list/summon, owned/same-map/in-party, fight→loot→gate legs via `OwnedAltCompanion.stats` | `src/main/java/soloMapling/autoqa/Probes160.java:205-533` |
| `!qa` scenarios | `oa-1` summon/save/dismiss/fast login; `oa-2` orders/trades/upgrades/supplies | `src/main/java/soloMapling/qa/OwnedAltScenarios.java:11-40`, registered `src/main/java/soloMapling/qa/QaScenarios.java:79` |
| Manual gate probe | `forceIdleForQa` used by the probe to prove `OWNER_IDLE` stands the alt down | `src/main/java/soloMapling/autoqa/Probes160.java:501-518` |

Not covered: no test drives `tickAll` end-to-end against a live server; no test exercises a real channel change through `ownerChangingChannel`; no test validates the shipped `UNIT_EDITS` line; the wedding fee/rollback is only exercised through the `Ops` seam (`src/test/java/soloMapling/companions/OwnedAltWeddingTest.java:204-252`), never against a real DB; there is **no `al-*` check at all** although the conf claims one (`docs/release/1.6.4.conf:131` "Check: !qa al-*").

## Known issues

| Card | One line |
|---|---|
| b285 (S3, Server) | An alt can offer an upgrade for a sibling alt but the trade command refuses it unless the owner is in the offer: `CompanionItems.has` gates on `GearUpgrades.offers(self, List.of(leader))` (`src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionItems.java:96`), so the 1.6.2 promise is broken on a switch live turns on. |
| b232 (S3, Server, fixed) | A follower inside an instance did not leave with its leader — closed by `625948ac9` (merged into release-1.6.2); alt exit path is `leaveInstanceQuietly` (`src/main/java/soloMapling/companions/OwnedAltCompanion.java:690-699`). |
| b276 (S4, Server) | QA feed misses map-scoped bot lines — triaged as a QA-tool defect, not this package. |
| b284 (S3, Server) | Auto-QA false FAILs at 10 lanes — QA-tool defect (scheduler key declarations), not this package. |
| b256 (S4, Client) | Real-client frames for the remaining alt extras need job staging. |
| L06 (check) | Failed save no longer locks an alt out; clearer gate and boot lines — branch `164-alt-bundle` `40b2240cf`, awaiting ticket 183 close. |
| L07 (check) | Marriage/wedding bugs fixed, unused wedding ticket refunded — `164-marr-java dfc64a336`, `164-marr-scripts`. |
| L08 (open) | Alt can marry via a proxy marriage; spouse EXP bonus — ticket 189 lane, awaiting review + TEST checklist. |
| e02 (later) | Alts answering their owner's own family/guild invites is built behind `-Dslums.ownedalts.acceptownerinvite` (`src/main/java/soloMapling/companions/OwnedAltInvite.java:60`), not in the release line. |

NEW-FINDING: `docs/release/1.6.4.conf:207-214` turns on all six companion switches but **does not set `OWNED_ALTS_ENABLED`**, whose code default is `false` (`src/main/java/config/ServerConfig.java:103`). `@botme` is gated by `requireEnabled`, so a freshly written 1.6.4 config would leave summoning dead and `t-oa-1` would SKIP itself — the exact failure `ownedAltSwitchesLine` was added to make visible (`src/main/java/config/ServerConfig.java:121-134`). Live only works today because `config.yaml` carries it from 1.6.0.

NEW-FINDING: `docs/release/1.6.4.conf:253-255` states `-Dslums.ownedalts.marry` and `-Dslums.marriage.expbonus` are "NOT in UNIT_EDITS", but `docs/release/1.6.4.conf:271`'s `UNIT_EDITS` list contains both at tokens 172 and 173, and the owner note at `:268` says both are ON. Two of the three statements must be wrong; a release engineer reading only `:253-255` would stage the wrong unit.

NEW-FINDING: `src/main/java/soloMapling/companions/OwnedAltWedding.java:21` (class comment) and `:70-73` describe the marriage as fee-only with "no bonus of any kind", but stage 3 in the same release added `MarriageExpBonus` (+5 % altbot spouse, `src/main/java/soloMapling/companions/MarriageExpBonus.java:14,39-41`). Comment and behaviour disagree.

NEW-FINDING: `src/main/java/soloMapling/companions/OwnedAltGate.java:117-123` treats `last == 0` (no player action ever recorded) as **not** idle, so a client that never sends a recorded action keeps its alts fighting indefinitely; the idle gate is only reachable after the first action. Nothing marks this as intended.

NEW-FINDING: `src/main/java/soloMapling/companions/OwnedAltOrders.java:62-64` refuses `LANES` while `OwnedAltCompanion.tickAll` assigns lane offsets from `PartyBotOrders.slots` (`src/main/java/soloMapling/companions/OwnedAltCompanion.java:237-247`) only when `CompanionSwitches.orders()` is true — so with `-Dslums.alts.lanes=true` and `COMPANION_ORDERS_ENABLED=false` the lane stance is never created, and `CompanionStance.lanesWholeMap` stays false in `OwnedAltGate`/`OwnedAltCombat`. The two switches have an undocumented dependency.

## State

**partial.** Evidence: summon → 200 ms tick → fight/loot/follow/gate → save is implemented end to end (`src/main/java/soloMapling/companions/OwnedAltCompanion.java:306-409`, `src/main/java/soloMapling/companions/OwnedAltService.java:197-254`, `src/main/java/soloMapling/companions/OwnedAltBackend.java:39-130`) with 157 unit tests and one auto-QA check (`t-oa-1`), but: b285 breaks a promise on a switch live enables; `OWNED_ALTS_ENABLED` is absent from the 1.6.4 `SERVER_SET`; the conf contradicts itself on marry/expbonus; e02 is unshipped; and nothing here was runtime-verified in this review.

## Open questions

- Is `OWNED_ALTS_ENABLED=true` in live's `config.yaml` today (rather than assumed from `docs/release/1.6.0.conf:34`)? The conf file itself is not in this repo outside `docs/release/`.
- Did the deployed 1.6.4 unit actually receive tokens 172/173 (`-Dslums.ownedalts.marry`, `-Dslums.marriage.expbonus`)? The conf intends it at `:271`; no staging pre-check output was available.
- Should `OwnedAltOrders` accept `LANES` directly, or is the `orders()`-gated assignment in `tickAll` the only intended path? Nothing states it.
- Does any `al-*` QA check exist outside this repo? `docs/release/1.6.4.conf:131` says "Check: !qa al-*" but no such id is registered in `src/main/java/soloMapling/qa/`.
- Is TEST running with `OWNED_ALTS_SUMMON_ENABLED=true` and the four `COMPANION_*` switches? `start-test.sh` is unreachable, so TEST: unknown.
