# 86: Bots answer players' megaphone buy requests (1.6.0)

Source: owner, 1.6.0 notes page, 2026-10-25 ("Player can use a smega to request to purchase an item and a playerbot
will whisper them"). A feature (owner confirmed; it was filed as a bug by mistake).

Read as: when a player megaphones a buy request ("B> red whip 5m", "lf clean claw"), a bot that has the item (in its
inventory or FM shop stock) whispers an offer, haggles within its economy rules, and meets/trades or points to its
shop. At most one or two bots answer, after a human-like delay, never for items no bot has; no answers to sell
megaphones from players.

## Built (1.6.0 rc1)

- Hook: `UseCashItemHandler` megaphone, super megaphone, Megassenger, item megaphone, triple megaphone and avatar
  megaphone call `PlayerMegaphones.onUse(player, text)`, which returns at once and works on the executor.
- `MegaBuyParser` (B>/wtb/lf/buying/looking for, price k/m/b, xN), `MegaBuyMatcher` (exact name, else every word and
  half the name, else reject; two items that fit = no offer), `MegaBuyDesk` (limits, one claim, 20-120 s delay, price
  plan inside `PriceBand`), `MegaBuyGame` (bots' bags, merchant bots, open bot shop shelves), `MegaReactions` (40-70% of
  player smegas get a short bot line). Logs: `[MegaBuy] ...`, QA topic TRADE `MEGABUY ...`. Scenario `tr-8`, auto-QA `t-mb-1`.
- Knobs (system properties): `solomapling.megaBuy=false`, `solomapling.playerMegaphones=false`,
  `solomapling.megaReactChance` (0.55), `megaBuyDelayMinMs/MaxMs`, `megaBuyPlayerGapMs` (180 s), `megaBuyPlayerPerHour` (4),
  `megaBuyServerPerWindow` (8 per 10 min), `megaBuyBotRestMs` (15 min), `megaBuyFarBelow` (0.6).
- Credit: the grammar shape, name-resolution rule and one-bot claim follow nutnnut/Cosmic 722719702, b00401fda, 5342622c3.
