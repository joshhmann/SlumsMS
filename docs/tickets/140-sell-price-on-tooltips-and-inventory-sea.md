# 140: Sell price on tooltips and inventory search

**Area:** client
**Rank:** 12 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** equip tooltips show the **NPC sell price**, and a search box finds an item in your inventory or
  storage by name or id.
- **Evidence:** "[NpcEquipSellPrice] ... CItemInfo::GetItemPrice hook"; "Search your inventory (name or ID)",
  "Search your inventory for the typed name (Enter)", "Search stored items", "SEARCH_INVENTORY_RESPONSE (0xE5)".
- **How:** a tooltip hook reads `price` from Item.wz. Kuro searches on the server, but our client can search its own
  inventory locally.
- **Size:** S. The search fits ticket 127's storage work.
