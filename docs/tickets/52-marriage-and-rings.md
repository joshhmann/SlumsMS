# 52: Marriage, weddings and couple/friendship rings

**Area:** Server / Cash Shop / Real bots
**Kind:** FIX (rings unbuyable), verify (weddings), BUILD (bot weddings)
**Effort:** M overall: rings M, wedding check S, bot weddings M
**Depends on:** Cash Shop window (`cashshop-fixes` and `cashshop-catalog-tool` work), 21 (world events) for bot
weddings
**Status:** closed 2026-10-25 (done in 1.5.0, branches gameplay-partner-rings-150, gameplay-marriage-result-150, bots-affinity-150, bots-drama-150).

Research: [docs/real-bots/social-systems-research.md](../real-bots/social-systems-research.md).

## Today

- **Code:** stock Cosmic and complete.
  - Handlers: `RingActionHandler` (propose, accept, divorce), `WeddingHandler`, `WeddingTalkHandler`,
    `WeddingTalkMoreHandler`, `SpouseChatHandler`.
  - `Marriage`, `Ring`, and the wedding events `WeddingCathedral.js` and `WeddingChapel.js`.
  - Amoria NPCs 9201000 (Moony, engagement boxes), 9201001 (Nana, proof of love) and 9201002-9201014 (the wedding
    staff) all match Cosmic. `9201001.js` has its script pasted twice; harmless, clean it up.
- **Wedding tickets:** 5251000-5251003 are in the SlumsMS Cash Shop catalogue.
- **Live:** 0 marriages, 0 rings, 0 characters with a partner. Never tried end to end on SlumsMS.
- **Rings cannot be bought:** the SlumsMS Cash Shop window replaced the real Cash Shop, and
  `tools/cashshop/catalog_rules.py` leaves out every ring that needs a partner record ("no partner flow in the
  window"). That covers crush 11120xx, friendship 11128xx and wedding-effect 1112803-09.
  - Stock `CashOperationHandler` action 0x1D (crush) and 0x23 (friendship) create the pair with `Ring.createRing`,
    but nothing sends those actions now.
- **Bots:** a proposal to a bot is ignored and expires. Bots have no partner state.

## What

- **Rings (FIX, M):** add a partner flow to the Cash Shop window.
  - Buying a crush or friendship ring asks for the partner's name, which must be an existing player character, then
    calls the stock `Ring.createRing` path: the buyer's copy, the partner's copy by gift, and the
    `rings` rows.
  - Re-include those ids in the catalogue and lint.
  - Same-gender couples: a friendship ring, as stock does.
- **Weddings (verify, S):** one end-to-end run on the test server with two test characters:
  - Nana proofs → Moony box → engagement → wedding ticket → reservation (`WEDDING_RESERVATION_*` config) →
    ceremony → wedding ring;
  - then spouse chat, and divorce.
  - File any breakage as a FIX.
- **Bot weddings (BUILD, M):** bots marry **only bots**.
  - A rare scheduled world event (ticket 21): two bots with a long-standing pairing in their personas hold a
    Cathedral or Chapel wedding in Amoria. A server notice and megaphone invite players as guests.
  - Guests get the stock bless EXP (`WEDDING_BLESS_EXP`).
  - The pairing is stored bot-side (`botroster` persona or a small `botpartner` table), not in `marriages`, unless
    the stock event needs the rows; then use bot ids there, which have no foreign key.
- **Player proposes to a bot:** the bot always declines, in character ("lol we just met", "im taken"), and the
  engagement box is kept.
  - Bots never propose to players.
  - Marrying a player needs an explicit owner decision first.

## Done when

- A player can buy a friendship or couple ring for another player, and both see the ring effect.
- The wedding run passes on test, or each failure has its own FIX ticket.
- A scheduled bot wedding runs on test without errors, players can attend and bless, and no `characters` rows
  change.
- A proposal to a bot gets a decline line and nothing is consumed.

## Risks

- **Ring records are cash items with partner links:** a broken flow leaves orphan `rings` rows. Write both sides in
  one transaction.
- **Bot weddings use the event instance and reservation queue:** never let a bot event block a real couple's slot.
  Real reservations win.
- **Consent:** bots never pursue players romantically. Persona text must not imply a relationship with a real
  player.
- **Never reveal AI.**
