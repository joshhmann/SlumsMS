# 183: Cheap bot and alt improvements that need no owner decision

Requested by the owner, 2026-11-03 10:24 UTC (02:24 PT).

Target: 1.6.4. The owner opened 1.6.4 for new capabilities.

## Goal

Improvements, not bug fixes, for the population bots and the owned alts. Every item is cheap, can be proven without
a running economy, and needs no number chosen by the owner. **Economy numbers touched: none.**

Source: `BEHAVIOUR.md` §"Better systems" (items 2, 4, 9), `AUDIT.md` / `AUDIT-PLAIN.md` items AL-1, AL-2, AL-4,
ticket 181 §"Systems worth changing", ticket 163. Nothing here was run; see "What is not proven".

## In scope

**1. One owned-lifetime sweep (about 1.5 days).** `BoundedMaps.prune(map, soft, hard, predicate)` (already used by
`BotEpisodes.java:173` and `BotFameRequest.java:128`) is applied to the static maps that grow without an owner;
`removeBotFromServer` becomes the one documented exit for a bot. Closes `sweep#B2`, `B4`, `B5`, `B6`, `B7`, `R3`, `R4`.
- *Cheapest experiment:* the reflection test below; it lists today's six.
- *Files:* `BotMegaphoneSystem/MegaReactions.java`, `BotFamily.java`, `BotGuildSystem/BotGuilds.java`,
  `BotMessagingSystem/MapChatLog.java`, `Gachapon/BotGacha.java`, `BotChatterSystem/BotChatter.java`,
  `BotMenus.java`, and `removeBotFromServer`.
- *Proof test:* a reflection test "every registry has an exit": each static map in the package is cleared or pruned
  from `removeBotFromServer` or a named sweep. Fails before, once per missing registry.
- *Start condition:* the sweep touches static maps in bot code that ticket 181 brief `s-bot181-sweep` also edits
  (`sweep#B1` BotBlockList concurrency, `R1`, `D1`). This item must start AFTER `s-bot181-sweep` is merged.

**2. Mint/destroy ledger, observation only (about 1.5 days).** `EconomyLedger.minted(...)` on grants with no paired
debit, `destroyed(...)` on destroys and upkeep, printed per source per day. **No behaviour change.** It supplies the
numbers that STATUS rows b257, e16 and e11 need before the owner decides anything (and `market#F-10`, the
10,000,000 mesos per bot per day mint, becomes measurable).
- *Files:* `BotTypes/FMBotWallet.java:27`, merchant bot item grants, `destroyHeldItem` sites, `Economy/`.
- *Proof test:* a unit test that a grant and a destroy each move the ledger by exactly their amount and move no
  wallet. Fails before (no ledger exists).

**3. Alt bundle AL-1 + AL-4 + AL-2 (small).**
- AL-1: after three failed saves a character is locked out of login and `@botme` until restart. Clear the failed
  mark at login and add `!alts clear <id>`. Proof: a store that throws three times, then login works. Files:
  `OwnedAltService.java:361-380`, `:151-153`.
- AL-4: one boot line naming the six alt switches, and state `OWNED_ALTS_ENABLED` in `docs/release/1.6.3.conf`.
  Proof: read the boot log; `t-oa-1` stops skipping. Files: `ServerConfig.java:103`.
- AL-2: say per name why an alt cannot follow, and widen `t-oa-1`. Files: `OwnedAltGate.java:85-110`,
  `OwnedAltService.java:526-533`. Proof: a staged probe per gate reason.

## Overlap with ticket 181

This item (sweep) overlaps 181 S3 rows `sweep#B2`, `B4`-`B7`, `R3`, `R4`; those S3 rows stay deferred in 181, and
fixing them here is recorded here (181's table points at this ticket). It also touches the same static maps as 181
brief `s-bot181-sweep`, hence the start condition above. The ledger (item 2) touches `market#F-10` (observation only).

## Parked to avoid overlap (see ticket 184)

The owner decided to avoid possible overlaps for now. Two items are parked, not dropped:
- **Restock receipt** (`RestockOutcome`, closes `train#F2`) and **wear predicate** (one `WearRules.wearable`, closes
  `prog#P1`, `prog#P6`): ticket 181's briefs `s-bot181-train` (`train#F1`/`F2`) and `s-bot181-progress`
  (`prog#P1`/`P2`/`P3`) are fixing the same code now. Revisit after those branches merge; ticket 184 tracks it.

## Related approved work

STATUS rows e03 (tiered ticking), e06 (small bot wins) and e08 (ramp-shaped boot fill) are already owner-approved and
are separate; nothing here depends on them or changes them. Item 2 only feeds b257, e16 and e11.

## Out of scope

- 181 #8 (conserved sale, price floor), #11 (one offscreen settle), #12 (one relation store), AL-9 (alt timing),
  AL-10 (give alts the bot brain): owner decisions.
- Boss fights and party quests: a separate lane.
- Progression beyond level 120: codex lane, branch `164-gear-progression-codex`.
- Exchange item catalog: ticket 270.

## Order of work

1 (owned-lifetime sweep, only after `s-bot181-sweep` is merged), then 3 (alt bundle, any time), then 2 (ledger). Each
item gets its own helper brief when started; there are no briefs yet.

## What is not proven

Source reading only; nothing was run. Effort figures are the audit's estimates. Line numbers are from the audit pin
and may have moved. The cheapest experiments need a TEST server and have not been done.
