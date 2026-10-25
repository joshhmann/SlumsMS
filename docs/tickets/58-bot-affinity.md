# 58: Affinity - relationships between bots and with players

**Area:** Real bots / Social
**Depends on:** 49 (buddies), 50 (guilds), 52 (marriage), 21 (world events), 26/36/37 (chat), 47 (megaphone content)
**Status:** closed 2026-10-25 (done in 1.5.0, branches bots-affinity-150 and bots-drama-150 (bot-bot friendships re-enter via ticket 60)).

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

### (b) Earned speed

`GCMoveSystem/BotMovementProfile.fromCharacter`: a bot's speed and jump are now exactly a player's with the same
things - **100% + real gear + real buffs + Haste only when it really has it**, capped like a player at speed 140 and
jump 123. The level/class baseline (115-155 speed, 100-123 jump) is gone; it was added on top of real gear, so geared
bots were counted twice.

- **Haste:** the bot's own Haste at the level it holds (speed +2, jump +1 per level from the skill data: Haste 20 =
  140 / 120), or the best Haste of a party member standing on the same map and channel (a thief keeps the party
  hasted while they train together). A bot with no skills holds no Haste (the old job-based fallback is gone). A real
  SPEED buff already on the bot (a player's Haste) is used as-is, never added twice.
- **Nav graphs and cache:** graphs are keyed by the (bucketed) profile. Before, every map had up to six level tiers
  plus gear variants per map; now almost every bot plays on the base graph (100/100) - the same graph the grind spot
  finder already used - with a few extra keys for hasted thieves (120/110, 140/120) and speed gear. Fewer resident
  graphs in `NavGraphCache`, fewer builds. `NavObservedEdges.PROOF_PROFILES` (the tiers a learned player move is
  re-simulated for) is now base, 110/100, 120/110, 140/120 and 140/123; stored learned moves from before 1.5.0 (no
  `tiers` key) carry over only to new tiers at least as fast as the tier that proved them, and are re-saved.
- **Reach:** a bot's route and its physics use the same profile, so it never plans a jump it cannot make; at 100/100
  it jumps like a fresh player, which every map is built for. Ledges only a faster character reaches are simply not in
  its graph (as for the spot finder before).
- **Bake:** `NavDeepBake` re-bakes every key found in `cache/bot-nav`; the retired tier files (115/100, 125/105,
  130/110, 135/115, 145/120, 155/123 ...) stay there and get re-baked nightly until removed. One-time step at the rc2
  deploy (owner's call): clear the non-base files in `cache/bot-nav` and the deep overlay so only keys bots use come
  back.

### (c) Bot couples and weddings

Code: `src/main/java/soloMapling/ArtificialPlayer/BotCoupleSystem/` (lines in `BotDialoguePack/CoupleDialogue.yaml`,
the love note in `MegaphoneDialogue.yaml` `CoupleLoveNote`, wedding chatter `WeddingDuring/After` in
`WorldEventDialogue.yaml`), migration `012-bot-couples`.

- **Rare:** at most 4 couples and 4 pairs of best friends in the world. About twice a day the world rolls once for
  each; on a hit, two roster bots (training or social, level 15+, within 20 levels, **any genders** - owner
  2026-10-21) standing on the same map and channel get together. Bots only ever pair with bots.
- **Best friends** (`FRIENDS`): hang out like a couple (same channel, party, train on the leader's map, talk to each
  other) and wear friendship rings (Clover / Flower Petal / Star, 1112800-1112802, one kind per pair). They never marry,
  use no couple chair and send no love notes. Players cannot get friendship rings today (the Cash Shop window leaves out
  every partner ring, ticket 52), so bots wear them as plain items, like the wedding rings.
- **Together** (checked once a minute, only for couples with both online - a handful of bots): the second hops to the
  first one's channel; they party (bots only - never out of a real player's party) and a training bot trains on its
  partner's map when the partner leads (`TrainingBot.partyTargetMapId`); where a real player can see, one now and then
  says something to the other (20 min gap); a couple that owns a couple chair sits on it together (married couples own
  the Amorian Loveseat 3012005; dating ones only if a chair is in their setup inventory); married couples wear wedding
  rings (Moonstone / Star Gem / Golden Heart / Silver Swan, one kind per couple, plain items: no `rings` rows); on about
  one day in four the second wears the first's cash hat; every 3 days or so one sends a `CoupleLoveNote` megaphone
  through `WorldEventMegaphones`/`BotMegaphoneGate` (the world's megaphone cap and `@quiet megaphones` hold). Their chat
  prompt knows the partner, and that they are taken.
- **Weddings:** a couple dating for 2+ days may marry, at most one wedding a day, in the evening (director curve >= 50%),
  as world event `amoria_wedding` (new kind `wedding`, weight 0 so the generic scheduler never picks it). The stock v83
  wedding (reservations, Cathedral/Chapel instances, `marriages` rows) is built for two real players and real
  reservations must win, so the ceremony is staged in Amoria town by the chapel staff where anyone can walk up: blue
  notice "[Wedding] A & B are getting married in Amoria!", the couple comes to channel 1 and Amoria, each in what fits
  them (a man in the tuxedo, a woman in the wedding dress; any pair marries), their own clothes back afterwards, 5 guests
  appear, a guest officiates, "i do", rings, "married", cheering; at the end guests leave and the couple goes back to where it was, doing what it did. Nothing is paid out;
  no `characters`, `marriages` or `rings` row changes.
- **Bless EXP:** when each half says "i do", every real player on the map gets a small blessing, once per player per
  wedding: about 1% of their level's EXP, at most `solomapling.couples.blessExpCap` (20000).
- **GM** (`!couple`, GM 4): `list`, `spawn [married|friends] [same|mixed]` (a staged pair next to you, memory only),
  `form <a> <b> [friends]`, `wedding [id]`, `note [id]`, `step`, `break <id>`, `unstage`. **`!qa`**:
  `cp-1 [married|friends] [same|mixed]` (`!qa do step`), `we-1 [same|mixed]`.
- **`-D` tunables** (`solomapling.couples.*`): `enabled` (true), `maxCouples` (4), `maxFriendships` (4),
  `blessExpCap` (20000), `formEveryHours` (12), `formChance`
  (0.5), `weddingAfterDays` (2), `weddingGapHours` (24), `loveNoteDays` (3), `mentionMinutes` (20), `chairMinutes` (15).

### (d) Same-gender marriage for real players (owner, 2026-10-21)

"We gotta be progressive chief let's get same gender marriage working too and friendships!!"

- Where the v83 flow enforced opposite genders, and what changed:
  - `RingActionHandler.sendEngageProposal` refused a proposal to the same gender: removed.
  - `RingActionHandler.breakEngagement` worked out the partner's engagement token from the breaker's gender (+1 for a
    man, -1 for a woman): now the token recorded on the partner, else whichever half of the pair they carry.
  - Moony (`scripts/npc/9201000.js`) crafted ring boxes for men only: anyone now.
  - Messages that said "she" / "the girl" are neutral.
- Already gender-free (checked): the wedding reservation (`9201005/9201008`, `Channel.pushWeddingReservation`),
  `groomId`/`brideId` (the reserving player and their partner), `Marriage.isMarriageGroom`, the wishlists, the
  ceremony scripts (`sendWeddingAction` only orders the two ids), `Ring.createRing`. Wedding-guest outfits and the
  Amoria hair shops still pick by the player's own gender, as they should.
- `WeddingPackets.OnMarriageResult` still writes a man first when there is one; for a same-gender pair both partners
  get the same order. What the **client** might still check is written up for the client lane in
  [58-client-same-gender-marriage.md](58-client-same-gender-marriage.md) (to verify on a real client).

### Deploy

[docs/release/1.5.0-bots-affinity-deploy.md](../release/1.5.0-bots-affinity-deploy.md): migrations 011 and 012, no
config keys, and the one-time nav cache clean-up (non-base graph files) the owner approved.

## Bot drama (1.5.0 rc4, branch `bots-drama-150`, owner 2026-10-22: "spicy")

The owner moved player romance, KS wars and guild beef into 1.5.0. This overrides decision 1 above ("bot<->player
only" stays true; romance with players is now in, opt-in) and the "never follows a player to another map" rule for
two cases only: a romance partner (opt-in) and one revenge follow. Deploy notes, every number and switch:
[1.5.0-bot-drama-deploy.md](../release/1.5.0-bot-drama-deploy.md). Code: `ArtificialPlayer/BotDramaSystem/`.

Guardrails (kept exactly): `@botdrama off` = no rival, KS, callout or guild-war drama aimed at the player (and quiet
romance breakups); romance needs `@romance on` (off by default); roasts stay about the game and romance is PG-13 - in
the prompts and in `ContentGuard`, which every model reply and every scripted drama line passes; every megaphone goes
through the gate; everything is time-limited and capped, nobody is locked out of a map.

- **Romance** (`BotRomance`, `RomanceWedding`, migration 015): flirting, asking out (both ways), partners who show up,
  follow sometimes, share a couple chair, whisper (capped, never while busy) and get jealous; the stock ring-box
  proposal on a bot (or the bot proposes); an Amoria wedding that writes the stock marriage (rings pair, the player's
  ring, marriage item ids); divorce the stock way or `@romance divorce`; an offline bot spouse handled (spouse chat,
  `getNameById` for bots, the ring at its next login). Drama: neglect breakups with the `I'M DONE WITH YOU <name> </3`
  megaphone, the ex as a rival who rebounds and returns as an old flame, two-timing confrontations, gossip into bot
  memory and guild chat, wedding objections, runaways and crashed receptions.
- **Rivals** (`BotRivalry`, `RivalRoasts`): roasts from real facts (level vs map, deaths, gear, job, KS), scripted or
  one model line; KS wars (2-4 called bots, 3 min, once per player per day); callout megaphones; the one revenge follow
  that wants an apology ("sorry" calms it, mocking makes a nemesis).
- **Guilds** (`GuildWars`, migration 016): bot guild wars with turf on grind maps and a weekly score, wars on player
  guilds whose members are the bot guild's rivals (`@guildwar`, `@guildwar peace`), poaching (bot from bot, whispers to
  players), alliances with joint boss runs, and betrayals that turn into war.
- **GM / QA:** `!botromance`, `!botrival`, `!botwar`; `!qa` rm-1..rm-4, rv-1..rv-4, gw-1..gw-4 (docs/ops/qa-suite.md),
  log topics `ROMANCE`, `RIVALRY`, `GUILD_WAR`.
