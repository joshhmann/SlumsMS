# 185: Bot and alt improvement backlog (needs an owner decision or a later start)

Requested by the owner, 2026-11-03 10:36 UTC (02:36 PT). Target: 1.6.4. Docs only.

## Read this first

**Nothing in this ticket is started. No briefs exist.** It is a menu. Each item becomes its own ticket and its own
helper brief only after the owner picks it. Sources: `/root/work/agent-runs/server/audit-164/BOT-ALT-IMPROVEMENTS-INTERNAL.md`
(read in full; it says which parts of its own sources it did not read) and the internal-audit tables it cites.

Other tickets, so nothing here is counted twice:
- Ticket 183: the cheap items with no owner decision (owned-lifetime sweep, mint/destroy ledger, alt bundle AL-1/AL-2/AL-4).
  Restock receipt and wear predicate are parked there (ticket 184 entry 1).
- Ticket 186: the other-repo bot audit (`research/COMPARISON.md` ranked candidates, the boss-fight gap, and the seven
  measurements). Not repeated here.
- Ticket 184: places where in-flight work could collide. The "overlap risk" column cites its entries.

Copying source from another repo needs a provenance entry in `docs/PROVENANCE.md` and licence compliance (all five
repos are AGPL-3.0); an idea-only port is recorded too. Items below are SlumsMS-internal, so this applies only if one is
later answered by an upstream design.

## Table 1: `BEHAVIOUR.md` "Better systems" (`/root/work/audit/BEHAVIOUR.md`)

Items 1, 2, 4 and 9 are in 183 or parked there and are not listed. The internal file's line numbers for these rows are
two lines off; the lines below were re-read.

| # | Item | Bot/alt | Effort | Owner decision | Overlap risk | Where |
|---|---|---|---|---|---|---|
| 3 | `BotBlockList` as a concurrent set | bot | 0.5 d | n | **covered by 181** (`sweep#B1`, brief `s-bot181-sweep`; 184 entry 8). Do not start. | BEHAVIOUR.md:572 |
| 5 | One life-day clock and interrupt predicate (`train#F1`) | bot | 1 d | n | 181 `s-bot181-train`/`-life` fix `TrainingBot` now (184 entries 1, 8) | :576 |
| 6 | Pending offers as leases (`F-04`/`F-05`); changes when a sale holds | bot (shops) | 2 d | n (economy effect: sale holds) | `ShopOfferSystem` is edited by 181 `-market` (184 entries 3, 8) | :578 |
| 7 | One transport step that owns message delivery (`social#1,2,3,5`) | bot | 2 d, 40-line spike first | n | 181 `-social` edits `BotConversation` (not file-compared, 184 not checked) | :580 |
| 8 | One conserved sale primitive with the price floor inside; relist clamped to price band and paid price (`F-01`, `F-03`) | bot | 4 d | **y**: the price floor | 181 `s-bot181-s1` builds the `F-03` floor behind a switch (184 entry 3); its height is an open owner question | :582 |
| 9 | Mint/destroy ledger | bot | 1.5 d | n | in 183 item 2, not here | :584 |
| 10 | One `TrainTarget` produced by the chooser (`F6`) | bot | 2 d | n | `TrainingBot` is touched by 181 train/life; not checked against `F6` | :586 |
| 11 | One offscreen economy tick (exp, meso, items valued once) | bot | 3 d + QA re-baseline | **y**: changes every abstract number | not checked | :588 |
| 12 | One relationship store with a kind (merge 5 tables) | bot | 5 d | **y**: risky data migration | not checked | :590 |
| 13 | Judge modes as data; `!qa judges` | bot | 2 h | n | not checked | :592 |

Why the three need the owner: **#8** sets a price floor on player-visible sales, a number only the owner can choose;
**#11** would shift all abstract exp, meso and item totals, so every QA baseline moves; **#12** merges five tables, a
migration with no easy way back.

## Table 2: alt items (`/root/work/audit/AUDIT.md`)

| Item | Bot/alt | Effort | Owner decision | Overlap risk | Where |
|---|---|---|---|---|---|
| AL-6: the 200 ms alt tick has no backoff and scans all monsters; add visibility tiering | alt | medium | n | ties to STATUS e03 (owner already said yes); ticket 186 rank 1 (observation granularity) is the same area, not file-compared | AUDIT.md:31, :91 |
| AL-9: alts act with instant timing; catch-up teleport; humanise | alt | medium | **y**: how it should feel | not checked | AUDIT.md:40 |
| AL-10: 727-line second brain with 40+ `isOwnedAlt` branches; give alts the bot brain | alt | large | **y**: architecture | not checked; would touch most alt code | AUDIT.md:41, :82-89 |

## Table 3: the KPQ gap (ticket 163)

| Item | Bot/alt | Effort | Owner decision | Overlap risk | Where |
|---|---|---|---|---|---|
| A player-led Kerning PQ cannot finish: bots never perform the leader's stage actions (`Orchestrator.java:196-198`), so the run burns its 30-minute budget | bot and alt | medium to large | **y** | the party-quest lane (`/root/work/164-opq`) may touch it; not checked (184 entry 4 checked only paths) | 163-bot-audit-1-6-2.md:41-48 |

## Table 4: `STATUS-1.6.4.md` rows

| Row | Item | Bot/alt | Effort | Owner decision | Overlap risk | Where |
|---|---|---|---|---|---|---|
| b257 | FM bot shops sparse; class gear uneven (ticket 155 per-class share not built). "Investigation only, numbers to the owner first" | bot | medium (investigation) | **y**, before any build | feeds on the 183 ledger numbers; 181 `s-bot181-s1` edits shop code (184 entry 3) | STATUS-1.6.4.md:62 |
| s20 | Ticket 155, bot merchant equip audit; related to b257 | bot | medium | **y** | as b257; 181 `s-bot181-progress` edits gear (184 entry 1) | :102 |
| e16 | Bots buy NX more readily when the desk holds a surplus; refill the desk from grind income | bot | medium | **y**, economy | exchange lane (ticket 270) shares no file; behaviour dependency on 181 market (184 entry 3) | :95 |
| e11 | Exchange fair value anchored on merchant prices | bot, economy | medium | **y**, economy | as e16; start after 181 market/s1 merge | :90 |

## Not checked

Effort figures are the internal audit's, not mine. "Not checked" in the overlap column means no file or brief
comparison was made. Nothing was run. The internal file's STATUS line for e11 said `:91`; the row is at `:90`.
Unread by that audit: the middle of `AUDIT.md`, `AUDIT-PLAIN.md`, ticket 163 after line 50.
