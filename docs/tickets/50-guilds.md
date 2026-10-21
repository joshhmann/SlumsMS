# 50: Guilds: fix creation near bots, then bot-run guilds and guild chat

**Area:** Server / Real bots / Social
**Kind:** FIX (part A), BUILD (parts B and C)
**Effort:** A: S. B and C: L
**Depends on:** A: none. B and C: 49 (botstore name fallback and login notify), 36/37
**Status:** open

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
