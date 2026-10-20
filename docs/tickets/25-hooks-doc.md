# 25: SOLOMAPLING_HOOKS.md: list every core file SoloMapling touches

**Area:** Ops  
**Depends on:** -  
**Status:** open

## What

From the versioning grill: one line per core (non-soloMapling) file the bot branch changes and why, so future MapleRoot/Cosmic updates can be re-applied quickly. Derive the list from `git diff main...persistence-on-v120 -- src/main/java ':!src/main/java/soloMapling'`.

## Done when

The doc exists on the bot branch and every core edit is listed.
