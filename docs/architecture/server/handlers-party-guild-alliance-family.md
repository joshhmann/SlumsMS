# Parties, Guilds, Alliances, Families and Party Search

## Purpose

Five related group subsystems share one shape: a packet handler in `src/main/java/net/server/channel/handlers/` validates a client request, mutates an in-memory object graph owned by `World` or `Server`, then writes SQL and broadcasts packets. A party is a transient group of up to 6 (`src/main/java/net/server/world/Party.java:59`) held only in RAM in `World.parties`. A guild is a durable rank hierarchy (`src/main/java/net/server/guild/Guild.java`) with an optional alliance of guilds (`src/main/java/net/server/guild/Alliance.java`). A family is a separate senior/junior pedigree tree with reputation currency and entitlements (`src/main/java/client/Family.java`, handlers `Family*`). Party Search is a background coordinator that invites queued party leaders to candidate players every 10 seconds (`src/main/java/net/server/coordinator/partysearch/PartySearchCoordinator.java`).

## Entry points

| Packet / trigger | Opcode constant | Handler | Registered at |
| --- | --- | --- | --- |
| Party create/leave/join/invite/expel/change-leader | `PARTY_OPERATION` | `PartyOperationHandler.handlePacket` | src/main/java/net/PacketProcessor.java:184 |
| Party invite refusal | `DENY_PARTY_REQUEST` | `DenyPartyRequestHandler.handlePacket` | src/main/java/net/PacketProcessor.java:185 |
| Guild ops 0x00-0x1E | `GUILD_OPERATION` | `GuildOperationHandler.handlePacket` | src/main/java/net/PacketProcessor.java:204 |
| Guild invite refusal | `DENY_GUILD_REQUEST` | `DenyGuildRequestHandler.handlePacket` | src/main/java/net/PacketProcessor.java:205 |
| Alliance ops 0x01-0x0A | `ALLIANCE_OPERATION` | `AllianceOperationHandler.handlePacket` | src/main/java/net/PacketProcessor.java:257 |
| Alliance invite refusal | `DENY_ALLIANCE_REQUEST` | `DenyAllianceRequestHandler.handlePacket` | src/main/java/net/PacketProcessor.java:258 |
| Family open / pedigree | `OPEN_FAMILY`, `OPEN_FAMILY_PEDIGREE` | `OpenFamilyHandler`, `OpenFamilyPedigreeHandler` | src/main/java/net/PacketProcessor.java:243-244 |
| Family invite / accept | `ADD_FAMILY`, `ACCEPT_FAMILY` | `FamilyAddHandler`, `AcceptFamilyHandler` | src/main/java/net/PacketProcessor.java:245, 265 |
| Family separate / use / precepts / summon reply | `SEPARATE_FAMILY_BY_SENIOR`/`_JUNIOR`, `USE_FAMILY`, `CHANGE_FAMILY_MESSAGE`, `FAMILY_SUMMON_RESPONSE` | `FamilySeparateHandler`, `FamilyUseHandler`, `FamilyPreceptsHandler`, `FamilySummonResponseHandler` | src/main/java/net/PacketProcessor.java:246-250 |
| Party search register/start/update | `PARTY_SEARCH_REGISTER`/`_START`/`_UPDATE` | `PartySearchRegisterHandler` (empty), `PartySearchStartHandler`, `PartySearchUpdateHandler` | src/main/java/net/PacketProcessor.java:273-275 |
| 10-second party search tick | — | `PartySearchTask.run` → `World.runPartySearchUpdateSchedule` | src/main/java/net/server/world/World.java:212 |
| Guild creation confirmation (cofounders) | — | `MatchCheckerGuildCreation.notifyPlayer` | src/main/java/net/server/coordinator/matchchecker/listener/MatchCheckerGuildCreation.java:114 |
| Alliance creation from an NPC script | — | `NPCConversationManager.createAlliance` | src/main/java/scripting/npc/NPCConversationManager.java:751 |
| Family init / daily reset at boot | — | `Family.loadAllFamilies`, `FamilyBuffs.load`, `FamilyDailyResetTask` | src/main/java/net/server/Server.java:995, 998; src/main/java/net/server/world/World.java:218-220 |

## How it works

### Party lifecycle

1. `PartyOperationHandler.handlePacket` reads the operation byte and resolves the world and the sender's party (`src/main/java/net/server/channel/handlers/PartyOperationHandler.java:45-48`).
2. Create → `Party.createParty(player, false)` (`PartyOperationHandler.java:50-52`). That method refuses level < 10 unless `USE_PARTY_FOR_STARTERS`, refuses Ariant Coliseum entrants, builds a `PartyCharacter`, calls `world.createParty`, then `setParty`/`setMPC`, `map.addPartyMember`, `silentPartyUpdate`, `updatePartySearchAvailability(false)`, `partyOperationUpdate` and the `partyCreated` packet (`src/main/java/net/server/world/Party.java:329`).
3. `World.createParty(PartyCharacter)` takes the next id from `runningPartyId`, inserts into `parties`, registers the char→party mapping under `partyLock`, and only then adds the creator as a member (`src/main/java/net/server/world/World.java:920`). `Party`'s constructor deliberately does not add its own leader (`src/main/java/net/server/world/Party.java:61`).
4. Join → `InviteCoordinator.answerInvite(PARTY, …)` must return `ACCEPTED` first (`PartyOperationHandler.java:64-74`), then `Party.joinParty` re-checks membership under the world (`src/main/java/net/server/world/Party.java:362`). The `getMembers().size() < MAX_MEMBERS` test at `Party.java:369` is documented as advice only; the authoritative cap is inside `addMember` under the party lock (`src/main/java/net/server/world/Party.java:75-81`).
5. `World.updateParty(partyid, JOIN, target)` returns false when the party is gone (only logged once, to cut 1.5.2 log spam) or when `addMember` refuses, which the caller turns into `partyStatusMessage(17)` (`src/main/java/net/server/world/World.java:1020`, `src/main/java/net/server/world/Party.java:362`).
6. Invite → channel-scoped lookup, owned-alt refusal, level-10 rules, lazy party creation, the `size() < 6` pre-check, `InviteCoordinator.createInvite`, the `partyInvite` packet and the bot hook (`PartyOperationHandler.java:76-129`).
7. Leave → `Party.leaveParty`; the leader branch broadcasts `DISBAND` and tears down the expedition instance, a member branch broadcasts `LEAVE` (`src/main/java/net/server/world/Party.java:418-474`).
8. Expel → `Party.expelFromParty` requires `partyplayer.equals(party.getLeader())`, then cleans map/carnival/instance state, nulls the victim's party and broadcasts `EXPEL` (`src/main/java/net/server/world/Party.java:476-515`). `PartyCharacter.equals` compares names only (`src/main/java/net/server/world/PartyCharacter.java:123`).
9. Change leader → the b298 gate (see Invariants), then `world.updateParty(party.getId(), PartyOperation.CHANGE_LEADER, newLeadr)` (`PartyOperationHandler.java:136-150`), which resolves `party.getLeader().getPlayer()` and calls `party.setLeader(target)` (`src/main/java/net/server/world/World.java:1054`).
10. Disband/leave/expel all loop back through `World.updateParty(Party, op, target)` (`src/main/java/net/server/world/World.java:981`), which pushes `setParty`/`setMPC` onto every online member and — for `DISBAND` only — calls `chr.updatePartySearchAvailability(true)` under a `ticket 164` comment (`World.java:991`). `OwnedAltService.onPartyChange` fires after every successful change (`World.java:1080`).

### Party Search

1. `PartySearchStartHandler` reads min/max level and a job mask, refuses min>max, a range wider than 30, and a sender whose own level is outside the range (`src/main/java/net/server/channel/handlers/PartySearchStartHandler.java:40-59`). It reads and discards an int it labels `members` (`PartySearchStartHandler.java:62`).
2. It then requires `party != null && isPartyLeader()` and registers (`PartySearchStartHandler.java:66-71`).
3. `PartySearchCoordinator.registerPartyLeader` stores `LeaderSearchMetadata` and appends to `leaderQueue` (`src/main/java/net/server/coordinator/partysearch/PartySearchCoordinator.java:284`). `PartySearchUpdateHandler` unconditionally unregisters (`src/main/java/net/server/channel/handlers/PartySearchUpdateHandler.java:31-33`).
4. `PartySearchTask` is registered every 10 s with a 10 s initial delay (`src/main/java/net/server/world/World.java:212`); `PartySearchTask.run` calls `wserv.runPartySearchUpdateSchedule()` (`src/main/java/net/server/task/PartySearchTask.java:29-30`), which copies each echelon into the storage (`PartySearchCoordinator.updatePartySearchStorage`, `src/main/java/net/server/coordinator/partysearch/PartySearchCoordinator.java:245-249`) and then runs `runPartySearch` (`src/main/java/net/server/world/World.java:2159-2162`; `PartySearchCoordinator.java:403`).
5. For each queued leader, `sendPartyInviteFromSearch` creates a `PARTY` invite and disables that candidate's search-invite flag (`PartySearchCoordinator.java:333-350`).
6. Candidate matching is `PartySearchStorage.callPlayer`, which binary-searches a level-sorted list and marks empty level intervals to skip (`src/main/java/net/server/coordinator/partysearch/PartySearchStorage.java:102-146`).
7. A leader whose invite attempt fails is re-queued while `reentryCount < PARTY_SEARCH_REENTRY_LIMIT`, else expired (`PartySearchCoordinator.java:417`). Searched leaders still under 6 members are re-queued; full ones get "your party reached full capacity" (`PartySearchCoordinator.java:441-452`). `updateCount % 77 == 0` reinstates long-term leaders (`PartySearchCoordinator.java:476`).
8. `isInVicinity` decides whether a candidate's map is neighbours-of the caller's map; ticket 164 fixed it to test the *caller* map against the callee's neighbour set (`PartySearchCoordinator.java:124-131`).
9. `PartySearchRegisterHandler.handlePacket` is intentionally empty: the 0xDC layout is unknown and invite reception is toggled from NPC scripts instead (src/main/java/net/server/channel/handlers/PartySearchRegisterHandler.java:29-50).

### Guild

1. Create (`0x02`) requires no guild, `CREATE_GUILD_COST` meso, a 3-12 letter name, enough eligible cofounders present, and a fresh party (`src/main/java/net/server/channel/handlers/GuildOperationHandler.java:124-159`). It then opens a `GUILD_CREATION` match confirmation with a timeout timer (`GuildOperationHandler.java:163-172`).
2. Each cofounder answers via `0x1E`, which either joins the party or records the answer (`GuildOperationHandler.java:307-332`). When everyone has answered, `MatchCheckerGuildCreation` inserts the guild, charges the meso, loads it through `Server.getGuild(leader.getGuildId(), leader.getWorld(), leader)` and sets the leader to rank 1 (`src/main/java/net/server/coordinator/matchchecker/listener/MatchCheckerGuildCreation.java:114-124`).
3. Invite `0x05` first repairs the sender's own rank via `mendLeaderRank`, then requires guild rank ≤ 2 and hands off to `Guild.sendInvitation` (`GuildOperationHandler.java:176-185`; `src/main/java/net/server/guild/Guild.java:637, 782`).
4. Join `0x06` rejects an existing guild and a mismatched chrId, then `joinAfterInvite` answers the invite and installs guild/rank 5 (`GuildOperationHandler.java:192-204, 90-118`).
5. Quit `0x07` validates cid+name+guildId, broadcasts GP 0, leaves the guild, clears guild id/rank and broadcasts an empty guild name (`GuildOperationHandler.java:208-229`).
6. Expel `0x08` requires rank ≤ 2 and delegates to `Server.expelMember` → `Guild.expelMember`, which requires the initiator's rank to be strictly better than the victim's (`GuildOperationHandler.java:231-244`; `src/main/java/net/server/guild/Guild.java:534-567`).
7. Ranks `0x0d`/`0x0e`, emblem `0x0f` (rank 1, at `MapId.GUILD_HQ`, `CHANGE_EMBLEM_COST`), notice `0x10` (rank ≤ 2, ≤ 100 chars) (`GuildOperationHandler.java:246-306`).
8. `Guild.writeToDB` persists guild columns; disband nulls members' `guildid`/`guildrank` and deletes the row (`src/main/java/net/server/guild/Guild.java:156-186`).

### Alliance

1. Creation is NPC-script-driven: `Alliance.createAlliance(getParty(), name)` requires exactly two guild masters on the leader's map and sets alliance ranks 1 and 2 (`src/main/java/net/server/guild/Alliance.java:86-143`; `src/main/java/scripting/npc/NPCConversationManager.java:751`).
2. `AllianceOperationHandler` first resolves the sender's guild and alliance; anything above alliance rank 2, or a guild not in the alliance, is refused with `enableActions` (`src/main/java/net/server/channel/handlers/AllianceOperationHandler.java:45-67`).
3. `0x02` leave, `0x03` invite, `0x04` accept (rank 1, re-checks capacity, sets alliance rank 2 on both the sender and guild members) (`AllianceOperationHandler.java:75-138`).
4. `0x06` expel guild, `0x07` change alliance leader, `0x08` rank titles, `0x0A` notice all require alliance rank 1; `0x09` (change a member's alliance rank) has no rank check (`AllianceOperationHandler.java:139-219`).
5. `alliance.saveToDB()` runs after every handled case (`AllianceOperationHandler.java:224`).

### Family

1. Every family handler starts with `OpenFamilyHandler.enabledOrExplain`, which either allows the op or sends the "coming soon" notice plus `enableActions` (`src/main/java/net/server/channel/handlers/OpenFamilyHandler.java:41-48`).
2. Invite (`FamilyAddHandler`) resolves the target from channel storage first, and — 1.6.1 — also accepts a bot that offered itself from another map (`src/main/java/net/server/channel/handlers/FamilyAddHandler.java:43-56`). Refusals use `sendFamilyMessage` codes 65/69/72/73/76/77 (`FamilyAddHandler.java:57-72`).
3. Accept (`AcceptFamilyHandler`) runs the whole membership mutation inside `Family.underMembershipLock` (`src/main/java/net/server/channel/handlers/AcceptFamilyHandler.java:74`), then either inserts the junior under the inviter, joins an existing family when generations fit, or creates a brand-new `Family(-1, chr.getWorld())` (`AcceptFamilyHandler.java:80-137`).
4. Separate (`FamilySeparateHandler`) charges meso `2500*levelDiff + levelDiff^2` (`:61-63`) and reputation `((level/20)+10)*level*2` charged in full to the senior and half to the senior's own senior (`:69-73`, formula at `:82-89`); it rejects an edited `0x95` whose fork target is not the sender's own junior (`src/main/java/net/server/channel/handlers/FamilySeparateHandler.java:43-52`).
5. Use (`FamilyUseHandler`) gates on reputation and on the entitlement not already being used, then either travels (reunion/summon) or applies a buff (`src/main/java/net/server/channel/handlers/FamilyUseHandler.java:51-82, 92-155`); `refund` returns the reputation when a summon cannot complete (`FamilyUseHandler.java:197-204`).
6. Summon reply (`FamilySummonResponseHandler`) answers the `FAMILY_SUMMON` invite, returns quietly if the summoner left, and only travels when the summoner is on the same map, otherwise refunds (`src/main/java/net/server/channel/handlers/FamilySummonResponseHandler.java:36-61`).
7. Precepts are leader-only (`src/main/java/net/server/channel/handlers/FamilyPreceptsHandler.java:17-24`); pedigree lookup is channel-scoped (`src/main/java/net/server/channel/handlers/OpenFamilyPedigreeHandler.java:33-41`).

## Data

### In memory

| Store | Type | Where |
| --- | --- | --- |
| char id → party id | `Map<Integer,Integer>` | src/main/java/net/server/world/World.java:117 |
| party id → `Party` | `Map<Integer,Party>` guarded by a fair `ReentrantLock` | src/main/java/net/server/world/World.java:118-120 |
| next party id | `AtomicInteger`, seeded 1_000_000_001 so it cannot collide with char ids | src/main/java/net/server/world/World.java:119, 180 |
| guild id → `Guild` | `Map` in `Server` | src/main/java/net/server/Server.java:1286-1293 |
| guild id → `GuildSummary` cache | `gsStore` | src/main/java/net/server/world/World.java:100 |
| world id → `World` | `worldsById` (lookup, not index) | src/main/java/net/server/Server.java:122, 249 |
| family id → `Family` | `Map` in `World`, synchronized accessors | src/main/java/net/server/world/World.java:97, 621-649 |
| party-search echelons / queue | `Map<Job,…>`, `leaderQueue` under a fair `ReentrantReadWriteLock` | src/main/java/net/server/coordinator/partysearch/PartySearchCoordinator.java:47-55 |

### SQL

| Table | Read | Written |
| --- | --- | --- |
| `characters` (guildid, guildrank, allianceRank, party, familyid) | guild members src/main/java/net/server/guild/Guild.java:95; party at login src/main/java/client/Character.java:8535 | party in the main save `Character.java:9907`; guildid/guildrank offline `src/main/java/net/server/world/World.java:720-729`, online via `saveGuildStatus` (`World.java:749`); allianceRank src/main/java/net/server/guild/Guild.java:897; familyid src/main/java/net/server/channel/handlers/AcceptFamilyHandler.java:161 |
| `guilds` | `SELECT * FROM guilds WHERE guildid = <concat>` src/main/java/net/server/guild/Guild.java:72 | INSERT src/main/java/net/server/guild/Guild.java:470; UPDATE built at `Guild.java:161-165`; DELETE `Guild.java:186`; allianceId `Guild.java:874` |
| `alliance` / `allianceguilds` | src/main/java/net/server/guild/Alliance.java:191, 214 | src/main/java/net/server/guild/Alliance.java:160, 170, 232, 246, 252, 266, 271, 285 |
| `family_character` | `SELECT *` src/main/java/client/Family.java:266 | INSERT src/main/java/net/server/channel/handlers/AcceptFamilyHandler.java:152; updates src/main/java/client/FamilyEntry.java:192, 419, 637 |
| `family_entitlement` | read through `FamilyEntry` state | INSERT src/main/java/client/FamilyEntry.java:578; DELETE on refund src/main/java/client/FamilyEntry.java:592 |
| `family_buffs` | src/main/java/client/FamilyBuffs.java:253 | `SAVE_SQL` INSERT src/main/java/client/FamilyBuffs.java:214; DELETE src/main/java/client/FamilyBuffs.java:234; expiry sweep src/main/java/client/FamilyBuffs.java:247 |

Party membership is **never** stored as a party table: only the `characters.party` integer survives a restart, and the party object itself is rebuilt only if the id still exists in `World.parties` at login (`src/main/java/client/Character.java:8535-8543`) — otherwise the character logs in partyless with the stale column left in place.

Guild members are rebuilt from `characters` plus `BotGuilds.guildCharacters` appended and re-sorted (`src/main/java/net/server/guild/Guild.java:95-109`); bot members never appear in `characters`.

## Switches

| Switch | Kind | Default | Read at | Live / TEST |
| --- | --- | --- | --- | --- |
| `slums.guild.createTimeoutMs` | `-D` | 60_000 ms | src/main/java/net/server/channel/handlers/GuildOperationHandler.java:52 | not in docs/release/1.6.4.conf → default |
| `slums.family.bondingMin` | `-D` | 6 (floor 1) | src/main/java/net/server/channel/handlers/FamilyUseHandler.java:242 | default |
| `USE_FAMILY_SYSTEM` | config.yaml | false (no initialiser) | src/main/java/config/ServerConfig.java:309 | live `true` (docs/release/1.6.4.conf:204); TEST unknown |
| `USE_PARTY_FOR_STARTERS` | config.yaml | false | src/main/java/config/ServerConfig.java:321 | absent from live conf → default; TEST unknown |
| `PARTY_SEARCH_REENTRY_LIMIT` | config.yaml | 10 | src/main/java/config/ServerConfig.java:414 | default (no conf line); TEST unknown |
| `CREATE_GUILD_MIN_PARTNERS` | config.yaml | none (0 unless set) | src/main/java/config/ServerConfig.java:494 | live 1 per docs/tickets/50-guilds.md:19; TEST unknown |
| `CREATE_GUILD_COST` | config.yaml | none | src/main/java/config/ServerConfig.java:495 | live 1_500_000 per docs/tickets/50-guilds.md:19 |
| `CHANGE_EMBLEM_COST` | config.yaml | none | src/main/java/config/ServerConfig.java:496 | unknown |
| `FAMILY_REP_PER_KILL` / `FAMILY_MAX_GENERATIONS` | config.yaml | 4 / 1000 | src/main/java/config/ServerConfig.java:506, 509 | default |
| `CHANNEL_LOAD` | config.yaml | none | src/main/java/config/ServerConfig.java:24 | live 125 with 5 channels (docs/release/1.6.4.conf:277-278) |

The guild-cost and party-search keys appear in neither `ServerConfig` initialisers nor `docs/release/1.6.4.conf`, so the running value comes from the live `config.yaml` (not read here); TEST is unknown.

## Invariants

| # | Invariant | Evidence |
| --- | --- | --- |
| I1 | **The party leader check is the only thing standing between a crafted `PARTY_OPERATION` 0x06 and a leadership takeover.** b298 added `if (party == null \|\| !player.isPartyLeader())` plus a target null-guard; before it, any member could hand leadership to itself, and a partyless sender NPE'd. | src/main/java/net/server/channel/handlers/PartyOperationHandler.java:138-147; card b298 at /root/work/STATUS-1.6.4.md:48 |
| I2 | **Alliance mutating opcodes must be gated on alliance rank 1 *and* on the target guild actually belonging to the alliance.** b298 added the containment half for 0x06 and the rank half for 0x06/0x07/0x08/0x0A; 0x09 was left rank-2-allowed deliberately. | src/main/java/net/server/channel/handlers/AllianceOperationHandler.java:67, 147, 168, 184, 210 |
| I3 | `isPartyLeader()` is `party != null && party.getLeaderId() == getId()` under `prtLock`; it is the single leader predicate the handlers rely on. | src/main/java/client/Character.java:7590 |
| I4 | A party never exceeds `MAX_MEMBERS` because the check lives inside `addMember` under the party's fair lock; every caller-side `size()` test is a hint. | src/main/java/net/server/world/Party.java:59, 75-81, 369 |
| I5 | **World id is not assumed to be 0 anywhere, but it is *not* stored on a guild either.** `Guild` captures whatever world id its first loader passed (`Server.getGuild(id, world, mc)` → `new Guild(id, world)`), has no `world` column, so the value is frozen by the first caller. `Alliance` carries no world at all and each packet derives it from a member. | src/main/java/net/server/Server.java:1288-1293; src/main/java/net/server/guild/Guild.java:72-92; src/main/java/net/server/guild/Alliance.java:143 |
| I6 | **The world-0 guild rank bug:** a `GuildCharacter` built for an offline member used to keep world 0, so on the live world-1 server `Guild.changeRank` looked up a non-existent world 0, threw, and a new guild's master stayed at rank 5. Fixed by setting the world for offline members and by `worldServer(mgc)` falling back to the member's own world when the guild's world is not running. | src/main/java/net/server/guild/GuildCharacter.java:52-59; src/main/java/net/server/guild/Guild.java:592, 618-631; commit bc500b87e |
| I7 | Live runs world **1** only; a `Starting world 0` log line is treated as an incident. Ranking code therefore offsets by `getWorldIdBound()`, not by a constant. | docs/release/1.6.4.conf:295; docs/tickets/41-world-name-bera.md:99-100; src/main/java/net/server/Server.java:295-302, 824 |
| I8 | Family membership mutations are serialised by `Family.underMembershipLock`; concurrent accepts cannot double-insert. | src/main/java/net/server/channel/handlers/AcceptFamilyHandler.java:74 |
| I9 | Every family op is gated on `USE_FAMILY_SYSTEM` through one helper, so disabling the system cannot leave one opcode live. | src/main/java/net/server/channel/handlers/OpenFamilyHandler.java:41-48 |
| I10 | A party leader only leaves the party as a disband; there is no "leader leaves, another takes over" path except `assignNewLeader`. | src/main/java/net/server/world/Party.java:275, 426-450 |

## Origin

| Area | Classification | Evidence |
| --- | --- | --- |
| `Party`, `Alliance`, `GuildPackets`, `PartyCharacter`, `PartySearchTask` | base, unmodified | `diff -q` against /root/workspace/cosmicms is identical for all four; oldest commit 03d13cb21 (repack baseline) |
| `net/server/world/Party.java` | modified (66 lines) | 03d13cb21 baseline then c8a813448, 95e8164d0, 35f27c2c6; the "ticket 58" hook at `Party.java:383-399` is the only lineage comment |
| `net/server/channel/handlers/PartyOperationHandler.java` | modified (27 lines) | 03d13cb21, c8a813448 2026-10-23, 95e8164d0 2026-10-28, 35f27c2c6 2026-10-30; b298 branch 164-b298 (`8e641a07d`) |
| `net/server/guild/Guild.java` | modified (144 lines) | 03d13cb21, ad0359efc, 086880a8d 2026-10-21, bc500b87e 2026-10-23 (world-1 rank fix), 1028bfc0a |
| `net/server/guild/GuildCharacter.java` | modified (4 lines) | 03d13cb21 then bc500b87e only; the `SlumsMS rc6` comment is the change |
| `net/server/channel/handlers/GuildOperationHandler.java` | modified (95 lines) | 03d13cb21, ad0359efc (bot guild creation), ade430132 (QA suite) |
| `net/server/channel/handlers/AllianceOperationHandler.java` | modified (27 lines) | 03d13cb21 then b298 |
| `net/server/channel/handlers/FamilyAddHandler.java` | modified (25 lines) | 03d13cb21, 5ab28f6c1 (coming-soon notice), 68d88c19b 2026-10-27 (family on, bots as juniors) |
| `net/server/channel/handlers/AcceptFamilyHandler.java` | modified (35 lines) | 03d13cb21, 68d88c19b |
| `net/server/coordinator/partysearch/PartySearchCoordinator.java` | modified (60 lines) | 03d13cb21, 7b458ad19 2026-11-01 (vicinity fix), 0fbb4d0f1 2026-11-01 |
| `net/server/guild/Alliance.java` | base (unmodified) | `diff -q` identical to cosmicms; only commit 03d13cb21 |

`tools/cosmicdiff/classifications.json` contains no entry for any path in this subsystem (a grep for `guild/`, `world/Party`, `PartySearch`, `Family` matches one unrelated line), so the base-vs-custom call rests on `diff -q` and the oldest-commit record only.

## Tests and QA

| Layer | Coverage |
| --- | --- |
| Unit | net/server/channel/handlers/PartyOperationHandlerTest.java:35 `aNonLeaderCannotChangeThePartyLeader`, :53 `aPartylessSenderIsRefusedInsteadOfThrowing`, :69 `theLeaderCanStillHandLeadershipOver` |
| Unit | net/server/channel/handlers/AllianceOperationHandlerTest.java:100 `aRankTwoSenderCannotExpelAGuild`, :110 `theLeaderCannotExpelAGuildOfAnotherAlliance` |
| Unit | net/server/world/UpdatePartyMissingPartyTest.java:22 `updateForAPartyTheWorldNoLongerHoldsChangesNothing` |
| Unit | net/server/guild/GuildCharacterWorldTest.java:14 `anOfflineMemberKeepsItsWorld`, :21 `anOnlineMemberStillGetsItsChannel`; net/server/guild/GuildChangeRankWorldTest.java:29 `rankChangeGoesThroughTheMembersWorldWhenTheGuildWorldIsNotRunning` |
| Unit | net/server/coordinator/partysearch/PartySearchVicinityTest.java:63-80 (four vicinity cases), PartySearchReentryLimitTest.java:56-72, PartySearchEchelonConcurrencyTest.java:41 |
| Unit | client/FamilyBuffsTest, FamilyDissolveSafetyTest, FamilyMissingLeaderTest, FamilyOffscreenRepTest, SilentPartyUpdateTest, config/FamilyConfigDefaultsTest |
| Unit | net/server/channel/handlers/{FamilyAcrossChannelsTest,FamilyOfferFlowTest,FamilySummonRegularTest,FamilyTravelFieldsTest,OpenFamilyHandlerTest}, server/life/MonsterFamilyRepTest, scripting/FamilyQuestScriptsTest |
| Integration | database/FamilyBotJuniorsMigrationIT, database/FamilyBuffsMigrationIT |
| Auto-QA | `t-gu-1` :205, `t-gu-3` :228, `t-gu-4` :238, `t-gu-5` :244, `t-gu-8` :252, `t-gu-2` :273, `t-gu-6` :282, `t-gw-1` :289, `t-gw-2` :298, `t-gw-3` :306, `t-gw-4` :310, `t-pb-1` :316, `t-fy-1` :1307, `t-fy-2` :1315, `t-fy-3` :1320, `t-fy-4` :1324 (all in src/main/java/soloMapling/autoqa/AutoQaCatalog.java; there is no `t-gu-7`) |
| `!qa` scenarios | gd-1 (guild creation) src/main/java/soloMapling/qa/QaScenarios.java:418; fy-1 src/main/java/soloMapling/qa/FamilyScenarios.java:30; gu-1..gu-8 src/main/java/soloMapling/qa/GuildScenarios.java:112, 137, 174, 203, 234, 295, 332, 350; gw-1..gw-4 src/main/java/soloMapling/qa/DramaScenarios.java:498, 532, 564, 598 |

**Not covered:** no test or auto-QA check exercises `PartySearchCoordinator.runPartySearch` end to end, `PartySearchStartHandler`/`PartySearchUpdateHandler` at the packet layer, party leave/expel, alliance leave/leader-change/member-rank, guild quit/expel/emblem/notice against a live world, or `GuildOperationHandler` 0x08 (its NPE path below). Party-side coverage is via bot tests (BotPartySystem, LfgPartyTest, PartyChatRouteTest) which drive the same handlers but assert bot behaviour, not the packet contract. GuildQuest has no row and has never been walked end to end (`/root/work/PQ-UNFINISHED-REPORT.md:325-341`).

## Known issues

| Id | One line |
| --- | --- |
| b298 | S2: crafted party/alliance packets skipped leader/rank checks. Fixed in 2 commits (`50ca3014d`, `8e641a07d`) — party 0x06 leader check, alliance rank 1 for 0x06/0x07/0x08/0x0A plus guild containment for 0x06; 4 fail-before tests. Outstanding: `0x07` still NPEs when the incumbent alliance head is offline (src/main/java/net/server/channel/handlers/AllianceOperationHandler.java:227-230) and a leader can still expel their own guild via `0x06`. |
| b295 | Party heal applies even when the caster is refused (combat-side, surfaced here because party membership is what gates it). |
| e02 | Owned alts accepting their owner's invites exists for family and guild (`OwnedAltInvite.acceptFamily`, `GuildOperationHandler.joinAfterInvite`) but the ticket is still open for the general case. |
| L09 | Lane: bots run party quests and boss expeditions; party-slot accounting for bots is part of that lane. |
| NEW-FINDING | src/main/java/net/server/channel/handlers/GuildOperationHandler.java:232 reads `mc.getGuild().getAllianceId()` *before* the rank/guild guard at :236, so a crafted `0x08` from a sender with no resolvable guild throws NPE instead of being refused. 0x07 was given the guard first (:211-216) and 0x08 was not. |
| NEW-FINDING | src/main/java/net/server/channel/handlers/PartyOperationHandler.java:49-152 has no `default` branch — an unknown operation byte is dropped with no `enableActions`, which leaves the client waiting. The b298 refusals do send it (:139). |
| NEW-FINDING | src/main/java/net/server/channel/handlers/PartySearchStartHandler.java:66-67 returns silently when the sender is not the party leader (or has no party) and sends no `enableActions`, unlike every other refusal in this subsystem. |
| NEW-FINDING | src/main/java/net/server/world/World.java:1055 `Character mc = party.getLeader().getPlayer();` is null-checked at :1056, but `Party.getLeader()` itself returns null when `leaderId` matches no member (`src/main/java/net/server/world/Party.java:192-205`), and that call is not guarded — a party whose leader row is missing NPEs inside `updateParty`, which is reached from `assignNewLeader` (`Party.java:291`) and every login party refresh. |
| NEW-FINDING | src/main/java/net/server/guild/Guild.java:72 builds `"SELECT * FROM guilds WHERE guildid = " + guildid` by concatenation while every other statement in the class is parameterised; the id is an int so it is not injectable today, but the pattern invites the next copy to be. |
| NEW-FINDING | src/main/java/net/server/world/Party.java:105 `setLeader` takes neither the party lock nor a membership check; only `World.updateParty`'s CHANGE_LEADER branch calls it, so the safety is entirely in the caller. |

## State

| Area | State | Evidence |
| --- | --- | --- |
| Party create/join/invite/leave/expel | working | exercised by PartyOperationHandlerTest and the bot party suites; membership cap and lock prove concurrency safety (`Party.java:75-81`) |
| Party change-leader | working for an online sender and target; broken with an offline incumbent | `PartyOperationHandler.java:138-150` is green in the suite; `World.java:1054` NPE risk |
| Guild create/invite/join/quit/rank/notice | working | `MatchCheckerGuildCreation` + GuildOperationHandler tests + gu-1..gu-8; `GuildChangeRankWorldTest` covers the world fallback |
| Guild expel (0x08) from a guildless sender | broken | NEW-FINDING at `GuildOperationHandler.java:232` |
| Guild world binding | working but fragile | fixed by `Guild.worldServer(GuildCharacter)` (`Guild.java:624-631`); the world id is still frozen at first load (`Server.java:1293`) |
| Alliance ops | partial | b298 closed 0x06/0x07/0x08/0x0A; 0x09 still rank-2-allowed by design; `0x07` offline-incumbent NPE open (`AllianceOperationHandler.java:227-230`) |
| Family | working (live-enabled) | `USE_FAMILY_SYSTEM=true` on live (docs/release/1.6.4.conf:204); family tests + t-fy-1..t-fy-4 green per the 1.6.4 tests |
| Party search | partial | coordinator is unit-tested (vicinity, re-entry, concurrency) but no end-to-end check; `PARTY_SEARCH_REGISTER` is an intentionally empty handler (`PartySearchRegisterHandler.java:50`) |
| Party persistence | working by design (memory-only) | `World.java:117-120`; `characters.party` written at save (`Character.java:9907`) and read at login (`Character.java:8535`) |

## Open questions

- Live runs world 1 (`docs/tickets/41-world-name-bera.md:99-100`) and the world-0 fallback in `Guild.worldServer(mgc)` only helps *online* members (`Guild.java:624-631`). Whether an offline-member rank change on a world that is not running yet is fully safe was not testable here.
- `Alliance` has no stored world id; `AllianceOperationHandler` derives packet worlds from the acting guild (`Alliance.java:143`, `AllianceOperationHandler.java:155-160`). With more than one world configured, two guilds from different worlds in one alliance would produce packets addressed to the wrong world — whether that is reachable depends on deployment, and `alliance` / `allianceguilds` rows carry no world column at all (`src/main/java/net/server/guild/Alliance.java:160-170`).
- The real values of `CREATE_GUILD_MIN_PARTNERS`, `CREATE_GUILD_COST` and `CHANGE_EMBLEM_COST` on TEST come from that host's `config.yaml`, which is not reachable from this worktree; only the live values from `docs/tickets/50-guilds.md:19` are recorded above.
- `PartySearchStartHandler.java:62` reads and discards an int labelled `members`; whether the client sends a meaningful member count that the coordinator should honour (the coordinator re-counts the party itself at `PartySearchCoordinator.java:441`) is undetermined.
- `AllianceOperationHandler.java:159` resolves the expelled guild through `Server.getGuild(int)`, which only reads the cache and returns `null` for a guild that was never loaded (`src/main/java/net/server/Server.java:1272-1279`); the guild is still cached in the normal path because `Server.removeGuildFromAlliance` only clears its allianceId (`Server.java:1219-1227`), but a guild the server never loaded takes the NPE.
