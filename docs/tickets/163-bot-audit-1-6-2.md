# 163: 1.6.2 bot audit — 58 findings for the launch switch set

**Status:** open, 1.6.2. **Owner:** Server Dev. **Source:** owner, 2026-10-30: "run through and audit playerbots and
altbots on server, read only … compare the target features of 1.6.1/2 and see what is missing or unclear", then "yes
please do that its only way claude can track properly".

Full text with every anchor, the switch map, the per-ticket verdict tables and the QA coverage gaps:
`/root/work/agent-runs/server/bot-audit-162/REPORT.md` (398 lines, written by an outside agent, read-only). This ticket
is the tracking copy: 13 HIGH, 27 MEDIUM, 18 LOW. **Line anchors were read at `b010f7998`; `release-1.6.2` has since
moved (now `547b23933`, 162-makerfee), so re-grep the symbol before editing.**

Nothing here was changed by the audit: no build, no test run, no deploy, no TEST/live access. Each fix needs the usual
fail-before unit test (or an auto-QA id, which is what most of these lack) plus a smoke run before it counts.

## 1. HIGH — fix or gate before the 1.6.2 switches go on live

| # | finding | anchor | fix |
|---|---|---|---|
| 1 | Population bots hand out **max-level** party buffs for **0 MP** (`Bless`, `Haste`, `Holy Symbol`, `MW`, `Sharp Eyes`, `Hyper Body`) | `BotAttackSystem/BotBuffEffects.java:108`, `:138-146`; `BotBuffConfig.java:143-152`; `BotBuffDriver.java:122-127` | cast at the bot's real skill level, skip the skill when `level == 0`; keep `getMaxLevel()` only for the visual aura |
| 2 | A Temple step that cannot be finished is **never dropped**: `consider()` ignores `log.skipped/completed`, and `NOT_READY` has no deferral or backoff | `BotQuestSystem/TempleOfTime.java:82-93`, `:284,302,312,329,346,353`; `TrainingBot.java:545-548` | test `log.skipped(id) || log.completed(id)`; treat `ran && !completed` as deferred-with-backoff or `drop(log)` |
| 3 | "Watched" is **map-id only** → bots on other channels run real quest scripts and take real EXP/mesos/items | `GCMoveSystem/ObserverTracker.java`, `GCMovement.isMapObserved` (`:885-887`), `TrainingBot.java:514`, `BotQuests.java:462-466` | key observation on the map *instance* (mapid + channel, or the `MapleMap`) |
| 4 | KPQ instances are all named `Kerning0` → **one PQ at a time server-wide**; the lobby's `lobbyRuns=2` is really 1 and the second party is disbanded | `mechanics/MechanicsRuns.java:76`, `:119-124`; `KpqLobby.java:42,179-183`; `scripts/event/KerningPQ.js:39,115`; `EventManager.java:325-332` | key the registry by channel id + instance name, or by `eim` identity |
| 5 | Alts are **saved on the packet thread** when the owner changes channel or world-warps | `companions/OwnedAltService.java` (`ownerChangingChannel`), `OwnedAltBackend.save` | move the save+detach onto `runAsync`, as summon already does |
| 6 | `transitioning` is only cleared on arrival → a **character can be locked out of `@botme` until restart** | `OwnedAltService` reservation set | clear on failure/timeout, not only on arrival |
| 7 | A `FAILED` alt is left **in the world, untickable and unloggable** | `OwnedAltService` (`FAILED` state) | make `FAILED` terminal: detach + free the slot, or retry with a cap |
| 8 | An alt killed **inside a PQ is saved on the PQ map** and told "It got up in town"; its detach can end the owner's run | `OwnedAltCompanion.java:551-572`; `OwnedAltBackend.java:102,107-114`; `KerningPQ.js:184-189`; `BotDeaths.java:75-78`; `BotVitals.java:60-71` | warp to town *before* saving, then detach; fix the line |
| 9 | An abort in a player's party **leaves the alts inside the instance** until KPQ's 30-minute end | `MechanicsRuns.java:221` (`onEnd = null`), `:267-298`; `OwnedAltService.java:440-443`; `OwnedAltCompanion.java:529-548` | give `onEnd` a warp-out + claim release; contradicts `docs/tickets/130-bots-play-mechanics.md:213-216` |
| 10 | The brand literal **"MapleStory"** is sent to the model on every decide call, and shipped dialogue says it too | `BotBrainSystem/ChatDecide.java:384-430` | replace with a neutral phrase; grep the shipped dialogue once more |
| 11 | The 6-alts-per-map cap is **not enforced**: with >6 alts they flap fight/follow every 200 ms and the "map already has 6 alts fighting" line is re-sent ~2×/s (`s.told` is reset on every OPEN tick) | `OwnedAltCompanion.java:196-204`, `:224`, `:273-281`, `:450-451`; `OwnedAltGate.java:40,102-104` | count decided-in-this-tick alts; only reset `s.told` after the gate has been OPEN a few seconds |
| 12 | The Pareto skyline is **quadratic and its cap is applied after the loop** | `BotGearSystem/BotGearOptimizer.java:405-446` (`MAX_PARETO_STATES 2000`) | cap inside the loop; sort/select top-N incrementally |
| 13 | One "review" is **many full plans**; the count scales with the bag → ~24 s of a shared executor thread per town stop | `BotGearSystem/BotGear.java:68`, `BotGearOptimizer` plan fan-out | budget the review per tick, cache the last plan, or move it off the shared executor |

## 2. MEDIUM — the owner will see most of these in the @botme playtest

- **Alt behaviour (ticket 13):** AoE/ultimate swings ignore the 650 px owner leash (`OwnedAltCombat.java:146-149`); KS
  ordering is lost because `Monster.damageSnapshot()` carries no times (`OwnedAltCombat.java:91-111`), so an alt can
  take a population bot's mob and can hit a stranger's mob the party tagged later; one unreachable drop **parks an alt
  forever** (`OwnedAltCompanion.java:480-518`, `LootPicker.onItsLedge` is a bare 120 px band); save window races the
  tick (pickups during a save are lost); `attach` runs under the service-wide monitor; alt summons can exceed the
  5-companion cap; messenger + buddy invites are unguarded.
- **KPQ/mechanics:** a **player-led run cannot be finished** — the bots never perform the leader's stage actions
  (`Orchestrator.java:196-198`), so it burns the 30-minute budget (owner must play leader); the owner's **death is
  invisible** (`KerningPQ.js:173`); any party op tears alts out mid-run; a throw between party creation and run start
  leaks the party and the holds **forever** (`KpqLobby.java:143-186`, `MechanicsRuns.java:130-138`); a broken stage
  script floods ~720 FAILED lines per bot per run (`StepExecutor.java:37,189-211`); the prize step re-talks on a full
  bag; lobby level band is hardcoded 21–30 while the content file says 21–255.
- **Bot quests:** the real quest gate is unguarded and the throw is **swallowed with no log**
  (`BotQuests.java:431,501-503` → `TrainingBot.java:532` → `BotSM.java:339-341` → `BotTickService.java:289-292`).
- **Population-bot combat:** KS manners is enforced when a target is picked, not when it is hit
  (`BotAttackDriver.java:287,586,765-770` vs `GrindBrain.java:242-243`); a mob a player **ever** touched is theirs
  forever; keydown bursts keep dealing damage and dropping loot **after the bot dies**
  (`BotAttackEffects.java:151-177`).
- **Gear/economy:** the maker batch dead-ends permanently on one unconvertible leftover (`BotMaker.java:155-215`); a
  throwing op keeps its world-cap unit; one disassembly yields several crystals against a per-operation cap; gear is
  worn with no `canWearEquipment` re-check; gear-first ignores player occupancy; a second decide consumer hardcodes the
  model id `decide` → 404 → the feature is silently off (`BotChatRouting.java:302-342`); fame is farmable through every
  bot friend (`BotFameRequest.java:62-131`); crew mates pull each other back and forth across towns.
- **Nav:** the blocked-position watchdog can record a false GENUINE failure (`BotNavigationManager.java:234`, jump gate
  clears `lastEdgeBlockReason` on every success path).

## 3. LOW and unclear

- `gear.dexless` only constrains AP spent after the switch — every bot that exists at launch keeps its old build
  (`BotProgression.java:217-228` sets `secondaryCap` after `BotBuild.catchUp`).
- Junk sort's NPC sale bypasses `SaleRules` (`BotBagRoom.java:128-150`); `scroll.ev` uses a looser rule set than
  `ScrollRules`; `bots.apvariants` is **inert without `slums.bots.apvariants.fromSlot`**.
- `bots.partysupport` ships ON while its own javadoc says "the pose is NOT yet verified on a real client".
- Temple lane entries outlive the quest (6 h); the Temple corridor gate ignores owned alts (`TempleOfTime.java:194-197`);
  lobby slot race; no backoff on refused lobby starts; lobby ignores one-lobby-per-channel; leaked `EventInstanceManager`
  when a bot despawns inside a run; shutdown saves alts only via channel teardown.
- **Doc drift:** `slums.inspect` / `slums.killcalib` / `slums.navTrace` are documented "off on live" and ship `true`;
  `Mechanics.java:8-11` still says "nothing calls this yet"; roadmap 1.6.1's alts/KPQ/Temple rows still say "built, not
  merged, not on TEST".

## 4. Missing (not a bug, but a gap in the target set)

- **12 switches ship with no auto-QA check**: `botquests`, `temple`, `mechanics.lobby`, pareto (`gear.optimizer`),
  `gear.dexless`, `grind.gearFirst`, `junk.sort`, `scroll.ev`, `bots.apvariants`, `bots.partysupport`,
  `bots.famerequest`, `bots.magicguard`, `jobErrand`, `botSkillBooks`, both nav ports.
- **Mechanics coverage is `t-mx-1` + `t-mx-2` only**, both staged with fresh roster bots and `withGm=false`. Nothing
  covers a player party with alts, a player-led run, holds under each abort path, the lobby, an alt dying or being
  dismissed inside a PQ, an abort leaving members inside, the cross-channel collision, the Temple driver, or bot quests.
- **`t-bt-1` (bot talk tick cost) last measured FAIL — 25.3 ms against a 20 ms budget — on the pre-1.6.2 jar; re-run it
  on the 1.6.2 build.**
- **Ticket 13's "needs a real client" list is still unrecorded** everywhere: attack poses, knockback/death pose, boat
  catch-up, PQ with alts; ticket 99 names `oa-2` for the same reason. Nothing bot-related has been seen on a real client
  for 1.6.2 at all.

## 5. Owner decisions (nobody else can settle these)

1. `OWNED_ALTS_ENABLED` in live `config.yaml`: the 1.6.2 conf sets the five companion switches and
   `OWNED_ALTS_SUMMON_ENABLED`, but not this one (`ServerConfig.java:98` defaults false, `1.6.2.conf:122` claims it "stays
   true on live"). If live lacks it, `@botme` is dead while companions are on.
2. `slums.bots.apvariants.fromSlot` — unset means the AP-variant feature does nothing.
3. Does `bots.partysupport` stay ON given the unverified pose?
4. `mechanics.lobby` run cap (default 2 — really 1 until finding 4 is fixed).
5. Do alts keep gaining equip-item EXP and quest-mob counters? Current code says yes by owner decision 2026-10-26, which
   contradicts ticket 13's "EXP only through normal party play".

Closing rule: an item is done when the fix is in and a fail-before test or a named auto-QA id proves it; the audit
report's `[scout]` marks are the items I did not re-read line-by-line, so check those anchors first.
