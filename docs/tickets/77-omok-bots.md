# Ticket 77: Omok and Match Cards that work, then bots who play them

Status: open, target 1.6.0. Owner decision 2026-10-25: bots play each other and accept player challenges.
Part 1 in the 1.5.x bug squash; part 2 with the 1.6.x bot brain.

## Part 1 (1.5.x): the minigames work for players

v83 Omok (Omok Set, items 4080000-4080011) and Match Cards (4080100) rooms: create a room, a second player joins,
ready/start, moves, win/lose/draw/forfeit/time-out, the leave/kick flow, and the W-L-D record. Check our handlers and
`server/MiniGame` against stock Cosmic (MapleRoot may have changed them, see ticket 68), fix what's broken, and add
auto-QA checks with two pretend players (create, join, play a short game to a win, record updated).

## Part 2 (1.6.x): bots play

- Bots open Omok rooms in towns and the Free Market (a few at a time, per-map cap), sit at them and invite passers-by.
- A player can join a bot's room or invite a bot; the bot plays a real game. AI: a simple five-in-a-row heuristic
  (win > block four > make four > block open three ...) with per-bot skill (mistake rate, lookahead) from the bot
  brain's personality, so some bots are easy and some are sharks. Bots also play each other for players to watch.
- Chat: "gg", rematch asks, trash talk within `@botdrama` rules; a win or loss nudges affinity a little.
- Match Cards later, same frame (memory per bot skill).
- Performance: move choice is cheap (15x15 board) and only on the bot's turn.

## Done when

Part 1: two players can finish an Omok and a Match Cards game and records update; auto-QA covers it. Part 2: a player
can walk up to a bot's Omok room in Henesys, play, and get a believable game.

## Decision (Owner decision 2026-10-25, brain grill)

Part 2 is in 1.6.0: bots set up omok/match-card rooms in towns and the FM, play each other, and accept players' challenges (a brain activity under ticket 60). Part 1 (rooms work for players) remains a bug-squash item.
