# 178: A sibling-only gear upgrade offer cannot start its handover

State: **new audit finding, pending formal triage**. Coordinator + submitter must assign bug id, severity,
release admission, fix owner and closer before implementation. Suggested impact: S3 companion behavior.
Source inspected at `d4c24d11626912d43bfa4c1a0ee9d33d9195dc3d`; no runtime reproduction claimed.

## Promise

[99](99-companions.md), its implemented-features table, and [162](162-gear-optimizer-full.md) part 3 promise upgrade
sharing with the owner **and sibling alts**. `OwnedAltScenarios.oa-2` describes an offer for each recipient followed
by normal-trade handovers.

## Source-level reproduction

With companion upgrade sharing enabled, summon donor A and recipient B. Give A one tradeable spare that improves
B but does not improve the owner (the owner's worn/carried gear is already better). Keep actors nearby and trades
free. Ask `any upgrades?`, then `trade upgrades`.

- `CompanionItems.upgrades` (line 233) scores both leader and siblings. It can name B and tell the player to say
  `trade upgrades` (`offerLine`, line 259).
- `carryOut` (line 60) routes `TRADE_UPGRADES` through `firstWithSomething` (line 71).
- Its eligibility check `has` (line 96) asks only `GearUpgrades.offers(self, List.of(leader))`.
- Therefore every donor with only sibling improvements is rejected before `tradeUpgrades` (line 312), even though
  that method does score both leader and siblings and can perform the handover.
- The resulting response can be `nothing that'd help you rn`, after a sibling offer just said otherwise.

Expected: an eligible sibling-only offer can proceed through the existing normal-trade mechanism. No direct
inventory transfer, economy adjustment or new gear-scoring implementation is requested.

## Acceptance after triage

1. Fail-before regression exercising the public command dispatch with donor/owner/sibling inventories: only B
   benefits, and the handover is reached for B. Do not test only `tradeUpgrades` below the failing gate.
2. Keep owner-only, mixed-recipient and no-upgrade behavior covered; normal ownership/trade safety still applies.
3. Lane-run targeted scenario and recorded client check: the offered item changes hands once through the normal
   trade path and inventories remain correct after dismiss/relogin.

Duplicate search: the available STATUS-1.6.3 export and tickets 99/162/171/176 contain related features but no
specific report of this entry-gate mismatch. STATUS is stale; the Coordinator must reconcile against the book.
No fix has been attempted. See [audit 177](177-promised-feature-code-audit.md).
