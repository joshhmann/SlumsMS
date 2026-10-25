# 20: Packed first install for remote friends

**Area:** Launcher  
**Depends on:** -  
**Status:** open, target 1.6.0 (launcher work). Owner decision 2026-10-25: yes, before more remote friends join.

## What

Ship the base layer as about 100 large chunks for the first install, verifying every file hash on unpack; updates stay per-file. Optional Windows Defender exclusion prompt; HTTP range resume.

## Done when

Remote first install is bounded by bandwidth, not request count.

## Decision (Owner decision 2026-10-25)

Yes: packed first-install chunks, done before more remote friends join. Launcher lane, target 1.6.0. See also the launcher scale concerns (verify-on-Play, split manifest) before hosting.
