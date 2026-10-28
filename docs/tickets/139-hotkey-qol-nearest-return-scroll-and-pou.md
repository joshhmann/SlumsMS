# 139: Hotkey QoL: nearest return scroll, and pouch/scripted items on hotkeys

**Area:** client
**Rank:** 11 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** one hotkey that always uses the **right town return scroll** (the nearest town), and the ability to
  put items the client normally refuses on hotkeys (Mineral Pouch, scripted items).
- **Evidence:** "[NearestScrollHotkey] Return scroll hotkey patches installed", "mainfunc.nearest_scroll_hotkey";
  "[ConsumeCategoryHotkey] Mineral Pouch (2101203) + scripted item (243) hotkey patches installed", patch sites
  `CDraggableItem::MapFuncKey`, `CUserLocal::UseFuncKeyMapped`.
- **How:** patch the item-type checks in the func-key mapping and the use dispatch, and choose the scroll id at use time
  from the current map's return map. The server already accepts the normal use packets; just confirm the handlers.
- **Size:** S.
