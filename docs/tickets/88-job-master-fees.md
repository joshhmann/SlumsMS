# 88: Job Master advancement fees (1.6.0)

Owner, 2026-10-25. MapleRoot's Job Master (scripts/npc/9010003.js; Base of Roots 993910000 and Ellinia) gives
every advancement instantly with no test, which makes the stock job quests pointless. Charge for the skip:

| Advancement | Fee |
|---|---|
| 1st | free |
| 2nd | 200,000 mesos |
| 3rd | 10,000,000 mesos |
| 4th | 100,000,000 mesos |
| 5th | unchanged (MapleRoot: 3 rebirths + item 4036541) |

Same tiers for Explorers, Cygnus and Aran (and Super Beginner / Synth Master where the Job Master advances them).
The stock routes (instructors, quests, Leafre tests) stay free. Bots advance in code, not through the NPC: unaffected.

Done when: the menu shows the fee before you confirm; not enough mesos = polite refusal and no advancement; the fee
is taken in the same step as the job change (never one without the other); the Leafre master levels (1.5.2) still
come with a paid 4th job; unit test over the fee table and the refusal path; an auto-QA check (vb family).
