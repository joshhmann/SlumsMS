# 57: Fewer, better bots, spread over the channels

**Area:** Real bots / Population
**Kind:** BUILD
**Effort:** M
**Depends on:** 08 (population director), 10 (roster lifecycle)
**Status:** done on branch `bots-population-142` (for 1.4.2). Not deployed.

## Owner decision (2026-10-21)

"Fewer, better bots. ~500 peak is fine but we can spread the bots per channel keeping first channels higher then
lower pop in higher channels." Like a real server: channel 1 busiest, falling toward channel 8.

## Before (live, 1.4.0 / 1.4.1)

- Every bot lived on **channel 1**. Channels 2-8 had no bots at all.
- The director ran `BOT_ROSTER_PEAK_ONLINE: 1200` (set in live's config.yaml) on the daily curve
  `0:0.45 … 20:1.0, 22:0.8`. The roster has about 2,550 members.
- The boot environment spawned about 660 bots in waves 1-7 (21 Oct: 659 and 670). About 310 of them run FM room
  shops, about 310 are town dressing (Henesys, Market, Park, Pet Park, FM entrance), and about 47 are set pieces.
- So about 1,000 bots were online right after a restart, and up to about 1,850 at the evening peak.

## What changed

### Population: about 500 at the peak

| Key (config.yaml `server:`) | Shipped default | Meaning |
|---|---|---|
| `BOT_POPULATION_PEAK` | **500** (new) | All bots online at the curve's peak. The director's peak is `min(BOT_ROSTER_PEAK_ONLINE, this - environment bots online)`. `0` = no cap. |
| `BOT_ENVIRONMENT_SCALE` | **0.25** (new) | The share of the environment's town dressing that spawns. Set pieces always spawn whole. `1.0` = the old environment. |
| `BOT_CHANNEL_WEIGHTS` | **`26,20,16,12,9,7,5,5`** (new) | How bots split over the channels, ch1 first. Any number of weights. Channels past the list take the last weight. Normalised. `100,0` puts every bot back on channel 1 (the old behaviour). |
| `BOT_ROSTER_PEAK_ONLINE` | **300** (was 2400) | The director's own peak, before the cap. |

No live config edit is needed. Live keeps `BOT_ROSTER_PEAK_ONLINE: 1200`, and the 500 cap still holds:
`min(1200, 500 - ~200) = ~300`. You can remove that line later; it changes nothing.

**The split, and why.** About 200 environment bots and about 300 director bots at the peak:

- **Environment, about 200, around the clock.** These are the town dressing at 0.25 (about 154 bots) plus the set
  pieces (about 47).
  - The set pieces are the tutorial bot, the gacha bots, the Game Zone hosts, five blackjack tables, the drop game
    and its spectators, and the OPQ lobby. They are features, not filler: they keep their full size and stay
    together on channel 1, where the casino NPCs are.
  - The dressing is what makes towns feel alive, so it is not cut to zero. It goes on every channel, so a player on
    any channel sees some life in Henesys and FM.
  - The FM room spots that lose their shop bot get a **hired merchant** on channel 1 instead. Every FM room stays as
    full of shops as before, with about 230 fewer bot characters standing in it.
- **Director, about 300 at the peak.** These are the "better" bots: persistent identities, chat memory, levelling,
  sessions, friends. Most of the cut lands on them in raw numbers (from up to 1,200 to 300). Even so, they stay the
  majority, because they are what players talk to and party with.
  - On the default curve the director runs about 45 bots at 06:00 and 300 at 20:00.
  - In total that is about 245 bots in the early morning and 500 in the evening.

**Where the numbers live:**

- `config/ServerConfig.java`: the four keys above.
- `soloMapling/ArtificialPlayer/BotPopulationDirector.directorPeak()`: the cap.
- `soloMapling/Environment/EnvironmentManager.environmentLoadStartup()`: the waves. `spread(...)` marks town
  dressing; an unmarked task is a set piece.
- `soloMapling/Environment/PlatformPlacement`: the scaled headcounts, via `BotChannels.envCount`.
- `soloMapling/FreeMarket/ArtificialFreeMarket.populateFreeMarketRoom`: the FM shop bots that become hired
  merchants.

### Channels: channel 1 busiest

`soloMapling/ArtificialPlayer/BotChannels` (new):

- **How a bot changes channel.** Each channel now has its own shared headless bot client
  (`BotClientHandler.getBotClient(channel)`). A bot's channel is its client's: the channel's player storage, its
  map factory, and every `changeMap` follow the client.
- **Director logins** (including new roster players) take the channel that is furthest below its share of all
  bots online. A reservation counts logins that are still in flight, so the boot fill's parallel logins do not
  pile onto one channel. Logouts are left to sessions, and later logins correct the split, so it stays close
  (within about 3% in a simulated day of churn).
- **Environment town dressing** is split by the same weights among the dressing itself, bot by bot. It is not split
  against all bots: the set pieces already stand on channel 1, and counting them would leave channel 1's towns
  almost bare. The director then evens out the total around the set pieces.
- **Expected split at 500 bots:** ch1 130 / ch2 100 / ch3 80 / ch4 60 / ch5 45 / ch6 35 / ch7 25 / ch8 25.

| Channel | Total | Environment | Director |
|---|---|---|---|
| ch1 | 130 | about 87 (40 dressing + 47 set pieces) | about 43 |
| ch2 | 100 | about 31 | about 69 |
| ch3 | 80 | about 25 | about 55 |
| ch4 | 60 | about 18 | about 42 |
| ch5 | 45 | about 14 | about 31 |
| ch6 | 35 | about 11 | about 24 |
| ch7 | 25 | about 8 | about 17 |
| ch8 | 25 | about 8 | about 17 |

- **At night**, with only about 45 director bots, ch1 stays the busiest (its set pieces), and ch2-ch8 fall away in
  order.
- **Everything else** spawns on the channel of the map it is given. A GM's `!bot spawn` and similar commands now use
  the GM's own channel.
- **Companions follow their player across channels.** A `FollowerBot` whose leader is online on another channel
  changes to that channel, onto the same map and spot, and follows on from there. Before, it lost its leader and
  said goodbye. Its party entry gets the new channel.
- **Real players are untouched.** Nothing counts or moves them.

**Channel-1 assumptions fixed along the way:**

- Bot removal now uses the bot's own channel.
- Bot lookups (`getCharFromChannelStorage`, `SoloMaplingUtilities.getChr`) use the world's storage.
- Bot megaphones show the bot's channel.
- The ambient conversation and bark managers now work on the map instance a real player is on, on any channel.
- The equip checker scans every channel.
- World events take joiners and speakers only from the event's channel.
- The warp helpers of HenesysBot, HenesysJQBot and OPQBot use the bot's own channel. (`changeMap` already
  re-resolves through the bot's client.)

**GM read-out:** `!roster director` now shows the director's peak as
`min(roster peak, cap - environment)`, and adds a second line: `bots per channel (now/target): ch1 …`. The
dashboard's peak is the capped one.

## Economy side effects

- **Meso intake falls about 4x.** Mob mesos are the bots' only big faucet, and they scale with director bot-hours.
  - The curve averages about 0.5 of the peak, so the director averages about 150 online instead of about 600 at
    peak 1200.
  - On 2026-10-20 bots took **7.83 billion** (1,515 bots). Expect roughly **2 billion a day**.
- **The yield caps still make sense; nothing to retune.**
  - The per-bot daily cap (`max(100k, level x 10k) x meso rate`; 9.6m at lv 96 at 10x) is per bot, so each bot
    earns what it did.
  - The per-map yield cap is per map instance (map + channel). With bots spread over 8 channels, fewer bots share
    each instance, so the map cap binds less often. The daily cap stays the effective limit.
- **The FM is a slightly bigger sink.** About 230 FM spots changed from bot shops to artificial hired merchants.
  - An artificial merchant's sales go nowhere (`isPersistent() == false`), so a player's purchase there is a pure
    sink. At a bot's shop, the mesos would have gone to the bot.
  - The number of shops is unchanged, so players see the same supply on ch1.
- **Bots buy less from players' shops (ticket 56A).** There are about 4x fewer `FMBot`s, spread over 8 channels.
- **Bot wealth (17.7 billion stored) does not shrink by itself.** It just grows more slowly. Ticket 56B (wealth
  ceiling) is still the fix if the owner wants `SUM(botstore.Meso)` flat.

## Tests

- **`BotChannelsTest`** (9):
  - the shipped split, and fallbacks for blank, broken and negative lists;
  - any channel count, including 1, 3 and 10, all zeros, and `100,0` as a switch back to channel 1;
  - **500 simulated logins land within 1 bot of each share**, in order, ch1 busiest;
  - 20,000 steps of churn (150-500 bots) stay within 3%;
  - parallel reservations do not stack on channel 1;
  - scaled cohort counts keep their expectation;
  - the cap arithmetic, including live's 1200;
  - spread tasks are recognised by slot.
- **`BotPopulationBudgetTest`** (6):
  - the unscaled environment model matches live's 659/670;
  - the shipped defaults give an environment of 180-230 and a director peak of 270-300, so the total is 500 or
    less;
  - the scaled waves stay near count x scale;
  - the director curve gives 300 at 20:00, 45 at 06:00 and 135 at midnight, the weekend lift never passes the peak,
    and Saturday midnight is 149;
  - 500 bots give 130/100/80/60/45/35/25/25;
  - dressing, set pieces and director together keep each channel within 1.5 of its share, and ch1 is still
    busiest at night.
- **Full suite:** `mvn -q -o test`: **3229 run, 0 failures, 0 errors, 20 skipped** (rc1 baseline 3214; 15 new).

## In-game QA (test server, after a restart)

1. Boot log:
   - waves 1-7 add up to about 200, not about 660;
   - `[Director] boot fill: target …` is about `0.27 x 300` in the morning, and up to 300 in the evening;
   - `Population director started: peak ~300 (roster peak …, population cap 500) … channel weights 26,20,…`.
2. World list: the channel bars are highest on ch1 and fall toward ch8. With `CHANNEL_LOAD: 100` and bots at 1/4
   (1.4.1), the evening peak shows about 33% on ch1 and about 6% on ch8. Live's `CHANNEL_LOAD: 300` stopgap makes
   them three times flatter: put it back to 100 with 1.4.1.
3. `!roster director`:
   - line 1: `target now N (peak ~300 = min(roster peak 1200, cap 500 - ~200 environment))`;
   - line 2: `bots per channel (now/target): ch1 130/130 ch2 100/100 …` at the peak, each within a few bots.
4. Walk Henesys, the Market, the Park, the FM entrance and a few FM rooms on **ch1**:
   - still busy;
   - the FM rooms are as full of shops as before, mostly hired merchants now;
   - the blackjack tables, drop game, gacha bots and OPQ lobby are all there.
5. Change to **ch5** and **ch8**: a few bots in Henesys and at the FM entrance, and grinders in the fields. Quieter,
   but not empty.
6. Companion:
   - recruit a bot on ch1 (`follow me` + party invite), then change channel to ch3;
   - within a few seconds the bot appears on ch3 on your map and follows on;
   - the party window shows it on ch3.
7. Talk to a bot on ch3 (map chat and whisper). It answers as on ch1.
8. A GM spawn (`!bot spawn …`) on ch4 appears on ch4.

## Risks

- **More map instances in memory.** Bots now load town and field maps on every channel they reach (mobs and respawn
  timers included). With 500 bots the total should stay close to before, but watch heap (`-Xmx4G` on live) and the
  respawn CPU on the test server before release.
  - Switch back to the old behaviour with `BOT_CHANNEL_WEIGHTS: "100,0"`.
  - Get more bots back with `BOT_POPULATION_PEAK` or `BOT_ENVIRONMENT_SCALE`.
- **LOD observation is per map id, not per channel** (`ObserverTracker`). A player in Henesys on ch1 keeps
  Henesys's bots on all channels at full physics. This is a performance cost only; nobody sees wrong behaviour.
  Barks now only go where a real player is.
- **World events stay on channel 1.** Only that channel's bots join or speak. That is correct, but there are fewer
  of them now (about 130 at peak against everyone before).
- **Only `FollowerBot` follows across channels.** A training bot that is merely in a player's party stays where it
  is, as a real party member would.
- **The roster is still about 2,550 for about 300 online.** Roster growth still keys on `BOT_ROSTER_PEAK_ONLINE`
  (live's 1200 x 3), so new beginners keep appearing. Each bot is now online about 1/8 as often, so familiar faces
  return less often. Shrinking the roster (or tying it to the capped peak) is a separate owner decision.
- **Environment counts are drawn at random around count x 0.25.** A single small cohort (1-2 bots) may be absent on
  a given boot. That is by design.
