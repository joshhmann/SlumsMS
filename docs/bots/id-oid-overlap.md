
## Party ids (checked 2026-11-08, read-only; Coordinator's question closed)

Party ids are only ever compared with party ids (MapleMap's `mapParty`, `getAnyCharacterFromParty`,
`MapItem.canBePickedBy`), except in one place: the drop packet's single owner field (PacketCreator.java:1824,
`MapItem.getClientsideOwnerId`), which carries a char id or a party id, and which the client compares with both
its own. A regular's own drop can therefore look like a partied player's party drop when that party's id equals the
regular's char id; the server still refuses the loot, so nothing is lost. Card b368 (1.6.7, S4): start party ids
above the highest possible bot id.
