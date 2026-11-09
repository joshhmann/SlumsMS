# LPQ feed lines (1.6.7, boss-feed-lines batch 2): expected outcome

Observe only. Nothing about LPQ plays differently; these are feed and log lines. Spec: Server Dev plan
`boss-feed-lines-167.md`, "Batch 2: LPQ"; analyser card `lpq.yaml` (`missing_lines_for_server_dev`). Where the two
differ in wording the card wins (none found; the card defers to the plan's shapes).

## The tag and the sinks

Every line below is `RUN <text> [lpq, leader X]`, X being the party leader's name (the only name in any line; every
other fact is a count or a character id). Each line goes to:

1. the QA feed, topic MECHANICS, for the run's real players (TEST only, only while a runner polls); and
2. the server log at INFO, logger `BossFeed`, as `[Mechanics] <instance> <line>`.

The existing MECHANICS reporter writes to BotLog.txt only, and only when a mechanics run is armed on the party. The new
lines do not depend on a run being armed, so a human-only LPQ is covered too; that is why they also use `BossFeed`.

## Existing shapes (must not change, byte for byte)

- `RUN start lpq stage 1 in <instance>: ...`
- `RUN stage N cleared in S s (...) [lpq, leader X]`
- `RUN end lpq in <instance>: cleared after S s ...`

## New lines

| Line | When |
|---|---|
| `RUN lpq stage N hand-in P passes [lpq, leader X]` | the leader hands in at the stage NPC and the passes are taken. N = 1-5, 7 (P = 25, 15, 32, 6, 24, 3) and 9 (P = 1, the key; same shape so one parser reads all) |
| `RUN lpq stage N hand-in refused: has P of Q passes [lpq, leader X]` | the leader talks to the NPC after the intro, holds fewer than Q, nothing is taken. P = what he holds |
| `RUN lpq stage 6 puzzle row R of 15 [lpq, leader X]` | a climber takes the right portal out of row R (1 = the lowest row, h000-h002, up to 15 = h042-h044) and R is higher than any row the party has solved. Once per new highest row, so at most 15 lines |
| `RUN lpq stage 6 fall to start from row R [lpq, leader X]` | a climber takes a wrong portal out of row R and lands at the start. At most one line per 10 s per instance |
| `RUN lpq stage 8 combination try T, K right [lpq, leader X]` | five boxes are occupied and the leader asks for the check. T counts the instance's tries from 1; K is how many of the occupied boxes are in the combination (0-5; 5 clears the stage). Not written when fewer than five boxes are covered (no try was made) |
| `RUN lpq cleared, K members to 922011000 [lpq, leader X]` | LudiPQ `clearPQ`, K = members in the instance |
| `RUN lpq rewards exp E to K members [lpq, leader X]` | same call; E = the stage 9 clear bonus before each member's own exp rate |

## Tests that guard this

- the three existing shapes unchanged (a test builds each and compares the exact text);
- one test per hook, written to fail on the base code: stage hand-in and refusal (stages 1-5, 7, 9), stage 6 first row,
  not repeated for a lower or equal row, fall and its 10 s limit, stage 8 try, clear and rewards;
- a hook-removal guard: a hook that is missing from a script makes its test fail.

## What existing characters keep

Everything. No item, exp, position or progress changes; only log and feed lines are written.
