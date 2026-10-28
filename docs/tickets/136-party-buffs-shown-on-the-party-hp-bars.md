# 136: Party buffs shown on the party HP bars

**Area:** client + server (NEEDS SERVER)
**Rank:** 8 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** the party HP window is widened and shows each member's **active buffs** as icons, so you can see
  who still needs Hyper Body or Holy Symbol.
- **Evidence:** "Party buff snapshot received: members=", "CUIPartyHP::Create ... fixedWidth ... maxIconsPerRow",
  "native party hp create size expanded".
- **How:** the server sends a per-member buff snapshot when buffs change. The client hooks `CUIPartyHP::Create/Draw`
  to widen the window and paint stock buff icons.
- **Size:** M. Good with support bots (Priests handing out buffs).
