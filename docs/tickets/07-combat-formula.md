# 07: Real combat damage from stats, gear, skills and buffs

**Area:** Real bots  
**Depends on:** 05  
**Status:** open

## What

A server-side formula (after nutnnut's CombatFormulaProvider) behind our attack adapter; offscreen LOD uses averaged numbers. PacketCreator and AbstractDealDamageHandler stay untouched.

## Done when

Bot damage tracks its gear and level; the packet bytes are unchanged; tests.
