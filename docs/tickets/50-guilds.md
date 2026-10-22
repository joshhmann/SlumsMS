# 50: Guilds: fix creation near bots, then bot-run guilds and guild chat

**Area:** Server / Real bots / Social
**Kind:** FIX (part A), BUILD (parts B and C)
**Effort:** A: S. B and C: L
**Depends on:** A: none. B and C: 49 (botstore name fallback and login notify), 36/37
**Status:** A built (rc1). B and C built for 1.5.0 on `bots-guilds-150` with the owner's grill decisions (2026-10-21, below)

Research: [docs/real-bots/social-systems-research.md](../real-bots/social-systems-research.md).

## Today

- **Code:** stock Cosmic.
  - Handlers: `GuildOperationHandler`, `AllianceOperationHandler`, `BBSOperationHandler`, `DenyGuildRequestHandler`.
  - Services and scripts: `Guild`, `Alliance`, `GuildPackets`, NPCs 2010007 (Heracle), 2010008 (emblem) and 2010009
    (Lenario, alliance), and `GuildQuest.js` (GP).
  - The client supports all of it natively, and the SlumsMS DLL does not touch it.
- **Live:** 0 guilds, 0 alliances, 0 characters in a guild.
  - Config: `CREATE_GUILD_MIN_PARTNERS: 1` (Cosmic default 6), `CREATE_GUILD_COST: 1500000`, expansion 500k-5m.
- **Likely bug for players:** `Guild.getEligiblePlayersForGuild` adds **every** character on the leader's map that
  is not in a party or guild, bots included.
  - `MatchCheckerCoordinator.createMatchConfirmation` then waits for all of them to accept. It has no timeout, and
    bots never answer.
  - So a player trying to create a guild with any bot standing in the Guild Headquarters (200000301) is stuck in
    the confirmation until someone leaves the map or logs out.
  - Not yet reproduced in game.
- **Bots:**
  - A guild invite sent to a bot expires silently.
  - Guild chat (`MultiChat` type 2) is not routed to bots.
  - Bots have no guild state. Members load from `characters.guildid`, which bots do not have.

## What

### A. Fix creation (S)

- Leave bots out of `getEligiblePlayersForGuild` with `BotHelpers.isBot`.
- Add a 60 s timeout to the guild-creation match, so one silent player cannot wedge it.
- Re-check `CREATE_GUILD_MIN_PARTNERS: 1` with the owner. 1 means a solo founder, which suits a small real-player
  base.

### B. Bot-run guilds players can join (L)

- **Store:**
  - A handful of bot guilds (5-15, from FM clan names in `FMNameDesc/FMClans.txt`) are real `guilds` rows, so names
    are reserved and the ranking NPC shows them.
  - Bot membership lives in a new `botguildmember (BotId, GuildId, Rank)` table.
  - `Guild` member loading unions `characters` with that table plus `botstore` for name, level and job.
  - The leader is a bot. Players join by invite (a bot officer invites a player it knows, or on request in chat:
    "can i join ur guild?").
- **Size:** about 10-30 bots per guild, so the guild list stays readable and online counts look real.
- **Bot login and logout:** notify the guild (online dot) through the stock `World` guild member update path. Batch
  it.
- **GP:** bots add small GP for levels and quests. GPQ stays player-driven.
- **Emblems:** set once at creation from the stock emblem ids.
- **Leaving and kicking:** a player can leave freely. Bots never kick players, except a GM tool.
- **Player-founded guilds:** a player can invite a bot that knows them. The bot accepts on the same rules as buddies
  (ticket 49) and joins `botguildmember`. Cap the bots per player guild (about 5) so player guilds stay mostly
  players.

### C. Guild chat (part of L)

- Route `MultiChat` type 2 to the guild's online bots.
  - At most one bot answers a line: the one addressed by name, or one picked by the Decide gate.
- Bots post their own guild chatter: a level-up "gz", "anyone for pq", drop brags.
  - A per-guild rate limit (for example 1 line per 5-10 minutes, with idle hours silent).
  - Scripted lines first; LLM only for direct replies.

## Done when

- **A:** a player with bots standing in 200000301 can create a guild. A unit test shows bots are not eligible
  cofounders.
- **B:**
  - A player joins a bot guild and sees bot members with real levels and jobs and online dots that match the roster.
  - It survives a restart, and the ranking NPC shows the guild.
- **C:** guild chat gets at most one bot answer per line, and idle bot chatter stays under the rate limit.

## Risks

- **Packet spam:**
  - Every member login or logout sends a guild member update to every online member. Bot guilds of 30 with roster
    churn produce a steady stream, so batch it or send only on a real state change.
  - Never put hundreds of bots in one guild.
- **DB growth:** small. 15 guilds × 30 bots.
- **LLM cost:** guild chat reaches every member. Keep bot replies to one per line, mostly scripted.
- **Privacy:** guild chat is semi-private. Bots must not reveal they are AI, or pass guild chat on as `BotGossip`
  hearsay without a filter.
- **Consent:** bots never invite players who never talked to them, and they never kick players.

## Owner decisions (grill, 2026-10-21) - these override the design above where they differ

1. About 10 bot guilds of 15-40 members (a few big, mostly mid-size, a couple of tiny new ones), each with a name, an
   emblem, a leader and a vibe, drawn from the roster. Real stock guilds. Members talk in guild chat (rate-limited)
   and party up to grind. Leaders recruit with megaphones (within the gate) and whisper players they like.
2. Friend bots accept a player's guild invite and act as members. Soft limit: past half bots, bots get pickier.
   Players can apply to a bot guild; the leader decides by affinity and level through the normal invite flow. A bot
   quits if the guild's players become its rivals, or if ignored for weeks.
3. Light rivalry: a couple of pairs of rival bot guilds (FM trash talk, the same grind maps, megaphone jabs). No wars,
   no alliances.
4. Boss runs and the Guild PQ are staged in 1.5.0 (gathering, announce, off-screen run, believable result; loot
   economy-safe). A player can join for real; bots fight simply. Real boss AI in 1.5.x.
5. Partying with players: friends accept party invites, follow and train with you; neutral sometimes; rivals never.
6. GM commands and `!qa` scenarios for all of it.

## Built (1.5.0, branch `bots-guilds-150`)

Code: `src/main/java/soloMapling/ArtificialPlayer/BotGuildSystem/` (`BotGuilds`, `GuildRules`, `GuildPlan`,
`GuildStore`, `GuildBossRuns`, `GuildStaging`, `GuildLines`), lines in `BotDialoguePack/GuildDialogue.yaml` and five
`Guild*` nodes in `MegaphoneDialogue.yaml`, migration `014-bot-guilds`. Deploy: [1.5.0-bot-guilds-deploy.md](../release/1.5.0-bot-guilds-deploy.md).

- **Store:** bot guilds are stock `guilds` rows (name reserved, emblem, notice, capacity with room for players, some
  GP). `botguild` holds the vibe, rival and size; `botguildmember (BotId, GuildId, GuildRank, JoinedAt, AttentionAt)`
  holds every bot in any guild. `Guild`'s member load adds the bot members (name, level, job from `botstore`), so
  the guild list shows them with offline/online dots. On-demand (staged) bots are members in memory only.
- **Founding:** the first guild step with migration 014 and 40+ roster bots founds the ten guilds of
  `GuildPlan.SPECS` (Legendz hardcore 38, MoonLiteX casual 34, MesoMafia merchant 26, SlumLords social 24,
  ZakumSlayerz pvm 24, KoreanBBQ social 22, SnailGang casual 20, Pwnage hardcore 18, NoobTeam newbie 8, HenesysKidz
  newbie 6). Bots come from each guild's level band; the strongest leads, two officers (rank 2), the next fifth rank
  3. A small roster shrinks the guilds to half of it at most. Rival pairs: Legendz/ZakumSlayerz, MoonLiteX/SlumLords.
- **Bot login/logout** (`BotGeneration`): a member takes its guild before it shows on the map (name and emblem over
  its head), and goes online/offline on the guild list. A bot's online dot is only broadcast to a guild with a real
  player online (`Guild.setOnline`), so roster churn costs nothing elsewhere.
- **Guild chat** (`MultiChatHandler` type 2): at most one bot answers a player's line, through the chat brain (named
  bot, else 60% for a question/greeting, 25% otherwise, one answer per guild per 20 s). Idle chatter is scripted, per
  vibe, one try per guild per 8 min, only while a real member is online and the population curve is up. A bot says gz
  on a real member's level-up, hi when one logs in (once per 2 h), welcome when a player joins. The chat prompt knows
  the bot's guild, rank, vibe and rival.
- **Parties to grind:** every 10 min a bot guild partys 2-3 of its online training members on one channel (within 20
  levels, led by the strongest); the others train on the leader's map (`TrainingBot.partyTargetMapId`).
- **Recruiting:** a guild with room, every ~6 h (half the time): its leader or an officer posts `GuildRecruitCall`
  (gate), a member standing in Henesys or the FM says it out loud where a player sees, and members whisper guildless
  online players they count as friends, followed by a real guild invite (a player at most every 3 days, 4 an hour
  world-wide).
- **Players apply:** "can i join ur guild?" to a member (map chat or whisper) is answered before the chat brain: the
  leader (online) or the asked bot decides (`GuildRules.application`: no to a rival, a full guild or a player in a
  guild; yes to a friend; else the guild's level floor - 50 for hardcore/pvm - and an 80% roll). On a yes the leader,
  an officer or the bot sends a real invite through `InviteCoordinator` a few seconds later. A bot in a player's guild
  says to ask the leader.
- **Players invite bots** (`Guild.sendInvitation`): the bot answers in 5-40 s. Only a friend says yes (95%); past half
  bots the chance falls (60% bots ~57%, 70% ~19%, never under 5%). A no is the stock "denied" message.
- **Quitting** (every 10 min per guild with a real member online, rank 1 never): a bot leaves when it is a rival of
  the player leader or of at least half the guild's online players it has feelings about, or - player-led guilds only
  - when it joined 21+ days ago and nobody of the guild did anything friendly with it for 21 days (`AttentionAt`, kept
  by positive affinity events and guild chat answers). It says why in guild chat. Expelling a bot, a disband and rank
  changes are kept too.
- **Rivalry:** where members of a rival pair stand on the same map a player sees, one jabs (map chat), maybe one jabs
  back - every 15 min per pair; a megaphone jab every ~8 h (gate); now and then one guild's party leader goes to grind
  on the rival's party map for 25 min (`WorldEventSteer.hold`). Never at players.
- **Rival players in a bot guild** (owner, 2026-10-21 follow-up): the bot leader kicks an online player member who is
  its rival or nemesis, or a rival of most of the bot members with feelings about them (`GuildRules.kick`), after an
  in-character whisper (`KickLeader` / `KickMembers`), through the stock expel. Checked every 10 min per bot guild
  with a player online; one kick per guild per 30 min, 3 an hour world-wide; `@botdrama off` players are never
  kicked (their feelings read neutral). Logged as `KICK` in `BOT_GUILD`.
- **Ambient guild megaphones:** the ticker's `GuildRecruitment` category now recruits for a real bot guild with room,
  shouted by one of its online members (`GuildDialogue.yaml` `AmbientRecruit`); with no bot guild it sends nothing.
- **Names** (owner, 2008 v83 style; all pass the 3-12 letters rule): Lionheart -> Legendz, Moonlight -> MoonLiteX,
  GoldenBazaar -> MesoMafia, SlumLords, Nightfall -> ZakumSlayerz, MapleLeaf -> KoreanBBQ, RedSnails -> SnailGang,
  Ironclad -> Pwnage, Sprouts -> NoobTeam, DawnRunners -> HenesysKidz.
- **Staged boss runs** (`GuildBossRuns`, one at a time, a guild every 8 h at most, evenings): GPQ (30+), Zakum (50+),
  Papulatus (70+), Horntail (110+), picked by who is online. 3-6 free roster members gather at the entrance on one
  channel (4 min, visible, chat), go in (they log out where they stand; the director leaves them out), come back after
  25 min (they log back in at the entrance), and one posts the result in guild chat and by megaphone (clear 65-90% by
  level margin). Loot is only named ("zhelm"); no item is made, so nothing reaches the economy. On a clear the guild
  gains 150 GP. A player can join for real: a crew member at the entrance always takes a real player's party invite,
  becomes a follower and goes in with them (simple follower fighting; no boss AI).
- **Partying with players** (`BotRecruitManager.armIfWelcome`): an unasked party invite from a real player is taken by
  a friend (100%), a guildmate (90%), a boss-run crew member (100%), a neutral bot (30%, a no holds 3 min), never by a
  rival. A social bot then follows the player, a training bot trains on their map. PQs: no new PQ AI. A follower
  next to the party leader at a KPQ/LPQ NPC should be registered with the party (Cosmic's `registerParty` takes every
  member on the map; not checked on a real client); it does no stage work. OPQ steps exist only for the OPQ lobby
  bots (`BotTypes/OPQ`).
- **GM** (`!botguild`, GM 4): `list`, `info <guild>`, `form <name> [vibe] [size]`, `stage [n] [join] [neutral|rival]
  [guild]`, `unstage`, `recruit <guild>`, `jab <guild>`, `bossrun <guild> [boss] [fast]`, `bossstop`,
  `quit <bot> [rivals|ignored]`, `join <bot> <guild>`, `kick <guild>`, `step`.
- **`!qa`:** `gu-1` (apply to a bot guild), `gu-2` (invite bots, soft limit), `gu-3` (recruit), `gu-4` (rivalry),
  `gu-5` (staged boss run), `gu-6` (a bot quits), `gu-7` (party invites by affinity), `gu-8` (a bot guild kicks a rival player). Log topic `BOT_GUILD`.
