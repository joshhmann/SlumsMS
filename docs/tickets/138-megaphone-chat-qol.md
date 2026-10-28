# 138: Megaphone chat QoL

**Area:** client
**Rank:** 10 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** (a) hover an item shown in an **Item Megaphone** in chat to get the full item tooltip; (b) click a
  megaphone line to whisper its sender (ear icon); (c) the unused "Spouse" chat target becomes **"Megaphone"**: typing
  with it selected uses a Super Megaphone from your cash inventory (nothing is sent if you have none).
- **Evidence:** "[ChatItemMegaTooltip] show item=", "[ChatMegaphoneWhisper] chat bar now whispers",
  ";1 = megaphones sent from that target allow click-to-whisper (ear icon)", "installed: Spouse chat target now sends a
  Super Megaphone (5072000)", "[Megaphone] You need a Super Megaphone in your Cash inventory".
- **How:** hooks on chat-line decode and draw (keep the item payload, show `CUIToolTip` on hover), plus
  `CUIStatusBar::SetWhisperTarget`, and a remap of the chat target to send the normal use-cash-item megaphone packet.
- **Size:** S each. Note that `src/resolution.cpp` already mentions megaphones, so check overlap first.
