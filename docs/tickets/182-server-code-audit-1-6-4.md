# 182: Fix the server-code defects the audit-164 audit found

Requested by the owner, 2026-11-03 10:07 UTC (02:07 PST).

Target: 1.6.4.

## Goal

Close the defects the server-code audit found, in the order below, through the normal release gate. Every change
that moves an economy number is listed for the owner in "Economy numbers touched"; nobody picks a number on the
authority of the audit alone.

## What the audit was

A read-only audit of the server code, split into eleven lanes: persist, cashitems, socialsys, shopeconomy,
scrollaudit, deathmech, skills, sidecontent, cfgzero, npcaudit, questaudit. Nothing was changed, built or run
against live. The source of truth is `/root/work/agent-runs/server/audit-164/REPORT.md` (536 lines, sections 1-6);
each lane has its own directory with `REPORT.md` and `findings.csv` under `/root/work/agent-runs/server/`.

- 249 rows. Impact histogram 5/4/3/2/1/0 = 29/18/51/71/53/27.
- 98 rows at impact 3 or higher, which are 90 distinct defects.
- About a dozen are live defects caused by code.
- 51 rows are latent config problems (a setting missing from the config file silently becomes 0 or false). One
  validation pass in `YamlConfig` closes all of them.
- Pass 2 re-derived 14 claims independently: 14 of 14 confirmed, with eight corrections to wording or detail
  (listed at the end of REPORT.md, "Pass-2 corrections").

## Owner decisions

All five were answered on 2026-11-03 with the option marked Recommended. The record, with old and new numbers, is
`/root/work/agent-runs/server/audit-164/OWNER-DECISIONS.md`.

| q | bug | decision |
|---|---|---|
| (a) | b289 | Rechargeables (stars, bullets): charge per unit at purchase so buy and sell come from one expression. |
| (b) | b291 | Items 4036550-4036553 get drop rows on the mobs that already drop the neighbouring event tokens. |
| (c) | b292 | Maker level gate applies to every recipe, at the `requiredLevel` already stored. |
| (d) | b293 | Fix the Star Force fail branch and the superior boom, and say so in the patch notes. Success odds are not retuned. |
| (e) | b296 | `STARFORCE_BOOST_PERCENTAGE2` = 0.2, matching `STARFORCE_BOOST_PERCENTAGE`. |

## Work order

### 1. Bug cards b289-b298

Each has a brief at `/root/work/agent-runs/server/s-<id>/BRIEF.md`. Severity is the grade in the bug table of
`/root/work/STATUS-1.6.4.md`. All ten briefs now exist.

| id | sev | title | decision-gated | brief |
|---|---|---|---|---|
| b289 | S1 | Buying a rechargeable charges a flat price but grants a full stack; selling it back pays far more | yes (a) | `s-b289/BRIEF.md` |
| b290 | S2 | Vega's Spell throws away the scroll fed to it and rolls the item's own level (a pure loss at level 15+; 2 live NX items) | no | `s-b290/BRIEF.md` |
| b291 | S3 | Collection tokens 4036550-4036553 are granted nowhere | yes (b) | `s-b291/BRIEF.md` |
| b292 | S3 | Maker has no level gate | yes (c) | `s-b292/BRIEF.md` |
| b293 | S2 | Star Force failure never loses the star/stat; superior boom returns nothing | yes (d) | `s-b293/BRIEF.md` |
| b294 | S2 | `Storage.saveToDB` swallows a SQL error inside the caller's transaction, so inventory is deleted and storage not rewritten | no | `s-b294/BRIEF.md` |
| b295 | S3 | An attack at 0 MP still lands damage; party Heal heals before the caster's refusal | no | `s-b295/BRIEF.md` |
| b296 | S3 | `STARFORCE_BOOST_PERCENTAGE2` missing from production config, so enhanced gear loses its stat share | yes (e) | `s-b296/BRIEF.md` |
| b297 | S2 | Megaphones 5070000/5073000/5074000 are consumed with no broadcast | no | `s-b297/BRIEF.md` |
| b298 | S2 | Crafted party and alliance packets skip leader/rank checks | no | `s-b298/BRIEF.md` |

All paths are under `/root/work/agent-runs/server/`. Per-card proof (fail-before test or hands-on step) is in the
REPORT.md card-mapping table.

### 2. Cardless impact-4 set

No card exists for these; they come right after the cards: persist PS-1 and PS-3, socialsys CFG-1 and ALL-1,
npcaudit NPA-01, questaudit QUA-04 and QUA-05. Details in REPORT.md section 3, "Cardless findings".

### 3. Migration 059 (casino chip shop)

A deploy gap, not a code defect (NPA-01). It goes out on the normal release path. Note that
`059-casino-chip-shop` does not exist on `release-1.6.4` yet; it lives on `163-integration` (commit `9cbdafe3e`).
No code is needed, but the migration has to reach the release branch and the release migrations list.

### 4. cfgzero: one validation pass

One validation pass in `YamlConfig` that rejects or defaults the 51 latent config rows at startup.

### 5. Test-tab rows

After the build lands, run the Test-tab rows in REPORT.md section 4 ("Test-tab rows") on a real client. Eleven
rows: star sell-back, Vega's Spell, Star Force fail, Maker gate, megaphone, leader checks, casino chip shop,
collection tokens, storage save (needs the b294 test, not doable by hand), unaffordable Heal and
`STARFORCE_BOOST_PERCENTAGE2`.

## Economy numbers touched

For the owner. Nothing else in this ticket changes a number.

- b289: buy and sell are priced from one expression. Today 500 mesos buys 9,999 stars and selling pays 10,249;
  after the fix there is no net gain on any mastery.
- b291: new `drop_data` rows for four tokens (mobs and rates copied from the neighbouring event token rows).
- b296: `STARFORCE_BOOST_PERCENTAGE2` goes 0 to 0.2, which raises player power (a 20% share of the item's own
  STR/DEX/LUK/INT).
- b293: Star Force becomes harder than it plays today (a failure now also loses the star/stat). Success odds are
  not retuned. Needs a patch-notes line.

## What is not proven

Nothing was observed in a real client. Every claim is a reading of code, WZ data or read-only DB aggregates.
Four findings still carry their own audit's `[INFERENCE]` label (GUILD-1, GUILD-4, MARR-1, scrollaudit S-01); see
REPORT.md section 6. Each fix needs its fail-before test or the Test-tab row before it counts as closed.

## Relation to other tickets

- [181](181-bot-behaviour-audit-1-6-4.md) is the bot-behaviour audit (60 findings) and stays separate.
- [163](163-bot-audit-1-6-2.md) is the earlier bot audit (1.6.2).
- This ticket owns the server-code audit. A fix that touches both is recorded once, in the older ticket.
