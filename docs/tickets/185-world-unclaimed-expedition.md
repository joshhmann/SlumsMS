# Ticket 185: `World.addUnclaimed` is a no-op and `removeUnclaimed` puts the same value back, so the unclaimed-reward ledger can never hold anything (F07)

**Status:** open for 1.6.4. Filed 2026-11-02 09:04 UTC (01:04 PST) from the 1.6.4 content hunt (boss/expedition audit
row F07, impact 2, "FIX"). Line numbers as of `release-1.6.4` tip `c0e0a2cd7`.

## The defect

`src/main/java/net/server/world/World.java` keeps `Map<Integer, Map<ExpeditionBossLog.BossLogEntry, Short>> unclaimedRewards`
and exposes four methods over it. Two of them do nothing:

```java
public void addUnclaimed(ExpeditionBossLog.BossLogEntry boss, int cid) {          // :2281
    unclaimedRewards.compute(cid, (k, chrUnclaimed) -> chrUnclaimed);            // never puts anything
}

public void removeUnclaimed(ExpeditionBossLog.BossLogEntry boss, int cid) {      // :2285-2293
    Map<ExpeditionBossLog.BossLogEntry, Short> chrUnclaimed = unclaimedRewards.get(cid);
    if (chrUnclaimed != null) {
        Short unclaimedAmount = chrUnclaimed.get(boss);
        if (unclaimedAmount != null) {
            chrUnclaimed.put(boss, unclaimedAmount);                             // the same value, put back
        }
    }
}
```

`addUnclaimed`'s body is the identity function on the map entry: it never inserts the boss/count, so the per-character
map stays empty. `removeUnclaimed` reads the amount and writes it back unchanged, so even if the map held something the
entry would survive.

The readers are `hasUnclaimedExpedition(int cid)` (`World.java:2272`) and `removeUnclaimedRewards(int cid)`
(`:2277`), and they can only ever see an empty map.

## Who calls it

- `addUnclaimed` has **no caller at all** — `grep -rn addUnclaimed src scripts` returns the definition only. Nothing in
  the fork ever credits an unclaimed reward.
- `removeUnclaimed` has **14** callers, all in the boss exit NPCs: 13 in `scripts/npc/3004006.js` (`:68` ZAKUM, `:82`
  HORNTAIL, `:97` VONLEON, and ten more down the file) and 1 in `scripts/npc/9201134.js:68` (`SCARGA`). Every one of
  them is a no-op today.

## Why it matters

Today nothing breaks, which is why this sat unfixed: the code is a wired gate whose condition can never be true. The
cost is that the fork has no way to hand a player the reward for a clear he missed (the "come back to the NPC later"
flow the exit scripts are written for), and the read side of that flow is dead too. It is also a trap: the next person
who wires `addUnclaimed` into a clear path will believe the NPC side works, and it does not.

## Acceptance

- `addUnclaimed(boss, cid)` inserts the entry (`Short` count of 1, or `merge` + 1 for a repeat) so that
  `hasUnclaimedExpedition(cid)` is true afterwards.
- `removeUnclaimed(boss, cid)` removes the boss entry (and drops the empty per-character map) so that
  `hasUnclaimedExpedition(cid)` is false afterwards, and `removeUnclaimedRewards(cid)` returns what was left.
- A test asserts exactly that: `hasUnclaimedExpedition` flips true after `addUnclaimed` and false after
  `removeUnclaimed`, for a character with no prior state and for one that is called twice.
- Decide whether the NPC scripts' calls stay as they are (they are correct once the two methods work) or the ledger is
  deleted as unused surface. If `addUnclaimed` still has no caller after this fix, say so in the ticket's hand-back
  rather than leaving a working-but-unused map in `World`.

## For the release book

- **Card:** Expeditions | the "unclaimed expedition reward" ledger can never hold a value (`addUnclaimed` is a no-op, `removeUnclaimed` rewrites the same value), so the exit NPCs' 14 calls do nothing | S4 | lane Server | closer: make both methods real and prove it with a `World` test (`hasUnclaimedExpedition` true after `addUnclaimed`, false after `removeUnclaimed`).
- **Patch notes (draft, player words):** "Staff only, no line" — players cannot see this today; the NPCs already behave as if nothing is pending.
- **Confign line:** none.
- **Test-tab row:** none (no player-visible surface yet; the unit test is the proof).
