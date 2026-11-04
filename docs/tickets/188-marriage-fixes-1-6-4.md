# 188 — Marriage fixes for 1.6.4

Status: open. Requested by the owner on 2026-11-03 (UTC 11:41, Pacific 03:41). Target: 1.6.4. Economy: none.

## Why

A friend said marriage isn't working. Two read-only audits followed (reports in
`/root/work/agent-runs/server/marriage-audit/`):

- `player/REPORT.md` — the real-player flow, 13 findings (F1-F13).
- `bots-alts/REPORT.md` — marriage with bots and owned alts, 14 findings (MA-01 to MA-14).

The flow exists end to end, but nobody has run it on a real client. The chapel booking loses the ticket and the
invitations, the wait between booking and ceremony can silently eat the ticket, and a lost ring item locks a
character out of divorce and re-marriage.

Two worker branches, both from `release-1.6.4`, no shared files:

- Script side: branch `164-marr-scripts` (worker `marriage-fix-scripts`).
- Java side: branch `164-marr-java` (worker `marriage-fix-java`).

## In scope

| Id | What | Side |
|---|---|---|
| F1 | Chapel booking (`9201008.js`) throws "Channel is not defined" after taking the ticket; no invitations | Script |
| F12 | Both booking scripts destroy the ticket before checking the reservation result | Script |
| F3 | Ceremony-timing trap: "at null from now" text and the ticket is lost on a failed or late entry. Script part: text and ticket handling in the NPC. Anything needing a Java change is written down, not done here | Script (+ Java part, see report) |
| F9 | Divorce takes the 500,000 mesos before the ring break and trusts a stale "equipped" flag. The amount does not change | Script |
| F2 | Orphan marriage (partner set, ring object missing) cannot be divorced or re-married | Java |
| F6 | `Character.getMarriageInstance` has an inverted type test and can throw | Java |
| F13 | `createRing` result is not checked when the rings are given | Java |
| MA-01 | Same ring-missing divorce crash when the spouse is a bot | Java |
| MA-02 | `@romance divorce` gives the wrong answer when the ring is gone | Java |
| MA-03 | A failed bot wedding burns the whole 24 h window | Java |

## Out of scope, and why

| Id | Why not here |
|---|---|
| F4 | Wedding-talk and wedding-talk-more share opcode 0x8B. That is client protocol; needs a client check first |
| F5 | `WEDDING_GIFT_LIMIT` and `WEDDING_BLESS_EXP` values and the missing `WEDDING_*` config keys are owner balance numbers |
| F7 | Carat-ring boxes: latent, nobody can get those boxes |
| F8 | No client route into Amoria: cosmetic (`@goto amoria` works) |
| F10 | Invitation expiry looks like a boot-time placeholder: check separately |
| F11 | Wedding Shop 9201020 garment stock: needs a live DB read, owner or Server Dev |
| MA-04, MA-07, MA-10 to MA-14 | Not asked for in this effort (MA-04 and MA-07 are by design) |
| MA-05, MA-06 | Left alone (stale rows; bot ring is cosmetic by design) |
| MA-08, MA-09 | S4, not asked for |

## Open owner question

A wedding path for owned alts is not decided (`PLAYERBOT-LANE-REPORT.md:362-365`). Nothing here changes it.

## What is not proven

Nothing was run on a real client. The unit tests cover the scripts and handlers in isolation only. The TEST
checklist at the end of `player/REPORT.md` must be run by a human before this counts as fixed.

## Economy

None. No price, rate, cap, stock or config value changes.
