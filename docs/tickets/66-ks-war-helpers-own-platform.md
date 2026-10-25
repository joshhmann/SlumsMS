# Ticket 66: KS war helpers grind on their own platform, not the player's

Status: found 2026-10-24 while fixing the 1.5.1 auto-QA checks (t-rv-2). Not fixed: needs a way to keep a rallied
training bot on the player's platform.

## What happens

`!qa do kswar` (and a nemesis that calls one itself) recruits guildmates, its partner or free training bots, then
`BotRivalry.startKsWar` brings each one with `DramaKit.bringTo(helper, player, offset, true)`. On a map with stacked
platforms (104040000, Henesys Hunting Grounds) the helpers end up on ledges 300-500 px above the player and grind
there. `AffinityReactions.ksPick` takes a mob only within `KS_REACH_X` 350 x `KS_REACH_Y` 90 **px of the bot**, so
nothing they fight is ever one of the player's mobs, and the QA line reads
`AFFINITY KS <bot> no take at 958,-85: 5 mob(s) in reach, 0 hit by AutoQaOne`.

The rally text tells the player "they show up next to you"; on these maps they do not.

## Why

1. `DramaKit.bringTo` only calls `bot.changeMap(map, at)` when the bot is on a **different map**. A bot already
   training on the player's map keeps its own spot.
2. When it does move, `StageSpots.near` may return a spot one platform up; the spot is only "near" in x.
3. After landing, the training brain picks its own grind spot (claims in `CampStrategy`) and walks off.

Fixed in 151-qa-chat-gate: (1) same-map bots are moved too when they are more than 120 px away and the scene grinds,
and (2) a staged spot is used only when it is on the player's own ledge (|dy| <= 30). Both help; they do not stop
(3), so t-rv-2 still fails about half the runs on 104040000 and passes when the map's mobs happen to be on one
platform (5 and 23 takes in the passing runs).

## What a fix needs

A way for a scene to pin a bot's grind spot for its duration (a forced spot or claim the scene owns, released by
`endKsWar`), so the helper stays on the player's platform and fights there. `WorldEventSteer.hold` pins the map only,
and `GCMovement.follow` (tried 2026-10-24) made the helpers stop fighting altogether.

## 160-bugs follow-up (2026-10-25)

After 1.5.2 the arrival half works (helpers enter at a portal and walk to the player's ledge, `DramaKit.enter/arrive`,
fightSpot staging), and `AffinityReactions.nearestToRival` pulls a helper that finds no take toward the player's mobs.
What stayed open was step 3: the training brain's own spot choice walking a helper to another ledge after it landed.
`BotRivalry.rallyStrays` now checks every 6 s (8 rounds at most, while the war lasts) and walks any helper that is
more than 60 px above/below the player or more than 400 px away along x back with a bounded `DramaKit.walkTo` (no warp,
no held follow). Unit-tested for the stray decision only; the walk itself is untested in a real client. Re-run auto-QA
t-rv-2 on 104040000 on the test server to confirm.
