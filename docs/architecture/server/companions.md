# Companions (owned alts)

## Purpose

An *owned alt* is another character on the same account and world, taken out of the database and driven by the server as a headless companion of the character the player is logged in on: it follows, fights, loots, takes hits, can die and is saved back. The player calls them with `@botme`, dismisses them with `@dismiss`, and gives them movement orders with `@alts`. The subsystem exists so a solo player is a party without a second client, without touching the population bot registry (`soloMapling.ArtificialPlayer.*`) — an alt uses the normal inventory, save and trade paths (`src/main/java/soloMapling/ArtificialPlayer/BotHelpers.java:48` — `isRosterBot = isBot && !chr.isOwnedAlt()`).

## Entry points

| Entry | What it does | File:line |
|---|---|---|
| `BotMeCommand.execute` | `@botme` list / `@botme <name>` summon / `@botme here`; runs off the packet thread | src/main/java/client/command/commands/gm0/BotMeCommand.java:7-40 |
| `CommandsExecutor` registration | `@botme`, `@dismiss`, `@alts`, `@bonds` | src/main/java/client/command/CommandsExecutor.java:290-296 |
| `OwnedAltService.summon` | the only place an alt enters the world | src/main/java/soloMapling/companions/OwnedAltService.java:197-254 |
| `OwnedAltService.beginLogin(int)` / `finish(int,boolean)` | a player logging into a character that was out as someone's alt | src/main/java/soloMapling/companions/OwnedAltService.java:306, :315-366 |
| `PlayerLoggedinHandler` | calls `beginLogin` before the login is accepted; `ownerArrived` after | src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:150, :425 |
| `OwnedAltCompanion.start()` | registers the 200 ms driver | src/main/java/soloMapling/companions/OwnedAltCompanion.java:132-145 |
| `Client.disconnectInternal` | an alt's own client disconnect, and the owner's logout | src/main/java/client/Client.java:1061-1073 |
| `Client` channel change | `ownerChangingChannel` before the transition | src/main/java/client/Client.java:1681 |
| `Server` shutdown | saves alts still out, in the players' phase | src/main/java/net/server/Server.java:2160-2177 |
| `OwnedAltOrders.order` | `@alts` movement orders from the owner | src/main/java/soloMapling/companions/OwnedAltOrders.java:53-84 |
| `RingActionHandler` | proposal to an owned alt (ticket 189) | src/main/java/net/server/channel/handlers/RingActionHandler.java:102-155 |

## How it works

```mermaid
flowchart TD
    A["@botme &lt;name&gt;"] --> B[OwnedAltService.summon]
    B --> C[OwnedAltBackend.load]
    C --> D[OwnedAltBackend.attach]
    D --> E[OwnedAltBackend.started]
    E --> F["OwnedAltCompanion.start<br/>TimerManager 200 ms"]
    F --> G[OwnedAltCompanion.tickAll -> driveAll -> tick]
    G --> H[OwnedAltGate.check]
    H -->|OPEN| I[fight / loot / askSiblings / keepFollowing]
    H -->|not OPEN| J[standDown, tell once, follow]
    G --> K[BotDeaths -> afterDeath -> finish(login=false)]
    A2["@dismiss / logout / channel change / shutdown"] --> L[OwnedAltService.finish]
    L --> M[closing, leaveEvent, leaveExpedition, save]
    M --> N[detach, entry removed]
```

1. **Owner logs in.** `PlayerLoggedinHandler` calls `OwnedAltService.beginLogin(cid)` before the character is accepted; a throw sends the login to `afterLoginError(10)` (src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:150-152). `beginLogin` writes a `State.LOGIN` entry and returns a `LoginLease` (src/main/java/soloMapling/companions/OwnedAltService.java:306-309). If the id still carries a `FAILED && detached` mark from a save that gave up, the mark is cleared and logged (`"Owned alt {} (id {}) was locked out by a failed save; mark cleared at login"`) rather than locking the character out for the process lifetime (src/main/java/soloMapling/companions/OwnedAltService.java:323-334 — AL-1, ticket 183). Once the login completes, `ownerArrived` (src/main/java/soloMapling/companions/OwnedAltService.java:501-510) removes the entry and, if the owner arrived on a new channel after `ownerChangingChannel` sent alts home, tells them `WENT_HOME` or `HOME_FAILED` (src/main/java/soloMapling/companions/OwnedAltService.java:26, :30).
2. **Summon.** `BotMeCommand` refuses early with `OwnedAltService.RESTING` when `OWNED_ALTS_SUMMON_ENABLED` is off (src/main/java/client/command/commands/gm0/BotMeCommand.java:16-19). `summon` then: refuses an owner that is itself an alt or bot; resolves the name against `backend.list(owner)` filtered by `eligible` (same account, same world, `0 < id < 1e9`, not the owner) (src/main/java/soloMapling/companions/OwnedAltService.java:147-150, :197-203); refuses a closing session, an alt already `entries`-tracked or online, a channel transition in progress (`TRANSITION_TTL_MS` 60 s), and a full companion set (`CAP = 5`, shared with bot friends) (src/main/java/soloMapling/companions/OwnedAltService.java:17, :32, :205-213). It inserts a `LOADING` entry under the monitor, loads **outside** it, re-checks the owner, attaches **outside** it (audit B14 comment at src/main/java/soloMapling/companions/OwnedAltService.java:231-232), flips to `ACTIVE`, and calls `backend.started` (src/main/java/soloMapling/companions/OwnedAltService.java:216-240).
3. **Load and attach.** `OwnedAltBackend.load` builds a `BotClient`, `Character.loadCharFromDB`, `setPlayer`, `markOwnedAlt(owner)` and copies EXP/drop rate settings (src/main/java/soloMapling/companions/OwnedAltBackend.java:35-58). `attach` requires the alt to be in the owner's channel and not in an instance, creates or reuses a party with the owner as leader and fewer than 6 members, sets party/map/position (`groundedSpawn` snaps the owner's X/Y down onto the foothold below so the alt stands rather than floats — src/main/java/soloMapling/companions/OwnedAltService.java:187-190) and stance `4 | (ownerStance & 1)` (standing, facing like the owner — `standingStance`, src/main/java/soloMapling/companions/OwnedAltService.java:193-195), then `PlayerStorage.addPlayer` + `map.addPlayer`, `joinParty`, and `GCMovement.follow` (src/main/java/soloMapling/companions/OwnedAltBackend.java:59-84).
4. **Start the driver.** `backend.started` calls `OwnedAltCompanion.start()` (src/main/java/soloMapling/companions/OwnedAltBackend.java:104-106), which registers `OwnedAltCompanion::tickAll` on `server.TimerManager` with period and delay `TICK_MS = 200` (src/main/java/soloMapling/companions/OwnedAltCompanion.java:69, :139; `TimerManager.register` → `ses.scheduleAtFixedRate` src/main/java/server/TimerManager.java:96-102). Registration is idempotent (`task != null` guard) and a failure only logs.
5. **Tick.** `tickAll` returns immediately when `BotShutdown.isQuiesced()`, recomputes the live owner and alt id sets, prunes `owners`/`states`, drops combat touch memory for owners whose alts went home (`OwnedAltCombat.retainOwners`) (src/main/java/soloMapling/companions/OwnedAltCompanion.java:219-235). With `COMPANION_ORDERS_ENABLED` it also assigns each alt its formation slot via `PartyBotOrders.slots` + `GCMovement.setFollowOffset` (src/main/java/soloMapling/companions/OwnedAltCompanion.java:235-247). `driveAll` does the fights-first seat accounting so `otherAltsFightingHere` is stable, then calls `tick` per alt (src/main/java/soloMapling/companions/OwnedAltCompanion.java:263-299).
6. **Per-alt tick.** Under the alt's `State` monitor: drop the tick if `gone`, closing, or mapless; remember `elapsed`; on `BotDeaths.isDead` either `afterDeath` when `readyToRespawn` or return; return on `hp <= 0`; `BotVitals.regen`; `BotPartySupport.tick`; hand over entirely to `MechanicsHold.committed` (a party quest drives the alt); `catchUp` a stranded alt; then the gate (src/main/java/soloMapling/companions/OwnedAltCompanion.java:306-337).
7. **Gate.** `OwnedAltGate.check` returns the first failing `Why` in order — `OWNER_GONE`, `OTHER_MAP`, `TOWN`, `TOO_FAR` (|dx| > 900 px or |dy| > 500 px, relaxed when the alt holds a whole-map lane), `OWNER_IDLE` (no client player action for `idleMs()`), `MAP_FULL` (6 alts fighting on the map) — else `OPEN` (src/main/java/soloMapling/companions/OwnedAltGate.java:38-44, :89-114). On a non-OPEN gate the alt stands down, the owner is told the reason once per gate (`why.tell`, null for `OPEN`/`OWNER_GONE`), and the alt keeps following (src/main/java/soloMapling/companions/OwnedAltCompanion.java:340-354).
8. **Fight.** With an OPEN gate: self-buffs via `BotBuffDriver.selfBuff`; `BotAttackDriver.setTargetFilter` with `OwnedAltCombat.driverMayFace`; target from `OwnedAltCombat.target`, which respects the leash, engagement (owner/party recently hit it, 6 s hit memory), `KsManners` strangers, and the attackable surface, choosing nearest (src/main/java/soloMapling/companions/OwnedAltCompanion.java:363-385; src/main/java/soloMapling/companions/OwnedAltCombat.java:41-44, :266-295). A weapon problem (no ammo/too weak) is said once and stops the fight (src/main/java/soloMapling/companions/OwnedAltCompanion.java:369-381, :506-515).
9. **Loot.** No target → `loot`, which only takes drops owned by the alt, the owner or the party, within an X band clamped by both the alt's 350 px reach and the owner's 650 px leash, and only if the bag has room; a full bag produces a one-shot line and a stall of `LOOT_STALL_MS` 8 s, and an unreachable drop is skipped for `LOOT_SKIP_MS` 120 s (src/main/java/soloMapling/companions/OwnedAltCompanion.java:391-393, :561-613; src/main/java/soloMapling/companions/OwnedAltLoot.java:33, :107-132).
10. **Idle.** No target and no loot → every `SHARE_CHECK_MS` 15 s `askSiblings` (pot/ammo sharing between alts), then `keepFollowing`, `s.doing = "following"` (src/main/java/soloMapling/companions/OwnedAltCompanion.java:394-399, :461-496).
11. **Death.** `BotDeaths.readyToRespawn` → `afterDeath`: the alt is told in party chat that it died and lost EXP, is put back in town, and `finish(login=false)` saves it so a dead alt is never a ghost (src/main/java/soloMapling/companions/OwnedAltCompanion.java:650-680; src/main/java/soloMapling/companions/OwnedAltService.java:635-649).
12. **Save / logout.** `finish` runs outside the service monitor: `backend.closing` (holds the driver), `leaveEvent`, `ExpeditionDoor.leaveExpedition`, then `backend.save` = `Trade.cancelTrade(alt, PARTNER_CANCEL)` + `saveOwnedAltToDB` (src/main/java/soloMapling/companions/OwnedAltBackend.java:99-115; src/main/java/soloMapling/companions/OwnedAltService.java:341-346). Success sets `saved`/`detached` and the entry becomes `LOGIN` (login) or is removed (dismiss). Failure marks `FAILED`, counts to `SAVE_TRIES = 3`, schedules a self-retry after `RETRY_MS` 30 s, and throws `"Unable to finish saving that character. Please contact a GM before logging into it."`; at three failures `giveUp` takes the alt out of the world and frees the slot (src/main/java/soloMapling/companions/OwnedAltService.java:34-36, :355-365, :386-401).
13. **Out-of-band endings.** Channel change: `ownerChangingChannel` saves and sends alts home before the transition, and blocks `@botme` for `TRANSITION_TTL_MS` (src/main/java/soloMapling/companions/OwnedAltService.java:467-498). Logout: `disconnectInternal` calls `dismissAll(player, true)` and logs `"login remains blocked"` on failure (src/main/java/client/Client.java:1066-1073). Shutdown: `outForShutdown` + `closeForShutdown` inside the players' save budget (src/main/java/net/server/Server.java:2160-2177).

### Marriage EXP bonus math

`-Dslums.marriage.expbonus` (default OFF) adds a flat share of the base gain, computed **outside** the coupon/pendant multiplier chain:

1. `Character.gainExpAllowed` — after the Curse halving and the negative-overflow clamps, `long equipL = (long) (gain / 10) * pendantExp;` (src/main/java/client/Character.java:3993).
2. `equipL += soloMapling.companions.MarriageExpBonus.bonusFor(this, gain);` with the comment *"the marriage bonus rides with the Pendant's share, outside the multiplier chain and any stacking cap"* (src/main/java/client/Character.java:3994-3995).
3. `int equip = (int) Math.min(equipL, Integer.MAX_VALUE);` then `gainExpInternal(gain, equip, party, ...)` (src/main/java/client/Character.java:3996-3998).
4. `bonusFor` returns 0 unless `base > 0`, the switch is on, the character is married and has a map; it finds the spouse with `chr.getMap().getCharacterById(chr.getPartnerId())` — same map only — and returns 0 for self or absent spouse (src/main/java/soloMapling/companions/MarriageExpBonus.java:35-38).
5. Spouse kind decides the rate: an owned alt that is not closing → `share(base, 0.05)`; a roster bot (`BotHelpers.isRosterBot`) → `share(base, 0.10)`; a real player → 0 (src/main/java/soloMapling/companions/MarriageExpBonus.java:14-15, :39-42).
6. `share` is `(int) Math.min((long) Math.floor(amount * rate), Integer.MAX_VALUE)`, 0 for non-positive input (src/main/java/soloMapling/companions/MarriageExpBonus.java:29-32). With `gain = 10000`: 0.05 → **500**, 0.10 → **1000** added on top of the pendant share.

Alts also gain EXP like any party member: `gainPartyPlayExp` refuses only when the alt's owner is not live (`canGainOwnedAltPartyExp`, `liveOwnedAltOwner`) (src/main/java/client/Character.java:3949-3977).

### Marriage to your own alt

`-Dslums.ownedalts.marry` (default OFF): `OwnedAltWedding.accepts` requires the switch, an owner that is a real player, an alt that is the owner's own, same map, both level ≥ 50 (src/main/java/soloMapling/companions/OwnedAltWedding.java:43-56). `marry` pays one `NORMAL_WEDDING_TICKET_CATHEDRAL` as the fee, removes it from cash on failure, writes the bond, and wears a ring in the first free slot of `{-12,-13,-15,-16}` (src/main/java/soloMapling/companions/OwnedAltWedding.java:25-26, :70-132, :168-204). `RingActionHandler` diverts an owned-alt target here and auto-answers the proposal (src/main/java/net/server/channel/handlers/RingActionHandler.java:102-106, :152-155).

## Data

| Data | Where | Notes |
|---|---|---|
| Alt rows | `characters` table, `SELECT id, accountid, world, name FROM characters WHERE accountid = ? AND world = ? ORDER BY name` | src/main/java/soloMapling/companions/OwnedAltBackend.java:19-31 |
| In-memory entries | `Map<Integer,Entry>` with `State{LOADING,ACTIVE,SAVING,FAILED,LOGIN}` | src/main/java/soloMapling/companions/OwnedAltService.java:71-102 |
| Per-alt tick state | `OwnedAltCompanion.State` (targets, skips, gate, `told`, timing) keyed by alt id | src/main/java/soloMapling/companions/OwnedAltCompanion.java:86-127 |
| Hit memory | `OwnedAltCombat`, 6 s window, per owner | src/main/java/soloMapling/companions/OwnedAltCombat.java:41 |
| Stance | `CompanionStance` (patrol 250 px, sentry reach 200 px, "there" 25 px) | src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionStance.java:40-42 |
| Save marker | `ownedAltSaveCommitted`, cleared before each save, required after commit | src/main/java/client/Character.java:735, :752-762 |
| Population registry | alts are explicitly refused | src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/CharacterStorage.java:20-22 |

Alts hold no bot-roster row, no popularity/affinity record, and are excluded from the population save path by `isOwnedAlt()` checks (~22 call sites under `src/main/java/soloMapling/ArtificialPlayer/`).

## Switches

| Switch | Default | Live (1.6.4) | Meaning |
|---|---|---|---|
| `OWNED_ALTS_ENABLED` (config.yaml) | false (src/main/java/config/ServerConfig.java:103) | **absent from SERVER_SET** — see NEW-FINDING 1 | the master switch; gates `@botme` entirely (`requireEnabled`, src/main/java/soloMapling/companions/OwnedAltService.java:144-146) and the bot-friend capacity |
| `OWNED_ALTS_SUMMON_ENABLED` | true (src/main/java/config/ServerConfig.java:107) | `true` (docs/release/1.6.4.conf:208) | `@botme` summoning; off = "Alts are resting", `@dismiss` still works |
| `COMPANION_ORDERS_ENABLED` | false (src/main/java/config/ServerConfig.java:111) | `true` (docs/release/1.6.4.conf:209) | `@alts` movement/formation orders |
| `COMPANION_TRADES_ENABLED` | false (src/main/java/config/ServerConfig.java:113) | `true` (docs/release/1.6.4.conf:210) | trades by category with the owner |
| `COMPANION_UPGRADES_ENABLED` | false (src/main/java/config/ServerConfig.java:115) | `true` (docs/release/1.6.4.conf:211) | spare-gear upgrade sharing (broken for sibling-only offers, b285) |
| `COMPANION_SUPPLIES_ENABLED` | false (src/main/java/config/ServerConfig.java:117) | `true` (docs/release/1.6.4.conf:212) | pot/ammo/rock sharing |
| `OWNED_ALTS_INVITE_SUMMON_ENABLED` | false (src/main/java/config/ServerConfig.java:119) | `true` (docs/release/1.6.4.conf:213) | a party invite naming an offline alt summons it |
| `-Dslums.alts.lanes` | off (src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionSwitches.java:43-49) | `true` (docs/release/1.6.4.conf:271) | each alt holds a lane across the whole map (relaxes the gate's distance rule) |
| `-Dslums.bonds` | off (src/main/java/soloMapling/ArtificialPlayer/RelationView.java:20-31) | `true` (docs/release/1.6.4.conf:271) | `@bonds` lists friends/partner/guild/family/alts; alts listed via `OwnedAltService.owned` (src/main/java/soloMapling/ArtificialPlayer/RelationView.java:153) |
| `-Dslums.ownedalts.marry` | off (src/main/java/soloMapling/companions/OwnedAltWedding.java:23) | `true` (docs/release/1.6.4.conf:271) | marry your own out alt at John, one ticket fee |
| `-Dslums.marriage.expbonus` | off (src/main/java/soloMapling/companions/MarriageExpBonus.java:13) | `true` (docs/release/1.6.4.conf:271) | +10% playerbot spouse / +5% out altbot spouse, same map |
| `-Dslums.alts.idleMs` | 180000 (src/main/java/soloMapling/companions/OwnedAltGate.java:44, :81-83) | not set | no player action for this long → `OWNER_IDLE` |
| `-Dslums.ownedalts.acceptownerinvite` | off (src/main/java/soloMapling/companions/OwnedAltInvite.java:60) | not set | e02: alts answer their owner's own invites |
| `-Dslums.exped.altfollow` | true (src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:66, :72-74) | not in the unit's line | an owned alt joins any expedition its owner is in |

`-Dslums.alts.lanes` is deliberately a system property rather than a config key: an unknown key in `config.yaml` stops the boot (comment at src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionSwitches.java:43-49). `Server` logs `ownedAltSwitchesLine()` at boot so a conf that leaves the master off is visible instead of an unexplained `"not available yet"` (src/main/java/net/server/Server.java:1026-1031; src/main/java/config/ServerConfig.java:126-134).

TEST: the host's `start-test.sh` is not reachable from this review, so TEST's values are **unknown**.

## Invariants

- One alt instance at a time: `entries` contains the id, or `backend.online(id)` is true, or a save is in flight → refuse (`src/main/java/soloMapling/companions/OwnedAltService.java:207-208`).
- A save that fails three times takes the alt out of the world and frees its companion slot; a `FAILED && detached` entry no longer counts against `CAP` (audit #7, honest slot accounting) (`src/main/java/soloMapling/companions/OwnedAltService.java:164-173`, `:355-365`).
- A given-up save never locks the character out of login permanently: the login clears the mark and reads the last DB copy (`src/main/java/soloMapling/companions/OwnedAltService.java:323-334`).
- An alt never leaves the owner's map by itself (`OTHER_MAP` gate) and never fights in a town (`TOWN` gate) (`src/main/java/soloMapling/companions/OwnedAltGate.java:96-105`).
- Catch-up and `@botme here` never carry an alt into or out of an instance, and never past a map gate it could not walk (audit K12) (`src/main/java/soloMapling/companions/OwnedAltService.java:651-668`).
- Attach, save, dismiss and map moves run outside the service monitor so the 200 ms driver never waits on I/O (audit B14) (`src/main/java/soloMapling/companions/OwnedAltService.java:231-232`, `:341`, `:584`).
- An alt never enters the population registry (`src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/CharacterStorage.java:20-22`).
- The marriage bonus is additive with the Pendant share, never multiplied by it, and cannot overflow int (`src/main/java/client/Character.java:3994-3996`).
- A closing alt (`isOwnedAltClosing`) earns no marriage bonus mid-save (`src/main/java/soloMapling/companions/MarriageExpBonus.java:39-41`).

## Origin

**custom.** The package `src/main/java/soloMapling/companions/` has no counterpart in the Cosmic base tree — `/root/workspace/cosmicms/src/main/java/soloMapling` does not exist, and `tools/cosmicdiff/classifications.json` contains no `soloMapling` entry at all, so no DELIBERATE/BROKEN/RESTORED classification exists for these files.

| File | Oldest commit (git --follow, tail) |
|---|---|
| `OwnedAltService.java` | `cc5058459` 2026-10-25 |
| `OwnedAltCompanion.java` | `524e420ed` 2026-10-29 |
| `OwnedAltGate.java` | `524e420ed` 2026-10-29 |
| `OwnedAltWedding.java` | `21ec85c67` 2026-11-04 (ticket 189 stage 2) |
| `MarriageExpBonus.java` | `5a76873a5` 2026-11-04 (ticket 189 stage 3) |

All later than `03d13cb21`, the base-v83 repack baseline, so everything here is ours. Base files this subsystem **modified**: `client/Character.java`, `client/Client.java`, `config/ServerConfig.java`, `server/TimerManager.java`, `net/server/channel/handlers/PlayerLoggedinHandler.java`, `net/server/channel/handlers/RingActionHandler.java`, `server/Trade.java`, `net/server/world/World.java`.

## Tests and QA

| Layer | What | Where |
|---|---|---|
| Unit tests | 21 classes in the package's own test dir | `src/test/java/soloMapling/companions/` — `MarriageExpBonusTest`, `OwnedAltBackendFollowTest`, `OwnedAltCombatTest`, `OwnedAltCompanionTest`, `OwnedAltExclusionTest`, `OwnedAltFailedSaveLoginTest`, `OwnedAltGateReasonTest`, `OwnedAltGateTest`, `OwnedAltHotfixTest`, `OwnedAltInstanceFightTest`, `OwnedAltInviteTest`, `OwnedAltLifecycleAuditTest`, `OwnedAltLoginTest`, `OwnedAltLootTest`, `OwnedAltOwnerInviteTest`, `OwnedAltServiceTest`, `OwnedAltSharedPathsTest`, `OwnedAltSpawnGroundTest`, `OwnedAltSpawnPacketTest`, `OwnedAltSummonSwitchTest`, `OwnedAltWeddingTest` |
| Unit tests outside | ~42 further classes under `src/test/java/client/`, `net/server/`, `soloMapling/ArtificialPlayer/` (`AltMarriageGuardsTest` among them) | — |
| Auto-QA | `t-oa-1` "Call an alt as a companion: party, cap 5, saved on dismiss", lane only, 3 min, probe `ownedAlts` | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1231-1232 |
| Auto-QA probe | `Probes160.ownedAlts`; skips when `OWNED_ALTS_ENABLED`/`OWNED_ALTS_SUMMON_ENABLED` are off; checks `CAP == 5`, `@botme` list/summon, owned/same-map/in-party | src/main/java/soloMapling/autoqa/Probes160.java:209-215, :231, :256-268 |
| Manual `!qa` | `oa-1` summon/save/dismiss/fast login; `oa-2` companion orders, trades, upgrades, supplies (both refuse unless the switches are on) | src/main/java/soloMapling/qa/OwnedAltScenarios.java:10-56 |

Not covered: no test drives the 200 ms tick end-to-end against a live server; no test exercises a real channel change through `ownerChangingChannel`; no test validates the shipped `UNIT_EDITS` line; no test runs the wedding fee/rollback against a real database; the `marry`/`expbonus` switches are never tested with their live values; `oa-2` refuses on a server where the six companion switches are off, so it proves nothing by default.

## Known issues

| Card | One line |
|---|---|
| b285 (S3, Server; ticket 178) | A sibling-only upgrade offer is refused: `CompanionItems.has` gates on `GearUpgrades.offers(self, List.of(leader))` (src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionItems.java:96) so a donor that only helps a sibling alt never reaches `tradeUpgrades` (src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/CompanionItems.java:312), contradicting ticket 178's promise (docs/tickets/178-sibling-only-upgrade-handover.md:3-5, :24-26). |
| b256 (S4, Client) | Real-client frames for the remaining alt extras still need job staging (/root/work/STATUS-1.6.4.md:73). |
| e02 (later) | `@botme` alts accepting their owner's own invites is built behind `-Dslums.ownedalts.acceptownerinvite` but the switch is not in the release line (/root/work/STATUS-1.6.4.md:112; src/main/java/soloMapling/companions/OwnedAltInvite.java:60). |
| L06 (check) | Failed save no longer locks an alt out; clearer gate and boot lines — branch `164-alt-bundle` 40b2240cf, awaiting ticket 183 close (/root/work/STATUS-1.6.4.md:97). |
| L08 (open) | Alt marriage + marriage EXP bonus: built, awaiting review and the TEST checklist (/root/work/STATUS-1.6.4.md:99; docs/tickets/189-alt-marriage-1-6-4.md:3). |
| P13 (PQ-UNFINISHED-REPORT) | Zakum arms need a real player's alt to prove the party path (/root/work/PQ-UNFINISHED-REPORT.md:652). |

NEW-FINDING: `docs/release/1.6.4.conf:201-214` (SERVER_SET) no longer sets `OWNED_ALTS_ENABLED`, while the six companion switches beside it are `true`. The 1.6.3 conf did set it (docs/release/1.6.3.conf:113; docs/release/1.6.0.conf:34), and `ServerConfig.ownedAltSwitchesLine`'s own comment records that leaving it at the false default made `@botme` unavailable and self-skipped `t-oa-1` (src/main/java/config/ServerConfig.java:121-125). A fresh 1.6.4 config write therefore leaves the master off; the only signal is the boot log line.

NEW-FINDING: `src/main/java/soloMapling/companions/OwnedAltWedding.java:21` still says "No bonus of any kind", which stopped being true when stage 3 added `MarriageExpBonus` (same release), and `docs/release/1.6.4.conf:253-256` still says marry/expbonus are "NOT in UNIT_EDITS" while line 271's `UNIT_EDITS` carries both.

## State

**partial.** Evidence: the summon → 200 ms tick → fight/loot/follow → save path is fully implemented (src/main/java/soloMapling/companions/OwnedAltCompanion.java:306-401, src/main/java/soloMapling/companions/OwnedAltService.java:197-254, :315-366) and has 21 dedicated unit tests plus `t-oa-1`, but b285 breaks the 1.6.2 upgrade-handover promise on a switch the live conf turns on, e02 is unshipped, `OWNED_ALTS_ENABLED` is missing from the 1.6.4 SERVER_SET, and nothing in this review was runtime-verified.

## Open questions

- Is TEST's `start-test.sh` setting `OWNED_ALTS_ENABLED=true`? The file is on another host and unreachable, so TEST's effective switch set is unknown.
- Does the deployed 1.6.4 unit actually carry `-Dslums.marriage.expbonus` and `-Dslums.ownedalts.marry`? The conf intends it (docs/release/1.6.4.conf:271) and the owner block at :268-270 says on, but the staging pre-check output was not available here.
- Was `OWNED_ALTS_ENABLED` dropped from SERVER_SET deliberately (in favour of hand-editing `config.yaml`) or by omission? The conf carries no note either way.
- How much of the `@bonds` "alts that are out must show" gap (/root/work/STATUS-1.6.4.md:12) is closed by `OwnedAltService.owned` returning every alt (src/main/java/soloMapling/companions/OwnedAltService.java:160-163) rather than only summonable ones — not verified against a live session.
