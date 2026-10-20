# 47: Review bot chat and megaphone content

**Area:** Real bots / Chat
**Depends on:** -
**Status:** open

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
