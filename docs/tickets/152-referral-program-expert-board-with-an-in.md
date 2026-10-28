# 152: Referral program ("Expert Board") with an invite link

**Area:** client + server (NEEDS SERVER)
**Rank:** 24 of 25 in the PlayKuro client research
**Status:** open (proposed 2026-10-28, not scheduled)

Source: PlayKuro client research (owner request 2026-10-28), static reading only; full note in docs/research/kuro-client-features.md. Ideas only: we build our own version, never copying their art, scripts or code.

## Research

- **For players:** veterans enrol as "Experts", share an invite link, and see their recruits' progress. Milestones
  pay out ("Expert Program - Milestone", "Recent:", "waiting on playtime").
- **Evidence:** `[ExpertBoard]`, "Become an Expert", "Invite link copied to the clipboard.", "No recruits yet. Share your
  invite link to bring in a new player.", REQUEST_BOARD, "BOARD role=%u season=%d recruits=%u".
- **Size:** M (website signup tracking plus server rewards). Low priority for a friends server, but cheap if
  sign-ups grow.
