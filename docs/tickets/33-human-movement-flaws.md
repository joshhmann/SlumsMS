# 33: Human movement flaws (missed jumps, falls, route variety)

**Area:** Nav v2
**Depends on:** 04 (learning on v68)
**Status:** built on branch `movement-flaws` (not merged); waiting for the owner's in-game look

## Why

Nav learning makes bots better at every map, and a bot that never misses looks like a bot. Real players mistime
jumps, fall off ropes, overshoot a ledge, take the long way and hesitate. Bots should too, without the misses
teaching the nav graph that a good jump is bad.

## What

- **Skill split:** what a bot *knows* (the nav graph and its learning) is separate from how well it *executes*.
  Planning stays smart; execution sometimes fails on purpose.
- **Per-persona skill:**
  - Each roster bot gets a movement skill (e.g. 0.85–0.99 success on hard jumps), stored with its persona.
  - Some personas are clumsy, some are crisp.
  - Skill improves slightly with level, and a bot gets a little better on maps it grinds often.
- **Kinds of flaws, all made with real physics, so the client sees an honest fall:**
  - **Missed jump:** the takeoff starts early or late, and the bot lands short and falls to the platform below.
  - **Overshoot:** too much run-up past a ledge.
  - **Rope slip:** lets go of a rope a bit early.
  - **Hesitation:** a short pause before a hard jump, or a wasted hop in place.
  - **Flash-jump or teleport overshoot:** lands past the target and walks back.
  - **Route variety:** sometimes takes a near-equal route, not always the single cheapest one.
- **Weighted by difficulty:** easy walks basically never fail; long or tight jumps fail more often.
- **Recovery looks human:** after a fall the bot shrugs it off and re-plans; sometimes it emotes or says "ugh" (ties
  into bot chat, rarely).
- **Learning must not be poisoned:**
  - Every deliberate flaw is tagged.
  - `NavEdgeLearning` ignores tagged failures, so only genuine failures raise an edge's cost.
  - Nav lab (04a) runs bots with flaws off.
- **Limits:**
  - Never during commitments that matter (a party quest, a boss, an escort).
  - Never a flaw that kills the bot or strands it.
  - Offscreen (LOD) bots don't simulate flaws; they only add a small time cost.

## Done when

Watching a busy map, the owner sees occasional, believable mistakes and varied routes. Learned edge costs come only
from genuine failures, which a test shows.

## What was built (2026-10-20)

`GCMoveSystem/NavMovementFlaws.java`, hooked into the jump and rope-exit executors (`BotNavigationManager`) and the
driver (`GCMovementDriver`). Planning is untouched; only execution fumbles. Every flaw is a move a player sends (walk,
jump, jump off a rope) launched through the normal physics, so the client gets ordinary movement packets.

- **Flaws:**
  - hesitation at a jump's launch spot (0.25-1.15 s);
  - false start: a hop in place, only where the simulated landing is the same platform;
  - overrun: runs 2-4 steps past the launch spot (staying 24 px inside the platform), then walks back;
  - missed jump: takes off 2-4 steps early with full jump physics;
  - rope slip: jumps off a rope part way up (falls of 260 px or less);
  - wrong-way twitch while walking (2-4 ticks, only with 30 px of room behind);
  - running hops while walking: a "hopper" personality (about 1 in 5 bots) hops roughly every 4 s of a long walk,
    others almost never. Hops happen only on a long walk (150 px or more to go), and only when the simulated
    landing is on the same platform and short of the target.
- **Per-bot skill:**
  - Each bot's success rate on the hardest jump is drawn once from its character id (0.85-0.99, skewed clean,
    mean about 0.94). It never re-rolls.
  - It improves a little with level: a level 120 bot has closed a third of the gap to perfect.
  - It also improves with time on the current map: up to 30% fewer flaws after 30 minutes.
- **Difficulty:** walks, drops, portals and teleports never fail. A jump's difficulty comes from a narrow launch
  window, a long reach and a rise; rope exits count as 0.4. Per jump approach (rolled once):
  - miss chance = (1 - skill) x difficulty;
  - quirk chance = 2 (1 - skill)(0.3 + 0.7 difficulty).
- **Safety:**
  - Before a miss or slip fires, its arc is simulated. It goes ahead only if it lands on a foothold the graph knows,
    and that spot is the start, the target, or a spot from which the edge's landing is reachable within 12 s of
    planned travel. Otherwise it is skipped and counted as `unsafe-skipped`.
  - No bot misses or slips twice within 45 s, or on the same edge within 5 minutes.
  - Recovery is the normal re-plan from wherever the bot lands.
  - While a flaw hop or slip is in the air, the navigator keeps its edge, so a hop never logs `NO_REGION`.
- **Where:**
  - Only on maps with a real player (`ObserverTracker.isFull`). Offscreen, coarse movement stays perfect.
  - Never in an event instance (PQ, boss, escort), a trade hold, a rest hang, or while following as a party
    companion.
  - A per-bot off switch (`NavMovementFlaws.setOff`) is there for the nav lab (04a) when it is built.
- **Learning:**
  - Misses and slips are tagged with `NavEdgeLearning.tagDeliberateFlaw`. The tag is spent at landing, so the
    honest retry after a flaw that came down on its own platform is judged normally.
  - An early takeoff that still makes it counts as a make.
- **Reaction:** after a fall, 20% of bots emote (troubled, angry or bewildered). One in five of those also says
  "ugh", "oops", "lol", "bruh", "missed" and so on.
- **Read-out:** `!gcmove learned` now also prints the flaw counts. An INFO line `movement flaws: ...` is logged at
  most every 10 minutes.
- **Route variety** was already there (per-bot route seed, epsilon and jitter in the planner); it is unchanged.
- **Not built:** a flash-jump or teleport overshoot, and the "small time cost" for offscreen bots. The ticket left
  both optional, and offscreen stays perfect by request.

### Tunables (-D system properties, no config.yaml keys)

| Property | Default | Meaning |
| --- | --- | --- |
| `bot.flaws.enabled` | true | the whole feature |
| `bot.flaws.rate` | 1.0 | multiplies every flaw chance (0 = off) |
| `bot.flaws.skillMin` / `bot.flaws.skillMax` | 0.85 / 0.99 | success rate on the hardest jump, sloppiest and cleanest bot |
| `bot.flaws.hopperShare` | 0.2 | share of bots that hop as they walk |
| `bot.flaws.hopRate` | 1.0 | multiplies hop frequency |
| `bot.flaws.react` | 0.2 | chance of an emote after a fall |
| `bot.flaws.observedOnly` | true | only on maps with a real player |
| `bot.flaws.recoveryMaxMs` | 12000 | how far off course a miss may leave a bot |
| `bot.flaws.missCooldownMs` | 45000 | minimum gap between one bot's misses and slips |
| `bot.flaws.logEveryMin` | 10 | period of the INFO summary line (0 = never) |

## Evidence

- **Unit tests:** `NavMovementFlawsTest` has 12 tests. They cover:
  - skill spread and stability, level and familiarity, the hopper share;
  - difficulty, and eligibility (commitment, companion, event, offscreen, switches);
  - one roll per approach, the miss cooldown and the same-edge memory;
  - the measured default rates: over 500 bots and a mix of four jumps, 3.2% misses and 8.3% quirks per jump;
  - ten deliberate falls leave the cost at 1.0, while genuine misses after them still reach the bad factor;
  - an early takeoff that makes it counts as a make;
  - overrun and twitch steering, and that the hop holds its edge.
- **Full suite:** `./mvnw -o test` ran 2,706 tests, 0 failures (2,694 + 12).
- **Flaw lab:** `NavFlawLab`, a dev tool in the test sources and not a unit test. It runs the real driver, navigator,
  physics and learning headless on real maps (WZ from the dev box), forced FULL as if a player were on every map.
  - Setup: 40 bots on 100000000, 101000000, 104040000 and 103000000, 12 minutes per mode, with the same bots (same
    ids, so the same skill) on the same errands in each run. Each bot walks to a stream of random points.
  - The stuck and abandoned counts are high in every mode: random targets include spots behind one-way portals.
    Compare the modes with each other, not with live.

| Run | trips/bot/h | mean trip | abandoned | stuck-log events | penalised edges | flaws in 8 bot-hours |
| --- | --- | --- | --- | --- | --- | --- |
| off (baseline) | 262.4 / 264.6 | 7.77 s | 912 / 879 | 2459 / 2419 | 118 / 111 | - |
| on, defaults | 260.4 / 259.1 | 7.91 s | 878 / 892 | 2407 / 2433 | 111 / 114 | 236 hesitate, 59 false start, 47 overrun, 73 miss, 6 slip, 21 twitch, 63 hop; 21 falls, 71 unsafe skipped |
| on, rate 3, tagged | 255.3 | 8.11 s | 867 | 2345 | 119 | 722 hesitate, 151 miss, 56 falls |
| on, rate 3, **untagged** | 253.5 | 8.18 s | 850 | 2377 | **171** | 726 hesitate, 157 miss, 59 falls |

  - The two numbers in a cell are two separate runs. The second "on, defaults" run predates a small fix that
    re-rolls after each real launch.
  - **Efficiency:** the defaults cost 0.8-2% of trips per hour, about 0.1 s per trip. Grinding spends most of its
    time fighting, not walking between platforms, so the cost to kills per hour is smaller still.
  - **Stuck:** the stuck-log events and abandoned trips are within run-to-run noise of the baseline. No bot was
    stuck in a fall loop: every miss and slip landed on a known foothold with a route back.
  - **Learning:** with the tag, the number of penalised edges stays within the baseline's noise (111-119 against
    111-118). The same flaws untagged add about 55 penalised edges in 12 minutes. The tag is what keeps the
    learned costs from drifting.
  - **Seen per bot:** these lab bots do nothing but cross platforms, about 260 trips an hour. That works out to about
    30 hesitations, 9 early takeoffs (about 3 of them real falls), 6 false starts, 6 overruns and 8 hops per
    bot-hour. A grinding bot jumps far less, so on a live map expect a visible fumble from any one bot every few
    minutes. Tune with `bot.flaws.rate`.

### Not done here: the server on-screen check on 164

- The plan was a scratch copy with its own schema, as in tickets 07-09, using a temporary "observe every map" switch.
- This session's permissions refused reading the test database credentials on 164, so no scratch schema could be
  made. The lab above runs the same code path headless instead.
- Kills per hour (combat) and client acceptance were therefore not measured on a server. Combat is untouched; the
  packets are the same kinds bots already send for jumps and rope jumps.

## What the owner should look at in game

- Stand on a busy grind map with jumps (Ellinia trees, the Kerning construction site), or `-Dbot.flaws.rate=3` on a
  test server to see it sooner.
- Watch for: pauses at a jump, a hop in place, running past and coming back, an early takeoff that falls short, an
  occasional rope jump-off, and the few "hopper" bots hopping along.
- Confirm that falls look like a player's: the client accepts the arc, with no rubber-banding. Bots should get up
  and re-route without circling, and none should land in a spot they cannot leave.
- Check that the "ugh" emote or chat after a fall is rare and not annoying.
- `!gcmove learned` shows the flaw counts beside the learned-edge counts.
