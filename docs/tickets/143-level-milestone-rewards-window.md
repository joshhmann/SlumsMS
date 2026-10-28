# 143: Level milestone rewards window

**Area:** client + server (NEEDS SERVER)
**Rank:** 15 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** a native-style window lists level milestones (10, 30, ... 200, 300) with a reward each and a Claim
  button ("Ready to claim. Click to claim it.").
- **Evidence:** `UIWindow.img/MilestoneRewards`, `CMilestoneRewardsWindow`, "Claiming Level %d reward...",
  "Level 10 requires", "Level 200 requires", "send 0xD9 milestoneLevel=%d", "No level milestone data is available.".
- **How:** the server keeps a claimed-levels set per character (or per account) and a reward table. The client shows the
  snapshot and sends a claim. It shares UI with our daily check-in.
- **Size:** S-M. Good for new players and alts. It also makes the weekly bonus from NOTES easy to add.
