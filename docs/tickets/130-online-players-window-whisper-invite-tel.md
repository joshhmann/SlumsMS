# 130: Online Players window (whisper, invite, teleport)

**Area:** client + server (NEEDS SERVER)
**Rank:** 2 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** a list of who is online (name, level, job, channel), filtered by **in your guild / party / buddy
  list**, auto-refreshed every 10 s. Each row has whisper, party invite, and **teleport to that player for 1 VIP Teleport
  Rock** (with a confirmation).
- **Evidence:** `COnlinePlayersWnd`, "Online Players###OnlinePlayers", ONLINE_PLAYERS_RESPONSE (0xC0), "In your guild",
  "In your party", "On your buddy list", "Auto-refresh 10s", "Showing %d of %d players", "Invite to party",
  "Teleport (1 VIP Rock)", "TP = 1 VIP Rock", "That player is in a different world.", "That party member is not in your
  channel.", "Teleporting to that map is not allowed."
- **How:** the server sends a snapshot of online characters. The client renders it and reuses the native
  whisper and party-invite packets. Teleport reuses the VIP Rock "to player" logic on the server, with field-limit
  checks.
- **Bots:** this is our biggest win and our biggest risk. With 3,000 bots the list needs a "players only / include bots"
  filter (or bots shown as a count), or it becomes noise. It also makes a natural place to find a bot to party with.
- **Size:** M. Client: ImGui list. Server: snapshot packet, filtering, rate limit. Data: none.
