# 135: Better "view other player" window: equipped item list, pet info

**Area:** client + server (NEEDS SERVER)
**Rank:** 7 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** double-clicking a player opens a larger info window with a scrollable **list of their equipped items
  (hover for full tooltips)**, a pet panel (level, closeness, fullness, pet equip), and party/trade buttons.
- **Evidence:** `CharacterInfo.img` (new): RemoteInfo (button:party, button:trade, button:toggleItemlist, button:showPet),
  ItemList/ItemListEntry, PetInfo (type, level, closeness, fullness, equip). "[UserInfoEquipTooltip] Kuro item list hooks
  decode=", "CUIUserInfo::Draw hook".
- **How:** the server appends an equipment list (and pet stats) to the character info response, gated by a capability
  bit. The client hooks `CUIUserInfo` to draw the extra panel and reuses the native item tooltip.
- **Size:** M. Client: CUIUserInfo hook and panel. Server: an extended char-info packet. Data: our own panel art.
  Players like inspecting gear, and inspecting bots' gear is a nice touch.
