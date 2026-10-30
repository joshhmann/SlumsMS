# 159: Bots advance jobs at the instructor and use 4th-job mastery books

**Status:** open, 1.6.2. **Owner:** Server Dev. Source: owner, 2026-10-30, after the nutnnut/Cosmic README_SERVER_BOTS.md review ("what we havent adopted yes update tickets or create new ones"). Upstream is AGPL-3.0 like ours: credit `nutnnut/Cosmic <sha>` in port commits and docs/PROVENANCE.md. Matrix: 157-nutnnut-tools/adoption-matrix.md.

1. **Job-advance errand** (M): a watched bot walks to its job instructor and advances there (with a short
   celebration line) instead of BotProgression setting the job silently. Falls back to today's instant advance if a
   ferry or fare blocks the trip (upstream hit two such deadlocks). Offscreen bots keep the instant path.
2. **4th-job mastery books** (M): bots want, keep and use the books their skill plan needs (master levels modelled
   in BotSkillPlan); spare books may be sold under SaleRules. Economy-adjacent: books get held back from the market,
   so check bot-shop book supply after.
Tests: errand reaches the NPC on a fresh bot per class; book use raises the master level; fallback when blocked.
