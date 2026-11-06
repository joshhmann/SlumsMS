# G. Release schedule amendment: 1.6.5 to 1.7.0 (owner-approved)

Date: 2026-11-05. Status: **owner-approved amendment, LOCKED again once recorded** (decision D23 in F).
It changes **when** tickets ship: release scheduling and ticket allocation only.

Unchanged:
- the architecture (A);
- the decisions D1-D22;
- ticket IDs (C);
- test expectations and the gates G0-G5 (D, E);
- the evidence rules.

A ticket moved to a point release keeps its ID, its acceptance criteria and its proof rule.

## Owner's conditions (verbatim, 2026-11-05)

> The architecture remains locked. This is a release scheduling and ticket-allocation change, not a redesign.
> 1. 1.6.5 stays safety-first. Prioritize P0 integrity, persistence, recovery and release tooling. Additional
>    player-facing fixes must be independently verified and must not delay safety-critical work. Review the workload so
>    1.6.5 doesn't become another oversized release.
> 2. Introduce essential content-validation checks earlier. Map, portal, script and quest smoke validation should
>    improve starting with 1.6.5/1.6.6. The full capability scanner and recovery program can remain in 1.6.9.
> 3. 1.7.0 is certification-focused, not an absolute code freeze. No planned new features, but fixes required to pass
>    G0-G5 and GP1-GP12 remain in scope.
> 4. Respect release dependencies. Agents may develop and test independent future work in parallel, but final
>    integration and release qualification must use the actual preceding release baseline. Do not assume an older
>    branch remains compatible.
> 5. Every point release must be independently releasable. It needs its own scope, acceptance tests, regression checks,
>    rehearsal, deployment approval, live verification and rollback plan. Don't defer an essential safety invariant
>    just because the comprehensive 1.7.0 certification comes later.
> 6. 1.6.4 remains the immediate priority. No point-release implementation begins until 1.6.4 is released, verified,
>    and tagged.

## Rules that apply to every point release

- **Baseline (conditions 4 and 6, D20):**
  - release-1.6.N is cut from the tag `v1.6.(N-1)` actually running on live and verified, starting with `v1.6.4`.
  - Parallel work on `16N-*` / `170-*` branches may start once v1.6.4 is tagged. *(Superseded: see Amendments, 6 Nov, item 1.)*
  - It is rebased and re-tested on the real preceding baseline before it merges, never merged on the assumption that
    an older branch still fits.
- **Independently releasable (condition 5).** Each release has:
  - its own book;
  - its own release conf;
  - acceptance tests per ticket (from C) and a regression run (the release auto-QA, plus the change-impact selection
    once P1-Q3 exists);
  - a rehearsal on a fresh live copy with **Result: PASS**;
  - the owner's go per live step;
  - a live verification;
  - a rollback plan (rollback.sh + the client manifest restore).
- **No deferred safety (condition 5).** A safety invariant that a release touches must hold when that release ships.
  It may not wait for 1.7.0's certification.
- **Player fixes ride along only when independently verified (condition 1).** A player fix without its own proof
  slips to the next release rather than holding the release.
- **Feature freeze (plan section 02):** new features go to the 1.8.x backlog. The owner may pull one forward,
  per item.

## Releases, scope and exit criteria

### 1.6.5: Safety first (baseline v1.6.4; amended to v1.6.4.1, see Amendments, 6 Nov, item 2)

Core scope (must ship):
- **P0-1, P0-2:** buyback and merchant/shop atomicity, with the operation journal and boot reconcile (RFC 2.9).
- **P0-3:** `deploy-test` readiness.
- **P0-4:** a failed boot exits non-zero.
- **P0-5:** honest saves.
- **P0-6:** the incubator.
- **P0-7:** a ban revokes site sessions (with Site and Launcher).
- **P0-8:** backups and a restore drill.
- **P1-E2:** the guild expel / party leader handler crashes.
- **P1-R1:** the rehearsal covers WZ and extra steps; the release tooling can **remove** unit flags (b328's lesson);
  the WZ/extra steps are part of the staged release.
- **P1-C1a (new sub-ticket of P1-C1, condition 2):** L0 smoke validation of map, portal and script references.
  Every portal targets an existing map and portal, and every NPC/portal/reactor script a map references exists. It
  runs in the release check and fails on a new fatal reference.

Stretch, only if each is independently verified and nothing above slips:
- **b307** (S2, live: Furious Targa/Scarlion won't die);
- **b274** (Will/Magnus portals);
- **b280** (pet auto-potion).

Workload review: about 11 core tickets plus 3 stretch.
- P0-1/P0-2 (the journal) is the big one and gates the rest of W3. Start it first.
- If it runs long, P0-6 and P1-E2 can ship as soon as they're proven, but the release waits for every P0.
- Nothing else is added without the owner's say.

Exit criteria:
1. **G1 holds:** every P0 is `fixed` with a fault-injection test that failed before the fix, and GP4, GP6 and GP8
   (the golden paths that touch them) are green.
2. **G5 holds:** the rehearsal **Result: PASS** is bound to the staged hashes **including the WZ and extra steps**,
   plus a restore drill receipt from this cycle.
3. P1-C1a runs in the release check with zero new fatal references.
4. The release auto-QA is green on the frozen commit.

Dependencies: v1.6.4 tagged.

### 1.6.6: QA platform, quest smoke, leftovers (baseline v1.6.5)

Scope:
- **P1-Q1** (b286: the runner loses batched events);
- **P1-Q2** (the QA client module from the release client line, with Client);
- **P1-Q3** (impact selection and honest skips);
- **P1-L1** (one client build number in the manifest);
- **P1-E1** (one bad life entry doesn't kill a map load);
- **P1-C1b (new sub-ticket, condition 2):** quest smoke validation (start/end NPCs exist, required items and mobs
  exist, scripts load), in the release check;
- **P2-10 leftovers:** b287 (trader scroll selling), b291 + L04 (event token drops);
- **QA passes:** v01-v05;
- QA tooling bugs: b284 (10-lane false fails), b276 (QA feed), o02 (QA controller), s19 (wider auto-QA);
- small ops: **P2-6, P2-7, P2-8**, and b017 (Defender false positive).

Exit criteria:
1. **G0 holds:** the P1-Q1 regression is green; every result carries run id, commit, environment and capability.
2. Impact selection is used on every merge of the cycle.
3. P1-C1b runs in the release check.
4. v01-v05 each have QA evidence.
5. The 1.6.5 safety invariants still hold (regression).

Dependencies:
- v1.6.5 tagged;
- the QA event schema (D sections 6-7) is agreed and checked in. 1.6.7 depends on it.

### 1.6.7: Bots, first slice (baseline v1.6.6)

Scope:
- **P1-A1** (outcome reasons + honest learning);
- **P1-A2** (the ledger + the HELP_FRIEND vertical slice + the town-EXP root fix, b240);
- **b324 + b327** (the bot DB write path: batched touches, refused writes queued or retried, no caching of a
  refused load); *(Moved to 1.6.5: see Amendments, 6 Nov, item 9.)*
- related small bot bugs: P2-1, P2-2, b303, b317, b318, b319.

The A1 deviation question (the GRIND auto-complete kept as a legacy end, per RFC section 5) goes to the owner before
A1 code. *(Answered: see Amendments, 6 Nov, item 6.)*

Exit criteria:
1. The A1/A2 fail-before tests are green, plus the D2 architecture test.
2. HELP_FRIEND positive and negative scenarios pass on TEST.
3. Bot DB refusals on TEST at full population are back near the 1.6.3 level (the owner sets the bar at kickoff).
   *(Superseded: see Amendments, 6 Nov, item 7.)*
4. Legacy activities show no regression.
5. `-Dslums.action.authority` is ON for TEST and OFF for live, unless the owner says otherwise.

Dependencies: v1.6.6 tagged; the event schema from 1.6.6.

### 1.6.8: Bots, the rest, and alts (baseline v1.6.7)

Scope:
- **P1-A3** (attempt trees, deadlines, handshakes, settling; uses the 1.6.5 journal);
- **P1-A4** (owned alts on the ledger);
- **P1-A5** (town stay ceiling, party GRIND admission);
- **P1-A6** (world knowledge + reassessment);
- **P2-9** (FEELING grudges);
- alt/social bugs: b285, b305, b326.

Exit criteria:
1. **G2 holds on TEST:** a 24 h soak at full population; started-root terminal-within-deadline at least 99.9%; zero
   illegal transitions; the G2 coverage report (D21).
2. The LifeMemory 1.6.8 -> 1.6.7 -> 1.6.8 round trip (D10) passes.
3. `-Dslums.action.authority` is decided by the owner for live.

Dependencies: v1.6.7 tagged; the P0-1/P0-2 journal from 1.6.5.

### 1.6.9: Content repair and content trust (baseline v1.6.8)

Scope:
- **P1-C1** (the full capability registry + scanners);
- **P2-W6** (fanout-ranked content repairs; each names what it unblocks);
- **P2-3, P2-4**;
- **P2-5** (cash-shop gifts, after the owner's n16 decision);
- **P1-L2** (game-content manifest authenticity + rollback protection; stays open until verified);
- content bugs: b257, b306, b308, b309, b310, b312, b313, b314, b315, b316, s20 (merchant equip audit), b256
  (alt extras frames).

Exit criteria:
1. The scanner report shows zero fatal references on golden-path consumers.
2. Every P2-W6 repair lists what it unblocked.
3. P1-L2 is verified: unsigned and old manifests are refused on the RC channel.

Dependencies: v1.6.8 tagged; P1-C1a/b from 1.6.5/1.6.6.

### 1.7.0: Certification (baseline v1.6.9)

Scope:
- GP1-GP12;
- the gates G0-G5 (E) on the RC commit;
- the 24 h soak;
- one full cross-system certification: client, launcher, site, DB migration and restore, signed manifests;
- s12 (the manual passes nobody has done).

**Not an absolute code freeze (condition 3).** No planned new features, but fixes needed to pass G0-G5 or GP1-GP12
are in scope.

Exit criteria: E's gates, all with named evidence files; no false greens (FLAKY = not passed until two passes in a
row; a skipped golden path = FAIL).

## Card allocation (release books)

Cards keep their IDs. A card's `target` says its release.

Already shipped in 1.6.4 and waiting on 1.6.4 close-out, not re-allocated: every 1.6.4 card at `ontest` or `check`.
They close on their own proof in the 1.6.4 book.

| Release | Bug cards | Items |
|---|---|---|
| 1.6.5 | b307*, b274*, b280* (* stretch) | P0-1..8, P1-E2, P1-R1, P1-C1a (new cards in the 1.6.5 book) |
| 1.6.6 | b286, b287, b291, b284, b276, b017 | L04, o02, s19, s32, v01-v05; P1-Q1..Q3, P1-L1, P1-E1, P1-C1b, P2-6/7/8 |
| 1.6.7 | b324, b327, b303, b317, b318, b319, b240 (if still open after 1.6.4) | P1-A1, P1-A2, P2-1, P2-2 |
| 1.6.8 | b285, b305, b326 | P1-A3..A6, P2-9 |
| 1.6.9 | b257, b306, b308, b309, b310, b312, b313, b314, b315, b316, b256 | P1-C1, P2-W6, P2-3, P2-4, P2-5, P1-L2, s20 |
| 1.7.0 | (fixes found by certification) | GP1-GP12, G0-G5, s12 |

Not in the table above, allocated when the 1.6.5 book opened (Nov 6): b275 → 1.6.6, then moved by the owner to 1.6.5 stretch (Nov 6); w06 → 1.8.x backlog. *(Superseded: see Amendments, 6 Nov, item 4.)*

**1.8.x backlog (feature freeze; the owner may pull any one forward):**
- e01 (economy rework), e03, e05, e06, e07, e08, e09 (NX desk auto top-up), e10, e11, e12 (PELIXIR in game), e13
  (housing), e14 (in-game exchange window), e16, e18 (bot decisions dashboard), e19;
- n01 (icon dock), n03 (quest coverage, ticket 283), n10 (Holiday PQ), n11 (Elnath PQ), n12 (Von Leon/Cygnus/Hard
  Lotus/Balrog Easy), n13 (Chaos Horntail), n15 (Chaos Zakum quota polish);
- L15 (client fixes + bonds window: split any bug fixes out into the release that fits), L17 (held items, the owner
  picks);
- o04 (builds to tarkov2), s17 (Most hunted split).

Owner-decision cards stay open for the owner: n14 (open boss/PQ decisions), n16 (architecture review leads, inputs to
P1-C1).

## Amendments, 6 Nov 2026 (owner-approved 6 Nov)

The text above stays as written; each line these items change carries a pointer here.

1. **Authorization.** "Parallel work on `16N-*` / `170-*` branches may start once v1.6.4 is tagged" is superseded.
   Work on any release, including future branches, starts only when the owner activates that release and names the
   lanes and work allowed (full development halt, owner 07:28 UTC Nov 6).
2. **Baseline.** 1.6.5's baseline is **v1.6.4.1**. It is merged into release-1.6.5 before any 1.6.5 work (step 0).
3. **Hotfix 1.6.4.1** (owner, 06:49 UTC Nov 6). Scope: the b331 reactor last-state cap and the shared bot-client guard,
   from v1.6.4. Its record is the 1.6.4 release book; only evidence from that exact candidate counts.
4. **Card moves** (owner, 02:07 UTC Nov 6). b275, w06, b329 and b330 are 1.6.5 extras. The note above that puts w06 in
   the 1.8.x backlog is superseded.
5. **Lanes** (Road to 1.7.0, approved 6 Nov). QA takes P1-C1a, P1-C1b, s32 and b284; Server reviews the smoke checks.
   Bots takes b303, b317, b318, b319, b324 and b285. Server keeps the database side of b324.
6. **1.6.7, the A1 deviation question:** answered by A section 5, phase 1 (the GRIND auto-complete is deleted; other
   activities run through the legacy adapter). It is not an owner question.
7. **1.6.7 exit criterion 3** is replaced by: "Bot DB refusals on TEST at full population are no worse than a baseline
   measured under the same conditions at kickoff." Protocol (owner-approved 6 Nov; run only once 1.6.7 is
   activated):
   - baseline: the live predecessor (v1.6.6) on TEST, full roster with the count recorded, the same host, a 60-minute
     window starting 10 minutes after the boot fill;
   - source: the BotDbGate "refused so far" minute lines in the dated server logs, split by operation, with peak permits
     in use and pool waiting;
   - the candidate passes if it is no worse than the baseline and no refused write is lost;
   - evidence: both extracts, with population, commit and jar hash.
   The conf's "stay at 0" bar is a separate goal, not this exit criterion.
8. **Cards filed 6 Nov** (b331-b344) are placed by severity in the 1.6.5 book.
9. **b324 and b327** (owner, 6 Nov): moved from 1.6.7 to 1.6.5 as must-ship bot-memory reliability fixes, Bots lane;
   acceptance on the b324 card.
