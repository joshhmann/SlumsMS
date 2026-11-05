# Families

## Purpose

Families are v83's senior/junior pedigree: two characters link, the junior earns reputation for its senior on kills and level-ups, and the accumulated reputation buys one-shot entitlements (EXP/drop buffs, Reunion teleport, Summon). SlumsMS keeps stock's wire protocol and tables but implements what stock left stubbed: the EXP/drop entitlements, the off-screen rep that bots and offline characters earn, and a DB-backed buff store that survives a restart. Guilds, parties and alliances are documented separately in ../server/handlers-party-guild-alliance-family.md; this page covers only the family side of that shared handler area.

## Entry points

| Surface | Where | Notes |
|---|---|---|
| Client opcodes `OPEN_FAMILY_PEDIGREE`..`FAMILY_SUMMON_RESPONSE` (0x91-0x99) | src/main/java/net/opcodes/RecvOpcode.java:155-163 | 9 opcodes: pedigree, open, add, separate-by-senior (0x94), separate-by-junior (0x95), accept, use, precepts, summon response |
| Handler registration | src/main/java/net/PacketProcessor.java:243-250, :265 | 0x91-0x99 → the eight handlers below |
| `OpenFamilyHandler.handlePacket` | src/main/java/net/server/channel/handlers/OpenFamilyHandler.java:51 | Sends `getFamilyInfo`; refuses with "The family system is coming soon!" when the flag is off (:33, :41-48) |
| `OpenFamilyPedigreeHandler.handlePacket` | src/main/java/net/server/channel/handlers/OpenFamilyPedigreeHandler.java:33 | `showPedigree` for a name looked up in **channel** storage only |
| `FamilyAddHandler.handlePacket` | src/main/java/net/server/channel/handlers/FamilyAddHandler.java:39 | Invite by name, all the refusal codes |
| `AcceptFamilyHandler.handlePacket` | src/main/java/net/server/channel/handlers/AcceptFamilyHandler.java:52 | Accept/decline an invite; the only place a `Family` object is created |
| `FamilySeparateHandler.handlePacket` | src/main/java/net/server/channel/handlers/FamilySeparateHandler.java:33 | Leave a senior (0x94 with id = drop a junior; 0x95 = leave own senior) |
| `FamilyPreceptsHandler.handlePacket` | src/main/java/net/server/channel/handlers/FamilyPreceptsHandler.java:12 | Leader edits the family message (≤200 chars) |
| `FamilyUseHandler.handlePacket` | src/main/java/net/server/channel/handlers/FamilyUseHandler.java:51 | Spend reputation on an entitlement |
| `FamilySummonResponseHandler.handlePacket` | src/main/java/net/server/channel/handlers/FamilySummonResponseHandler.java:21 | The summoned character accepts/declines |
| Send-side packet builders | src/main/java/tools/PacketCreator.java:5854, :5903, :5910, :5948, :6476-6511 | No `net/packet/out/*Family*` classes exist — everything family-shaped lives in `PacketCreator` |
| Boot load | src/main/java/net/server/Server.java:991-999 | `Family.loadAllFamilies(con)` and `FamilyBuffs.load` while the world list is built |
| Daily reset | src/main/java/net/server/world/World.java:216-220 | `FamilyDailyResetTask.resetEntitlementUsage(this)` at world construction and the task registered for the next midnight |
| Bot juniors | src/main/java/soloMapling/ArtificialPlayer/BotFamily.java:172, :361, :395 | `onInvite`, `onSummon`, `arrive` called from the handlers |
| Auto-QA probes `family`, `familyPerks`, `familyTravel`, `familyOffer` | src/main/java/soloMapling/autoqa/Probes160.java:98-101 (body :1451-1509) | Checks t-fy-1..t-fy-4 |
| `!qa run fy-1` | src/main/java/soloMapling/qa/FamilyScenarios.java:30 | Registered src/main/java/soloMapling/qa/QaScenarios.java:109 |

## How it works

1. **Invite.** `FamilyAddHandler.handlePacket` (src/main/java/net/server/channel/handlers/FamilyAddHandler.java:39) returns immediately when `USE_FAMILY_SYSTEM` is off (:40), reads the target name (:43) and resolves it in channel storage first, then falls back to a world-wide scan for a bot that offered itself (:46-56, `BotFamily.offeredRecently(id, chr.getId(), now)`). It refuses with a `sendFamilyMessage` code: 65 not found/offline (:58), self (:60, `enableActions` only), 69 not on the same map or hidden (:62), 77 target level ≤ 10 (:64), 72 level gap > 20 (:66), same family (:68, `enableActions`), 73 an invite is already pending (:70, `InviteCoordinator.hasInvite`), 76 generation cap (:72, `FAMILY_MAX_GENERATIONS`). Otherwise `InviteCoordinator.createInvite(InviteType.FAMILY, chr, target, target.getId())` (:74) and the offer is delivered three ways: bot → `BotFamily.onInvite` (:77), owned alt → `OwnedAltInvite.acceptsOwnerInvite` / `acceptFamily` (src/main/java/soloMapling/companions/OwnedAltInvite.java:76, :92) (:78-80), human → `PacketCreator.sendFamilyInvite` (:82). The inviter sees "The invite has been sent." (:84).

2. **Accept.** `AcceptFamilyHandler.answer` (src/main/java/net/server/channel/handlers/AcceptFamilyHandler.java:69) runs the whole accept inside `Family.underMembershipLock` (:74 → src/main/java/client/Family.java:59), because the junior becomes visible in memory before its row exists. `answerLocked` (:80) consumes the invite with `InviteCoordinator.answerInvite(InviteType.FAMILY, ...)` (:82) and then takes one of three shapes: (a) inviter already in a family, invitee in none — new `FamilyEntry`, `setCharacter`, `setSenior(inviterEntry, true)`, `family.addEntry`, `insertNewFamilyRecord(chr, familyId, inviterId, false)` (:86-98); (b) invitee already in a family — the invitee's family leader must be the target (:102-104), both sides are checked against `FAMILY_MAX_GENERATIONS` (:105), and the whole branch is absorbed with `targetEntry.join(inviter.getFamilyEntry())` (:106, src/main/java/client/FamilyEntry.java:93); (c) neither has a family — `new Family(-1, chr.getWorld())` (:119), `worldServer.addFamily` (:120), the inviter becomes leader (:123), two `insertNewFamilyRecord` calls (:130-131) and `setMessage("", true)`. Success broadcasts `sendFamilyJoinResponse(true, name)`, `getSeniorMessage`, `getFamilyInfo`, `updateSeniorFamilyInfo(true)` (:139-142); decline sends `sendFamilyJoinResponse(false)` (:144); every path ends with `sendFamilyMessage(0, 0)` (:147). `insertNewFamilyRecord` (:150-172) is `INSERT INTO family_character (cid, familyid, seniorid)` plus the optional `UPDATE characters SET familyid`.

3. **Pedigree and branch.** `OpenFamilyPedigreeHandler.handlePacket` (src/main/java/net/server/channel/handlers/OpenFamilyPedigreeHandler.java:33) gates on the flag and, if the named character is in **this channel** and has a family, sends `PacketCreator.showPedigree(entry)` (:39). `showPedigree` (src/main/java/tools/PacketCreator.java:5948) computes `entryCount` as 2 (leader and self) + `min(2, totalSeniors)`, plus one per own junior and per super-junior (:5953-5974), plus one when the direct senior has two juniors (:5956-5961) and one more when the total is still 2 (:5976-5979) and writes one `addPedigreeEntry` (:6026) per node: cid, parent cid, job, level, online flag, reputation, total reputation, reps-to-senior, today's reps, channel (or -1 when away/cashshop), minutes online, name. Counts are maintained on every join/leave by `FamilyEntry.doFullCount` / `traverseAndUpdateCounts` (src/main/java/client/FamilyEntry.java:550, :555), which also sets `Family.totalGenerations` (:171).

4. **Separate.** `FamilySeparateHandler.handlePacket` (src/main/java/net/server/channel/handlers/FamilySeparateHandler.java:33) treats 0x94 with a character id as "drop this junior" (the id must be one of my juniors, :43-48) and 0x95 with no payload as "leave my own senior" (:49-52). The meso price is `2500 * levelDiff + levelDiff²` (:61-63); a shortage sends code 80/81 with the amount (:65). Then the reputation penalty `separateRepCost = ((level / 20) + 10) * level * 2` (:82-89) is taken from the senior (:70) and half of it from the grand-senior (:71-73); the branch announces `<name> has left the family.` (:74) and `forkOn.fork()` (:75, src/main/java/client/FamilyEntry.java:128) turns the leaver into the leader of a brand-new family.

5. **Buff use.** `FamilyUseHandler.handlePacket` (src/main/java/net/server/channel/handlers/FamilyUseHandler.java:51) reads the entitlement index (:55), silently drops out-of-range/already-used/too-expensive requests (:56-64 — the client is not supposed to send them), refreshes the window (:65), and branches: Reunion/Summon go to `useTravel` (:66-72), everything else to `applyBuff` then `useEntitlement` (:73-81).

6. **Buff targeting.** `applyBuff` (:253-287) builds the recipient list from `FamilyBuffs.specOf(entitlement)`'s `Target`: `SELF` → just me; `PARTY` → `getPartyMembersOnline()` across channels; `BONDING` → `onlineBelow(entry)` (:290-305, BFS over juniors) and refuses with "Family Bonding needs at least N Family members online below you (M now)." when the count is under `bondingMinimum()` (:241-243). Each recipient gets `FamilyBuffs.grant(chrId, spec, now)` (src/main/java/client/FamilyBuffs.java:119) and a `dropMessage(5, describe(spec) + " (from <name>)")`; bots only receive the buff for Bonding and get no message (:280-286). The spend itself is `useEntitlement` (:315-322): `entry.useEntitlement` (INSERT into `family_entitlement`, src/main/java/client/FamilyEntry.java:573), `gainReputation(-cost, false)`, then `getFamilyInfo`.

7. **Buff lifetime.** `FamilyBuffs.specOf` (src/main/java/client/FamilyBuffs.java:103-116) maps nine of the eleven entitlements to a `Spec(exp, drop, rate, minutes, Target)` — 1.5× for 15 minutes (self), 2× for 15 minutes (self), 2× for 30 minutes (self), 2× for 30 minutes (party), and Bonding as 2× on exp **and** drop for 30 minutes; only Reunion and Summon fall through to `default -> null` (:114). `grant` (:119-146) keeps the higher rate and, on a tie, extends the end time, capped at `MAX_RATE = 2.0` (:38). Buffs are read on the damage/EXP path: `FamilyBuffs.expMultiplier(attacker)` at src/main/java/server/life/Monster.java:850 (personal EXP) and :867 (party EXP), `FamilyBuffs.dropMultiplier(chr)` at src/main/java/server/maps/MapleMap.java:869, and the off-screen bot paths at src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2815 (loot draw) and :2863 (EXP per second).

8. **Reunion.** `useTravel` (src/main/java/net/server/channel/handlers/FamilyUseHandler.java:92) finds the named member anywhere in the world (`findMember` :171), refuses 65 for self/not-found (:95-98) and 67 for a different family (:99-102). `travelAllowed` (:163-169) rejects maps with `CANNOTMIGRATE` on departure, `CANNOTVIPROCK` on arrival, a forced-return map other than NONE/Maple Island, and event instances; failure sends code 75. Same channel → `changeMap(targetMap, targetMap.getPortal(0))` then `useEntitlement` (:114-118). Cross-channel → remember the destination with `FamilyTravel.expect(me.getId(), theirChannel, mapId, position, now)` (src/main/java/client/FamilyTravel.java:30), then `changeChannel`; a refused change calls `FamilyTravel.cancel` and `refund` and says "Family Reunion could not change your channel. Nothing was spent." (:125-132). The pending arrival is consumed in `Character.newClient` (src/main/java/client/Character.java:1033) — `FamilyTravel.take(getId(), c.getChannel(), now)` with a 60-second TTL (src/main/java/client/FamilyTravel.java:18).

9. **Summon.** Same handler (:135-153): `travelAllowed(targetMap, ownMap)`, then `summonRefusal` (:233-238, which delegates to `BotFamily.summonRefusal` for bots), then code 74 when a summon is already pending, then `InviteCoordinator.createInvite(InviteType.FAMILY_SUMMON, me, victim, victim.getId(), ownMap)` and `useEntitlement` — in that order, so a failed invite costs nothing. A bot is moved by `BotFamily.onSummon` (:149-151); a human receives `PacketCreator.sendFamilySummonRequest`. `FamilySummonResponseHandler.answer` (src/main/java/net/server/channel/handlers/FamilySummonResponseHandler.java:36) consumes the invite, treats a null inviter as "the summoner left" (:46-48) where stock threw, and refuses when the summoner is no longer on the recorded map (:51); any failure refunds `SUMMON_FAMILY` through `FamilyUseHandler.refund` (:57, src/main/java/net/server/channel/handlers/FamilyUseHandler.java:197 — `refundEntitlement` + `gainReputation(+cost, false)`). `travel` (:64-82) moves bots by `BotFamily.arrive`, same-channel humans by `changeMap`, and cross-channel humans by `FamilyTravel.expect` + `changeChannel`; a `SyntheticClient` (auto-QA) is refused with "an auto-QA player can't change channel" (:73-75).

10. **Reputation gain.** Kills: `Monster.giveFamilyRep` (src/main/java/server/life/Monster.java:1120-1128) pays `FAMILY_REP_PER_BOSS_KILL` for bosses and `FAMILY_REP_PER_KILL` otherwise, and 0 when `getMaxHp() <= 1` ("don't count trash mobs"); it is called once per kill for the killer (:635, inside `distributePlayerExperience`) and once for each party member that received no EXP (:691) — the comment at :687 records that stock called it twice for the killer. Level-ups: `Character.levelUp` (src/main/java/client/Character.java:7926-7935) calls `giveReputationToSenior(FAMILY_REP_PER_LEVELUP, true)` and notifies the senior with `levelUpMessage(1, ...)`. Off-screen: `Character.applyOffscreenExp` (:4053) and `TrainingBot.accrueOffscreen` (src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2148-2151) call `giveOffscreenRep`.

11. **Reputation rules.** `FamilyEntry.giveReputationToSenior` (src/main/java/client/FamilyEntry.java:311) halves the gain when the senior is lower level than the junior (:314-316), caps a **bot** junior at `-Dslums.family.botJuniorDailyRep` (default 300) per day (:317-322, :363-365), credits `repsToSenior` (:325-328) and optionally the grand-senior (:329-334). Reputation is flushed by `saveReputation` on character save (src/main/java/client/Character.java:10265-10280 → src/main/java/client/FamilyEntry.java:621/:633) and by `Family.saveAllMembersRep` (src/main/java/client/Family.java:390).

12. **Boot.** `Server.initWorld` (src/main/java/net/server/Server.java:991-999) calls `client.Family.loadAllFamilies(con)` when the flag is on (:995) — stock Cosmic never did, so a restart forgot every family — then `client.FamilyBuffs.load(now)` (:998) and logs "Family buffs restored for {N} characters". `Family.loadAllFamilies` (src/main/java/client/Family.java:264) reads `SELECT * FROM family_character`, materialises bot rows from `botstore` (:275-283, `cid >= BOT_ID_BASE` = 1_000_000_000, :262), reloads entitlements per character (:334-340), re-links juniors whose senior row had not been seen yet (:345-356), repairs a family whose leader row is gone with `repairMissingLeader` (:373-388, memory-only) and recomputes counts with `doFullCount` (:363). On login, `PlayerLoggedinHandler` sends `loadFamily` (src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:272) and, when the character's family entry is found, `getFamilyInfo` (:281) and the login notice up the chain (:282).

13. **Daily reset.** `World` registers `new FamilyDailyResetTask(this)` for the next midnight on every world (src/main/java/net/server/world/World.java:216-220). `FamilyDailyResetTask.run` (src/main/java/net/server/task/FamilyDailyResetTask.java:23-28) calls `resetEntitlementUsage(world)` and then `Family.resetDailyReps()` for each family (:253 of src/main/java/client/Family.java → zeroes `todaysRep`/`repsToSenior` in memory and calls `resetEntitlementUsages`). `resetEntitlementUsage` (:30-53) issues `UPDATE family_character SET todaysrep = 0, reptosenior = 0 WHERE lastresettime <= ?` (:38) and `DELETE FROM family_entitlement WHERE timestamp <= ?` (:44), both against midnight of the next day.

## Data

| Table | Key columns | Written by | Notes |
|---|---|---|---|
| `family_character` | `cid`, `familyid`, `seniorid`, `precepts`, `reputation`, `todaysrep`, `totalreputation`, `reptosenior`, `lastresettime` | src/main/java/client/FamilyEntry.java:409/:418 (`UPDATE family_character SET familyid, seniorid, reptosenior = 0`), :621/:633 (`reputation, todaysrep, totalreputation, reptosenior`), src/main/java/client/Family.java:183-186 (`precepts`), src/main/java/net/server/channel/handlers/AcceptFamilyHandler.java:150-172 (INSERT) | No `CREATE TABLE` for it exists in the repo; the columns are inferred from these statements and src/test/java/database/FamilyBotJuniorsMigrationIT.java:37 (`lastresettime BIGINT NOT NULL DEFAULT 0, INDEX (cid, familyid)`) |
| `family_entitlement` | `entitlementid`, `charid`, `timestamp` | INSERT src/main/java/client/FamilyEntry.java:573; DELETE :590; daily DELETE src/main/java/net/server/task/FamilyDailyResetTask.java:44 | One row per spend; the enum's `usageLimit` is 1 for all 11 entitlements (src/main/java/client/FamilyEntitlement.java:4-14) |
| `family_buffs` | `charid` PK, `exp_rate`, `exp_ends`, `drop_rate`, `drop_ends` | src/main/java/client/FamilyBuffs.java:214-216 upsert, :232 delete | Created by database/migrations/053-family-buffs.sql:13-19; the comment at :5-6 says the game works without it (buffs then end at a restart) |
| `family_041_fks` | `table_name`, `constraint_name`, `column_name`, `ref_table`, `ref_column`, `delete_rule` | database/migrations/041-family-bot-juniors.sql:12-20 | Drops the two FKs from `family_character.cid` and `family_entitlement.charid` to `characters(id)` so bot juniors (synthetic ids) can exist; `Character.deleteCharFromDB` still deletes the row itself (src/main/java/client/Character.java:3083) |
| `mapleroot_integration_migrations` | ledger ids `041-family-bot-juniors` (database/migrations/041-family-bot-juniors.sql:47), `053-family-buffs` (database/migrations/053-family-buffs.sql:21) | migration runner | Both migrations are idempotent through this ledger |

In memory:

| Object | Where | Holds |
|---|---|---|
| `World.families` | src/main/java/net/server/world/World.java:97 | `Map<Integer, Family>`, `LinkedHashMap`; `addFamily` :621 (synchronized, no-op when present), `removeFamily` :629, `getFamily` :635, `getFamilies` :644 (unmodifiable) |
| `Family` | src/main/java/client/Family.java:47, :113-118 | id, world, leader entry, `name`, message, `members` map (`ConcurrentHashMap`), `totalGenerations`; `familyIDCounter` starts at 0 and skips ids already live in any world (`idInUse` :133) |
| `FamilyEntry` | src/main/java/client/FamilyEntry.java:44-57 | senior, `juniors[2]`, `entitlements int[11]`, reputation/totalReputation/todaysRep/repsToSenior, totalSeniors/totalJuniors, generation, and a cached name/level/job so an offline entry can still be shown (`cacheOffline` :85) |
| `FamilyBuffs.active` | src/main/java/client/FamilyBuffs.java:50 | `ConcurrentHashMap<Integer, Active>`; the DB is the persistence, this is the hot path |
| `FamilyTravel.pending` | src/main/java/client/FamilyTravel.java:24 | `ConcurrentHashMap<Integer, Arrival>` with a 60 s TTL, one-shot |
| `FamilyEntitlement` | src/main/java/client/FamilyEntitlement.java:4-14 | 11 constants `FAMILY_REUINION` 300, `SUMMON_FAMILY` 500, `SELF_DROP_1_5` 700, `SELF_EXP_1_5` 800, `FAMILY_BONDING` 1000, `SELF_DROP_2` 1200, `SELF_EXP_2` 1500, `SELF_DROP_2_30MIN` 2000, `SELF_EXP_2_30MIN` 2500, `PARTY_DROP_2_30MIN` 4000, `PARTY_EXP_2_30MIN` 5000 |

Wire packets (all built in src/main/java/tools/PacketCreator.java; decoded by the v83 client):

| Send opcode | Builder | Payload |
|---|---|---|
| `FAMILY_PRIVILEGE_LIST` (0x64) | `loadFamily` :5854 | count, then per entitlement: type byte (1 for the first two, else 2), rep cost, usage limit, name, description |
| `FAMILY_RESULT` (0x60) | `sendFamilyMessage(type, mesos)` :5903 | `int type`, `int mesos`; the codes 64-82 are documented in the comment at :5868-5902 |
| `FAMILY_INFO_RESULT` (0x5F) | `getFamilyInfo` :5910 / `getEmptyFamilyInfo` :5933 | rep, total rep, today's rep, junior count, allowed juniors (2), 0, leader cid, family name, message, entitlement count, then (ordinal, used) pairs |
| `FAMILY_CHART_RESULT` (0x5E) | `showPedigree` :5948 + `addPedigreeEntry` :6026 | viewed cid, node count, then per node: cid, parent cid, job (short), level (byte), online (bool), rep, total rep, reps-to-senior, today, channel (`channel-1`, or -1 when away/cashshop, 0 offline), minutes online, name |
| `FAMILY_JOIN_REQUEST` (0x61) | `sendFamilyInvite` :6476 | player id, inviter name |
| `FAMILY_JOIN_REQUEST_RESULT` (0x62) | `sendFamilyJoinResponse` :6497 | accepted byte, name |
| `FAMILY_JOIN_ACCEPTED` (0x63) | `getSeniorMessage` :6504 | name, 0 |
| `FAMILY_REP_GAIN` (0x65) | `sendGainRep` :6511 | gain, from name |
| `FAMILY_NOTIFY_LOGIN_OR_LOGOUT` (0x66) | `sendFamilyLoginNotice` :6490 | boolean, name |
| `FAMILY_SUMMON_REQUEST` (0x68) | `sendFamilySummonRequest` :6483 | summoner name, family name |

## Switches

| Switch | Kind | Default | Live / TEST |
|---|---|---|---|
| `USE_FAMILY_SYSTEM` | config.yaml key, `public boolean USE_FAMILY_SYSTEM;` (src/main/java/config/ServerConfig.java:309, no Java initialiser) | false until yaml says otherwise | Live ON: `USE_FAMILY_SYSTEM=true` inside `SERVER_SET` (docs/release/1.6.4.conf:204), and config.example.yaml:216 ships true. Ticket 53's "Today" section still records the pre-enable state `false` plus 0 rows in `family_character` (docs/tickets/53-family.md:15, :20). TEST `start-test.sh` is on another host and not reachable — unknown |
| `FAMILY_REP_PER_KILL` | config.yaml key (src/main/java/config/ServerConfig.java:506) | 4 (initialised in Java at :501-509 so an absent key cannot mean 0) | Live uses the default; docs/release/1.6.4.conf has no override of the rep block |
| `FAMILY_REP_PER_BOSS_KILL` | src/main/java/config/ServerConfig.java:507 | 20 | as above |
| `FAMILY_REP_PER_LEVELUP` | src/main/java/config/ServerConfig.java:508 | 200 | as above |
| `FAMILY_MAX_GENERATIONS` | src/main/java/config/ServerConfig.java:509 | 1000 (the v83 client's cap; comment cites docs/tickets/53-family.md:18-19) | as above |
| `-Dslums.family.botJuniorDailyRep` | JVM property, `Integer.getInteger` (src/main/java/client/FamilyEntry.java:364) | 300, floored at 0 | Live sets no `-Dslums.family.*` in docs/release/1.6.4.conf → default; TEST unknown |
| `-Dslums.family.bondingMin` | JVM property (src/main/java/net/server/channel/handlers/FamilyUseHandler.java:242) | 6, floor 1 | default; auto-QA t-fy-2 sets it for the JVM at runtime (src/main/java/soloMapling/autoqa/FamilyProbes.java:107/:176) |
| `-Dslums.family.botsOff` | JVM property, `Boolean.getBoolean` (src/main/java/soloMapling/ArtificialPlayer/BotFamily.java:87) | false (bots participate) | default |
| `-Dslums.family.offersPerHour` (+ `-Dslums.family.offerChance`) | JVM properties read through `BotFamily.setting(key, fallback)` = `System.getProperty("slums.family." + key)` (src/main/java/soloMapling/ArtificialPlayer/BotFamily.java:90-96) | 3 world-wide offers an hour and a 0.1 roll per candidate (:163, :166) | default |
| `-Dslums.family.acceptChance` | removed | — | No longer read: the only mention is the comment at src/main/java/soloMapling/ArtificialPlayer/BotFamily.java:127 ("There was a 80% roll here (slums.family.acceptChance), now gone") |

The flag gates the whole subsystem: handlers answer "The family system is coming soon!" (src/main/java/net/server/channel/handlers/OpenFamilyHandler.java:33), `World` does not register the reset task (src/main/java/net/server/world/World.java:216), `Server` does not load families (src/main/java/net/server/Server.java:993) and `BotFamily.enabled()` is false (src/main/java/soloMapling/ArtificialPlayer/BotFamily.java:86).

## Invariants

- **One membership mutation at a time.** Every accept runs inside `Family.underMembershipLock` (src/main/java/client/Family.java:59; src/main/java/net/server/channel/handlers/AcceptFamilyHandler.java:74) and the QA teardown uses the same lock (src/main/java/soloMapling/ArtificialPlayer/FamilyQa.java:117). This exists because auto-QA t-fy-4's join ran while dissolve deleted rows, and on 2026-10-29 13:05 UTC a TEST boot found families 4 and 8 holding a bot junior whose senior row was gone (comment src/main/java/client/Family.java:50-55).
- **Juniors are deleted before seniors.** `Family.safeDeletionOrder` (src/main/java/client/Family.java:82-112) topologically sorts the rows; a row still in a cycle is never deleted (:111), so a corrupt pedigree leaks rather than breaks.
- **An invite is consumed exactly once.** `InviteCoordinator.answerInvite` returns `NOT_FOUND` for a second answer and the handler returns without touching anything (src/main/java/net/server/channel/handlers/AcceptFamilyHandler.java:82-85).
- **A family id is never reused while any world holds it.** `Family.idInUse` scans every world (src/main/java/client/Family.java:133).
- **A junior has at most two juniors and one senior.** `addJunior` is synchronized and refuses the third slot (src/main/java/client/FamilyEntry.java:467).
- **The generation cap is enforced from both directions** — inviter side src/main/java/net/server/channel/handlers/FamilyAddHandler.java:71, accept side src/main/java/net/server/channel/handlers/AcceptFamilyHandler.java:105.
- **Reputation bought entitlements are one-shot per day:** the popularity/usage check is `isEntitlementUsed` (src/main/java/client/FamilyEntry.java:603) and the daily wipe is `resetEntitlementUsages` (:615) plus the `family_entitlement` DELETE (src/main/java/net/server/task/FamilyDailyResetTask.java:44).
- **Family buffs never stack and never exceed 2×.** `FamilyBuffs.grant` keeps the higher rate and only extends on a tie, clamped by `MAX_RATE = 2.0` (src/main/java/client/FamilyBuffs.java:38, :122-141). `load` applies no clamp of its own (:77-87), so a hand-edited `family_buffs` row above 2.0 would be served as written.
- **Buffs survive a restart but not a missing migration:** `DbStore.loadAll` deletes expired rows and warns "migration 053 missing?" when the table is absent (src/main/java/client/FamilyBuffs.java:243-263).
- **Travel never lands a character on a map it cannot enter:** `travelAllowed` (src/main/java/net/server/channel/handlers/FamilyUseHandler.java:163-169) and the `FamilyTravel` TTL pair with the arrival in `Character.newClient` (src/main/java/client/Character.java:1033) mean a failed channel change simply never lands.
- **A refused cross-channel spend is refunded in full** — reputation back plus the entitlement row deleted (src/main/java/net/server/channel/handlers/FamilyUseHandler.java:197-205; also used for a refused summon, src/main/java/net/server/channel/handlers/FamilySummonResponseHandler.java:57).

## Origin

**Modified.** Every family class exists in the Cosmic base tree except `FamilyBuffs` and `FamilyTravel`, which are absent upstream (`/root/workspace/cosmicms/src/main/java/client/FamilyBuffs.java` and `.../FamilyTravel.java` do not exist) and therefore custom. `tools/cosmicdiff/classifications.json` contains 0 entries matching `family`, so the classification tooling never adjudicated this subsystem. Commit 03d13cb21 is the base-v83 repack baseline; later commits are ours.

| Path | Origin | Evidence |
|---|---|---|
| src/main/java/client/Family.java | modified | `git log --follow` oldest: `03d13cb21`; later `68d88c19b` (enable + entitlements), `3c09a2606` (missing-leader boot repair), `98648cfdc` (dissolve/join lock). Byte-differs from /root/workspace/cosmicms/src/main/java/client/Family.java |
| src/main/java/client/FamilyEntry.java | modified | oldest `03d13cb21`; `68d88c19b`, `113b99778` (bot junior rep / Bonding). Differs from base |
| src/main/java/client/FamilyEntitlement.java | modified | differs from base only in the description strings: stock's "If the … event is in progress, this will be nullified." became "* Stacks with … events and coupons." (src/main/java/client/FamilyEntitlement.java:6-14) |
| src/main/java/client/FamilyBuffs.java | custom | absent upstream; oldest commit `68d88c19b`, then `899741272` (travel/store), `113b99778` (bot juniors), `77762dd81` (stacking tooltips) |
| src/main/java/client/FamilyTravel.java | custom | absent upstream; only commit `899741272` |
| src/main/java/net/server/channel/handlers/FamilyAddHandler.java | modified | oldest `03d13cb21`; `5ab28f6c1` (coming-soon gate), `68d88c19b` (enable + entitlements), `899741272` (world-wide bot offer fallback); differs from base |
| src/main/java/net/server/channel/handlers/AcceptFamilyHandler.java | modified | oldest `03d13cb21`; `68d88c19b`, `98648cfdc` |
| src/main/java/net/server/channel/handlers/FamilyUseHandler.java | modified | oldest `03d13cb21`; `68d88c19b`, `1c8e03a81` (summon refusal) |
| src/main/java/net/server/channel/handlers/FamilySummonResponseHandler.java | modified | oldest `03d13cb21`; `899741272` |
| src/main/java/net/server/channel/handlers/OpenFamilyHandler.java, OpenFamilyPedigreeHandler.java | modified | oldest `03d13cb21`; `5ab28f6c1` (coming-soon gate) |
| src/main/java/net/server/channel/handlers/FamilyPreceptsHandler.java, FamilySeparateHandler.java, src/main/java/net/server/task/FamilyDailyResetTask.java | base unmodified | byte-identical to /root/workspace/cosmicms copies |
| src/main/java/tools/PacketCreator.java | modified | family builders (:5854-6511) differ from base |
| database/migrations/041-family-bot-juniors.sql, 053-family-buffs.sql | custom | not present in the base tree; ticket 53 records why (bot juniors hit the `characters` FK) |

Ticket history: docs/tickets/53-family.md. Stock Cosmic shipped six EXP/drop entitlements and left Bonding stubbed with `//not implemented`; it also never called `loadAllFamilies` from `Server`, which the fork inherited (docs/tickets/53-family.md:10-24, :54-87). The 1.6.1 work is branches 161-family/2..5 plus b035 for off-screen rep (:123-139) after the promise audit (:88-122), and the 2026-10-29 boot stop produced the membership lock (:158-169).

## Tests and QA

| Test class | Covers |
|---|---|
| src/test/java/client/FamilyBuffsTest.java | spec table, higher-rate-wins, extension, cap, expiry, store round-trip (10 methods) |
| src/test/java/client/FamilyDissolveSafetyTest.java | `safeDeletionOrder`: juniors before seniors, cycle rows kept (3) |
| src/test/java/client/FamilyMissingLeaderTest.java | `repairMissingLeader` picks the senior-less member, refuses an empty family (2) |
| src/test/java/client/FamilyOffscreenRepTest.java | `giveOffscreenRep` whole/remainder and level-ups (1) |
| src/test/java/server/life/MonsterFamilyRepTest.java | boss vs normal rep, `maxHp <= 1` pays nothing (2) |
| src/test/java/config/FamilyConfigDefaultsTest.java | the four rep defaults and `MAX_GENERATIONS` are non-zero (3) |
| src/test/java/net/server/channel/handlers/FamilyAcrossChannelsTest.java | Reunion/Summon across channels, refund on refusal (5) |
| src/test/java/net/server/channel/handlers/FamilyOfferFlowTest.java | invite → accept, offer window (3) |
| src/test/java/net/server/channel/handlers/FamilySummonRegularTest.java | a regular (non-bot) summon, decline, refund (6) |
| src/test/java/net/server/channel/handlers/FamilyTravelFieldsTest.java | `FamilyTravel.Arrival` fields/TTL (1) |
| src/test/java/net/server/channel/handlers/OpenFamilyHandlerTest.java | coming-soon gate (2) |
| src/test/java/scripting/FamilyQuestScriptsTest.java | quests 2233 "Raise the Rep!" and 2234 "Enjoy the Entitlement!" against scripts/quest/2233.js and scripts/quest/2234.js (8) |
| src/test/java/database/FamilyBuffsMigrationIT.java, FamilyBotJuniorsMigrationIT.java | migration 053 and 041 apply/rollback and the ledger |
| src/test/java/soloMapling/ArtificialPlayer/BotFamilyTest.java, BotFamilyTestSeams.java | bot accept/offer/summon decisions through the seams |
| src/test/java/soloMapling/qa/FamilyScenarioTest.java | the `fy-1` scenario shape |

| Auto-QA | Where | What it proves |
|---|---|---|
| t-fy-1 "Family: a bot junior, rep, the EXP / drop entitlements" | src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1307-1309, probe `family` | bot junior joins, rep moves, a self buff is granted |
| t-fy-2 "Family perks: party, Bonding, 2x / 30 min, real kills, the cap, the reset" | AutoQaCatalog.java:1315-1318, probe `familyPerks` | party/Bonding targeting, the 2× cap, real kills, and that `resetDailyReps` clears today's rep |
| t-fy-3 "Family Reunion and Summon with a regular junior" | AutoQaCatalog.java:1320-1322, probe `familyTravel` | cross-channel travel bookkeeping |
| t-fy-4 "A regular asks to be your junior, and Add Junior works" | AutoQaCatalog.java:1324-1326, probe `familyOffer` | the offer flow that exposed the 2026-10-29 race |
| `!qa run fy-1` | src/main/java/soloMapling/qa/FamilyScenarios.java:30-76 | manual staging via `FamilyQa.stage`, prints rep and remaining buff minutes; teardown dissolves the family |

Not covered: nothing exercises the **client** side of the family window (entitlement clicks are simulated as raw packets), the reconnect path of a cross-channel Reunion/Summon is unverified on a real client, and no test drives the summoned regular's actual walk-in (docs/tickets/53-family.md:158-169). `FamilyDailyResetTask`'s SQL predicate is only touched by migration ITs, never by a unit test that would notice `lastresettime` never being written.

## Known issues

- **e02 (Server, "later"):** "@botme alts accept their own owner's invites (family, guild, maybe wedding)" — /root/work/STATUS-1.6.4.md:112. The family half is implemented (src/main/java/soloMapling/companions/OwnedAltInvite.java:76, :92 called from src/main/java/net/server/channel/handlers/FamilyAddHandler.java:78-80) but the card still says later, so guild/wedding may be the open part.
- **NEW-FINDING:** `family_character.lastresettime` is filtered on but never written. src/main/java/net/server/task/FamilyDailyResetTask.java:38 does `UPDATE family_character SET todaysrep = 0, reptosenior = 0 WHERE lastresettime <= ?`, and the only other mentions of the column in the whole tree are the commented-out read at src/main/java/client/Family.java:305 and the test DDL at src/test/java/database/FamilyBotJuniorsMigrationIT.java:37. With the column stuck at 0 the predicate always matches, so the filter is decorative and the durable daily reset is really the in-memory `Family.resetDailyReps` (src/main/java/client/Family.java:253).
- **NEW-FINDING:** the pedigree window only sees one channel. `OpenFamilyPedigreeHandler.handlePacket` resolves the name with `c.getChannelServer().getPlayerStorage().getCharacterByName` (src/main/java/net/server/channel/handlers/OpenFamilyPedigreeHandler.java:37-40), so a family member standing in another channel is reported as not found even though `Add Junior` can reach them world-wide (src/main/java/net/server/channel/handlers/FamilyAddHandler.java:46-56). Base behaviour, kept as-is.
- **NEW-FINDING:** the family message never reaches the other members. `FamilyPreceptsHandler` writes the new text (src/main/java/net/server/channel/handlers/FamilyPreceptsHandler.java:24) but the broadcast is commented out (:25), so only the leader's own window updates until someone reopens it (`getFamilyInfo` carries the message, src/main/java/tools/PacketCreator.java:5910-5931). Base behaviour.
- **NEW-FINDING:** `Family.loadAllFamilies` links a junior to a senior only within the same load pass; a row whose senior has already been loaded but whose family had no leader is repaired memory-only (src/main/java/client/Family.java:373-388) and logged, so the repair is lost on the next restart until something rewrites the rows.

No family entries in /root/work/OWNER-DECISIONS.md; /root/work/PQ-UNFINISHED-REPORT.md's "family" hits (:363 "27-line family with CWKPQ", :393 "Nstageclear family") are PQ script families, unrelated.

## State

**working**, with a narrow unverified client surface. Evidence: the boot path loads families and buffs on every world (src/main/java/net/server/Server.java:993-998), invite→accept→rep→buff→travel have unit and cross-channel tests (src/test/java/net/server/channel/handlers/FamilyAcrossChannelsTest.java, FamilySummonRegularTest.java), four auto-QA checks exist (src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1307-1326), and ticket 53 records t-fy-1..t-fy-4 passing on TEST after the 2026-10-29 fix (docs/tickets/53-family.md:140-169). The `lastresettime` finding above does not break the daily reset (the in-memory path does it) but means the SQL predicate gives no protection. The client-side window interactions listed under Tests and QA remain unverified.

## Open questions

- Which characters write `family_character.lastresettime` was never found; either the column is vestigial or a stock writer was dropped in the repack. Grepping src/main/java, src/test/java and database/ for the name yields only the three sites above.
- Whether TEST runs with `USE_FAMILY_SYSTEM=true` and the same rep block is unknown — TEST's `start-test.sh` is on another host and was not reachable, and docs/release/1.6.4.conf describes live only (:160, :204).
- `-Dslums.family.offersPerHour` (src/main/java/soloMapling/ArtificialPlayer/BotFamily.java:163) has no dedicated test; the only coverage is via the t-fy-4 offer probe, so the exact offer cadence in production is unverified.
- Why stock's `Monster` credited family rep twice per kill (comment src/main/java/server/life/Monster.java:687) is not recorded in ticket 53; the fork removed the duplicate, but whether the base numbers (4/20) assumed doubling is not documented.
