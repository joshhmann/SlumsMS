# Ticket 69: auto-QA "v83 basics": the stock features a real player uses

Status: open, 2026-10-25. Owner OK'd. After ticket 68 (or alongside). TEST server only.

## Why

Our auto-QA covers the features we built (bots, chat, guilds, gacha). Nothing checks that a player can still do the
ordinary things v83 offers, and those broke silently (an NPC says "no options available", a delete fails, a PQ never
ends). Bots never exercise these paths.

## Do

Add an auto-QA family `vb-*` (pretend player, `!qa` staging, nightly with the rest), one check per basic feature,
each asserting the outcome, not just that nothing threw:
- `vb-1` hair, hair color, face, lens and skin at the Henesys salons with the right coupon (style actually changes).
- `vb-2` delete a character with PIC.
- `vb-3` clear one party quest (KPQ: stage the party, finish, reward given, warped out).
- `vb-4` the mount path: quest 6002 room and hand-in, 6007 Tatamo bottle, 6003 perfume, catch the hog in 922200000.
- `vb-5` buy and sell at an NPC shop, use a Cash Shop coupon (EXP card).
- `vb-6` a job advancement and a skill point spend.
- `vb-7` storage, Duey, trade between two pretend players.
Also: log every NPC dialog line the pretend player gets, and fail any check whose NPC says "no options",
"can't", "closed", "error" or similar when the check expects success.

## Done when

The `vb-*` checks pass nightly on TEST, each has failed at least once against a deliberately broken build (or the
pre-fix commit for vb-1/vb-3), and `docs/qa/QA.md` lists them.
