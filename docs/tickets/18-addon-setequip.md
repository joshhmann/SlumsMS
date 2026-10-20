# 18: SetEquipV3 addon

**Area:** Addons  
**Depends on:** -  
**Status:** open

## What

Server set data first (v83 has no SetItemInfo.img, so it must be written), then the client tooltip (merge into src/tooltip.cpp) and the stat window. Opcodes 0x371B/0x371C. See the addon review.

## Done when

Set bonuses apply server-side and show in the tooltip.
