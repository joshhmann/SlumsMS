# 159: Bots advance jobs at the instructor and use 4th-job mastery books

**Status:** open, 1.6.2. **Owner:** Server Dev. Source: owner, 2026-10-30, after the nutnnut/Cosmic README_SERVER_BOTS.md review ("what we havent adopted yes update tickets or create new ones"). Upstream is AGPL-3.0 like ours: credit `nutnnut/Cosmic <sha>` in port commits and docs/PROVENANCE.md. Matrix: 157-nutnnut-tools/adoption-matrix.md.

1. **Job-advance errand** (M): a watched bot walks to its job instructor and advances there (with a short
   celebration line) instead of BotProgression setting the job silently. Falls back to today's instant advance if a
   ferry or fare blocks the trip (upstream hit two such deadlocks). Offscreen bots keep the instant path.
2. **4th-job mastery books** (M): bots want, keep and use the books their skill plan needs (master levels modelled
   in BotSkillPlan); spare books may be sold under SaleRules. Economy-adjacent: books get held back from the market,
   so check bot-shop book supply after.
Tests: errand reaches the NPC on a fresh bot per class; book use raises the master level; fallback when blocked.

## Built (branch 162-joberrands, both behind switches, default off)

- `-Dsolomapling.jobErrand=true`: `JobErrand`. 1st and 2nd job of warrior, magician, bowman, thief and pirate: a
  watched bot (a real player on its map instance) travels to Perion, Ellinia, Henesys, Kerning City or Nautilus and
  walks to the instructor NPC, then advances and says a line. No route, fares it cannot pay, a failed trip, a missing
  NPC or 6 minutes (`-Dsolomapling.jobErrand.timeoutMs`) end in today's instant advance. Offscreen bots, Cygnus, Aran
  and 3rd/4th job stay instant. TrainingBot waits while the errand runs.
- `-Dsolomapling.botSkillBooks=true`: `BotSkillBooks`. A bot that reaches 4th job gets the instructors' master level 10
  on the book-limited skills (`FourthJobMasteries`), its SP stops there, and a book that raises one of them is
  wanted, read after a short pause (on loot or level-up), and kept. Spare books (not wanted) are stocked by bot
  shops only under `SaleRules` (boss-only stays blocked). Bots built before the switch keep their old master levels.
- Open: no hunting or buying of books (they come from loot and players); economy check of bot-shop book supply needs
  the TEST run. Client look of a bot standing at the instructor is untested on a real client.
