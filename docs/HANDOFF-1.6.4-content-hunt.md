# Handoff: the 1.6.4 content and mechanics hunt

**Purpose.** The work itself is in [ticket 176](tickets/176-1.6.4-content-gaps.md); this file is the state of
the hunt and the traps you will hit. It covers only the 1.6.3/1.6.4 content and mechanics pass — the boss and
expedition audit, the config-template gap, and the eleven lane merges that landed on `163-integration`. It does
not summarise the project. Read this first, then the ticket, then whichever lane report a task names. Every
fact below is checkable from the merge record
(`/root/work/agent-runs/server/merge-164-c/REPORT.md`), the follow-up list
(`/root/work/agent-runs/FOLLOWUPS-164.md`), or the tree itself; anything I could not confirm is marked
`[UNVERIFIED]`.

## Where the work sits

| | |
|---|---|
| branch | `163-integration` |
| worktree | `/root/work/SlumsMS-163-integration` |
| tip | `6cea00b4e95fe0d537a87a3b615019b15aa8fe1f` |
| working tree | clean (`git status --porcelain` empty) at hand-off |
| pushed | nothing; `remote.origin.pushurl` is `DISABLED` (worktree config) |
| stash | none used for this work |
| full suite | `[WARNING] Tests run: 10479, Failures: 0, Errors: 0, Skipped: 146` — `BUILD SUCCESS` |

The stash point matters: the shared repo has **one** stash entry, `stash@{0}: WIP on rc6-saydo-social`
(2026-10-23). It is not ours and must not be touched. Its existence is the reason the rule below is absolute.

The eleven merges in the merge record were made with `--no-ff` and no hand conflict resolution. Each carries:

```
Co-Authored-By: DeepSeek <noreply@deepseek.com>
Coordinated-By: Claude Opus 5.5 <noreply@anthropic.com>
```

## What was fixed, and the defect behind each fix

All shas and lane names are from the merge record. The queue's pre-merge tip was
`f5d20680376d5509f3cc4eb2b5e7bd855ae6bf92` (itself `merge 164-jobmaster`). The baseline full suite at that tip
was `Tests run: 8322, Failures: 0, Errors: 0, Skipped: 133`.

| merge commit | lane | the defect it fixed |
|---|---|---|
| `48c0a40b7` | `164-temple3521` | Quest 3521 "Force Field" ran its item turn-in through player/client-based quest actions, so a headless character (a bot) NPE'd running the quest; the item actions were given `Character`-based paths in `scripting/AbstractPlayerInteraction.java`, `server/quest/actions/ItemAction.java` and the inventory manipulators, with a WZ-backed test for the turn-in. |
| `cf7978577` | `164-apigap2` | 14 live script call sites invoked methods the scripting classes do not declare (e.g. `cm.getChannelServer()` at `scripts/npc/9000011.js:52`, `qm.showItemGain(...)` at `scripts/quest/23011.js:23`), so those branches died at runtime; six inert cues were implemented as log-and-return, the script-side calls were corrected or made to fail safely, one was parked for missing WZ evidence, and `scripting.ScriptApiCoverageTest` now fails the build on a new dangling call. |
| `5a061e26c` | `164-makergate` | Moren's Maker chain 6029-6036 was unhidden while `Skill.wz` has no Maker node on an unpatched install: a player spends 30,000 mesos across 6030/6031/6032, learns nothing, then cannot finish 6033 because 4260003 is a Maker craft and `MakerService.getMakerSkillLevel` returns 0. Re-hidden in `server/quest/HiddenQuests.java`, so the existing refusal explains it. |
| `879dbc7d2` | `164-jobmaster` (correction) | Job Master NPC 9010003 offered job ids no `Job.java` entry backs (the test output names `job 910 at level 70 was offered 911`); the recorded level-1 offer is kept and the offered list is filtered by `Job.getById(id) !== null`. |
| `070ca0c4c` | `164-reactor` | `server/maps/ReactorFactory.java`'s map-load path lacked the inert-state fallback the spawn path has, so a map-loaded reactor whose state 0 has no event was dead forever; CWKPQ's decoy sigil tiles were also left active each run. |
| `a7a44c6d0` | `164-testassume` | `scripting/TempleForceFieldQuestTest` decided `Quest.wz` availability in `@BeforeAll`, so an absent WZ aborted the container and surefire printed `Tests run: 0` — the class silently left the suite. Moved to `@BeforeEach`: the same five tests now report as 5 skipped. |
| `d300bfafe` | `164-buffclean` | Seven `BuffStat` debuff constants nothing writes or reads (`CONFUSE`, `BERSERK_FURY`, `STUN`, `POISON` and three more) were deleted; debuffs are documented as living in `client.Disease`. |
| `2614ad58b` | `164-monsterbook` | Tier rows were inserted rather than upserted, so Monster Book progress was lost on relogin; the link medal went to the wrong character; `ItemInformationProvider.getCardMobId` NPE'd on a chosen cover during login; the cover handler did not check ownership. |
| `c589cf49d` | `164-eventfix` | `scripts/event/BalrogBattle.js` and `BalrogBattle_Easy.js` read a `const` local of one function from inside another (GraalJS cross-function scoping), so the sealed Balrog spawn saw `undefined`; it is now spawned through the instance manager. |
| `987b44c1f` | `164-minigame` | A closed Omok/match-card room never left the map; `piece[250]` was not cleared between games; tie refusals aliased the in-progress field; nine `PlayerInteractionHandler` branches dereferenced `chr.getMiniGame()` unguarded; wire input into `setPiece` was unbounded. |
| `6cea00b4e` | `164-familycfg` | Four `FAMILY_*` fields (`FAMILY_REP_PER_KILL`, `FAMILY_REP_PER_BOSS_KILL`, `FAMILY_REP_PER_LEVELUP`, `FAMILY_MAX_GENERATIONS`) had neither an initialiser nor a live key, so they read 0: every family entitlement was unaffordable and a 0 generation cap was a join wall. |

For completeness, these `164-*` merges were already on `163-integration` before this queue and are **not** part
of the 10479 delta; they are listed only so their subjects are not mistaken for new work (all verifiable with
`git log --oneline --merges`): `164-botgames` (`6ecc14829`), `164-y07` (`994c59d66`), `164-notefix`
(`05a831166`), `164-deadhandler` (`c979a9a35`), `164-casino` (`63c16b501`), `164-familyfix` (`22a670a51`),
`164-questhidden` (`3d5cf4956`), `164-psearch` (`2f2fa3cf6`), `164-apigap` (`9e2a09058`), `164-trock`
(`b9c4bb10c`), `164-rewardfix` (`281f590ee`), `164-cpqtimeout` (`ba5d6cba7`).

## Suite progression

From the merge record's full-suite table, in order:

| after | commit | aggregate | build |
|---|---|---|---|
| baseline (pre-queue) | `f5d206803` | `8322 / 0 / 0 / 133` | BUILD SUCCESS |
| merge 4 | `879dbc7d2` | `10426 / 0 / 0 / 133` | BUILD SUCCESS |
| merge 5 | `070ca0c4c` | `10431 / 0 / 0 / 135` | BUILD SUCCESS |
| merge 6 | `a7a44c6d0` | `10436 / 0 / 0 / 140` | BUILD SUCCESS |
| merge 7 | `d300bfafe` | `10438 / 0 / 0 / 140` | BUILD SUCCESS |
| merge 8 | `2614ad58b` | `10454 / 0 / 0 / 146` | BUILD SUCCESS |
| merge 9 | `c589cf49d` | `9775 / 8 / 207 / 145` | **not a signal — contaminated** |
| merges 9-11 (final) | `6cea00b4e` | `10479 / 0 / 0 / 146` | BUILD SUCCESS |

Final delta: **+2157 tests**, failures `0 -> 0`, errors `0 -> 0`, skipped `133 -> 146`. The skip growth is all
accounted for: two WZ reactor cases, the five temple tests now counted instead of vanishing, and the six
DB-backed monsterbook tests (see the traps below). Every added test class is named in the record's class-by-class
reconciliation, and the per-class sum is 10479 over 1115 classes — no residue.

**The `c589cf49d` run is contaminated and must not be quoted as a result.** Another process rebuilt into
`/root/work/SlumsMS-163-integration/target/` while surefire was running. The log ended with 295
`NoClassDefFoundError`s — `soloMapling/ArtificialPlayer/BotQuestSystem/TempleLanes` x38,
`.../BotConvo/DecideShadow` x23, `soloMapling/qa/InitiativeScenarios` x15, `server/BotTickService` x8,
`net/packet/InPacket` among them — plus `org.mockito.exceptions.base.MockitoException: Mockito cannot mock this
class: class client.Character.` with `Caused by: java.lang.InternalError: class redefinition failed: invalid
class`. Every one of the 3984 `target/classes` `.class` files carries an mtime inside that window. It was
disproved, not argued away: every class named in a `NoClassDefFoundError` exists on disk and loads under
`javap -cp target/classes`, and the re-run on a quiet tree (`find target -name '*.class' -newermt "<suite start>"`
empty) shows zero `NoClassDefFoundError` and the 10479 line.

## Audits already done — do not redo

- **Mob loot and meso — premise refuted.** `drop_data_global` never held meso and is the wrong table; meso comes
  from `drop_data` rows with `itemid = 0` via `MapleMap.java:674-687`, byte-identical to stock. 768 of 1004
  droppers have a meso row in the reference dataset. Report `/root/work/agent-runs/server/s-loot-audit/REPORT.md`.
- **Dead packet handlers — clean apart from the stock collision.** 176 registrations mapped, 186
  `RecvOpcode` constants diffed, three empty `handlePacket` bodies total. The one functional defect is the `0x8B`
  collision, which is inherited from stock. Report `.../s-deadpacket-audit/REPORT.md`.
- **Family system — complete.** Fully wired: 18 config read sites, 9 opcodes all registered, 11 packet emitters
  all with live callers, real buff formulas applied at `Monster.java:850`, `:867` and `MapleMap.java:869`.
  Report `.../s-family-audit/REPORT.md`. The only real divergence was the four uninitialised tuning fields,
  merged as `6cea00b4e`.
- **Buffs — no player-visible loss.** 45 of 87 `BuffStat` constants reach a formula; combat is
  client-authoritative on both ends, so a decorative server-side stat is not a bug. Report
  `.../s-buff-audit/REPORT.md`.
- **Monster book collection — provably finished.** Opcodes, pickup hook, persistence, and 254 real card drops in
  `database/migrations/045-newer-area-cards.sql`. Report `.../s-monsterbook-audit/REPORT.md`. The payout half was
  the only open part and landed as `2614ad58b`.
- **Minigames — finished, and two candidate findings are false positives.** 20 new tests merged. The
  `writeShort` vs `writeByte` "desync" is not a desync (`ByteBufOutPacket.writeShort` is little-endian, `3F 00`
  vs `3F 01`), and the crossed tie bits are correct by design. Report
  `/root/work/agent-runs/server/164-minigame/REPORT.md`.
- **Quest rewards — 312 sites classified, 198 INVENTED.** The important non-defect: `QuestScriptManager` always
  loads `scripts/quest/<id>.js` and `Quest.forceComplete` never runs the WZ acts, so a script's `gainExp` **is**
  the only payment. An "invented reward" is therefore not automatically a double pay. Report
  `.../s-rew-audit/REPORT.md`.

## Parked, with the reason

- **Pyramid PQ** — owner-gated by divergence-ledger MP-006. The owner said "leave it off for now" (2026-10-25).
  Four broken links in series; the map loads but no entry. Cosmic PR #221 is the source if it is ever revived.
  **Do not start it.**
- **`0x8B` wedding opcode collision** — `WEDDING_TALK` and `WEDDING_TALK_MORE` share `0x8B`
  (`net/opcodes/RecvOpcode.java:151-152`), so `PacketProcessor.java:287` shadows `:286`. Identical to stock
  Cosmic, no correct value is derivable from this repo, and `scripts/event/WeddingCathedral.js:42` records that
  the scripts broadcast `OnWeddingProgress` themselves, so nothing player-visible is lost. Do not guess `0x8C`.
- **`RedisJobQueue`** — dead: no construction site, no config key, no other jedis user. Cleanup only (see
  ticket 176 Part C).
- **`AdminLogHandler`** — a registered no-op (`//harhar`), GM-only audit trail, no player harm.
- **Empty-chat `charAt(0)`** — reachable only from an edited client.
- **Mob skills 115/134/135/136/142** — unreachable.
- **F04 Balrog `Balrogundefined`**, **F05 Plunk/Tana data**, **F06 boss-log table shape**, **F09 missing boss
  data** — all parked with reasons in `/root/work/agent-runs/FOLLOWUPS-164.md` section B.

## Environment traps that will cost you an hour

- **Copy `/root/work/SlumsMS/config.yaml` into any new worktree.** It is gitignored. Without it the first run
  throws roughly 52 `ExceptionInInitializerError`s from `config.YamlConfig.loadConfig(YamlConfig.java:22)`.
- **Per worktree, set the git config before you start:** `git config extensions.worktreeConfig true`, then
  `git config --worktree remote.origin.pushurl DISABLED`. Several hundred worktrees share one `.git`, and a
  push is irreversible.
- **NEVER `git stash`.** Every worktree shares one stash list; a stash from one lane is visible and pop-able in
  another. Prove a regression by editing the implementation out instead (see the method rules).
- **WZ-backed tests need `-Dwz-path=/root/slums-nav-v70/wz`.** The integration worktree has no `wz/` directory,
  so those tests skip without it. They are still counted as skipped, not hidden — that is what `a7a44c6d0` fixed.
- **The DB-backed monsterbook tests `assumeTrue` on `-Dslums.monsterbook.it.url`**
  (`MonsterBookTierRowTest.java:45`, `MonsterBookTierPersistenceTest.java:98,111`), so in a plain run they skip
  (2 + 4) instead of erroring. Six of the 146 skips are these.
- **Known flake:**
  `soloMapling.Environment.PlatformGroundTest.aBandAcrossTwoLevelsPutsEachSpawnOnItsOwnLevel`
  (`PlatformGroundTest.java:48`). `src/main/java/soloMapling/Environment/PlatformSpawner.java:24` uses an
  unseeded `private static final Random random = new Random()`, and `:101` can land `x = 0`, which the test
  rejects with `java.awt.Point[x=0,y=300] is off its level`. It did not fire in any suite of this queue. Re-run
  before investigating.
- **`monsterbook_stats` has no DDL anywhere in this repo** (there is no `src/main/resources/db` directory at
  all). Its deployed shape was checked against the live schema: `PRIMARY KEY (id)` **plus** `UNIQUE KEY
  accountid`, InnoDB, `utf8mb4`. That uniqueness is what makes the tier upsert correct — it is a checked fact,
  not an assumption. Do not quote `slums164_monsterbook` as schema evidence; that is the lane's own test
  fixture (`MonsterBookTierPersistenceTest.java:40-44`) and it has a different key.

## Method rules learned the hard way

- **Verify a lane's premise before spawning it.** This killed two lanes outright.
- **Prove fail-before by editing the implementation out and running the test.** Never `git stash`, never
  `git checkout --`. Paste both runs verbatim into the report.
- **When an audit claims a "double pay" or a "duplicate", read the actual callee body before acting.**
  `s-rew-audit` called quest 2034 a double-pay; the five-file call chain showed it was a faithful copy of WZ act
  block 1, and the finding was downgraded to drift risk.
- **Check the WZ key name before calling something a mismatch.** `Mob.wz` uses a singular `revive` child, so
  `grep name="revives"` returns a false negative — that turned a correct revive chain into a phantom defect.
- **Anything with no reachable player impact is parked, not fixed.** Combat is client-authoritative on both ends
  (`net/server/channel/handlers/TakeDamageHandler.java:70`, `AbstractDealDamageHandler.clientDamage:884-893`), so
  a decorative server-side stat is not a player-visible bug.
- **A branch that is "green on its lane" is not merged.** `164-configtemplate` is finished and proven both ways
  but is still only on `164-configtemplate` (ticket 176 Part A). Do not assume it is in the tip.

## Open questions for the owner

- **Pyramid PQ** — MP-006 stays `undecided`. The owner said leave it off; only the owner can reopen it.
- **`050-bb-drop-tables.sql` meso data** — owner-gated draft, 8011 rows across 350 mobs. Not applied.
- **Amoria marriage quests 8862-8871** — gated by marriage with `lvmin 10020` on 8862 (ledger QU-010).
- **The four `164-configtemplate` recommendations** — inline initialisers for `ITEM_EXPIRE_CHECK`,
  `MAP_VISITED_SIZE` and `MOB_STATUS_AGGRO_INTERVAL`; a floor on the `REBIRTH_*_RATE` assignment; a jumpquest
  reward cap; and dropping the four declared-but-never-read keys. Each needs an owner yes/no.
- **Stale divergence-ledger rows that need rewriting**, none of which is a code change: NP-007 (family system)
  should become `keep / on` — the subsystem is complete and on, and the four tuning defaults that had muted it
  landed as `6cea00b4e`; SK-033 (19 mastery books for removed skills) is already handled by
  `database/migrations/025-remove-dead-skill-books.sql:1-13`;
  MP-003 (`inExpedition`) no longer exists anywhere in `src/main/java` — `UseItemHandler.java:99-106` gates the
  map-wide apply on `HAPPY_BIRTHDAY` only.
