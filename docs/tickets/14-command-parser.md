# 14: Companion chat commands and menus (party-only)

**Area:** Companions  
**Depends on:** 13  
**Status:** done (branch command-parser, pending in-game check)

## What

Port cosmic_agents' command parser (name, prefix and slot targeting) into BotMenus and FollowerBot.

## Done when

'jason follow', '1 stop' and 'grind' work for party bots only.

## Notes

- Ported from cosmic_agents `server/agents/commands/AgentCommandParser` (target token: full name, prefix of 2+, slot 1-5) as `BotPartySystem.BotCommandParser`, plus a piece-of-name tier (3+ letters, "fire" for xFirex), a trailing target ("stop 1") and everyone-words ("all", "guys", "party"...).
- Orders: follow / come / fm (FOLLOW), stop / stay / wait / hold (STAY, a new FollowerBot phase), grind / train / farm / hunt / station (GRIND = train here). A line is an order only when it is the order plus filler ("pls", "here", "me"...); anything else stays natural chat (Dispatcher.talkNaturally).
- Party bots only (`PartyBotOrders.slots`: the sender's party bots in party order; real players take no slot). Runs first in `Dispatcher.dispatch`; a bot sender is never read as giving orders. A follower obeys only its leader; a partied trainer follows on "follow" and just answers "stop"/"grind". Ambiguous names get "which one? 1 Jason / 3 Jane" in a bubble. Nothing opens the NPC window.
- Not ported: loot and buff orders (no companion loot toggle exists; buffs already go through BotBuffRequestHandler's "hs pls").
