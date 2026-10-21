# 58: Affinity - relationships between bots and with players

**Area:** Real bots / Social
**Depends on:** 49 (buddies), 50 (guilds), 52 (marriage), 21 (world events), 26/36/37 (chat), 47 (megaphone content)
**Status:** open (design, owner idea 2026-10-21)

Owner: "an affinity system? Friends, enemies, nemesis, guild beef, guild alliances, KS wars, friends to lovers to
marriage." The aim: the world remembers who gets along with whom, and that shows up in play - who greets whom, who
parties, which guilds feud, who ends up married at Amoria. It is the spine the social tickets (49-54) hang from.

## Today (what it builds on)
- `BotMemory`: short notes a bot keeps about a player, across restarts (wary notes exist).
- `BotGossip`: notes spread bot to bot as hearsay, a few hops deep.
- `BotRecognition`: a bot greets a player it knows; never one it is wary of.
These are about bot -> player, as text. Nothing scores a relationship, nothing links bot -> bot, nothing moves it.

## What
### 1. Relationship store
- A pair (bot-bot or bot-player) has an **affinity score** (-100..+100) and a **stage** derived from it plus
  history: stranger, acquaintance, friend, close friend, crush, partner, married; rival, enemy, nemesis.
- Scores drift back toward 0 when nothing happens (weeks, not days); a stage needs history to reach (no instant
  best friends, no nemesis from one bad moment).
- Bounded: each bot keeps at most N strong ties (e.g. 20 positive, 5 negative); weak ties are dropped, so the table
  scales with the roster.
### 2. What moves it (events, mostly scripted - no LLM needed)
- Up: partying together, healing/buffing someone, a fair trade, a gift, finishing a PQ or boss together, a long
  friendly chat (the chat brain can tag the conversation's tone), dying side by side, guild membership.
- Down: **KS** (taking a kill someone was fighting), looting another's drops, an unfair trade, rude chat, a broken
  party, rival guild actions.
- Hearsay moves it a little (gossip: "he KSed my friend") - friends of friends inherit a bit of a grudge.
### 3. What it changes (behaviour)
- Friends: warmer greetings, buddy requests accepted (49), party invites, help in fights, follow to a map, show up
  at each other's weddings.
- Rivals / enemies: trash-talk in chat, megaphone beef (rate-limited, ticket 47 content rules), avoid each other's
  maps or channel, **KS wars between bots** (two bots contesting the same mobs - visible, funny, bounded).
- Nemesis: a long-running rivalry storyline between two bots (they race levels, PQ times, boss kills; gossip and
  megaphones follow it).
- Guilds (with 50): a guild's affinity is the sum of its members' ties - alliances form between friendly guilds,
  **guild beef** between hostile ones (GPQ races, map claims, megaphone wars), peace treaties after a while.
- **Friends -> lovers -> marriage (bot-bot):** close friends can become a couple; couples get rings and hold a
  wedding at Amoria as a world event (21/52) players can attend; occasionally a couple breaks up (drama, gossip).
### 4. With real players (opt-in, never forced)
- Bots like or dislike players from what actually happens (party, KS, trades, chat) - shown as greeting tone,
  buddy acceptance, party invites, gossip.
- **No griefing**: bots never KS, loot-steal from or harass a real player as "revenge"; negative affinity with a
  player shows as coolness in chat and avoidance only, unless the player opts into a rivalry (e.g. accepts a "rival"
  challenge) - then it's friendly competition (races, PQ times, megaphone banter), still no griefing.
- **Romance with a player only if the player starts it** (proposes / opts in); bots never initiate romance with
  players; a player can end it anytime. Marriage to a bot uses the normal ring + wedding flow with the player's
  explicit consent at each step.
- Bots never reveal they are AI (owner rule) - relationships are in-character.
### 5. Seeing it
- GM: `!affinity <a> [b]` (score, stage, recent events), `!affinity top` (friendships, feuds, couples).
- Dashboard: a relationship graph (who knows whom, feuds, couples, guild alliances/beef).
- `!qa` scenarios to stage: KS war between two bots, a friendship forming, a guild feud, a bot wedding.

## Phases
1. Store + scripted events (party, KS, trade, heal, chat tone) + bot->player behaviour (greetings, buddy accept).
2. Bot-bot friendships and rivalries visible: gossip, megaphone beef, KS wars between bots.
3. Guild alliances and beef (needs 50 B/C bot-run guilds).
4. Couples -> weddings as world events (needs 52).
5. Player opt-ins: rivalry challenges, romance started by the player.

## Done when
Owner can watch, over a week of play, bots form friendships and feuds, a guild beef flare up and settle, and a bot
couple marry at Amoria - and a player's own actions visibly change how bots treat them, without bots ever griefing.

## Risks
DB growth (bounded ties); megaphone spam (47's gate + caps); bots ganging up on a player (hard rule: no griefing);
romance content stays tasteful (ticket 47 rules); LLM cost (events are scripted; the brain only tags chat tone).
