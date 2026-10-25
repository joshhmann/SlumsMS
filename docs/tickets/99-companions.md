# 99: Companions (1.6.0 epic): bot friends and owned alts

**Area:** Companions
**Kind:** EPIC
**Depends on:** 60 (bot brain), 14 (command parser, done), 49 (buddies, done)
**Status:** open, target 1.6.0 (rc1 "Bots feel alive")

Source: owner grill, 2026-10-25 (`docs/roadmap/1.6.0.md`, section "Owner grill").

## Children and related tickets

- 13 owned alt-bots (the "owned character" identity kind)
- 14 command parser (done; the companion command set extends it)
- 60 bot brain (supplies the decisions: restock, come back, chat)
- 85 party chat with player bots
- 87 second pass through nutnnut/Cosmic (research; port its owned-party design first)
- 51 closed, folded into this epic and 85

## Rules (from the owner grill)

- Companions are bot friends who tag along and your own characters (same account, same world) as bots. **Up to 5 at
  once** (a full party), shared between both kinds. No hiring.
- Normal party rules: EXP shared, drops to whoever gets them.
- Recruit bot friends by asking in chat ("wanna party?", "come with me") or by a normal party invite; the bot accepts or
  declines like a person (likes you, not busy). A bot-friend companion may leave for its own plans.
- Companions follow anywhere you can go (maps, towns, PQs and boss rooms they are allowed in), catching up through
  portals and travel.
- Owned alts: summoned with `@botme <alt>` / `@dismiss <alt>` (a list when no name is given, "come here" in party
  chat). They log off when you do (no offline progress), level only with you (EXP from party play; you do their job
  advances and quests yourself), move items only through normal logged trades, join PQs and bosses as full party
  members, and never leave on their own.
- Priority: the owner's orders win, the brain fills the gaps (restock and come back, chat).
- Commands: movement and formations (follow / stay / patrol / move here / spread / stack / snap); supplies and item
  sharing ("need pots", ammo, return scrolls; spare books/scrolls to whoever can use them); gear sharing ("any
  upgrades?" against your gear, offered through trades); PQ helpers (worker roles in Kerning PQ and the Zakum prequest
  while you lead).

## Done when

A player can recruit up to 5 bot friends and owned alts, run a PQ with them, and dismiss them, on TEST with auto-QA
coverage and an in-game look from the owner.
