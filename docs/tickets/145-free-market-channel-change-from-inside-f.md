# 145: Free Market channel change from inside FM rooms

**Area:** client
**Rank:** 17 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** change channel while inside FM rooms 1-22 without walking back to the FM entrance.
- **Evidence:** "[FreeMarketChannelChange] FM rooms 910000001-910000022 may change channel".
- **How:** patch the client's field-limit check for those maps, and make sure the server allows the change-channel
  request there and places you in the same room.
- **Size:** S. It helps with merchant-browsing across channels, which matters with bot merchants.
