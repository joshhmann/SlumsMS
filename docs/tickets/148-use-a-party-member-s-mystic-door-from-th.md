# 148: Use a party member's Mystic Door from the party window

**Area:** client
**Rank:** 20 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** "Use %s's Mystic Door?" from the party list, without hunting for the door on the map.
- **Evidence:** "Use %s's Mystic Door?", "Mystic Door is not available right now.", NOTES ("Mystic Door use from the
  party window").
- **How:** the client sends the door-enter request for that member's door, and the server checks map, party and door.
- **Size:** S-M. Handy with Priest bots.
