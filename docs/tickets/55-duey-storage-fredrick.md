# 55: Duey, storage and Fredrick fixes

**Area:** Server / Economy
**Kind:** ENABLE (Duey), FIX (Ariant storage NPC, Fredrick guard)
**Effort:** S
**Depends on:** check the cosmic-diff agent's report (`docs/release/cosmic-diff-1.4.1.md` on `cosmic-diff-1.4.1`)
first, so nothing is restored twice
**Status:** Duey done in 1.5.2 (owner 2026-10-25: on, bots never send or receive); storage and Fredrick open

## Today

- **Duey:** live `USE_DUEY: false` (Cosmic default true).
  - `NPCTalkHandler` still opens the Duey window for NPC 9010009, but `DueyHandler` refuses every action. The window
    opens and nothing works.
  - Live has 0 `dueypackages`.
- **Storage:** stock code. MapleRoot changed new storages to 48 slots.
  - Live has 5 storages and 0 stored mesos.
  - `scripts/npc/2110000.js` (Ariant's storage keeper) is missing; it exists in Cosmic.
- **Fredrick:** `FredrickProcessor.fredrickRetrieveItems` lacks Cosmic's `merchant.clearItems()` after
  `deleteFredrickItems`.
  - The NPC script (9030000) refuses while a merchant is open, which covers the normal path. The guard is still
    missing.

## What

- **Duey:** pick one.
  - Enable it (`USE_DUEY: true`) after a dupe test on the test server: send and claim, quick delivery, claiming with
    a full inventory, a relog mid-claim, and a two-client race.
  - Or keep it off and make NPC 9010009 say "Duey is closed" instead of opening a dead window.
- **Ariant:** restore `2110000.js` from Cosmic.
- **Fredrick:** restore the `clearItems()` guard (unless cosmic-diff already did).
- **Bots:** none. Bots do not use Duey or storage. Bot-to-player Duey stays off; trades are the value-safe path.

## Done when

- Duey either works through the dupe checklist, or says it is closed.
- Ariant's storage keeper opens storage.
- Fredrick has the Cosmic guard.

## Risks

- Duey is a classic dupe vector. Enable it only after the race test.
- Duey also moves mesos between accounts, so keep it in the GM audit (ticket 43).
