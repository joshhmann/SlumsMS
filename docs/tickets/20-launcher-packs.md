# 20: Packed first install for remote friends

**Area:** Launcher  
**Depends on:** -  
**Status:** open

## What

Ship the base layer as about 100 large chunks for the first install, verifying every file hash on unpack; updates stay per-file. Optional Windows Defender exclusion prompt; HTTP range resume.

## Done when

Remote first install is bounded by bandwidth, not request count.
