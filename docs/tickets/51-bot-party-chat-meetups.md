# 51: Parties with bots: party chat, meet-ups, bots inviting buddies

**Area:** Real bots / Companions
**Kind:** BUILD
**Effort:** S-M
**Depends on:** 14 (command parser), 49 (buddies, for invites by buddy bots)
**Status:** open

## Today

- **Players:** party code is stock, plus the race-proof 6-member cap (`Party.MAX_MEMBERS`).
- **Bots:**
  - A player asks a bot in map chat. `BotRecruitManager` rolls accept or decline and arms an accept window.
  - The player invites; `PartyOperationHandler` hands the invite to `BotPartyQueue`, and the bot accepts and becomes
    a `FollowerBot`.
  - Ticket 14's orders (`jason follow`, `1 stop`, `grind`) work in **map chat**.
- **Missing:**
  - Party chat (`MultiChat` type 1) is not routed to bots, so orders and talk typed in party chat do nothing.
  - Bots never invite a player.
  - No meet-up exists outside the follower flow.

## What

- **Party chat to party bots:** run it through the same order parser first (ticket 14), then `BotConversation`.
  Answers go to party chat, one bot per line.
- **Bot buddies invite:** a buddy bot (ticket 49) that is online in the same channel may invite its player buddy to
  hunt together. It is rare, it is asked in buddy chat first ("wanna party?"), and it is never sent unprompted to a
  player who declined.
- **Meet-ups:**
  - "meet me in henesys / come to kerning" from a party or buddy bot becomes a travel goal (ticket 03's world graph)
    and then a follow.
  - It gives up with an in-character line after a timeout.
- **Bot-to-bot parties:** stay as today (OPQ path and social bots). This ticket adds no new bot-only parties.

## Done when

- `1 stop` in party chat stops slot 1, and a question in party chat gets one in-character answer.
- A buddy bot can ask, invite and party with its player. A declined invite is not repeated that session.
- "meet me in Henesys" brings a party bot to Henesys, or it says it cannot.

## Risks

- LLM cost per party line: rate-limit and prefer scripted replies.
- Never reveal AI.
- Do not pull a grinding bot across the world for a single-word line. Confirm first ("omw?").
