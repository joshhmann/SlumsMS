# 88: Job Master advancement fees (1.5.2)

Status: closed 2026-10-25 (done in 1.5.2, commits c679bac1b and f57944b2a).

Owner, 2026-10-25; in 1.5.2 at the owner's call. Fees set from live players' mesos (about half of what the poorer player carries at 30 and 70; 4th kept high). Moved into 1.5.2 at the owner's call (before the first live player reaches 4th job). MapleRoot's Job Master (scripts/npc/9010003.js; Base of Roots 993910000 and Ellinia) gives
every advancement instantly with no test, which makes the stock job quests pointless. Charge for the skip:

| Advancement | Fee |
|---|---|
| 1st | free |
| 2nd | 800,000 mesos |
| 3rd | 15,000,000 mesos |
| 4th | 100,000,000 mesos |
| 5th | unchanged (MapleRoot: 3 rebirths + item 4036541) |

Same tiers for Explorers, Cygnus and Aran (and Super Beginner / Synth Master where the Job Master advances them).
The stock routes (instructors, quests, Leafre tests) stay free. Bots advance in code, not through the NPC: unaffected.

Done when: the menu shows the fee before you confirm; not enough mesos = polite refusal and no advancement; the fee
is taken in the same step as the job change (never one without the other); the Leafre master levels (1.5.2) still
come with a paid 4th job; unit test over the fee table and the refusal path; an auto-QA check (vb family).
