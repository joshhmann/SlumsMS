# 142: Guild storage with an activity log

**Area:** client + server (NEEDS SERVER)
**Rank:** 14 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** a shared guild bank (items and mesos) with a "Latest 50 storage actions" log (who stored or took what),
  meso transfer, and a search.
- **Evidence:** "Guild Storage##GuildStorageMain", GUILD_STORAGE_RESPONSE (0xE0) ... GUILD_STORAGE_LOG_RESPONSE (0xE9),
  "Move meso from your wallet into guild storage", "Take meso out of guild storage", "Latest 50 storage actions",
  "Stored by: %s", `EzorsiaV2_UI.wz/GuildStorage.img`.
- **How:** server-side storage per guild with rank permissions and an audit log. The client window is the ImGui
  storage view reused.
- **Size:** M. Server is most of it (DB, permissions, log, no-dupe care). Check whether ticket 127 already covers it.
