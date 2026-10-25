# Ticket 60: the bot brain (weighted daily-life decisions)

Status: designed 2026-10-23 with the owner (grill), planned as the **1.6.0 headline**, alongside fishing, medals and
the daily dungeon, which become options the brain chooses. Not started.

## Why

Bots today pick their day from separate systems (the population director, grinder logic, town/social roles,
habits, commitments). Each works, but nothing weighs them against each other, so a bot can't decide "my friend
needs help, I'll go" or "I'm out of pots, brb", and its chat can promise things it never does. The owner's goal is
that **bots do what they say**: chat follows decisions, and decisions follow the bot's situation and personality.
The look to aim for is old-school Henesys: a hangout for every level, high levels dropping into low maps to show off
or help friends.

## Decisions (owner, 2026-10-23)

| Topic | Decision |
|---|---|
| Scope | **Daily life:** where a bot goes and what it does between fights: grind here / move maps, restock, sell, hang out in town, help a friend, show off, party quest, FM, break, log off. Combat stays as tuned in 1.5.0. |
| Personality | **Archetypes plus a random blend per bot:** grinder, socialite, show-off, helper, merchant, explorer, slacker. Each bot has a recognisable vibe but no two are alike. |
| Drift | **Slowly, with life events:** a job advance, marriage, a guild war, a big drop or boom nudge the weights a little. |
| Player pull | **Strong for friends, light for strangers, negative for rivals** (affinity). Respects `@botdrama off`. |
| Decide model | **Reads players only:** it interprets player chat (help asks, LFG, tone) and feeds scores. The brain itself is weighted maths with no model calls. |
| Chat model | Voices what was chosen. The say-do rule: a line that claims an action is only said when the action happens. |
| Visibility | `!botwhy <name>` shows needs, the top 3 options with scores and the reason; every decision is logged for auto-QA. Players never see numbers. |
| Pacing | **Human-like stickiness:** grind sessions of 20-60 min and town hangouts of 5-20 min, unless an interrupt fires. |
| Interrupts | A friend asks or a party with a friend forms; out of pots or bag full ("brb pots", then a real trip); world events, bosses and guild wars; a rival shows up (respecting `@botdrama off`). |
| Goals | **1-2 simple goals per bot:** the next job advance, a gear upgrade, saving for an item, a medal. They bias choices and give chat something real to talk about. |
| Henesys mix | **Old-school:** lots of level 1-30, a solid group of 30-70 selling and chatting, a few 70+ showing off; high levels sometimes visit low maps to help or flex. |

## Shape

- **Needs** (0-1, rising over time or with events): supplies, bag space, boredom (time on the same activity),
  social (time since talking or partying), fatigue (session length, time of day), progress (EXP and gear behind the
  bot's goal).
- **Context:** level vs the map, gear tier, friends online and where, party and PQ openings, world events, rivals
  nearby, the hour (the population curve), the channel and the map cap.
- **Options:** each option scores `sum(need × weight) + context bonuses + personality weights + small noise`. The
  current activity gets a stickiness bonus that decays over its expected length. An interrupt can only win if it
  beats the current activity by a margin.
- **Execution:** the existing systems do the work (grind paths, travel, BotResupply/BotShopping, social roles, PQ
  and party code, the director's logout). The brain only chooses.
- **Chat:** the chosen option and its reason go into the conversation card ("heading to Ant Tunnel to grind for 2nd
  job"); ambient lines with an action claim are only picked when that option was chosen.
- **Cost:** a re-score is a few dozen multiplications per bot; bots re-score on interrupts and when their activity
  ends, not every tick. It must stay cheap at 3,000 bots.

## Testing

- **Unit:** scoring is pure functions. Personas get golden tests (a socialite with friends in Henesys goes there; a
  grinder out of pots restocks; a helper's friend asks for help and it goes).
- **`!qa` scenarios:** stage a need or event and assert the decision. Auto-QA reads `!botwhy`-style decision logs, the
  say-do metric, and the Henesys level mix at peak (target bands per the owner's mix).
- **Soak:** decisions per minute and CPU at 3,000 bots.

## Open questions for build time

- The exact archetype list and blend ranges, tuned against live data from 1.5.0.
- How goals are seeded for the existing roster (from current level and gear).
- Fishing, medals and the daily dungeon: each needs its option definition when it lands.

Parent: ticket 99 (Companions epic).
