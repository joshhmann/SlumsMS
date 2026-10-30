# 87: Second pass through nutnnut/Cosmic for player-bot logic (1.6.0)

Source: owner, 1.6.0 notes page, 2026-10-25 ("Look into the repo more to find any other useful things").

The 1.5.2 pass ported stability fixes (ticket 80) and some misc items (trade cap, !warp by name, Zakum prequest).
This pass reads nutnnut/Cosmic's main, dev and experimental branches for bot behaviour we lack (party/PQ play,
shops and market, movement, combat, social), compares with our bot brain plans (ticket 60), and lists what to port
with effort. Credit nutnnut/Cosmic (AGPL-3.0) on ports; don't port its unauthenticated web admin.
Earlier reports: scratchpad nutnnut-bots-report.md / nutnnut-misc-report.md (2026-10-24).

Parent: ticket 99 (Companions epic).

## Follow-up (2026-10-30)

Third review against README_SERVER_BOTS.md: nothing new upstream since this ticket (dev 77e0c5bc9, 2026-08-28).
Not-adopted items became tickets: 157 (admin views and debug tools, started), 158 (questing), 159 (job-advance
errands, mastery books), 160 (gear progression, scroll valuer; needs the owner's economy yes), 161 (build variants,
party support), plus additions to 99 (companion commands and sharing) and 112 (Maker plan and batch). Still open
from here: KPQ automation (rank 6, part of 1.7.0 "bots play the mechanics"; don't copy the NPC bypasses) and the
nav/movement fixes (NN-011).

## Owner decision (2026-10-30): adopt everything not yet adopted

"everything not adopted we should adopt." Scope: every "not adopted" and "partly" row in
157-nutnnut-tools/adoption-matrix.md, through tickets 157 (debug tools, admin views), 158 (questing), 159 (job
errands, mastery books), 161 (builds, party support), 162 (full gear optimization, includes 160), 99 additions
(companion commands, sharing, formations, buddy-menu summon), 112 additions (Maker plan and batch, after the Maker
skill), KPQ automation (1.7.0 mechanics) and the nav/movement fixes (NN-011). Owner rule: login-less pages go into our own login admin page, and useful tools get our own version (see ticket
157). Not copied: their server code itself and the unlicensed "wisteria" client edit; cross-account ownership
transfer stays out unless the owner asks. Where ours is ahead (population, LLM chat), ours stays.
Order: 157, 162, 158, 159, 161 in parallel worktrees; 99 additions after 162 (upgrade sharing overlaps); 112 after
the Maker skill; KPQ with the 1.7.0 mechanics work.

## Owner decision (2026-10-30, later): all of it in 1.6.1, hold everything

"So my friends want alt bots so no release of anything till it's ready." Answers: **every nutnnut port** is in
scope for 1.6.1, and **everything else is held** (no hotfix): one 1.6.1 release when it is all ready. So tickets
157, 158, 159, 161, 162, the 99 and 112 additions, KPQ automation, the Temple of Time driver and the NN-011 nav fixes
all target 1.6.1. The 162-* branch names stay (they branch from release-1.6.1 and merge into it). Each feature keeps
its switch; which ones are ON at release is decided at integration after QA. Dependency: the Maker plan/batch needs
the Maker skill (ticket 112) first.
