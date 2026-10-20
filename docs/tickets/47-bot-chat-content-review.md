# 47: Review bot chat and megaphone content

**Area:** Real bots / Chat
**Depends on:** -
**Status:** implemented on branch `chat-content` (not merged); the RWT share is waiting on the owner's decision

## Today

- The megaphone ticker draws from `MegaphoneDialogue.yaml` with weights; "RWTSpam" is 19% of picks. Megaphones are now
  capped server-wide (one per 2 minutes, `-Dsolomapling.botMegaphoneGapSeconds`).
- Dialogue packs (social, training, merchant, conversation) were written over time and never reviewed together.

## What

- Read every line pack; drop or rewrite lines that are spammy, off-tone, real-world, or repeated too often; lower RWT
  spam to flavour level (owner decides).
- Tie megaphones to the world: real events (a boss kill, a rare drop, a party-quest call when a PQ is actually forming)
  instead of random lines.
- Per-player noise settings: `@quiet megaphones` / `@quiet bots` hide bot megaphones or ambient chatter for that player.
- Track which lines players react to (ticket 37's tier log) to prune dead lines.

## Done when

An hour in town reads like a busy server, not a spam feed, and players can turn bot noise down for themselves.

## Done (branch `chat-content`, 2026-10-20)

- **Content review:** every pack was read. 3,212 of 13,160 lines were dropped and 250 rewritten; 149 were added
  (world-event megaphone lines, 36 town-chatter exchanges). Per-pack counts, reasons and examples are in
  `docs/chat/content-review-2026-10.md`. The game-zone host no longer recites a whole node (it used to say ~80
  lines when offering a drink). Malformed `{{line: ...}}` items in TrainingBot are fixed. Town-chatter exchanges
  are dealt like a shuffled deck, so none repeats until all have been used. `DialoguePackCheck` logs
  "N packs, M lines, K errors" at start and runs as a test.
- **RWT spam: 19% → 3% (owner's call).** Every megaphone category weight is `-Dsolomapling.megaWeight.<Category>`.
- **World-event megaphones** (`WorldEventMegaphones`, still through `BotMegaphoneGate`):
  - Boss kills: a notable boss, not its arms or heads. The killing bot flexes, or a bot on the same map congratulates
    the real player. For headline bosses (level ≥ 100) any online bot may congratulate.
  - Rare drops: a scroll or mastery book with base chance ≤ 200 per million (chaos scrolls), or any equipment from
    a boss. The bot that got it flexes, or a bot on the same map congratulates the player.
  - PQ forming: an OPQ lobby bot that can really be invited calls "J> OPQ lvl 55 priest, orbis lobby ch1".
  - An event that finds the gate closed waits in one pending slot, up to 4 minutes, and goes out before any random
    line. The same boss, item or PQ is not repeated within 10 minutes.
- **`@quiet megaphones | bots | all | off`** hides bot megaphones and/or ambient bot map chat, for that player only.
  Bots the player talks to, party members, and lines naming the player still show. Real players, GM notices and
  server notices are never filtered: only the bot speech primitives go through the filter. Settings are kept per
  character in memory. They survive relog and channel change, but not a server restart (no DB migration).
- **New `-D` tunables:**
  - `solomapling.megaWeight.<Category>`
  - `solomapling.worldMegaphones` (on/off)
  - `solomapling.worldMegaphoneCooldownSeconds` (600)
  - `solomapling.worldMegaphoneMaxDelaySeconds` (240)
  - `solomapling.worldMegaphoneHeadlineBossLevel` (100)
  - `solomapling.rareDropMaxChance` (200)
  - `solomapling.quietEngagedSeconds` (180)
- **Not done:** pruning lines by player reaction needs ticket 37's tier log (branch `chat-natural`, not on main yet).

