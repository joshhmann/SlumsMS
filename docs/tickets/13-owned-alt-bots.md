# 13: Owned alt-bots (alts as companions)

**Area:** Companions  
**Depends on:** 05  
**Status:** open

## What

A new identity kind: a real `characters` row loaded through BotClient, saveCharToDB allowed, outside the roster, director and 1e9 range. Commands to summon your alt.

## Done when

An alt joins your party as a bot and logs in normally later with its progress kept.

Parent: ticket 99 (Companions epic).

Owner 2026-10-25: in 1.6.0 as a child of ticket 99.

## Foundation checkpoint (branch `160-owned-alts`)

The default-off identity, same-account/world load, gm0 summon/dismiss commands, synchronous final save,
login reservation/takeover, owner logout/account cleanup and shared five-companion capacity are implemented.
Population progression, persona chat and generated trade stock exclude alts. Local unit tests cover save and
login races; no deployment or real-client validation has been performed.

This ticket remains open for companion drivers and TEST acceptance. Following, formations, combat/loot,
automatic normal-trade offers, and PQ roles are later work. See
[`docs/qa/owned-alts-foundation.md`](../qa/owned-alts-foundation.md) for identity contracts and `!qa run oa-1` checks.
