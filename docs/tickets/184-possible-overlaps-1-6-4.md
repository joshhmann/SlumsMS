# 184: Possible overlaps to revisit (1.6.4 parking list)

Requested by the owner, 2026-11-03 10:33 UTC (02:33 PT). Target: 1.6.4. Docs only.

## Purpose

A parking list of places where in-flight 1.6.4 work could collide. **Check the matching entry before starting or
merging either side.** Checks (10:3x UTC) were read-only: `git diff --name-only release-1.6.4...HEAD` per worktree,
`git status` for uncommitted work, and the briefs under `/root/work/agent-runs/server/`. No merge was attempted. Branch
tips move; re-run before relying on an entry.

## Summary
| # | Overlap | Verdict |
|---|---|---|
| 1 | 183 parked items vs 181 train / progress briefs | verified overlap (restock receipt already built on the 181 train branch) |
| 2 | 181 `prog#P3` vs the codex progression lane | no overlap found (no shared file) |
| 3 | 181 `market#F-03` / `F-07` vs the exchange catalog (270) | no shared file, re-checked 10:40 UTC; two 270 decisions DECIDED 2026-11-03; e11/e16 not checked |
| 4 | 181 / 182 paths vs boss-fight and party-quest lane | no overlap on the 181 and 182 paths, re-checked 10:40 UTC; re-run after the chaos commits land |
| 5 | `docs/release/1.6.4.conf` created twice | verified overlap (two planned creators) |
| 6 | `docs/tickets/README.md` rows from many branches | verified overlap (same insertion point) |
| 7 | 186 (external-repo adoption) vs boss/PQ lane, 181, 183 | verified overlap on `BotCombatFormula`; others not checked |
| 8 | Found while checking: overlaps inside 181 itself | verified overlap |
| 9 | Boss/PQ lane has nothing playable | blocked on the owner: a TEST deploy of one branch, plus 7 + 6 decisions |
| 10 | Social lane `164-social-int` (35 commits, 153 files) | two numbering clashes fixed on `164-social-int-renum`; code overlaps listed, not checked |

## 1. 183 parked items vs 181 train and progress (verified overlap)

- **What:** ticket 183 parked the *restock receipt* (`RestockOutcome`, closes `train#F2`) and the *wear predicate*
  (closes `prog#P1`, `prog#P6`) because 181 is fixing the same code.
- **Sides:**
  - Restock receipt: brief `/root/work/agent-runs/server/s-bot181-train`, worktree `/root/work/SlumsMS-164-bot181-train`,
    branch `164-bot181-train`, tip `4e13ba094`. It already contains `enum RestockOutcome { BOUGHT, PARTIAL, NOTHING }`
    and changes `BotShopping`, `BotSupplies`, `BotResupply`, `TrainingBot`: **already built there.**
  - Wear predicate: brief `s-bot181-progress` (`prog#P1`: `BotEquipChecker.java:101-104`, `BotDecorateEquips.java:47-53`).
    No worktree or branch exists for it yet (only `-life`, `-market`, `-s1`, `-social`, `-train`).
- **How to check:** diff the train branch (and the progress branch once it exists) by name; grep `WearRules`,
  `BotEquipChecker`, `BotDecorateEquips`.
- **Action:** revisit after the 181 train and progress branches merge. Then re-read what is left of 183's restock and
  wear items; drop whatever 181 already closed.

## 2. 181 `prog#P3` vs the codex progression lane (no overlap found)

- **What:** `prog#P3` (skill points unusable at level 120): `BotProgression.java:283`, `:377`, `Character.java:7691`.
  The codex lane (normal 120-149 gear bridge, forge, ticket doc `normal-200-to-forged-rebirth-progression.md`) is the
  other progression work.
- **Sides:** brief `s-bot181-progress` (no branch yet); codex worktree `/root/work/SlumsMS-164-gear-progression-codex`,
  branch `164-gear-progression-codex`, tip `0444737f2`.
- **Fact (key check):** the codex branch changes 87 files, 11 under `src/main`: `ItemConstants`,
  `NPCConversationManager`, `NormalGearBridge`, `WeaponCoreExchange`, `AutoQaCatalog`, `GameAutoQaWorld`, `ProbesGear`,
  `QaMenuCatalog`, `GearProgressionScenarios`, `QaScenarios`, `autoqa-coverage.tsv`. **Neither `BotProgression.java` nor `Character.java` is among them.**
  (A plain two-dot diff also shows `helper-checkout` deleted: the branch is older than 1.6.4's tip.)
- **Not checked:** whether the 120-149 gear bridge changes what a level-120 bot spends skill points on (behaviour).
- **Action:** none for files; tell the codex owner about P3.

## 3. 181 `market#F-03` / `F-07` vs the exchange catalog, ticket 270 (no shared file; both 270 decisions decided)

- **What:** price floor (`F-03`) and merchant-bot mint (`F-07`) versus the exchange catalog and STATUS e11 / e16.
- **270 facts**, reported by the lane session, 2026-11-03 10:40 UTC (02:40 PT), not verified by me except tip and file
  list: `164-excatalog`, `/root/work/SlumsMS-164-excatalog`, commit `17c840b06`, CHECKOUT PASS. 709 rows in
  `src/main/java/soloMapling/itemPool/itemConfig/exchangeTickers.yaml`; three fold into existing 1.6.3 books, so 706
  new tickers, no migration. Maker funded with real items via `!exchange fund`, nothing minted. Economy tests 506 run,
  0 failures. Cutover flags: ticket 270 section 6 step 9; none on before a TEST run.
- **Fact (re-checked 10:40 UTC):** `git diff --name-only release-1.6.4...HEAD` on `164-excatalog` (52 files, 26 under
  `src/main`, mostly `Economy/exchange/**`) against `git status --short` / `git diff --name-only` in
  `SlumsMS-164-bot181-s1` and `-market` (uncommitted): **no shared file**. s1's new untracked `Economy/EconomyLedger.java`
  is not the exchange lane's `Economy/exchange/Ledger.java`. e11 / e16 have no code yet; they will read merchant prices
  that `F-03`/`F-07` change (behaviour, not checked).
- **DECIDED, owner (270), 2026-11-03 (PT same day):** (1) scroll `maxPrice` = **2x the anchor** (replaces flat `1e9`).
  (2) The 109 anchor-0 ETC rows stay **player-priced only**, no derived prices; revisit when ticket 183's ledger can
  supply numbers.
- **Action:** start e11 only after 181 market/s1 merge.

## 4. 181 and 182 paths vs the boss-fight and party-quest lane (no overlap found, re-run needed)

- **Earlier "no overlap found"** (all seven branches): checked at 10:3x UTC; **re-run after the chaos commits land**.
- **Lane facts**, reported by the lane session, 2026-11-03 10:40 UTC (02:40 PT), not verified by me except the two
  tips below: finished documents `PQ-BOSS-COVERAGE.md`, `PQ-STAGE-TRUTH.md`, `BOSS-ROW-DATA.md`, `WZ-REACTOR-FACTS.md`,
  `DIFFICULTY-CHAOS-PLAN.md`, `PQ-UNFINISHED-REPORT.md` (in `/root/work/`) and the e04 hand-back
  `/root/work/agent-runs/playerbot/e04/`. Branches off `release-1.6.4` `c0e0a2cd7`, none pushed or deployed: `164-opq`
  (`ffad5e817`), **`164-bossrows` (`b919dbfa4`; chaos commits still in flight, it still moves)**, `164-bossfight`
  (`83eaba4b8`), `164-ex2qa`, `164-bossproof`, `164-ex2`, `164-altinvite` (`3b546e834`), `164-pqpolish` (`d30a22702`),
  `164-all` (stale). Running: `boss-rows-impl` (Chaos Zakum, `-Dslums.chaos.zakum`, default OFF),
  `upstream-pq-boss-logic`, `pq-gaps-report`.
- **Re-run 10:40 UTC (mine):** `git -C /root/work/164-bossrows` (131 files) and `164-opq` (127 files),
  `git diff --name-only release-1.6.4...HEAD`, matched by file name against the 23 names in `s-bot181-*/BRIEF.md` and
  the ten 182 card paths: **none shared**. `BotCombatFormula` is in both (entry 7). Other five branches not re-run.
- **Note:** the *codex* lane (entry 2) edits `NPCConversationManager.java` near line 2283; 182 card b293 edits it near
  line 1679. Same file, regions about 600 lines apart; not test-merged. Hand-check when the second lands.

## 5. `docs/release/1.6.4.conf` created twice (verified overlap)

- **What:** the file does not exist on `release-1.6.4`. Two pieces of work plan to create it.
- **Sides:** (a) `/root/work/SlumsMS-164-bot181-s1/docs/release/1.6.4.conf`, untracked there (a DRAFT carrying 181's
  `-Dslums.offer.pricefloor` and `-Dslums.bots.merchantpoolstock` switches). (b) 182 brief `s-b296` ("1.6.4.conf, new,
  shaped on 1.6.3.conf") adds `STARFORCE_BOOST_PERCENTAGE2=0.2` to its `SERVER_SET=(...)` array.
- **Fact:** `ls /root/work/*/docs/release/1.6.4.conf` finds only (a); nested worktrees were not searched.
- **Action:** one owner. Whoever merges first creates it; the second only appends its own key. Tell the b296 helper.

## 6. `docs/tickets/README.md` rows (verified overlap)

- **What:** `164-bot-behaviour-audit` (181), `164-audit-ticket` (182), `164-bot-improve-ticket` (183), `164-excatalog`
  (270; diff-verified) and the new 184, 185, 186 branches each add a section to this README after the 179 block.
- **Action:** expect a textual conflict on each merge after the first. Recommended order: 181, 182, 183, 184, 185, 186,
  270, resolving by keeping every section (they are separate tables). Merge 183 after 181's train/progress branches so
  its parked-items text stays true.

## 7. 186 (external-repo adoption) vs boss/PQ lane, 181 and 183 (verified overlap on one file)

- **What:** 186 ranks useful-DPS attack scoring (rank 3, needs only `BotCombatFormula`), observation granularity (1)
  and headless quests (2).
- **Fact:** `BotCombatFormula.java` is changed by `164-bossrows`, `164-opq`, `164-bossfight` and `164-all-merge`.
  `GuildBossRuns`, `BotTickService`, `BotSM`, `BotQuestScript` are touched by none of them, and none of those names
  appears in ticket 181. Ticket 183's ledger (observation only) and e03 tiered ticking are not file-compared.
- **Also:** the boss-fight gap in 186 is owned by the boss lane (STATUS e04, "built on lane" per
  `PLAYERBOT-LANE-REPORT.md`); 186 must not start it.
- **Action:** start 186 rank 3 only after the boss lane merges `BotCombatFormula`. Start rank 1 only after the owner
  decides how it relates to e03 (already owner-approved) and 183's ledger.

## 8. Found while checking: overlaps inside 181 (verified overlap)

- `ShopOfferResponse.java` is modified (uncommitted) in both `SlumsMS-164-bot181-s1` and `-market`; `TrainingBot.java`
  in both `-train` (committed) and `-life` (uncommitted). Action: tell the 181 coordinator; agree one merge order per pair.
- `s-bot181-sweep` and 183's sweep touch the same static maps; 183 starts after it merges.

## 9. Boss/PQ lane: blocked on the owner

- **Blocked on the owner:** a TEST deploy of one boss/PQ branch (helpers cannot deploy), plus seven e04 and six
  difficulty-plan decisions (reported by the lane session, 2026-11-03 10:40 UTC / 02:40 PT; not verified by me, except
  that e04 `REPORT.md` section 5 lists seven). No branch has been played in a client; every row is simulator-proven
  except KPQ (322 TEST runs, 262 clears), per the lane session (not verified). **Action:** owner picks one branch.

## 10. Social lane `164-social-int` (35 commits, 153 files), checked 2026-11-03 (Server Dev: merge `164-social-int-renum`, tip `a226edfdf`, not `164-social-int`)

- **Clashes fixed (renum branch, two commits):** ticket 181 (also the bot-behaviour audit) is now **271** (max taken was 270); migrations `059-bot-relationship-axes` / `060-bot-memory-social` (059 = casino chip shop on 163-integration; 060/061 on `164-gear-progression-codex`) are now **062 / 063**, in that order (ledger is by id and the MIGRATIONS list is explicit, so gaps are fine; `1700-*` on 170-chaos/170-gamepass is a separate series).
- **Code overlaps NOT checked:** 181 helpers' `BotConversation`/`ReplyCleaner`, `TrainingBot`, the sweep maps; `SocialLod` vs owner-approved e03 tiered ticking; `SocialBudget`; the Bond/Tie eight-axis store vs `/root/work/agent-runs/server/relationship-window-scope.md`; marriage/@romance code vs `SocialActions`.
- **Shared files vs `release-1.6.4...164-social-int-renum` (names only, none uncommitted):** s1, market, progress, sweep, alt-bundle, marr-java, marr-refund, wedding-config, bossfight: none. bot181-social: `BotConversation.java`, `BotConversationFlowTest.java`. bot181-train: `TrainingBot.java`. bot181-life: `TrainingBot.java`, `TrainingBotLifeExecutionTest.java`. marr-scripts, excatalog: `docs/tickets/README.md`. codex: `qa-suite.md`, `AutoQaCatalog.java`, `GameAutoQaWorld.java`, `autoqa-coverage.tsv`, `AutoQaCatalogTest.java`. opq: `qa-suite.md`, `AutoQaCatalog.java`, `autoqa-coverage.tsv`, `BotConversationFlowTest.java`, `AutoQaCatalogTest.java`. bossrows: `qa-suite.md`, `AutoQaCatalog.java`, `autoqa-coverage.tsv`, `AutoQaCatalogTest.java`.

## Not verified

No merge or build was run. `s1`, `market`, `life` were read from `git status` (uncommitted). Boss-lane reports, e11/e16 beyond their titles, and behaviour-level overlaps were not read.
