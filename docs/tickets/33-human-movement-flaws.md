# 33: Human movement flaws (missed jumps, falls, route variety)

**Area:** Nav v2
**Depends on:** 04 (learning on v68)
**Status:** open

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
