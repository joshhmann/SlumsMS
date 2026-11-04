# 189 — Marriage with your own alts (altbots) for 1.6.4

Status: stages 1 and 2 built on branch 164-altmarry (switch -Dslums.ownedalts.marry, default OFF), awaiting review and the TEST checklist; stage 3 (bonuses) still open with the owner, nothing built (2026-11-04 20:50 UTC). Earlier: open, docs only. Decided by the owner on 2026-11-03 (20:55 UTC / 12:55 PST, recorded at filing). Target: 1.6.4.
Economy: none changed beyond one wedding ticket paid as a fee (stage 2). Economy numbers touched: marriage EXP bonus +10% playerbot spouse, +5% altbot spouse (stage 3), and the wedding ticket fee (one `5251000`, stage 2).

## Why

A player asked whether they can marry their own `@botme` alt. Today the proposal is refused up front
(`RingActionHandler.java:102-105`) with advice that does not work ("Use @dismiss and log in on it": the alt shares the
owner's account, so it cannot log in separately). An alt has no client, so it cannot press Yes in the cathedral.
Design note: `/root/work/agent-runs/server/marriage-audit/alt-wedding-design.md` (option B2). Audit rows MA-09, MA-10:
`/root/work/agent-runs/server/marriage-audit/bots-alts/REPORT.md:158-167`. Bonuses today and the meaning of "regulars":
`/root/work/agent-runs/server/marriage-audit/marriage-bonuses-today.md`.

## Chosen by the owner (2026-11-03 20:55 UTC / 12:55 PST, recorded at filing)

- For owned alts (altbots) the marriage **ceremony is skipped**.
- It is the **same marriage status** as a normal marriage: stock `marriages` and `rings` rows, a ring, divorceable.
- **Divorce hook: yes.** The alt side can be cleaned after a dismiss.
- The alt's ring **is equipped**, so the couple look shows.
- **Ticket fee: yes.** The owner pays one wedding ticket as a fee (the cathedral common ticket `5251000`,
  `ItemId.NORMAL_WEDDING_TICKET_CATHEDRAL`; `scripts/npc/9201005.js:30` uses it; the chapel script `9201008.js:30` uses
  `5251001`, a different item). John (`9201002.js`) is the cathedral flow, so the fee is `5251000`.
- Marriage to an altbot gets **weaker bonuses** than marriage with regulars: "the real bonuses come from working with
  actual regulars". **Regulars = the playerbots (the regular population bots), as opposed to altbots (owned alts,
  `@botme`). Confirmed by the owner.**
- Design: option B2 of the note, all optional extras accepted, behind the boot switch `-Dslums.ownedalts.marry`
  (default **OFF**), after option A's guards (reword the refusal, MA-09, MA-10) and the deletion guard.
- **Decided 2026-11-04 00:34 UTC / 2026-11-03 16:34 PST: marriage with playerbots stays as it is today.** With `@romance on`,
  dating, engagement and marriage with a playerbot work; a cold proposal is declined. Owner confirmed this matches what they want.

## Work stages

| Stage | What | Effort | State |
|---|---|---|---|
| 1 | Option A guards: reword the alt refusal (drop the `@dismiss` advice); MA-09 (invite mode 5 refuses an alt name); MA-10 (mode-2 accept and the ceremony start refuse an alt). **Deletion guard:** refuse deleting a married character (`DeleteCharHandler.java:46` has only a comment). That also fixes the ghost-spouse login crash for real players | ~1.25 d | brief ready |
| 2 | B2 proxy marriage: owner proposes to the out alt (hook modes 0 and 2, engagement rows and tokens), new John choice "Marry my alt now" (`OwnedAltWedding.marry`), no queue, no instance; fee of one `5251000`; the alt's ring equipped; divorce hook for the alt side; switch default OFF, a `docs/release/<v>.conf` line | ~3.5-4 d (incl. ring equip 0.5 d and divorce hook 0.5 d) | brief ready, starts after the merges below |
| 3 | DECIDED: marriage EXP bonus +10% playerbot spouse, +5% altbot spouse (same map, EXP only), switch `-Dslums.marriage.expbonus` default OFF; placement and stacking cap to confirm at review | ~1 d | brief ready, starts after stage 2 |

### Stage 3 (DECIDED by the owner 2026-11-04 03:45 UTC / 2026-11-03 19:45 PST): marriage EXP bonus
There are no marriage bonuses today beyond ceremony EXP, an achievement, an Amoria PQ couple route, spouse chat and
cosmetics (`marriage-bonuses-today.md` section 1), so this is a new capability. Playerbot-marriage baseline is unchanged
(`@romance on`, cold proposal declined; `BotRomance.java:814-860`, `RomanceWedding.java:349`).
**Chosen by the owner:** marriage EXP bonus **+10% with a playerbot spouse** (a regular population bot), **+5% with an
altbot spouse** (owned alt). This replaces the earlier ".25 ratio" idea. Terms accepted in the proposal:
- EXP only; no drop bonus (drops are already 4x). Marriage only (not dating or engagement).
- Only while the spouse is on the same map as you. A playerbot spouse must be present on that map; an altbot spouse must be out (dismissed or offline gives nothing).
- Ends the moment you divorce. Marriage state is read from the stock `partnerId`/`marriageRing` (`isMarried`).
- Added on top like Pendant of the Spirit: additive after the multiplier chain, not inside it, so it does not compound with coupons or the family buff.
**Placement (proposed by the director, owner accepted the numbers; confirm placement at review).**
**OPEN:** whether the bonus sits under any EXP stacking cap. Reviewer note: `bonus-rate-calibration.md` recommended N <= 5 to stay clearly
below party +20..60%, coupons 2x/3x and pendant up to +30%; 10% is above that guidance, the owner's choice.
Brief: `/root/work/agent-runs/server/s-marrybonus/BRIEF.md`; boot switch `-Dslums.marriage.expbonus`, default OFF.

## Start conditions
Stage 1 may start any time. **Stage 2 starts only after the branches `164-marr-java`, `164-marr-scripts` (marriage
fixes, ticket 188) and `164-alt-bundle` (ticket 183) are merged into `release-1.6.4`.** They edit the same code
(`RingActionHandler.java`, `OwnedAltService.java`, the John and Nicole scripts). Stage 3 starts after stage 2 exists in `release-1.6.4`.

## Relation to other work
- **Ticket 185 item 12 "one relationship store" (merge five tables) and the relationship window idea** (`/root/work/agent-runs/server/relationship-window-scope.md`): both want one read model of "who is bonded to whom". A marriage bonus that differs for an altbot spouse and a regular spouse needs that same read model (stage 3 overlaps `@bonds` / `RelationView`). Do not build a second store here; stage 1 and 2 write only stock rows (`marriages`, `rings`, `partnerId`).
- **Families question** (stock family tree, bot juniors only, `BotFamily.java:1-30`): not touched here; whether an alt spouse counts as family is open.
- Ticket 188 (marriage fixes) and `OwnedAltInvite` (`-Dslums.ownedalts.acceptownerinvite`) are the precedents for the hook style.

## What is not proven
Source reading only. Nothing was run, on TEST or locally. Line numbers are from `release-1.6.4` at `6d18f961e` and will
drift: re-find by text. The ring item stats and the Amoria quests 8862-8871 are game data outside the repo and were not
read. The human TEST checklist is in the design note section 4 and in the stage 1/2 brief.
