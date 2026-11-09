# 285 - Aran finishing kit (card c-a): combo critical formulas on the stock v83 client

Recorded 2026-11-09. **Status: brief ready, deferred to post-1.7.0 (owner, 2026-11-09: jobs wait until
1.7.0 ships; 1.6.6 -> 1.7.0 is full).** Route A (owner approved, ticket 284, §Owner decisions). Read-only analysis so far; this
briefs the implementation, no code has changed.

## Why

Ticket 32 records Aran as incomplete on our side: "only Final Blow at 4th job. The combo-gated finishers
(Smash, Fenrir, Tempest) are left out, and there is no Aran combo counter", plus unexplained pauses. Kuro ships
Aran on the same v83 base and patches exactly these formulas client-side, so the sites below are the map for
closing that gap.

## Sites (verified against OUR pinned stock exe)

Our stock reference is `stock-v83-MapleStory.exe`, sha256
`86e4e6be050caeda4a93443237256a84376ea74781fcba558d2418f0568aac90`, the same binary the project patches
(AGENTS.md, client-1.5.0-fixes2\exe-copy). File offset = VA - 0x400000.

| # | VA | Size | Original bytes (stock) | Disassembly at the site |
|---|---|---|---|---|
| 1 | `0x0078E31A` | 6 | `0f af c7 83 c0 0a` | `imul edi,eax ; add eax,0xA` - combo-critical formula term |
| 2 | `0x0078E34D` | 5 | `0f af c7 59 59` | `imul edi,eax ; pop ecx ; pop ecx` - second formula term |

Kuro's installer for these is `FUN_10026290` in `Kuro-dinput8.dll`: it `VirtualProtect`s both sites, writes the
replacement bytes, `FlushInstructionCache`s, then re-runs its verifier; on failure it restores the saved
originals byte-by-byte. Its log lines (which name the feature) are:
`[CygnusSkills] Aran Combo Critical native formula patch skipped`, `... verification failed`,
`... rollback verification failed`, `... direct WZ formula patched`. Two related helpers are
`FUN_10026240` (verify) and `FUN_100261f0`, and `FUN_101361c0(6)`/`FUN_101361c0(5)` (the byte writers).

The "Aran renumber" mechanism is separate: `FUN_10025ef0` logs `Aran renumber site mismatch at 0x...` and
`Aran renumber recognition:`, and computes its site at run time (no literal exe VA in the function). Not pinned
yet; out of scope for this card until the renumber need is understood.

## What the patch does

Both sites multiply a combo-critical term by `edi` and scale it (the first adds 0xA, i.e. the +10 in the
formula); Kuro replaces those terms so the client's own Aran combo-critical calculation matches what their
server computes. We need the exact replacement bytes; the installer branch that computes them is in
`FUN_10026290` (already decompiled at
`/root/work/rea-kuro/ghidra-out/aran-sites.txt`). Next RE step: read that branch to recover the two
replacement sequences, then re-derive the intended formula (do not copy their bytes; implement from the
formula and prove it against our server's damage).

## Plan (implementation, after this brief)

1. Recover the intended formula from Kuro's installer branch + our server's Aran damage path; write it down.
2. In `slums.dll`: two guarded byte patches at the sites above, each with a runtime stock-byte check (the
   AGENTS.md rule), a switch in `config.ini`, and a native test that fails without the patch.
3. Server: confirm the combo-critical values our `BotCombatFormula`/player damage path uses for Aran; align
   both sides. Fail-before evidence: the 4th-job Aran combo critical and the finishers behave as ticket 32
   recorded.
4. Real-client proof on TEST: combo counter appears and finishers (Smash/Fenrir/Tempest) draw and damage
   correctly; no pause regression.
5. Client data: the combo-gated finishers may also need skill rows/WZ checks; compare our `Skill.wz/2100-2113`
   against the recorded Aran skill list before writing code.

## Acceptance

- Two guarded patches at `0x0078E31A` (6 B) and `0x0078E34D` (5 B), switchable, stock-byte verified.
- Fail-before test: Aran 4th job shows no combo counter / no finishers before the change, both after.
- Real-client proof recorded in the REPORT; no other class regressed.
- Formula documented in the ticket before code lands.

## Constraints

- Behaviour only. Nothing from Kuro's DLL or data is copied into the repo; their byte guards, installer and
  strings are leads, not source. Our own stock exe and WZ stay the truth.
- Their client changes weekly; re-verify the stock bytes above before writing the patch (sha256 of the exe is
  pinned in section "Sites").
- No live/TEST changes without the release lane; client work in `slums.dll` per the client AGENTS.md.

## Open questions

1. Do we port the combo counter at all, or only the critical formula? (Ticket 32 implies counter.)
2. Which finishers are in v83-era Aran data that we can support (Smash/Fenrir/Tempest) - check `Skill.wz`.
3. Is the +A term the level-scaled part or the mastery part? Resolve from the installer branch before coding.