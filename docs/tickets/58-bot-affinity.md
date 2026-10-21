# 58: Affinity - relationships between bots and with players

**Area:** Real bots / Social
**Depends on:** 49 (buddies), 50 (guilds), 52 (marriage), 21 (world events), 26/36/37 (chat), 47 (megaphone content)
**Status:** 1.5.0 part built on `bots-affinity-150` (bot<->player affinity, `@botdrama`, earned speed, bot couples and weddings); bot<->bot friendships, guild beef and player romance stay open

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

## Owner decisions (grill, 2026-10-21) - these override the design above where they differ

1. **1.5.0 is bot<->player only.** Each bot keeps its feelings toward each player, persisted and small, next to the
   short notes and gossip it already has. States: friend, rival, nemesis; neutral is the default.
   - Up: partying, buffs or heals, rescuing the bot from mobs, trades, gifts, fame, kind chat (tone judged by the cheap
     Decide model, with a rule fallback). Down: KSing its mobs, taking its drops, defame, rude chat.
   - Grudges fade over days. Bots may mention players to other bots, lightly.
2. **It shows only in behaviour, never as a number.** Friends greet, send buddy requests, invite to parties, help.
   Hostile bots use words plus petty moves: trash talk, KSing your mobs, refusing trades or parties, defaming you.
   Never harassment: everything is rate limited and a bot never follows a player to another map.
3. **`@botdrama off|on`**: off = bots stay neutral or friendly to that player. Stored per character.
4. **Earned speed** (see the 1.5.0 notes below).
5. **Bot couples and weddings**, rare (see below).
6. GM tools and `!qa` scenarios for all of it.

## Built (1.5.0, branch `bots-affinity-150`)

### (a) Affinity, `@botdrama`, GM tools

Code: `src/main/java/soloMapling/ArtificialPlayer/BotAffinitySystem/` (lines in `BotDialoguePack/AffinityDialogue.yaml`).

- **Store** (`BotAffinity`, `AffinityStore`, migration `011-bot-affinity`): one row per bot and real player with
  history: a score -100..100 as of a time, plus how many kind and hostile things built it. Read once when the player
  first enters a map (off the game thread) and kept in memory while they play; every read is a map lookup. Written only
  when something changes. At most 40 ties per player (the weakest go); rows that faded to nothing are deleted at the
  next login. Without the migration it all works in memory until a restart.
- **Arithmetic** (`AffinityRules`): scores fade toward 0 - grudges halve every 72 h (a rival is neutral again after
  about 2-3 days, a nemesis within a week), friendships every 14 days. States need history: friend >= 25 with 3 kind
  things; rival <= -25 with 2 hostile things; nemesis <= -65 with 5. Gossip only nudges a tie that is still near
  neutral and never past +-15, so hearsay alone never makes a friend or a rival.
- **Events** (`AffinityHooks`, each through `MapleRootSolo.runHook`, each with a per-pair cooldown):

  | Event | Where | Delta | Cooldown |
  |---|---|---|---|
  | party | `Party.joinParty` (either joins the other) | +6 | 30 min |
  | buff / heal | `StatEffect.applyBuff` (a player's party buff lands on a bot) | +3 | 5 min |
  | rescue | the player kills a mob that hit the bot in the last 8 s (`BotContactDamage` -> `MapleMap.damageMonster`) | +8 | 10 min |
  | trade / gift | `Trade.completeTrade` (gift = the bot gave nothing back) | +5 / +9 | 10 min |
  | fame / defame | `GiveFameHandler` | +12 / -18 | - |
  | kind / rude chat | `BotConversation` via `ChatTone` (rules, then Decide; harassment counts double) | +2 / -7 | 3 min |
  | KS | the player's first hit on a mob a bot (not in their party) already damaged | -6 | 2 min |
  | drop steal | the player picks up a bot-owned drop within 45 s of it falling | -5 | 2 min |
  | hearsay | `BotGossip` passing on an affinity note | +3 / -4 | 1 h |

- **Behaviour** (`AffinityReactions`), only when the bot already stands near the player (the roaming bots' player
  scan, observed maps only - off-screen bots do nothing), at most one action per sighting:
  - Friend: greet (through the chat brain), buddy request (`BotBuddies.offerAsFriend`, all of ticket 49's caps
    still apply), party invite (training/social bots; leaves a party it made if the player never joins), a party
    buff it really holds. The chat prompt says they are friends.
  - Rival / nemesis: trash talk; a KS spree (90 s, at most 3 of the player's mobs within 350 px of where the bot already
    is, same map only: `AffinityReactions.ksPick` from the grind strategies); refuses trades (`BotTradeLogic`),
    parties and buddy requests (it counts as wary); a nemesis may defame (at most one bot defame per player per day).
    The chat prompt says it has beef (petty, never about real life, no threats).
  - Cooldowns per pair: greet 2 h, invite 3 h, buddy 1 day, help 15 min, taunt 20 min, KS spree 15 min, defame 1 day.
    World caps per hour: taunts 20, KS sprees 10, invites 6. Gossip lines out loud: one per 10 min world-wide, never
    in front of the player it is about.
  - State changes write a short memory note ("keeps ksing my mobs", "is a good friend, always nice to me"), so the
    chat brain and gossip carry them.
- **`@botdrama off|on`** (any player): off = every bot is neutral or friendly to them, hostile events about them do
  not count, gossip about them is never negative out loud. Stored in `botdrama` (migration 011).
- **GM tools** (`!affinity`, GM 4): `!affinity [bot] [player]` (state, score, counters, age, KS spree),
  `list [player]`, `set <bot> <friend|rival|nemesis|neutral> [player]`, `grudge <bot> [player]` (fresh grudge and a KS
  spree), `stage <friend|rival|nemesis> [social|training]` (a new bot next to you in that state; a training bot is held
  on your map), `unstage`.
- **`!qa`**: `af-1` (friend), `af-2 [rival|nemesis]` (`!qa do grudge`), `af-3` (`@botdrama off`, `!qa do on|off`).

**`-D` tunables** (config.yaml is strict, so none there): `solomapling.affinity` (true), `solomapling.affinity.hostility`
(true), `solomapling.affinity.maxTiesPerPlayer` (40), `.friendAt` (25), `.rivalAt` (-25), `.nemesisAt` (-65),
`.grudgeHalfLifeHours` (72), `.friendHalfLifeDays` (14), `.toneMin` (0.6), per event `.<event>` and
`.<event>.cooldownSec`, per action `.<action>Min` and `.<action>PerHour`, `.gossipLineMin` (10).

